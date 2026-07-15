(ns im.ttalk.agent.tool
  "工具函数定义宏

   deftool 宏同时生成：
   1. 普通 Clojure 函数（可直接调用）
   2. 完整的 tool schema 元数据（用于 LLM Function Calling）

   参数声明使用向量格式: [名称 类型 描述]
   支持可选参数: [名称 类型 描述 :default 默认值]
   支持敏感标记: {:sensitive true}
   支持只读 context: {:context true}
   支持串行标记: {:serial true}（副作用工具，批内并行时整批退化为按序执行）
   支持直接返回: {:return-direct true}（结果即最终答案，不再回灌 LLM）

   状态读写（Tool 阶段 MapReduce 契约，见 docs/agent-loop-concurrency-design.md §9）：
   - 读：{:context true} 工具收到只读的轮初 context 快照（ctx 变量自动绑定）
   - 写：任意工具（无论是否声明 :context）返回 {:result r :writes {k v}}
     声明写意图；批次屏障处按 tool-call 原始序经槽级 reducer 折叠进 context。
     同批工具互相看不到对方的写（快照隔离）；失败/超时/拒绝的调用 writes 不生效。

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

   ;; 读 context（只读快照）+ 写状态（:writes 声明写意图）
   (deftool save-note
     \"保存笔记\"
     [[content :string \"内容\"]]
     {:context true}
     ;; ctx 是自动绑定的只读 context 变量
     (let [user (context/get-var ctx :user-id)]
       {:result (str \"已保存（\" user \"）\")
        :writes {:notes content}}))   ;; 槽 :notes 的 reducer 决定如何合并

   ;; 无参数函数
   (deftool get-time
     \"获取当前时间\"
     []
     (str (java.time.LocalDateTime/now)))"
  (:require [clojure.set]
            [clojure.string]
            [im.ttalk.agent.model.error :as err]))

(set! *warn-on-reflection* true)

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
     {:context true}        ; 需要只读 context
     ;; ctx 变量自动可用（只读快照）；写状态用 :writes
     {:result \"...\" :writes {:key val}})

   生成的 var 元数据:
   - :tool/schema    Anthropic 格式 tool schema
   - :tool/params    参数定义列表
   - :tool/sensitive 是否为敏感工具
   - :tool/serial    是否串行工具（批内并行时整批退化）
   - :tool/category  工具分类
   - :tool/context   是否需要（只读）context
   - :tool/tags      标签集合（用于过滤）
   - :tool/function  标记为 tool function"
  {:arglists '([name description params & body]
               [name description params opts-map & body])}
  [fn-name description params & body]
  (let [;; 分离选项 map 和函数体
        [opts body] (if (and (map? (first body))
                             (some #{:sensitive :timeout :category :context :tags :serial :retry
                                     :return-direct}
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
            :tool/serial    ~(boolean (:serial opts))
            :tool/return-direct ~(boolean (:return-direct opts))
            :tool/retry     ~(:retry opts)
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
            :tool/serial    ~(boolean (:serial opts))
            :tool/return-direct ~(boolean (:return-direct opts))
            :tool/retry     ~(:retry opts)
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
            :tool/serial    ~(boolean (:serial opts))
            :tool/return-direct ~(boolean (:return-direct opts))
            :tool/retry     ~(:retry opts)
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
            :tool/serial    ~(boolean (:serial opts))
            :tool/return-direct ~(boolean (:return-direct opts))
            :tool/retry     ~(:retry opts)
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

(defn context-tool?
  "检查 tool function 是否需要（只读）context

   参数:
   - v: var 引用

   返回: boolean"
  [v]
  (boolean (:tool/context (meta v))))

(defn serial-tool?
  "检查 tool function 是否声明 :serial（副作用工具；
   批内并行时含 serial 工具的整批退化为按序执行）

   参数:
   - v: var 引用

   返回: boolean"
  [v]
  (boolean (:tool/serial (meta v))))

(defn return-direct-tool?
  "检查 tool function 是否声明 :return-direct（结果即最终答案，不再回灌 LLM）

   参数:
   - v: var 引用

   返回: boolean"
  [v]
  (boolean (:tool/return-direct (meta v))))

(defn retry-spec
  "读取 tool function 的 :retry 声明（deftool 选项，幂等工具 opt-in）。

   返回: nil（未声明）| true | {:max-retries n :initial-delay-ms ms}"
  [v]
  (:tool/retry (meta v)))

(defn get-tags
  "获取 tool function 的标签集合

   参数:
   - v: var 引用

   返回: 标签集合（set）或 nil"
  [v]
  (:tool/tags (meta v)))

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
   - context: (可选) Context 对象（{:context true} 工具的只读快照）

   返回:
   {:success bool :result any :error string (:writes {k v})}
   工具返回含 :writes 的 map（{:result r :writes {k v}}）时拆出写意图，
   由批次屏障处的 reducer 折叠进共享 context（工具本身不改 context）。"
  ([v args]
   (invoke v args nil))
  ([v args context]
   ;; 先按 schema 校验必填参数：LLM 漏传时给出明确错误，而非进入函数体以 NPE 炸出
   ;; （:default 参数不在 required 中，不受影响）。
   (let [{:keys [valid errors]} (validate-args v (or args {}))]
     (if-not valid
       {:success false :error (clojure.string/join "; " errors)}
       (try
         (let [f (var-get v)
               needs-ctx? (context-tool? v)
               raw-result (if needs-ctx?
                            (f (or args {}) context)
                            (f (or args {})))
               ;; 任意工具可返回 {:result r :writes {k v}} 声明写意图（判据：含 :writes）
               [result writes] (if (and (map? raw-result)
                                        (contains? raw-result :writes))
                                 [(:result raw-result) (:writes raw-result)]
                                 [raw-result nil])]
           (cond-> {:success true
                    :result (if (string? result) result (pr-str result))}
             (seq writes) (assoc :writes writes)))
         (catch Exception e
           {:success false
            ;; NPE 等异常 getMessage 为 nil，直接用会喂给 LLM 空错误 "错误: "；
            ;; 回退异常类名，保证错误可读。
            :error (or (not-empty (.getMessage e))
                       (.getName (class e)))
            ;; 故障类别（S2 屏障路由）：:semantic | :transient | :environment
            :error-class (err/classify-exception e)}))))))
