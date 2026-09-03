(ns im.ttalk.agent.mcp.server
  "MCP 服务端：把一组**内联工具**（以及资源、提示词）暴露成 MCP server。

   ```clojure
   (def s (server {:name \"clj-agent\" :version \"0.3\"
                   :instructions \"这台机器上的天气与文件工具。\"
                   :tools [{:name \"get_weather\"
                            :description \"查天气\"
                            :parameters {:type \"object\"
                                         :properties {:city {:type \"string\"}}
                                         :required [\"city\"]}
                            :handler (fn [args _ctx] (str \"晴，\" (get args \"city\")))}]}))

   (stdio-server! s)        ;; 阻塞：把 stdin/stdout 当传输
   (handle-message s msg)   ;; 或者自己接到别的传输上——这是个**纯函数**
   ```

   ## 分层：本 ns **不碰 web**

   核心是 `handle-message`：一条进、一条出（通知返回 nil），没有 I/O、没有会话。
   把它接到 Ring / http-kit / 别的什么上，是调用方几行的事，也是 `design-principles`
   §2 划的那条线（HTTP 服务端依赖不进库）。stdio 循环留在模块里——它不碰 web。

   ## 双时代

   同一个 `handle-message` 同时服务两种客户端，靠**请求自己的形状**分流：

   | 请求 | 判定 | 依据 |
   |---|---|---|
   | `params._meta` 里有 `protocolVersion` | **modern**，无状态服务 | 规范的兼容矩阵：带 modern `_meta` 的请求按本版语义处理 |
   | `method = initialize` | **legacy**，回一份 `InitializeResult` | 同上：`initialize` 选择 legacy 语义 |
   | 两者都不是 | 当 legacy 请求处理 | 我们是无状态的，本来也不靠会话记东西 |

   modern 侧的三条校验（错一条就静默少一半功能，所以都做实）：

   1. `_meta.protocolVersion` 与 `_meta.clientCapabilities` **必填**，缺 → `-32602`；
   2. 版本不认识 → `-32022`，且 `data.supported` 必须**列出我们支持的版本**，
      客户端要靠它重挑；
   3. HTTP 的 `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` 头要与信封一致，
      不一致 → `-32020`。头只有传输层看得见，所以这条由 `check-headers`
      提供，web 层在调 `handle-message` 之前先过一遍。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.mcp.protocol :as p]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

(def supported-versions
  "我们**同时**支持的版本，从新到旧。modern 一个、legacy 两个。
   `server/discover` 与 `-32022` 的 `data.supported` 都报这一份，不能各报各的。"
  [p/version-2026-07 p/version-2025-11 p/version-2025-06])

;;; ============================================================
;;; 构造
;;; ============================================================

(defn server
  "构造一个 server（**纯值**，没有连接、没有线程）。

   - `:name` / `:version`  自报家门（`Implementation`）
   - `:instructions`       用法说明，客户端可能塞进 system prompt
   - `:tools`              内联工具数组 `{:name :description :parameters :handler}`，
                           `:handler` 是 `(fn [args ctx] result)`——**与 clj-agent 的
                           内联工具同形**，所以现成的工具直接放进来就能暴露出去
   - `:resources`          `[{:uri :name :description :mimeType :read (fn [uri] contents)}]`
   - `:prompts`            `[{:name :description :arguments :get (fn [args] messages)}]`
   - `:ctx`                传给工具 handler 的第二个参数（缺省 `{}`）
   - `:expose-error-messages?` 工具抛异常时，把**异常消息**也回给客户端（缺省 `false`）

   ## 为什么异常消息默认不外泄

   server 的对面是**外部客户端**，而异常消息里常有路径、内部结构、上游 URL 与
   request-id。缺省只回**异常类名**（够模型判断「该不该换个说法重试」），完整消息
   记进 server 日志。本地开发要看细节，把 `:expose-error-messages?` 打开——
   这是一次**显式**的取舍，不是偷偷做的决定。"
  [{:keys [name version tools resources prompts instructions ctx expose-error-messages?]
    :or {name "clj-agent-mcp" version "0.3"}}]
  {:info {:name name :version version}
   :instructions instructions
   :expose-error-messages? (boolean expose-error-messages?)
   :ctx (or ctx {})
   :tools (vec tools)
   :resources (vec resources)
   :prompts (vec prompts)
   :tools-by-name (into {} (map (fn [t] [(clojure.core/name (:name t)) t])) tools)
   :resources-by-uri (into {} (map (juxt :uri identity)) resources)
   :prompts-by-name (into {} (map (fn [t] [(clojure.core/name (:name t)) t])) prompts)})

