(ns im.ttalk.agent.tools.exec-test
  "期望值参照 Codex head_tail_buffer_tests.rs / process_manager_tests.rs（经 Keelson 转写）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools :as tools]
            [im.ttalk.agent.tools.exec :as exec]
            [im.ttalk.agent.tools.test-util :as u :refer [ok err]]))

(defn- push-str! [b s] (let [bs (.getBytes ^String s "UTF-8")] (exec/push! b bs 0 (alength bs))))
(defn- buf-str [b] (String. ^bytes (exec/to-bytes b) "UTF-8"))

(deftest head-tail-buffer
  (testing "没超预算原样"
    (let [b (exec/head-tail-buffer 10)]
      (push-str! b "hello")
      (is (= "hello" (buf-str b)))
      (is (zero? (exec/omitted b)))))
  (testing "超了：头尾各半、丢中间并计数"
    (let [b (exec/head-tail-buffer 10)]
      (push-str! b "0123456789abcdef")
      (is (= "01234bcdef" (buf-str b)))
      (is (= 6 (exec/omitted b)))
      (is (= 16 (exec/total b)))
      (is (= "01234\n... 省略了 6 字节 ...\nbcdef" (String. ^bytes (exec/to-bytes b exec/omission-marker) "UTF-8")))))
  (testing "分多次推，尾部始终是最新的"
    (let [b (exec/head-tail-buffer 6)]
      (doseq [c (map str "abcdefghij")] (push-str! b c))
      (is (= "abchij" (buf-str b)))
      (is (= 4 (exec/omitted b)))))
  (testing "预算 0：全部算省略"
    (let [b (exec/head-tail-buffer 0)]
      (push-str! b "abc")
      (is (= "" (buf-str b)))
      (is (= 3 (exec/omitted b)))))
  (testing "奇数预算尾多一字节"
    (let [b (exec/head-tail-buffer 5)]
      (push-str! b "abcdefgh")
      (is (= "abfgh" (buf-str b))))))

