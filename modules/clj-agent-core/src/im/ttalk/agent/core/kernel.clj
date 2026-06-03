(ns im.ttalk.agent.core.kernel
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
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.kernel.memory-filter :as mf]
            [im.ttalk.agent.core.memory :as memory]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.llm.response :as response]))

;;; ============================================================
;;; Kernel Record
;;; ============================================================

(defrecord Kernel [service filters tools tool-vars settings memory])

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
    :memory    nil
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

(defn add-memory
  "设置 ChatMemory store 到 builder（不设则 build-kernel 默认用 in-memory）

   参数:
   - builder: kernel builder
   - store:   实现 core.memory/ChatMemory 协议的 store

   返回:
   更新后的 builder"
  [builder store]
  (assoc builder :memory store))

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
        tool-vars-map (build-tool-vars-map tool-vars-list)
        ;; ChatMemory store：未指定则默认 in-memory
        store (or (:memory builder) (memory/in-memory-store))
        ;; 自动挂载 Memory Filter（pre/post-chat），并保留用户 filters
        all-filters (into (vec (mf/memory-filters store))
                          (:filters builder))]
    (->Kernel (:service builder)
              all-filters
              tools
              tool-vars-map
              (:settings builder)
              store)))

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

(defn- build-chat-opts
  "从 invoke/resume 的 opts 构建传给 invoke-chat 的 chat 选项
   （system-prompt 合并 + 工具 schema 过滤 + tool-choice）。"
  [kernel opts]
  (let [system-prompts (or (:system-prompts opts) [])
        sp-str (when (seq system-prompts)
                 (->> system-prompts (map :content) (clojure.string/join "\n")))
        tool-schemas (filter-tools-by-tags kernel opts)
        tool-choice (or (:tool-choice opts) :auto)]
    (cond-> {:tools tool-schemas :tool-choice tool-choice}
      sp-str (assoc :system-prompt sp-str))))

(defn execute-batch
  "按 gate 决策执行一批工具调用，产出中立 tool 结果消息 + 记录 + 更新后的 ToolContext。

   gate: (fn [tool-call] -> :proceed | :reject)，nil 视为全 :proceed。
   - :proceed 调 invoke-tool（异常捕获为错误结果，不中断）
   - :reject 跳过执行，填入「已拒绝执行」中立结果

   参数:
   - kernel, tool-calls, gate, tool-context, init-records

   返回: {:messages [...] :records [...] :context ...}"
  [kernel tool-calls gate tool-context init-records]
  (reduce
    (fn [{:keys [messages records context]} tc]
      (let [fn-key (keyword (:name tc))
            decision (if gate (gate tc) :proceed)]
        (if (= :reject decision)
          {:messages (conj messages (msg/tool-result (:id tc) (:name tc) "已拒绝执行"))
           :records  (conj records {:name fn-key :args (:input tc) :result :rejected})
           :context  context}
          (let [{:keys [value context]}
                (try (invoke-tool kernel fn-key (:input tc) context)
                     (catch Exception e
                       {:value (str "错误: " (.getMessage e)) :context context}))]
            {:messages (conj messages (msg/tool-result (:id tc) (:name tc) value))
             :records  (conj records {:name fn-key :args (:input tc) :result value})
             :context  context}))))
    {:messages [] :records init-records :context tool-context}
    tool-calls))

(defn run-tools
  "execute-batch 的无 gate 特例：全部执行。供外部手搓工具循环使用。

   返回 {:messages [...] :records [...] :context ...}"
  [kernel tool-calls tool-context]
  (execute-batch kernel tool-calls nil tool-context []))

