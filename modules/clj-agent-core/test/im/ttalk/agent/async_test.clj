(ns im.ttalk.agent.async-test
  "异步适配层：虚拟线程入口、CompletionStage 适配、两回调 sink、阻塞取值。

   组合子本身的契约（C1–C5）在 chain_result_test 里；本 ns 测的是这一层
   **适配**：线程模型、动态绑定传导、异常解包、Ring 回调对接。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.async :as async])
  (:import [java.util.concurrent CompletableFuture CompletionException ExecutionException
            TimeoutException]))

(def ^:dynamic *marker* :unbound)

;;; ============================================================
;;; 基础构造与解包
;;; ============================================================

(deftest primitives-test
  (testing "deferred? 只认 CompletionStage"
    (is (true? (async/deferred? (async/completed 1))))
    (is (false? (async/deferred? 1)))
    (is (false? (async/deferred? {:status :completed})))
    (is (false? (async/deferred? nil))))

  (testing "completed / failed"
    (is (= 1 @(async/completed 1)))
    (is (thrown? ExecutionException @(async/failed (ex-info "x" {})))))

  (testing "unwrap-cause 只剥 JDK 那两层包装，剥到底"
    (let [root (ex-info "根因" {:k 1})]
      (is (identical? root (async/unwrap-cause root)))
      (is (identical? root (async/unwrap-cause (CompletionException. root))))
      (is (identical? root (async/unwrap-cause (ExecutionException. (CompletionException. root)))))
      (is (nil? (async/unwrap-cause nil)))))

  (testing "unwrap-cause 不动别的包装（业务自己的 ex-info 链要留着）"
    (let [wrapper (ex-info "外层" {} (ex-info "内层" {}))]
      (is (identical? wrapper (async/unwrap-cause wrapper))))))

;;; ============================================================
;;; vthread：线程模型 / 不阻塞 / 绑定传导 / error channel
;;; ============================================================

(deftest vthread-test
  (testing "跑在虚拟线程上，调用线程立刻拿到未完成的 CompletableFuture"
    (let [gate (promise)
          started (promise)
          cf (async/vthread (fn []
                              (deliver started (.isVirtual (Thread/currentThread)))
                              @gate
                              :done))]
      (is (instance? CompletableFuture cf))
      (is (false? (.isDone cf)) "调用线程没被阻塞：任务还卡在 gate 上")
      (is (true? (deref started 2000 :timeout)) "任务确实在虚拟线程上")
      (deliver gate :go)
      (is (= :done (async/join cf 2000)))))

  (testing "动态绑定帧带进工作线程（与 future / run-on-executor 一致）"
    (binding [*marker* :from-caller]
      (is (= :from-caller (async/join (async/vthread (fn [] *marker*)) 2000)))))

  (testing "thunk 抛出 → 落 error channel，不在调用线程抛"
    (let [cf (async/vthread (fn [] (throw (ex-info "工作线程炸" {:tag :t}))))]
      (is (instance? CompletableFuture cf))
      (let [t (try (async/join cf 2000) nil (catch Throwable t t))]
        (is (= "工作线程炸" (ex-message t)) "join 抛的是解包后的原异常")
        (is (= {:tag :t} (ex-data t))))))

  (testing "inline 是同款签名的同步跑法：当场执行、返回普通值、异常照常抛"
    (is (= 42 (async/inline (fn [] 42))))
    (is (false? (async/deferred? (async/inline (fn [] 42)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"同步炸"
          (async/inline (fn [] (throw (ex-info "同步炸" {}))))))))

;;; ============================================================
;;; on-complete：Ring 异步 handler 的那对回调
;;; ============================================================

(deftest on-complete-test
  (testing "同步值：success 在**当前线程**直接调用（同一个 handler 两种入口都成立）"
    (let [got (atom nil)
          this-thread (Thread/currentThread)
          caller (atom nil)]
      (is (nil? (async/on-complete {:status 200}
                                   (fn [v] (reset! caller (Thread/currentThread)) (reset! got v))
                                   (fn [_] (reset! got :raised)))))
      (is (= {:status 200} @got))
      (is (identical? this-thread @caller))))

  (testing "deferred 成功：success 拿到值"
    (let [p (promise)]
      (async/on-complete (async/vthread (fn [] {:status 200})) #(deliver p %) #(deliver p [:raise %]))
      (is (= {:status 200} (deref p 2000 :timeout)))))

  (testing "deferred 失败：failure 拿到**解包后**的原异常（Ring 的 raise 要的就是这个）"
    (let [p (promise)]
      (async/on-complete (async/vthread (fn [] (throw (ex-info "handler 里炸" {:code 500}))))
                         #(deliver p [:respond %])
                         #(deliver p [:raise %]))
      (let [[kind t] (deref p 2000 :timeout)]
        (is (= :raise kind))
        (is (= "handler 里炸" (ex-message t)))
        (is (= {:code 500} (ex-data t))))))

  (testing "组合出一个 Ring 形状的 handler：同一份代码接同步与异步两种链结果"
    (let [handler (fn [result respond raise]
                    (-> result
                        (flt/fmap (fn [r] {:status 200 :body (:text r)}))
                        (async/on-complete respond raise)))
          sync-out (atom nil)
          async-p (promise)]
      (handler {:text "同步"} #(reset! sync-out %) identity)
      (handler (async/vthread (fn [] {:text "异步"})) #(deliver async-p %) identity)
      (is (= {:status 200 :body "同步"} @sync-out))
      (is (= {:status 200 :body "异步"} (deref async-p 2000 :timeout))))))

;;; ============================================================
;;; join：调用方边界上的阻塞取值
;;; ============================================================

(deftest join-test
  (testing "同步值原样返回（join 对两种形态都成立，调用方不必分支）"
    (is (= 42 (async/join 42)))
    (is (= 42 (async/join 42 1000))))

  (testing "deferred 等它完成"
    (is (= 42 (async/join (async/vthread (fn [] 42)) 2000))))

  (testing "失败时抛解包后的原异常，不是 ExecutionException"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"炸"
          (async/join (async/failed (ex-info "炸" {})) 2000))))

  (testing "超时抛 TimeoutException（deferred 本身不受影响，仍在跑）"
    (let [gate (promise)
          cf (async/vthread (fn [] @gate :late))]
      (is (thrown? TimeoutException (async/join cf 50)))
      (deliver gate :go)
      (is (= :late (async/join cf 2000))))))
