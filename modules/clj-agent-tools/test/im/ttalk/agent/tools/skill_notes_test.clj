(ns im.ttalk.agent.tools.skill-notes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools :as tools]
            [im.ttalk.agent.tools.notes :as notes]
            [im.ttalk.agent.tools.skill :as skill]
            [im.ttalk.agent.tools.test-util :as u :refer [ok err]]))

(deftest frontmatter
  (is (= [{"name" "release" "description" "发版 步骤"} "\n正文"]
         (skill/parse-frontmatter "---\nname: release\ndescription: \"发版\n  步骤\"\n---\n\n正文")))
  (is (= [{} "no front"] (skill/parse-frontmatter "no front")))
  (is (= [{} "---\nname: x\n"] (skill/parse-frontmatter "---\nname: x\n")) "没闭合 ⇒ 当没有")
  (is (= "" (get (first (skill/parse-frontmatter "---\ndescription: >\n---\n")) "description"))))

(deftest skill-tool
  (let [root (u/tmp-dir)
        proj (u/p root "proj-skills")
        user (u/p root "user-skills")]
    (u/spit! (u/p proj "release" "SKILL.md") "---\nname: release\ndescription: 怎么发版\n第二行不进目录\n---\n1. bb release\n")
    (u/spit! (u/p proj "release" "scripts" "bump.sh") "echo")
    (u/spit! (u/p user "release" "SKILL.md") "---\nname: release\ndescription: 用户目录里的同名，被项目的盖掉\n---\nx")
    (u/spit! (u/p user "test-run" "SKILL.md") "跑测试：bb test")
    (u/spit! (u/p user "bad name" "SKILL.md") "x")
    (let [ts (tools/workspace-tools {:root (str root) :skills {:dirs [(str proj) (str user)]}} {:groups #{:skill}})
          desc (:description (:tool/schema (meta (first ts))))]
      (testing "说明里列出目录：同名先到的赢、没 name 用目录名、不合规的跳过"
        (is (str/includes? desc "\n- release: 怎么发版"))
        (is (str/includes? desc "\n- test-run: （没有描述）"))
        (is (not (str/includes? desc "用户目录里的同名")))
        (is (not (str/includes? desc "bad name"))))
      (testing "装载：正文去掉 frontmatter + 基目录 + 旁边的文件"
        (let [r (ok ts "skill" {:name "release"})]
          (is (str/starts-with? r "已装载 skill release\n\n1. bb release"))
          (is (str/includes? r (str "基目录：" (u/p proj "release"))))
          (is (str/includes? r (str "- " (u/p proj "release" "scripts" "bump.sh"))))))
      (is (re-find #"有的是：release, test-run" (err ts "skill" {:name "nope"}))))
    (testing "没有目录 ⇒ 说明里说清楚"
      (let [ts (tools/workspace-tools {:root (str root)} {:groups #{:skill}})]
        (is (str/includes? (:description (:tool/schema (meta (first ts)))) "当前没有可用的 skill"))))
    (u/delete-tree! root)))

(deftest notes-tools
  (let [root (u/tmp-dir)]
    (doseq [store [(notes/memory-store) (notes/dir-store (str (u/p root "notes")))]]
      (let [ts (tools/workspace-tools {:root (str root) :notes store} {:groups #{:notes}})]
        (is (= "还没有笔记。" (ok ts "notes_list" {})))
        (is (= "记下了：build.md" (ok ts "notes_write" {:name "build.md" :content "bb test"})))
        (is (= "build.md" (ok ts "notes_list" {})))
        (is (= "bb test" (ok ts "notes_read" {:name "build.md"})))
        (is (re-find #"没有这篇笔记" (err ts "notes_read" {:name "x.md"})))
        (testing "名字不能拿来拼路径逃出去"
          (is (re-find #"笔记名不成立" (err ts "notes_write" {:name "../evil" :content "x"})))
          (is (re-find #"笔记名不成立" (err ts "notes_write" {:name ".." :content "x"}))))
        (testing "notes_write 缺省不敏感，可按实例开"
          (is (false? (:tool/sensitive (meta (u/find-tool ts "notes_write"))))))))
    (let [ts (tools/workspace-tools {:root (str root) :notes (notes/memory-store) :notes-write-sensitive? true} {:groups #{:notes}})]
      (is (true? (:tool/sensitive (meta (u/find-tool ts "notes_write"))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"笔记存储"
                          (tools/workspace-tools {:root (str root)} {:groups #{:notes}})))
    (u/delete-tree! root)))
