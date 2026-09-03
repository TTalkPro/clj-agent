(ns im.ttalk.agent.chain-result-test
  "链结果组合子 fmap / fbind / fcatch（filter-chain-design.md §2.6.4 契约 C1–C5）。

   同步实现是恒等展开，光测同步值证明不了「异步化后源码不用改」——所以每条契约
   都在**同步值与 deferred 两条路径上各测一遍**，deferred 用 `im.ttalk.agent.async`
   的 CompletionStage 适配层（C5 说的那层，JDK 自带、不引依赖）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.async :as async])
  (:import [java.util.concurrent CompletableFuture ExecutionException]))

(defn- done [v] (async/completed v))

(defn- root-cause [^Throwable t]
  (if-let [c (.getCause t)] (recur c) t))

(defn- boom [msg] (fn [_] (throw (ex-info msg {:tag ::boom}))))

;;; ============================================================
;;; C1 形态保持
;;; ============================================================

(deftest c1-shape-preserving-test
  (testing "同步值进 → 同步值出（不被包装成 deferred）"
    (let [r (flt/fmap {:status :completed :n 1} #(update % :n inc))]
      (is (map? r))
      (is (= {:status :completed :n 2} r))))

  (testing "deferred 进 → 同类 deferred 出"
    (let [r (flt/fmap (done {:n 1}) #(update % :n inc))]
      (is (instance? CompletableFuture r))
      (is (= {:n 2} @r))))

  (testing "nil 也是合法的链返回值（走 nil 实现，不 NPE）"
    (is (= :was-nil (flt/fmap nil (fn [v] (if (nil? v) :was-nil :huh)))))
    (is (= :was-nil (flt/fbind nil (fn [v] (if (nil? v) :was-nil :huh)))))))

;;; ============================================================
;;; C2 永不阻塞
;;; ============================================================

(deftest c2-never-blocks-test
  (testing "未完成的 deferred 上 fmap 立即返回，f 尚未执行"
    (let [cf (CompletableFuture.)
          ran (atom false)
          r (flt/fmap cf (fn [v] (reset! ran true) (inc v)))]
      (is (false? @ran) "fmap 不等待、不 deref")
      (is (instance? CompletableFuture r))
      (is (not (.isDone ^CompletableFuture r)))
      (.complete cf 41)
      (is (= 42 @r))
      (is (true? @ran))))

  (testing "fcatch 同样不阻塞：未完成的 deferred 原样带着 handler 返回"
    (let [cf (CompletableFuture.)
          handled (atom false)
          r (flt/fcatch cf (fn [_] (reset! handled true) :recovered))]
      (is (false? @handled))
      (.complete cf :ok)
      (is (= :ok @r))
      (is (false? @handled) "正常完成不触发 handler"))))

;;; ============================================================
;;; C3 异常语义不变
;;; ============================================================

(deftest c3-exception-semantics-test
  (testing "同步：f 抛出原样传播，不被包装（与手写 let 逐字相同）"
    (let [t (try (flt/fmap {:n 1} (boom "同步炸"))
                 nil
                 (catch Throwable t t))]
      (is (some? t))
      (is (= "同步炸" (ex-message t)))
      (is (= ::boom (:tag (ex-data t))) "原异常对象本身，不是包装层")))

  (testing "异步：f 抛出落进 error channel，不吞、不在调用点抛"
    (let [r (flt/fmap (done {:n 1}) (boom "异步炸"))]
      (is (instance? CompletableFuture r) "fmap 调用点本身不抛")
      (let [t (try @r nil (catch ExecutionException e e))]
        (is (some? t))
        (is (= "异步炸" (ex-message (root-cause t)))))))

  (testing "fcatch 同步：接住实参求值时抛出的异常"
    (is (= :recovered
           (flt/fcatch (flt/fmap {:n 1} (boom "炸")) (fn [_] :recovered))))
    (is (= "炸"
           (flt/fcatch (flt/fmap {:n 1} (boom "炸")) ex-message))))

  (testing "fcatch 同步：无异常时原样返回、handler 不执行"
    (let [called (atom false)]
      (is (= 2 (flt/fcatch (flt/fmap 1 inc) (fn [_] (reset! called true) :nope))))
      (is (false? @called))))

  (testing "fcatch 异步：error channel 上的失败被 handler 恢复"
    (let [r (flt/fcatch (flt/fmap (done 1) (boom "异步炸"))
                        (fn [t] {:recovered (ex-message t)}))]
      (is (= {:recovered "异步炸"} @r))))

  (testing "C3 的要害：handler 在两条路径上拿到**同一个异常对象**（适配层已剥掉
            CompletionException），故 ex-data / instance? 判断不必分路径写"
    (let [grab (fn [t] [(type t) (ex-message t) (ex-data t)])
          expected [clojure.lang.ExceptionInfo "炸" {:tag ::boom}]]
      (is (= expected (flt/fcatch (flt/fmap 1 (boom "炸")) grab)))
      (is (= expected @(flt/fcatch (flt/fmap (done 1) (boom "炸")) grab)))))

  (testing "反例锚点：fcatch 若是函数就接不住同步异常——实参在进入函数前就抛了。
            这正是它做成宏的理由，别把它包进函数再用"
    (let [fcatch-as-fn (fn [x h] (flt/fcatch x h))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"炸"
            (fcatch-as-fn (flt/fmap 1 (boom "炸")) (constantly :never))))
      ;; deferred 路径不受影响（失败在 error channel 里，不在实参求值时抛）
      (is (= :recovered @(fcatch-as-fn (flt/fmap (done 1) (boom "炸")) (constantly :recovered))))))

  (testing "fcatch 异步：终端在返回 deferred 之前就同步抛，也接得住"
    (is (= :recovered
           (flt/fcatch (throw (ex-info "终端直接抛" {})) (fn [_] :recovered)))))

  (testing "handler 返回 deferred 时**不产生嵌套**——同步路径的 try/catch 本就
            原样返回 handler 的结果，异步这边靠 compose 补齐（重试就这么写）"
    (let [recover (fn [_] (done :recovered))]
      ;; 同步：catch 分支直接返回 handler 的结果（deferred 原样）
      (is (= :recovered @(flt/fcatch (flt/fmap 1 (boom "炸")) recover)))
      ;; 异步：exceptionallyCompose，不是 exceptionally——否则这里是 CF<CF>
      (let [r (flt/fcatch (flt/fmap (done 1) (boom "炸")) recover)]
        (is (= :recovered @r))
        (is (not (instance? CompletableFuture @r)) "拆开只有一层"))))

  (testing "handler 自身抛出照常传播，不被二次吞掉"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"handler 炸"
          (flt/fcatch (throw (ex-info "原始" {})) (boom "handler 炸"))))))

;;; ============================================================
;;; C4 组合律
;;; ============================================================

(deftest c4-functor-laws-test
  (let [f #(* 2 %)
        g #(+ 3 %)]
    (testing "同步：结合律 (fmap (fmap x f) g) ≡ (fmap x (comp g f))"
      (doseq [x [0 1 7 -5]]
        (is (= (flt/fmap (flt/fmap x f) g)
               (flt/fmap x (comp g f))))))

    (testing "同步：恒等律 (fmap x identity) ≡ x（且是同一个对象）"
      (let [x {:status :completed :response "答"}]
        (is (identical? x (flt/fmap x identity)))))

    (testing "deferred：结合律"
      (is (= @(flt/fmap (flt/fmap (done 7) f) g)
             @(flt/fmap (done 7) (comp g f)))))

    (testing "deferred：恒等律（值相等；deferred 对象本身允许换壳）"
      (is (= 7 @(flt/fmap (done 7) identity))))

    (testing "fbind 左单位元：同步值上 (fbind v f) ≡ (f v)"
      (let [h (fn [v] {:wrapped v})]
        (is (= (h 7) (flt/fbind 7 h)))))

    (testing "fbind 结合律"
      (let [h (fn [v] (done (* 2 v)))
            k (fn [v] (done (+ 3 v)))]
        (is (= @(flt/fbind (flt/fbind (done 7) h) k)
               @(flt/fbind (done 7) (fn [v] (flt/fbind (h v) k)))))))))

;;; ============================================================
;;; fbind：不产生嵌套 deferred
;;; ============================================================

(deftest fbind-flattens-test
  (testing "deferred + f 返回 deferred → 单层 deferred"
    (let [r (flt/fbind (done 1) (fn [v] (done (inc v))))]
      (is (instance? CompletableFuture r))
      (is (= 2 @r) "不是套了两层的 deferred")))

  (testing "同步值 + f 返回 deferred → deferred（flatMap 定义使然，非形态保持）"
    (let [r (flt/fbind 1 (fn [v] (done (inc v))))]
      (is (instance? CompletableFuture r))
      (is (= 2 @r)))))

;;; ============================================================
;;; 在链上的实际用法：响应侧改写成 fmap 后语义逐字不变
;;; ============================================================

(defn- tag-filter
  "响应侧改写的两种写法，语义应完全一致。"
  [style tag]
  (case style
    :direct (fn [req chain] (update (chain req) :tags conj tag))
    :fmap   (fn [req chain] (flt/fmap (chain req) #(update % :tags conj tag)))))

(deftest chain-with-fmap-test
  (testing "同步链上，fmap 写法与直写等价（含洋葱顺序：后进先出）"
    (let [terminal (fn [req] {:tags [] :echo (:msg req)})
          direct ((flt/build-chain [(tag-filter :direct :a) (tag-filter :direct :b)] terminal)
                  {:msg "hi"})
          fmapped ((flt/build-chain [(tag-filter :fmap :a) (tag-filter :fmap :b)] terminal)
                   {:msg "hi"})]
      (is (= {:tags [:b :a] :echo "hi"} direct))
      (is (= direct fmapped))))

  (testing "同一份 filter 源码，终端改成返回 deferred 后照常工作"
    (let [terminal (fn [req] (done {:tags [] :echo (:msg req)}))
          r ((flt/build-chain [(tag-filter :fmap :a) (tag-filter :fmap :b)] terminal)
             {:msg "hi"})]
      (is (instance? CompletableFuture r))
      (is (= {:tags [:b :a] :echo "hi"} @r))))

  (testing "递归重入（validation 形态）：同步 loop/recur 与异步 fbind 自递归同构"
    (let [attempts (atom 0)
          terminal (fn [req] (swap! attempts inc)
                     {:status :completed :ok? (>= (:round req) 2) :round (:round req)})
          retry-filter (fn [req chain]
                         (loop [req req]
                           (let [r (chain req)]
                             (if (:ok? r) r (recur (update req :round inc))))))
          sync-r ((flt/build-chain [retry-filter] terminal) {:round 0})

          _ (reset! attempts 0)
          async-terminal (fn [req] (done (terminal req)))
          async-retry (fn [req chain]
                        (letfn [(step [req]
                                  (flt/fbind (chain req)
                                             (fn [r] (if (:ok? r) r (step (update req :round inc))))))]
                          (step req)))
          async-r ((flt/build-chain [async-retry] async-terminal) {:round 0})]
      (is (= {:status :completed :ok? true :round 2} sync-r))
      (is (= sync-r @async-r) "两种写法结果逐字相同")
      (is (= 3 @attempts) "重入如实计入：0、1、2 共三次终端执行"))))
