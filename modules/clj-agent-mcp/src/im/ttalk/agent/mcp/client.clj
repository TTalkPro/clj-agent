(ns im.ttalk.agent.mcp.client
  "MCP 客户端：**双时代**。

   ```clojure
   (def c (client {:url \"https://example.com/mcp\"}))     ;; 或 {:command [\"npx\" …]}
   (connect! c)                                            ;; 幂等；自动判时代
   (list-tools c)
   (call-tool c \"get_weather\" {:city \"北京\"})
   (close! c)
   ```

   ## 判时代的算法（照规范 PR #2844 与官方 SDK 的实现写）

   先按 **modern** 发一条 `server/discover`，然后看回来的是什么：

   | 回来的 | 判定 | 为什么 |
   |---|---|---|
   | `DiscoverResult`，`supportedVersions` 含我们这版 | **modern**，结束 | 对面是新 server |
   | `DiscoverResult`，但不含我们这版 | 挑一个双方都支持的：还有 modern 版就换版重试，只剩 legacy 就退回 `initialize` | 它懂 discover，只是版本对不上 |
   | `-32022` UnsupportedProtocolVersion | 同上（按 `data.supported` 重挑） | 这是**新 server 的信号**，不是老 server |
   | `-32021` / `-32020` | **原样抛，不回退** | 能力不够 / 信封头不匹配——退回 `initialize` 也治不好，只会把真正的错误藏起来 |
   | 其他任何 JSON-RPC 错误 | 回退 `initialize` | `-32601`（没这方法）、`-32602`（被 `_meta` 搞晕）、`-32700`…… 都说明对面是老 server。**判据不能只认一个码**，这是规范明写的 |
   | HTTP 400 / 404 且响应体里没有可识别的 modern 错误 | 回退 | 老 server 在 HTTP 层就把无会话的 POST 拒了（404 = 它要 `Mcp-Session-Id`） |
   | 超时没回 | 回退 | stdio 上老 server 见到陌生方法可能干脆不吭声 |

   **时代是 server 的属性，不是单条请求的**：判一次，记在 client 上，后续请求
   都按那个时代走。

   ## 一处**没做**、且要说清楚的

   **server → client 的请求（elicitation / MRTR 的补充输入）只在 stdio 上到得了**：
   stdio 的读线程会把它交给 `:on-notification`；而 HTTP 那侧，这类请求是从
   POST 响应的 SSE 流里回来的，本模块的 HTTP 传输只取流里第一条 `data:`
   （见 `mcp.transport`），所以 HTTP 上收不到。
   `input_required` 的结果**原样返回**（`protocol/input-required?` 可判），
   调用方要接就自己接——不替想象中的用户把 MRTR 的整套状态机写出来。"
  (:require [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.transport :as t]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 错误
;;; ============================================================

(defn mcp-error
  "server 回了 JSON-RPC 错误。`ex-data` 带 `:code` / `:message` / `:data`。"
  [{:keys [code message data]} method]
  (ex-info (str "MCP 错误 " code ": " message)
           {::error true :code code :message message :data data :method method}))

(defn mcp-error?
  [t] (boolean (some-> t ex-data ::error)))

(defn error-code
  "从异常里取 MCP 错误码（不是 MCP 错误就 nil）。"
  [t] (when (mcp-error? t) (:code (ex-data t))))

;;; ============================================================
;;; 构造
;;; ============================================================

(defn client
  "构造客户端（**不连接**——连接在 `connect!`，因为判时代要发请求）。

   传输三选一：
   - `:transport`  直接给一个 `Transport`（或**任意 `(fn [msg] resp)`**）
   - `:url`        Streamable HTTP
   - `:command`    stdio 子进程

   其余：
   - `:client-info`      自报家门，缺省 `clj-agent-mcp`
   - `:capabilities`     声明的客户端能力，缺省 `protocol/default-client-capabilities`
   - `:protocol-version` **钉死**版本（给了就不做时代探测，直接按它走）
   - `:server-id`        本地标识，日志与工具归属用
   - `:headers` / `:timeout-ms` / `:env` / `:dir` / `:on-notification` 透传给传输"
  [{:keys [transport url command] :as opts}]
  (let [tp (cond
             transport transport
             url (t/http-transport opts)
             command (t/stdio-transport opts)
             :else (throw (ex-info "client 需要 :transport / :url / :command 之一" {})))]
    {:opts opts
     :transport tp
     :next-id (let [n (atom 0)] #(swap! n inc))
     ;; 一个 client 一个状态：时代、协商出来的版本、server 自述
     :state (atom {:era nil
                   :protocol-version (:protocol-version opts)
                   :capabilities nil
                   :server-info nil
                   :instructions nil})}))

(defn- envelope-opts
  "组信封要的那几个键。legacy 时代 `:protocol-version` 传 nil ——
   `protocol/request` 据此决定写不写 `_meta`。"
  [c]
  (let [{:keys [era protocol-version]} @(:state c)
        o (:opts c)]
    {:protocol-version (when (= :modern era) protocol-version)
     :client-info (or (:client-info o) (p/client-info))
     :client-capabilities (or (:capabilities o) p/default-client-capabilities)}))

(defn- send-request!
  "发一条请求并**把错误抛出来**。`version` 显式传（探测阶段还没有 era）。"
  [c method-name params {:keys [protocol-version] :as env}]
  (let [msg (p/request method-name params
                       (assoc env :id ((:next-id c))
                              :protocol-version protocol-version))
        resp (t/send! (:transport c) msg)]
    (if-let [err (p/response-error resp)]
      (throw (mcp-error err method-name))
      (p/response-result resp))))

;;; ============================================================
;;; 连接：判时代
;;; ============================================================

(defn- probe-modern
  "发一条 modern 的 `server/discover`。

   返回 `[:ok result]` / `[:retry version]` / `[:legacy supported]` / `[:throw ex]`。
   **判据集中在这一个函数里**——散开写的话，「哪些错该回退」这条规则会在三处
   各长一份，然后慢慢长歪。"
  [c version]
  (let [env {:protocol-version version
             :client-info (or (get-in c [:opts :client-info]) (p/client-info))
             :client-capabilities (or (get-in c [:opts :capabilities])
                                      p/default-client-capabilities)}]
    (try
      (let [r (send-request! c (p/method :discover) {} env)
            supported (or (get r "supportedVersions") (:supportedVersions r) [])]
        (cond
          (some #{version} supported) [:ok r]
          ;; 它懂 discover，只是不支持我们这版：还有别的 modern 版就换一版再来
          (some p/modern? supported) [:retry (first (filter p/modern? supported))]
          :else [:legacy supported]))

      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)
              ;; 错误可能来自两处：JSON-RPC 信封里的 error，或 HTTP 非 2xx 的响应体
              code (or (:code d) (get-in d [:jsonrpc-error "error" "code"]))
              data (or (:data d) (get-in d [:jsonrpc-error "error" "data"]))
              supported (or (get data "supported") (:supported data))]
          (cond
            (= code (:unsupported-protocol-version p/error-codes))
            (if-let [v (first (filter p/modern? (or supported [])))]
              [:retry v]
              [:legacy (or supported [])])

            ;; **不回退**：这两个说明对面就是 modern，只是我们发的东西它不收
            (p/sep-2575-error? code) [:throw e]

            ;; 其余一律当 legacy：JSON-RPC 错误、HTTP 400/404、超时、连不上
            :else [:legacy nil]))))))

