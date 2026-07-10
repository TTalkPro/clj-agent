(ns im.ttalk.agent.timeline-test
  "Timeline：版本链 / 时间旅行 / 分支 / 血缘 / 清理。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.timeline :as tl]))

(defn- mgr [] (tl/manager (tl/in-memory-store)))

(deftest save-chain-test
  (let [m (mgr)
        e1 (tl/save! m "s1" {:step 1})
        e2 (tl/save! m "s1" {:step 2})
        e3 (tl/save! m "s1" {:step 3})]
    (testing "版本递增、parent 链正确、位置随 save 前移"
      (is (= [1 2 3] (map :version [e1 e2 e3])))
      (is (nil? (:parent-id e1)))
      (is (= (:id e1) (:parent-id e2)))
      (is (= (:id e2) (:parent-id e3)))
      (is (= (:id e3) (tl/get-position m "s1"))))
    (testing "load-latest / list-entries / owner 隔离"
      (is (= {:step 3} (:data (tl/load-latest m "s1"))))
      (is (= [1 2 3] (map :version (tl/list-entries m "s1"))))
      (is (nil? (tl/load-latest m "other"))))))

(deftest time-travel-test
  (let [m (mgr)
        e1 (tl/save! m "s1" {:v 1})
        e2 (tl/save! m "s1" {:v 2})
        e3 (tl/save! m "s1" {:v 3})]
    (testing "go-back 沿 parent 链回退"
      (is (= (:id e1) (:id (tl/go-back! m "s1" 2))))
      (is (= {:v 1} (:data (tl/get-position-entry m "s1")))))
    (testing "go-forward 沿子链前进"
      (is (= (:id e2) (:id (tl/go-forward! m "s1" 1))))
      (is (= (:id e3) (:id (tl/go-forward! m "s1" 5))) "越界停在链尾"))
    (testing "goto 直达任意 entry"
      (is (= (:id e2) (:id (tl/goto! m "s1" (:id e2)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不存在"
            (tl/goto! m "s1" "ghost"))))
    (testing "go-back 越界停在根"
      (tl/goto! m "s1" (:id e3))
      (is (= (:id e1) (:id (tl/go-back! m "s1" 99)))))))

(deftest branch-test
  (let [m (mgr)
        e1 (tl/save! m "s1" {:v 1})
        e2 (tl/save! m "s1" {:v 2})
        _  (tl/save! m "s1" {:v 3})]
    (testing "在 v2 开分支并切换：位置落锚点，后续 save 上分支"
      (tl/create-branch! m "s1" (:id e2) "exp")
      (let [at (tl/switch-branch! m "s1" "exp")]
        (is (= (:id e2) (:id at)) "分支尚无 entry → 位置在锚点"))
      (is (= "exp" (tl/current-branch m "s1")))
      (let [b1 (tl/save! m "s1" {:v :exp-3})]
        (is (= "exp" (:branch-id b1)))
        (is (= 3 (:version b1)) "版本 = 锚点版本 + 1（与 main v3 平行）")
        (is (= (:id e2) (:parent-id b1)) "分支首存档 parent 是锚点")))
    (testing "切回 main：位置 = main 最新"
      (let [head (tl/switch-branch! m "s1" "main")]
        (is (= "main" (:branch-id head)))
        (is (= {:v 3} (:data head)))))
    (testing "load-latest 按当前分支"
      (is (= {:v 3} (:data (tl/load-latest m "s1"))))
      (tl/switch-branch! m "s1" "exp")
      (is (= {:v :exp-3} (:data (tl/load-latest m "s1")))))
    (testing "list-branches / 重复建分支报错 / 未知分支报错"
      (is (= #{"main" "exp"} (set (map :branch-id (tl/list-branches m "s1")))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"已存在"
            (tl/create-branch! m "s1" (:id e1) "exp")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未知分支"
            (tl/switch-branch! m "s1" "ghost"))))))

(deftest lineage-test
  (let [m (mgr)
        e1 (tl/save! m "s1" {:v 1})
        e2 (tl/save! m "s1" {:v 2})
        _  (tl/create-branch! m "s1" (:id e2) "exp")
        _  (tl/switch-branch! m "s1" "exp")
        b1 (tl/save! m "s1" {:v :exp})]
    (testing "血缘跨分支锚点回到根"
      (is (= [(:id e1) (:id e2) (:id b1)]
             (map :id (tl/get-lineage m "s1" (:id b1))))))))

(deftest history-and-prune-test
  (let [m (mgr)]
    (dotimes [i 6] (tl/save! m "s1" {:v i}))
    (testing "get-history :limit 取最近 n 条"
      (is (= [4 5] (map (comp :v :data) (tl/get-history m "s1" {:limit 2})))))
    (testing "prune 每分支保留最新 keep-last，当前位置不删"
      (tl/go-back! m "s1" 4)                       ;; 位置移到 v2（version 2）
      (let [deleted (tl/prune! m "s1" {:keep-last 2})]
        (is (= 3 deleted) "v1/v3/v4 删除；v2 是当前位置豁免；v5/v6 保留")
        (is (= #{1 4 5} (set (map (comp :v :data) (tl/list-entries m "s1"))))))
      (testing "被删 parent 的血缘止于断点"
        (let [latest (tl/load-latest m "s1")]
          (is (= [4 5] (map (comp :v :data)
                            (tl/get-lineage m "s1" (:id latest))))))))))

(deftest back-forward-symmetry-across-fork-test
  (let [m (mgr)
        e1 (tl/save! m "s1" {:v 1})
        e2 (tl/save! m "s1" {:v 2})]
    (tl/create-branch! m "s1" (:id e1) "exp")
    (tl/switch-branch! m "s1" "exp")
    (tl/save! m "s1" {:v :exp-2})
    ;; 现在 e1 是分叉点：main 的 v2 与 exp 的 exp-2 都是其子
    (testing "exp 分支：退过分叉点再前进，回到 exp 的线（back/forward 对称）"
      (is (= 1 (-> (tl/go-back! m "s1" 1) :data :v)) "落在分叉点 e1")
      (is (= "exp" (tl/current-branch m "s1")) "go-back 不切分支")
      (is (= :exp-2 (-> (tl/go-forward! m "s1" 1) :data :v))))
    (testing "main 分支：同一分叉点 forward 走 main 的线"
      (tl/switch-branch! m "s1" "main")            ;; 位置 = main 头 v2
      (tl/go-back! m "s1" 1)                       ;; 回到 e1
      (is (= (:id e2) (:id (tl/go-forward! m "s1" 1)))))))
