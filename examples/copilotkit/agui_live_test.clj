(ns copilotkit.agui-live-test
  "AG-UI runtime × **真实 MiniMax 端点**端到端验证。

   离线那份（`agui_example.clj`）用桩 provider 证明「机制对不对」；这一份证明
   「接到真模型上还对不对」——真流式（token 一个个从 HTTP 流里出来）、真工具
   调用（模型自己决定调）、真取消（断连要能把上游停掉）。

   六个场景与离线那份一一对应：
     1. 流式        2. 工具调用      3. 断线重连
     4. 停止        5. 审批 HITL     6. 前端工具

   运行（需 MINIMAX_API_KEY，兼容旧的 MINIMAX_AUTH_TOKEN）：
     clojure -M -e \"(load-file \\\"examples/copilotkit/agui_live_test.clj\\\")\""
  (:require [cheshire.core :as json]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.tool :refer [deftool]]))

;;; ============================================================
;;; 环境
;;; ============================================================

(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(when-not auth-token
  (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
  (System/exit 1))

(def MODEL minimax/default-model)

(def failures (atom 0))
(def seen-models (atom #{}))

(defn- check [ok? label & [detail]]
  (if ok?
    (println "  ✓" label (or detail ""))
    (do (swap! failures inc)
        (println "  ✗" label (or detail "")))))

(defn- wait-for
  ([pred] (wait-for pred 90000))
  ([pred ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (cond (pred) true
             (> (System/currentTimeMillis) deadline) false
             :else (do (Thread/sleep 20) (recur)))))))

;;; ============================================================
;;; 工具
;;; ============================================================

(def executed (atom []))

(deftool get-weather
  "查询指定城市的当前天气"
  [[city :string "城市名"]]
  (swap! executed conj [:weather city])
  (str city "：晴，25°C，湿度 60%"))

(deftool refund-order
  "给订单退款（敏感操作，执行前必须人工批准）"
  [[order-id :string "订单号"]]
  {:sensitive true}
  (swap! executed conj [:refund order-id])
  (str "订单 " order-id " 已退款 128 元"))

;;; ============================================================
;;; runtime（真 provider）
;;; ============================================================

(defn- make-runtime [& {:keys [on-concurrent]}]
  (rt/runtime
   (cond-> {:agent-fn (agui-tools/agent-fn
                       {:provider (minimax/create-provider {:api-key auth-token})
                        :model MODEL
                        :max-tokens 1024
                        :tools [#'get-weather #'refund-order]
                        :memory (memory/in-memory-store)
                        :pause-store (pause/in-memory-pause-store)
                        :on-pause (fn [_])
                        ;; 用户自己的回调照常触发（agui 只是把自己的合成进去）——
                        ;; 顺便核对模型名，防「未知模型静默回退」
                        :callbacks {:on-llm-result
                                    (fn [resp _] (swap! seen-models conj (:model resp)))}})}
     on-concurrent (assoc :on-concurrent on-concurrent))))

;;; ============================================================
;;; 假 SSE 连接（真部署里这层是 http_kit_routes.clj）
;;; ============================================================

(defn- connect [runtime conv-id & {:keys [since]}]
  (let [frames (atom [])
        last-id (atom since)
        unsub (rt/subscribe runtime conv-id
                            {:since since
                             :on-event (fn [ev]
                                         (reset! last-id (:seq ev))
                                         (when-let [agui (codec/->agui ev)]
                                           (swap! frames conj [(:seq ev) agui])))})]
    {:frames frames :last-id last-id :close! unsub}))

(defn- events [conn] (mapv second @(:frames conn)))
(defn- of-type [conn t] (filterv #(= t (:type %)) (events conn)))
(defn- text-of [conn] (apply str (map :delta (of-type conn "TEXT_MESSAGE_CONTENT"))))
(defn- done? [conn] (seq (concat (of-type conn "RUN_FINISHED") (of-type conn "RUN_ERROR"))))
(defn- paused? [conn]
  (seq (filter #(= "cljagent.run.paused" (:name %)) (of-type conn "CUSTOM"))))

;;; ============================================================
;;; 场景
;;; ============================================================

(defn scenario-streaming []
  (println "\n1) 流式：真 token → TEXT_MESSAGE_CONTENT")
  (let [r (make-runtime)
        c (connect r "live-1")]
    (rt/start-run! r "live-1" "用大约 60 个字介绍杭州，不要分点。")
    (check (wait-for #(done? c)) "跑完")
    (check (>= (count (of-type c "TEXT_MESSAGE_CONTENT")) 2)
           "至少两个增量块（真流式，不是一次性甩；答案太短时服务端可能一块就发完）"
           (str "共 " (count (of-type c "TEXT_MESSAGE_CONTENT")) " 块"))
    (check (seq (text-of c)) "文本非空" (pr-str (text-of c)))
    (check (= "RUN_FINISHED" (:type (last (events c)))) "终态在最后")
    (check (= 1 (count (of-type c "TEXT_MESSAGE_START"))) "消息开合各一次")
    (rt/shutdown! r)))

(defn scenario-tool-call []
  (println "\n2) 工具调用：模型自己决定调，四件套齐全")
  (reset! executed [])
  (let [r (make-runtime)
        c (connect r "live-2")]
    (rt/start-run! r "live-2" "北京现在天气怎么样？请用 get-weather 工具查，然后一句话告诉我。")
    (check (wait-for #(done? c)) "跑完")
    (let [start (first (of-type c "TOOL_CALL_START"))
          args (first (of-type c "TOOL_CALL_ARGS"))
          result (first (of-type c "TOOL_CALL_RESULT"))]
      (check (some? start) "TOOL_CALL_START 到达" (:toolCallName start))
      (check (some? args) "TOOL_CALL_ARGS 到达" (:delta args))
      (check (some? result) "TOOL_CALL_RESULT 到达")
      (check (and start args result
                  (apply = (map :toolCallId [start args result])))
             "id 全程串得起来" (:toolCallId start))
      (check (some #(= :weather (first %)) @executed) "工具真的执行了" (pr-str @executed))
      (check (seq (text-of c)) "模型基于工具结果给了最终回答" (pr-str (text-of c))))
    (rt/shutdown! r)))

(defn scenario-reconnect []
  (println "\n3) 断线重连：连接掉了 run 照跑，Last-Event-ID 只补缺口")
  (let [r (make-runtime)
        c1 (connect r "live-3")]
    ;; 要一段**足够长**的生成：短答案服务端可能一两块就发完，那样根本没有
    ;; 「中途断线」可言（实测踩过——断言写成「断线前至少收到 N 块」会随机红）
    (rt/start-run! r "live-3" "写一段 300 字左右的短文，介绍杭州的四季。")
    (check (wait-for #(seq (of-type c1 "TEXT_MESSAGE_CONTENT")) 60000)
           "流已经在出了")
    ((:close! c1))                                   ;; 浏览器刷新 / 切网
    (let [seen @(:last-id c1)
          partial (text-of c1)
          _ (check (wait-for #(= :idle (:state (rt/run-status r "live-3"))))
                   "订阅者没了，run 照样在真 provider 上跑完")
          c2 (connect r "live-3" :since seen)        ;; EventSource 带 Last-Event-ID 回来
          whole (str partial (text-of c2))]
      (check (seq (of-type c2 "RUN_FINISHED")) "终态由重连的这条补上")
      (check (< (count partial) (count whole))
             "断线确实发生在中途（c1 只看到一部分）"
             (str (count partial) " / " (count whole) " 字"))
      (let [seqs (concat (map first @(:frames c1)) (map first @(:frames c2)))]
        (check (= seqs (range (first seqs) (inc (last seqs))))
               "断线前 + 重连后拼起来 seq 连续无洞"))
      (check (> (count whole) 150) "拿到的是完整答案，不是残段"
             (str (count whole) " 字")))
    (rt/shutdown! r)))

(defn scenario-stop []
  (println "\n4) 停止：另一个「请求」凭 conversation-id 停掉真在跑的 run")
  (let [r (make-runtime)
        c (connect r "live-4")]
    (rt/start-run! r "live-4" "写一篇 400 字的短文，讲讲杭州的四季。")
    (check (wait-for #(>= (count (of-type c "TEXT_MESSAGE_CONTENT")) 3) 60000)
           "流已经在出了")
    (check (true? (rt/stop! r "live-4")) "取消已登记（≠ 已经停了）")
    (check (true? (:stopping? (rt/run-status r "live-4"))) "「正在停止…」")
    (check (wait-for #(done? c) 30000) "终态到达")
    (check (= "cancelled" (get-in (last (of-type c "RUN_FINISHED")) [:result :status]))
           "落 :run/cancelled（AG-UI 侧是 RUN_FINISHED + result.status）")
    (check (< (count (text-of c)) 400) "真的提前停了，没把 400 字写完"
           (str (count (text-of c)) " 字"))
    (rt/shutdown! r)))

(defn scenario-hitl []
  (println "\n5) 审批 HITL：敏感工具暂停 → 另一个「请求」批准 → 续跑（也是流式的）")
  (reset! executed [])
  (let [r (make-runtime)
        c (connect r "live-5")]
    (rt/start-run! r "live-5"
                   "请给订单 ORD-2026 办理退款。退款只能通过 refund-order 工具执行，不要口头答复。")
    ;; 模型有时会自称「已提交退款申请」而不真的调工具（实测过）。**这是模型的随机性，
    ;; 不是机制问题**——催一次再看，仍不调就如实记失败，不放宽断言。
    (when-not (wait-for #(paused? c) 60000)
      (println "    · 首轮模型没调工具，催一次：" (pr-str (text-of c)))
      (rt/start-run! r "live-5" "你并没有真正执行退款。请立刻调用 refund-order 工具，order-id 是 ORD-2026。"))
    (check (wait-for #(paused? c)) "暂停事件到达"
           (when-not (paused? c) (str "模型两次都没调工具，答了：" (pr-str (text-of c)))))
    (let [p (first (filter #(= "cljagent.run.paused" (:name %)) (of-type c "CUSTOM")))]
      (check (= "refund-order" (get-in p [:value :pendingTool :name])) "带 pendingTool")
      (check (empty? @executed) "工具还没执行"))
    (check (= :awaiting-resume (:state (rt/run-status r "live-5"))) "会话在等 resume")
    (let [before (count (of-type c "TEXT_MESSAGE_CONTENT"))]
      ;; ↓ 真部署里这是**另一个 HTTP 请求**：它手上只有 conversation-id
      (rt/resume-run! r "live-5" "approved")
      (check (wait-for #(seq (of-type c "RUN_FINISHED"))) "续跑完成")
      (check (some #(= :refund (first %)) @executed) "批准后工具真的执行了" (pr-str @executed))
      (check (>= (- (count (of-type c "TEXT_MESSAGE_CONTENT")) before) 2)
             "审批之后那段是**流式**的（框架侧唯一那处改动换来的）"
             (str "新增 " (- (count (of-type c "TEXT_MESSAGE_CONTENT")) before) " 块"))
      (check (>= (count (distinct (keep :runId (events c)))) 2)
             "暂停是 run 的终态，续跑是新的 run；订阅跨 run 连续"))
    (rt/shutdown! r)))

(defn scenario-frontend-tool []
  (println "\n6) 前端工具：AG-UI client-side tool 走同一套 HITL 词汇")
  (let [r (make-runtime)
        c (connect r "live-6")
        agui-input {:threadId "live-6" :runId "r1"
                    :messages [{:role "user"
                                :content "请调用 confirm-dialog 工具，弹出标题为「确认继续」的确认框。"}]
                    :tools [{:name "confirm-dialog"
                             ;; 描述要写成**可调用的动作**。写成「在用户浏览器里…」
                             ;; 模型会判定「这不归我调」，转而给你讲怎么写 JS——
                             ;; 真机验证时实测踩过
                             :description "弹出确认对话框并返回用户的选择"
                             :parameters {:type "object"
                                          :properties {:title {:type "string" :description "标题"}}
                                          :required ["title"]}}]}
        parsed (codec/parse-run-input agui-input)]
    (rt/start-run! r (:conversation-id parsed) (:message parsed)
                   {:tools (mapv agui-tools/frontend-tool (:agui-tools parsed))})
    (check (wait-for #(paused? c)) "模型发出前端工具调用 → 暂停"
           (when-not (paused? c) (str "模型没调工具，答了：" (pr-str (text-of c)))))
    (check (= "confirm-dialog" (:toolCallName (first (of-type c "TOOL_CALL_START"))))
           "前端拿到 TOOL_CALL_START，自己去执行")
    ;; 前端执行完，把结果作为 :reply 送回来（载荷即工具结果）
    (rt/resume-run! r "live-6" "reply" {:message (json/generate-string {:clicked "ok"})})
    (check (wait-for #(seq (of-type c "RUN_FINISHED"))) "续跑完成")
    (check (= {:clicked "ok"} (some-> (first (of-type c "TOOL_CALL_RESULT"))
                                      :content (json/parse-string true)))
           "前端的结果作为工具结果回灌给模型")
    (check (seq (text-of c)) "模型据此给了最终回答" (pr-str (text-of c)))
    (rt/shutdown! r)))

;;; ============================================================
;;; 运行
;;; ============================================================

(defn run []
  (reset! failures 0)
  (reset! seen-models #{})
  (println "AG-UI runtime × MiniMax live 验证 |" MODEL)
  (doseq [f [scenario-streaming scenario-tool-call scenario-reconnect
             scenario-stop scenario-hitl scenario-frontend-tool]]
    (try (f)
         (catch Throwable t
           (swap! failures inc)
           (println "  ✗ 场景异常:" (.getMessage t)))))
  (println "\n模型名回显：" (pr-str @seen-models))
  ;; 未知模型名会被服务端静默回退，回显对不上就是在测别的模型
  (check (contains? @seen-models MODEL) "模型名回显核对（无静默回退）")
  (println)
  (if (zero? @failures)
    (println "全部通过 ✓")
    (println @failures "项失败 ✗"))
  @failures)

(when-not (System/getProperty "clj-agent.embedded-examples")
  (System/exit (if (zero? (run)) 0 1)))