(defn capabilities
  "只报**真有东西**的那几位。空的工具表还报 `tools` 是谎报能力位——客户端会照着
   去 `tools/list`，拿回一个空数组，然后不知道该怪谁。"
  [s]
  (cond-> {}
    (seq (:tools s)) (assoc :tools {})
    (seq (:resources s)) (assoc :resources {})
    (seq (:prompts s)) (assoc :prompts {})))

;;; ============================================================
;;; 结果构造
;;; ============================================================

(defn- text-content [s] [{"type" "text" "text" (str s)}])

(defn- tool-result
  "工具返回值 → `CallToolResult`。

   字符串 → 一块 text；map / 向量 → 既给 `structuredContent` 也给一块 text
   （老客户端只认 content，新客户端优先读 structured——两个都给才两边都对）。"
  [v]
  (cond
    (string? v) {"content" (text-content v)}
    (nil? v) {"content" (text-content "")}
    (or (map? v) (sequential? v))
    {"content" (text-content (p/encode v)) "structuredContent" v}
    :else {"content" (text-content v)}))

(defn- tool-error-result
  "工具**自己失败了** → `isError: true`，**不是** JSON-RPC 错误。

   这条区分是协议要求的，也确实有用：协议错误是「我们之间说话出了问题」，
   工具错误是「活没干成」——后者要回给模型让它换个说法再试，前者不该到模型那儿。"
  [msg] {"content" (text-content msg) "isError" true})

;;; ============================================================
;;; 派发
;;; ============================================================

(defn- tool->wire
  [t]
  (cond-> {"name" (name (:name t))
           "description" (or (:description t) "")
           "inputSchema" (or (:parameters t) (:input_schema t)
                             {"type" "object" "properties" {}})}
    (:output-schema t) (assoc "outputSchema" (:output-schema t))
    (:_meta t) (assoc "_meta" (:_meta t))))

(defn- resource->wire
  [r]
  (cond-> {"uri" (:uri r) "name" (or (:name r) (:uri r))}
    (:description r) (assoc "description" (:description r))
    (:mimeType r) (assoc "mimeType" (:mimeType r))))

(defn- prompt->wire
  [p*]
  (cond-> {"name" (name (:name p*))}
    (:description p*) (assoc "description" (:description p*))
    (:arguments p*) (assoc "arguments" (:arguments p*))))

(defn- discover-result
  "`server/discover`：**服务端 MUST 实现**。客户端靠它一次问清「你支持哪些版本、
   有什么能力」，从而不需要握手。

   `ttlMs` 与 `cacheScope` 是必填字段；缺省给「立刻过期 + 私有」——**不缓存**是
   安全的默认，声明可缓存却变了才是坑。"
  [s]
  (cond-> {"supportedVersions" supported-versions
           "capabilities" (capabilities s)
           "ttlMs" 0
           "cacheScope" "private"}
    (:instructions s) (assoc "instructions" (:instructions s))))

