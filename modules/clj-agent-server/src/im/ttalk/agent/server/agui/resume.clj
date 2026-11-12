(ns im.ttalk.agent.server.agui.resume
  "`/run` 起跑前的那一段：新 run 还是续跑（interrupt 协议的 `resume[]`、前端工具的结果、审批答复）。
   逻辑最初出自 clj-agent 的 examples/copilotkit/http_kit_routes.clj（handle-run* / resume-plan / resume-decision …）；2026-11-10 起
   那份示例改为调用本模块，这里是唯一的一份。
   文案走本模块的 i18n。C# 那边这一段在 ChatAguiAgent 里（Keelson.Agui），协议层只管路由。

   `start!` 返回 runtime 的结果（`rt/start-run!` / `rt/resume-run!`：`{:status :started :run-id :since}`、`:busy`、
   `:awaiting-resume`、`:not-paused`），或 `{:status :resume-error :error [status code extra]}`（在起 run 之前判掉的坏答复）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.server.strings :refer [msg]]))

(set! *warn-on-reflection* true)

(defn decision-of
  "前端 `respond(...)` 回来的载荷 → resume 决策（审批场景下载荷是**决策**，不是工具结果）。"
  [content]
  (let [c (str/lower-case (str/trim (str content)))]
    (if (or (str/includes? c "approve") (str/includes? c "同意") (= c "true") (= c "yes"))
      "approved"
      "rejected")))

(defn- reason-of [payload]
  (let [r (cond (map? payload) (or (:reason payload) (:message payload))
                (string? payload) payload)]
    (when (and (string? r) (not (str/blank? r))) r)))

(defn- explicit-false? [v]
  (or (false? v) (and (string? v) (= "false" (str/lower-case (str/trim v))))))

(defn resume-decision
  "AG-UI `resume[]` 条目 → `[decision payload]`。`status=cancelled` ⇒ 拒；`approved:false` 先于一切判；
   批准 + `editedArgs` 对象 ⇒ `{:args editedArgs}`（整体替换）。⛔ 不把客户端载荷原样透传。
   取消带 `:cancelled? true`——交给模型的那句「拒绝」还是「取消」由宿主的 `:reject-note` 分（缺省同一句）。"
  [{:keys [status payload]}]
  (let [cancelled? (= "cancelled" status)
        reject (fn [] (let [r (reason-of payload)]
                        ["rejected" (cond-> nil
                                      r          (assoc :message r)
                                      cancelled? (assoc :cancelled? true))]))
        approve (fn [] (let [e (when (map? payload) (:editedArgs payload))]
                         (if (map? e) ["approved" {:args e}] ["approved" nil])))]
    (cond
      (= "cancelled" status) (reject)
      (map? payload) (cond
                       (explicit-false? (:approved payload)) (reject)
                       (some? (:decision payload)) (if (= "approved" (decision-of (:decision payload))) (approve) (reject))
                       :else (approve))
      (some? payload) (if (= "approved" (decision-of payload)) ["approved" nil] (reject))
      :else ["approved" nil])))

(defn- payload-text [payload]
  (cond (nil? payload) nil
        (map? payload) (let [r (or (:result payload) (get payload "result"))]
                         (if (some? r) (str r) (json/generate-string payload)))
        :else (str payload)))

(defn frontend-reply
  "前端工具那支的 resume 载荷 → 交给模型的**工具结果文本**。取消先看 status（⛔ 不能把缺席的载荷 str 成空结果）。"
  [resume-entry tool-result]
  (let [text (payload-text (:payload resume-entry))
        content (:content tool-result)]
    (cond
      (= "cancelled" (:status resume-entry))
      (:text (if text (msg :agui-server/frontend-cancelled-reason text) (msg :agui-server/frontend-cancelled)))
      (some? text) text
      (some? content) (str content)
      :else (:text (msg :agui-server/frontend-no-result)))))