(defn- initialize!
  "legacy 握手：`initialize` + `notifications/initialized`。

   **那条通知不能省**：讲究的 server 在收到它之前会拒掉后续所有请求。"
  [c version]
  (let [o (:opts c)
        r (send-request! c (p/method :initialize)
                         {"protocolVersion" version
                          "capabilities" (or (:capabilities o) p/default-client-capabilities)
                          "clientInfo" (or (:client-info o) (p/client-info))}
                         {:protocol-version nil})]
    (t/send! (:transport c) (p/notification (p/method :initialized)))
    ;; server 回的版本才算数（它可能降级到一个我们也支持的旧版）
    (let [negotiated (or (get r "protocolVersion") version)]
      (swap! (:state c) assoc
             :era :legacy
             :protocol-version negotiated
             :capabilities (get r "capabilities")
             :server-info (get r "serverInfo")
             :instructions (get r "instructions"))
      @(:state c))))

(defn connect!
  "连接并判时代。**幂等**——连过了直接返回状态。

   返回 `{:era :modern|:legacy :protocol-version :capabilities :server-info :instructions}`。"
  [c]
  (let [st @(:state c)]
    (if (:era st)
      st
      (let [pinned (get-in c [:opts :protocol-version])]
        (if (and pinned (not (p/modern? pinned)))
          ;; 钉死了一个 legacy 版本 = 明说「走握手」，不必探测
          (initialize! c pinned)
          (loop [version (or pinned p/latest-version)
                 tries 0]
            (let [[tag v] (probe-modern c version)]
              (case tag
                :ok (do (swap! (:state c) assoc
                               :era :modern
                               :protocol-version version
                               :capabilities (or (get v "capabilities") {})
                               :server-info (get-in v ["_meta" p/meta-server-info])
                               :instructions (get v "instructions"))
                        @(:state c))
                ;; 换一版再试。**次数封顶**：server 反复推荐一个我们不认的版本时，
                ;; 没有上限就是一个礼貌的死循环
                :retry (if (< tries 2)
                         (recur v (inc tries))
                         (initialize! c (p/best-legacy-version nil)))
                :legacy (initialize! c (p/best-legacy-version v))
                :throw (throw v)))))))))

