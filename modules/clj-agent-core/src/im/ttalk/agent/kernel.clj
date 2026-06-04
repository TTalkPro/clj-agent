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
  (:require [im.ttalk.agent.advisor :as filters]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :as tool]))

;;; ============================================================
;;; Kernel Record
;;; ============================================================

(defrecord Kernel [service filters tools tool-vars settings])

;;; ============================================================
;;; Build API
;;; ============================================================

(defn build-kernel
  "构建 Kernel 实例

    参数 opts map:
    - :service   LLM Service map（必需）
    - :tools     tool var 向量（如 [#'get-weather]）
    - :filters   filter 向量（如 [memory-filter logging-filter]），注册顺序即执行顺序
    - :settings  额外设置（可选），如 {:max-tool-iterations 10}

    返回:
    Kernel record

    示例:
    (build-kernel {:service svc
                   :tools [#'get-weather #'get-time]
                   :filters [memory-filter retry-filter]
                   :settings {:max-tool-iterations 10}})"
  [{:keys [service tools tool-vars filters settings]
    :or {tools [] filters [] settings {}}}]
  (let [tool-vars-list (vec (or tool-vars tools))
        compiled-tools (vec (for [v tool-vars-list
                                  :when (tool/tool-function? v)]
                              (tool/get-schema v)))
        tool-vars-map (into {}
                            (for [v tool-vars-list
                                  :when (tool/tool-function? v)]
                              [(keyword (:name (tool/get-schema v))) v]))]
    (->Kernel service
              (vec filters)
              compiled-tools
              tool-vars-map
              settings)))

;;; ============================================================
;;; 向后兼容的 Builder API（已废弃，保留兼容）
;;; ============================================================

(defn create-kernel-builder
  "已废弃：请使用 build-kernel
    保留以兼容旧代码。"
  ([]
   (create-kernel-builder {}))
  ([settings]
   {:service nil
    :tools []
    :filters []
    :settings settings}))

(defn add-tool
  "已废弃：请使用 build-kernel 的 :tools 参数"
  [builder tool-var]
  (update builder :tools conj tool-var))

(defn add-tools
  "已废弃：请使用 build-kernel 的 :tools 参数"
  [builder tool-vars]
  (update builder :tools into tool-vars))

(defn add-service
  "已废弃：请使用 build-kernel 的 :service 参数"
  [builder service]
  (assoc builder :service service))

(defn add-filter
  "已废弃：请使用 build-kernel 的 :filters 参数"
  [builder filter-def]
  (update builder :filters conj filter-def))

(defn ^:no-doc build-kernel-from-builder
  "从 builder map 构建 Kernel（内部使用，支持旧版 builder API）"
  [builder]
  (build-kernel {:service (:service builder)
                 :tools (vec (:tools builder))
                 :filters (vec (:filters builder))
                 :settings (:settings builder)}))

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

;;; ============================================================
;;; Invoke API - invoke-tool（函数调用，经过 Filter 管道）
;;; ============================================================

(defn- build-func-def
  "构建 ToolRequest 的 :function 信息（供 tool filter 读取）"
  [fn-name tool-var]
  (let [schema (when tool-var
                 (:tool/schema (meta tool-var)))]
    {:name      fn-name
     :schema    schema
     :sensitive (when tool-var
                  (boolean (:tool/sensitive (meta tool-var))))}))

(defn invoke-tool
  "调用 Kernel 中注册的函数（经 tool filter 洋葱链）

    组装 ToolRequest {:function :args :context} → build-chain(:tool filters) 包裹
    → terminal 执行函数。filter 可改写 args/context、短路(不调 chain，如审批拒绝/熔断/
    限流/安全策略)、around(超时计时)。

    参数:
    - kernel:  Kernel 实例
    - fn-name: 函数名（关键字或字符串）
    - args:    参数 map
    - context: Context 对象

    返回:
    {:value result :context updated-ctx}

    错误:
    抛 ex-info（仅函数未找到；执行异常被 terminal 捕获为错误结果字符串）"
  [kernel fn-name args context]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))
        found (find-function kernel fn-key)
        _ (when-not found
            (throw (ex-info (str "函数未找到: " fn-key)
                            {:fn-name fn-key
                             :available (list-functions kernel)})))
        {:keys [tool-var]} found
        func-def (build-func-def fn-key tool-var)
        ;; 最内层：执行函数。异常捕获为错误结果（不中断 filter 链）
        terminal (fn [req]
                   (let [exec (try (tool/invoke tool-var (:args req) (:context req))
                                   (catch Exception e
                                     {:success false :error (.getMessage e)}))
                         value (if (:success exec)
                                 (:result exec)
                                 (str "错误: " (:error exec)))]
                     {:result value :context (or (:context exec) (:context req))}))
        chain (filters/build-chain
                (keep :tool (:filters kernel))
                terminal)
        out (chain {:function func-def :args args :context context})]
    {:value (:result out) :context (:context out)}))

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
    {:response {:text \"...\" :tool-calls [...] :assistant-msg {...}}
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
