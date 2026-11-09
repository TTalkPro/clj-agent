(ns im.ttalk.agent.admission-test
  "并发准入票据池（designs/provider-reliability.md §5）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.admission :as adm]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response])
  (:import [java.util.concurrent CompletableFuture TimeUnit]))

(defn- await-true [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 5) (recur))))))

(deftest pool-basics-test
  (testing "票数上限、等待计数、FIFO 移交、release 幂等"
    (let [p (adm/admission {:permits 1})
          t1 (adm/acquire! p "k")
          w1 (adm/acquire-async p "k")
          w2 (adm/acquire-async p "k")]
      (is (= 1 (adm/in-flight p "k")))
      (is (= 2 (adm/waiting p "k")))
      (is (not (.isDone w1)) "没票时排队，不占线程")
      (adm/release! t1)
      (adm/release! t1)                                ;; 幂等
      (let [t2 (.get w1 2 TimeUnit/SECONDS)]
        (is (some? t2))
        (is (not (.isDone w2)) "重复 release 没有把池吹大")
        (is (= 1 (adm/waiting p "k")))
        (adm/release! t2))
      (let [t3 (.get w2 2 TimeUnit/SECONDS)]
        (adm/release! t3))
      (is (await-true #(zero? (adm/in-flight p "k")) 2000))))
  (testing "按键分池互不影响；缺省 8 张"
    (let [p (adm/admission)
          a (adm/acquire! p "a")]
      (is (= 8 (:permits p)))
      (is (.isDone (adm/acquire-async p "b")))
      (adm/release! a)))
  (testing "取消的等待者被跳过"
    (let [p (adm/admission {:permits 1})
          t1 (adm/acquire! p "k")
          w1 (adm/acquire-async p "k")
          w2 (adm/acquire-async p "k")]
      (.cancel w1 false)
      (adm/release! t1)
      (let [t2 (.get w2 2 TimeUnit/SECONDS)]
        (is (some? t2))
        (adm/release! t2))))
  (testing "with-permit 异常也还票；with-permit-async"
    (let [p (adm/admission {:permits 1})]
      (is (thrown? RuntimeException (adm/with-permit p "k" #(throw (RuntimeException. "x")))))
      (is (zero? (adm/in-flight p "k")))
      (is (= 42 (async/join (adm/with-permit-async p "k" #(async/vthread (fn [] 42))) 2000)))
      (is (await-true #(zero? (adm/in-flight p "k")) 2000)))))

(defrecord SlowProvider [inflight max-seen script]
  proto/ILLMProvider
  (provider-name [_] :slow)
  (call-llm [_ _ _ _]
    (let [n (swap! inflight inc)]
      (swap! max-seen max n)
      (try (Thread/sleep 30)
           (let [r (first @script)] (swap! script rest)
             (if (instance? Throwable r) (throw r) (or r {:text "ok"})))
           (finally (swap! inflight dec)))))
  (call-llm-stream [this c m t _] (proto/call-llm this c m t))
  (extract-tool-calls [_ _] nil)
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id c] {:role "tool" :id id :content c})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (tool->schema [_ t] t))

(deftest chat-model-admission-test
  (testing "DefaultChatModel：并发被票数压住；缺省键 provider/model"
    (let [inflight (atom 0) max-seen (atom 0)
          p (adm/admission {:permits 2})
          m (cm/with-admission (cm/create-chat-model (->SlowProvider inflight max-seen (atom [])) {:model "m"}) p)
          futs (doall (for [_ (range 6)] (async/vthread #(cm/call m (req/chat-request [(msg/user "hi")])))))]
      (doseq [f futs] (async/join f 5000))
      (is (<= @max-seen 2))
      (is (instance? im.ttalk.agent.chat_model.DefaultChatModel m) "包 DefaultChatModel 返回同类型")
      (is (zero? (adm/in-flight p "slow/m")))))
  (testing "退避期间不占票"
    (let [p (adm/admission {:permits 1})
          seen (atom nil)
          err (ex-info "抖动" (errors/error :network-error "抖动" {:retryable? true}))
          m (cm/with-admission (cm/create-chat-model (->SlowProvider (atom 0) (atom 0) (atom [err {:text "ok"}]))
                                                     {:model "m"})
                               p {:key "K"})
          r (cm/call m (req/chat-request [(msg/user "hi")]
                                         {:rand-fn (constantly 0.0)
                                          :sleep-fn (fn [_] (reset! seen (adm/in-flight p "K")))}))]
      (is (= "ok" (response/response-text r)))
      (is (= 0 @seen) "退避（sleep-fn 被调用）时票已还回池里")))
  (testing "其它 ChatModel → AdmittingChatModel 装饰器"
    (let [p (adm/admission {:permits 1})
          seen (atom nil)
          m (cm/with-admission {:chat-fn (fn [_ _] (reset! seen (adm/in-flight p "default"))
                                           (response/make-response :text "ok"))}
                               p)]
      (is (= "ok" (response/response-text (cm/call m (req/chat-request [(msg/user "hi")])))))
      (is (= 1 @seen))
      (is (zero? (adm/in-flight p "default"))))))