(defn- bad-resume-entry? [e]
  (not (and (map? e)
            (let [id (:interruptId e)] (or (and (string? id) (not (str/blank? id))) (number? id)))
            (or (nil? (:status e)) (contains? #{"resolved" "cancelled"} (:status e))))))

(defn- per-call-decision [pending-tool entry server-tool-names]
  (if (contains? server-tool-names (str (:name pending-tool)))
    (let [[d p] (resume-decision entry)]
      (cond-> {:decision (keyword d)}
        (:args p) (assoc :args (:args p))
        (:message p) (assoc :message (:message p))
        (:cancelled? p) (assoc :cancelled? true)))
    {:decision :reply :message (frontend-reply entry nil)}))

(defn- sep [] (:text (msg :agui-stock/list-separator)))

(defn resume-plan
  "`resume[]` 对着当前暂停算出怎么续：nil（没带）/ `{:error [status code extra]}` / `{:single entry}` / `{:decisions {id 决策}}`。
   | 情形 | status | code |
   | resume 不是数组 / 条目形状不对 | 400 | interrupt_bad_resume |
   | 没有暂停 | 409 | no_pending_interrupt |
   | 一条都对不上（过期重放） | 409 | interrupt_mismatch |
   | 对上了、但还夹着不认识的 id | 409 | interrupt_unknown |
   | 多条审批只答了一部分 | 409 | interrupt_incomplete |"
  [thread-id resume pending server-tool-names]
  (when (and (some? resume) (or (not (sequential? resume)) (seq resume)))
    (cond
      (or (not (sequential? resume)) (some bad-resume-entry? resume))
      {:error [400 "interrupt_bad_resume" {:message (:text (msg :agui-server/interrupt-bad-resume))}]}

      (nil? pending)
      {:error [409 "no_pending_interrupt" {:message (:text (msg :agui-server/no-pending-interrupt thread-id))}]}

      :else
      (let [pts (or (not-empty (vec (:pending-tools pending))) [(:pending-tool pending)])
            multi? (> (count pts) 1)
            ids (if multi? (mapv #(str (get-in % [:tool-call :id])) pts) [(codec/interrupt-id pending)])
            id-set (set ids)
            by-id (reduce (fn [m e] (let [k (str (:interruptId e))] (if (contains? m k) m (assoc m k e)))) {} resume)
            unknown (vec (remove id-set (keys by-id)))
            missing (vec (remove #(contains? by-id %) ids))]
        (cond
          (= (count unknown) (count by-id))
          {:error [409 "interrupt_mismatch"
                   (cond-> {:message (:text (msg :agui-server/interrupt-mismatch (str/join (sep) unknown)))
                            :interruptId (first ids)}
                     multi? (assoc :interruptIds ids))]}

          (seq unknown)
          {:error [409 "interrupt_unknown"
                   {:message (:text (msg :agui-stock/interrupt-unknown (str/join (sep) unknown))) :unknown unknown :pending ids}]}

          (not multi?) {:single (get by-id (first ids))}

          (seq missing)
          {:error [409 "interrupt_incomplete"
                   {:message (:text (msg :agui-stock/interrupt-incomplete (str/join (sep) missing))) :missing missing :pending ids}]}

          :else
          {:decisions (into {} (map (fn [p id] [id (per-call-decision p (get by-id id) server-tool-names)]) pts ids))})))))

(defn start!
  "照 RunAgentInput 起 run 或续跑（见 ns 文档）。`entry` 是注册表里那台 agent 的条目：`:runtime`、可选 `:server-tool-names`
   （服务端工具名 —— 前端同名声明 = `useHumanInTheLoop` 的渲染 / 审批意图，⛔ 不当新工具）、可选 `:input-transform`。"
  [{:keys [runtime] :as entry} body]
  (let [server-tool-names (or (:server-tool-names entry) #{})
        input-transform (:input-transform entry)
        {:keys [conversation-id message agui-tools context parent-run-id state chat-options]}
        (cond-> (codec/parse-run-input body) input-transform (input-transform body))
        ;; `:chat-options` 只能由宿主的 `:input-transform` 给（如按 forwardedProps 切思考档），
        ;; ⛔ 不从客户端载荷直取——新 run 与续跑都带，进每一轮 chat 选项
        ctx-opts (cond-> nil
                   (codec/context->prompt context) (assoc :extra-system-prompts [(codec/context->prompt context)])
                   parent-run-id (assoc :parent-run-id parent-run-id)
                   (map? chat-options) (assoc :chat-options chat-options))
        pending (rt/awaiting runtime conversation-id)
        pending-name (get-in pending [:pending-tool :name])
        plan (resume-plan conversation-id (:resume body) pending server-tool-names)
        resume-entry (:single plan)
        tool-result (when pending (->> (:messages body) (filter #(= "tool" (:role %))) last))
        real-frontend-tools (remove #(contains? server-tool-names (str (:name %))) agui-tools)
        frontend-names (into #{} (map #(str (:name %))) real-frontend-tools)
        stale-frontend-pause? (and pending (nil? tool-result) (nil? resume-entry) (contains? frontend-names pending-name))]
    (cond
      (:error plan) {:status :resume-error :error (:error plan)}

      (:decisions plan)
      (rt/resume-run! runtime conversation-id "approved" {:decisions (:decisions plan)} ctx-opts)

      (and resume-entry (contains? server-tool-names pending-name))
      (let [[decision payload] (resume-decision resume-entry)]
        (rt/resume-run! runtime conversation-id decision payload ctx-opts))

      resume-entry
      (rt/resume-run! runtime conversation-id "reply" {:message (frontend-reply resume-entry tool-result)} ctx-opts)

      (and pending tool-result (contains? server-tool-names pending-name))
      (rt/resume-run! runtime conversation-id (decision-of (:content tool-result)) nil ctx-opts)

      (and pending tool-result)
      (rt/resume-run! runtime conversation-id "reply" {:message (str (:content tool-result))} ctx-opts)

      :else
      (rt/start-run! runtime conversation-id (or message "")
                     (merge ctx-opts {:tools (mapv agui-tools/frontend-tool real-frontend-tools)
                                      :discard-pause? stale-frontend-pause?
                                      :state state})))))