(defn- call-tool!
  [s params]
  (let [nm (get params "name")
        args (or (get params "arguments") {})
        tool (get (:tools-by-name s) nm)]
    (if-not tool
      ;; 工具不存在是**协议错误**（客户端叫了个不存在的名字），不是工具失败
      {::error [:invalid-params (str "未知工具: " nm)]}
      (try
        (tool-result ((:handler tool) args (:ctx s)))
        (catch Throwable t
          ;; 完整消息**只进日志**；回给客户端的默认只有类名（见 `server` 的 docstring）
          (log/warn "MCP server 工具抛异常:" nm (.getMessage t))
          (tool-error-result
           (if (:expose-error-messages? s)
             (str "工具执行失败: " (.getName (class t)) ": " (.getMessage t))
             (str "工具执行失败: " (.getName (class t)) "（详情见 server 日志）"))))))))

(defn- read-resource!
  [s params]
  (let [uri (get params "uri")
        r (get (:resources-by-uri s) uri)]
    (if-not r
      ;; 规范：资源不存在用 `-32602`（老版本的 `-32002` 已作废，不再发）
      {::error [:invalid-params (str "未知资源: " uri)]}
      {"contents" [(cond-> {"uri" uri}
                     (:mimeType r) (assoc "mimeType" (:mimeType r))
                     true (assoc "text" (str ((:read r) uri))))]})))

(defn- get-prompt!
  [s params]
  (let [nm (get params "name")
        pr (get (:prompts-by-name s) nm)]
    (if-not pr
      {::error [:invalid-params (str "未知提示词: " nm)]}
      (cond-> {"messages" ((:get pr) (or (get params "arguments") {}))}
        (:description pr) (assoc "description" (:description pr))))))

(defn- dispatch
  "方法 → 结果 map，或 `{::error [code message]}`。**不关心时代**——时代只影响
   信封（`_meta` 校验与 `resultType`），不影响每个方法做什么。"
  [s method params]
  (condp = method
    "server/discover"  (discover-result s)
    "ping"             {}
    "tools/list"       {"tools" (mapv tool->wire (:tools s))}
    "tools/call"       (call-tool! s params)
    "resources/list"   {"resources" (mapv resource->wire (:resources s))}
    "resources/read"   (read-resource! s params)
    "prompts/list"     {"prompts" (mapv prompt->wire (:prompts s))}
    "prompts/get"      (get-prompt! s params)
    {::error [:method-not-found (str "未知方法: " method)]}))

;;; ============================================================
;;; 信封校验
;;; ============================================================

(defn check-headers
  "HTTP 层用：头与信封是否一致。一致返回 nil，不一致返回一条 `-32020` 错误响应。

   **只有传输层看得见头**，所以这条校验不能藏在 `handle-message` 里；但规则属于
   协议，所以也不能让每个 web 层各写一遍。折中就是这个函数：web 层拿到请求后
   先过一遍它，再把 body 交给 `handle-message`。

   `headers` 是**小写键**的 map（HTTP 头大小写不敏感，Ring 也给小写）。"
  [msg headers]
  (let [id (get msg "id")
        h (fn [k] (get headers k))
        pv (p/protocol-version-of msg)
        method (get msg "method")
        rname (p/routing-name msg)
        mismatch (cond
                   (and pv (h "mcp-protocol-version") (not= pv (h "mcp-protocol-version")))
                   (str "MCP-Protocol-Version 头 (" (h "mcp-protocol-version")
                        ") 与信封里的版本 (" pv ") 不一致")

                   (and method (h "mcp-method") (not= method (h "mcp-method")))
                   (str "Mcp-Method 头 (" (h "mcp-method") ") 与 method (" method ") 不一致")

                   (and rname (h "mcp-name") (not= rname (h "mcp-name")))
                   (str "Mcp-Name 头 (" (h "mcp-name") ") 与目标 (" rname ") 不一致"))]
    (when mismatch
      (p/error-response id :header-mismatch mismatch))))

(defn- modern-request?
  "带 modern `_meta` 的请求。"
  [msg] (some? (p/protocol-version-of msg)))

