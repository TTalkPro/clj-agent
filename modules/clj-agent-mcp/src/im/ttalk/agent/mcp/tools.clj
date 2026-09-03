(ns im.ttalk.agent.mcp.tools
  "把 MCP server 的工具接成 clj-agent 的**内联工具**。

   ```clojure
   (def servers [{:url \"https://example.com/mcp\" :server-id \"demo\"}
                 {:command [\"npx\" \"-y\" \"@modelcontextprotocol/server-filesystem\" \"/tmp\"]}])

   (create-agent (update spec :tools into (connect-servers servers)))
   ```

   产出是**普通 map**（`{:name :description :parameters :handler}`），所以本 ns
   ——以及整个 `clj-agent-mcp` 模块——不需要 require 任何 clj-agent 的东西。
   谁要用谁 conj 进 `:tools`。

   ## 连接发生在**装配期**

   `connect-servers` 会真的去连、去 `tools/list`。取舍与理由：server 挂了就在起
   服务时报错（这里是记 warn 跳过那一个），**不要每轮对话多一次 `tools/list` 的
   网络往返**。代价是「server 中途新增了工具」要重启才看得见——`notifications/
   tools/list_changed` 那条路等有人真要再说。

   ## MCP Apps

   工具的 `_meta` 里带 `io.modelcontextprotocol/ui-resource-uri` 说明它会画一块
   界面。这里只做两件事：**在描述里告诉模型**「这个工具会出界面」，以及把 uri
   记在工具的 metadata 上。真正把界面拉起来是前端的事（`agui.mcp` 那半边）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.protocol :as p]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(defn result-text
  "`tools/call` 的结果 → 喂回模型的字符串。

   `content` 是块数组（text / image / resource / resource_link…）。取所有 text 块
   拼起来；**一块 text 都没有就整个 JSON 化**——宁可给模型一坨 JSON，也别给它 nil。

   `structuredContent` 优先：2025-06-18 起工具可以给结构化结果，有它就用它
   （模型更好读，也免得 text 块只是它的一份字符串复读）。"
  [result]
  (let [structured (or (get result "structuredContent") (:structuredContent result))
        blocks (or (get result "content") (:content result))
        texts (keep (fn [b] (when (= "text" (or (get b "type") (:type b)))
                              (or (get b "text") (:text b))))
                    blocks)]
    (cond
      (some? structured) (json/generate-string structured)
      (seq texts) (str/join "\n" texts)
      (seq blocks) (json/generate-string blocks)
      :else "")))

(defn ui-resource-uri
  "这个 MCP 工具带 UI 资源吗？带就返回那个 uri（= 它是个 MCP App 工具）。

   规范的位置是 **`_meta.ui.resourceUri`**（嵌套对象）。同时兼容扁平写法
   `_meta` 里直接挂 `ui/resourceUri` ——那是本仓早期实现按猜测写的键，外面也可能
   有 server 这么发。**认多一种不会错认**：两处都没有就不是 App 工具。"
  [mcp-tool]
  (let [m (or (get mcp-tool "_meta") (:_meta mcp-tool))
        ui (or (get m p/ui-meta-key) (get m (keyword p/ui-meta-key)))
        v (or (get ui "resourceUri") (:resourceUri ui)
              (get m "ui/resourceUri") (get m (keyword "ui/resourceUri")))]
    (when (string? v) v)))

(defn server-hash
  "server 的稳定哈希——**前端引用某个 server 用它**，不必知道 url。
   与上游 `getServerHash` 同款：md5 of `{type,url}`。"
  [{:keys [url command]}]
  (let [payload (json/generate-string (if url
                                        {:type "http" :url url}
                                        {:type "stdio" :command (vec command)}))
        md (MessageDigest/getInstance "MD5")]
    (->> (.digest md (.getBytes ^String payload "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn ->inline-tool
  "MCP 工具 → 内联工具 map。

   `:parameters` 直接用 server 给的 `inputSchema`（JSON Schema 2020-12）——
   不翻译、不校验：翻译一遍只会引入我们自己的 bug，而模型看的就是这份 schema。

   描述里追加 `[UI Resource: …]` 与上游一致：这是给**模型**的提示——「这个工具会
   画出一块界面」，它据此决定要不要再啰嗦一遍结果。"
  [c mcp-tool]
  (let [nm (or (get mcp-tool "name") (:name mcp-tool))
        desc (or (get mcp-tool "description") (:description mcp-tool) "")
        schema (or (get mcp-tool "inputSchema") (:inputSchema mcp-tool)
                   {:type "object" :properties {}})
        ui (ui-resource-uri mcp-tool)]
    (with-meta
      {:name nm
       :description (if ui (str desc "\n[UI Resource: " ui "]") desc)
       :parameters schema
       :handler (fn [args _ctx] (result-text (mc/call-tool c nm args)))}
      {::mcp true
       ::ui-resource ui
       ::server-id (or (get-in c [:opts :server-id])
                       (server-hash (:opts c)))
       ::client c
       ::raw mcp-tool})))

(defn mcp-tool? [tool] (boolean (::mcp (meta tool))))
(defn tool-ui-resource [tool] (::ui-resource (meta tool)))
(defn tool-server-id [tool] (::server-id (meta tool)))
(defn tool-client [tool] (::client (meta tool)))

(defn app-tools
  "工具集里带 UI 资源的那些（MCP Apps）。"
  [tools] (filterv #(and (mcp-tool? %) (tool-ui-resource %)) tools))

(defn connect-servers
  "连上每个 server、列出工具、转成内联工具。

   **一个 server 挂了不拖垮别的**：记一条 warn 然后跳过——不是静默吞掉，也不是
   让整个 agent 起不来。返回值是所有成功接入的工具。"
  [servers]
  (reduce
   (fn [acc spec]
     (try
       (let [c (mc/client spec)
             _ (mc/connect! c)
             tools (mapv #(->inline-tool c %) (mc/list-tools c))]
         (log/info "MCP server 接入:" (or (:url spec) (first (:command spec)))
                   "时代" (name (mc/era c)) "工具" (count tools) "个")
         (into acc tools))
       (catch Throwable t
         (log/warn "MCP server 接不上，跳过:" (or (:url spec) (first (:command spec)))
                   (.getMessage t))
         acc)))
   []
   servers))

(defn with-tools
  "把 MCP 工具并进一份 agent 配置的 `:tools`。**一层包装，不是配置项**。"
  [spec servers]
  (update spec :tools into (connect-servers servers)))
