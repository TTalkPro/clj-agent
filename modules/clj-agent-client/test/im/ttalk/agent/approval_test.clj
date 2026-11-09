(ns im.ttalk.agent.approval-test
  "R-05 / R-06 / R-07：声明式审批策略、会话缓存、审批链、熔断、超时、模型审阅者
   （designs/runtime-borrow.md §5 §6 §7）。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.approval :as ap]
            [im.ttalk.agent.approval.model :as am]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.filter.memory :as mf]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.react :as react]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool shell "执行命令" [[argv :array "命令"]] {:sensitive true} (str "ran " (pr-str argv)))
(deftool look "只读查看" [[path :string "路径"]] (str "content of " path))

;;; ------------------------------------------------------------ 策略

(deftest strictest-test
  (is (= :forbid (:decision (ap/strictest [ap/allow (ap/prompt) (ap/forbid "x") (ap/prompt)]))))
  (is (= "first" (:reason (ap/strictest [(ap/prompt "first") (ap/prompt "second")]))))
  (is (= :allow (:decision (ap/strictest [])))))

(deftest path-and-predicates-test
  (is (= ["a" "b"] (ap/parse-path "$.a.b")))
  (is (= ["a" "b"] (ap/parse-path "a.b")))
  (is (= ["argv" 0] (ap/parse-path "$.argv[0]")))
  (is (= ["k.x"] (ap/parse-path "$['k.x']")))
  (is (= [] (ap/parse-path "$")))
  (is (thrown? clojure.lang.ExceptionInfo (ap/parse-path "$..a")))
  (is (ap/glob-match? "shell*" "shell_exec"))
  (is (ap/glob-match? "f?o" "foo"))
  (is (not (ap/glob-match? "f?o" "fooo"))))

(def rules
  [{:tool "shell" :when [{:path "$.argv" :prefix ["git" ["status" "log"]]}]
    :decision :allow :justification "只读 git"
    :match [{:argv ["git" "status" "-s"]}] :not-match [{:argv ["git" "push"]}]}
   {:tool "shell" :when [{:path "$.argv[0]" :in ["rm" "dd"]}]
    :decision :forbid :justification "危险命令"}
   {:tool "shell" :when [{:path "$.argv[1]" :regex "^-rf$"}] :decision :prompt :justification "递归删除"}
   {:tool "look" :when [{:path "path" :exists true}] :decision :allow}])

(deftest rule-policy-test
  (let [p (ap/rule-policy rules)]
    (testing "命中取最严；没命中走 fallback（sensitive）"
      (is (= :allow (:decision (p {:name "shell" :args {:argv ["git" "log"]} :sensitive? true}))))
      (is (= {:decision :forbid :reason "危险命令"} (p {:name "shell" :args {:argv ["rm" "-rf" "/"]}})))
      (is (= :prompt (:decision (p {:name "shell" :args {:argv ["ls"]} :sensitive? true}))))
      (is (= :allow (:decision (p {:name "shell" :args {:argv ["ls"]} :sensitive? false})))))
    (testing ":equals 深相等（keyword/字符串、数字 1 与 1.0）"
      (let [p2 (ap/rule-policy [{:tool "*" :when [{:path "mode" :equals "fast"} {:path "n" :equals 1}] :decision :forbid}])]
        (is (= :forbid (:decision (p2 {:name "x" :args {:mode :fast :n 1.0}}))))))
    (testing "构造时自检"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"自检失败"
                            (ap/rule-policy [{:tool "shell" :when [{:path "argv" :prefix ["git"]}]
                                              :not-match [{:argv ["git" "x"]}]}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"恰好一个运算"
                            (ap/rule-policy [{:tool "x" :when [{:path "a" :equals 1 :in [1]}]}])))
      (is (thrown? clojure.lang.ExceptionInfo (ap/rule-policy [{:tool "x" :decision :maybe}])))
      (is (thrown? clojure.lang.ExceptionInfo (ap/rule-policy [{:tool "x" :justification "  "}])))
      (is (thrown? clojure.lang.ExceptionInfo (ap/rule-policy [{:tool "x" :when [{:path "a" :regex "("}]}]))))))

