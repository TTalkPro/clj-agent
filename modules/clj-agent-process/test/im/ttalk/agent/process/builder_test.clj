(ns im.ttalk.agent.process.builder-test
  "Builder：组装 API 与 build 时校验。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.process.builder :as pb]))

(defn- noop-activate [_ _ _] {:events []})

(deftest build-basic-test
  (testing "组装出的 process-spec 结构完整，保留注册顺序"
    (let [spec (-> (pb/builder :demo)
                   (pb/add-step {:id :a :on-activate noop-activate})
                   (pb/add-step {:id :b :on-activate noop-activate
                                 :required-inputs [:x :y]})
                   (pb/on-event :start :a)
                   (pb/on-event :a-done :b :x)
                   (pb/on-event :a-done :b :y {:transform inc})
                   (pb/set-initial-event :start "go")
                   (pb/build))]
      (is (= :demo (:name spec)))
      (is (= [:a :b] (:step-order spec)))
      (is (= [:input] (get-in spec [:steps :a :required-inputs])) "缺省 [:input]")
      (is (= [:x :y] (get-in spec [:steps :b :required-inputs])))
      (is (= 3 (count (:bindings spec))))
      (is (= :input (get-in spec [:bindings 0 :target-input])) "缺省槽 :input")
      (is (fn? (get-in spec [:bindings 2 :transform])))
      (is (= [{:name :start :data "go" :type :public}] (:initial-events spec))))))

(deftest validation-test
  (testing "重复 step id 立即抛"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"重复注册"
          (-> (pb/builder :p)
              (pb/add-step {:id :a :on-activate noop-activate})
              (pb/add-step {:id :a :on-activate noop-activate})))))

  (testing "缺 :on-activate 抛"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"on-activate"
          (pb/add-step (pb/builder :p) {:id :a}))))

  (testing "非 keyword id 抛"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword"
          (pb/add-step (pb/builder :p) {:id "a" :on-activate noop-activate}))))

  (testing "binding 指向未注册 step → build 抛"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未注册"
          (-> (pb/builder :p)
              (pb/add-step {:id :a :on-activate noop-activate})
              (pb/on-event :e :ghost)
              (pb/build)))))

  (testing "error-handler 指向未注册 step → build 抛"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"error-handler"
          (-> (pb/builder :p)
              (pb/add-step {:id :a :on-activate noop-activate})
              (pb/on-error :ghost)
              (pb/build))))))
