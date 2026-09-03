(ns copilotkit.agui-example
  "AG-UI runtime 端到端示例（**离线可跑**，不需要 API Key，不引任何 web 依赖）。

   ## 这个例子在证明什么

   CopilotKit 的前端要连到一个 runtime；那个 runtime 今天是 Node 进程，我们的
   Clojure 服务只能挂在它后面当 remote agent。把 runtime 的**后台机制**原生实现
   之后，中间那一跳就没有存在理由了：

   ```
   改造前： React ──HTTP──► Node runtime ──AG-UI/SSE──► Clojure
   改造后： React ──HTTP(AG-UI/SSE)──► Clojure（runtime + codec + 你的 web 栈）
   ```

   六个场景，全部离线：

   1. **流式**：token 逐个变成 AG-UI 的 `TEXT_MESSAGE_CONTENT`
   2. **工具**：`TOOL_CALL_START/ARGS/END/RESULT` 四件套，id 全程串得起来
   3. **断线重连**：连接掉了 run 照跑；带 `Last-Event-ID`（= `:seq`）连回来只补缺口
   4. **停止**：另一个「请求」凭 conversation-id 停掉正在跑的 run
   5. **审批 HITL**：敏感工具暂停 → 另一个「请求」批准 → 续跑（**这段也是流式的**）
   6. **前端工具**：AG-UI 的 client-side tool → 暂停 → 前端结果回灌 → 续跑

   本文件用一个十几行的**假 SSE 连接**（`fake-connection`）代替真 web 服务器，
   所以 `clojure -M -e ...` 就能跑完全套。真路由长什么样见同目录
   `http_kit_routes.clj`（那份要 http-kit 依赖，不在 CI 里跑）。

   运行：
     clojure -M -e \"(load-file \\\"examples/copilotkit/agui_example.clj\\\")\""
  (:require [cheshire.core :as json]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.tool :refer [deftool]]))

(def failures (atom 0))

(defn- check [ok? label & [detail]]
  (if ok?
    (println "  ✓" label (or detail ""))
    (do (swap! failures inc)
        (println "  ✗" label (or detail "")))))

(defn- wait-for
  ([pred] (wait-for pred 5000))
  ([pred ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (cond (pred) true
             (> (System/currentTimeMillis) deadline) false
             :else (do (Thread/sleep 5) (recur)))))))

;;; ============================================================
;;; 桩：一个会流式的 provider + 两个工具
;;; ============================================================

(defrecord ScriptedProvider [script delay-ms]
  provider/ILLMProvider
  (provider-name [_] :scripted)
  (call-llm [_ _ _ _] (let [r (first @script)] (swap! script rest) r))
  (extract-tool-calls [_ r] (:tool-calls r))
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id content] {:role "tool" :tool_call_id id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (call-llm-stream [_ _ _ _ on-token]
    (let [r (first @script)]
      (swap! script rest)
      (doseq [c (some->> (:text r) (map str))]
        (when delay-ms (Thread/sleep (long delay-ms)))
        (on-token {:token c}))
      r))
  (tool->schema [_ t] t))

(defn- scripted [responses & [delay-ms]]
  (->ScriptedProvider (atom responses) delay-ms))

(defn- tool-call [id name args] {:text nil :tool-calls [{:id id :name name :args args}]})

(def executed (atom []))

(deftool get-weather
  "查天气"
  [[city :string "城市"]]
  (swap! executed conj city)
  (str city "：晴 25°C"))

(deftool wipe-database
  "清库（敏感，需人工批准）"
  [[confirm :string "确认串"]]
  {:sensitive true}
  (Thread/sleep 120)
  (swap! executed conj (str "wiped:" confirm))
  "库已清空")

;;; ============================================================
;;; 假 SSE 连接：真服务器那一层长什么样，这里就是它的十行版
;;; ============================================================

(defn- sse-frame
  "AG-UI 事件 → SSE 帧。`id:` 放 `:seq`——浏览器断线重连时会把它作为
   `Last-Event-ID` 发回来，那正好就是我们 `subscribe` 的 `:since`。"
  [event agui]
  (str "id: " (:seq event) "\n"
       "data: " (json/generate-string agui) "\n\n"))

(defn- fake-connection
  "把一个订阅接到「连接」上。返回 {:frames :close! :last-id}。"
  [runtime conv-id & {:keys [since]}]
  (let [frames (atom [])
        last-id (atom since)
        unsub (rt/subscribe runtime conv-id
                            {:since since
                             :on-event (fn [ev]
                                         (reset! last-id (:seq ev))
                                         (when-let [agui (codec/->agui ev)]
                                           (swap! frames conj (sse-frame ev agui))))})]
    {:frames frames :last-id last-id :close! unsub}))

