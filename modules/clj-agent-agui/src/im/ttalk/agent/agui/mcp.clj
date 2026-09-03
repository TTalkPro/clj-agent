(ns im.ttalk.agent.agui.mcp
  "MCP（Model Context Protocol）接入 —— **可选插件**。

   对标 CopilotKit 服务端挂的两个中间件（`handlers/shared/agent-utils.ts`）：

   | 上游 | 干什么 | 这里 |
   |---|---|---|
   | `MCPMiddleware` | 把 MCP server 的工具接进工具表 | `with-tools` |
   | `MCPAppsMiddleware` | **带 UI 资源**的工具：结果额外翻成 activity 消息，前端零代码渲染 | `with-tools` + `event-transform` + `proxy-request` |

   ## 三块

   1. **客户端**（`client` / `list-tools` / `call-tool` / `read-resource`）：
      JSON-RPC 2.0 over Streamable HTTP。**不引依赖**——传输走 JDK 自带的
      `java.net.http`，而且 `:transport` 可注入（测试用纯函数，不起服务）；
   2. **工具接入**（`with-tools`）：连一次 server 列出工具，转成内联工具，
      handler 就是 `tools/call`。**这里与上游有个真实差异**，见下；
   3. **MCP Apps**（`event-transform` / `proxy-request`）：`_meta` 带
      `ui/resourceUri` 的工具，结果额外发一条 `ACTIVITY_SNAPSHOT`
      （`activityType` `mcp-apps`），前端据此把 server 提供的 UI 拉起来；
      UI 里再发起的 MCP 调用经 `forwardedProps.__proxiedMCPRequest` 回到
      `proxy-request`。

   ## 与上游的差异：谁执行 UI 工具

   上游把 UI 工具**注入**进 `tools` 但 agent 框架并不执行它们，所以中间件在
   run 结束时自己扫「没有结果的 tool call」再补执行 + 补 `TOOL_CALL_RESULT`。
   我们的内联工具本来就由循环执行（`:handler` 就是 `tools/call`），所以：

   - 不需要「扫悬空 tool call」那套补偿逻辑；
   - 工具结果**在轮内**回灌给模型（上游是 run 末尾才补，模型看不到）——
     对「查完再答」这种用法反而更对；
   - activity 消息由 `event-transform` 在看到 `:tool/result` 时发。

   ## 装

   ```clojure
   (def servers [{:type :http :url \"https://example.com/mcp\" :server-id \"demo\"}])
   (rt/runtime {:agent-fn (tools/agent-fn (mcp/with-tools spec servers))
                :event-transform (mcp/event-transform {:servers servers})})
   ```

   **连接发生在装配期**（`with-tools` 会去 `tools/list`）：server 挂了就挂了，
   宁可起服务时报错，也不要每轮对话多一次网络往返（上游 `available: \"always\"`
   那条路每次建议都要 `listTools`，它自己的注释里都在抱怨）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(def default-protocol-version
  "协议版本。SDK 的 `SUPPORTED_PROTOCOL_VERSIONS` 里这个覆盖面最广；server 会在
   `initialize` 的响应里回它自己的版本，不一致也能继续（协商而非握死）。"
  "2025-06-18")

(def client-info {:name "clj-agent-agui" :version "0.3"})

(def ui-capabilities
  "声明我们认 MCP Apps 的 UI 扩展——不声明的话 server 不会把 UI 工具给你。"
  {:extensions {(keyword "io.modelcontextprotocol/ui") {:mimeTypes ["text/html+mcp"]}}})

(def activity-type "mcp-apps")

(def ui-resource-meta-key
  "工具 `_meta` 里指向 UI 资源的键。有它 = 这是个 MCP App 工具。"
  "ui/resourceUri")

;;; ============================================================
;;; 传输：JSON-RPC 2.0 over Streamable HTTP
;;; ============================================================

(defn- parse-sse-body
  "Streamable HTTP 的响应可能是 SSE。挑出第一条能解析成 JSON 的 `data:`。"
  [body]
  (some (fn [line]
          (when (str/starts-with? line "data:")
            (try (json/parse-string (str/trim (subs line 5)) true)
                 (catch Exception _ nil))))
        (str/split-lines (str body))))

(defn http-transport
  "默认传输：`java.net.http`（**JDK 自带，不加依赖**）。

   返回 `(fn [payload] response-map-or-nil)`。会话 id（`Mcp-Session-Id`）在闭包
   里维护——`initialize` 的响应头带上它，之后每个请求都要回传，否则 server 当你
   是新连接。

   通知（没有 `:id` 的 payload）server 通常回 202 空体，返回 nil。"
  [{:keys [url headers timeout-ms protocol-version]
    :or {timeout-ms 30000 protocol-version default-protocol-version}}]
  (let [http (-> (HttpClient/newBuilder)
                 (.connectTimeout (Duration/ofMillis (long timeout-ms)))
                 (.build))
        session (atom nil)]
    (fn [payload]
      (let [b (-> (HttpRequest/newBuilder (URI/create url))
                  (.timeout (Duration/ofMillis (long timeout-ms)))
                  (.header "Content-Type" "application/json")
                  (.header "Accept" "application/json, text/event-stream")
                  (.header "MCP-Protocol-Version" protocol-version))
            _ (doseq [[k v] headers] (.header b (name k) (str v)))
            _ (when-let [s @session] (.header b "Mcp-Session-Id" s))
            req (-> b (.POST (HttpRequest$BodyPublishers/ofString
                              (json/generate-string payload)))
                    (.build))
            resp (.send http req (HttpResponse$BodyHandlers/ofString))
            status (.statusCode resp)
            ctype (str (-> resp .headers (.firstValue "content-type") (.orElse "")))
            body (.body resp)]
        (when-let [s (-> resp .headers (.firstValue "mcp-session-id") (.orElse nil))]
          (reset! session s))
        (cond
          (>= status 400)
          (throw (ex-info (str "MCP 请求失败 HTTP " status)
                          {:status status :url url :body (subs (str body) 0 (min 500 (count (str body))))}))

          (str/blank? (str body)) nil
          (str/includes? ctype "text/event-stream") (parse-sse-body body)
          :else (try (json/parse-string body true) (catch Exception _ nil)))))))

;;; ============================================================
;;; 客户端
;;; ============================================================

(defn client
  "构造一个 MCP 客户端。

   - `:url`        server 地址（用默认 HTTP 传输时必填）
   - `:transport`  `(fn [payload] response)`，可注入。**测试就靠它**——
                   一个纯函数就能把整条工具接入链跑完，不用起服务
   - `:headers`    额外请求头（鉴权）
   - `:server-id`  稳定标识；不给就按配置算哈希（前端引用 server 用它）"
  [{:keys [transport] :as opts}]
  (let [n (atom 0)]
    {:opts opts
     :transport (or transport (http-transport opts))
     :next-id #(swap! n inc)
     :state (atom {:initialized? false :server-info nil})}))

(defn request!
  "发一个 JSON-RPC 请求，返回 `result`。server 回 `error` 就抛。"
  [{:keys [transport next-id]} method params]
  (let [id (next-id)
        resp (transport (cond-> {:jsonrpc "2.0" :id id :method method}
                          params (assoc :params params)))]
    (when-let [err (:error resp)]
      (throw (ex-info (str "MCP 错误: " (:message err))
                      {:method method :code (:code err) :data (:data err)})))
    (:result resp)))

(defn notify!
  "发一个通知（没有 id，不等结果）。"
  [{:keys [transport]} method params]
  (transport (cond-> {:jsonrpc "2.0" :method method} params (assoc :params params)))
  nil)

(defn initialize!
  "握手。**幂等**：已经握过就直接返回。

   握完必须补一条 `notifications/initialized`——不发的话，讲究的 server 会拒掉
   后续所有请求。"
  [{:keys [state] :as c}]
  (if (:initialized? @state)
    (:server-info @state)
    (let [info (request! c "initialize"
                         {:protocolVersion (or (get-in c [:opts :protocol-version])
                                               default-protocol-version)
                          :capabilities ui-capabilities
                          :clientInfo client-info})]
      (notify! c "notifications/initialized" nil)
      (swap! state assoc :initialized? true :server-info info)
      info)))

(defn list-tools
  "`tools/list` → 工具数组（原样，含 `_meta`）。"
  [c]
  (initialize! c)
  (vec (:tools (request! c "tools/list" {}))))

(defn call-tool
  "`tools/call` → 结果（原样：`{:content [...] :isError bool}`）。"
  [c tool-name args]
  (initialize! c)
  (request! c "tools/call" {:name tool-name :arguments (or args {})}))

(defn read-resource
  "`resources/read` → `{:contents [...]}`。MCP Apps 的 UI 模板就是这么取的。"
  [c uri]
  (initialize! c)
  (request! c "resources/read" {:uri uri}))

(defn ping [c] (request! c "ping" {}))

;;; ============================================================
;;; 工具接入
;;; ============================================================

(defn ui-resource-uri
  "这个 MCP 工具带 UI 资源吗？带就返回那个 uri（= 它是个 MCP App 工具）。"
  [mcp-tool]
  (let [m (or (:_meta mcp-tool) (get mcp-tool "_meta"))
        v (or (get m (keyword ui-resource-meta-key)) (get m ui-resource-meta-key))]
    (when (string? v) v)))

(defn text-content
  "MCP 工具结果 → 喂回模型的字符串。

   `content` 是块数组（text / image / resource …）。取所有 text 块拼起来；
   一块 text 都没有就整个 JSON 化——**宁可给模型一坨 JSON，也别给它 nil**。"
  [result]
  (let [blocks (or (:content result) (get result "content"))
        texts (keep (fn [b] (when (= "text" (or (:type b) (get b "type")))
                              (or (:text b) (get b "text"))))
                    blocks)]
    (if (seq texts) (str/join "\n" texts) (json/generate-string blocks))))

(defn ->inline-tool
  "MCP 工具 → 内联工具 map（`:handler` 之外的键原样就是发给模型的 schema）。

   描述里追加 `[UI Resource: …]` 与上游一致：这是给**模型**的提示——「这个工具
   会画出一块界面」，它据此决定要不要再啰嗦一遍结果。"
  [c mcp-tool]
  (let [nm (or (:name mcp-tool) (get mcp-tool "name"))
        desc (or (:description mcp-tool) (get mcp-tool "description") "")
        schema (or (:inputSchema mcp-tool) (get mcp-tool "inputSchema")
                   {:type "object" :properties {}})
        ui (ui-resource-uri mcp-tool)]
    (with-meta
      {:name nm
       :description (if ui (str desc "\n[UI Resource: " ui "]") desc)
       :parameters schema
       :handler (fn [args _ctx] (text-content (call-tool c nm args)))}
      {::mcp true ::ui-resource ui ::server-id (get-in c [:opts :server-id])})))

(defn server-hash
  "server 的稳定哈希——**前端引用 server 用它**（不必知道 url）。
   与上游 `getServerHash` 同款：md5 of `{type,url,headers}`。"
  [{:keys [type url headers]}]
  (let [payload (json/generate-string
                 (cond-> {:type (name (or type :http)) :url url}
                   (= :sse type) (assoc :headers headers)))
        md (MessageDigest/getInstance "MD5")]
    (->> (.digest md (.getBytes ^String payload "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn connect-servers
  "连上每个 server，列出工具，转成内联工具。

   **一个 server 挂了不拖垮别的**（上游同款：`console.error` 然后继续）——
   但会记一条 warn，不是静默吞掉。"
  [servers]
  (reduce
   (fn [acc server]
     (try
       (let [c (client server)
             tools (mapv #(->inline-tool c %) (list-tools c))]
         (log/info "MCP server 接入:" (:url server) "工具" (count tools) "个")
         (into acc tools))
       (catch Throwable t
         (log/warn "MCP server 接不上，跳过:" (:url server) (.getMessage t))
         acc)))
   []
   servers))

(defn mcp-tool? [tool] (boolean (::mcp (meta tool))))

(defn with-tools
  "把 MCP server 的工具挂进一份 `create-agent` 配置。"
  [spec servers]
  (update spec :tools #(into (vec %) (connect-servers servers))))

;;; ============================================================
;;; MCP Apps：activity 事件
;;; ============================================================

(defn app-tools
  "工具表里哪些是**带 UI 的** → `{工具名 {:resource-uri … :server-id …}}`。"
  [tools]
  (into {}
        (keep (fn [t]
                (when-let [uri (::ui-resource (meta t))]
                  [(:name t) {:resource-uri uri :server-id (::server-id (meta t))}])))
        tools))

(defn event-transform
  "`runtime` 的 `:event-transform`：带 UI 的工具出结果时，补一条 activity 快照。

   前端收到它就去把 `resourceUri` 指向的界面拉起来（`replace: true` = 每次结果
   整块换掉）。**只认带 UI 的工具**——普通 MCP 工具就是普通工具，不该多出一块界面。

   `:apps` 由 `app-tools` 算出来（装配期就知道，不必每轮再问 server）。"
  [{:keys [apps servers]}]
  (let [hash-of (into {} (map (fn [s] [(:server-id s) (server-hash s)])) servers)]
    (fn [_run]
      (let [args-of (volatile! {})]     ;; tool-call-id -> 调用参数（快照里要带上）
        (fn [{:keys [type tool-call-id] :as ev}]
          (case type
            :tool/args (do (vswap! args-of assoc tool-call-id (:args ev)) [ev])

            :tool/result
            (if-let [app (get apps (:name ev))]
              [ev {:type :activity/snapshot
                   :message-id (str activity-type "-" tool-call-id)
                   :activity-type activity-type
                   :replace true
                   :content {:resourceUri (:resource-uri app)
                             :serverId (:server-id app)
                             :serverHash (get hash-of (:server-id app))
                             :toolInput (get @args-of tool-call-id)
                             :result (:content ev)}}]
              [ev])

            [ev]))))))

;;; ============================================================
;;; 前端 iframe 的代理通道
;;; ============================================================

(def proxy-allowed-methods
  "UI 代理**只允许这四个方法**。上游同款白名单——那块界面跑在浏览器里，
   不能让它随便调 server 的任何东西。"
  #{"tools/call" "resources/read" "notifications/message" "ping"})

(defn proxy-request
  "前端那块 MCP UI 发起的调用（`forwardedProps.__proxiedMCPRequest`）。

   `servers` 是你配的那几个；按 `serverId` 或 `serverHash` 找到是哪一个——
   **前端不知道 url**，它只有这两个引用。"
  [servers {:keys [serverId serverHash method params]}]
  (let [server (or (first (filter #(= serverId (:server-id %)) servers))
                   (first (filter #(= serverHash (server-hash %)) servers)))]
    (cond
      (nil? server)
      {:error (str "Unknown server: " (or serverId serverHash))}

      (not (contains? proxy-allowed-methods method))
      {:error (str "MCP method not allowed for UI proxy: " method)}

      :else
      (try
        (let [c (client server)]
          (case method
            "tools/call" (call-tool c (:name params) (:arguments params))
            "resources/read" (read-resource c (:uri params))
            "notifications/message" (do (notify! c "notifications/message" params)
                                        {:success true})
            "ping" (ping c)))
        (catch Throwable t
          {:error (str t)})))))
