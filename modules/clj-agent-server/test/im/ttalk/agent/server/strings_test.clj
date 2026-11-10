(ns im.ttalk.agent.server.strings-test
  "文案表：代码里用到的每个键都在表里（缺键会退化成只剩码），占位符渲染、`{{` 转义、错误码。
   取代 Keelson 的 localization_test（那边测中英两份资源包一致；这边只有一份中文表）。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.server.agui.support :as s]
            [im.ttalk.agent.server.strings :as strings]))

(defn- src-files []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (str %) ".clj"))))

(deftest every-used-key-is-in-the-table
  (let [used (into #{} (comp (mapcat #(re-seq #"\(msg (:[a-z-]+/[a-z0-9-]+)" (slurp %))) (map (comp keyword #(subs % 1) second)))
                   (src-files))]
    (is (seq used))
    (doseq [k used] (is (contains? strings/messages k) (str k)))))

(deftest rendering
  (is (= {:code "server/body-too-large" :text "请求体超过 9 字节"} (strings/msg :server/body-too-large 9)))
  (is (str/includes? (:text (strings/msg :agui-server/thread-mutation-bad-json)) "{\"agentId\":\"keel\"}") "{{ }} 是字面量花括号")
  (is (= "nope/missing" (:text (strings/msg :nope/missing))) "缺键 ⇒ 码"))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(deftest option-errors-keep-codes
  (let [reg (registry/registry [(s/entry "a" (s/provider []))])
        e (thrown #(host/host reg {:heartbeat-ms -1000}))]
    (is (= "agui-server/negative-heartbeat" (strings/code e)))
    (is (= :argument-out-of-range (:im.ttalk.agent.server/kind (ex-data e))))
    (is (str/starts-with? (ex-message e) ":heartbeat-ms 不能是负数"))
    (is (= "agui-registry/allow-headers-empty-string" (strings/code (thrown #(host/host reg {:allow-headers ["X-A" ""]})))))
    (is (= "agui-registry/origin-allow-need-one" (strings/code (thrown #(ahttp/origin-allow)))))
    (is (= "agui-registry/origin-allow-need-one" (strings/code (thrown #(ahttp/origin-allow "http://a" nil)))))))