(deftest hint-policy-test
  (let [p (ap/hint-policy :modes [["gh_*" :writes]])
        req (fn [nm hints] {:name nm :inline? true :hints hints})]
    (is (= :allow (:decision (p (req "fs_read" {:read-only? true})))))
    (is (= :prompt (:decision (p (req "fs_rm" {:destructive? true :read-only? true})))))
    (is (= :prompt (:decision (p (req "unknown" {})))) "缺注解 ⇒ 问")
    (is (= :allow (:decision (p (req "closed" {:destructive? false :open-world? false})))))
    (testing ":writes 模式问的时候不许会话批准"
      (is (= {:decision :prompt :allow-session? false} (p (req "gh_push" {})))))
    (testing "var 工具仍按 :sensitive"
      (is (= :allow (:decision (p {:name "look" :inline? false :sensitive? false})))))
    (testing "hints-of 读 MCP annotations"
      (is (= {:read-only? true :destructive? nil :open-world? false}
             (ap/hints-of {:name "t" :annotations {"readOnlyHint" true "openWorldHint" false}}))))))

;;; ------------------------------------------------------------ gate

(defn- cc* [cm] (cc/build-chat-client {:chat-model cm :tools [#'shell #'look]}))

(deftest gate-test
  (let [cache (ap/session-cache)
        g (ap/gate (cc* {:chat-fn (fn [_ _] nil)}) {:policy (ap/rule-policy rules) :cache cache})
        call (fn [args] {:id "1" :name "shell" :args args})]
    (is (= :proceed (g (call {:argv ["git" "status"]}))))
    (is (= {:reject "危险命令"} (g (call {:argv ["rm" "x"]}))))
    (let [d (g (call {:argv ["ls" "-la"]}))]
      (is (true? (get-in d [:pause :allow-session?])))
      (ap/remember! cache (get-in d [:pause :key])))
    (testing "会话缓存命中（键规范化）"
      (is (= :proceed (g (call {:argv ["ls" "-la"]}))))
      (is (= (ap/approval-key "t" {:a 1 :b 2}) (ap/approval-key "t" {:b 2 :a 1}))))
    (testing "快照可 EDN 往返"
      (let [c2 (ap/session-cache)]
        (ap/restore! c2 (edn/read-string (pr-str (ap/snapshot cache))))
        (is (= @cache @c2))))))

;;; ------------------------------------------------------------ 审批者组合子 + 熔断

(deftest approvers-test
  (testing "链：第一个表态的说了算"
    (is (= {:approved? true} ((ap/chain-approver (constantly nil) (constantly {:approved? true})
                                                 (constantly {:approved? false})) {}))))
  (testing "无人表态 ⇒ 拒；chain-reviewer 返回 nil"
    (is (false? (:approved? ((ap/chain-approver (constantly nil)) {}))))
    (is (nil? ((ap/chain-reviewer (constantly nil)) {}))))
  (testing "某环抛 ⇒ 拒，不再往下问"
    (let [asked (atom false)
          r ((ap/chain-approver (fn [_] (throw (ex-info "挂了" {}))) (fn [_] (reset! asked true) {:approved? true})) {})]
      (is (false? (:approved? r)))
      (is (str/includes? (:reason r) "挂了"))
      (is (false? @asked))))
  (testing "超时是结果"
    (let [r ((ap/timeout-approver (fn [_] (Thread/sleep 2000) {:approved? true}) 50) {})]
      (is (= {:approved? false :timed-out? true} (select-keys r [:approved? :timed-out?])))))
  (testing "熔断：连拒 3 次跳闸、只跳一次、批准清零连拒"
    (let [b (ap/denial-breaker)]
      (is (= [false false] [(ap/record! b true) (ap/record! b true)]))
      (is (false? (ap/record! b false)))
      (is (= [false false true false] [(ap/record! b true) (ap/record! b true) (ap/record! b true) (ap/record! b true)]))))
  (testing "窗口内拒满 max-recent 也跳"
    (let [b (ap/denial-breaker {:max-consecutive 100 :max-recent 3 :window 5})]
      (is (= [false false false false true]
             [(ap/record! b true) (ap/record! b false) (ap/record! b true) (ap/record! b false) (ap/record! b true)])))))

;;; ------------------------------------------------------------ approval-filter（同步审批路径）

(defn- scripted [responses]
  (let [q (atom responses)]
    {:chat-fn (fn [_ _] (let [r (first @q)] (swap! q rest) (or r (response/make-response :text "完"))))}))

(defn- shell-call [id argv] (response/make-response :tool-calls [{:id id :name "shell" :args {:argv argv}}]))

(deftest approval-filter-test
  (testing "forbid 不问直接拒；prompt 交审批者；remember :session 后同键不再问"
    (let [asked (atom 0)
          store (memory/in-memory-store)
          cache (ap/session-cache)
          f (ap/approval-filter {:policy (ap/rule-policy rules) :cache cache
                                 :approver (fn [_] (swap! asked inc) {:approved? true :remember :session})})
          c (cc/build-chat-client {:chat-model (scripted [(shell-call "1" ["rm" "x"]) (shell-call "2" ["ls"])
                                                          (shell-call "3" ["ls"])])
                                   :tools [#'shell] :filters [(mf/memory-filter store) f]})
          r (react/invoke c store [(msg/user "go")] {:context (ctx/with-conversation-id {} "af-1")})
          results (mapv :content (filter msg/tool? (memory/mem-get store "af-1")))]
      (is (= :completed (:status r)))
      (is (= ["已拒绝执行：危险命令" "ran [\"ls\"]" "ran [\"ls\"]"] results))
      (is (= 1 @asked))))
  (testing "熔断跳闸 → 停回合"
    (let [store (memory/in-memory-store)
          f (ap/approval-filter {:approver (constantly {:approved? false :reason "不行"})
                                 :breaker {:max-consecutive 2}})
          c (cc/build-chat-client {:chat-model (scripted (map #(shell-call (str %) ["ls"]) (range 5)))
                                   :tools [#'shell] :filters [(mf/memory-filter store) f]})
          r (react/invoke c store [(msg/user "go")] {:context (ctx/with-conversation-id {} "af-2")})]
      (is (= :denial-breaker (:stopped-by r)))
      (is (= 2 (count (:tool-calls-made r))))))
  (testing "没有审批者 ⇒ 敏感操作一律拒"
    (let [c (cc/build-chat-client {:chat-model (scripted []) :tools [#'shell]
                                   :filters [(ap/approval-filter {})]})]
      (is (str/starts-with? (:value (cc/invoke-tool c :shell {:argv ["ls"]} {})) "已拒绝执行")))))

;;; ------------------------------------------------------------ simple-agent 端到端（暂停路径）

(defn- mk-agent [responses approval & [extra]]
  (agent/create-agent (merge {:chat-client (cc* (scripted responses))
                              :approval approval}
                             extra)))

(deftest agent-approval-test
  (testing "forbid 不暂停，直接回拒绝给模型"
    (let [a (mk-agent [(shell-call "1" ["rm" "-rf" "/"])] {:policy (ap/rule-policy rules)})
          r (agent/chat a "删库")]
      (is (= :completed (:status r)))
      (is (some #(= "已拒绝执行：危险命令" (:content %)) (agent/get-history a)))))
  (testing "prompt 暂停 → {:remember :session} 批准 → 同参数下一轮不再暂停"
    (let [a (mk-agent [(shell-call "1" ["ls"]) (response/make-response :text "ok1")
                       (shell-call "2" ["ls"]) (response/make-response :text "ok2")]
                      {:policy (ap/rule-policy rules)})
          r1 (agent/chat a "列目录")]
      (is (= :paused (:status r1)))
      (is (= 1 (count (:pending-tools r1))))
      (is (= :completed (:status (agent/resume a "approved" {:remember :session}))))
      (is (= :completed (:status (agent/chat a "再列一次"))))))
  (testing "会话缓存随暂停快照跨重启恢复"
    (let [ps (pause/in-memory-pause-store)
          store (memory/in-memory-store)
          cfg (fn [responses] {:chat-client (cc* (scripted responses)) :memory store :pause-store ps
                               :conversation-id "x-1" :approval {:policy (ap/rule-policy rules)}})
          a1 (agent/create-agent (cfg [(shell-call "1" ["ls"]) (response/make-response :text "ok")
                                       (shell-call "2" ["pwd"])]))
          _ (agent/chat a1 "ls")
          _ (agent/resume a1 "approved" {:remember :session})
          r (agent/chat a1 "pwd")
          snap (edn/read-string (pr-str (pause/pause-load ps "x-1")))]
      (is (= :paused (:status r)))
      (is (= 1 (count (:session-approvals snap))))
      ;; 「重启」：新 agent、新缓存，同 pause-store
      (let [a2 (agent/create-agent (cfg [(response/make-response :text "done")]))]
        (is (= :completed (:status (agent/resume a2 "approved"))))
        (is (ap/approved? (get-in a2 [:approval :cache]) (ap/approval-key "shell" {:argv ["ls"]}))))))
  (testing "审批超时：暂停太久再 resume 按拒绝处理"
    (let [a (mk-agent [(shell-call "1" ["ls"])] {:policy (ap/rule-policy rules) :timeout-ms 1})
          _ (agent/chat a "ls")
          _ (Thread/sleep 20)
          r (agent/resume a "approved")]
      (is (= :completed (:status r)))
      (is (some #(= "已拒绝执行：审批超时" (:content %)) (agent/get-history a)))))
  (testing "暂停路径的拒绝熔断"
    (let [a (mk-agent (map #(shell-call (str %) ["ls"]) (range 5))
                      {:policy (ap/rule-policy rules) :breaker {:max-consecutive 2}})]
      (agent/chat a "ls")
      (is (= :paused (:status (agent/resume a "rejected"))))
      (let [r (agent/resume a "rejected")]
        (is (= :completed (:status r)))
        (is (= :denial-breaker (:stopped-by r)))))))

;;; ------------------------------------------------------------ 模型审阅者

(deftest model-approver-test
  (let [reply (atom nil)
        seen (atom nil)
        cm {:chat-fn (fn [ms opts] (reset! seen [ms opts])
                       (let [r @reply] (if (instance? Throwable r) (throw r) (response/make-response :text r))))}
        appr (am/model-approver cm :history-fn (fn [_] [(msg/system "sys") (msg/user "旧消息")
                                                        (msg/user (apply str (repeat 5000 "很长")))])
                                :message-tokens 50)]
    (testing "allow / deny 映射"
      (reset! reply "{\"outcome\":\"allow\"}")
      (is (= {:approved? true :risk-level :low} (select-keys (appr {:name "shell" :args {:argv ["ls"]}}) [:approved? :risk-level])))
      (reset! reply "好的，结论如下：{\"risk_level\":\"high\",\"user_authorization\":\"low\",\"outcome\":\"deny\",\"rationale\":\"会删数据\"} 以上")
      (is (= {:approved? false :reason "会删数据" :risk-level :high :user-authorization :low}
             (appr {:name "shell" :args {}}))))
    (testing "证据有预算：长消息被截、system 不算"
      (let [content (:content (first (first @seen)))]
        (is (str/includes? content "chars truncated"))
        (is (not (str/includes? content "sys")))
        (is (str/includes? (:system-prompt (second @seen)) "严格的 JSON"))))
    (testing "非法输出 / 模型抛错 → 拒；可弃权"
      (reset! reply "{\"outcome\":\"maybe\"}")
      (is (false? (:approved? (appr {:name "x" :args {}}))))
      (reset! reply (ex-info "503" {}))
      (is (str/includes? (:reason (appr {:name "x" :args {}})) "503"))
      (is (nil? ((am/model-approver cm :abstain-on-failure? true) {:name "x" :args {}})))))
  (testing "超时 → 拒"
    (let [cm {:chat-fn (fn [_ _] (Thread/sleep 2000) (response/make-response :text "{\"outcome\":\"allow\"}"))}]
      (is (str/includes? (:reason ((am/model-approver cm :timeout-ms 50) {:name "x" :args {}})) "超时"))))
  (testing "最小 JSON 读取器"
    (is (= {"a" [1 2.5 true nil "x\ny"] "b" {}} (am/read-json "{\"a\":[1,2.5,true,null,\"x\\ny\"],\"b\":{}}")))
    (is (= "中" (am/read-json "\"\\u4e2d\"")))))
