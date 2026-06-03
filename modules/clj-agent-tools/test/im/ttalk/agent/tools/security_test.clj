(ns im.ttalk.agent.tools.security-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.tools.security :as security]))

(defn- run-filter
  "执行 security filter 的 around。
   chain 被调用(放行)时返回 {:result :passed}，未被调用(阻止)时返回阻止结果。
   返回 {:passed? bool :result <:result 值>}。"
  [filter tool-name args]
  (let [passed (atom false)
        chain (fn [req] (reset! passed true) {:result :passed :context (:context req)})
        out ((:around filter)
             {:function {:name tool-name} :args args :context nil}
             chain)]
    {:passed? @passed :result (:result out)}))

(deftest create-security-policy-test
  (testing "default policy allows everything"
    (let [policy (security/create-security-policy {})]
      (is (nil? (:allowed-tools policy)))
      (is (empty? (:blocked-tools policy)))))

  (testing "policy with blocked tools"
    (let [policy (security/create-security-policy
                   {:blocked-tools #{:execute-command}})]
      (is (contains? (:blocked-tools policy) :execute-command)))))

(deftest security-filter-structure-test
  (testing "create-security-filter returns an filter definition map"
    (let [policy (security/create-security-policy {})
          a (security/create-security-filter policy)]
      (is (= :security (:name a)))
      (is (= :tool (:phase a)))
      (is (fn? (:around a)))
      (is (= 10 (:order a)))))

  (testing "custom order (兼容旧 :priority)"
    (let [policy (security/create-security-policy {})]
      (is (= 50 (:order (security/create-security-filter policy {:order 50}))))
      (is (= 50 (:order (security/create-security-filter policy {:priority 50})))))))

(deftest security-filter-tool-blocking-test
  (testing "blocked tool is rejected"
    (let [policy (security/create-security-policy {:blocked-tools #{:execute-command}})
          a (security/create-security-filter policy)
          {:keys [passed? result]} (run-filter a :execute-command {:command "ls"})]
      (is (false? passed?))
      (is (clojure.string/includes? result "安全策略阻止"))))

  (testing "allowed tool passes through"
    (let [policy (security/create-security-policy {:blocked-tools #{:execute-command}})
          a (security/create-security-filter policy)
          {:keys [passed?]} (run-filter a :read-file {:path "/tmp/test.txt"})]
      (is (true? passed?)))))

(deftest security-filter-whitelist-test
  (testing "tool not in whitelist is rejected"
    (let [policy (security/create-security-policy {:allowed-tools #{:read-file :file-exists}})
          a (security/create-security-filter policy)
          {:keys [passed? result]} (run-filter a :write-file {:path "/tmp/test.txt" :content "data"})]
      (is (false? passed?))
      (is (clojure.string/includes? result "安全策略阻止"))))

  (testing "tool in whitelist passes"
    (let [policy (security/create-security-policy {:allowed-tools #{:read-file :file-exists}})
          a (security/create-security-filter policy)
          {:keys [passed?]} (run-filter a :read-file {:path "/tmp/test.txt"})]
      (is (true? passed?)))))

(deftest security-filter-path-check-test
  (testing "path outside allowed dirs is rejected"
    (let [policy (security/create-security-policy {:allowed-paths ["/tmp/safe"]})
          a (security/create-security-filter policy)
          {:keys [passed? result]} (run-filter a :read-file {:path "/etc/passwd"})]
      (is (false? passed?))
      (is (clojure.string/includes? result "安全策略阻止"))))

  (testing "path inside allowed dirs passes"
    (let [policy (security/create-security-policy {:allowed-paths ["/tmp"]})
          a (security/create-security-filter policy)
          {:keys [passed?]} (run-filter a :read-file {:path "/tmp/test.txt"})]
      (is (true? passed?)))))

(deftest security-filter-command-check-test
  (testing "dangerous command is rejected"
    (let [policy (security/create-security-policy {})
          a (security/create-security-filter policy)
          {:keys [passed? result]} (run-filter a :execute-command {:command "rm -rf /"})]
      (is (false? passed?))
      (is (clojure.string/includes? result "安全策略阻止"))))

  (testing "safe command passes"
    (let [policy (security/create-security-policy {})
          a (security/create-security-filter policy)
          {:keys [passed?]} (run-filter a :execute-command {:command "ls -la"})]
      (is (true? passed?)))))

(deftest security-filter-url-check-test
  (testing "URL with blocked domain is rejected"
    (let [policy (security/create-security-policy {:allowed-domains ["api.example.com"]})
          a (security/create-security-filter policy)
          {:keys [passed? result]} (run-filter a :http-get {:url "https://evil.com/data"})]
      (is (false? passed?))
      (is (clojure.string/includes? result "安全策略阻止"))))

  (testing "URL with allowed domain passes"
    (let [policy (security/create-security-policy {:allowed-domains ["api.example.com"]})
          a (security/create-security-filter policy)
          {:keys [passed?]} (run-filter a :http-get {:url "https://api.example.com/data"})]
      (is (true? passed?)))))

(deftest preset-policies-test
  (testing "strict policy blocks shell and http"
    (is (contains? (:blocked-tools security/strict-policy) :execute-command))
    (is (contains? (:blocked-tools security/strict-policy) :http-get))
    (is (contains? (:blocked-tools security/strict-policy) :write-file)))

  (testing "sandbox policy blocks shell write and http"
    (is (contains? (:blocked-tools security/sandbox-policy) :execute-command))
    (is (contains? (:blocked-tools security/sandbox-policy) :http-get))
    (is (contains? (:blocked-tools security/sandbox-policy) :write-file)))

  (testing "development policy blocks nothing"
    (is (empty? (:blocked-tools security/development-policy)))))
