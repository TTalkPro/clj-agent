(ns im.ttalk.agent.model
  "LLM Provider 协议定义

   定义所有 LLM 提供商必须实现的统一接口。
   第三方只需依赖 clj-agent-core 即可实现自定义 provider。

   使用示例：

   (defrecord MyProvider [api-key]
     ILLMProvider
     (provider-name [_] :my-provider)
     (call-llm [this config messages tools] ...)
     (extract-tool-calls [_ response] ...)
     (extract-text [_ response] ...)
     (build-tool-result [_ tool-id content] ...))")

(set! *warn-on-reflection* true)

;;; ============================================================
;;; LLM Provider 协议
;;; ============================================================

(defprotocol ILLMProvider
  "LLM Provider 统一接口

   必需方法：
   - provider-name     返回提供商名称
   - call-llm          调用 LLM API（同步）
   - extract-tool-calls 从响应中提取工具调用
   - extract-text       从响应中提取文本
   - build-tool-result  构建工具结果消息

   可选方法（有默认实现）：
   - call-llm-stream         流式调用
   - supports-function-calling? 是否支持 Function Call
   - supports-stream?         是否支持流式调用
   - tool->schema            工具转 Schema

   注：历史上的 build-assistant-message / build-result-messages 已移除——对话历史
   由 filter/memory 的 response->neutral（中立消息）统一构建，provider 无需也不再
   实现这两个方法。"

  ;; 基本信息
  (provider-name [this]
    "返回提供商名称（关键字）

     示例：(provider-name provider) ; => :anthropic")

  ;; 核心 API
  (call-llm [this config messages tools]
    "调用 LLM API（同步）

     参数：
     - config:   配置 {:model \"...\" :max-tokens n :temperature f}
     - messages: 消息列表 [{:role \"user\" :content \"...\"}]
     - tools:    工具定义列表

     返回：原始 API 响应")

  (call-llm-stream [this config messages tools on-token]
    "流式调用 LLM API

     参数：
     - config:   配置（同 call-llm）
     - messages: 消息列表
     - tools:    工具定义列表
     - on-token: 回调函数 (fn [{:keys [token index]}] ...)

     返回：最终完整响应

     默认实现：回退到非流式调用")

  ;; 响应解析
  (extract-tool-calls [this response]
    "从响应中提取工具调用

     返回：工具调用列表 [{:id \"...\" :name \"字符串\" :args {...}}]（与中立消息 tool-call 同构）")

  (extract-text [this response]
    "从响应中提取文本内容

     返回：字符串")

  (build-tool-result [this tool-id content]
    "构建工具结果消息

     参数：
     - tool-id: 工具调用 ID
     - content: 工具执行结果（字符串）

     返回：提供商特定的消息格式")

  ;; 能力查询
  (supports-function-calling? [this]
    "是否支持 Function Call

     返回：boolean")

  (supports-stream? [this]
    "是否支持流式调用

     返回：boolean")

  ;; Schema 转换
  (tool->schema [this tool]
    "将工具定义转换为提供商特定格式

     参数：
     - tool: {:name :keyword :description \"...\" :parameters {...}}

     返回：提供商特定的 schema 格式"))

;;; ============================================================
;;; 可选协议：不透明回放载荷
;;; ============================================================

(defprotocol IReplayableResponse
  "**可选**协议：响应里存在「必须原样带回下一轮」的不透明数据时实现它。

   为什么不加进 ILLMProvider：那是 DIP 的端口，加方法 = 所有实现方（含仓库外的）
   破坏性变更。差异化能力一律走**独立可选协议 + satisfies? 探测**——
   有则用、无则走原路径。能力是发现出来的，不是继承出来的。

   **本协议不得提供 extend-type Object 默认实现。** ILLMProvider 就因为有兜底，
   `satisfies?` 对任意非 nil 对象都返回 true（见 provider? 的注释）；本协议的
   全部机制就建立在 satisfies? 上，加了兜底等于当场失效。

   动机（三家同一个问题的三个方言，见 docs/provider-variant-design.md §2.2）：
   Anthropic thinking 块 + signature、MiniMax 的整段 response.content、
   Gemini thought_signature——都是**中立层看不懂也不该看懂、但必须原封不动送回去**
   的数据。实测代价（M3，40 次/臂）：不送回去，正确率 100% → 82.5%（§7.5.3）。

   只有**抽取**这一个方法：还原发生在各 provider 自己的 wire 转换器里
   （它本就认识自家格式），无需经协议绕一圈。缺了才加，别为对称性加。"
  (replay-blocks [this raw-response]
    "从原始响应抽出需要原样回放的载荷。

     返回 {:format <keyword> :data <原样载荷>}；没有则返回 nil。
     :format 是**降级判据**——历史跨 provider 复用时，认不出 format 的一方
     必须当它不存在（见 wire/anthropic assistant->wire）。"))

(defprotocol IAsyncLLMProvider
  "**可选**协议：provider 有原生异步 HTTP 时实现它，让调用不占线程。

   为什么不加进 ILLMProvider：同 `IReplayableResponse` 的理由——那是 DIP 的端口，
   加方法 = 所有实现方（含仓库外的）破坏性变更。**同样不得 `extend-type Object`
   兜底**：兜了 `satisfies?` 就恒真，探测机制当场失效。

   **没实现不等于不能异步**：`chat-model/call-async*` 探测不到会用虚拟线程包同步
   调用兜底，调用方永远拿得到 deferred。实现本协议只是省掉那根线程——收益在
   超高扇出场景才显著，判据见 docs/async-chat-model-design.md §2。

   **返回值形状必须与同步版逐字相同**（`call-llm` 的原始响应，不是 HTTP map）：
   ChatModel 层对两条路径用的是同一个 `normalize-response`。历史上仓库里那套
   `xxx-call-async` 旁路正是栽在这——它把裸 HTTP map 交给 callback，绕过了
   wire 转换与归一化，于是永远接不进 filter 链（§0）。"
  (call-llm-async [this config messages tools]
    "异步调用 LLM。返回 deferred<原始响应>（形状同 `call-llm`）。
     失败：deferred 落 error channel，异常即 canonical error 的 ex-info。")
  (call-llm-stream-async [this config messages tools on-token]
    "异步流式调用。返回 deferred<原始响应>（形状同 `call-llm-stream`）。

     **on-token 的调用线程不保证**——异步实现里 token 在传输层的 executor
     上派发，不再是调用线程。取消令牌的登记必须在**调用线程**完成
     （动态 var 不跨异步边界），见 docs/async-chat-model-design.md §6。"))

;;; ============================================================
;;; 默认实现
;;; ============================================================

(extend-type Object
  ILLMProvider

  ;; 默认流式调用：回退到非流式
  (call-llm-stream [this config messages tools on-token]
    (let [response (call-llm this config messages tools)
          text (extract-text this response)]
      (when (and on-token (seq text))
        (on-token {:token text :index 0}))
      response))

  ;; 默认能力：不支持
  (supports-function-calling? [_] false)
  (supports-stream? [_] false)

  ;; 默认 schema 转换：原样返回
  (tool->schema [_ tool] tool))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn provider?
  "检查对象是否实现了 ILLMProvider 协议

   参数：
   - x: 任意对象

   返回：
   boolean

   注意：不能用 (satisfies? ILLMProvider x)。本协议为可选方法提供了
   `extend-type Object` 默认实现，导致 satisfies? 对任意非 nil 对象都返回 true
   （普通 map、字符串也会被误判为 provider）。改为精确判定：
   - 内联 defrecord 实现 → 是协议生成接口的实例；
   - extend/extend-protocol 注册的类型 → 出现在 :impls（排除 Object 兜底）。"
  [x]
  (and (some? x)
       (or (instance? (:on-interface ILLMProvider) x)
           (some (fn [c] (and (not= Object c) (instance? c x)))
                 (keys (:impls ILLMProvider))))))


(defn call-simple
  "简化的 LLM 调用（无工具）

   参数：
   - provider: ILLMProvider 实例
   - config:   配置
   - messages: 消息列表

   返回：
   文本响应字符串"
  [provider config messages]
  (let [resp (call-llm provider config messages nil)]
    (extract-text provider resp)))
