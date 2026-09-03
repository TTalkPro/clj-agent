(ns im.ttalk.agent.async-invoke-test
  "异步入口：react/invoke-async · resume-async，agent/chat-async · resume-async。

   要害只有一条——**异步路径与同步路径逐字同义**，差别只在返回值被 deferred 包了
   一层（契约 C1）。所以本 ns 的多数用例是「同一场景跑两遍，结果必须相等」。
   turn filter 在这条路径上真的会拿到 deferred，探针用例把这点钉死。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.chat-client :as core]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :refer [deftool]])
  (:import [java.util.concurrent CompletableFuture]))

(deftool noop-tool
  "占位工具"
  []
  "ok")

(deftool sensitive-noop
  "敏感占位工具（触发声明式暂停）"
  []
  {:sensitive true}
  "ok")

(defn- build [cm filters]
  (core/build-chat-client {:chat-model cm :tools [#'noop-tool] :filters (vec filters)}))

(defn- ctx-for [cid]
  (context/with-conversation-id (context/create) cid))

(defn- two-round-model
  "第 1 轮要调工具，第 2 轮给最终答案。calls 记调用次数。"
  [calls]
  {:chat-fn (fn [_ _]
              (if (= 1 (swap! calls inc))
                (response/make-response :text nil
                  :tool-calls [{:id "t1" :name "noop-tool" :args {}}])
                (response/make-response :text "done" :tool-calls nil)))})

;;; ============================================================
;;; invoke-async ≡ invoke
;;; ============================================================

(deftest async-equals-sync-test
  (testing "同一场景：invoke 与 invoke-async 结果逐字相同"
    (let [run (fn [cid invoke-fn]
                (let [calls (atom 0)
                      store (memory/in-memory-store)
                      cc (build (two-round-model calls) [(ma/memory-filter store)])]
                  [(async/join (invoke-fn cc store [(msg/user "干活")] {:context (ctx-for cid)}) 5000)
                   @calls
                   (memory/mem-get store cid)]))
          [sync-r sync-calls sync-hist] (run "ae-1" agent-loop/invoke)
          [async-r async-calls async-hist] (run "ae-2" agent-loop/invoke-async)]
      (is (= :completed (:status sync-r) (:status async-r)))
      (is (= (get-in sync-r [:response :text]) (get-in async-r [:response :text]) "done"))
      (is (= (count (:tool-calls-made sync-r)) (count (:tool-calls-made async-r)) 1))
      (is (= sync-calls async-calls 2))
      (is (= (mapv (juxt :role :content) sync-hist)
             (mapv (juxt :role :content) async-hist))
          "memory filter 在异步路径上落库形状一致")))

  (testing "invoke-async 返回 CompletableFuture 且调用线程不阻塞"
    (let [gate (promise)
          calls (atom 0)
          cm {:chat-fn (fn [_ _]
                         (swap! calls inc)
                         @gate
                         (response/make-response :text "done" :tool-calls nil))}
          store (memory/in-memory-store)
          cc (build cm [(ma/memory-filter store)])
          cf (agent-loop/invoke-async cc store [(msg/user "干活")] {:context (ctx-for "ae-3")})]
      (is (instance? CompletableFuture cf))
      (is (false? (.isDone cf)) "LLM 还卡着，调用线程已经回来了")
      (deliver gate :go)
      (is (= "done" (get-in (async/join cf 5000) [:response :text]))))))

;;; ============================================================
;;; turn filter 在异步路径上真的拿到 deferred
;;; ============================================================

(deftest turn-filter-sees-deferred-test
  (testing "探针：同步入口拿到普通 map，异步入口拿到 deferred；两边都能改写响应"
    (let [seen (atom [])
          tagger {:name :tagger
                  :turn (fn [req chain]
                          (let [out (chain req)]
                            (swap! seen conj (async/deferred? out))
                            ;; 响应侧走 fmap —— 这一份代码同时服务两条路径
                            (flt/fmap out #(assoc % :tagged true))))}
          mk (fn [cid] (let [calls (atom 0)
                             store (memory/in-memory-store)]
                         [(build (two-round-model calls) [(ma/memory-filter store) tagger])
                          store cid]))
          [cc1 s1 c1] (mk "td-1")
          sync-r (agent-loop/invoke cc1 s1 [(msg/user "go")] {:context (ctx-for c1)})
          [cc2 s2 c2] (mk "td-2")
          async-r (async/join (agent-loop/invoke-async cc2 s2 [(msg/user "go")]
                                                       {:context (ctx-for c2)}) 5000)]
      (is (= [false true] @seen) "同步终端给普通值，异步终端给 deferred")
      (is (true? (:tagged sync-r)))
      (is (true? (:tagged async-r)) "fmap 改写在 deferred 上照样生效")
      (is (= :completed (:status sync-r) (:status async-r))))))

;;; ============================================================
;;; 递归重入（validation-turn-filter）在异步路径上
;;; ============================================================

(deftest async-validation-reentry-test
  (testing "不合格 → 反馈重入 → 合格：异步路径上重入次数与同步一致"
    (let [run (fn [cid invoke-fn]
                (let [calls (atom 0)
                      cm {:chat-fn (fn [msgs _]
                                     (swap! calls inc)
                                     (if (some #(str/includes? (str (:content %)) "未通过校验") msgs)
                                       (response/make-response :text "{\"ok\":1}" :tool-calls nil)
                                       (response/make-response :text "随口一答" :tool-calls nil)))}
                      validate (fn [r] (when-not (str/starts-with?
                                                   (get-in r [:response :text]) "{")
                                         "必须 JSON"))
                      store (memory/in-memory-store)
                      cc (build cm [(ma/memory-filter store)
                                    (flt/validation-turn-filter validate)])]
                  [(async/join (invoke-fn cc store [(msg/user "试试")] {:context (ctx-for cid)}) 5000)
                   @calls]))
          [sync-r sync-calls] (run "av-1" agent-loop/invoke)
          [async-r async-calls] (run "av-2" agent-loop/invoke-async)]
      (is (= "{\"ok\":1}" (get-in sync-r [:response :text]) (get-in async-r [:response :text])))
      (is (= 2 sync-calls async-calls) "原始答 + 反馈重入答")))

  (testing "重试耗尽：异步路径同样原样返回最后一次结果"
    (let [calls (atom 0)
          cm {:chat-fn (fn [_ _] (swap! calls inc)
                         (response/make-response :text "永远不合格" :tool-calls nil))}
          store (memory/in-memory-store)
          cc (build cm [(ma/memory-filter store)
                        (flt/validation-turn-filter (constantly "不行") :max-retries 2)])
          r (async/join (agent-loop/invoke-async cc store [(msg/user "试试")]
                                                 {:context (ctx-for "av-3")}) 5000)]
      (is (= "永远不合格" (get-in r [:response :text])))
      (is (= 3 @calls) "原始 + 2 次重试"))))

;;; ============================================================
;;; HITL：paused 透传 + resume-async
;;; ============================================================

(deftest async-pause-resume-test
  (testing ":paused 沿异步链回流（不重入），resume-async 续跑到完成"
    (let [calls (atom 0)
          cm {:chat-fn (fn [_ _]
                         (if (= 1 (swap! calls inc))
                           (response/make-response :text nil
                             :tool-calls [{:id "p1" :name "noop-tool" :args {}}])
                           (response/make-response :text "收工" :tool-calls nil)))}
          store (memory/in-memory-store)
          reentries (atom 0)
          guard {:name :guard
                 :turn (fn [req chain]
                         (flt/fmap (chain req)
                                   (fn [r] (swap! reentries inc) r)))}
          cc (build cm [(ma/memory-filter store) guard])
          opts {:context (ctx-for "ap-1") :tool-gate (fn [_] :pause)}
          paused (async/join (agent-loop/invoke-async cc store [(msg/user "干活")] opts) 5000)]
      (is (= :paused (:status paused)) "暂停是返回值，照常沿异步链回流")
      (is (= 1 @reentries) "turn filter 的 around 后半段在暂停时也执行了")
      (let [r (async/join (agent-loop/resume-async cc (:loop-state paused) :approved
                                                   {:context (ctx-for "ap-1")}) 5000)]
        (is (= :completed (:status r)))
        (is (= "收工" (get-in r [:response :text])))
        (is (= 2 @reentries) "resume 同样经过 turn 链")))))

;;; ============================================================
;;; 异常：落 error channel，不在调用线程抛
;;; ============================================================

(deftest async-error-channel-test
  (testing "provider 抛异常 → deferred 失败，join 抛解包后的原异常"
    (let [cm {:chat-fn (fn [_ _] (throw (ex-info "provider 炸" {:code 502})))}
          store (memory/in-memory-store)
          cc (build cm [(ma/memory-filter store)])
          cf (agent-loop/invoke-async cc store [(msg/user "go")] {:context (ctx-for "ec-1")})]
      (is (instance? CompletableFuture cf) "调用线程不抛")
      (let [t (try (async/join cf 5000) nil (catch Throwable t t))]
        (is (= "provider 炸" (ex-message t)))
        (is (= 502 (:code (ex-data t)))))))

  (testing "前置校验（未配 ChatModel）同步抛出——异步入口也不吞"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未配置 ChatModel"
          (agent-loop/invoke-async (core/build-chat-client {:tools []})
                                   (memory/in-memory-store) [(msg/user "go")]
                                   {:context (ctx-for "ec-2")})))))

;;; ============================================================
;;; P2：全链路 deferred —— :chat / :iteration 链也在异步形态下运行
;;; ============================================================

(deftest full-chain-deferred-test
  (testing "探针：异步入口下 :chat 与 :iteration 链的 (chain req) 都返回 deferred；
            同步入口下都是普通值"
    (let [seen (atom [])
          probe (fn [hook]
                  {:name (keyword (str "probe-" (name hook)))
                   hook (fn [req chain]
                          (let [out (chain req)]
                            (swap! seen conj [hook (async/deferred? out)])
                            out))})
          mk (fn [] (let [calls (atom 0)
                          store (memory/in-memory-store)]
                      [(build (two-round-model calls) [(ma/memory-filter store)
                                                       (probe :chat) (probe :iteration)])
                       store]))
          [cc1 s1] (mk)
          _ (agent-loop/invoke cc1 s1 [(msg/user "go")] {:context (ctx-for "fc-1")})
          sync-seen @seen
          _ (reset! seen [])
          [cc2 s2] (mk)
          _ (async/join (agent-loop/invoke-async cc2 s2 [(msg/user "go")]
                                                 {:context (ctx-for "fc-2")}) 5000)
          async-seen @seen]
      (is (= 2 (count (filter #(= :chat (first %)) sync-seen))) "两轮 LLM 调用")
      (is (every? false? (map second sync-seen)) "同步入口：两条链都是普通值")
      (is (= (map first sync-seen) (map first async-seen)) "两条路径的钩子次序一致")
      (is (every? true? (map second async-seen))
          "异步入口：:chat 与 :iteration 链拿到的都是 deferred（全链路异步）")))

  (testing "iteration filter 的递归重入在异步链上照常（重跑一轮 = 真的又跑一遍）"
    (let [calls (atom 0)
          reruns (atom 0)
          cm {:chat-fn (fn [_ _] (swap! calls inc)
                         (response/make-response :text "done" :tool-calls nil))}
          store (memory/in-memory-store)
          once? (atom true)
          retry-once {:name :retry-once
                      :iteration (fn [req chain]
                                   (flt/fbind (chain req)
                                              (fn [r]
                                                (if (compare-and-set! once? true false)
                                                  (do (swap! reruns inc) (chain req))
                                                  r))))}
          cc (build cm [(ma/memory-filter store) retry-once])
          r (async/join (agent-loop/invoke-async cc store [(msg/user "go")]
                                                 {:context (ctx-for "fc-3")}) 5000)]
      (is (= :completed (:status r)))
      (is (= 1 @reruns))
      (is (= 2 @calls) "重入那一轮的 LLM 调用真的又发生了一次"))))

;;; ============================================================
;;; 异步入口不占调用线程（P2 之后：连 LLM 调用都不占）
;;; ============================================================

(deftest async-entry-non-blocking-test
  (testing "整条链在 LLM 未返回前就把控制权还给调用线程"
    (let [gate (promise)
          entered (promise)
          cm {:chat-fn (fn [_ _]
                         (deliver entered true)
                         @gate
                         (response/make-response :text "done" :tool-calls nil))}
          store (memory/in-memory-store)
          cc (build cm [(ma/memory-filter store)])
          t0 (System/nanoTime)
          cf (agent-loop/invoke-async cc store [(msg/user "go")] {:context (ctx-for "nb-1")})
          dispatch-ms (/ (- (System/nanoTime) t0) 1e6)]
      (is (instance? CompletableFuture cf))
      (is (< dispatch-ms 200) (str "派发耗时 " dispatch-ms "ms"))
      (is (true? (deref entered 3000 :timeout)) "LLM 调用确实已在别的线程上发起")
      (is (false? (.isDone cf)))
      (deliver gate :go)
      (is (= "done" (get-in (async/join cf 5000) [:response :text]))))))

;;; ============================================================
;;; agent 级：chat-async / resume-async
;;; ============================================================

(defn- slow-agent
  "每次 LLM 调用睡 sleep-ms 的 agent（模拟真实往返）。"
  [sleep-ms answer]
  (let [cm {:chat-fn (fn [_ _]
                       (Thread/sleep (long sleep-ms))
                       (response/make-response :text answer :tool-calls nil))}]
    (agent/create-agent {:chat-client (core/build-chat-client
                                        {:chat-model cm
                                         :filters [(ma/memory-filter (memory/in-memory-store))]})})))

(deftest agent-chat-async-test
  (testing "chat-async 返回值形状与 chat 相同"
    (let [a (slow-agent 0 "你好")
          r (async/join (agent/chat-async a "hi") 5000)]
      (is (= :completed (:status r)))
      (is (= "你好" (:text r)))
      (is (= 2 (count (agent/get-history a))) "历史照常落库")))

  (testing "N 个会话并发：墙钟 ≈ 单次往返，而不是 N 倍（不阻塞的实证）"
    (let [n 8
          per-call 150
          agents (mapv (fn [i] (slow-agent per-call (str "答" i))) (range n))
          t0 (System/nanoTime)
          cfs (mapv (fn [a] (agent/chat-async a "go")) agents)   ;; 全部立刻返回
          dispatch-ms (/ (- (System/nanoTime) t0) 1e6)
          results (mapv #(async/join % 10000) cfs)
          total-ms (/ (- (System/nanoTime) t0) 1e6)]
      (is (< dispatch-ms per-call) (str "派发不阻塞，实测 " dispatch-ms "ms"))
      (is (= (mapv #(str "答" %) (range n)) (mapv :text results)))
      (is (< total-ms (* per-call 3))
          (str "并发跑完，实测 " total-ms "ms（串行会是 " (* n per-call) "ms）"))))

  (testing "异常：agent 层照常归一化成 {:status :error}，不抛给调用方"
    (let [cm {:chat-fn (fn [_ _] (throw (ex-info "provider 炸" {})))}
          a (agent/create-agent {:chat-client (core/build-chat-client {:chat-model cm})})
          r (async/join (agent/chat-async a "go") 5000)]
      (is (= :error (:status r)))
      (is (some? (:error r))))))

(deftest agent-resume-async-test
  (testing "HITL 两段都异步：chat-async 暂停 → resume-async 完成"
    (let [calls (atom 0)
          cm {:chat-fn (fn [_ _]
                         (if (= 1 (swap! calls inc))
                           (response/make-response :text nil
                             :tool-calls [{:id "s1" :name "sensitive-noop" :args {}}])
                           (response/make-response :text "已完成" :tool-calls nil)))}
          a (agent/create-agent
              {:chat-client (core/build-chat-client
                              {:chat-model cm
                               :tools [#'sensitive-noop]
                               :filters [(ma/memory-filter (memory/in-memory-store))]})
               :on-pause (fn [_] nil)})
          paused (async/join (agent/chat-async a "干活") 5000)]
      (is (= :paused (:status paused)))
      (is (true? (agent/paused? a)))
      (let [r (async/join (agent/resume-async a "approved") 5000)]
        (is (= :completed (:status r)))
        (is (= "已完成" (:text r)))))))
