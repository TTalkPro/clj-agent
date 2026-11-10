(ns im.ttalk.agent.server.agui.support
  "agui-server 测试设施：假模型（脚本化、会流式、可加闸）+ 建注册表 / 起服务 / 收 SSE 的快捷方式。
   ⛔ 不连真模型。C# 的 ScriptedChatClient / AguiAgent.Create 在这边是「脚本化 provider + clj-agent 的 simple-agent」。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.agui.event-store :as es]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model :as model]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.streaming :as streaming]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.server.agui :as agui]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.jetty :as net]
            [im.ttalk.agent.server.support :as t])
  (:import (java.io Closeable InputStream)))

(set! *warn-on-reflection* true)

;; ── 假模型 ───────────────────────────────────────────

(defn- next-response! [responses default]
  (let [[old _] (swap-vals! responses #(if (seq %) (rest %) %))]
    (or (first old) default)))

(defrecord ScriptedProvider [responses default asked chunk-size]
  model/ILLMProvider
  (provider-name [_] :scripted)
  (call-llm [_ _config messages _tools]
    (swap! asked conj (vec messages))
    (let [r (next-response! responses default)] (if (fn? r) (r (fn [_])) r)))
  (extract-tool-calls [_ response] (:tool-calls response))
  (extract-text [_ response] (:text response))
  (build-tool-result [_ tool-id content] {:role "tool" :tool_call_id tool-id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (call-llm-stream [_ _config messages _tools on-token]
    (swap! asked conj (vec messages))
    (let [r (next-response! responses default)]
      (if (fn? r)
        (r on-token)
        (do (doseq [chunk (some->> (:text r) (partition-all chunk-size) (map #(apply str %)))]
              (on-token {:token chunk}))
            r))))
  (tool->schema [_ tool] tool))

(defn provider
  "按顺序回 `responses`（{:text …} / {:tool-calls [{:id :name :args}]} / (fn [on-token] response)）；用完了回 `:default`。
   `:chunk-size` 流式分块（缺省一整块）。`(:asked p)` 记下每一轮收到的消息。"
  ([responses] (provider responses nil))
  ([responses {:keys [default chunk-size]}]
   (->ScriptedProvider (atom (seq responses)) (or default {:text ""}) (atom []) (or chunk-size 100000))))

(defn text [s] {:text s})

(defn tool-call [id name args] {:text nil :tool-calls [{:id id :name name :args args}]})

(defn gated
  "C# 的 GatedAgent：先发一个 token（⇒ TEXT_MESSAGE_START + CONTENT），等闸开了再收尾。被按停（取消令牌）⇒ 抛，
   `ended` 收 false；正常收尾 ⇒ `ended` 收 true。"
  [gate ended]
  (fn [on-token]
    (let [cancelled (promise)]
      (on-token {:token "完"})
      (streaming/register-cancel! #(deliver cancelled true))
      (loop []
        (cond
          (realized? cancelled) (do (deliver ended false) (throw (ex-info "cancelled" {})))
          (realized? gate) (do (deliver ended true) {:text "完"})
          :else (do (Thread/sleep 5) (recur)))))))

(defn slow
  "先发一个 token，睡 `ms`，再收尾（C# HeartbeatWhileIdle 的 agent）。"
  [ms]
  (fn [on-token] (on-token {:token "等"}) (Thread/sleep (long ms)) {:text "等"}))

(defn asked-user-texts
  "provider 收到的每一轮里最后一条 user 消息的文本。"
  [p]
  (mapv (fn [msgs] (some #(when (contains? #{:user "user"} (:role %)) (:content %)) (reverse msgs))) @(:asked p)))

(deftool echo
  "回声"
  [[text :string "文本"]]
  (str "echo:" text))

;; ── 注册表 ───────────────────────────────────────────

(defn recording-store
  "包一层缺省环形缓冲，另记下每一条事件（含终态 —— 读 :run/cancelled 的 :reason，即 C# 的 AguiCancelReason）。"
  []
  (let [inner (es/ring-store) seen (atom [])]
    {:seen seen
     :store (reify es/EventStore
              (es-append! [_ c e] (swap! seen conj e) (es/es-append! inner c e))
              (es-since [_ c s] (es/es-since inner c s))
              (es-events [_ c] (es/es-events inner c))
              (es-last-seq [_ c] (es/es-last-seq inner c))
              (es-release! [_ c] (es/es-release! inner c))
              (es-delete! [_ c] (es/es-delete! inner c)))}))

(defn cancel-reasons
  "某条线程上 :run/cancelled 的原因，按发生顺序。"
  [{:keys [seen]} tid]
  (vec (keep #(when (and (= :run/cancelled (:type %)) (= tid (:conversation-id %))) (:reason %)) @seen)))

(defn entry
  "一台 agent 的注册表条目。opts：:tools（服务端工具 var）:on-concurrent :event-store :description :suggest? :agent-spec :stop。"
  [id p & [{:keys [tools on-concurrent event-store] :as opts}]]
  (let [mem (memory/in-memory-store)
        spec {:provider p :memory mem :pause-store (pause/in-memory-pause-store) :tools (vec tools)}]
    (cond-> {:id id
             :runtime (rt/runtime (cond-> {:agent-fn (agui-tools/agent-fn spec)
                                           :on-concurrent (or on-concurrent :reject)}
                                    event-store (assoc :event-store event-store)))
             :memory mem}
      (:description opts) (assoc :description (:description opts))
      (:suggest? opts) (assoc :suggest? true)
      (:agent-spec opts) (assoc :agent-spec (:agent-spec opts))
      (:stop opts) (assoc :stop (:stop opts)))))

(defn suggest-spec [p] {:provider p})

;; ── 服务 ─────────────────────────────────────────────

(defn start
  "起一台：host（缺省心跳关，同 C# 测试）+ clj-net-server。⇒ {:host :server :reg :url (fn [path])}。
   opts：:prefix（缺省 /api/copilotkit）:server（clj-net-server 选项）:extra（(fn [host] 路由表)，并在 AG-UI 那几条后面）。"
  ([reg] (start reg nil nil))
  ([reg host-opts] (start reg host-opts nil))
  ([reg host-opts {:keys [prefix server extra]}]
   (let [prefix (if (nil? prefix) "/api/copilotkit" prefix)
         h (host/host reg (merge {:heartbeat-ms 0} host-opts))
         rs (cond-> (agui/routes prefix h) extra (into (extra h)))
         s (net/start rs server)
         base (str (net/base-address s) (str/replace prefix #"^/+" ""))]
     {:host h :server s :reg reg
      :url (fn [path] (if (empty? path) (str base (if (empty? prefix) "" "/")) (str base (if (empty? prefix) "" "/") path)))})))

(defn stop! [{:keys [server reg]}]
  (net/stop! server)
  (registry/shutdown! reg))

(defmacro with-agui [[sym expr] & body]
  `(let [~sym ~expr] (try ~@body (finally (stop! ~sym)))))

(defn body
  "C# 测试的 Body()：RunAgentInput 的 JSON 原文。"
  ([] (body "t-1" "r-1" "在吗"))
  ([tid] (body tid "r-1" "在吗"))
  ([tid rid] (body tid rid "在吗"))
  ([tid rid user & [extra]]
   (json/generate-string (merge {:threadId tid :runId rid :messages [{:id "u1" :role "user" :content user}]} extra))))

(defn post!
  "POST，读完 ⇒ {:status :headers :body}（:json 解析过的正文，能解析时）。"
  [ctx path b & {:keys [origin headers]}]
  (let [r (t/send! :post ((:url ctx) path) :body b
                   :headers (cond-> (merge {"Content-Type" "application/json"} headers) origin (assoc "Origin" origin)))]
    (assoc r :json (try (json/parse-string (:body r) true) (catch Exception _ nil)))))

(defn send!
  "任意方法 ⇒ 同 post!。"
  [ctx method path & {:keys [body origin headers]}]
  (let [r (t/send! method ((:url ctx) path) :body body
                   :headers (cond-> (merge (when body {"Content-Type" "application/json"}) headers) origin (assoc "Origin" origin)))]
    (assoc r :json (try (json/parse-string (:body r) true) (catch Exception _ nil)))))

(defn post-stream!
  "POST，正文以流返回（读 SSE 用；关流 = 掐连接）。"
  [ctx path b]
  (t/send-stream! :post ((:url ctx) path) :body b :headers {"Content-Type" "application/json"}))

(defn agui-events
  "SSE 原文 ⇒ AG-UI 事件（keyword 键）。"
  [raw]
  (mapv #(json/parse-string (:data %) true) (t/events raw)))

(defn types [evs] (mapv :type evs))

(defn sse!
  "POST 一条 SSE，读到流结束 ⇒ {:events :raw :resp}。"
  [ctx path b]
  (let [r (post! ctx path b)]
    {:events (agui-events (:body r)) :raw (:body r) :resp r}))

(defn read-events [^InputStream in] (agui-events (t/read-all in)))

(defn disconnect!
  "起一条 SSE，读到第一条事件就掐掉连接（= 浏览器刷新）。回第一条事件。"
  [ctx path b]
  (let [{:keys [^InputStream stream]} (post-stream! ctx path b)
        r (t/read-stream stream)
        first-ev (t/next-event r)]
    (.close ^Closeable r)
    first-ev))

(defn wait-until [pred] (t/wait-until pred))

(defn run-of [ctx tid] (host/run (:host ctx) tid))
