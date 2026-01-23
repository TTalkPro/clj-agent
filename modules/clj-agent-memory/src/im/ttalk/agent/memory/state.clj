(ns im.ttalk.agent.memory.state.protocol
  "IState 协议 - Agent 状态协议

   定义 Agent 状态的数据结构协议，确保不同 Agent 实现的状态一致性。

   协议要求：
   1. 必须包含 :messages (对话历史)
   2. 必须包含 :scratchpad (临时草稿)
   3. 可选：其他自定义字段

   数据结构：
   {:messages [...]
    :scratchpad {...}
    :custom-field ...}"

  (:require [clojure.string :as str]))

;; =============================================================================
;; IState 协议定义
;; =============================================================================

(defprotocol IState
  "Agent 状态协议

   所有 Agent 的 state 必须实现此协议，确保状态一致性。

   必需字段：
   - :messages: 对话历史列表 [{:role \"user\" :content \"...\"} ...]
   - :scratchpad: 临时草稿 {<任意键> <任意值>}）

   可选字段：
   - 其他自定义数据

   示例：
   {:messages [{:role \"user\" :content \"你好\"}
              {:role \"assistant\" :content \"你好！\"}]
    :scratchpad {:draft \"未发送的消息\"}
    :current-node \"calculator\"}"
  )

(defn validate-state
  "验证 state 是否符合协议

   参数：
   - state: 待验证的 state

   返回：{:valid? boolean
           :errors [...]}
           或 {:valid? true}

   示例：
   (validate-state {:messages [] :scratchpad {}})
   => {:valid? true}

   (validate-state {:scratchpad {}})
   => {:valid? false
     :errors [\":messages 字段缺失\"]}"
  [state]
  (let [errors (atom [])]
        check (fn [field type checker]
                 (when-not (checker (get state field))
                   (swap! errors conj (str \": " field " " 字段缺失"))))]
    ;; 检查必需字段
    (check :messages vector? vector?)
    (check :scratchedpad map? map?)

    {:valid? (empty? @errors)
     :errors @errors}))

(defn ensure-state!
  "确保 state 符合协议，如果缺失必需字段则自动补全

   参数：
   - state: 待处理的 state

   返回：符合协议的 state

   示例：
   (ensure-state! {:scratchpad {}})
   => {:scratchpad {} :messages []}

   (ensure-state! {:messages [...]})
   => {:messages [...] :scratchpad {}}"
  [state]
  (let [validated (validate-state state)]
    (if (:valid? validated)
      state
      ;; 补全缺失字段
      (-> state
          (update :messages #(or % []))
          (update :scratchpad #(or % {}))))))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn state?
  "检查是否实现了 IState 协议"
  [x]
  (satisfies? IState x))

(defn empty-state
  "创建一个符合协议的空状态"
  []
  {:messages []
   :scratchpad {}})

(defn add-message-to-state
  "添加消息到 state

   参数：
   - state: 当前状态
   - role: 角色（:user/:assistant/:system/:tool）
   - content: 内容

   返回：更新后的 state"
  [state role content]
  (update state :messages conj {:role role
                                       :content content
                                       :timestamp (System/currentTimeMillis)}))

(defn get-messages-from-state
  "从 state 获取消息"
  [state]
  (:messages state))

(defn scratchpad-write
  "写入 scratchpad

   参数：
   - state: 当前状态
   - key: 键
   - value: 值

   返回：更新后的 state"
  [state key value]
  (assoc-in state [:scratchpad key] value))

(defn scratchpad-read
  "读取 scratchpad"
  [state key]
  (get-in state [:scratchpad key]))

(defn scratchpad-clear
  "清空 scratchpad"
  [state]
  (assoc state :scratchpad {}))
