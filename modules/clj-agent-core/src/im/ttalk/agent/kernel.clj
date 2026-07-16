(ns im.ttalk.agent.kernel
  "Kernel 核心 - 中央编排器

    Kernel 构建使用声明式 map：

      (build-kernel {:service my-service
                     :tools [#'get-weather #'get-time]
                     :filters [memory-filter logging-filter]
                     :settings {:max-tool-iterations 10}})

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
      (find-function kernel :get-weather)

    Service 格式:
    Service 是一个 map，定义 LLM 调用接口：
      {:chat-fn (fn [messages opts] -> {:text \"...\" :tool-calls [...]})}

    返回值格式:
      invoke-tool: {:value v (:writes {k v})}   ;; context 只读，写意图经 :writes
      invoke-chat: {:response r :context ctx}
      invoke:      {:response r :context ctx :tool-calls-made [...]}"
  (:require [im.ttalk.agent.advisor :as filters]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.model.error :as err]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.tool-calling-manager :as tcm]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; Kernel Record
;;; ============================================================

;; inline-handlers: {keyword -> (fn [args ctx] result)} — 内联工具处理函数，
;; 由 delegate-tool 等动态构建的工具填充，与 tool-vars（var 引用）互补。
;; inline-meta: {keyword -> {:serial :retry :timeout :return-direct}} —
;; 装配期预计算的内联工具声明，消除运行期 by-name O(n) 线性扫描。
(defrecord Kernel [service filters tools tool-vars inline-handlers inline-meta settings tool-manager])

;;; ============================================================
;;; Build API
;;; ============================================================

(defn- validate-tool-timeouts!
  "工具声明的 `:timeout` 必须是正整数毫秒，否则装配期即抛（消息串统一走 `tool/check-timeout!`）。"
  [var-map inline-tools]
  (doseq [[fn-key v] var-map
          :let [t (tool/timeout-spec v)]
          :when (not (tool/valid-timeout? t))]
    (tool/check-timeout! (str "工具 " fn-key) t))
  (doseq [{:keys [name timeout]} inline-tools
          :when (not (tool/valid-timeout? timeout))]
    (tool/check-timeout! (str "内联工具 " name) timeout)))

(defn- validate-tool-retries!
  "工具声明的 `:retry` 必须合法（nil / true / 正整数 map），否则装配期即抛。
   与 `validate-tool-timeouts!` 对称——`:retry` 此前零校验。"
  [var-map inline-tools]
  (doseq [[fn-key v] var-map
          :let [r (tool/retry-spec v)]
          :when (not (tool/valid-retry? r))]
    (throw (ex-info (str "工具 " fn-key " 的 :retry 必须为 nil / true / 正整数 map，实为 " (pr-str r))
                    {:tool fn-key :retry r})))
  (doseq [{:keys [name retry]} inline-tools
          :when (not (tool/valid-retry? retry))]
    (throw (ex-info (str "内联工具 " name " 的 :retry 必须为 nil / true / 正整数 map，实为 " (pr-str retry))
                    {:tool name :retry retry}))))

(defn build-kernel
  "构建 Kernel 实例

    参数 opts map:
    - :service     LLM Service map（必需）
    - :tools       tool var 向量（如 [#'get-weather]）
    - :filters     filter 向量（如 [memory-filter logging-filter]），注册顺序即执行顺序
    - :settings    额外设置（可选），如 {:max-tool-iterations 10}
    - :state-slots 状态槽声明（可选）{k {:init v0 :reduce (fn [old new] merged)}}——
                   工具批次 :writes 的合并语义（未声明的槽默认 last-writer）
    - :eligibility-fn 循环续跑判据（可选）(fn [response context] -> boolean)——
                   响应带 tool-call 时是否**真的**执行并续跑；返回 false 则
                   该响应按最终答案收尾（工具不执行）。缺省恒真，即「有
                   tool-call 就跑」。对标 Spring AI ToolExecutionEligibilityChecker

    返回:
    Kernel record

    示例:
    (build-kernel {:service svc
                   :tools [#'get-weather #'get-time]
                   :filters [memory-filter retry-filter]
                   :settings {:max-tool-iterations 10}})"
  [{:keys [service tools tool-vars filters settings state-slots eligibility-fn tool-manager]
    :or {tools [] filters [] settings {}}}]
  (let [all-tools (vec (or tool-vars tools))
        ;; 内联工具：map 且含 :handler fn（由 delegate-tool 等动态构建）
        inline-tools (filter #(and (map? %) (fn? (:handler %))) all-tools)
        var-tools    (filter var? all-tools)

        compiled-var-tools (vec (for [v var-tools :when (tool/tool-function? v)]
                                  (tool/get-schema v)))
        var-map            (into {} (for [v var-tools :when (tool/tool-function? v)]
                                      [(keyword (:name (tool/get-schema v))) v]))

        ;; 内联工具：schema 去掉 :handler，handler 单独存入 inline-handlers
        compiled-inline-tools (mapv #(dissoc % :handler) inline-tools)
        inline-handler-map    (into {} (mapv #(vector (keyword (:name %)) (:handler %))
                                             inline-tools))
        ;; 装配期预计算内联工具声明 → O(1) 查询（替代 4 处运行期 by-name O(n) 扫描）
        inline-meta-map       (into {} (mapv (fn [t]
                                               [(keyword (:name t))
                                                (select-keys t [:serial :retry :timeout :return-direct])])
                                             inline-tools))
        _ (validate-tool-timeouts! var-map inline-tools)
        _ (validate-tool-retries! var-map inline-tools)
        _ (when (some? tool-manager)
            (tool/check-timeout! ":tool-manager" (:timeout tool-manager)))]

    (->Kernel service
              (vec filters)
              (into compiled-var-tools compiled-inline-tools)
              var-map
              inline-handler-map
              inline-meta-map
              (cond-> settings
                state-slots (assoc :state-slots state-slots)
                eligibility-fn (assoc :eligibility-fn eligibility-fn))
              tool-manager)))

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

(defn serial-tool?
  "工具是否声明 :serial（副作用工具；批内并行时整批退化为按序执行）。
   var 工具查 :tool/serial 元数据；内联工具查 inline-meta 预计算 map（O(1)）。"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (if-let [v (get (:tool-vars kernel) fn-key)]
      (tool/serial-tool? v)
      (boolean (get-in (:inline-meta kernel) [fn-key :serial])))))

(defn return-direct-tool?
  "工具是否声明 :return-direct（结果即最终答案，不再回灌 LLM）。
   var 工具查 :tool/return-direct 元数据；内联工具查 inline-meta 预计算 map（O(1)）。

   对标 Spring AI ToolCallingAdvisor 的 return direct。"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (if-let [v (get (:tool-vars kernel) fn-key)]
      (tool/return-direct-tool? v)
      (boolean (get-in (:inline-meta kernel) [fn-key :return-direct])))))

(def ^:private default-retry-policy
  {:max-retries 2 :initial-delay-ms 200})

(defn retry-policy
  "工具的 :retry 声明（归一化）。仅 :transient 类错误按此策略重试；
   声明即承诺幂等（重试会重跑整条 tool filter 链）。

   返回: nil（未声明，不重试）| {:max-retries n :initial-delay-ms ms}"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))
        spec (if-let [v (get (:tool-vars kernel) fn-key)]
               (tool/retry-spec v)
               (get-in (:inline-meta kernel) [fn-key :retry]))]
    (when spec
      (merge default-retry-policy (when (map? spec) spec)))))

(defn tool-timeout
  "工具**自己声明**的 `:timeout`（毫秒）。var 工具查 `:tool/timeout` 元数据；
   **内联工具查 inline-meta 预计算 map（O(1)）**——与 `serial-tool?` / `retry-policy` /
   `return-direct-tool?` 逐字同款。

   只答「这个工具声明了什么」，不含引擎缺省——那一层见 `effective-tool-timeout`。

   返回: nil（未声明）| 正整数毫秒"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (if-let [v (get (:tool-vars kernel) fn-key)]
      (tool/timeout-spec v)
      (get-in (:inline-meta kernel) [fn-key :timeout]))))

