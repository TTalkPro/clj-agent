(ns im.ttalk.agent.server.agui.plugins-test
  "`/run` 的两个入站挂点：`:input-transform`（改写本轮输入）与 `:mcp-proxy`（MCP Apps 前端代理：不起 run，回一对
   RUN_STARTED / RUN_FINISHED{result}）。条目里给了以条目为准，否则取 host 选项里的缺省（同原 examples/copilotkit 的口径）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.server.agui.support :as s]))

(defn- input [tid & {:as extra}]
  (json/generate-string (merge {:threadId tid :runId (str tid "-r") :messages [{:id "m1" :role "user" :content "原话"}]
                                :tools [] :context [] :state {} :forwardedProps {}}
                               extra)))

(deftest mcp-proxy-is-not-a-run
  (let [p (s/provider [(s/text "不该被调用")])
        seen (atom nil)
        reg (registry/registry [(assoc (s/entry "a" p) :mcp-proxy (fn [req] (reset! seen req) {:ok true :echo (:method req)}))])]
    (s/with-agui [c (s/start reg)]
      (let [{:keys [events]} (s/sse! c "agent/a/run" (input "t1" :forwardedProps {:__proxiedMCPRequest {:method "tools/call" :params {:name "x"}}}))]
        (is (= ["RUN_STARTED" "RUN_FINISHED"] (s/types events)))
        (is (= {:ok true :echo "tools/call"} (:result (second events))))
        (is (= "tools/call" (:method @seen)))
        (is (empty? @(:asked p)) "模型一次都没被叫")))))

(deftest mcp-proxy-from-host-options-and-errors
  (let [reg (registry/registry [(s/entry "a" (s/provider []))])]
    (s/with-agui [c (s/start reg {:mcp-proxy (fn [_] (throw (ex-info "代理挂了" {})))})]
      (let [{:keys [events]} (s/sse! c "agent/a/run" (input "t2" :forwardedProps {:__proxiedMCPRequest {:method "x"}}))]
        (is (= "代理挂了" (get-in (second events) [:result :error])))))))

(deftest no-proxy-configured-means-a-normal-run
  (let [reg (registry/registry [(s/entry "a" (s/provider [(s/text "照常回答")]))])]
    (s/with-agui [c (s/start reg)]
      (let [{:keys [events]} (s/sse! c "agent/a/run" (input "t3" :forwardedProps {:__proxiedMCPRequest {:method "x"}}))]
        (is (some #(= "TEXT_MESSAGE_CONTENT" (:type %)) events))))))

(deftest input-transform-entry-wins-over-host-default
  (let [p1 (s/provider [(s/text "ok")])
        p2 (s/provider [(s/text "ok")])
        add (fn [tag] (fn [parsed _body] (update parsed :message #(str % tag))))
        reg (registry/registry [(assoc (s/entry "a" p1) :input-transform (add "【条目】"))
                                (s/entry "b" p2)])]
    (s/with-agui [c (s/start reg {:input-transform (add "【缺省】")})]
      (s/sse! c "agent/a/run" (input "t4"))
      (s/sse! c "agent/b/run" (input "t5"))
      (is (some #(str/includes? (str %) "原话【条目】") (flatten (map seq @(:asked p1)))) (pr-str @(:asked p1)))
      (is (some #(str/includes? (str %) "原话【缺省】") (flatten (map seq @(:asked p2)))) (pr-str @(:asked p2))))))
