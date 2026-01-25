(ns im.ttalk.agent.core.kernel.core
  "Kernel 核心 - 中央编排器

   参考 beamai_kernel 设计，Kernel 提供三类 API：

   Build API - 构建 Kernel:
     (-> (create-kernel-builder)
         (add-plugin weather-plugin)
         (add-service my-service)
         (add-filter logging-pre-filter)
         (build-kernel))

   Invoke API - 调用函数/LLM:
     (invoke-tool kernel :get-weather {:city \"北京\"} context)
     (invoke-chat kernel messages opts)
     (invoke kernel messages opts)

   Query API - 查询 Kernel 状态:
     (:tools kernel)          ;; 直接关键字访问
     (find-function kernel :get-weather)
     (list-functions kernel)

   Service 格式:
   Service 是一个 map，定义 LLM 调用接口：
     {:chat-fn           (fn [messages opts] -> {:text \"...\" :tool-calls [...] :assistant-msg {...}})
      :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}

   返回值格式:
     invoke-tool: {:value v :context ctx}
     invoke-chat: {:response r :context ctx}
     invoke:      {:response r :context ctx :tool-calls-made [...]}"
  (:require [clojure.string]
            [im.ttalk.agent.core.kernel.plugin :as kp]
            [im.ttalk.agent.core.kernel.filter :as filters]
            [im.ttalk.agent.core.kernel.context :as ctx]))

;;; ============================================================
;;; Kernel Record
;;; ============================================================

(defrecord Kernel [service plugins filters tools settings])

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
   {:service  nil
    :plugins  []
    :filters  []
    :settings settings}))

(defn add-plugin
  "添加 Plugin 到 builder

   参数:
   - builder: kernel builder
   - plugin:  KernelPlugin 实例

   返回:
   更新后的 builder"
  [builder plugin]
  (update builder :plugins conj plugin))

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
  "编译所有 Plugin 的 tool schema"
  [plugins]
  (vec (mapcat kp/get-schemas plugins)))

(defn build-kernel
  "构建最终 Kernel 实例

   参数:
   - builder: 配置完成的 builder

   返回:
   Kernel record"
  [builder]
  (let [tools (compile-tools (:plugins builder))]
    (->Kernel (:service builder)
              (:plugins builder)
              (:filters builder)
              tools
              (:settings builder))))

;;; ============================================================
;;; Query API
;;; ============================================================

(defn find-function
  "在 Kernel 的所有 Plugin 中查找函数

   支持:
   - 关键字或字符串名称
   - 短名格式（遍历所有 Plugin）

   参数:
   - kernel:  Kernel 实例
   - fn-name: 函数名

   返回:
   {:plugin plugin :tool-var var} 或 nil"
  [kernel fn-name]
  (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
    (some (fn [plugin]
            (when-let [v (kp/get-tool-var plugin fn-key)]
              {:plugin plugin :tool-var v}))
          (:plugins kernel))))

(defn list-functions
  "列出 Kernel 中所有注册的函数名称

   返回:
   关键字列表"
  [kernel]
  (vec (mapcat kp/list-function-names (:plugins kernel))))

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
        {:keys [plugin tool-var]} found
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
                          (let [do-exec #(kp/execute-tool plugin fn-key args context)]
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
      :tool-choice      :auto/:none/:required（默认 :auto）}

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
        tool-schemas (:tools kernel)
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
