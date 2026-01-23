(ns im.ttalk.agent.core.kernel.core
  "Kernel 核心 - 中央编排器

   参考 beamai_kernel.erl 设计，Kernel 提供三类 API：

   Build API - 构建 Kernel:
     (-> (create-kernel-builder)
         (add-plugin weather-plugin)
         (add-service my-service)
         (add-filter logging-filter)
         (build-kernel))

   Invoke API - 调用函数/LLM:
     (invoke kernel :get-weather {:city \"北京\"})
     (invoke kernel :get-weather {:city \"北京\"} context)
     (invoke-chat kernel messages opts)
     (invoke-chat-with-tools kernel messages opts)

   Query API - 查询 Kernel 状态:
     (get-tools kernel)
     (find-function kernel :get-weather)
     (list-functions kernel)

   Service 格式:
   Service 是一个 map，定义 LLM 调用接口：
     {:chat-fn           (fn [messages opts] -> {:text \"...\" :tool-calls [...] :assistant-msg {...}})
      :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}

   其中:
   - chat-fn: 调用 LLM，返回归一化响应
   - build-result-msgs: 将 assistant 消息和工具执行结果转为消息列表，
     用于追加到对话历史后再次调用 LLM"
  (:require [im.ttalk.agent.core.kernel.plugin :as kp]
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

   Filter 签名: (fn [context next-fn] -> result)

   参数:
   - builder:   kernel builder
   - filter-fn: filter 函数

   返回:
   更新后的 builder"
  [builder filter-fn]
  (update builder :filters conj filter-fn))

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

(defn get-tools
  "获取 Kernel 中所有工具的 schema 列表"
  [kernel]
  (:tools kernel))

(defn get-plugins
  "获取 Kernel 中所有 Plugin"
  [kernel]
  (:plugins kernel))

(defn get-filters
  "获取 Kernel 中所有 Filter"
  [kernel]
  (:filters kernel))

(defn get-service
  "获取 Kernel 的 LLM 服务

   返回:
   service map 或 nil"
  [kernel]
  (:service kernel))

(defn find-function
  "在 Kernel 的所有 Plugin 中查找函数

   支持:
   - 关键字或字符串名称
   - \"plugin.func\" 格式（从指定 Plugin 查找）
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
;;; Invoke API - invoke（函数调用，经过 Filter 管道）
;;; ============================================================

(defn- run-invoke-pipeline
  "执行函数调用管道：Filter 链 → 函数执行

   参数:
   - kernel:  Kernel 实例
   - fn-name: 函数名（keyword）
   - args:    参数 map
   - context: Context 对象

   返回:
   {:success bool :result any :error string :context ctx}"
  [kernel fn-name args context]
  (let [filter-fns (:filters kernel)
        ;; 最终执行函数
        exec-fn (fn [filter-ctx]
                  (if-let [{:keys [plugin]} (find-function kernel fn-name)]
                    (kp/execute-tool plugin fn-name args (:context filter-ctx))
                    {:success false
                     :error (str "函数未找到: " fn-name)}))
        ;; 构建 filter context
        filter-ctx (ctx/create-invocation-context
                     (ctx/create-tool-context fn-name args nil)
                     kernel
                     (ctx/get-history context)
                     context)]
    (if (seq filter-fns)
      (let [chain (filters/build-filter-chain filter-fns exec-fn)]
        (chain filter-ctx))
      (exec-fn filter-ctx))))

(defn invoke
  "调用 Kernel 中注册的函数（经过 Filter 管道）

   执行流程：查找函数 → Filter 链 → 函数执行 → 返回结果。
   等同于 Erlang beamai_kernel:invoke/3,4。

   参数:
   - kernel:  Kernel 实例
   - fn-name: 函数名（关键字或字符串）
   - args:    参数 map
   - context: (可选) Context 对象

   返回:
   {:success bool :result any :error string :context ctx}"
  ([kernel fn-name args]
   (invoke kernel fn-name args (ctx/create)))
  ([kernel fn-name args context]
   (let [fn-key (if (keyword? fn-name) fn-name (keyword fn-name))]
     (run-invoke-pipeline kernel fn-key args context))))

;;; ============================================================
;;; Invoke API - invoke-chat（纯 LLM 调用）
;;; ============================================================

(defn invoke-chat
  "发送 Chat Completion 请求（不含工具调用循环）

   纯 LLM 调用，不自动执行工具。
   等同于 Erlang beamai_kernel:invoke_chat/3。

   参数:
   - kernel:   Kernel 实例（需已配置 service）
   - messages: 消息列表
   - opts:     选项 map（传递给 service 的 chat-fn）
     {:tools       工具 schema 列表（可选，默认不传）
      :tool-choice :auto/:none/:required（可选）}

   返回:
   {:text \"...\" :tool-calls [...] :assistant-msg {...}} 或抛出异常"
  [kernel messages opts]
  (let [service (:service kernel)]
    (when-not service
      (throw (ex-info "Kernel 未配置 LLM 服务（调用 add-service）"
                      {:kernel-keys (keys kernel)})))
    (let [chat-fn (:chat-fn service)]
      (when-not chat-fn
        (throw (ex-info "Service 缺少 :chat-fn"
                        {:service-keys (keys service)})))
      (chat-fn messages opts))))

;;; ============================================================
;;; Invoke API - invoke-chat-with-tools（工具调用循环）
;;; ============================================================

(def ^:private default-max-iterations
  "工具调用循环默认最大次数"
  10)

(defn- execute-tool-calls
  "批量执行工具调用（经过 Filter 链），传递 context

   使用 reduce 使 context 在多个工具调用间逐步传递。

   参数:
   - kernel:     Kernel 实例
   - tool-calls: [{:id \"...\" :name \"...\" :input {...}}]
   - messages:   当前消息历史
   - context:    Context 对象

   返回:
   {:results [{:tool-id ... :name ... :result ... :error ... :context ...} ...]
    :context <updated-context>}"
  [kernel tool-calls messages context]
  (let [filter-fns (:filters kernel)]
    (reduce
      (fn [{:keys [results ctx]} tc]
        (let [fn-name (keyword (:name tc))
              args    (:input tc)
              tool-id (:id tc)
              ;; 最终执行函数
              exec-fn (fn [filter-ctx]
                        (if-let [{:keys [plugin]} (find-function kernel fn-name)]
                          (let [{:keys [success result error context]}
                                (kp/execute-tool plugin fn-name args (:context filter-ctx))]
                            (cond-> {:tool-id tool-id
                                     :name    fn-name
                                     :result  (when success result)
                                     :error   error}
                              context (assoc :context context)))
                          {:tool-id tool-id
                           :name    fn-name
                           :result  nil
                           :error   (str "函数未找到: " (name fn-name))}))
              ;; 构建 filter context
              filter-ctx (ctx/create-invocation-context
                           (ctx/create-tool-context fn-name args tool-id)
                           kernel
                           messages
                           ctx)
              ;; 执行（经过 filter 链或直接执行）
              result (if (seq filter-fns)
                       (let [chain (filters/build-filter-chain filter-fns exec-fn)]
                         (chain filter-ctx))
                       (exec-fn filter-ctx))
              ;; 提取更新后的 context
              new-ctx (or (:context result) ctx)]
          {:results (conj results result)
           :ctx     new-ctx}))
      {:results [] :ctx context}
      tool-calls)))

(defn- tool-calling-loop
  "工具调用循环

   LLM 返回 tool_calls 时自动执行对应函数，
   将结果拼入消息后再次请求 LLM，
   循环直到 LLM 返回文本响应或达到最大迭代次数。

   参数:
   - kernel:   Kernel 实例
   - messages: 消息列表
   - opts:     chat 选项
   - max-iter: 剩余最大迭代次数
   - context:  Context 对象

   返回:
   {:text \"...\" :tool-calls-made [...] :raw-response ... :context ctx}"
  [kernel messages opts max-iter context]
  (let [service    (:service kernel)
        chat-fn    (:chat-fn service)
        build-msgs (:build-result-msgs service)]
    (loop [msgs           messages
           remaining      max-iter
           all-tool-calls []
           ctx            context]

      (when (zero? remaining)
        (throw (ex-info "工具调用循环次数超过上限"
                        {:max-iterations max-iter
                         :tool-calls-made all-tool-calls})))

      (let [response (chat-fn msgs opts)]
        (if (seq (:tool-calls response))
          ;; 工具调用分支
          (let [tool-calls (:tool-calls response)
                ;; 执行工具（经过 Filter 链），传递 context
                {:keys [results ctx]} (execute-tool-calls kernel tool-calls msgs ctx)
                ;; 构建追加消息（由 service 的 build-result-msgs 负责格式化）
                new-msgs (build-msgs (:assistant-msg response) results)
                ;; 追加到历史
                updated-msgs (into msgs new-msgs)]
            (recur updated-msgs
                   (dec remaining)
                   (into all-tool-calls
                         (mapv (fn [tc r]
                                 {:name   (:name tc)
                                  :args   (:input tc)
                                  :result (:result r)
                                  :error  (:error r)})
                               tool-calls results))
                   ctx))

          ;; 文本响应分支
          {:text            (:text response)
           :tool-calls-made all-tool-calls
           :raw-response    response
           :context         ctx})))))

(defn invoke-chat-with-tools
  "发送 Chat Completion 请求并驱动工具调用循环

   自动将 Kernel 中所有注册函数的 tool schema 传给 LLM。
   LLM 返回 tool_calls 时自动执行对应函数，将结果拼入消息后再次请求 LLM，
   循环直到 LLM 返回文本响应或达到最大迭代次数。

   等同于 Erlang beamai_kernel:invoke_chat_with_tools/3。

   参数:
   - kernel:   Kernel 实例（需注册函数和 LLM 服务）
   - messages: 消息列表
   - opts:     选项 map
     {:tool-choice        :auto/:none/:required（默认 :auto）
      :max-iterations     最大循环次数（默认 10）
      :context            Context 对象（可选，默认创建空 Context）
      :on-tool-call       工具调用回调 (fn [tool-call] ...)
      :on-tool-result     工具结果回调 (fn [result] ...)}

   返回:
   {:text            最终文本回答
    :tool-calls-made 已执行的工具调用记录列表
    :raw-response    最后一次归一化响应
    :context         最终 Context 对象}"
  [kernel messages opts]
  (when-not (:service kernel)
    (throw (ex-info "Kernel 未配置 LLM 服务（调用 add-service）"
                    {:kernel-keys (keys kernel)})))
  (let [tool-schemas (get-tools kernel)
        max-iter     (or (:max-iterations opts)
                         (get-in kernel [:settings :max-tool-iterations])
                         default-max-iterations)
        context      (or (:context opts) (ctx/create))
        chat-opts    (assoc (dissoc opts :context :max-iterations)
                            :tools tool-schemas
                            :tool-choice (or (:tool-choice opts) :auto))]
    (tool-calling-loop kernel messages chat-opts max-iter context)))
