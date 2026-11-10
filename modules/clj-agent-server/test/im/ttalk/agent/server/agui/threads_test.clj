(ns im.ttalk.agent.server.agui.threads-test
  "C# ThreadsTests.cs（keel tests/agui-http.ss §9：A16 / A18 的 Threads 那几条），真 socket。
   下面那份「存储」是判据自己拿几个 fn 假装的（≙ C# 的委托版 AguiThreadStore）—— 与宿主插进来的是同一种东西。

   与 C# 不同：端到端那条（InMemoryStoreRecordsARun）用的是 clj-agent-agui 的进程内线程存储 + 本模块的适配器
   （thread-store/from-clj-agent）：消息出自 ChatMemory，形状是 clj-agent 的 messages->thread-messages —— toolCalls 是扁的
   {id, name, args}（C# 的进程内存储存的是 OpenAI 风格 {id, type, function:{name, arguments}}）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.server.agui :as agui]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.support :as t]))

(set! *warn-on-reflection* true)

(def ^:private echo-body "{\"agentId\":\"echo\"}")

(defn- row []
  {:id "t-1" :name "第一条" :agentId "echo" :organizationId "" :createdById ""
   :archived false :createdAt "2026-09-04T00:00:00.000Z" :updatedAt "2026-09-04T00:01:00.000Z"})

(defn- msgs []
  [{:id "m-1" :role "user" :content "你好"}
   {:id "m-2" :role "assistant" :toolCalls [{:id "tc-1" :name "read_file" :args "{\"p\":\"a\"}"}]}
   {:id "m-3" :role "tool" :content "ok" :toolCallId "tc-1"}])

(defn- full-store
  "记下宿主那几个 fn 真的收到了什么（⛔ 不然「转出去了」只证明了自己会拼 JSON）。"
  [seen]
  {:list (fn [q] (swap! seen assoc :query q) [(row)])
   :messages (fn [tid] (when (= "t-1" tid) (msgs)))
   :events (fn [tid] (when (= "t-1" tid) [{:type "RUN_STARTED" :timestamp 1} {:type "RUN_FINISHED" :timestamp 2}]))
   :state (fn [tid] (case tid "t-1" {:state {:n 7}} "t-empty" {:state nil} nil))
   :update (fn [tid aid ups]
             (swap! seen assoc :update [tid aid ups])
             (when (= "t-1" tid) (assoc (row) :renamed true)))
   :delete (fn [tid aid] (swap! seen assoc :delete [tid aid]) (= "t-1" tid))})

(defn- start [store & [origin reg]]
  (s/start (or reg (registry/registry [(s/entry "echo" (s/provider []))]))
           (cond-> {:version "0.6.3" :thread-store store} origin (assoc :origin-allowed origin))))

(defn- req [c method path & [body origin]] (s/send! c method path :body body :origin origin))

;; ─── §9.1 ⭐⭐ 没注册 ⇒ 与之前逐字相同 ───────────────────────

(deftest unregistered-is-unchanged
  (with-agui [c (start nil (ahttp/origin-allow "http://a"))]
    (let [te (get-in (req c :get "info") [:json :threadEndpoints])]
      (is (map? te) "⭐ 这个键一直在（缺席与 false 不是一回事）")
      (doseq [k [:list :inspect :mutations :realtimeMetadata]] (is (false? (get te k)) (str k))))
    (is (= 404 (:status (req c :get "threads"))))
    (is (= 404 (:status (req c :get "threads/t-1/messages"))))
    (is (= 404 (:status (req c :delete "threads/t-1" echo-body))))
    (is (str/includes? (get-in (req c :get "threads") [:json :message]) "/agent/:agentId/run"))
    (is (= 404 (:status (req c :patch "threads/t-1" echo-body))))
    ;; ⭐⭐ 钉的是「根本不进新的匹配」：进了的话方法不对会答 405
    (let [post (req c :post "threads" "{}")]
      (is (= 404 (:status post)))
      (is (= "not_found" (get-in post [:json :error])))
      (is (nil? (t/header post "allow"))))
    (let [get-r (req c :get "threads/t-1")]
      (is (= 404 (:status get-r)))
      (is (= "not_found" (get-in get-r [:json :error]))))
    (is (= "GET, POST, OPTIONS" (t/header (req c :options "threads" nil "http://a") "access-control-allow-methods")) "一个字节不变")))

;; ─── §9.2 注册一份假存储 ⇒ 逐格 true + 上游形状 ──────────────────

(deftest info-flags-when-registered
  (with-agui [c (start (full-store (atom {})))]
    (let [info (:json (req c :get "info"))]
      (is (true? (get-in info [:threadEndpoints :list])))
      (is (true? (get-in info [:threadEndpoints :inspect])))
      (is (true? (get-in info [:threadEndpoints :mutations])))
      (is (false? (get-in info [:threadEndpoints :realtimeMetadata])) "⭐⭐ 永远 false")
      (doseq [k [:intelligence :inspectorMetadata :licenseStatus :runtimeEntitlements]] (is (not (contains? info k)) (str k))))))

(deftest list-envelope-and-query
  (let [seen (atom {})]
    (with-agui [c (start (full-store seen))]
      (let [{:keys [status json]} (req c :get "threads?agentId=echo&limit=2")
            threads (:threads json)]
        (is (= 200 status))
        (is (= 1 (count threads)))
        (is (= "t-1" (:id (first threads))))
        (is (= "2026-09-04T00:00:00.000Z" (:createdAt (first threads))))
        (is (contains? json :nextCursor) "补成 null 而不是缺席")
        (is (nil? (:nextCursor json)))
        (is (not (contains? json :joinCode)))
        (is (= "echo" (get-in @seen [:query "agentId"])))
        (is (= "2" (get-in @seen [:query "limit"])) "⚠️ 是串，不替他转数字")))))

(deftest messages-events-state
  (with-agui [c (start (full-store (atom {})))]
    (let [ms (get-in (req c :get "threads/t-1/messages") [:json :messages])]
      (is (= 3 (count ms)))
      (is (= "user" (:role (first ms))))
      (is (= "read_file" (get-in ms [1 :toolCalls 0 :name])) "toolCalls 那一层原样带出去"))
    (is (= "RUN_STARTED" (get-in (req c :get "threads/t-1/events") [:json :events 0 :type])))
    (is (= 7 (get-in (req c :get "threads/t-1/state") [:json :state :n])))
    (let [empty-r (req c :get "threads/t-empty/state")]
      (is (= 200 (:status empty-r)))
      (is (contains? (:json empty-r) :state))
      (is (nil? (get-in empty-r [:json :state])) "⭐ 回 null，不是 404"))))

(deftest mutations
  (let [seen (atom {})]
    (with-agui [c (start (full-store seen) (ahttp/origin-allow "http://a"))]
      (let [{:keys [status json]} (req c :patch "threads/t-1" "{\"agentId\":\"echo\",\"name\":\"改了\"}")
            [tid aid ups] (:update @seen)]
        (is (= 200 status))
        (is (= "t-1" (:id json)) "⛔ 不套信封")
        (is (= ["t-1" "echo"] [tid aid]))
        (is (not (contains? ups :agentId)) "updates 里没有 agentId")
        (is (= "改了" (:name ups))))
      (let [arch (:json (req c :post "threads/t-1/archive" echo-body))]
        (is (= "t-1" (:threadId arch)))
        (is (true? (:archived arch)))
        (is (true? (:archived (nth (:update @seen) 2))) "归档走的是 update"))
      (let [del (:json (req c :delete "threads/t-1" echo-body))]
        (is (= "t-1" (:threadId del)))
        (is (true? (:deleted del)))
        (is (= "echo" (second (:delete @seen)))))
      (let [pre (t/header (req c :options "threads" nil "http://a") "access-control-allow-methods")]
        (is (str/includes? pre "PATCH"))
        (is (str/includes? pre "DELETE")))
      ;; subscribe / clear 不当作 :threadId，也不路由 ⇒ 404
      (is (= 404 (:status (req c :post "threads/subscribe" "{}"))))
      (is (= 404 (:status (req c :post "threads/clear" "{}")))))))

;; ─── §9.3 只注册一部分 ⇒ 逐格报，⛔ 不一开全开 ─────────────────

(deftest only-list
  (with-agui [c (start {:list (fn [_] [])} (ahttp/origin-allow "http://a"))]
    (let [te (get-in (req c :get "info") [:json :threadEndpoints])]
      (is (true? (:list te)))
      (is (false? (:inspect te)))
      (is (false? (:mutations te))))
    (is (= 200 (:status (req c :get "threads"))))
    ;; ⭐⭐ 501：不是 404，也不是 500
    (is (= 501 (:status (req c :get "threads/t-1/events"))))
    (is (= 501 (:status (req c :get "threads/t-1/state"))))
    (is (= 501 (:status (req c :get "threads/t-1/messages"))))
    (is (= 501 (:status (req c :delete "threads/t-1" echo-body))))
    (let [ni (:json (req c :get "threads/t-1/events"))]
      (is (= "not_implemented" (:error ni)))
      (is (str/includes? (:message ni) ":thread-store") "说得出该补哪个槽"))
    (is (not (str/includes? (t/header (req c :options "threads" nil "http://a") "access-control-allow-methods") "PATCH")))))

(deftest partial-slots-report-per-cell
  (let [reg (registry/registry [(s/entry "echo" (s/provider []))])
        info-of (fn [store] (agui/info (host/host reg {:thread-store store})))]
    (let [only-msgs (info-of {:messages (fn [_] [])})]
      (is (true? (get-in only-msgs [:threadEndpoints :inspect])))
      (is (false? (get-in only-msgs [:threadEndpoints :list]))))
    ;; ⭐ mutations 要两个槽都给才报 true
    (is (false? (get-in (info-of {:update (fn [_ _ _] nil)}) [:threadEndpoints :mutations])))
    ;; ⚠️ 一个槽都不给当场抛（不然它和「没注册」长得一模一样）
    (let [e (try (host/host reg {:thread-store {}}) nil (catch Exception e e))]
      (is (= "agui-registry/thread-store-no-slot" (err/code e)))
      (is (= :argument (:im.ttalk.agent.server/kind (ex-data e)))))))

;; ─── §9.4 错误路径：每一条都说得出话 ─────────────────────────

(deftest error-paths
  (with-agui [c (start (full-store (atom {})))]
    (let [nf (req c :get "threads/%E6%B2%A1%E8%BF%99%E6%9D%A1/messages")]
      (is (= 404 (:status nf)))
      (is (= "thread_not_found" (get-in nf [:json :error])))
      (is (str/includes? (get-in nf [:json :message]) "没这条")))
    (is (= 404 (:status (req c :get "threads/%E6%B2%A1%E8%BF%99%E6%9D%A1/state"))))
    (is (= 404 (:status (req c :delete "threads/%E6%B2%A1%E8%BF%99%E6%9D%A1" echo-body))))
    ;; 405 要带 Allow
    (let [p (req c :post "threads" "{}")]
      (is (= 405 (:status p)))
      (is (= "GET" (t/header p "allow"))))
    (let [g (req c :get "threads/t-1")]
      (is (= 405 (:status g)))
      (is (= "PATCH, DELETE" (t/header g "allow"))))
    (let [ga (req c :get "threads/t-1/archive")]
      (is (= 405 (:status ga)))
      (is (= "POST" (t/header ga "allow"))))
    (is (= 405 (:status (req c :put "threads/t-1" "{}"))))
    ;; 改动类的体
    (let [bad (req c :patch "threads/t-1" "{坏的")]
      (is (= 400 (:status bad)))
      (is (= "bad_json" (get-in bad [:json :error]))))
    (let [no-agent (req c :patch "threads/t-1" "{\"name\":\"改了\"}")]
      (is (= 400 (:status no-agent)))
      (is (str/includes? (get-in no-agent [:json :message]) "agentId")))
    (is (= 400 (:status (req c :patch "threads/t-1" "[]"))))))

(deftest store-throws-is-500-with-its-words
  (with-agui [c (start {:messages (fn [_] (throw (IllegalStateException. "存储自己炸了")))})]
    (let [x (req c :get "threads/t-1/messages")]
      (is (= 500 (:status x)))
      (is (= "thread_store_error" (get-in x [:json :error])))
      (is (str/includes? (get-in x [:json :message]) "存储自己炸了") "⭐ 不吞"))))

(deftest list-pagination-and-bad-shape
  (with-agui [c (start {:list (fn [_] {:threads [(row)] :nextCursor "c-2"})})]
    (let [j (:json (req c :get "threads"))]
      (is (= "c-2" (:nextCursor j)) "自带 nextCursor 就原样出")
      (is (vector? (:threads j))))))

(deftest list-wrong-shape-is-500
  (with-agui [c (start {:list (fn [_] "不是数组")})]
    (let [x (req c :get "threads")]
      (is (= 500 (:status x)) "⛔ 不把串当数组发出去")
      (is (= "thread_store_bad_shape" (get-in x [:json :error]))))))

(deftest cors-gate-covers-threads
  (with-agui [c (start (full-store (atom {})))]
    (is (= 403 (:status (req c :get "threads" nil "http://evil"))) "⛔ 不是绕过白名单的路")))

;; ─── 端到端：clj-agent-agui 的进程内线程存储 + 服务端工具，跑一次带工具的 run，再从 /threads* 读回来 ───

(deftest in-memory-store-records-a-run
  (let [p (s/provider [(s/tool-call "tc-1" "echo" {:text "喂"}) (s/text "跑完了")])
        reg (registry/registry [(s/entry "keel" p {:tools [#'s/echo]})])]
    (with-agui [c (s/start reg {:thread-store (ts/in-memory-store)})]
      (is (= 200 (:status (s/post! c "agent/keel/run" (s/body "t-9" "r-1" "跑一下" {:state {:n 1}})))))
      (let [te (get-in (req c :get "info") [:json :threadEndpoints])]
        (is (and (:list te) (:inspect te) (:mutations te))))
      (let [row (first (get-in (req c :get "threads?agentId=keel") [:json :threads]))]
        (is (= 1 (count (get-in (req c :get "threads?agentId=keel") [:json :threads]))))
        (is (= "t-9" (:id row)))
        (is (= "keel" (:agentId row)))
        (is (= "跑一下" (:name row)) "线程名取第一条 user 消息"))
      (is (empty? (get-in (req c :get "threads?agentId=other") [:json :threads])))
      (let [ms (get-in (req c :get "threads/t-9/messages") [:json :messages])]
        (is (= ["user" "assistant" "tool" "assistant"] (mapv :role ms)))
        (let [call (get-in ms [1 :toolCalls 0])]
          (is (= "tc-1" (:id call)))
          (is (= "echo" (:name call)) "clj-agent 的扁形状（C# 是 function.name）")
          (is (= {:text "喂"} (:args call))))
        (is (= "tc-1" (get-in ms [2 :toolCallId])))
        (is (= "跑完了" (get-in ms [3 :content]))))
      (let [es (get-in (req c :get "threads/t-9/events") [:json :events])]
        (is (= "RUN_STARTED" (:type (first es))))
        (is (= "RUN_FINISHED" (:type (peek es)))))
      (is (= 1 (get-in (req c :get "threads/t-9/state") [:json :state :n])) "那一轮的 state 作基线")
      (is (= 404 (:status (req c :post "threads/t-9/archive" echo-body))) "agentId 对不上 ⇒ 当作没有")
      (is (true? (get-in (req c :post "threads/t-9/archive" "{\"agentId\":\"keel\"}") [:json :archived])))
      (is (empty? (get-in (req c :get "threads") [:json :threads])) "缺省不列归档的")
      (is (= 1 (count (get-in (req c :get "threads?includeArchived=true") [:json :threads]))))
      (let [renamed (:json (req c :patch "threads/t-9" "{\"agentId\":\"keel\",\"name\":\"新名\",\"archived\":false}"))]
        (is (= "新名" (:name renamed)))
        (is (false? (:archived renamed))))
      (is (true? (get-in (req c :delete "threads/t-9" "{\"agentId\":\"keel\"}") [:json :deleted])))
      (is (= 404 (:status (req c :get "threads/t-9/messages")))))))
