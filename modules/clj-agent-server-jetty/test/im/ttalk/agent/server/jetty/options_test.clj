(ns im.ttalk.agent.server.jetty.options-test
  "宿主选项错误的码与文案（Keelson localization_test 里与宿主有关的那几条，改测中文）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [im.ttalk.agent.server.jetty :as server]
            [im.ttalk.agent.server.strings :as strings]))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(deftest bad-host-keeps-code
  (let [e (thrown #(server/create (fn [_]) {:host "not an ip"}))]
    (is (= "server/bad-host" (strings/code e)))
    (is (= :argument (:im.ttalk.agent.server/kind (ex-data e))))
    (is (str/starts-with? (ex-message e) "host 要一个 IP 字面量或 localhost：not an ip") (ex-message e))))

(deftest bind-needs-token-keeps-code
  (let [e (thrown #(server/create (fn [_]) {:host "0.0.0.0" :guard {}}))]
    (is (= "server/bind-needs-token" (strings/code e)))
    (is (str/includes? (ex-message e) "0.0.0.0"))))

(deftest smoke-namespace-loads
  (is (fn? server/start)))