(defn ensure-connected!
  "没连就连。所有特性方法的第一句。"
  [c] (connect! c) c)

(defn era [c] (:era @(:state c)))
(defn protocol-version [c] (:protocol-version @(:state c)))
(defn server-capabilities [c] (:capabilities @(:state c)))
(defn server-info [c] (:server-info @(:state c)))
(defn instructions
  "server 自述的用法说明——**适合塞进 system prompt**，规范就是这么建议的。"
  [c] (:instructions @(:state c)))

(defn close! [c] (t/close! (:transport c)))

;;; ============================================================
;;; 请求
;;; ============================================================

(defn request!
  "发一条请求（自动补时代与信封）。`method-key` 是 `protocol/method-names` 的键。"
  ([c method-key] (request! c method-key {}))
  ([c method-key params]
   (ensure-connected! c)
   (send-request! c (p/method method-key) params (envelope-opts c))))

(def ^:private max-pages
  "分页上限。**不是防御性机器**：`nextCursor` 由对面给，server 有 bug 就是无限循环，
   而循环里每一圈都是一次网络往返。撞上了记一条 warn，不静默截断。"
  100)

(defn- list-all
  "翻页取全部。`k` 是结果里装数组的键（`\"tools\"` / `\"resources\"` / `\"prompts\"`）。"
  [c method-key k]
  (loop [cursor nil acc [] page 0]
    (let [r (request! c method-key (cond-> {} cursor (assoc "cursor" cursor)))
          items (into acc (or (get r k) []))
          next-cursor (get r "nextCursor")]
      (cond
        (nil? next-cursor) items
        (>= (inc page) max-pages)
        (do (log/warn "MCP" (name method-key) "分页超过" max-pages "页，已停止翻页")
            items)
        :else (recur next-cursor items (inc page))))))

(defn list-tools
  "`tools/list` → 工具数组（**原样**，含 `_meta` / `outputSchema` / `annotations`）。"
  [c] (list-all c :tools-list "tools"))

(defn call-tool
  "`tools/call` → 结果原样：`{content, structuredContent?, isError?}`。

   **不替调用方解释 `isError`**：那是「工具自己失败了」，与「协议出错」是两回事
   （后者会抛）。要文本用 `mcp.tools/result-text`。"
  [c tool-name args]
  (request! c :tools-call {"name" tool-name "arguments" (or args {})}))

(defn list-resources [c] (list-all c :resources-list "resources"))
(defn list-resource-templates [c] (list-all c :resources-templates "resourceTemplates"))

(defn read-resource
  "`resources/read` → `{contents [...]}`。"
  [c uri] (request! c :resources-read {"uri" uri}))

(defn list-prompts [c] (list-all c :prompts-list "prompts"))

(defn get-prompt
  "`prompts/get` → `{description?, messages [...]}`。"
  ([c prompt-name] (get-prompt c prompt-name nil))
  ([c prompt-name args]
   (request! c :prompts-get (cond-> {"name" prompt-name}
                              (seq args) (assoc "arguments" args)))))

(defn ping [c] (request! c :ping {}))
