(ns im.ttalk.agent.core.kernel.core
  "Kernel 核心 - 中央编排器

   Kernel 提供三类 API：

   Build API - 构建 Kernel:
     (-> (create-kernel-builder)
         (add-tools [#'get-weather #'get-time])
         (add-service my-service)
         (add-filter logging-pre-filter)
         (build-kernel))

   Tool 定义（支持 tags）:
     (deftool get-weather
       \"获取天气\"
       [[city :string \"城市\"]]
       {:tags [:weather :read-only]}
       (str city \": 25°C\"))

   Invoke API - 调用函数/LLM:
     (invoke-tool kernel :get-weather {:city \"北京\"} context)
     (invoke-chat kernel messages opts)
     (invoke kernel messages opts)
     (invoke kernel messages {:tags [:weather]})           ;; 只用带 :weather tag 的工具
     (invoke kernel messages {:exclude-tags [:dangerous]}) ;; 排除危险工具

   Query API - 查询 Kernel 状态:
     (:tools kernel)                         ;; 所有 tool schemas
     (list-functions kernel)                 ;; 所有函数名
     (list-functions-by-tag kernel :weather) ;; 按 tag 过滤
     (find-function kernel :get-weather)
     (get-tool-var kernel :get-weather)

   Service 格式:
   Service 是一个 map，定义 LLM 调用接口：
     {:chat-fn           (fn [messages opts] -> {:text \"...\" :tool-calls [...] :assistant-msg {...}})
      :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}

   返回值格式:
     invoke-tool: {:value v :context ctx}
     invoke-chat: {:response r :context ctx}
     invoke:      {:response r :context ctx :tool-calls-made [...]}"
  (:require [clojure.string]
            [im.ttalk.agent.core.kernel.filter :as filters]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.tool :as tool]))

;;; ============================================================
;;; Kernel Record
;;; ============================================================

(defrecord Kernel [service filters tools tool-vars settings])

;;; ============================================================
;;; Build API
;;; ============================================================

(defn create-kernel-builder
  "创建 Kernel Builder

   返回:
   builder map（初始为空配置）"
  ([]
   (create-kernel-builder {}))
  ([settings]
   {:service   nil
    :tool-vars []
    :filters   []
    :settings  settings}))

(defn add-tool
  "添加单个 tool var 到 builder

   参数:
   - builder:  kernel builder
   - tool-var: deftool 定义的 var 引用（如 #'get-weather）

   返回:
   更新后的 builder

   示例:
   (-> (create-kernel-builder)
       (add-tool #'get-weather)
       (add-tool #'get-time))"
  [builder tool-var]
  (update builder :tool-vars conj tool-var))

(defn add-tools
  "批量添加 tool vars 到 builder

   参数:
   - builder:   kernel builder
   - tool-vars: deftool 定义的 var 引用列表

   返回:
   更新后的 builder

   示例:
   (-> (create-kernel-builder)
       (add-tools [#'get-weather #'get-time #'calculate]))"
  [builder tool-vars]
  (update builder :tool-vars into tool-vars))

(defn add-service
  "设置 LLM 服务到 builder

   Service 是一个 map：
   {:chat-fn           (fn [messages opts] -> response)
    :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}

   参数:
   - builder: kernel builder
   - service: service map

   返回:
   更新后的 builder"
  [builder service]
  (assoc builder :service service))

(defn add-filter
  "添加 Filter 到 builder

   Filter 是一个 map（由 filters/create-filter 创建）：
   {:name :filter-name :type :pre-invocation :handler fn :priority 0}

   参数:
   - builder:    kernel builder
   - filter-def: filter 定义 map

   返回:
   更新后的 builder"
  [builder filter-def]
  (update builder :filters conj filter-def))

;;; ============================================================
;;; 构建 Kernel
;;; ============================================================

(defn- compile-tools
  "编译所有 tool schema"
  [tool-vars]
  (vec (for [v tool-vars
             :when (tool/tool-function? v)]
         (tool/get-schema v))))

(defn- build-tool-vars-map
  "构建 tool-vars map：{:fn-name var ...}"
  [tool-vars]
  (into {}
        (for [v tool-vars
              :when (tool/tool-function? v)]
          [(keyword (:name (tool/get-schema v))) v])))

(defn build-kernel
  "构建最终 Kernel 实例

   参数:
   - builder: 配置完成的 builder

   返回:
   Kernel record"
  [builder]
  (let [tool-vars-list (:tool-vars builder)
        tools (compile-tools tool-vars-list)
        tool-vars-map (build-tool-vars-map tool-vars-list)]
    (->Kernel (:service builder)
              (:filters builder)
              tools
              tool-vars-map
              (:settings builder))))

;;; ============================================================
;;; Query API
;;; ============================================================

(defn find-function
  "在 Kernel 中查找函数

   参数:
   - kernel:  Kernel 实例
   - fn-name: 函数名（关键字或字符串）

   返回:
   {:tool-var var} 或 nil"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (when-let [v (get (:tool-vars kernel) fn-key)]
      {:tool-var v})))

(defn list-functions
  "列出 Kernel 中所有注册的函数名称

   返回:
   关键字列表"
  [kernel]
  (keys (:tool-vars kernel)))

(defn get-tool-var
  "获取指定名称的 tool var

   参数:
   - kernel:  Kernel 实例
   - fn-name: 函数名（关键字或字符串）

   返回:
   var 引用或 nil"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (get (:tool-vars kernel) fn-key)))

;;; ============================================================
;;; Query API - Tag 过滤
;;; ============================================================

(defn list-functions-by-tag
  "列出带有指定 tag 的函数名称

   参数:
   - kernel: Kernel 实例
   - tag:    标签关键字

   返回:
   关键字列表"
  [kernel tag]
  (vec (for [[fn-name v] (:tool-vars kernel)
             :when (tool/has-tag? v tag)]
         fn-name)))

(defn list-functions-by-tags
  "列出带有任意指定 tags 的函数名称（OR 逻辑）

   参数:
   - kernel: Kernel 实例
   - tags:   标签集合

   返回:
   关键字列表"
  [kernel tags]
  (let [tags-set (set tags)]
    (vec (for [[fn-name v] (:tool-vars kernel)
               :when (tool/has-any-tag? v tags-set)]
           fn-name))))

(defn list-functions-with-all-tags
  "列出同时带有所有指定 tags 的函数名称（AND 逻辑）

   参数:
   - kernel: Kernel 实例
   - tags:   标签集合

   返回:
   关键字列表"
  [kernel tags]
  (let [tags-set (set tags)]
    (vec (for [[fn-name v] (:tool-vars kernel)
               :let [tool-tags (tool/get-tags v)]
               :when (and tool-tags
                          (every? #(contains? tool-tags %) tags-set))]
           fn-name))))

(defn- filter-tools-by-tags
  "根据 tags 过滤 tool schemas

   参数:
   - kernel:       Kernel 实例
   - tags:         包含的标签（OR 逻辑，nil 表示不过滤）
   - exclude-tags: 排除的标签（OR 逻辑，nil 表示不排除）

   返回:
   过滤后的 tool schemas 列表"
  [kernel {:keys [tags exclude-tags]}]
  (let [all-tools (:tools kernel)
        tool-vars-map (:tool-vars kernel)
        tags-set (when tags (set tags))
        exclude-tags-set (when exclude-tags (set exclude-tags))]
    (if (and (nil? tags-set) (nil? exclude-tags-set))
      ;; 无过滤条件，返回全部
      all-tools
      ;; 应用过滤
      (vec (for [tool-schema all-tools
                 :let [fn-name (keyword (:name tool-schema))
                       v (get tool-vars-map fn-name)]
                 :when (and v
                            ;; 如果指定了 tags，则必须包含任意一个
                            (or (nil? tags-set)
                                (tool/has-any-tag? v tags-set))
                            ;; 如果指定了 exclude-tags，则不能包含任意一个
                            (or (nil? exclude-tags-set)
                                (not (tool/has-any-tag? v exclude-tags-set))))]
             tool-schema)))))

;;; ============================================================
;;; Invoke API - invoke-tool（函数调用，经过 Filter 管道）
;;; ============================================================

(defn- build-func-def
  "构建传给 filter 的函数定义信息"
  [fn-name tool-var]
  (let [schema (when tool-var
                 (:tool/schema (meta tool-var)))]
    {:name      fn-name
     :schema    schema
     :sensitive (when tool-var
                  (boolean (:tool/sensitive (meta tool-var))))}))

(defn invoke-tool
  "调用 Kernel 中注册的函数（经过 pre/post invocation filter 管道）

   执行流程：
   1. 查找函数
   2. apply-pre-invocation-filters → 可修改 args/context 或跳过
   3. 执行函数
   4. apply-post-invocation-filters → 可修改 result/context

   参数:
   - kernel:  Kernel 实例
   - fn-name: 函数名（关键字或字符串）
   - args:    参数 map
   - context: Context 对象

   返回:
   {:value result :context updated-ctx}

   错误:
   抛 ex-info"
  [kernel fn-name args context]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))
        found (find-function kernel fn-key)
        _ (when-not found
            (throw (ex-info (str "函数未找到: " fn-key)
                            {:fn-name fn-key
                             :available (list-functions kernel)})))
        {:keys [tool-var]} found
        func-def (build-func-def fn-key tool-var)
        all-filters (:filters kernel)

        ;; 1. Pre-invocation filters
        pre-result (filters/apply-pre-invocation-filters
                     all-filters func-def args context)]

    (cond
      ;; Filter 跳过执行
      (contains? pre-result :skip)
      {:value (:skip pre-result) :context context}

      ;; Filter 报错
      (contains? pre-result :error)
      (throw (ex-info (str "Filter 错误: " (:error pre-result))
                      {:fn-name fn-key :error (:error pre-result)}))

      ;; 正常继续
      :else
      (let [{:keys [args context]} (:ok pre-result)
            ;; 2. 执行函数（带超时支持）
            timeout-ms (:timeout-ms pre-result)
            exec-result (try
                          (let [do-exec #(tool/invoke tool-var args context)]
                            (if timeout-ms
                              (let [result (deref (future (do-exec))
                                                  timeout-ms ::timeout)]
                                (if (= result ::timeout)
                                  {:success false :error (str "工具调用超时（" timeout-ms "ms）")}
                                  result))
                              (do-exec)))
                          (catch Exception e
                            {:success false :error (.getMessage e)}))
            ;; 提取结果
            result-value (if (:success exec-result)
                           (:result exec-result)
                           (str "错误: " (:error exec-result)))
            result-ctx (or (:context exec-result) context)

            ;; 3. Post-invocation filters
            post-result (filters/apply-post-invocation-filters
                          all-filters func-def args result-value result-ctx)]

        (cond
          (contains? post-result :error)
          (throw (ex-info (str "Post-filter 错误: " (:error post-result))
                          {:fn-name fn-key :error (:error post-result)}))

          :else
          (let [{:keys [result context]} (:ok post-result)]
            {:value result :context context}))))))

;;; ============================================================
;;; Invoke API - invoke-chat（纯 LLM 调用，带 pre/post chat filter）
;;; ============================================================

(defn invoke-chat
  "发送 Chat Completion 请求（带 pre/post chat filter，不含工具调用循环）

   执行流程：
   1. apply-pre-chat-filters → 可修改 messages/context
   2. 调用 LLM (chat-fn)
   3. apply-post-chat-filters → 可修改 response/context

   参数:
   - kernel:   Kernel 实例（需已配置 service）
   - messages: 消息列表
   - opts:     选项 map（传递给 service 的 chat-fn）
     {:tools       工具 schema 列表（可选）
      :tool-choice :auto/:none/:required（可选）
      :context     Context 对象（可选）}

   返回:
   {:response {:text \"...\" :tool-calls [...] :assistant-msg {...}}
    :context  updated-ctx}"
  [kernel messages opts]
  (let [service (:service kernel)
        _ (when-not service
            (throw (ex-info "Kernel 未配置 LLM 服务（调用 add-service）"
                            {:kernel-keys (keys kernel)})))
        chat-fn (:chat-fn service)
        _ (when-not chat-fn
            (throw (ex-info "Service 缺少 :chat-fn"
                            {:service-keys (keys service)})))
        context (or (:context opts) (ctx/create))
        all-filters (:filters kernel)
        chat-opts (dissoc opts :context)

        ;; 1. Pre-chat filters
        pre-result (filters/apply-pre-chat-filters all-filters messages context)]

    (cond
      (contains? pre-result :error)
      (throw (ex-info (str "Pre-chat filter 错误: " (:error pre-result))
                      {:error (:error pre-result)}))

      :else
      (let [{:keys [messages context]} (:ok pre-result)
            ;; 2. 调用 LLM
            response (chat-fn messages chat-opts)
            ;; 3. Post-chat filters
            post-result (filters/apply-post-chat-filters all-filters response context)]

        (cond
          (contains? post-result :error)
          (throw (ex-info (str "Post-chat filter 错误: " (:error post-result))
                          {:error (:error post-result)}))

          :else
          (let [{:keys [response context]} (:ok post-result)]
            {:response response :context context}))))))

;;; ============================================================
;;; Invoke API - invoke（工具调用循环，主入口）
;;; ============================================================

(def ^:private default-max-iterations
  "工具调用循环默认最大次数"
  10)

(defn- encode-tool-result
  "将工具执行结果编码为 tool message"
  [tool-call result-value]
  {:role         "tool"
   :tool_call_id (:id tool-call)
   :content      (if (string? result-value)
                   result-value
                   (pr-str result-value))})

(defn- execute-tool-calls
  "批量执行工具调用，累积 context 和结果记录

   参数:
   - kernel:     Kernel 实例
   - tool-calls: 工具调用列表
   - context:    当前 Context

   返回:
   {:results [{:tool-id :name :result}...]
    :context updated-ctx
    :records [{:name :args :result}...]}"
  [kernel tool-calls context]
  (reduce
    (fn [acc tc]
      (let [fn-name (keyword (:name tc))
            args (:input tc)
            {:keys [value context]}
            (try
              (invoke-tool kernel fn-name args (:context acc))
              (catch Exception e
                {:value (str "错误: " (.getMessage e))
                 :context (:context acc)}))
            tool-msg (encode-tool-result tc value)
            new-ctx (ctx/track-message context tool-msg)]
        {:results (conj (:results acc)
                        {:tool-id (:id tc) :name fn-name :result value})
         :context new-ctx
         :records (conj (:records acc)
                        {:name fn-name :args args :result value})}))
    {:results [] :context context :records []}
    tool-calls))

(defn- build-tool-messages
  "构建工具调用的追加消息（assistant-msg + tool-result-msgs）

   使用 service 的 build-result-msgs 格式化"
  [service assistant-msg results]
  ((:build-result-msgs service) assistant-msg results))

(defn invoke
  "工具调用循环主入口

   组合 context.messages + 新 messages，驱动 LLM + 工具调用循环，
   直到 LLM 返回文本响应或达到最大迭代次数。

   执行流程:
   1. 组合 context.messages + 新 messages
   2. 记录新消息到 context（track-message）
   3. tool-calling-loop:
      a. system-prompts ++ conversation-msgs → LLM（经过 pre/post chat filter）
      b. 如果返回 tool_calls:
         - 逐个执行 invoke-tool（经过 pre/post invocation filter）
         - track-message: assistant msg + tool result msgs
         - 继续循环
      c. 如果返回文本:
         - track-message: assistant msg
         - 返回结果

   参数:
   - kernel:   Kernel 实例（需注册函数和 LLM 服务）
   - messages: 新消息列表
   - opts:     选项 map
     {:context          Context 对象（可选，默认创建空 Context）
      :system-prompts   系统提示消息列表（每次 LLM 调用前拼接）
      :max-iterations   最大循环次数（默认 10）
      :tool-choice      :auto/:none/:required（默认 :auto）
      :tags             只使用带这些 tags 的工具（OR 逻辑）
      :exclude-tags     排除带这些 tags 的工具（OR 逻辑）}

   返回:
   {:response final-response :context updated-ctx :tool-calls-made [...]}"
  [kernel messages opts]
  (when-not (:service kernel)
    (throw (ex-info "Kernel 未配置 LLM 服务（调用 add-service）"
                    {:kernel-keys (keys kernel)})))
  (let [context (or (:context opts) (ctx/create))
        system-prompts (or (:system-prompts opts) [])
        ;; 将 system-prompts 消息列表合并为单个 system-prompt 字符串
        system-prompt-str (when (seq system-prompts)
                            (->> system-prompts
                                 (map :content)
                                 (clojure.string/join "\n")))
        max-iter (or (:max-iterations opts)
                     (get-in kernel [:settings :max-tool-iterations])
                     default-max-iterations)
        ;; 根据 tags 过滤工具
        tool-schemas (filter-tools-by-tags kernel opts)
        tool-choice (or (:tool-choice opts) :auto)
        service (:service kernel)

        ;; 记录新消息到 context
        ctx-with-new (reduce ctx/track-message context messages)
        ;; 从更新后的 context 获取完整对话消息（避免重复追加）
        conv-msgs (ctx/get-messages ctx-with-new)]

    (loop [conv-msgs      conv-msgs
           remaining      max-iter
           all-tool-calls []
           ctx            ctx-with-new]

      (when (zero? remaining)
        (throw (ex-info "工具调用循环次数超过上限"
                        {:max-iterations max-iter
                         :tool-calls-made all-tool-calls})))

      (let [;; 调用 invoke-chat（经过 pre/post chat filter）
            chat-opts (cond-> {:tools tool-schemas
                               :tool-choice tool-choice
                               :context ctx}
                        system-prompt-str (assoc :system-prompt system-prompt-str))
            {:keys [response context]} (invoke-chat kernel conv-msgs chat-opts)
            ctx context]

        (if (seq (:tool-calls response))
          ;; 工具调用分支
          (let [assistant-msg (:assistant-msg response)
                ctx (ctx/track-message ctx assistant-msg)
                {:keys [results context records]}
                (execute-tool-calls kernel (:tool-calls response) ctx)
                new-msgs (build-tool-messages service assistant-msg results)]
            (recur (into conv-msgs new-msgs)
                   (dec remaining)
                   (into all-tool-calls records)
                   context))

          ;; 文本响应分支
          (let [ctx (ctx/track-message ctx (:assistant-msg response))]
            {:response response
             :context ctx
             :tool-calls-made all-tool-calls}))))))
