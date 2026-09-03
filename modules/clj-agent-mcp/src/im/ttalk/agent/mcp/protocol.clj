(ns im.ttalk.agent.mcp.protocol
  "MCP 协议的**纯数据层**：方法名、`_meta` 键、错误码、版本、能力、信封构造与解析。

   **零 I/O、零状态**——传输在 `mcp.transport`，会话与回退策略在 `mcp.client`，
   服务端派发在 `mcp.server`。本 ns 只回答「这条消息长什么样」。

   ## 两个时代（本 ns 同时认识，因为编解码是共用的）

   规范 `2026-07-28`（SEP-2575 + SEP-2567）把 MCP 改成了**无状态**协议：

   | | legacy（≤ 2025-11-25） | modern（≥ 2026-07-28） |
   |---|---|---|
   | 开场 | `initialize` 握手 + `notifications/initialized` | **没有握手**，`server/discover` 可选 |
   | 版本/能力/身份 | 握手时协商一次，之后靠会话记住 | **每条请求自带**，放在 `params._meta` 里 |
   | HTTP 会话 | `Mcp-Session-Id` | **没有会话**；`Mcp-Method` / `Mcp-Name` 变必填头 |
   | 结果 | 直接是 result 对象 | 多一个 `resultType`（`complete` / `input_required`） |
   | roots / sampling / logging | 正常特性 | **全部 deprecated**（SEP-2577） |

   「服务器不得从上一条请求推断任何东西」是 modern 的核心不变量——所以
   `request` 每次都把版本与能力塞进 `_meta`，这不是冗余，是协议要求。

   ## 一处**必须**照做否则静默出错的地方

   modern 的 HTTP 传输要求 `MCP-Protocol-Version` 头与 `_meta` 里的版本**一致**，
   不一致 server 回 `-32020 HeaderMismatch`。所以「往信封里写版本」和「往头里写
   版本」必须是同一个值的两次投影，不能各写各的——`transport` 那侧因此从信封里
   反读版本（`protocol-version-of`），而不是自己记一份。"
  (:require [cheshire.core :as json]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 版本
;;; ============================================================

(def version-2026-07 "2026-07-28")
(def version-2025-11 "2025-11-25")
(def version-2025-06 "2025-06-18")
(def version-2025-03 "2025-03-26")
(def version-2024-11 "2024-11-05")

(def latest-version
  "本实现首选的版本。modern 时代从这一版开始。"
  version-2026-07)

(def modern-versions
  "走**每请求 `_meta`** 的版本（无 `initialize` 握手）。"
  #{version-2026-07})

(def legacy-versions
  "走 `initialize` 握手的版本，**从新到旧**——回退时按这个顺序挑。"
  [version-2025-11 version-2025-06 version-2025-03 version-2024-11])

(defn modern?
  "这个版本是 modern 时代的吗？"
  [v] (contains? modern-versions v))

(defn best-legacy-version
  "从 server 报的 `supported` 列表里挑一个**我们也支持**的 legacy 版本；
   挑不出就退到 `2025-11-25`（legacy 里最新的那个，`initialize` 本来就会再协商一次）。"
  [supported]
  (let [s (set supported)]
    (or (first (filter s legacy-versions)) version-2025-11)))

;;; ============================================================
;;; 方法名
;;; ============================================================

(def method-names
  "全部方法名。**字符串常量集中一处**——散在各处拼错了不会报错，只会收到
   `-32601 MethodNotFound`，而那看起来像「server 不支持」。"
  {:discover            "server/discover"
   :initialize          "initialize"
   :initialized         "notifications/initialized"
   :ping                "ping"
   :tools-list          "tools/list"
   :tools-call          "tools/call"
   :resources-list      "resources/list"
   :resources-read      "resources/read"
   :resources-templates "resources/templates/list"
   :resources-subscribe "resources/subscribe"
   :prompts-list        "prompts/list"
   :prompts-get         "prompts/get"
   :completion-complete "completion/complete"
   :elicitation-create  "elicitation/create"
   :subscriptions-listen "subscriptions/listen"
   ;; 以下三个 2026-07-28 起 deprecated（SEP-2577），只为连老 server 保留
   :logging-set-level   "logging/setLevel"
   :roots-list          "roots/list"
   :sampling-create     "sampling/createMessage"})

(defn method
  "取方法名；给了未知关键字**当场抛**（装配期错误好过运行期 -32601）。"
  [k]
  (or (get method-names k)
      (throw (ex-info "未知的 MCP 方法" {:key k :known (sort (keys method-names))}))))

;;; ============================================================
;;; `_meta` 键
;;; ============================================================

(def meta-protocol-version "io.modelcontextprotocol/protocolVersion")
(def meta-client-info      "io.modelcontextprotocol/clientInfo")
(def meta-client-caps      "io.modelcontextprotocol/clientCapabilities")
(def meta-server-info      "io.modelcontextprotocol/serverInfo")
(def meta-log-level        "io.modelcontextprotocol/logLevel")
(def meta-subscription-id  "io.modelcontextprotocol/subscriptionId")
(def meta-progress-token   "progressToken")

(def ui-meta-key
  "MCP Apps 扩展：工具 `_meta` 里那个 **`ui` 对象**的键。

   规范（`/extensions/apps/overview`）写的是 **`_meta.ui.resourceUri`**——
   一个嵌套对象，不是扁平的 `ui/resourceUri` 那种写法。同一个对象里还可能有 `csp`
   （app 能加载哪些外部源）与 `permissions`（麦克风/摄像头之类）。

   猜错这个键**不会报错**，只会「明明是 App 工具却没画出界面」——所以这里写死，
   并在 `mcp.tools/ui-resource-uri` 里同时兼容那个扁平写法。"
  "ui")

(def ui-extension-id
  "MCP Apps 扩展在 `capabilities.extensions` 里的标识。"
  "io.modelcontextprotocol/ui")

;;; ============================================================
;;; 错误码
;;; ============================================================

(def error-codes
  "JSON-RPC 标准码 + MCP 在 `-32020..-32099` 这段里定义的码。

   **`-32000..-32019` 是历史遗留段**，规范明令新实现不得再分配；
   `-32002`（资源不存在）只在读老 server 时接受，自己不发。"
  {:parse-error       -32700
   :invalid-request   -32600
   :method-not-found  -32601
   :invalid-params    -32602
   :internal-error    -32603
   ;; MCP 2026-07-28
   :header-mismatch                    -32020
   :missing-required-client-capability -32021
   :unsupported-protocol-version       -32022})

(def ^:private code->key
  (into {} (map (fn [[k v]] [v k])) error-codes))

(defn error-key
  "错误码 → 关键字（认不出就 nil）。"
  [code] (get code->key code))

(def sep-2575-error-codes
  "**modern 时代的三个「你确实在跟新 server 说话」的信号**。

   双时代回退的判据全在这三个码上：收到它们说明对面**懂** modern，只是这条请求
   有问题（版本不支持 / 能力不够 / 信封头不匹配）——**不该回退到 `initialize`**，
   回退也治不好。收到**别的**任何 JSON-RPC 错误，才说明对面是 legacy server。
   （规范 PR #2844 明确要求回退不得只认单个错误码。）"
  #{(:header-mismatch error-codes)
    (:missing-required-client-capability error-codes)
    (:unsupported-protocol-version error-codes)})

(defn sep-2575-error?
  [code] (contains? sep-2575-error-codes code))

;;; ============================================================
;;; 能力
;;; ============================================================

(def default-client-capabilities
  "我们作为 client 声明的能力。

   **只报真支持的**：`elicitation` 我们能接（`mcp.client` 有回调挂点），
   `roots` / `sampling` 是 2026-07-28 起 deprecated 的特性，不报。
   `extensions` 里报 MCP Apps——它只是说「UI 资源我认得」，渲染在前端。"
  {:elicitation {}
   :extensions {ui-extension-id {:mimeTypes ["text/html;profile=mcp-app"]}}})

(defn client-info
  "`Implementation`：自报家门。协议明说它**不做安全判断**，只用于显示与排查。"
  ([] (client-info "clj-agent-mcp" "0.3"))
  ([name version] {:name name :version version}))

;;; ============================================================
;;; 信封
;;; ============================================================

(defn- meta-of
  "组 modern 的 per-request `_meta`。

   `protocolVersion` 与 `clientCapabilities` 是**必填**——缺了 server MUST 回
   `-32602`。`clientInfo` 是 SHOULD，我们默认带上。"
  [{:keys [protocol-version client-info client-capabilities log-level extra-meta]}]
  (cond-> {meta-protocol-version protocol-version
           meta-client-caps (or client-capabilities default-client-capabilities)}
    client-info (assoc meta-client-info client-info)
    log-level   (assoc meta-log-level (name log-level))
    (seq extra-meta) (merge extra-meta)))

(defn request
  "组一条 JSON-RPC 请求。

   `opts`：
   - `:id`                 请求 id（必填；协议禁止 null，也禁止重复未回的 id）
   - `:protocol-version`   **给了就写 `_meta`**（modern 信封），传 nil 就不写（legacy）
   - `:client-info` / `:client-capabilities` / `:log-level` / `:extra-meta`

   **「写不写 `_meta`」由调用方决定，不在这里按版本号猜**：时代是 client 判出来的
   （见 `mcp.client`），这里只负责照做。曾经在这儿加过一道 `(modern? version)` 的
   守卫，结果是**探测一个我们没列进 `modern-versions` 的新版本时，`_meta` 被静默
   丢掉**——请求于是变成 legacy 形状，server 按老语义服务，双方都不报错。

   **legacy 不写 `_meta`**：老 server 见到陌生的 `_meta` 未必报错，但可能把它
   当成参数的一部分（`initialize` 那条路最容易出这种事），没必要冒险。"
  [method-name params {:keys [id protocol-version] :as opts}]
  (when (nil? id) (throw (ex-info "JSON-RPC 请求必须有 id" {:method method-name})))
  {"jsonrpc" "2.0"
   "id" id
   "method" method-name
   "params" (cond-> (or params {})
              protocol-version (assoc "_meta" (meta-of opts)))})

(defn notification
  "组一条通知（**没有 id**，对方 MUST NOT 回）。"
  ([method-name] (notification method-name nil nil))
  ([method-name params] (notification method-name params nil))
  ([method-name params {:keys [protocol-version] :as opts}]
   (cond-> {"jsonrpc" "2.0" "method" method-name}
     (or params protocol-version)
     (assoc "params" (cond-> (or params {})
                       protocol-version (assoc "_meta" (meta-of opts)))))))

(defn result-response
  "服务端：成功响应。

   **`resultType` 是 2026-07-28 新增的必填字段**；对 legacy 客户端不发——
   它们不认识，而规范说客户端把「缺省」当作 `complete`。"
  ([id result] (result-response id result nil))
  ([id result {:keys [protocol-version server-info result-type]}]
   {"jsonrpc" "2.0"
    "id" id
    "result" (cond-> (or result {})
               (and protocol-version (modern? protocol-version))
               (assoc "resultType" (or result-type "complete"))
               server-info
               (update "_meta" #(assoc (or % {}) meta-server-info server-info)))}))

(defn error-response
  "服务端：错误响应。`code` 可以是关键字（查 `error-codes`）或整数。"
  ([id code message] (error-response id code message nil))
  ([id code message data]
   {"jsonrpc" "2.0"
    "id" id
    "error" (cond-> {"code" (if (keyword? code) (get error-codes code) code)
                     "message" message}
              (some? data) (assoc "data" data))}))

(defn unsupported-version-error
  "`-32022`：带上**我们支持的版本列表**，客户端据此重挑一个重试。
   不带 `supported` 的话客户端只能猜——规范因此把它写成 data 的必备内容。"
  [id requested supported]
  (error-response id :unsupported-protocol-version "Unsupported protocol version"
                  {"supported" (vec supported) "requested" requested}))

(defn missing-capability-error
  "`-32021`：告诉客户端**缺哪几个能力**，否则它不知道该补什么。"
  [id required]
  (error-response id :missing-required-client-capability
                  "Missing required client capability"
                  {"requiredCapabilities" (vec required)}))

;;; ============================================================
;;; 解析
;;; ============================================================

(defn response-error
  "响应里的错误（没有就 nil）。返回 `{:code :message :data}`。"
  [resp]
  (when-let [e (or (:error resp) (get resp "error"))]
    {:code (or (:code e) (get e "code"))
     :message (or (:message e) (get e "message"))
     :data (or (:data e) (get e "data"))}))

(defn response-result
  "响应里的 result（没有就 nil）。"
  [resp]
  (or (:result resp) (get resp "result")))

(defn result-type
  "结果类型。**缺省当 `complete`**——老 server 不发这个字段，规范明确要求这样兜底。"
  [result]
  (or (:resultType result) (get result "resultType") "complete"))

(defn input-required?
  "MRTR：server 说「还差点信息」（`resultType = input_required`）。"
  [result] (= "input_required" (result-type result)))

(defn protocol-version-of
  "从**已组好的请求信封**里反读版本——HTTP 传输要拿它写 `MCP-Protocol-Version`
   头。反读而不是各记一份，是因为头与信封不一致 server 会回 `-32020`。"
  [msg]
  (get-in msg ["params" "_meta" meta-protocol-version]))

(defn routing-name
  "Streamable HTTP 的 `Mcp-Name` 头取哪个值。

   规范只对三个方法要求：`tools/call` / `prompts/get` 取 `params.name`，
   `resources/read` 取 `params.uri`。别的方法不发这个头。"
  [msg]
  (let [m (get msg "method")
        p (get msg "params")]
    (cond
      (#{"tools/call" "prompts/get"} m) (get p "name")
      (= "resources/read" m) (get p "uri")
      :else nil)))

(defn parse
  "JSON 文本 → 消息 map（键保持**字符串**，与信封构造侧一致）。解析失败返回 nil。"
  [s]
  (try (json/parse-string s) (catch Exception _ nil)))

(defn encode
  "消息 map → JSON 文本。"
  [msg] (json/generate-string msg))
