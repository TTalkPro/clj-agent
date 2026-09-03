(ns im.ttalk.agent.agui.support
  "agui 测试公共设施：一个**会流式**的 mock provider + 建 runtime 的快捷方式。

   `test-support/MockProvider`（client 模块）`supports-stream?` 返回 false，
   于是流式路径整条测不到——事件流的一半就是 token，所以这里另起一个。"
  (:require [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.simple-agent :as agent]))

(defn- take-response!
  "取下一条预设响应。**响应可以是个函数**——调用它，于是「这一轮 provider 抛异常」
   也能写进预设列表里。"
  [responses-atom fallback]
  (let [resp (first @responses-atom)]
    (swap! responses-atom rest)
    (cond
      (fn? resp) (resp)
      (some? resp) resp
      :else fallback)))

(defrecord StreamingMockProvider [responses-atom chunk-size delay-ms hold]
  provider/ILLMProvider
  (provider-name [_] :mock-stream)
  (call-llm [_ _config _messages _tools]
    (when delay-ms (Thread/sleep (long delay-ms)))
    (take-response! responses-atom {:text "默认回复" :tool-calls nil}))
  (extract-tool-calls [_ response] (:tool-calls response))
  (extract-text [_ response] (:text response))
  (build-tool-result [_ tool-id content] {:role "tool" :tool_call_id tool-id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (call-llm-stream [_ _config _messages _tools on-token]
    (let [resp (take-response! responses-atom {:text "默认回复"})]
      (doseq [[i chunk] (some->> (:text resp) (partition-all (or chunk-size 2))
                                 (map #(apply str %)) (map-indexed vector))]
        (when delay-ms (Thread/sleep (long delay-ms)))
        (on-token {:token chunk})
        ;; 第一个 token 之后卡住，直到测试 deliver——**用闸门代替 sleep**：
        ;; 「等 40ms 应该够它跑到那儿了」这种测试在负载高的机器上必然间歇性红
        (when (and hold (zero? i)) (deref hold 5000 nil)))
      resp))
  (tool->schema [_ tool] tool))

(defn provider
  "responses 按顺序返回。

   - `:chunk-size` 流式分块大小
   - `:delay-ms`   每块之间睡一会儿（让 run 有可观测的时长）
   - `:hold`       promise：**第一个 token 之后把这一轮卡住**，直到测试 deliver。
     并发 / supersede / 取消这三类测试的时序不能靠 sleep 猜——猜出来的阈值在
     负载高的机器上必然间歇性红。"
  ([responses] (provider responses nil))
  ([responses {:keys [chunk-size delay-ms hold]}]
   (->StreamingMockProvider (atom responses) (or chunk-size 2) delay-ms hold)))

(defn agent-fn
  "把一份 create-agent 配置包成 runtime 要的 `:agent-fn`。

   注意 `:tools` 的合并——AG-UI 的前端 action 是**每个 run** 带的。"
  [spec]
  (fn [{:keys [conversation-id tools]}]
    (agent/create-agent (-> spec
                            (assoc :conversation-id conversation-id)
                            (update :tools #(into (vec %) tools))))))

(defn runtime
  "建一个测试用 runtime：共享 memory + pause-store（跨 run 才有连续性）。"
  ([spec] (runtime spec nil))
  ([spec rt-opts]
   (let [spec (merge {:memory (memory/in-memory-store)
                      :pause-store (pause/in-memory-pause-store)}
                     spec)]
     (rt/runtime (merge {:agent-fn (agent-fn spec)} rt-opts)))))

(defn collector
  "收事件的订阅者。返回 `{:on-event f :events (fn [] [...]) :types (fn [] [...])}`。"
  []
  (let [a (atom [])]
    {:on-event #(swap! a conj %)
     :events   (fn [] @a)
     :types    (fn [] (mapv :type @a))
     :atom     a}))

(defn wait-for
  "轮询等条件成立（测试里等后台 run 收尾用）。超时返回 false。"
  ([pred] (wait-for pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 5) (recur)))))))

(defn terminal-event
  [events]
  (first (filter #(#{:run/finished :run/error :run/cancelled :run/paused} (:type %)) events)))

(defn text-of
  "把事件流里的 delta 拼回全文。"
  [events]
  (apply str (keep #(when (= :message/delta (:type %)) (:text %)) events)))
