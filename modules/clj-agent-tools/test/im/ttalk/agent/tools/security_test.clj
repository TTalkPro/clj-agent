(ns im.ttalk.agent.tools.security-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.tools.security :as security]))

(defn- run-filter
  "辅助函数：执行 filter handler 并返回结果"
  [filter-def tool-name args]
  ((:handler filter-def)
   {:function {:name tool-name}
    :args args
    :context nil
    :metadata {}}))

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
  (testing "create-security-filter returns a filter definition map"
    (let [policy (security/create-security-policy {})
          f (security/create-security-filter policy)]
      (is (= :security (:name f)))
      (is (= :pre-invocation (:type f)))
      (is (fn? (:handler f)))
      (is (= 10 (:priority f)))))

  (testing "custom priority"
    (let [policy (security/create-security-policy {})
          f (security/create-security-filter policy {:priority 50})]
      (is (= 50 (:priority f))))))

(deftest security-filter-tool-blocking-test
  (testing "blocked tool is rejected"
    (let [policy (security/create-security-policy
                   {:blocked-tools #{:execute-command}})
          f (security/create-security-filter policy)
          result (run-filter f :execute-command {:command "ls"})]
      (is (= :skip (:action result)))
      (is (clojure.string/includes? (:value result) "安全策略阻止"))))

  (testing "allowed tool passes through"
    (let [policy (security/create-security-policy
                   {:blocked-tools #{:execute-command}})
          f (security/create-security-filter policy)
          result (run-filter f :read-file {:path "/tmp/test.txt"})]
      (is (= :continue (:action result)))
      (is (some? (:context result))))))

(deftest security-filter-whitelist-test
  (testing "tool not in whitelist is rejected"
    (let [policy (security/create-security-policy
                   {:allowed-tools #{:read-file :file-exists}})
          f (security/create-security-filter policy)
          result (run-filter f :write-file {:path "/tmp/test.txt" :content "data"})]
      (is (= :skip (:action result)))
      (is (clojure.string/includes? (:value result) "安全策略阻止"))))

  (testing "tool in whitelist passes"
    (let [policy (security/create-security-policy
                   {:allowed-tools #{:read-file :file-exists}})
          f (security/create-security-filter policy)
          result (run-filter f :read-file {:path "/tmp/test.txt"})]
      (is (= :continue (:action result))))))

(deftest security-filter-path-check-test
  (testing "path outside allowed dirs is rejected"
    (let [policy (security/create-security-policy
                   {:allowed-paths ["/tmp/safe"]})
          f (security/create-security-filter policy)
          result (run-filter f :read-file {:path "/etc/passwd"})]
      (is (= :skip (:action result)))
      (is (clojure.string/includes? (:value result) "安全策略阻止"))))

  (testing "path inside allowed dirs passes"
    (let [policy (security/create-security-policy
                   {:allowed-paths ["/tmp"]})
          f (security/create-security-filter policy)
          result (run-filter f :read-file {:path "/tmp/test.txt"})]
      (is (= :continue (:action result))))))

(deftest security-filter-command-check-test
  (testing "dangerous command is rejected"
    (let [policy (security/create-security-policy {})
          f (security/create-security-filter policy)
          result (run-filter f :execute-command {:command "rm -rf /"})]
      (is (= :skip (:action result)))
      (is (clojure.string/includes? (:value result) "安全策略阻止"))))

  (testing "safe command passes"
    (let [policy (security/create-security-policy {})
          f (security/create-security-filter policy)
          result (run-filter f :execute-command {:command "ls -la"})]
      (is (= :continue (:action result))))))

(deftest security-filter-url-check-test
  (testing "URL with blocked domain is rejected"
    (let [policy (security/create-security-policy
                   {:allowed-domains ["api.example.com"]})
          f (security/create-security-filter policy)
          result (run-filter f :http-get {:url "https://evil.com/data"})]
      (is (= :skip (:action result)))
      (is (clojure.string/includes? (:value result) "安全策略阻止"))))

  (testing "URL with allowed domain passes"
    (let [policy (security/create-security-policy
                   {:allowed-domains ["api.example.com"]})
          f (security/create-security-filter policy)
          result (run-filter f :http-get {:url "https://api.example.com/data"})]
      (is (= :continue (:action result))))))

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
