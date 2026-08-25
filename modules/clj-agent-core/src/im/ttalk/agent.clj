(ns im.ttalk.agent
  "Facade 入口：core 的统一门面

   对照 beamai 的 `beamai.erl`——真实模块按职责拆开，**门面只负责「常用的那些
   东西在一个地方找得到」**。它不实现任何逻辑，每个函数都是一行转发；要用到
   门面没暴露的东西，直接 require 背后的 ns 即可，两条路等价。

   ```clojure
   (require '[im.ttalk.agent :as ai])

   (ai/deftool get-weather \"查天气\" [[city :string \"城市\"]]
     (str city \": 晴 25°C\"))

   (def cc (ai/chat-client {:chat-model (ai/chat-model provider {:model \"gpt-4\"})
                            :tools   [#'get-weather]
                            :filters [(ai/filter :log :chat my-around)]}))

   (ai/chat cc [{:role :user :content \"北京天气？\"}] {})
   ```

   **工具调用循环不在这里**——LLM ↔ Tool 的多轮编排属于 Agent 层，见
   `im.ttalk.agent.simple-agent`（clj-agent-client 模块）。core 只给两个原语：
   单次 LLM 调用与单次工具调用，各自经过 filter 洋葱链。

   背后的分工：

   | ns | 管什么 |
   |---|---|
   | `im.ttalk.agent.chat-model`    | 一次 LLM 调用：选项合并 → **重试** → 响应归一化 |
   | `im.ttalk.agent.chat-client`   | filter 洋葱链 + 工具装配；`invoke-chat` / `invoke-tool` |
   | `im.ttalk.agent.tool-registry` | 工具声明表：装配期建表 + 运行期查询 |
   | `im.ttalk.agent.filter`        | filter 契约、链合成、内置 filter 集 |
   | `im.ttalk.agent.model.*`       | 中立边界类型：消息 / 请求 / 响应 / 错误 / 内容 |
   | `im.ttalk.agent.retry`         | 通用重试（判据取自 canonical error） |"
  ;; `filter` 遮蔽 clojure.core/filter —— 门面里它是一等公民，显式 exclude
  ;; 掉警告；本 ns 自身不用 core 的 filter。
  (:refer-clojure :exclude [filter])
  (:require [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.tool-registry :as registry]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; ChatModel —— 一次 LLM 调用
;;; ============================================================

(defn chat-model
  "从 ILLMProvider 创建 ChatModel（内建重试；`:retry false` 关闭）。
   → `chat-model/create-chat-model`"
  [provider config]
  (cm/create-chat-model provider config))

(defn call-model
  "直接调 ChatModel，绕开 ChatClient 与 filter 链。
   → `chat-model/call`"
  [model request]
  (cm/call model request))

;;; ============================================================
;;; ChatClient —— filter 链 + 工具
;;; ============================================================

(defn chat-client
  "构建 ChatClient。opts: :chat-model / :tools / :filters / :settings /
   :state-slots / :eligibility-fn / :tool-manager
   → `chat-client/build-chat-client`"
  [opts]
  (cc/build-chat-client opts))

(defn chat
  "单次 LLM 调用（经 :chat filter 链，**不含**工具循环）。
   → `chat-client/invoke-chat`"
  ([client messages] (cc/invoke-chat client messages {}))
  ([client messages opts] (cc/invoke-chat client messages opts)))

(defn chat-stream
  "单次 LLM 流式调用（opts 需带 `:on-token`）。
   → `chat-client/invoke-chat-stream`"
  [client messages opts]
  (cc/invoke-chat-stream client messages opts))

(defn invoke-tool
  "单次工具调用（经 :tool filter 链）。
   → `chat-client/invoke-tool`"
  ([client fn-name args] (cc/invoke-tool client fn-name args nil))
  ([client fn-name args context] (cc/invoke-tool client fn-name args context)))

(defn with-filters
  "换掉 ChatClient 的 filter 链并重编 hooks。**改 `:filters` 走这里。**
   → `filter/with-filters`"
  [client fs]
  (flt/with-filters client fs))

;;; ============================================================
;;; Tool
;;; ============================================================

(defmacro deftool
  "定义工具：同时生成 Clojure 函数与 LLM tool schema。
   → `tool/deftool`（透传，语义完全一致）"
  [& args]
  `(tool/deftool ~@args))

(defn tools
  "ChatClient 里所有工具名。 → `tool-registry/list-functions`"
  [client]
  (registry/list-functions client))

(defn find-tool
  "查一个工具。 → `tool-registry/find-function`"
  [client fn-name]
  (registry/find-function client fn-name))

(defn tool-meta
  "工具的装配期声明（`ToolMeta`），未注册则 nil。 → `tool-registry/tool-meta`"
  [client fn-name]
  (registry/tool-meta client fn-name))

;;; ============================================================
;;; Filter
;;; ============================================================

(defn filter
  "创建 filter。钩子键：:chat / :tool / :turn / :iteration / :token-xform。
   → `filter/create-filter`

   ⚠️ 遮蔽了 `clojure.core/filter`——`:refer` 本 ns 时请注意，`:as` 则无妨。"
  [name & opts]
  (apply flt/create-filter name opts))

;;; ============================================================
;;; 请求 / 上下文
;;; ============================================================

(defn request
  "构造 ChatRequest（消息 + 选项）。 → `model.request/chat-request`"
  ([messages] (req/chat-request messages))
  ([messages options] (req/chat-request messages options)))

(defn context
  "创建请求级共享状态。 → `context/create`"
  ([] (ctx/create))
  ([conversation-id] (ctx/with-conversation-id (ctx/create) conversation-id)))