(defn- exec-tools [root & [extra]]
  (tools/workspace-tools (merge {:root (str root) :exec (merge {:backend (exec/local-backend)} extra)})
                         {:groups #{:exec}}))

(deftest exec-one-shot
  (let [root (u/tmp-dir)
        ts (exec-tools root)]
    (testing "argv 原样执行，工作目录缺省为根"
      (let [r (ok ts "exec" {:command ["pwd"]})]
        (is (str/starts-with? r "退出码：0\n耗时："))
        (is (str/ends-with? (str/trim r) (str root)))))
    (testing "固定环境：NO_COLOR / TERM=dumb / PAGER=cat"
      (let [r (ok ts "exec" {:command ["sh" "-c" "echo $NO_COLOR $TERM $PAGER $GIT_PAGER"]})]
        (is (str/includes? r "1 dumb cat cat"))))
    (testing "非零退出码照常回给模型（不是工具失败）"
      (is (str/starts-with? (ok ts "exec" {:command ["sh" "-c" "echo oops >&2; exit 3"]}) "退出码：3")))
    (testing "超时 ⇒ 124 + 说明"
      (let [r (ok ts "exec" {:command ["sleep" "5"] :timeout_ms 200})]
        (is (str/starts-with? r "退出码：124"))
        (is (str/includes? r "命令在"))))
    (testing "stdin 关闭：读 stdin 的命令立即见 EOF"
      (is (str/starts-with? (ok ts "exec" {:command ["cat"] :timeout_ms 5000}) "退出码：0")))
    (testing "输出头尾截断"
      (let [ts2 (exec-tools root {:max-output-bytes 100})
            r (ok ts2 "exec" {:command ["sh" "-c" "seq 1 1000"]})]
        (is (str/includes? r "省略了"))
        (is (str/includes? r "输出总行数：1000"))
        (is (str/includes? r "\n1\n2\n"))
        (is (str/ends-with? (str/trim r) "1000"))))
    (testing "workdir 越界 / 空命令 / 起不来"
      (is (re-find #"工作区之外" (err ts "exec" {:command ["pwd"] :workdir "/"})))
      (is (re-find #"命令参数为空" (err ts "exec" {:command []})))
      (is (re-find #"exec 失败" (err ts "exec" {:command ["/no/such/binary"]}))))
    (u/delete-tree! root)))

(deftest exec-backend-seam
  (let [root (u/tmp-dir)
        seen (atom nil)
        recording (reify exec/IExecBackend
                    (start-process [_ req] (reset! seen req) (exec/start-process (exec/local-backend) req)))
        ts (exec-tools root {:backend (exec/argv-prefix-backend recording ["env" "WRAPPED=yes"])})]
    (testing "argv 前缀后端：宿主接 bwrap 就这一行"
      (is (str/includes? (ok ts "exec" {:command ["sh" "-c" "echo $WRAPPED"]}) "yes"))
      (is (= ["env" "WRAPPED=yes" "sh" "-c" "echo $WRAPPED"] (:argv @seen))))
    (testing "自定义 runner：沙箱编排接在这里"
      (let [ts2 (tools/workspace-tools {:root (str root)
                                        :exec {:runner (reify exec/ICommandRunner
                                                         (run-command [_ req]
                                                           {:exit-code 0 :timed-out? false :duration-ms 1
                                                            :output (.getBytes (str "ran " (:argv req)) "UTF-8")}))}}
                                       {:groups #{:exec}})]
        (is (str/includes? (ok ts2 "exec" {:command ["x"]}) "ran [\"x\"]"))))
    (testing "没给后端 ⇒ 装配期抛"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"进程后端"
                            (tools/workspace-tools {:root (str root)} {:groups #{:exec}}))))
    (u/delete-tree! root)))

(deftest exec-sessions
  (let [root (u/tmp-dir)
        ts (exec-tools root {:sessions {:deterministic-ids? true :min-yield-ms 10 :max-background-ms 100}})
        w (tools/workspace-of (first ts))]
    (testing "很快跑完的命令：回退出码、不留会话"
      (let [r (ok ts "exec_command" {:cmd "echo hi" :login false :yield_time_ms 2000})]
        (is (str/includes? r "进程已退出，退出码 0"))
        (is (str/includes? r "\nhi"))
        (is (empty? (exec/session-ids w)))))
    (testing "交互式：cat 会话，写 stdin 再读回"
      (let [r (ok ts "exec_command" {:cmd "cat" :login false :yield_time_ms 50})
            id (parse-long (second (re-find #"会话 ID (\d+)" r)))]
        (is (= 1001 id) "确定性 id（1000 已被上一条用掉且不复用）")
        (is (= [1001] (exec/session-ids w)))
        (is (str/includes? (ok ts "write_stdin" {:session_id id :chars "ping\n" :yield_time_ms 300}) "ping"))
        (testing "空串轮询"
          (is (str/includes? (ok ts "write_stdin" {:session_id id :chars ""}) "进程仍在运行")))
        (testing "关 stdin 的办法：中断"
          (let [r (ok ts "write_stdin" {:session_id id :chars "\u0003" :yield_time_ms 500})]
            (is (str/includes? r "进程已退出"))))
        (is (re-find #"未知的会话 ID" (err ts "write_stdin" {:session_id id :chars ""})))))
    (testing "close! 结束全部"
      (ok ts "exec_command" {:cmd "sleep 30" :login false :yield_time_ms 10})
      (is (= 1 (tools/close! w)))
      (is (empty? (exec/session-ids w))))
    (u/delete-tree! root)))

(deftest prune-rules
  (let [m (fn [id t ex] {:id id :last-used t :exited? ex})]
    (testing "最近的 protected 个不动；其余先挑已退出的最久没用"
      (is (= 2 (exec/prune-candidate [(m 1 1 false) (m 2 2 true) (m 3 3 false) (m 4 4 false)] 1))))
    (testing "没有已退出的 ⇒ 最久没用的"
      (is (= 1 (exec/prune-candidate [(m 1 1 false) (m 2 2 false) (m 3 3 false)] 1))))
    (testing "全受保护 ⇒ 不驱逐"
      (is (nil? (exec/prune-candidate [(m 1 1 true) (m 2 2 true)] 8))))))

(deftest yield-clamps
  (is (= 250 (exec/clamp-exec-yield 10 250)))
  (is (= 30000 (exec/clamp-exec-yield 999999 250)))
  (is (= 5000 (exec/clamp-write-yield 250 true 300000 250)) "空轮询至少 5000")
  (is (= 300000 (exec/clamp-write-yield 999999 true 300000 250)))
  (is (= 30000 (exec/clamp-write-yield 999999 false 300000 250))))
