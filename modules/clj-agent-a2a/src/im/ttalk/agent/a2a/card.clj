(ns im.ttalk.agent.a2a.card
  "A2A Agent Card 模块

   提供 Agent Card 的创建、管理和序列化功能。
   Agent Card 用于 A2A 协议的服务发现。"
  (:require [im.ttalk.agent.a2a.types :as types]
            [clojure.string :as str]))

;; =============================================================================
;; Skill 生成
;; =============================================================================

(defn extract-tags
  "从描述中提取标签

   参数:
   - description: 描述文本

   返回: 标签列表"
  [description]
  (when description
    (->> (str/split (str/lower-case description) #"[\s,.:;!?]+")
         (filter #(> (count %) 3))
         (take 5)
         vec)))

(defn tool->skill
  "将工具定义转换为 Skill

   参数:
   - tool: 工具定义
     {:name \"tool-name\"
      :description \"描述\"
      :parameters {...}}

   返回: Skill map"
  [tool]
  (let [name (if (keyword? (:name tool))
               (name (:name tool))
               (:name tool))
        description (:description tool)]
    (types/create-skill
      {:id name
       :name (str/replace name #"[-_]" " ")
       :description description
       :tags (extract-tags description)
       :examples []
       :input-modes ["text/plain" "application/json"]
       :output-modes ["text/plain" "application/json"]})))

(defn tools->skills
  "将工具列表转换为 Skills

   参数:
   - tools: 工具定义列表

   返回: Skill 列表"
  [tools]
  (mapv tool->skill tools))

;; =============================================================================
;; Agent Card Registry
;; =============================================================================

(defrecord AgentCardRegistry [card-atom tools-atom])

(defn create-card-registry
  "创建 Agent Card 注册表

   参数:
   - opts: 基础 Agent Card 选项
     {:name \"agent-name\"
      :description \"描述\"
      :url \"https://...\"
      :version \"1.0.0\"
      :provider {...}
      :capabilities {...}}

   返回: AgentCardRegistry 实例"
  [opts]
  (let [base-card (types/create-agent-card opts)]
    (->AgentCardRegistry (atom base-card)
                         (atom []))))

(defn get-card
  "获取当前 Agent Card

   参数:
   - registry: AgentCardRegistry 实例

   返回: AgentCard map"
  [registry]
  (let [base-card @(:card-atom registry)
        tools @(:tools-atom registry)
        skills (tools->skills tools)]
    (assoc base-card :skills skills)))

(defn update-card!
  "更新 Agent Card 基础信息

   参数:
   - registry: AgentCardRegistry 实例
   - update-fn: 更新函数

   返回: 更新后的 AgentCard map"
  [registry update-fn]
  (swap! (:card-atom registry) update-fn))

(defn set-url!
  "设置 Agent URL

   参数:
   - registry: AgentCardRegistry 实例
   - url: 新 URL

   返回: 更新后的 AgentCard map"
  [registry url]
  (update-card! registry #(assoc % :url url)))

(defn set-capabilities!
  "设置 Agent 能力

   参数:
   - registry: AgentCardRegistry 实例
   - capabilities: 能力 map

   返回: 更新后的 AgentCard map"
  [registry capabilities]
  (update-card! registry #(update % :capabilities merge capabilities)))

;; =============================================================================
;; 工具注册
;; =============================================================================

(defn register-tool!
  "注册工具（用于生成 skills）

   参数:
   - registry: AgentCardRegistry 实例
   - tool: 工具定义

   返回: registry"
  [registry tool]
  (swap! (:tools-atom registry) conj tool)
  registry)

(defn register-tools!
  "批量注册工具

   参数:
   - registry: AgentCardRegistry 实例
   - tools: 工具定义列表

   返回: registry"
  [registry tools]
  (doseq [tool tools]
    (register-tool! registry tool))
  registry)

(defn clear-tools!
  "清除所有已注册的工具

   参数:
   - registry: AgentCardRegistry 实例

   返回: registry"
  [registry]
  (reset! (:tools-atom registry) [])
  registry)

;; =============================================================================
;; Agent Card 序列化
;; =============================================================================

(defn card->json
  "将 Agent Card 转换为 JSON 友好格式

   参数:
   - card: AgentCard map

   返回: JSON 友好的 map"
  [card]
  card)  ;; 已经是 JSON 友好格式

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn simple-agent-card
  "创建简单的 Agent Card

   参数:
   - name: Agent 名称
   - description: 描述
   - url: A2A 端点 URL

   返回: AgentCard map"
  [name description url]
  (types/create-agent-card
    {:name name
     :description description
     :url url}))

(defn agent-card-with-tools
  "创建带工具的 Agent Card

   参数:
   - opts: Agent Card 选项
   - tools: 工具列表

   返回: AgentCard map"
  [opts tools]
  (let [card (types/create-agent-card opts)
        skills (tools->skills tools)]
    (assoc card :skills skills)))

;; =============================================================================
;; Discovery 端点
;; =============================================================================

(def well-known-path "/.well-known/agent.json")

(defn discovery-response
  "生成发现端点响应

   参数:
   - registry: AgentCardRegistry 实例

   返回: Ring 响应 map"
  [registry]
  {:status 200
   :headers {"Content-Type" "application/json"
             "Cache-Control" "public, max-age=3600"}
   :body (get-card registry)})
