(ns copilotkit.http-kit-routes-test
  "路由层的离线测试（designs/agui-borrow.md §3 / §4 / §6 / §7，tasks/G-03～G-07）。

   两种打法：
   - **直接调 `handler`**（不起 server）：4xx / 501 / `/stop` / `/threads` 这些
     不开 SSE 的分支，返回的就是 ring 响应 map；
   - **起一台真 http-kit**（端口 0，本机回环）：SSE 那几条——心跳、`/run` 记进
     线程表——得有真的 channel。用 mock provider，不打任何外部端点。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [copilotkit.http-kit-routes :as routes]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.tool :refer [deftool]]
            [org.httpkit.server :as hk])
  (:import [java.io BufferedReader ByteArrayInputStream InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def base "/api/copilotkit")

(defn- req
  ([method path] (req method path nil))
  ([method path body & {:keys [query raw]}]
   (cond-> {:request-method method
            :uri (str base path)
            :headers {}}
     query (assoc :query-string query)
     raw   (assoc :body (ByteArrayInputStream. (.getBytes ^String raw "UTF-8")))
     (and body (not raw)) (assoc :body (ByteArrayInputStream.
                                        (.getBytes ^String (json/generate-string body) "UTF-8"))))))

(defn- body-of [resp] (some-> (:body resp) (json/parse-string true)))

(defn- mk-reg
  ([] (mk-reg nil))
  ([{:keys [hold memory]}]
   (let [mem (or memory (memory/in-memory-store))
         r1 (sup/runtime {:provider (sup/provider (repeat 10 {:text "一二三"}) {:chunk-size 1 :hold hold})
                          :memory mem})
         r2 (sup/runtime {:provider (sup/provider [{:text "运维"}])})]
     (registry/registry [{:id "default" :runtime r1 :memory mem :description "通用"}
                         {:id "ops" :runtime r2 :description "运维"}]))))

;;; ============================================================
;;; G-07 入参校验
;;; ============================================================

(deftest run-input-validation-test
  (let [reg (mk-reg)
        h (routes/handler reg base {:max-body-bytes 200})]
    (testing "坏 JSON → 400 bad_json（原来是 500）"
      (let [resp (h (req :post "/agent/default/run" nil :raw "{oops"))]
        (is (= 400 (:status resp)))
        (is (= "bad_json" (:error (body-of resp))))
        (is (string? (:message (body-of resp))))))
    ;; 错误种类码以 clj-agent-server 为准（上游 / C# 的线协议）：形状不对一律 bad_input，消息里点名缺哪个字段
    (testing "非对象 → 400 bad_input；空体 → 400"
      (is (= "bad_input" (:error (body-of (h (req :post "/agent/default/run" nil :raw "[1,2]"))))))
      (is (= 400 (:status (h (req :post "/agent/default/run"))))))
    (testing "缺 threadId / runId → 400"
      (let [b (body-of (h (req :post "/agent/default/run" {:runId "r1"})))]
        (is (= "bad_input" (:error b)))
        (is (str/includes? (:message b) "threadId")))
      (let [b (body-of (h (req :post "/agent/default/run" {:threadId "t1"})))]
        (is (= "bad_input" (:error b)))
        (is (str/includes? (:message b) "runId"))))
    (testing "超限 → 413 body_too_large"
      (let [resp (h (req :post "/agent/default/run" {:threadId "t1" :runId "r1"
                                                      :messages [{:role "user" :content (apply str (repeat 500 "x"))}]}))]
        (is (= 413 (:status resp)))
        (is (= "body_too_large" (:error (body-of resp))))))
    (testing "/connect 也校验 threadId"
      (is (= "bad_input" (:error (body-of (h (req :post "/agent/default/connect" {})))))))
    (testing "/suggest 坏 JSON → 400（没开 suggest 的照旧 404）"
      (is (= 404 (:status (h (req :post "/agent/default/suggest" nil :raw "{oops"))))))
    (registry/shutdown! reg)))

;;; ============================================================
;;; G-04 多 agent
;;; ============================================================

(deftest multi-agent-test
  (let [reg (mk-reg)
        h (routes/handler reg base {})]
    (testing "/info 列出两台，agents 是字典"
      (let [info (body-of (h (req :get "/info")))]
        (is (= #{"default" "ops"} (set (map name (keys (:agents info))))))
        (is (= "运维" (get-in info [:agents :ops :description])))
        (is (false? (:suggestions info)))
        (is (= {:list false :inspect false :mutations false :realtimeMetadata false}
               (:threadEndpoints info)) "没配线程存储就四格全 false")))
    (testing "未知 agent → 404 agent_not_found（原来静默跑 default）"
      (let [resp (h (req :post "/agent/nope/run" {:threadId "t1" :runId "r1"}))]
        (is (= 404 (:status resp)))
        (is (= "agent_not_found" (:error (body-of resp)))))
      (is (= "agent_not_found" (:error (body-of (h (req :post "/agent/nope/stop/t1")))))))
    (testing "名字里带 threads 的 agent 不会被 /threads 截走；认不出的路径 404 not_found"
      (is (= "agent_not_found" (:error (body-of (h (req :post "/agent/threads/run" {:threadId "t" :runId "r"})))))))
    (is (= "not_found" (:error (body-of (h (req :get "/whatever"))))))
    (testing "没开 suggest 的 agent → 404"
      (is (= 404 (:status (h (req :post "/agent/ops/suggest" {:messages [{:role "user" :content "hi"}]}))))))
    (registry/shutdown! reg)))

;;; ============================================================
;;; G-07 /stop 响应体
;;; ============================================================

(deftest stop-response-test
  (let [hold (promise)
        reg (mk-reg {:hold hold})
        h (routes/handler reg base {})
        r (:runtime (registry/find-agent reg "default"))
        c (sup/collector)]
    (testing "没在跑 → stopped false + message"
      (let [b (body-of (h (req :post "/agent/default/stop/t1")))]
        (is (false? (:stopped b)))
        (is (string? (:message b)))))
    (testing "在跑 → stopped true + interrupt（RUN_ERROR code=STOPPED，由前端合成）"
      (rt/subscribe r "t1" {:on-event (:on-event c)})
      (rt/start-run! r "t1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (let [b (body-of (h (req :post "/agent/default/stop/t1")))]
        (is (true? (:stopped b)))
        (is (= {:type "RUN_ERROR" :message "Run stopped by user" :code "STOPPED"} (:interrupt b))))
      (deliver hold true)
      (is (sup/wait-for #(some #{:run/cancelled} ((:types c)))))
      (is (= :stop (:reason (sup/terminal-event ((:events c)))))))
    (registry/shutdown! reg)))

;;; ============================================================
;;; G-06 / G-07 线程面
;;; ============================================================

(deftest threads-test
  (let [mem (memory/in-memory-store)
        reg (mk-reg {:memory mem})
        t (atom 1000)
        store (ts/in-memory-store {:now #(swap! t + 10)})
        h (routes/handler reg base {:thread-store store})]
    (ts/ts-touch! store "t1" {:agent-id "default"})
    (ts/ts-touch! store "t2" {:agent-id "ops"})
    (ts/ts-touch! store "t3" {:agent-id "default"})
    (memory/mem-add mem "t1" [{:role :user :content "你好"}])
    (testing "/info 按槽逐格报"
      (is (= {:list true :inspect true :mutations true :realtimeMetadata false}
             (:threadEndpoints (body-of (h (req :get "/info")))))))
    (testing "列表：新的在前、agentId 过滤、wire 形状"
      (let [b (body-of (h (req :get "/threads")))]
        (is (= ["t3" "t2" "t1"] (mapv :id (:threads b))))
        (is (nil? (:nextCursor b)))
        (is (= #{:id :name :agentId :organizationId :createdById :archived :createdAt :updatedAt}
               (set (keys (first (:threads b))))))
        (is (str/ends-with? (:createdAt (first (:threads b))) "Z")))
      (is (= ["t3" "t1"] (mapv :id (:threads (body-of (h (req :get "/threads" nil :query "agentId=default"))))))))
    (testing "分页：limit + cursor"
      (let [p1 (body-of (h (req :get "/threads" nil :query "limit=2")))
            p2 (body-of (h (req :get "/threads" nil :query (str "limit=2&cursor=" (:nextCursor p1)))))]
        (is (= ["t3" "t2"] (mapv :id (:threads p1))))
        (is (= ["t1"] (mapv :id (:threads p2))))
        (is (nil? (:nextCursor p2)))))
    ;; 改动类请求体要带 agentId（上游契约；缺了 400 bad_request）
    (testing "改动类缺 agentId → 400"
      (is (= "bad_request" (:error (body-of (h (req :post "/threads/t2/archive" {})))))))
    (testing "归档缺省隐藏；includeArchived=true 才列；PATCH 取消归档"
      (is (= {:threadId "t2" :archived true} (body-of (h (req :post "/threads/t2/archive" {:agentId "ops"})))))
      (is (= ["t3" "t1"] (mapv :id (:threads (body-of (h (req :get "/threads")))))))
      (is (some #{"t2"} (mapv :id (:threads (body-of (h (req :get "/threads" nil :query "includeArchived=true")))))))
      (is (false? (:archived (body-of (h (req :patch "/threads/t2" {:agentId "ops" :archived false}))))))
      (is (some #{"t2"} (mapv :id (:threads (body-of (h (req :get "/threads"))))))))
    (testing "改名（PATCH）；不存在 → 404 thread_not_found；坏 JSON → 400"
      (is (= "新名字" (:name (body-of (h (req :patch "/threads/t1" {:agentId "default" :name "新名字"}))))))
      (is (= "thread_not_found" (:error (body-of (h (req :patch "/threads/nope" {:agentId "default" :name "x"}))))))
      (is (= 400 (:status (h (req :patch "/threads/t1" nil :raw "{oops"))))))
    (testing "messages 读 ChatMemory；/threads/:id 不收 GET（上游只有 PATCH / DELETE）⇒ 405 带 Allow"
      (is (= 1 (count (:messages (body-of (h (req :get "/threads/t1/messages")))))))
      (is (= "新名字" (:name (first (filter #(= "t1" (:id %)) (:threads (body-of (h (req :get "/threads")))))))))
      (is (= 405 (:status (h (req :get "/threads/t1"))))))
    (testing "会话被驱逐之后线程仍在列表里（列表来自线程存储，不是会话表）"
      (rt/forget! (:runtime (registry/find-agent reg "default")) "t1")
      (is (some #{"t1"} (mapv :id (:threads (body-of (h (req :get "/threads"))))))))
    (testing "DELETE：删线程记录 + 清 ChatMemory；再删 404"
      (is (= {:threadId "t1" :deleted true} (body-of (h (req :delete "/threads/t1" {:agentId "default"})))))
      (is (empty? (memory/mem-get mem "t1")))
      (is (= 404 (:status (h (req :delete "/threads/t1" {:agentId "default"}))))))
    (testing "subscribe 如实 404"
      (is (= 404 (:status (h (req :get "/threads/subscribe"))))))
    (registry/shutdown! reg)))

(defrecord ListOnlyStore [inner]
  ts/ThreadStore
  (ts-slots [_] #{:list})
  (ts-touch! [_ id o] (ts/ts-touch! inner id o))
  (ts-get [_ id] (ts/ts-get inner id))
  (ts-list [_ q] (ts/ts-list inner q))
  (ts-update! [_ _ _] (throw (UnsupportedOperationException.)))
  (ts-delete! [_ _] (throw (UnsupportedOperationException.))))

(deftest missing-slot-501-test
  (let [reg (registry/registry [{:id "default" :runtime (sup/runtime {:provider (sup/provider [])})}])
        store (->ListOnlyStore (ts/in-memory-store))
        h (routes/handler reg base {:thread-store store})]
    (ts/ts-touch! store "t1" {:agent-id "default"})
    (testing "缺 :update / :delete / memory → 501 not_implemented，且 /info 如实报 false"
      (is (= 501 (:status (h (req :post "/threads/t1/archive" {})))))
      (is (= 501 (:status (h (req :patch "/threads/t1" {:name "x"})))))
      (is (= 501 (:status (h (req :delete "/threads/t1" {})))))
      (is (= "not_implemented" (:error (body-of (h (req :get "/threads/t1/messages"))))))
      (is (= {:list true :inspect false :mutations false :realtimeMetadata false}
             (:threadEndpoints (body-of (h (req :get "/info")))))))
    (testing "给了的槽照常工作"
      (is (= ["t1"] (mapv :id (:threads (body-of (h (req :get "/threads"))))))))
    (registry/shutdown! reg)))

;;; ============================================================
;;; G-03 心跳（纯函数部分）
;;; ============================================================

(defn- with-server [handler f]
  (let [server (hk/run-server handler {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try (f (hk/server-port server))
         (finally (hk/server-stop! server)))))

(defn- post-stream
  "POST，返回响应体的行 reader（SSE 逐行读）。"
  ^BufferedReader [port path body]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port base path)))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                    (.build))
        resp (.send client request (HttpResponse$BodyHandlers/ofInputStream))]
    (BufferedReader. (InputStreamReader. ^java.io.InputStream (.body resp) "UTF-8"))))

(deftest sse-heartbeat-and-thread-record-test
  (let [hold (promise)
        reg (mk-reg {:hold hold})
        store (ts/in-memory-store)
        h (routes/handler reg base {:thread-store store :heartbeat-ms 40})]
    (with-server h
      (fn [port]
        (let [rdr (post-stream port "/agent/default/run"
                               {:threadId "live-1" :runId "r1"
                                :messages [{:id "m1" :role "user" :content "hi"}]})
              lines (atom [])
              reader (future (loop []
                               (when-let [l (.readLine rdr)]
                                 (swap! lines conj l)
                                 (recur))))]
          (testing "run 卡住（工具 / 模型在想）时流上有心跳注释帧"
            (is (sup/wait-for #(some #{": clj-agent-alive"} @lines) 5000)))
          (testing "/run 起跑即进线程表（带 agentId）"
            (is (= "default" (:agent-id (ts/ts-get store "live-1")))))
          (deliver hold true)
          (is (not= ::timeout (deref reader 10000 ::timeout)) "终态即关流")
          (is (some #(str/includes? % "RUN_FINISHED") @lines))
          (testing "驱逐之后 /threads 照样列出"
            (rt/forget! (:runtime (registry/find-agent reg "default")) "live-1")
            (is (= ["live-1"] (mapv :id (:threads (body-of (h (req :get "/threads")))))))))))
    (registry/shutdown! reg)))

;;; ============================================================
;;; G-01 审批时改参数 + 拒绝理由
;;; ============================================================

(def refunded (atom []))

(deftool refund-order
  "给订单退款（敏感操作，需人工批准）"
  [[order-id :string "订单号"]]
  {:sensitive true}
  (swap! refunded conj order-id)
  (str "订单 " order-id " 已退款"))

(defn- read-all-lines [^BufferedReader rdr]
  (loop [acc []]
    (if-let [l (.readLine rdr)] (recur (conj acc l)) acc)))

(defn- approval-roundtrip
  "真 server 上走一遍：第一次 /run 停在审批，第二次 /run 带 resume[]。返回
   `{:info :refunded :tool-msgs}`。"
  [resume-payload]
  (reset! refunded [])
  (let [mem (memory/in-memory-store)
        r (sup/runtime {:provider (sup/provider [{:text nil :tool-calls [{:id "tc1" :name "refund-order"
                                                                           :args {:order-id "A-1"}}]}
                                                 {:text "好了"}])
                        :tools [#'refund-order]
                        :memory mem
                        :on-pause (fn [_])})
        reg (registry/registry [{:id "default" :runtime r :memory mem
                                 :server-tool-names #{"refund-order"}}])
        h (routes/handler reg base {:heartbeat-ms 0})]
    (try
      (with-server h
        (fn [port]
          (let [first-lines (read-all-lines (post-stream port "/agent/default/run"
                                                         {:threadId "hitl-1" :runId "r1"
                                                          :messages [{:id "m1" :role "user" :content "退款"}]}))]
            (assert (some #(str/includes? % "\"interrupt\"") first-lines) "第一段应停在审批")
            (read-all-lines (post-stream port "/agent/default/run"
                                         {:threadId "hitl-1" :runId "r2"
                                          :messages [{:id "m1" :role "user" :content "退款"}]
                                          :resume [{:interruptId "tc1" :status "resolved"
                                                    :payload resume-payload}]}))
            (sup/wait-for #(= :idle (:state (rt/run-status r "hitl-1"))))
            {:info (body-of (h (req :get "/info")))
             :refunded @refunded
             :tool-msgs (->> (memory/mem-get mem "hitl-1") (filter #(= :tool (:role %))) (mapv :content))})))
      (finally (registry/shutdown! reg)))))

(deftest approve-with-edits-e2e-test
  (testing "批准 + editedArgs → 工具收到替换后的参数；/info 报 approveWithEdits"
    (let [{:keys [info refunded]} (approval-roundtrip {:approved true :editedArgs {:order-id "B-2"}})]
      (is (= ["B-2"] refunded))
      (is (true? (get-in info [:agents :default :capabilities :humanInTheLoop :approveWithEdits])))))
  (testing "拒绝 + reason → 工具没执行，模型拿到理由"
    (let [{:keys [refunded tool-msgs]} (approval-roundtrip {:approved false :reason "先退款给另一单"})]
      (is (empty? refunded))
      (is (some #(str/includes? (str %) "先退款给另一单") tool-msgs) (pr-str tool-msgs))))
  (testing "⚠️ {approved:false} 不带 decision → 不执行（回归）"
    (let [{:keys [refunded]} (approval-roundtrip {:approved false})]
      (is (empty? refunded))))
  (testing "老前端 {decision:approved} 照旧执行原参数"
    (let [{:keys [refunded]} (approval-roundtrip {:decision "approved"})]
      (is (= ["A-1"] refunded)))))

;;; ============================================================
;;; G-02 同一轮多条审批
;;; ============================================================

(def ^:private two-pending
  {:run-id "r1"
   :pending-tool {:name "refund-order" :args {:order-id "A"} :tool-call {:id "tcA"}}
   :pending-tools [{:name "refund-order" :args {:order-id "A"} :tool-call {:id "tcA"}}
                   {:name "refund-order" :args {:order-id "B"} :tool-call {:id "tcB"}}]})

(defn- multi-approval-server
  "两条 refund 一起被叫停的真 server。返回 `{:h :port-fn …}` 由 f 使用。"
  [f]
  (reset! refunded [])
  (let [mem (memory/in-memory-store)
        r (sup/runtime {:provider (sup/provider [{:text nil :tool-calls [{:id "tcA" :name "refund-order" :args {:order-id "A"}}
                                                                          {:id "tcB" :name "refund-order" :args {:order-id "B"}}]}
                                                 {:text "好了"}])
                        :tools [#'refund-order] :memory mem :on-pause (fn [_])})
        reg (registry/registry [{:id "default" :runtime r :memory mem :server-tool-names #{"refund-order"}}])
        h (routes/handler reg base {:heartbeat-ms 0})]
    (try (with-server h (fn [port] (f {:h h :port port :runtime r :memory mem})))
         (finally (registry/shutdown! reg)))))

(deftest multi-approval-e2e-test
  (multi-approval-server
   (fn [{:keys [h port runtime memory]}]
     (let [msgs [{:id "m1" :role "user" :content "退两单"}]
           first-lines (read-all-lines (post-stream port "/agent/default/run" {:threadId "mt" :runId "r1" :messages msgs}))
           finished (->> first-lines (filter #(str/starts-with? % "data: "))
                         (map #(json/parse-string (subs % 6) true))
                         (filter #(= "RUN_FINISHED" (:type %))) first)
           run2 (fn [resume] (h (req :post "/agent/default/run" {:threadId "mt" :runId "r2" :messages msgs :resume resume})))]
       (testing "第一段：outcome.interrupts 两条"
         (is (= ["tcA" "tcB"] (mapv :id (get-in finished [:outcome :interrupts])))))
       (testing "只答一条 → 409 interrupt_incomplete，什么都没执行、暂停还在"
         (let [resp (run2 [{:interruptId "tcA" :status "resolved" :payload {:approved true}}])]
           (is (= 409 (:status resp)))
           (is (= ["tcB"] (:missing (body-of resp)))))
         (is (empty? @refunded))
         (is (= 2 (count (:pending-tools (rt/awaiting runtime "mt"))))))
       (testing "夹着不认识的 → 409 interrupt_unknown；坏形状 → 400"
         (is (= "interrupt_unknown" (:error (body-of (run2 [{:interruptId "tcA" :status "resolved"}
                                                            {:interruptId "tcB" :status "resolved"}
                                                            {:interruptId "nope" :status "resolved"}])))))
         (is (= 400 (:status (run2 [{:status "resolved"}])))))
       (testing "/pending 列两条"
         (is (= ["tcA" "tcB"] (mapv :interruptId (:pendingTools (first (:pending (body-of (h (req :get "/pending"))))))))))
       (testing "答全：A 改参数批准、B 带理由拒绝 → 只退了 A2，B 的理由进了工具结果"
         (read-all-lines (post-stream port "/agent/default/run"
                                      {:threadId "mt" :runId "r3" :messages msgs
                                       :resume [{:interruptId "tcA" :status "resolved"
                                                 :payload {:approved true :editedArgs {:order-id "A2"}}}
                                                {:interruptId "tcB" :status "resolved"
                                                 :payload {:approved false :reason "B 已经退过了"}}]}))
         (sup/wait-for #(= :idle (:state (rt/run-status runtime "mt"))))
         (is (= ["A2"] @refunded))
         (is (some #(str/includes? (str (:content %)) "B 已经退过了")
                   (filter #(= :tool (:role %)) (memory/mem-get memory "mt")))))))))
