(ns im.ttalk.agent.plugins.security-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.plugins.security :as security]))

(deftest create-security-policy-test
  (testing "default policy allows everything"
    (let [policy (security/create-security-policy {})]
      (is (nil? (:allowed-tools policy)))
      (is (empty? (:blocked-tools policy)))))

  (testing "policy with blocked tools"
    (let [policy (security/create-security-policy
                   {:blocked-tools #{:execute-command}})]
      (is (contains? (:blocked-tools policy) :execute-command)))))

(deftest security-filter-tool-blocking-test
  (testing "blocked tool is rejected"
    (let [policy (security/create-security-policy
                   {:blocked-tools #{:execute-command}})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :execute-command
                   :tool-args {:command "ls"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "should not reach"}))]
      (is (some? (:error result)))
      (is (nil? (:result result)))))

  (testing "allowed tool passes through"
    (let [policy (security/create-security-policy
                   {:blocked-tools #{:execute-command}})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :read-file
                   :tool-args {:path "/tmp/test.txt"}
                   :tool-id "call_2"}
          result (filter-fn context (fn [_] {:result "passed" :tool-id "call_2" :name :read-file}))]
      (is (= "passed" (:result result))))))

(deftest security-filter-whitelist-test
  (testing "tool not in whitelist is rejected"
    (let [policy (security/create-security-policy
                   {:allowed-tools #{:read-file :file-exists}})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :write-file
                   :tool-args {:path "/tmp/test.txt" :content "data"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "should not reach"}))]
      (is (some? (:error result)))))

  (testing "tool in whitelist passes"
    (let [policy (security/create-security-policy
                   {:allowed-tools #{:read-file :file-exists}})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :read-file
                   :tool-args {:path "/tmp/test.txt"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "ok" :tool-id "call_1" :name :read-file}))]
      (is (= "ok" (:result result))))))

(deftest security-filter-path-check-test
  (testing "path outside allowed dirs is rejected"
    (let [policy (security/create-security-policy
                   {:allowed-paths ["/tmp/safe"]})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :read-file
                   :tool-args {:path "/etc/passwd"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "should not reach"}))]
      (is (some? (:error result)))))

  (testing "path inside allowed dirs passes"
    (let [policy (security/create-security-policy
                   {:allowed-paths ["/tmp"]})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :read-file
                   :tool-args {:path "/tmp/test.txt"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "ok" :tool-id "call_1" :name :read-file}))]
      (is (= "ok" (:result result))))))

(deftest security-filter-command-check-test
  (testing "dangerous command is rejected"
    (let [policy (security/create-security-policy {})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :execute-command
                   :tool-args {:command "rm -rf /"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "should not reach"}))]
      (is (some? (:error result)))))

  (testing "safe command passes"
    (let [policy (security/create-security-policy {})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :execute-command
                   :tool-args {:command "ls -la"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "ok" :tool-id "call_1" :name :execute-command}))]
      (is (= "ok" (:result result))))))

(deftest security-filter-url-check-test
  (testing "URL with blocked domain is rejected"
    (let [policy (security/create-security-policy
                   {:allowed-domains ["api.example.com"]})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :http-get
                   :tool-args {:url "https://evil.com/data"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "should not reach"}))]
      (is (some? (:error result)))))

  (testing "URL with allowed domain passes"
    (let [policy (security/create-security-policy
                   {:allowed-domains ["api.example.com"]})
          filter-fn (security/create-security-filter policy)
          context {:tool-name :http-get
                   :tool-args {:url "https://api.example.com/data"}
                   :tool-id "call_1"}
          result (filter-fn context (fn [_] {:result "ok" :tool-id "call_1" :name :http-get}))]
      (is (= "ok" (:result result))))))

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