(defn effective-tool-timeout
  "该工具**实际生效**的超时（毫秒），nil = 不超时。

   **缺省不超时**——框架不替调用方决定何时放弃。要限时须显式给出，两个来源：

     工具声明 `deftool {:timeout ms}`  >  引擎缺省 `(...-tool-calling-manager
     {:timeout ms})`  >  **不超时**

   工具声明最优先——它最清楚自己要跑多久（长任务的逃生舱就是「声明一个大的」）；
   引擎缺省次之，让部署方能整体封顶而不必逐个改工具。

   引擎缺省的读取优先走 `*active-manager-timeout*`（**当前正在执行的** manager
   的值，R4），回落到 kernel 的 `:tool-manager` 字段（直调 invoke-tool 不经
   manager 时）。

   由 `invoke-tool` 的 terminal 消费并强制：**开箱即生效，不需要挂任何 filter**。
   超时只包裹工具本体（terminal 内），不包裹 filter 链——审批等待不再吃掉超时
   预算（R1）。"
  [kernel fn-name]
  (or (tool-timeout kernel fn-name)
      tcm/*active-manager-timeout*
      (tcm/manager-timeout (:tool-manager kernel))))

;;; ============================================================
;;; Invoke API - invoke-tool（函数调用，经过 Filter 管道）
;;; ============================================================

(defn- build-func-def
  "构建 ToolRequest 的 :function 信息（供 tool filter 读取）。

   **var 工具与内联工具共用本函数**——曾经两个构造点分头维护，正是 `:timeout`
   对内联工具静默失效的根因（`:serial`/`:retry`/`:return-direct` 都有 inline
   分支，独 `:timeout` 漏了）。新增字段请只加在这里。"
  [fn-name tool-var]
  ;; 超时不在此列：它由 terminal 在 filter 链**之内**强制（只包裹工具本体，
  ;; 不包裹 filter 链——R1: 审批等待不再吃掉超时预算）。
  {:name      fn-name
   :schema    (when tool-var (:tool/schema (meta tool-var)))
   :sensitive (boolean (when tool-var (:tool/sensitive (meta tool-var))))})

(defn- timeout-result
  "超时时 terminal 返回的结果形状。`:transient` 类故可被 `:retry` 重试；
   不带 `:writes`（事务性）。"
  [t-ms]
  {:result (str "工具调用超时（" t-ms "ms）")
   :error  {:class :transient
            :message (str "timeout " t-ms "ms")}})

(defn- exec-with-timeout
  "在超时保护下执行工具本体 `body`（无参函数，返回 terminal 形状 map）。

   **超时只包裹工具本体，不包裹 filter 链**（R1/R3）——此前 `run-chain` 把整条
   filter 链（含 approval-filter 的阻塞 read）一起计时，操作员审批慢一点就超时。

   有 `t-ms` 时在虚拟线程上跑 body（`call-with-timeout`），到点 interrupt。
   body 自身的 try/catch 已处理非致命 Error 收敛 + 致命原样重抛——若 body 重抛了
   致命 Throwable，`call-with-timeout` 会收到 `[:err t]`，这里的 `:err` 分支再抛
   出去。

   **R2 诚实降级**：有超时即起 VT，与引擎线程模型无关——Sequential 的「调用方线程」
   与 ThreadPool 的「池线程」承诺在有超时时均不成立（VT 做活、引擎线程等 deref）。
   未声明超时（大多数工具）则零开销直接跑，引擎承诺照常。"
  [t-ms body]
  (if-not t-ms
    (body)
    (let [[tag v] (tool/call-with-timeout t-ms body)]
      (case tag
        :ok      v
        :err     (throw ^Throwable v)
        :timeout (timeout-result t-ms)))))

(defn invoke-tool
  "调用 Kernel 中注册的函数（经 tool filter 洋葱链）

    组装 ToolRequest {:function :args :context} → build-chain(:tool filters) 包裹
    → terminal 执行函数。filter 可改写 args、短路(不调 chain，如审批拒绝/熔断/
    限流/安全策略)、around(超时计时)。**:context 是请求侧只读字段**：filter 与
    工具都不改写它；工具的写意图经返回值 :writes 声明，由批次屏障处的
    reducer 折叠（见 context/apply-writes）。

    参数:
    - kernel:  Kernel 实例
    - fn-name: 函数名（关键字或字符串）
    - args:    参数 map
    - context: Context 对象（只读快照）

    返回:
    {:value result (:writes {k v}) (:error {:class :message})}

    错误:
    抛 ex-info（仅函数未找到；执行异常被 terminal 捕获为错误结果字符串 +
    :error 分类信息 {:class :semantic|:transient|:environment}，供屏障路由；
    失败调用无 :writes——写意图不生效）"
  [kernel fn-name args context]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (if-let [inline-handler (get (:inline-handlers kernel) fn-key)]
      ;; 内联工具（由 delegate-tool 等构建）：通过同一 filter 链执行
      (let [func-def (build-func-def fn-key nil)
            t-ms     (effective-tool-timeout kernel fn-key)
             terminal (fn [req]
                        (exec-with-timeout t-ms
                          (fn []
                            (try
                              (let [raw (inline-handler (:args req) (:context req))
                                    [res writes] (if (and (map? raw) (contains? raw :writes))
                                                   [(:result raw) (:writes raw)]
                                                   [raw nil])]
                                (cond-> {:result (if (string? res) res (pr-str res))}
                                  (seq writes) (assoc :writes writes)))
                              (catch Throwable t
                                (let [{:keys [message class]} (err/contain-throwable t)]
                                  {:result (str "错误: " message)
                                   :error  {:class class :message message}}))))))
            chain (filters/build-chain (keep :tool (:filters kernel)) terminal)
            out   (chain {:function func-def :args args :context context})]
        (cond-> {:value (:result out)}
          (:writes out) (assoc :writes (:writes out))
          (:error out)  (assoc :error (:error out))))

      ;; 普通 var 工具
      (let [found (find-function kernel fn-key)
            _ (when-not found
                (throw (ex-info (str "函数未找到: " fn-key)
                                {:fn-name fn-key
                                 :available (list-functions kernel)})))
            {:keys [tool-var]} found
            func-def (build-func-def fn-key tool-var)
            t-ms     (effective-tool-timeout kernel fn-key)
            terminal (fn [req]
                       (exec-with-timeout t-ms
                         (fn []
                            (let [exec (try (tool/invoke tool-var (:args req) (:context req))
                                            (catch Throwable t
                                              (let [{:keys [message class]} (err/contain-throwable t)]
                                                {:success false
                                                 :error message
                                                 :error-class class})))]
                             (if (:success exec)
                               (cond-> {:result (:result exec)}
                                 (:writes exec) (assoc :writes (:writes exec)))
                               {:result (str "错误: " (:error exec))
                                :error  {:class (or (:error-class exec) :semantic)
                                         :message (:error exec)}})))))
            chain (filters/build-chain (keep :tool (:filters kernel)) terminal)
            out   (chain {:function func-def :args args :context context})]
        (cond-> {:value (:result out)}
          (:writes out) (assoc :writes (:writes out))
          (:error out)  (assoc :error (:error out)))))))

;;; ============================================================
;;; Invoke API - invoke-chat（纯 LLM 调用，带 chat filter）
;;; ============================================================

(defn invoke-chat
  "发送 Chat Completion 请求（经 chat filter 洋葱链，不含工具调用循环）

    组装 ChatRequest → build-chain(:chat filters) 包裹 → terminal 调 LLM。
    memory 等「读历史 / 改写请求 / 加工响应」能力以 chat filter 形态注入；
    filter 可 around（短路 / 重试 / 计时），详见 kernel.filter。

    参数:
    - kernel:   Kernel 实例（需已配置 service）
    - messages: 消息列表（本轮 delta）
    - opts:     {:tools 工具 schema 列表 / :tool-choice / :system-prompt / :context}

    返回:
    {:response {:text \"...\" :tool-calls [...]}
     :context  updated-ctx}"
  [kernel messages opts]
  (let [service (:service kernel)
        _ (when-not service
            (throw (ex-info "Kernel 未配置 LLM 服务（请在 build-kernel 中提供 :service）"
                            {:kernel-keys (keys kernel)})))
        chat-fn (:chat-fn service)
        _ (when-not chat-fn
            (throw (ex-info "Service 缺少 :chat-fn"
                            {:service-keys (keys service)})))
        context (or (:context opts) (ctx/create))
        ;; 统一 ChatRequest：filter 可改写其中任意字段
        request {:messages      messages
                 :tools         (:tools opts)
                 :tool-choice   (:tool-choice opts)
                 :system-prompt (:system-prompt opts)
                 :context       context}
        ;; 最内层：真正调 LLM（chat-opts 由 request 当前字段重建，吃到 filter 的改写）
        terminal (fn [req]
                   (let [chat-opts (cond-> {}
                                     (some? (:tools req))         (assoc :tools (:tools req))
                                     (some? (:tool-choice req))   (assoc :tool-choice (:tool-choice req))
                                     (some? (:system-prompt req)) (assoc :system-prompt (:system-prompt req)))]
                     {:response (chat-fn (:messages req) chat-opts)
                      :context  (:context req)}))
        chain (filters/build-chain
                (keep :chat (:filters kernel))
                terminal)]
    (chain request)))

(defn invoke-chat-stream
  "invoke-chat 的流式版本：terminal 调 service 的 :stream-fn，token 经 on-token 实时回调。

    与 invoke-chat 共用同一条 chat filter 洋葱链（memory 等照常生效）；on-token 经 request
    透传到 terminal。:stream-fn 在流结束时返回最终归一化响应，故 memory-filter 落库的是
    **完整** assistant 消息——与同步路径历史不分叉。

    filter 的 :token-xform（transducer）在 terminal 内组合成出站 token 变换链：
    provider 原始 token → xform 链（注册顺序，靠前者先见原始 token）→ on-token。
    正常完流调 completion arity（缓冲 flush），stream-fn 抛异常则不 flush；
    下游 reduced（如 take）后不再喂 token，completion 照常。**只变换交付流，
    不改最终 :response**。设计见 docs/token-stream-filter-design.md。

    参数:
    - opts: 同 invoke-chat，外加 :on-token (fn [token-data] ...)

    返回: {:response {:text ... :tool-calls [...]} :context ctx}"
  [kernel messages opts]
  (let [service (:service kernel)
        _ (when-not service
            (throw (ex-info "Kernel 未配置 LLM 服务（请在 build-kernel 中提供 :service）"
                            {:kernel-keys (keys kernel)})))
        stream-fn (:stream-fn service)
        _ (when-not stream-fn
            (throw (ex-info "Service 缺少 :stream-fn（不支持流式）" {:service-keys (keys service)})))
        context (or (:context opts) (ctx/create))
        request {:messages      messages
                 :tools         (:tools opts)
                 :tool-choice   (:tool-choice opts)
                 :system-prompt (:system-prompt opts)
                 :on-token      (:on-token opts)
                 :context       context}
        token-xforms (seq (keep :token-xform (:filters kernel)))
        terminal (fn [req]
                   (let [chat-opts (cond-> {}
                                     (some? (:tools req))         (assoc :tools (:tools req))
                                     (some? (:tool-choice req))   (assoc :tool-choice (:tool-choice req))
                                     (some? (:system-prompt req)) (assoc :system-prompt (:system-prompt req)))
                         sink (:on-token req)
                         ;; :token-xform 链：chat 链之后组装（包裹链上存活的 on-token）。
                         ;; rf 每次现场实例化——有状态 xform 的作用域 = 单次 LLM 流。
                         rf (when token-xforms
                              ((apply comp token-xforms)
                               (fn ([acc] acc)
                                   ([acc tok] (when sink (sink tok)) acc))))
                         on-tok (if rf
                                  (let [done (volatile! false)]
                                    (fn [tok]
                                      (when-not @done
                                        ;; 下游 reduced（如 take）：早停，不再喂 token
                                        (when (reduced? (rf nil tok))
                                          (vreset! done true)))))
                                  sink)
                         response (stream-fn (:messages req) chat-opts on-tok)]
                     ;; 正常完流 flush（对齐 transduce：reduced 后 completion 照常）；
                     ;; stream-fn 抛异常则不执行——缓冲丢弃，半截答案不外泄
                     (when rf (rf nil))
                     {:response response
                      :context  (:context req)}))
        chain (filters/build-chain
                (keep :chat (:filters kernel))
                terminal)]
    (chain request)))
