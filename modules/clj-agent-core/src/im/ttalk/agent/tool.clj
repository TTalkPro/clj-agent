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
   - :tool/timeout   超时毫秒（可选）。经 chat-client 调用时**开箱即生效**（缺省不超时；
                     优先级：本声明 > 引擎 {:timeout ms}）。语义为「放弃等待」而非
                     「终止执行」——超时后工具可能仍在后台跑完并产生外部副作用
                     （见 call-with-timeout 文档）
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
            :tool/timeout   ~(:timeout opts)
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
            :tool/timeout   ~(:timeout opts)
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
            :tool/timeout   ~(:timeout opts)
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
            :tool/timeout   ~(:timeout opts)
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

(defn timeout-spec
  "读取 tool function 的 :timeout 声明（deftool 选项，毫秒）。

   与 `:serial` / `:retry` / `:return-direct` 同款——经 chat-client 调用时**开箱即生效**，
   由 `chat-client/invoke-tool` 强制。缺省不超时；引擎可给整体缺省
   `(…-tool-calling-manager {:timeout ms})`，本声明恒优先。

   内联工具的同名声明经 `chat-client/tool-timeout` 读取（本函数只管 var）。
   语义注意：JVM 上超时 = 放弃等待 ≠ 终止执行，详见 `call-with-timeout`。

   返回: nil（未声明）| 正整数毫秒"
  [v]
  (:tool/timeout (meta v)))

(defn valid-timeout?
  "`:timeout` 声明是否合法：nil（未声明）或正整数毫秒。

   非正整数一律拒绝——`\"5s\"` 会在 deref 处抛 ClassCastException，`-1` 会让该
   工具每次调用都立刻超时（静默、无从排查），`2.7` 会被静默截断。由
   `chat-client/build-chat-client` 在装配期校验（var 与内联工具汇合、尚未开始执行的最早时点）。"
  [t]
  (or (nil? t) (pos-int? t)))

(defn check-timeout!
  "校验 timeout 值，非法则抛 ex-info。label 是出错时点名的对象（如 \"工具 :foo\"）。

   **DRY**：此前 4 处（chat_client.clj validate-tool-timeouts! var/inline + build-chat-client
   manager + react.clj check-timeout-opt!）各自手抄 `:timeout 必须为正整数毫秒`
   消息串——消息串被两个模块的测试用正则钉住，改一处漏一处即出问题。"
  [label t]
  (when-not (valid-timeout? t)
    (throw (ex-info (str label " 的 :timeout 必须为正整数毫秒，实为 " (pr-str t))
                    {:timeout t}))))

(defn valid-retry?
  "`:retry` 声明是否合法：nil（未声明）| true（opt-in 用默认）| 正整数 map。
   与 `valid-timeout?` 对称——`:retry` 此前零校验，`{:max-retries \"3\"}` 在
   运行期 `(long ...)` 抛 CCE 且被收敛成指向工具的 :semantic 错误——症状偏离病因。"
  [r]
  (or (nil? r)
      (true? r)
      (and (map? r)
           (every? #(or (nil? (get r %)) (pos-int? (get r %)))
                   [:max-retries :initial-delay-ms]))))

(defn call-with-timeout
  "在**虚拟线程**上跑 f，最多等 timeout-ms 毫秒。

   返回 `[:ok v]` | `[:err throwable]` | `[:timeout]`——**不**替调用方决定结果
   形状，由调用点自行翻译。这是超时机制的**唯一实现**，消费者是
   `chat-client/invoke-tool`（强制「工具声明 > 引擎缺省」的生效超时）。

   **语义如实声明：超时 = 放弃等待，不是终止执行。**JVM 没有强杀原语
   （`Thread.stop` 已移除），到点只能 interrupt：
   - `Thread/sleep`、阻塞 IO（socket read 等）**会被真正打断**——虚拟线程上 JDK
     关闭 socket 并抛 `SocketException: Closed by interrupt`（JEP 353 起
     `java.net.Socket` 基于 NIO；实测 JDK 25 确认）。故「工具卡在外部 API 上」
     这个最常见的形态能干净取消，副作用不落地；
   - 但**不检查中断标志的代码打不断**（纯 CPU 循环为主，另有 native 调用、吞掉
     `InterruptedException` 的代码）——它会继续跑完，其副作用可能在超时**之后**
     才落地。这是残余风险，框架消不掉。

   用虚拟线程而非 `clojure.core/future`（send-off 平台线程池）的两个理由：
   (1) 阻塞 IO 因此真的可取消（平台线程会无视 interrupt 把请求读完）；
   (2) 被放弃的执行只占几 KB 栈，且不破坏 ToolCallingManager 各引擎的线程模型。

   `bound-fn*` 包装：调用方的动态绑定对 f 照常可见（与 `future` 的传导语义一致）——
   见设计原则 §3「边界内一致」。"
  [timeout-ms f]
  (let [p    (promise)
        ;; bound-fn* 须在**调用方的**绑定帧内求值，故不能挪进 startVirtualThread
        task (bound-fn* (fn []
                          (deliver p (try [:ok (f)]
                                          (catch Throwable e [:err e])))))
        th   (Thread/startVirtualThread task)
        r    (deref p timeout-ms ::timeout)]
    (if (= r ::timeout)
      (do (.interrupt th) [:timeout])
      r)))

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
          (catch Throwable t
            (let [{:keys [message class]} (err/contain-throwable t)]
              {:success false :error message :error-class class})))))))

