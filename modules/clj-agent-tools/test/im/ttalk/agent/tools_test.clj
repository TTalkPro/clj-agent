(ns im.ttalk.agent.tools-test
  "门面：组选择、元数据、与 chat-client 的端到端集成、审批预览。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.filter :as filters]
            [im.ttalk.agent.tool-registry :as registry]
            [im.ttalk.agent.tools :as tools]
            [im.ttalk.agent.tools.exec :as exec]
            [im.ttalk.agent.tools.test-util :as u :refer [ok]]))

(defn- names [ts] (mapv #(:name (:tool/schema (meta %))) ts))

(deftest groups-and-meta
  (let [root (u/tmp-dir)]
    (testing "缺省只开只读的 :files"
      (is (= ["read_file" "list_dir" "glob" "grep"] (names (tools/workspace-tools {:root (str root)})))))
    (testing "组、:only、:exclude"
      (is (= ["read_file" "list_dir" "glob" "grep" "write_file" "edit_file" "apply_patch" "delete_path"]
             (names (tools/workspace-tools {:root (str root)} {:groups #{:files :edit :delete}}))))
      (is (= ["edit_file"] (names (tools/workspace-tools {:root (str root)} {:groups #{:edit} :only #{"edit_file"}}))))
      (is (= ["write_file" "apply_patch"] (names (tools/workspace-tools {:root (str root)} {:groups #{:edit} :exclude #{"edit_file"}})))))
    (testing ":all 只开配置齐了的组"
      (is (= #{"read_file" "list_dir" "glob" "grep" "write_file" "edit_file" "apply_patch" "delete_path" "web_fetch" "skill"}
             (set (names (tools/workspace-tools {:root (str root)} {:groups :all})))))
      (is (some #{"exec"} (names (tools/workspace-tools {:root (str root) :exec {:backend (exec/local-backend)}} {:groups :all})))))
    (testing "未知组 ⇒ 抛"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未知的工具组" (tools/workspace-tools {:root (str root)} {:groups #{:nope}}))))
    (testing "敏感 / 串行标注"
      (let [ts (tools/workspace-tools {:root (str root) :exec {:backend (exec/local-backend)}}
                                      {:groups #{:files :edit :delete :exec}})
            m (into {} (map (fn [v] [(:name (:tool/schema (meta v))) (meta v)])) ts)]
        (doseq [n ["write_file" "edit_file" "apply_patch" "delete_path" "exec" "exec_command" "write_stdin"]]
          (is (true? (:tool/sensitive (m n))) n)
          (is (true? (:tool/serial (m n))) n))
        (doseq [n ["read_file" "list_dir" "glob" "grep"]]
          (is (false? (:tool/sensitive (m n))) n))
        (is (str/includes? (:description (:tool/schema (m "apply_patch"))) "*** Begin Patch"))))
    (testing "每次 workspace-tools 都是独立实例（已读表不串）"
      (let [a (tools/workspace-tools {:root (str root)} {:groups #{:files :edit}})
            b (tools/workspace-tools {:root (str root)} {:groups #{:files :edit}})]
        (u/spit! (u/p root "x.txt") "x")
        (ok a "read_file" {:file_path "x.txt"})
        (is (:success (u/call a "write_file" {:path "x.txt" :content "y"})))
        (is (not (:success (u/call b "write_file" {:path "x.txt" :content "z"}))))))
    (u/delete-tree! root)))

(deftest chat-client-integration
  (let [root (u/tmp-dir)
        ts (tools/workspace-tools {:root (str root)} {:groups #{:files :edit}})
        approvals (atom [])
        client (cc/build-chat-client {:chat-model {:chat-fn (fn [& _] nil)}
                                      :tools ts
                                      :filters [(filters/approval-filter (fn [n args] (swap! approvals conj n) (= (name n) "write_file")))]})]
    (testing "var 工具进注册表，schema 来自 deftool"
      (is (= #{"read_file" "list_dir" "glob" "grep" "write_file" "edit_file" "apply_patch"}
             (set (map :name (registry/tool-schemas client))))))
    (testing "经 invoke-tool：敏感工具进审批 filter，非敏感直接跑"
      (is (str/includes? (:value (cc/invoke-tool client "write_file" {:path "a.txt" :content "hi"} nil)) "已新建"))
      (is (= "hi" (u/slurp* (u/p root "a.txt"))))
      (is (str/includes? (:value (cc/invoke-tool client "read_file" {:file_path "a.txt"} nil)) "L1: hi"))
      (is (= "用户拒绝了此敏感工具调用" (:value (cc/invoke-tool client "edit_file" {:path "a.txt" :old "hi" :new "yo"} nil))))
      (is (= ["write_file" "edit_file"] (map name @approvals)) "read_file 不进审批"))
    (testing "业务失败 ⇒ 给模型的错误文本 + :semantic"
      (let [r (cc/invoke-tool client "read_file" {:file_path "../x"} nil)]
        (is (str/starts-with? (:value r) "错误: "))
        (is (= :semantic (get-in r [:error :class])))))
    (u/delete-tree! root)))

(deftest approval-preview
  (let [root (u/tmp-dir)
        ts (tools/workspace-tools {:root (str root)} {:groups #{:files :edit :delete}})
        w (tools/workspace-of (first ts))]
    (u/spit! (u/p root "a.json") "{\"a\": 1}\n")
    (testing "没读过 ⇒ 预览说会失败（与执行同一个 plan）"
      (is (re-find #"还没有读过" (:will-fail (tools/preview w "edit_file" {:path "a.json" :old "1" :new "2"})))))
    (ok ts "read_file" {:file_path "a.json"})
    (testing "edit_file 预览：diff + 语法闸的同一次判断"
      (let [{:keys [title diff]} (tools/preview w "edit_file" {"path" "a.json" "old" "1" "new" "2"})]
        (is (= (str "编辑 " (u/p root "a.json")) title))
        (is (= "@@ -1 +1 @@\n-{\"a\": 1}\n+{\"a\": 2}\n✓ 改完之后语法没问题\n" diff)))
      (is (str/includes? (:diff (tools/preview (first ts) :edit_file {:path "a.json" :old "1}" :new "1"}))
                         "⚠️ 批准之后这次调用会被拒")))
    (testing "预览不落盘"
      (is (= "{\"a\": 1}\n" (u/slurp* (u/p root "a.json")))))
    (testing "write_file 新建 / apply_patch / delete_path"
      (is (str/starts-with? (:title (tools/preview w "write_file" {:path "n.txt" :content "x"})) "新建 "))
      (is (str/includes? (:diff (tools/preview w "apply_patch" {:input "*** Begin Patch\n*** Add File: z.txt\n+zz\n*** End Patch"}))
                         "新建 "))
      (is (str/starts-with? (:title (tools/preview w "delete_path" {:path "a.json"})) "删除文件 ")))
    (is (nil? (tools/preview w "read_file" {:file_path "a.json"})) "非写类工具没有预览")
    (u/delete-tree! root)))
