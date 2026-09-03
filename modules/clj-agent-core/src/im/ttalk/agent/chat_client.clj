(ns im.ttalk.agent.chat-client
  "ChatClient 核心 - 中央编排器

    ChatClient 构建使用声明式 map：

      (build-chat-client {:chat-model my-chat-model
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
      (invoke-tool chat-client :get-weather {:city \"北京\"} context)
      (invoke-chat chat-client messages opts)
      (invoke chat-client messages opts)
      (invoke chat-client messages {:tags [:weather]})           ;; 只用带 :weather tag 的工具
      (invoke chat-client messages {:exclude-tags [:dangerous]}) ;; 排除危险工具

    Query API - 查询 ChatClient 状态:
      (tool-registry/tool-schemas chat-client)     ;; 所有 tool schemas
      (list-functions chat-client)                 ;; 所有函数名
      (find-function chat-client :get-weather)

    ChatModel 格式:
    ChatModel 是一个 map，定义 LLM 调用接口：
      {:chat-fn (fn [messages opts] -> {:text \"...\" :tool-calls [...]})}

    返回值格式:
      invoke-tool: {:value v (:writes {k v})}   ;; context 只读，写意图经 :writes
      invoke-chat: {:response r :context ctx}
      invoke:      {:response r :context ctx :tool-calls-made [...]}"
  (:require [clojure.string]
            [im.ttalk.agent.filter :as filters]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.model.error :as err]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.tool-registry :as registry]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; ChatClient Record
;;; ============================================================

;; tool-registry: tool-registry/ToolRegistry — 工具那一摊收成一个值（schema 列表 /
;; var 表 / 内联 handler 表 / ToolMeta 表）。四者总是一起产生、一起使用、一起被
;; 子 agent 整体替换，故「一个 ChatClient 有一个工具注册表」是类型上的事实，
;; 不是约定。**取 schema 列表走 `tool-registry/tool-schemas`**（原 `(:tools cc)`）。
;; hooks: filter/CompiledHooks — 装配期预折叠的四条链（:chat/:tool/:turn/
;; :iteration 各是 (fn [terminal] -> chain)，:token-xform 是 (fn [sink] -> rf)），
;; 同款思路：把 keep + reverse + reduce 从每次 invoke 挪到 build-chat-client。
;; **读它请走 `filter-hooks`，改 :filters 请走 `with-filters`**（见两者 docstring）。
(defrecord ChatClient [chat-model filters hooks tool-registry settings tool-manager])


(defn build-chat-client
  "构建 ChatClient 实例

    参数 opts map:
    - :chat-model  ChatModel map（必需）
    - :tools       tool var 向量（如 [#'get-weather]），也可混入内联工具 map
                   （含 :handler，由 delegate-tool 等动态构建）。**工具名必须
                   唯一**——var 与内联共用一个命名空间，重名装配期即抛
    - :filters     filter 向量（如 [memory-filter logging-filter]），注册顺序即执行顺序
    - :settings    额外设置（可选），如 {:max-tool-iterations 10}
    - :state-slots 状态槽声明（可选）{k {:init v0 :reduce (fn [old new] merged)}}——
                   工具批次 :writes 的合并语义（未声明的槽默认 last-writer）
    - :eligibility-fn 循环续跑判据（可选）(fn [response context] -> boolean)——
                   响应带 tool-call 时是否**真的**执行并续跑；返回 false 则
                   该响应按最终答案收尾（工具不执行）。缺省恒真，即「有
                   tool-call 就跑」。对标 Spring AI ToolExecutionEligibilityChecker

    返回:
    ChatClient record

    示例:
    (build-chat-client {:chat-model cm
                        :tools [#'get-weather #'get-time]
                        :filters [memory-filter retry-filter]
                        :settings {:max-tool-iterations 10}})"
  [{:keys [chat-model tools tool-vars filters settings state-slots eligibility-fn tool-manager]
    :or {tools [] filters [] settings {}}}]
  (let [all-tools (vec (or tool-vars tools))
        ;; 内联工具：map 且含 :handler fn（由 delegate-tool 等动态构建）
        inline-tools (filter #(and (map? %) (fn? (:handler %))) all-tools)
        var-tools    (filter var? all-tools)

        compiled-var-tools (vec (for [v var-tools :when (tool/tool-function? v)]
                                  (tool/get-schema v)))
        var-map            (into {} (for [v var-tools :when (tool/tool-function? v)]
                                      [(keyword (:name (tool/get-schema v))) v]))

        ;; 内联工具：schema 去掉 :handler，handler 由 registry 单独收着
        compiled-inline-tools (mapv #(dissoc % :handler) inline-tools)
        _ (when (some? tool-manager)
            (tool/check-timeout! ":tool-manager" (:timeout tool-manager)))
        ;; 装配期一次：两个来源汇成一个 ToolRegistry（校验先于建表，见 tool-registry）
        tool-registry (registry/build-registry
                        var-tools var-map inline-tools
                        (into compiled-var-tools compiled-inline-tools))
        ;; 装配期一次：归一化 filter → Filter record，四条链各预折一次
        hooks (filters/compile-hooks filters)]

    (->ChatClient (cm/as-chat-model chat-model)
                  (:source hooks)          ;; 归一化后的 filter 向量（hooks 与之同源）
                  hooks
                  tool-registry
                  (cond-> settings
                    state-slots (assoc :state-slots state-slots)
                    eligibility-fn (assoc :eligibility-fn eligibility-fn))
                  tool-manager)))



;;; ============================================================
;;; Invoke API - invoke-tool（函数调用，经过 Filter 管道）
;;; ============================================================

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
  "调用 ChatClient 中注册的函数（经 tool filter 洋葱链）

    组装 ToolRequest {:function :args :context} → 预编译的 :tool 链包裹
    → terminal 执行函数。filter 可改写 args、短路(不调 chain，如审批拒绝/熔断/
    限流/安全策略)、around(超时计时)。**:context 是请求侧只读字段**：filter 与
    工具都不改写它；工具的写意图经返回值 :writes 声明，由批次屏障处的
    reducer 折叠（见 context/apply-writes）。

    参数:
    - chat-client:  ChatClient 实例
    - fn-name: 函数名（关键字或字符串）
    - args:    参数 map
    - context: Context 对象（只读快照）

    返回:
    {:value result (:writes {k v}) (:error {:class :message})}

    错误:
    抛 ex-info（仅函数未找到；执行异常被 terminal 捕获为错误结果字符串 +
    :error 分类信息 {:class :semantic|:transient|:environment}，供屏障路由；
    失败调用无 :writes——写意图不生效）"
  [chat-client fn-name args context]
  (let [fn-key (registry/tool-key fn-name)
        ;; 一次查表拿全：ToolRequest 的 :function 段与超时声明都在 ToolMeta 里，
        ;; 装配期定死（名字 / schema / :sensitive / :timeout 全来自声明）。
        ;; 表里同时有内联工具与 var 工具，「函数未找到」也在这里一次判完。
        reg   (registry/registry-of chat-client)
        tmeta (or (get (:tool-meta reg) fn-key)
                  (throw (ex-info (str "函数未找到: " fn-key)
                                  {:fn-name fn-key
                                   :available (registry/list-functions chat-client)})))
        func-def (:func-def tmeta)
        t-ms (registry/effective-timeout* chat-client tmeta)
        ;; 工具本体：内联 handler 与 var 工具只在这里分叉，外面的
        ;; ToolRequest 组装 / filter 链 / 返回值拆包三段完全共用
        body (if-let [inline-handler (get (:inline-handlers reg) fn-key)]
               (fn [req]
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
                        :error  {:class class :message message}}))))
               (let [tool-var (get (:tool-vars reg) fn-key)]
                 (fn [req]
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
        terminal (fn [req] (exec-with-timeout t-ms #(body req)))
        chain ((:tool (filters/filter-hooks chat-client)) terminal)
        out   (chain {:function func-def :args args :context context})]
    (cond-> {:value (:result out)}
      (:writes out) (assoc :writes (:writes out))
      (:error out)  (assoc :error (:error out)))))

(defn- chat-model-of
  "取 ChatClient 的 ChatModel；未配置则抛（四个 invoke-chat* 入口共用同一句话）。"
  [chat-client]
  (or (:chat-model chat-client)
      (throw (ex-info "ChatClient 未配置 ChatModel（请在 build-chat-client 中提供 :chat-model）"
                      {:chat-client-keys (keys chat-client)}))))

(defn- token-sink
  "组装 `:token-xform` 出站变换，返回 `[rf on-tok]`。

   `rf` 每次现场实例化（有状态 xform 的作用域 = 单次 LLM 流；comp 在装配期做过）；
   下游 reduced（如 take）后不再喂 token。`rf` 为 nil 表示没挂 token filter，
   此时 on-tok 就是原 sink（零包装）。flush（`(rf nil)`）由调用方在**正常完流**
   时执行——同步版在 terminal 里，异步版在 fmap 的续延里。"
  [make-rf sink]
  (if-let [rf (when make-rf (make-rf sink))]
    [rf (let [done (volatile! false)]
          (fn [tok]
            (when-not @done
              (when (reduced? (rf nil tok))
                (vreset! done true)))))]
    [nil sink]))

;;; ============================================================
;;; Invoke API - invoke-chat（纯 LLM 调用，带 chat filter）
;;; ============================================================

(defn invoke-chat
  "发送 Chat Completion 请求（经 chat filter 洋葱链，不含工具调用循环）

    组装 ChatRequest → 预编译的 :chat 链包裹 → terminal 调 LLM。
    memory 等「读历史 / 改写请求 / 加工响应」能力以 chat filter 形态注入；
    filter 可 around（短路 / 重试 / 计时），详见 chat-client.filter。

    参数:
    - chat-client:   ChatClient 实例（需已配置 chat-model）
    - messages: 消息列表（本轮 delta）
    - opts:     {:tools 工具 schema 列表 / :tool-choice / :system-prompt / :context}
                除 :context 外的键全部进 ChatRequest 的 :options（provider 私有
                参数照样穿得过去——不筛白名单）

    返回:
    `ChatClientResponse`{:response ChatResponse :context updated-ctx}"
  [chat-client messages opts]
  (let [model (chat-model-of chat-client)
        context (or (:context opts) (ctx/create))
        ;; 两层请求：外层 ChatClientRequest 走 filter 链（带 :context），
        ;; 内层 ChatRequest 是真正下发给 ChatModel 的那一段
        request (filters/->ChatClientRequest
                  (req/chat-request messages (dissoc opts :context))
                  context
                  nil)
        ;; 最内层：真正调 ChatModel（重试在它**内部**，故 filter 只看到一次
        ;; 逻辑调用——memory 不会把同一轮 delta 写两遍）。内层 ChatRequest
        ;; 原样交出去，filter 的改写（tool-search 收窄 :tools 等）自然吃得到
        terminal (fn [req]
                   (filters/->ChatClientResponse
                     (cm/call model (:request req))
                     (:context req)))
        chain ((:chat (filters/filter-hooks chat-client)) terminal)]
    (chain request)))

(defn invoke-chat-stream
  "invoke-chat 的流式版本：terminal 调 chat-model 的 :stream-fn，token 经 on-token 实时回调。

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

    返回: `ChatClientResponse`{:response ChatResponse :context ctx}"
  [chat-client messages opts]
  (let [model (chat-model-of chat-client)
        context (or (:context opts) (ctx/create))
        ;; :on-token 是 ChatClientRequest 的字段而非 ChatRequest 的 option——
        ;; sink 永远不该出现在 wire 上，两层结构让这件事无需靠白名单去保证
        request (filters/->ChatClientRequest
                  (req/chat-request messages (dissoc opts :context :on-token))
                  context
                  (:on-token opts))
        hooks (filters/filter-hooks chat-client)
        ;; 装配期预 comp 好的 (fn [sink] -> rf)，无 :token-xform filter 则 nil
        make-rf (:token-xform hooks)
        terminal (fn [req]
                   (let [[rf on-tok] (token-sink make-rf (:on-token req))
                         response (cm/stream-call model (:request req) on-tok)]
                     ;; 正常完流 flush（对齐 transduce：reduced 后 completion 照常）；
                     ;; stream-fn 抛异常则不执行——缓冲丢弃，半截答案不外泄
                     (when rf (rf nil))
                     (filters/->ChatClientResponse response (:context req))))
        chain ((:chat hooks) terminal)]
    (chain request)))

;;; ============================================================
;;; Invoke API - 异步孪生（:chat 链终端返回 deferred）
;;; ============================================================
;;;
;;; 与同步版**只差终端那一行**（`cm/call` → `cm/call-async*`）加响应侧的 fmap。
;;; 链的折叠代码（build-chain / compile-chain）一个字不用改——它只传闭包，
;;; 不看返回值类型；`:chat` filter 也不用改（内置的响应侧已全走 flt/fmap）。
;;; 设计见 docs/async-chat-model-design.md §7。

(defn invoke-chat-async
  "`invoke-chat` 的异步版：返回 **deferred<`ChatClientResponse`>**，调用线程不阻塞。

   ChatModel 实现 `IAsyncChatModel` 时走原生异步；否则虚拟线程兜底
   （`cm/call-async*`）——任何 provider 都可用。

   ⚠️ 走这条路时 `:chat` filter 的 `(chain req)` **真的返回 deferred**，响应侧
   必须用 `flt/fmap` / `flt/fbind`（`(let [r (chain req)] …)` 不报错，但拿到的是
   future——静默错）。"
  [chat-client messages opts]
  (let [model (chat-model-of chat-client)
        context (or (:context opts) (ctx/create))
        request (filters/->ChatClientRequest
                  (req/chat-request messages (dissoc opts :context))
                  context
                  nil)
        terminal (fn [req]
                   (filters/fmap (cm/call-async* model (:request req))
                                 #(filters/->ChatClientResponse % (:context req))))
        chain ((:chat (filters/filter-hooks chat-client)) terminal)]
    (chain request)))

(defn invoke-chat-stream-async
  "`invoke-chat-stream` 的异步版：返回 deferred<`ChatClientResponse`>。

   `:token-xform` 的组装位置与同步版完全一致（chat 链之后、terminal 之内），
   flush 挪进 fmap 的续延——**正常完流才 flush，异常（error channel）不 flush**，
   与同步版的 try 语义逐字相同。

   ⚠️ on-token 的调用线程不保证（见 `cm/stream-call-async*`）。"
  [chat-client messages opts]
  (let [model (chat-model-of chat-client)
        context (or (:context opts) (ctx/create))
        request (filters/->ChatClientRequest
                  (req/chat-request messages (dissoc opts :context :on-token))
                  context
                  (:on-token opts))
        hooks (filters/filter-hooks chat-client)
        make-rf (:token-xform hooks)
        terminal (fn [req]
                   (let [[rf on-tok] (token-sink make-rf (:on-token req))]
                     (filters/fmap (cm/stream-call-async* model (:request req) on-tok)
                                   (fn [response]
                                     (when rf (rf nil))
                                     (filters/->ChatClientResponse response (:context req))))))
        chain ((:chat hooks) terminal)]
    (chain request)))
