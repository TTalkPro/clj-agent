(ns im.ttalk.agent.mcp.registry
  "MCP Registry - 状态管理层

   管理工具、资源和提示词的注册与查询。
   此模块提供纯粹的状态管理，不涉及 MCP 协议处理逻辑。

   主要功能：
   - 创建和管理 MCPRegistry 实例
   - 工具的注册、注销和查询
   - 资源的注册和查询
   - 提示词的注册和查询
   - 与 clj-agent 工具系统的集成")

;; =============================================================================
;; Registry 记录
;; =============================================================================

(defrecord MCPRegistry [name version tools-atom resources-atom prompts-atom])

;; =============================================================================
;; 创建 Registry
;; =============================================================================

(defn create-registry
  "创建 MCPRegistry 实例

   参数:
   - config: 配置 map
     {:name \"服务名称\"
      :version \"版本号\"}

   返回: MCPRegistry 实例

   示例:
   (def registry (create-registry {:name \"my-tools\" :version \"1.0.0\"}))"
  [{:keys [name version]
    :or {name "clj-agent-mcp"
         version "1.0.0"}}]
  (->MCPRegistry name
                 version
                 (atom {})     ;; tools
                 (atom {})     ;; resources
                 (atom {})))   ;; prompts

;; =============================================================================
;; 服务器信息
;; =============================================================================

(defn registry-info
  "获取 registry 信息

   参数:
   - registry: MCPRegistry 实例

   返回: 服务器信息 map
   {:name \"...\" :version \"...\"}"
  [registry]
  {:name (:name registry)
   :version (:version registry)})

(defn registry-capabilities
  "获取 registry 能力信息

   参数:
   - registry: MCPRegistry 实例

   返回: 能力 map
   {:tools true/false :resources true/false :prompts true/false}"
  [registry]
  {:tools (boolean (seq @(:tools-atom registry)))
   :resources (boolean (seq @(:resources-atom registry)))
   :prompts (boolean (seq @(:prompts-atom registry)))})

;; =============================================================================
;; 工具管理
;; =============================================================================

(defn register-tool
  "注册工具

   参数:
   - registry: MCPRegistry 实例
   - tool: 工具定义
     {:name \"工具名\"
      :description \"描述\"
      :inputSchema {:type \"object\" :properties {...}}
      :handler (fn [args] ...)}

   返回: registry（支持链式调用）

   示例:
   (register-tool registry
     {:name \"calculator\"
      :description \"执行数学计算\"
      :inputSchema {:type \"object\"
                    :properties {:expression {:type \"string\"}}}
      :handler (fn [{:keys [expression]}]
                 (str (eval (read-string expression))))})"
  [registry tool]
  (let [tool-name (:name tool)
        mcp-tool {:name tool-name
                  :description (:description tool)
                  :inputSchema (or (:inputSchema tool)
                                   (:parameters tool)
                                   {:type "object" :properties {}})}]
    (swap! (:tools-atom registry)
           assoc tool-name (assoc mcp-tool :handler (:handler tool))))
  registry)

(defn register-tools
  "批量注册工具

   参数:
   - registry: MCPRegistry 实例
   - tools: 工具定义列表

   返回: registry"
  [registry tools]
  (doseq [tool tools]
    (register-tool registry tool))
  registry)

(defn unregister-tool
  "注销工具

   参数:
   - registry: MCPRegistry 实例
   - tool-name: 工具名称

   返回: registry"
  [registry tool-name]
  (swap! (:tools-atom registry) dissoc tool-name)
  registry)

(defn get-tool
  "获取单个工具定义

   参数:
   - registry: MCPRegistry 实例
   - tool-name: 工具名称

   返回: 工具定义 map 或 nil"
  [registry tool-name]
  (get @(:tools-atom registry) tool-name))

(defn list-tools
  "列出所有已注册的工具

   参数:
   - registry: MCPRegistry 实例

   返回: 工具定义列表（不含 handler）"
  [registry]
  (->> @(:tools-atom registry)
       vals
       (map #(dissoc % :handler))
       vec))

(defn get-tools-map
  "获取工具 map（包含 handler）

   参数:
   - registry: MCPRegistry 实例

   返回: {tool-name -> tool-def} map"
  [registry]
  @(:tools-atom registry))

;; =============================================================================
;; 资源管理
;; =============================================================================

(defn register-resource
  "注册资源

   参数:
   - registry: MCPRegistry 实例
   - resource: 资源定义
     {:uri \"file:///path\"
      :name \"名称\"
      :description \"描述\"
      :mime-type \"text/plain\"
      :reader (fn [uri] {:uri uri :text \"内容\"})}

   返回: registry"
  [registry resource]
  (let [uri (:uri resource)
        mcp-resource {:uri uri
                      :name (:name resource)
                      :description (:description resource)
                      :mimeType (or (:mime-type resource) "text/plain")}]
    (swap! (:resources-atom registry)
           assoc uri (assoc mcp-resource :reader (:reader resource))))
  registry)

(defn register-resources
  "批量注册资源

   参数:
   - registry: MCPRegistry 实例
   - resources: 资源定义列表

   返回: registry"
  [registry resources]
  (doseq [resource resources]
    (register-resource registry resource))
  registry)

(defn unregister-resource
  "注销资源

   参数:
   - registry: MCPRegistry 实例
   - uri: 资源 URI

   返回: registry"
  [registry uri]
  (swap! (:resources-atom registry) dissoc uri)
  registry)

(defn get-resource
  "获取单个资源定义

   参数:
   - registry: MCPRegistry 实例
   - uri: 资源 URI

   返回: 资源定义 map 或 nil"
  [registry uri]
  (get @(:resources-atom registry) uri))

(defn list-resources
  "列出所有已注册的资源

   参数:
   - registry: MCPRegistry 实例

   返回: 资源定义列表（不含 reader）"
  [registry]
  (->> @(:resources-atom registry)
       vals
       (map #(dissoc % :reader))
       vec))

;; =============================================================================
;; 提示词管理
;; =============================================================================

(defn register-prompt
  "注册提示词模板

   参数:
   - registry: MCPRegistry 实例
   - prompt: 提示词定义
     {:name \"模板名\"
      :description \"描述\"
      :arguments [{:name \"arg1\" :description \"...\" :required true}]
      :generator (fn [args] {:messages [...]})}

   返回: registry"
  [registry prompt]
  (let [prompt-name (:name prompt)
        mcp-prompt {:name prompt-name
                    :description (:description prompt)
                    :arguments (or (:arguments prompt) [])}]
    (swap! (:prompts-atom registry)
           assoc prompt-name (assoc mcp-prompt :generator (:generator prompt))))
  registry)

(defn register-prompts
  "批量注册提示词

   参数:
   - registry: MCPRegistry 实例
   - prompts: 提示词定义列表

   返回: registry"
  [registry prompts]
  (doseq [prompt prompts]
    (register-prompt registry prompt))
  registry)

(defn unregister-prompt
  "注销提示词

   参数:
   - registry: MCPRegistry 实例
   - prompt-name: 提示词名称

   返回: registry"
  [registry prompt-name]
  (swap! (:prompts-atom registry) dissoc prompt-name)
  registry)

(defn get-prompt
  "获取单个提示词定义

   参数:
   - registry: MCPRegistry 实例
   - prompt-name: 提示词名称

   返回: 提示词定义 map 或 nil"
  [registry prompt-name]
  (get @(:prompts-atom registry) prompt-name))

(defn list-prompts
  "列出所有已注册的提示词

   参数:
   - registry: MCPRegistry 实例

   返回: 提示词定义列表（不含 generator）"
  [registry]
  (->> @(:prompts-atom registry)
       vals
       (map #(dissoc % :generator))
       vec))

;; =============================================================================
;; clj-agent 工具集成
;; =============================================================================

(defn register-clj-agent-tool
  "将 clj-agent 工具注册到 registry

   参数:
   - registry: MCPRegistry 实例
   - tool: clj-agent 工具定义 {:name :description :parameters :handler}

   返回: registry"
  [registry tool]
  (register-tool registry
    {:name (name (:name tool))
     :description (:description tool)
     :inputSchema (or (:parameters tool)
                      {:type "object" :properties {}})
     :handler (:handler tool)}))

(defn register-clj-agent-tools
  "将 clj-agent 工具列表注册到 registry

   参数:
   - registry: MCPRegistry 实例
   - tools: clj-agent 工具列表

   返回: registry

   示例:
   (require '[im.ttalk.agent.tools.api :as tools])
   (def tool-registry (tools/create-tool-registry))
   (register-clj-agent-tools registry (tools/registry-list-tools tool-registry))"
  [registry tools]
  (doseq [tool tools]
    (register-clj-agent-tool registry tool))
  registry)

;; =============================================================================
;; 工具函数
;; =============================================================================

(defn clear-all
  "清除所有注册的工具、资源和提示词

   参数:
   - registry: MCPRegistry 实例

   返回: registry"
  [registry]
  (reset! (:tools-atom registry) {})
  (reset! (:resources-atom registry) {})
  (reset! (:prompts-atom registry) {})
  registry)

(defn stats
  "获取 registry 统计信息

   参数:
   - registry: MCPRegistry 实例

   返回: 统计 map {:tools-count N :resources-count N :prompts-count N}"
  [registry]
  {:tools-count (count @(:tools-atom registry))
   :resources-count (count @(:resources-atom registry))
   :prompts-count (count @(:prompts-atom registry))})
