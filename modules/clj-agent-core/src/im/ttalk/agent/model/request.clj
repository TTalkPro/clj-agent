(ns im.ttalk.agent.model.request
  "ChatRequest —— 发往 ChatModel 的一次调用请求

   对标 Spring AI 的 `Prompt(List<Message>, ChatOptions)`：**消息 + 选项**两段，
   没有第三样东西。wire 级参数（`:tools` / `:tool-choice` / `:system-prompt` /
   `:model` / `:max-tokens` / provider 私有键…）一律进 `:options`——它们的共同点
   是「会影响这一次 HTTP 请求长什么样」，而 `:messages` 是内容本身。

   **为什么是叶子 ns**：`chat-model` 要它（作为入参）、`filter` 要它（`ChatClientRequest`
   裹着它）、`chat-client` 要它（在 terminal 组装）。放在任何一个里面都会给另外两个
   造出反向依赖，故单独成 ns，只依赖 `clojure.core`。

   **与 `ChatClientRequest` 的分工**（两层，别混）：

     ChatClientRequest{:request ChatRequest, :context Context}   ← filter 洋葱链看到的
     ChatRequest{:messages [...], :options {...}}                ← ChatModel 看到的

   filter 链层多出来的那一样是 `:context`（请求级共享状态，只读）——它**不下发给
   provider**，所以不能混进 ChatRequest。这正是 Spring 把 `ChatClientRequest` 与
   `Prompt` 分成两个类的理由。

   构造走 `chat-request` / `as-chat-request`；`as-chat-request` 收 map 字面量并
   归一化成 record（与 `filter/as-filter` 同一套手法——用户侧照旧写 map，装配期
   / 入口处转一次）。"
  (:require [clojure.string]))

(set! *warn-on-reflection* true)

(defrecord ChatRequest [messages options])

(defn chat-request?
  "是否为 ChatRequest record（map 字面量不算——归一化前后要分得清）。"
  [x]
  (instance? ChatRequest x))

(defn chat-request
  "构造 ChatRequest。

   参数:
   - messages: 中立消息列表（本轮发给 provider 的全量消息）
   - options:  调用选项 map（可选，缺省 {}）——:tools / :tool-choice /
               :system-prompt / :model / :max-tokens / provider 私有键

   返回: ChatRequest record"
  ([messages] (chat-request messages {}))
  ([messages options]
   (->ChatRequest (vec messages) (or options {}))))

(defn as-chat-request
  "归一化成 ChatRequest：已是 record 则原样返回，map 则转换。

   接受两种 map 写法：
   - 嵌套：{:messages [...] :options {...}}
   - 扁平：{:messages [...] :tools [...] :tool-choice :auto :system-prompt \"...\"}
     —— 除 :messages 外的键**全部**收进 :options。扁平写法是为了让调用方
     不必为了传一个 :tools 而多敲一层。"
  [x]
  (cond
    (chat-request? x) x
    (map? x) (let [{:keys [messages options]} x
                   rest-opts (dissoc x :messages :options)]
               (chat-request messages (merge options rest-opts)))
    :else (throw (ex-info "无法归一化为 ChatRequest（需 ChatRequest 或 map）"
                          {:value x :type (type x)}))))

;;; ============================================================
;;; 选项存取 —— 让调用点不必满屏 assoc-in
;;; ============================================================

(defn option
  "读一个调用选项。"
  ([req k] (get (:options req) k))
  ([req k not-found] (get (:options req) k not-found)))

(defn with-option
  "写一个调用选项，返回新 ChatRequest。"
  [req k v]
  (assoc-in req [:options k] v))

(defn with-options
  "合并一组调用选项，返回新 ChatRequest。"
  [req m]
  (update req :options merge m))

(defn without-options
  "删除若干调用选项，返回新 ChatRequest。"
  [req & ks]
  (update req :options #(apply dissoc % ks)))

(defn with-messages
  "换掉消息列表，返回新 ChatRequest。"
  [req messages]
  (assoc req :messages (vec messages)))

(defn update-messages
  "以函数更新消息列表，返回新 ChatRequest。"
  [req f & args]
  (assoc req :messages (vec (apply f (:messages req) args))))

(defn wire-options
  "下发给 provider 的调用选项：`:options` 去掉值为 nil 的键。

   **只做去 nil**，不筛白名单——provider 私有参数（Anthropic 的 `:thinking`、
   `:service-tier`，MiniMax 的 `:cache-strategy`…）必须能原样穿过去，
   见 `docs/provider-variant-design.md` §1.2 那条「递不到底」的 bug。"
  [req]
  (into {} (remove (comp nil? val)) (:options req)))
