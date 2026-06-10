(ns im.ttalk.agent.tool
  "工具函数定义宏

   deftool 宏同时生成：
   1. 普通 Clojure 函数（可直接调用）
   2. 完整的 tool schema 元数据（用于 LLM Function Calling）

   参数声明使用向量格式: [名称 类型 描述]
   支持可选参数: [名称 类型 描述 :default 默认值]
   支持敏感标记: {:sensitive true}
   支持 context: {:context true}

   使用示例:

   ;; 基本用法
   (deftool get-weather
     \"获取指定城市的天气信息\"
     [[city :string \"城市名称\"]]
     (fetch-weather city))

   ;; 带可选参数
   (deftool calculate
     \"执行数学计算\"
     [[expression :string \"数学表达式\"]
      [precision :int \"小数精度\" :default 2]]
     (compute expression precision))

   ;; 标记敏感工具
   (deftool delete-file
     \"删除文件\"
     [[path :string \"文件路径\"]]
     {:sensitive true}
     (io/delete-file path))

   ;; 带 tags 的工具
   (deftool get-weather
     \"获取天气\"
     [[city :string \"城市名称\"]]
     {:tags [:weather :external-api :read-only]}
     (fetch-weather city))

   ;; 需要 context 的工具
   (deftool save-note
     \"保存笔记\"
     [[content :string \"内容\"]]
     {:context true}
     ;; ctx 是自动绑定的 context 变量
     (let [notes (context/get-var ctx :notes [])]
       {:result (str \"已保存\")
        :context (context/set-var ctx :notes (conj notes content))}))

   ;; 无参数函数
   (deftool get-time
     \"获取当前时间\"
     []
     (str (java.time.LocalDateTime/now)))"
  (:require [clojure.set]))

;;; ============================================================
;;; 类型映射：Clojure 关键字 → JSON Schema 类型
;;; ============================================================

(def ^:private type-mapping
  "Clojure 类型关键字到 JSON Schema 类型字符串的映射表"
  {:string  "string"
   :str     "string"
   :int     "integer"
   :integer "integer"
   :long    "integer"
   :float   "number"
   :double  "number"
   :number  "number"
   :num     "number"
   :bool    "boolean"
   :boolean "boolean"
   :array   "array"
   :list    "array"
   :vec     "array"
   :object  "object"
   :map     "object"})

(defn- clj-type->json-type
  "将 Clojure 类型关键字转换为 JSON Schema 类型字符串

   未知类型默认映射为 \"string\""
  [t]
  (get type-mapping t "string"))

;;; ============================================================
;;; 参数解析
;;; ============================================================

(defn- parse-param
  "解析单个参数声明向量

   输入格式: [name type description] 或 [name type description :default val]

   返回:
   {:name        参数名（symbol）
    :type        类型关键字
    :description 描述字符串
    :required    是否必需（无 :default 为 true）
    :default     默认值（如有）}"
  [param-vec]
  (let [[pname ptype pdesc & opts] param-vec
        opts-map (apply hash-map opts)]
    {:name        pname
     :type        ptype
     :description pdesc
     :required    (not (contains? opts-map :default))
     :default     (:default opts-map)}))

;;; ============================================================
;;; Schema 生成
;;; ============================================================

(defn params->json-schema
  "将解析后的参数列表转换为 JSON Schema

   返回:
   {:type       \"object\"
    :properties {:param1 {:type \"string\" :description \"...\"} ...}
    :required   [\"param1\" ...]}"
  [parsed-params]
  (let [properties (reduce
                     (fn [acc {:keys [name type description]}]
                       (assoc acc (keyword name)
                              {:type        (clj-type->json-type type)
                               :description description}))
                     {}
                     parsed-params)
        required (->> parsed-params
                      (filter :required)
                      (mapv (comp clojure.core/name :name)))]
    (cond-> {:type       "object"
             :properties properties}
      (seq required) (assoc :required required))))

(defn build-tool-schema
  "构建 Anthropic 兼容的 tool schema

   参数:
   - fn-name:     函数名（keyword）
   - description: 函数描述
   - params:      解析后的参数列表

   返回:
   {:name         \"函数名\"
    :description  \"描述\"
    :input_schema {:type \"object\" :properties {...} :required [...]}}"
  [fn-name description parsed-params]
  {:name         (name fn-name)
   :description  description
   :input_schema (params->json-schema parsed-params)})

;;; ============================================================
;;; deftool 宏
;;; ============================================================

(defmacro deftool
  "定义工具函数

   同时生成带 tool schema 元数据的 Clojure 函数。

   用法:
   (deftool 函数名
     \"描述\"
     [[参数1 :类型 \"描述\"] [参数2 :类型 \"描述\" :default 默认值]]
     函数体...)

   (deftool 函数名
     \"描述\"
     [[参数1 :类型 \"描述\"]]
     {:sensitive true}      ; 选项 map（可选）
     函数体...)

   (deftool 函数名
     \"描述\"
     [[参数1 :类型 \"描述\"]]
     {:context true}        ; 需要 context
     ;; ctx 变量自动可用
     {:result \"...\" :context (set-var ctx :key val)})

   生成的 var 元数据:
   - :tool/schema    Anthropic 格式 tool schema
   - :tool/params    参数定义列表
   - :tool/sensitive 是否为敏感工具
   - :tool/category  工具分类
   - :tool/context   是否需要 context
   - :tool/tags      标签集合（用于过滤）
   - :tool/function  标记为 tool function"
  {:arglists '([name description params & body]
               [name description params opts-map & body])}
  [fn-name description params & body]
  (let [;; 分离选项 map 和函数体
        [opts body] (if (and (map? (first body))
                             (some #{:sensitive :timeout :category :context :tags}
                                   (keys (first body))))
                      [(first body) (rest body)]
                      [{} body])
        ;; 解析参数定义
        parsed-params (mapv parse-param params)
        ;; 生成 tool schema
        schema (build-tool-schema (keyword fn-name) description parsed-params)
        ;; 提取参数名作为解构键
        param-names (mapv (comp symbol clojure.core/name :name) parsed-params)
        ;; 构建 :or 默认值 map：声明了 :default 的参数（:required false）在 LLM 省略时
        ;; 取声明的默认值，而非 nil。默认值为字面量（数字/字符串等），在 defn 上下文求值。
        or-map (into {} (for [{:keys [name default required]} parsed-params
                              :when (not required)]
                          [(symbol (clojure.core/name name)) default]))
        ;; 是否需要 context
        needs-context? (:context opts)
        ;; 生成参数符号
        arg-sym (gensym "args")
        ctx-sym 'ctx
        ;; 解析 tags
        tags-set (when-let [tags (:tags opts)]
                   (set tags))]
    (if needs-context?
      ;; 需要 context 的 tool：生成 2-arity 函数 [args-map context]
      (if (seq param-names)
        `(defn ~fn-name
           ~description
           {:tool/schema    ~schema
            :tool/params    '~parsed-params
            :tool/sensitive ~(boolean (:sensitive opts))
            :tool/category  ~(:category opts :general)
            :tool/context   true
            :tool/tags      ~tags-set
            :tool/function  true}
           [{:keys [~@param-names] :or ~or-map :as ~arg-sym} ~ctx-sym]
           ~@body)
        `(defn ~fn-name
           ~description
           {:tool/schema    ~schema
            :tool/params    '~parsed-params
            :tool/sensitive ~(boolean (:sensitive opts))
            :tool/category  ~(:category opts :general)
            :tool/context   true
            :tool/tags      ~tags-set
            :tool/function  true}
           [~arg-sym ~ctx-sym]
           ~@body))
      ;; 普通 tool（保持兼容）：1-arity 函数 [args-map]
      (if (seq param-names)
        `(defn ~fn-name
           ~description
           {:tool/schema    ~schema
            :tool/params    '~parsed-params
            :tool/sensitive ~(boolean (:sensitive opts))
            :tool/category  ~(:category opts :general)
            :tool/context   false
            :tool/tags      ~tags-set
            :tool/function  true}
           [{:keys [~@param-names] :or ~or-map :as ~arg-sym}]
           ~@body)
        `(defn ~fn-name
           ~description
           {:tool/schema    ~schema
            :tool/params    '~parsed-params
            :tool/sensitive ~(boolean (:sensitive opts))
            :tool/category  ~(:category opts :general)
            :tool/context   false
            :tool/tags      ~tags-set
            :tool/function  true}
           [~arg-sym]
           ~@body)))))

;;; ============================================================
;;; Tool Var 元数据查询
;;; ============================================================

(defn tool-function?
  "检查 var 是否为 tool function

   参数:
   - v: var 引用（如 #'get-weather）

   返回: boolean"
  [v]
  (boolean (:tool/function (meta v))))

(defn get-schema
  "获取 tool function 的 tool schema

   参数:
   - v: var 引用

   返回: tool schema map 或 nil"
  [v]
  (:tool/schema (meta v)))

(defn get-params
  "获取 tool function 的参数定义

   参数:
   - v: var 引用

   返回: 参数定义列表"
  [v]
  (:tool/params (meta v)))

(defn sensitive?
  "检查 tool function 是否标记为敏感工具

   参数:
   - v: var 引用

   返回: boolean"
  [v]
  (boolean (:tool/sensitive (meta v))))

(defn context-tool?
  "检查 tool function 是否需要 context

   参数:
   - v: var 引用

   返回: boolean"
  [v]
  (boolean (:tool/context (meta v))))

(defn get-category
  "获取 tool function 的分类

   参数:
   - v: var 引用

   返回: 分类关键字（默认 :general）"
  [v]
  (or (:tool/category (meta v)) :general))

(defn get-tags
  "获取 tool function 的标签集合

   参数:
   - v: var 引用

   返回: 标签集合（set）或 nil"
  [v]
  (:tool/tags (meta v)))

(defn has-tag?
  "检查 tool function 是否包含指定标签

   参数:
   - v:   var 引用
   - tag: 标签关键字

   返回: boolean"
  [v tag]
  (boolean (when-let [tags (get-tags v)]
             (contains? tags tag))))

(defn has-any-tag?
  "检查 tool function 是否包含任意一个指定标签

   参数:
   - v:    var 引用
   - tags: 标签集合

   返回: boolean"
  [v tags]
  (boolean (when-let [tool-tags (get-tags v)]
             (some tool-tags tags))))

;;; ============================================================
;;; Tool Var 执行辅助
;;; ============================================================

(defn validate-args
  "验证工具参数（基于 var 元数据中的 schema）

   参数:
   - v:    tool function 的 var 引用
   - args: 参数 map

   返回:
   {:valid bool :errors [string]}"
  [v args]
  (let [schema (get-schema v)
        required (set (map keyword (get-in schema [:input_schema :required] [])))]
    (if (empty? required)
      {:valid true :errors []}
      (let [provided (set (keys args))
            missing (clojure.set/difference required provided)]
        (if (empty? missing)
          {:valid true :errors []}
          {:valid false
           :errors (mapv #(str "缺少必需参数: " (name %)) missing)})))))

(defn invoke
  "执行 tool function 并返回标准结果

   参数:
   - v:       tool function 的 var 引用
   - args:    参数 map
   - context: (可选) Context 对象

   返回:
   {:success bool :result any :error string :context ctx}
   如果 tool 返回包含 :context 的 map，:context 会被提取到结果中。"
  ([v args]
   (invoke v args nil))
  ([v args context]
   (try
     (let [f (var-get v)
           needs-ctx? (context-tool? v)
           raw-result (if needs-ctx?
                        (f (or args {}) context)
                        (f (or args {})))
           ;; context-aware tool 可能返回 {:result ... :context ...}
           [result new-context] (if (and needs-ctx?
                                         (map? raw-result)
                                         (contains? raw-result :result))
                                  [(:result raw-result) (:context raw-result)]
                                  [raw-result context])]
       (cond-> {:success true
                :result (if (string? result) result (pr-str result))}
         new-context (assoc :context new-context)))
     (catch Exception e
       {:success false
        ;; NPE 等异常 getMessage 为 nil，直接用会喂给 LLM 空错误 "错误: "；
        ;; 回退异常类名，保证错误可读。
        :error (or (not-empty (.getMessage e))
                   (.getName (class e)))}))))
