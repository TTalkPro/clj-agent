(ns im.ttalk.agent.agui.subagent
  "把 `subagent/manager` 的**协议无关观察钩子**翻译成 lane 事件。

   分层在这里合拢：`clj-agent-client` 那侧只知道「有四个可选钩子」
   （`manager` 的 ns docstring），AG-UI 的知识一个字都没有；本 ns 负责给每次
   spawn 现造一条 lane 的发射器，并把四个钩子接上去。

   用法（用户在自己的 `agent-fn` 里）：

   ```clojure
   (rt/runtime
    {:subagent-events? true
     :agent-fn (fn [{:keys [conversation-id tools subagent-observer]}]
                 (agent/create-agent
                  {:provider p
                   :tools (conj (vec tools)
                                (delegate/delegate-tool
                                 {:name \"deep_research\"
                                  :subagent-fn (fn [_ _] {:provider p})
                                  :observer subagent-observer}))}))})
   ```

   `:subagent-observer` 在开关关闭时是 **nil**，`delegate-tool` 于是走今天的老路径
   ——「不塞就什么都不会发生」是这条链路每一跳的性质（设计文档 §1.3）。"
  (:require [im.ttalk.agent.agui.emit :as emit]
            [im.ttalk.agent.agui.event :as event]))

(set! *warn-on-reflection* true)

(defn- lane-id
  "lane 的稳定标识 = spawn id；`restart!` 的第 n 次尝试加后缀。

   同一个 registry id 开两条 lane 会让消费方把两次尝试叠在一起看
   （`SUBAGENT_STARTED` 出现两次、id 相同）。代号由 manager 给（`:attempt`）。"
  [id attempt]
  (if (and attempt (pos? attempt)) (str id "-r" attempt) id))

(defn- finish-of
  "manager 的 outcome → `event/finish-subagent!` 的入参。

   manager 的 outcome 形状是它自己的（`{:ok s}` / `{:error reason}`），这层翻译因此
   必须在 agui 这侧——client 不认识事件，事件层不认识 manager。

   **不额外脱敏**：`{:crashed true :message m}` 里的 m 今天已经经由
   `delegate/safe-result-str` 进了 `tool/result` 的正文，也就是早就到过前端。
   要脱敏就得在 `delegate` 那一层统一做（那是一次有意的产品决定），
   在这里偷偷截一刀只会让两处说法不一致。"
  [outcome]
  (cond
    ;; **`:ok` 的值就是协议里那个 `SUBAGENT_FINISHED.result`**——以前扔掉了。
    ;; 它已经经由 `delegate/safe-result-str` 进过工具结果的正文，所以这里带上
    ;; 不是新的暴露面，只是把协议那一位填上（脱敏若要做，在 delegate 那层统一做）
    (contains? outcome :ok)      {:outcome :success :result (:ok outcome)}
    (= :killed  (:error outcome)) {:outcome :killed}
    (= :timeout (:error outcome)) {:outcome :timeout}

    (map? (:error outcome))
    (let [{:keys [crashed message status]} (:error outcome)]
      {:error {:class :provider-error
               :message (cond
                          crashed (str "子 agent 崩溃：" message)
                          status  (str "子 agent 未正常完成：" status)
                          :else   (pr-str (:error outcome)))}})

    :else
    {:error {:class :provider-error :message (str "子 agent 失败：" (pr-str outcome))}}))

(defn observer-factory
  "给一个发射器造观察者工厂——就是 runtime 交给 `agent-fn` 的那个 `:subagent-observer`。

   每次 spawn 调一次（契约由 manager 保证），因此**每条 lane 一个发射器实例**：
   10 路并发共用一个实例会共用 `:current-message`，token 于是交错进同一条消息
   （设计文档 §3.3 契约 4）。

   **嵌套（子 agent 再委派）本层不接线**：事件层支持（`subagent-emitter` 认父 lane，
   有测试钉住），但要让子 agent 自己的 `delegate-tool` 拿到这条 lane 的工厂，
   `:subagent-fn` 得能收到它——那是 `delegate` 的签名变更，等真实需求
   （设计文档 §7.5 同款判据）。"
  [parent]
  (fn [{:keys [id attempt name task parent-tool-call-id]}]
    (let [lane (event/subagent-emitter parent {:subagent-run-id (lane-id id attempt)})]
      {:decorate  (fn [agent] (emit/attach agent lane))
       :chat-opts {:on-token (emit/token-fn lane)}
       :start!    (fn [] (event/start-subagent!
                          lane {:name name :task task
                                :parent-tool-call-id parent-tool-call-id
                                ;; 凭 tool-call-id 反查它属于哪条正文消息——委派
                                ;; 工具跑起来时那条消息早已收口，`current-message`
                                ;; 已是 nil，只能靠 `:open-tools` 那份记账
                                :parent-message-id
                                (when parent-tool-call-id
                                  (event/tool-parent-message parent parent-tool-call-id))}))
       :settle!   (fn [outcome] (event/finish-subagent! lane (finish-of outcome)))})))
