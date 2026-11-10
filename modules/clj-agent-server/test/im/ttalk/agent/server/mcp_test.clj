(ns im.ttalk.agent.server.mcp-test
  "S-06：MCP Streamable HTTP 端点。直调 Ring 处理器覆盖各分支；起 Jetty 后用 clj-agent-mcp 自己的客户端做完整往返。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.server :as srv]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.jetty :as jetty]
            [im.ttalk.agent.server.mcp :as mcp-http])
  (:import (java.io ByteArrayInputStream)))

(def server
  (srv/server {:name "test" :version "1.0"
               :tools [{:name "echo" :description "回声"
                        :parameters {"type" "object" "properties" {"text" {"type" "string"}} "required" ["text"]}
                        :handler (fn [args _] (str "echo:" (get args "text")))}
                       {:name "boom" :description "总是失败" :parameters {"type" "object" "properties" {}}
                        :handler (fn [_ _] (throw (ex-info "炸了" {})))}]}))

(defn- req [method & {:keys [body headers]}]
  {:request-method method :uri "/mcp"
   :headers (merge {"content-type" "application/json"} headers)
   :body (when body (ByteArrayInputStream. (.getBytes ^String (if (string? body) body (json/generate-string body)) "UTF-8")))})

(defn- json-of [resp] (some-> (:body resp) json/parse-string))

(def ^:private modern-meta
  {p/meta-protocol-version p/version-2026-07 p/meta-client-caps {}})

(defn- rpc [id method params] {"jsonrpc" "2.0" "id" id "method" method "params" (assoc params "_meta" modern-meta)})

(defn- mcp-headers [method & [name]]
  (cond-> {"mcp-protocol-version" p/version-2026-07 "mcp-method" method}
    name (assoc "mcp-name" name)))

(deftest post-request-and-notification
  (let [h (mcp-http/handler server)]
    (testing "请求 ⇒ 200 JSON"
      (let [r (h (req :post :body (rpc 1 "tools/list" {}) :headers (mcp-headers "tools/list")))]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "application/json"))
        (is (= ["echo" "boom"] (mapv #(get % "name") (get-in (json-of r) ["result" "tools"]))))))
    (testing "tools/call"
      (let [r (h (req :post :body (rpc 2 "tools/call" {"name" "echo" "arguments" {"text" "hi"}})
                      :headers (mcp-headers "tools/call" "echo")))]
        (is (= 200 (:status r)))
        (is (= "echo:hi" (get-in (json-of r) ["result" "content" 0 "text"])))))
    (testing "通知 ⇒ 202 空体"
      (let [r (h (req :post :body {"jsonrpc" "2.0" "method" "notifications/cancelled" "params" {}}))]
        (is (= 202 (:status r)))
        (is (nil? (:body r)))))))

(deftest post-errors
  (let [h (mcp-http/handler server {:max-body-bytes 200})]
    (is (= -32700 (get-in (json-of (h (req :post :body "{not json"))) ["error" "code"])))
    (is (= 400 (:status (h (req :post :body "{not json")))))
    (let [r (h (req :post :body [(rpc 1 "tools/list" {})]))]
      (is (= 400 (:status r)) "批量 ⇒ 400")
      (is (= -32600 (get-in (json-of r) ["error" "code"]))))
    (let [r (h (req :post :body (rpc 1 "tools/list" {}) :headers (mcp-headers "tools/call")))]
      (is (= 400 (:status r)) "头与信封不一致")
      (is (= -32020 (get-in (json-of r) ["error" "code"]))))
    (let [r (h (req :post :body {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
                                 "params" {"_meta" {p/meta-protocol-version "1999-01-01" p/meta-client-caps {}}}}))]
      (is (= 400 (:status r)) "不认识的版本 ⇒ -32022 ⇒ 400")
      (is (= -32022 (get-in (json-of r) ["error" "code"]))))
    (is (= 413 (:status (h (req :post :body (apply str (repeat 300 "x")))))))))

(deftest methods-preflight-and-origin
  (let [h (mcp-http/handler server)]
    (is (= 405 (:status (h (req :get)))))
    (is (= "POST, OPTIONS" (get-in (h (req :get)) [:headers "Allow"])))
    (is (= 405 (:status (h (req :delete)))))
    (is (= 204 (:status (h (req :options)))))
    (testing "Origin 缺省拒"
      (let [r (h (req :post :body (rpc 1 "tools/list" {}) :headers (merge (mcp-headers "tools/list") {"origin" "http://evil.example"})))]
        (is (= 403 (:status r)))
        (is (nil? (get-in r [:headers "Access-Control-Allow-Origin"]))))))
  (let [h (mcp-http/handler server {:origin-allowed (ahttp/origin-allow "http://app.example") :allow-headers ["X-Extra"]})]
    (let [r (h (req :post :body (rpc 1 "tools/list" {}) :headers (merge (mcp-headers "tools/list") {"origin" "http://app.example"})))]
      (is (= 200 (:status r)))
      (is (= "http://app.example" (get-in r [:headers "Access-Control-Allow-Origin"]))))
    (let [r (h (req :options :headers {"origin" "http://app.example"}))]
      (is (str/includes? (get-in r [:headers "Access-Control-Allow-Headers"]) "Mcp-Method"))
      (is (str/includes? (get-in r [:headers "Access-Control-Allow-Headers"]) "X-Extra")))))

(deftest async-arity
  (let [h (mcp-http/handler server)
        p (promise)]
    (h (req :get) #(deliver p %) #(deliver p %))
    (is (= 405 (:status (deref p 1000 nil))))))

(deftest full-round-trip-with-the-mcp-client-over-jetty
  (let [s (jetty/start (mcp-http/routes "/mcp" server) {:port 0})]
    (try
      (let [c (mc/client {:url (str (jetty/base-address s) "mcp")})]
        (mc/connect! c)
        (is (= :modern (mc/era c)))
        (is (= "test" (get (mc/server-info c) "name")))
        (is (= #{"echo" "boom"} (set (map #(get % "name") (mc/list-tools c)))))
        (is (= "echo:hey" (get-in (mc/call-tool c "echo" {"text" "hey"}) ["content" 0 "text"])))
        (is (true? (get (mc/call-tool c "boom" {}) "isError")) "工具失败是结果，不是协议错误"))
      (finally (jetty/stop! s)))))
