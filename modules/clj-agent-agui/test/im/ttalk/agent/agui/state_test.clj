(ns im.ttalk.agent.agui.state-test
  "JSON Pointer / JSON Patch 与 delta 规范化（RFC 6901 / 6902）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.state :as st]))

(deftest pointer-test
  (testing "RFC 6901：转义与非法形态"
    (is (= [] (st/parse-pointer "")))
    (is (= ["a" "b"] (st/parse-pointer "/a/b")))
    (is (= ["a/b"] (st/parse-pointer "/a~1b")) "~1 是 /")
    (is (= ["a~b"] (st/parse-pointer "/a~0b")) "~0 是 ~")
    (is (= [""] (st/parse-pointer "/")) "空段是合法的键名")
    (testing "非法**返回 nil 不抛**——串来自模型，写错了该跳过这条 op，不是炸掉整条 run"
      (is (nil? (st/parse-pointer "a")))
      (is (nil? (st/parse-pointer "/a~2b")))
      (is (nil? (st/parse-pointer nil))))))

(deftest lookup-test
  (testing "「存在」与「值是 nil」必须分开——补数组那条守卫全靠它"
    (is (= {:exists? true :value nil} (st/lookup {:a nil} ["a"])))
    (is (= {:exists? false} (st/lookup {} ["a"])))
    (is (= {:exists? true :value 2} (st/lookup {:a [1 2]} ["a" "1"])))
    (is (= {:exists? false} (st/lookup {:a [1 2]} ["a" "9"])) "越界"))

  (testing "字符串键与关键字键都认"
    (is (= 1 (:value (st/lookup {"a" 1} ["a"]))))
    (is (= 1 (:value (st/lookup {:a 1} ["a"]))))))

(deftest apply-op-test
  (testing "add / remove / replace"
    (is (= {:a 1} (:root (st/apply-op {} {:op "add" :path "/a" :value 1}))))
    (is (= {} (:root (st/apply-op {:a 1} {:op "remove" :path "/a"}))))
    (is (= {:a 2} (:root (st/apply-op {:a 1} {:op "replace" :path "/a" :value 2}))))
    (is (= 9 (:root (st/apply-op {:a 1} {:op "add" :path "" :value 9})))
        "路径为空 = 整体替换"))

  (testing "数组：`-` 追加，数字插入"
    (is (= {:a [1 2 3]} (:root (st/apply-op {:a [1 2]} {:op "add" :path "/a/-" :value 3}))))
    (is (= {:a [9 1 2]} (:root (st/apply-op {:a [1 2]} {:op "add" :path "/a/0" :value 9}))))
    (is (= {:a [1]} (:root (st/apply-op {:a [1 2]} {:op "remove" :path "/a/1"})))))

  (testing "copy / move / test"
    (is (= {:a 1 :b 1} (:root (st/apply-op {:a 1} {:op "copy" :from "/a" :path "/b"}))))
    (is (= {:b 1} (:root (st/apply-op {:a 1} {:op "move" :from "/a" :path "/b"}))))
    (is (true? (:applied? (st/apply-op {:a 1} {:op "test" :path "/a" :value 1}))))
    (is (false? (:applied? (st/apply-op {:a 1} {:op "test" :path "/a" :value 2})))))

  (testing "不适用的 op **状态原样、不抛**"
    (doseq [op [{:op "add" :path "/a/b/c" :value 1}     ;; 父不存在
                {:op "remove" :path "/nope"}
                {:op "replace" :path "/nope" :value 1}
                {:op "移动" :path "/a"}                  ;; 不认识的 op
                {:op "add" :path "不是指针" :value 1}]]
      (let [{:keys [root applied?]} (st/apply-op {:a 1} op)]
        (is (false? applied?) (str op))
        (is (= {:a 1} root) (str op)))))

  (testing "op 的键是关键字还是字符串都认（路上谁 keywordize 由 provider 决定）"
    (is (= {:a 1} (:root (st/apply-op {} {"op" "add" "path" "/a" "value" 1}))))))

(deftest normalize-ops-test
  (testing "⭐ `add /x/-` 而 `/x` 不存在 → **前面插一条 `add /x []`**。

            这是那个一个字都不报的静默失败：客户端 state 是 `{}` 时收到
            `add /todos/-`，RFC 6902 要求 `/todos` 已存在 ⇒ 必然失败，而
            `@ag-ui/client` 只保留旧状态 + console.warn"
    (let [{:keys [ops state]} (st/normalize-ops [{:op "add" :path "/todos/-" :value "买牛奶"}] {})]
      (is (= [{:op "add" :path "/todos" :value []}
              {:op "add" :path "/todos/-" :value "买牛奶"}] ops))
      (is (= {:todos ["买牛奶"]} state) "服务端同步算一遍，下一条 delta 才对得上")))

  (testing "数组已存在就不补"
    (let [{:keys [ops state]} (st/normalize-ops [{:op "add" :path "/todos/-" :value "乙"}]
                                                {:todos ["甲"]})]
      (is (= 1 (count ops)))
      (is (= {:todos ["甲" "乙"]} state))))

  (testing "父都不存在就**不补**——那要连着建好几层，猜得太多；照发让它按 RFC 失败"
    (let [{:keys [ops]} (st/normalize-ops [{:op "add" :path "/a/b/-" :value 1}] {})]
      (is (= 1 (count ops)))))

  (testing "多条 op 顺序累积，后面的对着前面算完的状态"
    (let [{:keys [state]} (st/normalize-ops [{:op "add" :path "/n" :value 1}
                                             {:op "replace" :path "/n" :value 2}
                                             {:op "add" :path "/l/-" :value "x"}] {})]
      (is (= {:n 2 :l ["x"]} state)))))