(defn- agui-events
  "把帧解回 AG-UI 事件（前端视角）。"
  [conn]
  (mapv #(json/parse-string (second (re-find #"data: (.*)\n\n" %)) true) @(:frames conn)))

(defn- of-type [conn t] (filterv #(= t (:type %)) (agui-events conn)))

;;; ============================================================
;;; runtime
;;; ============================================================

(defn- make-runtime [responses & {:keys [delay-ms on-concurrent]}]
  (rt/runtime
   (cond-> {:agent-fn (agui-tools/agent-fn
                       {:provider (scripted responses delay-ms)
                        :tools [#'get-weather #'wipe-database]
                        :memory (memory/in-memory-store)
                        :pause-store (pause/in-memory-pause-store)
                        :on-pause (fn [_])})}
     on-concurrent (assoc :on-concurrent on-concurrent))))

;;; ============================================================
;;; 场景
;;; ============================================================

(defn scenario-streaming []
  (println "\n1) 流式：token → TEXT_MESSAGE_CONTENT")
  (let [r (make-runtime [{:text "你好世界"}])
        conn (fake-connection r "c1")]
    (rt/start-run! r "c1" "打个招呼")
    (wait-for #(seq (of-type conn "RUN_FINISHED")))
    (check (= 1 (count (of-type conn "RUN_STARTED"))) "RUN_STARTED 一条")
    (check (= "你好世界" (apply str (map :delta (of-type conn "TEXT_MESSAGE_CONTENT"))))
           "逐 token 到达并拼得回全文")
    (check (= 1 (count (of-type conn "TEXT_MESSAGE_END"))) "消息收口")
    (check (= "RUN_FINISHED" (:type (last (agui-events conn)))) "终态在最后")
    (rt/shutdown! r)))

(defn scenario-tool-call []
  (println "\n2) 工具：TOOL_CALL_START/ARGS/END/RESULT")
  (reset! executed [])
  (let [r (make-runtime [(tool-call "tc-1" "get-weather" {:city "北京"})
                         {:text "北京晴"}])
        conn (fake-connection r "c1")]
    (rt/start-run! r "c1" "北京天气?")
    (wait-for #(seq (of-type conn "RUN_FINISHED")))
    (let [start (first (of-type conn "TOOL_CALL_START"))
          args  (first (of-type conn "TOOL_CALL_ARGS"))
          result (first (of-type conn "TOOL_CALL_RESULT"))]
      (check (= "get-weather" (:toolCallName start)) "toolCallName（不是 name）")
      (check (= {:city "北京"} (json/parse-string (:delta args) true)) "ARGS.delta 是 JSON 串")
      (check (= "北京：晴 25°C" (:content result)) "结果回来了")
      (check (apply = "tc-1" (map :toolCallId [start args result])) "id 全程串得起来"))
    (check (= ["北京"] @executed) "工具真的跑了")
    (rt/shutdown! r)))

(defn scenario-reconnect []
  (println "\n3) 断线重连：连接掉了 run 照跑，Last-Event-ID 只补缺口")
  (let [r (make-runtime [{:text "一二三四五六七八九十"}] :delay-ms 8)
        c1 (fake-connection r "c1")]
    (rt/start-run! r "c1" "数数")
    (wait-for #(>= (count (of-type c1 "TEXT_MESSAGE_CONTENT")) 2))
    ((:close! c1))                                  ;; 浏览器刷新 / 网络断了
    (let [seen @(:last-id c1)]
      (check (wait-for #(= :idle (:state (rt/run-status r "c1"))))
             "订阅者没了，run 照样跑完")
      (let [c2 (fake-connection r "c1" :since seen)] ;; EventSource 带 Last-Event-ID 连回来
        (check (= "一二三四五六七八九十"
                  (apply str (concat (map :delta (of-type c1 "TEXT_MESSAGE_CONTENT"))
                                     (map :delta (of-type c2 "TEXT_MESSAGE_CONTENT")))))
               "断线前 + 重连后 = 完整全文，一个 token 不丢")
        (check (seq (of-type c2 "RUN_FINISHED")) "终态也补上了")))
    (rt/shutdown! r)))

(defn scenario-stop []
  (println "\n4) 停止：另一个「请求」凭 conversation-id 停掉在跑的 run")
  (let [r (make-runtime [{:text "很长很长很长的一段回答"}] :delay-ms 20)
        conn (fake-connection r "c1")]
    (rt/start-run! r "c1" "说很多")
    (wait-for #(seq (of-type conn "TEXT_MESSAGE_CONTENT")))
    (check (true? (rt/stop! r "c1")) "取消已登记（≠ 已经停了：JVM 上没有抢占）")
    (check (wait-for #(seq (of-type conn "RUN_FINISHED"))) "终态到达")
    (check (= "cancelled" (get-in (last (of-type conn "RUN_FINISHED")) [:result :status]))
           "AG-UI 没有 cancelled，按「结束了，但带原因」发")
    (rt/shutdown! r)))

(defn scenario-hitl []
  (println "\n5) 审批 HITL：暂停 → 另一个「请求」批准 → 续跑（也是流式的）")
  (reset! executed [])
  (let [r (make-runtime [(tool-call "tc-9" "wipe-database" {:confirm "YES"})
                         {:text "已经清空了"}])
        conn (fake-connection r "c1")]
    (rt/start-run! r "c1" "清库")
    (check (wait-for #(seq (of-type conn "CUSTOM"))) "暂停事件到达")
    (let [paused (first (filter #(= "cljagent.run.paused" (:name %)) (of-type conn "CUSTOM")))]
      (check (= "wipe-database" (get-in paused [:value :pendingTool :name])) "带 pendingTool")
      (check (empty? @executed) "工具还没执行"))
    (check (= :awaiting-resume (:state (rt/run-status r "c1"))) "会话在等 resume")
    ;; ↓ 这在真部署里是**另一个 HTTP 请求**：它手上只有 conversation-id
    (rt/resume-run! r "c1" "approved")
    (check (wait-for #(seq (of-type conn "RUN_FINISHED"))) "续跑完成")
    (check (= ["wiped:YES"] @executed) "批准后工具真的执行了")
    (let [run-ids (distinct (keep :runId (agui-events conn)))]
      (check (= 2 (count run-ids)) "暂停是 run 的终态，续跑是新的 run"))
    (check (>= (count (of-type conn "TEXT_MESSAGE_CONTENT")) 2)
           "审批之后那段是流式的（这正是本轮框架侧唯一那处改动换来的）")
    (rt/shutdown! r)))

(defn scenario-frontend-tool []
  (println "\n6) 前端工具：AG-UI client-side tool 走的是同一套 HITL 词汇")
  (let [r (make-runtime [(tool-call "fe-1" "confirm-dialog" {:title "确定吗"})
                         {:text "用户点了确定"}])
        conn (fake-connection r "c1")
        ;; 前端每次请求带上它的 action 列表（AG-UI RunAgentInput.tools）
        agui-input {:threadId "c1" :runId "r1"
                    :messages [{:role "user" :content "弹个框"}]
                    :tools [{:name "confirm-dialog"
                             :description "在前端弹确认框"
                             :parameters {:type "object"
                                          :properties {:title {:type "string"}}}}]}
        parsed (codec/parse-run-input agui-input)]
    (rt/start-run! r (:conversation-id parsed) (:message parsed)
                   {:tools (mapv agui-tools/frontend-tool (:agui-tools parsed))})
    (check (wait-for #(seq (of-type conn "CUSTOM"))) "模型发出前端工具调用 → 暂停")
    (check (= "fe-1" (:toolCallId (first (of-type conn "TOOL_CALL_START"))))
           "前端拿到 TOOL_CALL_START，自己去执行")
    ;; 前端执行完，把结果作为 :reply 送回来（载荷即工具结果）
    (rt/resume-run! r "c1" "reply" {:message (json/generate-string {:clicked "ok"})})
    (check (wait-for #(seq (of-type conn "RUN_FINISHED"))) "续跑完成")
    (check (= {:clicked "ok"} (some-> (first (of-type conn "TOOL_CALL_RESULT"))
                                      :content (json/parse-string true)))
           "前端的结果作为工具结果回灌给模型")
    (rt/shutdown! r)))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn run
  "跑全部场景，**返回失败项数**（不 exit——嵌入 run_all_examples 时要接着往下跑）。"
  []
  (reset! failures 0)
  (println "AG-UI runtime 示例 | 桩 provider（离线）| 假 SSE 连接")
  (doseq [f [scenario-streaming scenario-tool-call scenario-reconnect
             scenario-stop scenario-hitl scenario-frontend-tool]]
    (try (f)
         (catch Throwable t
           (swap! failures inc)
           (println "  ✗ 场景异常:" (.getMessage t)))))
  (println)
  (if (zero? @failures)
    (println "全部通过 ✓")
    (println @failures "项失败 ✗"))
  @failures)

;;; 独立运行才 exit；被 run_all_examples `load-file` 进来时只定义、不自跑。
(when-not (System/getProperty "clj-agent.embedded-examples")
  (System/exit (if (zero? (run)) 0 1)))
