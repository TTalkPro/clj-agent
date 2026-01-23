(ns im.ttalk.agent.tools.impl.simple
  "SimpleTool - 简单工具实现

   最基础的 ITool 实现，用于本地函数工具。

   使用示例：

   (def calc-tool
     (make-tool
       :calculator
       \"执行数学计算\"
       {:type \"object\"
        :properties {:expression {:type \"string\"}}
        :required [\"expression\"]}
       (fn [{:keys [expression]}]
         (eval (read-string expression)))))

   (tool-execute calc-tool {:expression \"(+ 1 2)\"})"
  (:require [im.ttalk.agent.tools.protocol :as proto]
            [clojure.string :as str]))

;;; ============================================================
;;; SimpleTool Record
;;; ============================================================

(defrecord SimpleTool [name description parameters handler category metadata]
  proto/ITool

  ;; -------------------- 基本信息 --------------------

  (tool-name [_] name)

  (tool-description [_] description)

  (tool-parameters [_] parameters)

  (tool-category [_] (or category :general))

  (tool-metadata [_] (or metadata {}))

  ;; -------------------- 执行 --------------------

  (tool-execute [_ args]
    (try
      {:success true :result (handler args)}
      (catch Exception e
        {:success false :error (.getMessage e)})))

  (tool-validate [_ args]
    ;; 基础验证：检查必需参数
    (let [required (set (map keyword (get parameters :required [])))
          missing (filter #(nil? (get args %)) required)]
      (if (empty? missing)
        {:valid true :errors []}
        {:valid false
         :errors (mapv #(str "Missing required parameter: " (clojure.core/name %)) missing)})))

  ;; -------------------- Schema 转换 --------------------

  (tool-to-schema [this]
    (proto/tool-to-schema this :generic))

  (tool-to-schema [_ format]
    (let [tool-name-str (if (keyword? name) (clojure.core/name name) (str name))
          params (or parameters {:type "object" :properties {}})]
      (case format
        :anthropic
        {:name tool-name-str
         :description description
         :input_schema params}

        :openai
        {:type "function"
         :function {:name tool-name-str
                    :description description
                    :parameters params}}

        ;; :generic (default)
        {:name name
         :description description
         :parameters params
         :category (or category :general)}))))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn make-tool
  "创建简单工具

   参数:
   - name:        工具名称 (keyword 或 string)
   - description: 工具描述
   - parameters:  参数规范 (JSON Schema)
   - handler:     处理函数 (fn [args-map] -> result)
   - opts:        可选参数
     - :category  分类 (keyword)
     - :metadata  元数据 (map)

   返回: SimpleTool 实例

   示例:
   (make-tool :greet \"问候\" {:type \"object\" ...}
              (fn [{:keys [name]}] (str \"Hello, \" name))
              :category :demo)"
  [name description parameters handler & {:keys [category metadata]}]
  (->SimpleTool
    (if (keyword? name) name (keyword name))
    description
    parameters
    handler
    category
    metadata))

(defn from-map
  "从 map 创建 SimpleTool

   参数:
   - m: {:name :description :parameters :handler :category :metadata}

   返回: SimpleTool 实例

   示例:
   (from-map {:name :calc
              :description \"计算\"
              :parameters {...}
              :handler calc-fn})"
  [{:keys [name description parameters handler category metadata]}]
  (make-tool name description parameters handler
             :category category
             :metadata metadata))

;;; ============================================================
;;; 便捷构造函数
;;; ============================================================

(defn make-simple-tool
  "创建无参数验证的简单工具

   参数:
   - name:        工具名称
   - description: 描述
   - handler:     处理函数

   返回: SimpleTool 实例"
  [name description handler]
  (make-tool name description {:type "object" :properties {}} handler))

(defn make-string-tool
  "创建接受单个字符串参数的工具

   参数:
   - name:        工具名称
   - description: 描述
   - param-name:  参数名称
   - param-desc:  参数描述
   - handler:     处理函数 (fn [string] -> result)

   返回: SimpleTool 实例"
  [name description param-name param-desc handler]
  (let [param-key (keyword param-name)]
    (make-tool name description
               {:type "object"
                :properties {param-name {:type "string"
                                         :description param-desc}}
                :required [param-name]}
               (fn [args] (handler (get args param-key))))))

;;; ============================================================
;;; 工具转换
;;; ============================================================

(defn tool-map->simple-tool
  "将旧格式的工具 map 转换为 SimpleTool

   参数:
   - tool-map: {:name :description :parameters :handler :category :permissions :metadata}

   返回: SimpleTool 实例

   用于兼容旧的 registry 格式"
  [{:keys [name description parameters handler category permissions metadata]}]
  (make-tool name description parameters handler
             :category category
             :metadata (merge metadata
                              (when permissions {:permissions permissions}))))

(defn simple-tool->tool-map
  "将 SimpleTool 转换为旧格式的工具 map

   参数:
   - tool: SimpleTool 实例

   返回: 工具 map

   用于兼容旧的 registry 格式"
  [tool]
  {:name (proto/tool-name tool)
   :description (proto/tool-description tool)
   :parameters (proto/tool-parameters tool)
   :handler (:handler tool)
   :category (proto/tool-category tool)
   :metadata (proto/tool-metadata tool)})
