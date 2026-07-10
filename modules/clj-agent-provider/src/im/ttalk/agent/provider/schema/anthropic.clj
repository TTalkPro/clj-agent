(ns im.ttalk.agent.provider.schema.anthropic
  "Anthropic Schema 转换

   将工具定义转换为 Anthropic API 格式。

   Anthropic 的工具格式与 OpenAI 不同：
   - 使用 input_schema 而非 parameters
   - 工具调用使用 content_block 机制

   使用示例：

   (require '[im.ttalk.agent.provider.schema.anthropic :as schema])

   ;; 转换单个工具
   (schema/tool->schema {:name :calculator
                         :description \"计算器\"
                         :parameters {...}})

   ;; 批量转换
   (schema/tools->schemas tools)"
  (:require [im.ttalk.agent.provider.common.memo :as memo]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; Schema 转换
;;; ============================================================

(defn tool->schema
  "将工具定义转换为 Anthropic tool 格式

   参数：
   - tool: 工具定义 map
     {:name :keyword
      :description \"...\"
      :parameters {...}}

   返回：
   Anthropic 格式的 tool schema
   {:name \"...\" :description \"...\" :input_schema {...}}

   示例：
   (tool->schema {:name :calculator
                  :description \"执行数学计算\"
                  :parameters {:type \"object\"
                               :properties {:expr {:type \"string\"}}}})"
  [{:keys [name description parameters]}]
  {:name (if (keyword? name) (clojure.core/name name) name)
   :description description
   :input_schema (or parameters {:type "object" :properties {}})})

(defn schema->tool
  "将 Anthropic schema 转换为工具定义

   参数：
   - schema: Anthropic schema map

   返回：
   标准工具定义

   示例：
   (schema->tool {:name \"calculator\"
                  :description \"执行计算\"
                  :input_schema {...}})"
  [{:keys [name description input_schema]}]
  {:name (keyword name)
   :description description
   :parameters input_schema})

(defn wire-tool?
  "工具是否已是 Anthropic wire 格式（带 :type 的服务端工具或带 :input_schema 的定义）"
  [tool]
  (and (map? tool)
       (or (contains? tool :type)
           (contains? tool :input_schema))))

(def ^:private convert-tools
  (memo/bounded
    (fn [tools] (mapv (fn [t] (if (wire-tool? t) t (tool->schema t))) tools))
    32))

(defn tools->schemas
  "批量转换工具定义为 Anthropic schemas

   - 简单定义 {:name :description :parameters} -> 转为 input_schema 格式
   - 已是 wire 格式（带 :type 的服务端工具、或已带 :input_schema）-> 原样透传

   参数：
   - tools: 工具定义列表

   转换结果按 tools 列表有界缓存（同 schema.openai：ReAct 循环每轮免重转）。

   返回：
   Anthropic schema 列表"
  [tools]
  (convert-tools tools))

(defn schemas->tools
  "批量转换 Anthropic schemas 为工具定义

   参数：
   - schemas: Anthropic schema 列表

   返回：
   工具定义列表"
  [schemas]
  (mapv schema->tool schemas))

;;; ============================================================
;;; 服务端内置工具构造器
;;; ============================================================

(def web-search-tool-type
  "Anthropic web_search 服务端工具的类型标识（截至当前 API 版本）"
  "web_search_20250305")

(defn web-search-tool
  "构造 Anthropic 服务端 web_search 工具定义（已是 wire 格式，可直接放入 :tools）。

   选项 opts（均可选）：
   - :max-uses        单次请求最多搜索次数（Integer）
   - :allowed-domains 仅允许这些域名（[String]）
   - :blocked-domains 屏蔽这些域名（[String]）
   - :user-location   地理位置提示（map，如 {:type \"approximate\" :country \"CN\"}）

   :allowed-domains 与 :blocked-domains 互斥（由服务端校验）。

   示例：
   (web-search-tool {:max-uses 5 :allowed-domains [\"docs.anthropic.com\"]})
   ; => {:type \"web_search_20250305\" :name \"web_search\" :max_uses 5
   ;     :allowed_domains [\"docs.anthropic.com\"]}"
  ([] (web-search-tool {}))
  ([{:keys [max-uses allowed-domains blocked-domains user-location]}]
   (cond-> {:type web-search-tool-type
            :name "web_search"}
     max-uses             (assoc :max_uses max-uses)
     (seq allowed-domains) (assoc :allowed_domains (vec allowed-domains))
     (seq blocked-domains) (assoc :blocked_domains (vec blocked-domains))
     user-location        (assoc :user_location user-location))))

;;; ============================================================
;;; Citations：可引用文档内容块
;;; ============================================================

(defn text-document
  "构造启用引用（Citations）的纯文本 document 内容块，放入 user 消息的 content 向量。

   模型在回答时会从该文档摘引，响应的 text 块带 :citations（含 cited_text /
   document_index / 字符区间等）；用 provider.anthropic/extract-citations 提取。

   参数：
   - text: 文档正文字符串
   - opts（可选）：
     - :title      文档标题（出现在引用元数据里）
     - :context    给模型的额外上下文（不被引用）
     - :citations? 是否启用引用（默认 true）
     - :media-type 默认 \"text/plain\"

   返回：
   {:type \"document\" :source {:type \"text\" :media_type .. :data ..}
    :title .. :context .. :citations {:enabled true}}

   示例：
   (text-document \"地球绕太阳公转。\" {:title \"天文常识\"})"
  ([text] (text-document text {}))
  ([text {:keys [title context citations? media-type]
          :or {citations? true media-type "text/plain"}}]
   (cond-> {:type "document"
            :source {:type "text" :media_type media-type :data text}
            :citations {:enabled citations?}}
     title   (assoc :title title)
     context (assoc :context context))))

;;; ============================================================
;;; Skills（beta）：技能容器与代码执行工具
;;;
;;; 注意：Skills / code_execution 属 Anthropic beta 功能，需在 config 设
;;; :beta（见 default-skills-beta）启用相应 anthropic-beta 头。下列 type/版本
;;; 字符串为 beta 形态，可能随官方调整 —— 必要时用各构造器的 opts 覆盖。
;;; ============================================================

(def default-skills-beta
  "启用 Skills + code_execution 所需的 anthropic-beta 头值（传给 config :beta）。
   beta 标识可能随官方更新而变化。"
  ["skills-2025-10-02" "code-execution-2025-08-25" "files-api-2025-04-14"])

(def code-execution-tool-type
  "code_execution 服务端工具类型标识（beta）"
  "code_execution_20250825")

(defn code-execution-tool
  "构造 code_execution 服务端工具（beta；Skills 通常需要它来执行）。
   可传 :type 覆盖默认 beta 类型标识。"
  ([] (code-execution-tool {}))
  ([{:keys [type]}]
   {:type (or type code-execution-tool-type) :name "code_execution"}))

(defn skill
  "构造单个 skill 引用。

   参数：
   - skill-id: 技能标识（官方预置如 \"xlsx\"/\"pptx\"/\"docx\"/\"pdf\"，或自定义上传技能的 id）
   - opts（可选）：:type（默认 \"anthropic\" 表官方预置）:version（默认不发送，由服务端取最新）

   返回：{:type \"anthropic\" :skill_id \"xlsx\"}（含 :version 当提供时）"
  ([skill-id] (skill skill-id {}))
  ([skill-id {:keys [type version]}]
   (cond-> {:type (or type "anthropic") :skill_id skill-id}
     version (assoc :version version))))

(defn skills-container
  "构造 Skills 容器（放入 config :container）。

   参数：
   - skills: skill 引用列表（用 skill 构造，或自行给 map）

   返回：{:skills [...]}

   示例：
   {:container (skills-container [(skill \"xlsx\") (skill \"pdf\")])
    :beta default-skills-beta
    :tools [(code-execution-tool)]}"
  [skills]
  {:skills (vec skills)})