(defn- validate-modern
  "modern 侧的必填校验。过了返回 nil，没过返回一条错误响应。"
  [msg]
  (let [id (get msg "id")
        m (get-in msg ["params" "_meta"])
        pv (get m p/meta-protocol-version)
        caps (get m p/meta-client-caps)]
    (cond
      (not (contains? m p/meta-client-caps))
      (p/error-response id :invalid-params
                        (str "缺少必填的 _meta/" p/meta-client-caps))

      (not (map? caps))
      (p/error-response id :invalid-params
                        (str "_meta/" p/meta-client-caps " 必须是对象"))

      (not (some #{pv} supported-versions))
      (p/unsupported-version-error id pv supported-versions))))

;;; ============================================================
;;; 入口
;;; ============================================================

(defn- initialize-result
  "legacy 握手的回执。**版本要协商**：客户端要的版本我们支持就用它，
   否则给我们最新的 legacy 版——不能默默按自己的版本回，客户端会按回执行事。"
  [s params]
  (let [requested (get params "protocolVersion")
        negotiated (if (some #{requested} supported-versions)
                     requested
                     p/version-2025-11)]
    (cond-> {"protocolVersion" negotiated
             "capabilities" (capabilities s)
             "serverInfo" (:info s)}
      (:instructions s) (assoc "instructions" (:instructions s)))))

(defn handle-message
  "**一条进、一条出**的纯函数。通知返回 nil（协议禁止给通知回响应）。

   modern 请求走无状态语义并带上 `resultType` / `_meta.serverInfo`；
   legacy 请求（`initialize` 或没有 `_meta` 的）按老语义回。"
  [s msg]
  (let [id (get msg "id")
        method (get msg "method")
        params (or (get msg "params") {})]
    (cond
      ;; 通知：不回
      (nil? id) nil

      (nil? method)
      (p/error-response id :invalid-request "缺少 method")

      ;; legacy 握手
      (= method "initialize")
      (p/result-response id (initialize-result s params))

      (modern-request? msg)
      (or (validate-modern msg)
          (let [pv (p/protocol-version-of msg)
                r (dispatch s method params)]
            (if-let [[code message] (::error r)]
              (p/error-response id code message)
              (p/result-response id r {:protocol-version pv
                                       :server-info (:info s)}))))

      :else
      ;; 没有 `_meta` 也不是 initialize：按 legacy 请求服务（我们本来就无状态）
      (let [r (dispatch s method params)]
        (if-let [[code message] (::error r)]
          (p/error-response id code message)
          (p/result-response id r))))))

;;; ============================================================
;;; stdio 服务端
;;; ============================================================

(defn stdio-server!
  "把 stdin/stdout 当传输跑起来（**阻塞**，直到 stdin 关闭）。

   两条必须守住的：

   1. **响应只写 stdout，日志只写 stderr**。往 stdout 打一行日志，对面的解析器就
      废了——这是 stdio server 最常见的翻车方式；
   2. **一行一条，不能有裸换行**。`p/encode` 出来的 JSON 本身不含换行。"
  ([s] (stdio-server! s *in* *out*))
  ([s in out]
   (let [r (java.io.BufferedReader. (if (instance? java.io.Reader in)
                                      ^java.io.Reader in
                                      (java.io.InputStreamReader. ^java.io.InputStream in "UTF-8")))
         w (java.io.BufferedWriter. (if (instance? java.io.Writer out)
                                      ^java.io.Writer out
                                      (java.io.OutputStreamWriter. ^java.io.OutputStream out "UTF-8")))]
     (loop []
       (when-let [line (.readLine r)]
         (when-not (str/blank? line)
           (let [msg (p/parse line)
                 resp (if msg
                        (handle-message s msg)
                        (p/error-response nil :parse-error "不是合法的 JSON"))]
             (when resp
               (.write w ^String (p/encode resp))
               (.newLine w)
               (.flush w))))
         (recur))))))