(defn- run-tool-loop
  "统一工具循环：从 delta 起步，驱动 LLM ↔ 工具，直到文本响应或被 gate 暂停。

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {:tool-calls :remaining :records} :pending-tool {...} :tool-context c}"
  [kernel delta remaining records tctx gate chat-opts]
  (loop [delta delta, remaining remaining, records records, tctx tctx]
    (when (zero? remaining)
      (throw (ex-info "工具调用循环次数超过上限"
                      {:max-iterations remaining :tool-calls-made records})))
    (let [{:keys [response context]} (invoke-chat kernel delta (assoc chat-opts :context tctx))
          tctx context
          calls (response/response-tool-calls response)]
      (cond
        (empty? calls)
        {:status :completed :response response
         :tool-context tctx :tool-calls-made records}

        (and gate (some #(= :pause (gate %)) calls))
        (let [paused-call (first (filter #(= :pause (gate %)) calls))]
          {:status :paused
           :pause-reason (str "需要审批: " (:name paused-call))
           :loop-state {:tool-calls calls :remaining (dec remaining) :records records}
           :pending-tool {:name (:name paused-call)
                          :args (:input paused-call)
                          :tool-call paused-call}
           :tool-calls-made records
           :tool-context tctx})

        :else
        (let [{:keys [messages records context]}
              (execute-batch kernel calls gate tctx records)]
          (recur messages (dec remaining) records context))))))

(defn invoke
  "工具调用循环主入口（Memory Filter 模式，统一循环）

   每轮只向 invoke-chat 传 delta，pre-chat 的 Memory Filter 拼出完整历史。
   可选 :tool-gate 提供暂停/拒绝能力（gate 返回 :pause 时整批暂停）。

   参数:
   - kernel:   Kernel 实例（需注册 LLM 服务）
   - messages: 本轮新消息（中立消息，通常 [(message/user \"...\")]）
   - opts:
     {:context          ToolContext（含 :conversation-id 则多轮持久；否则临时会话）
      :system-prompts   系统提示消息列表
      :max-iterations   最大循环次数（默认 10）
      :tool-choice      :auto/:none/:required（默认 :auto）
      :tool-gate        (fn [tool-call] -> :proceed|:pause|:reject)，可选
      :tags / :exclude-tags  工具 tag 过滤}

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {...} :pending-tool {...} :tool-context c}"
  [kernel messages opts]
  (when-not (:service kernel)
    (throw (ex-info "Kernel 未配置 LLM 服务（调用 add-service）"
                    {:kernel-keys (keys kernel)})))
  (let [base-ctx (or (:context opts) (ctx/create))
        ephemeral? (nil? (ctx/conversation-id base-ctx))
        conv-id (or (ctx/conversation-id base-ctx)
                    (str "conv-" (java.util.UUID/randomUUID)))
        init-ctx (ctx/with-conversation-id base-ctx conv-id)
        max-iter (or (:max-iterations opts)
                     (get-in kernel [:settings :max-tool-iterations])
                     default-max-iterations)
        result (run-tool-loop kernel (mapv msg/normalize messages)
                              max-iter [] init-ctx
                              (:tool-gate opts)
                              (build-chat-opts kernel opts))]
    ;; 临时会话仅在完成时清理（暂停需保留历史以便 resume）
    (when (and ephemeral? (= :completed (:status result)))
      (memory/mem-clear (:memory kernel) conv-id))
    result))

(defn resume
  "从 paused 的 loop-state 继续工具循环。

   参数:
   - kernel:     Kernel 实例
   - loop-state: invoke 返回的 :loop-state {:tool-calls :remaining :records}
   - decision:   :approved（强制全部执行）| :rejected（gate 决定，敏感→拒绝）
   - opts:       同 invoke（须含带 conversation-id 的 :context 以接续历史）

   返回: 同 invoke（:completed 或再次 :paused）"
  [kernel loop-state decision opts]
  (let [{:keys [tool-calls remaining records]} loop-state
        tctx (or (:context opts) (ctx/create))
        resume-gate (if (= decision :approved) (constantly :proceed) (:tool-gate opts))
        {:keys [messages records context]}
        (execute-batch kernel tool-calls resume-gate tctx records)]
    (run-tool-loop kernel messages remaining records context
                   (:tool-gate opts)
                   (build-chat-opts kernel opts))))
