(ns im.ttalk.agent.mcp.http-transport-test
  "H-05：Streamable HTTP 传输（本机回环 com.sun.net.httpserver，不打外部端点）。"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.mcp.transport :as t])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- with-server* [handler f]
  (let [s (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
            (.createContext "/" (reify HttpHandler
                                  (handle [_ ex]
                                    (try (handler ex) (finally (.close ^HttpExchange ex))))))
            (.start))]
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress s)) "/mcp"))
         (finally (.stop s 0)))))

(defn- respond! [^HttpExchange ex status headers ^String body]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (let [b (.getBytes body StandardCharsets/UTF_8)]
    (.sendResponseHeaders ex status (if (zero? (alength b)) -1 (alength b)))
    (when (pos? (alength b)) (with-open [o (.getResponseBody ex)] (.write o b)))))

(def ^:private req {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                    "params" {"name" "echo" "_meta" {"io.modelcontextprotocol/protocolVersion" "2026-07-28"}}})

(deftest sse-multi-line-data-and-crlf
  (doseq [[label wire] [["多行 data" "event: message\ndata: {\"jsonrpc\":\"2.0\",\ndata: \"id\":1,\"result\":{}}\n\n"]
                        ["CRLF" "event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\r\n\r\n"]
                        ["无收尾空行" "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n"]
                        ["先推通知再给响应" ": ping\n\ndata: not-json\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n"]]]
    (testing label
      (with-server* (fn [ex] (respond! ex 200 {"Content-Type" "text/event-stream"} wire))
        (fn [url]
          (is (= {"jsonrpc" "2.0" "id" 1 "result" {}} (t/send! (t/http-transport {:url url}) req))))))))

(deftest required-headers-session-and-errors
  (let [seen (atom nil)]
    (with-server* (fn [^HttpExchange ex]
                    (reset! seen (into {} (map (fn [[k v]] [(.toLowerCase ^String k) (first v)])) (.getRequestHeaders ex)))
                    (if (= "boom" (get @seen "x-case"))
                      (respond! ex 400 {"Content-Type" "application/json"}
                                (json/generate-string {"jsonrpc" "2.0" "id" 1 "error" {"code" -32022 "message" "bad version"}}))
                      (respond! ex 200 {"Content-Type" "application/json" "Mcp-Session-Id" "s-1"}
                                (json/generate-string {"jsonrpc" "2.0" "id" 1 "result" {}}))))
      (fn [url]
        (let [tr (t/http-transport {:url url :headers {"Authorization" "Bearer x" "mcp-method" "spoofed"}})]
          (is (= {} (get (t/send! tr req) "result")))
          (is (= "tools/call" (get @seen "mcp-method")) "调用方的同名头盖不掉必填头")
          (is (= "echo" (get @seen "mcp-name")))
          (is (= "Bearer x" (get @seen "authorization")))
          (is (= "application/json" (get @seen "content-type")))
          (t/send! tr req)
          (is (= "s-1" (get @seen "mcp-session-id")) "server 给的 session id 回传"))
        (let [tr (t/http-transport {:url url :headers {"X-Case" "boom"}})
              e (try (t/send! tr req) nil (catch Exception e e))]
          (is (t/transport-error? e))
          (is (= 400 (:status (ex-data e))))
          (is (= -32022 (get-in (ex-data e) [:jsonrpc-error "error" "code"]))))))))

(deftest client-and-proxy-options
  (is (some? (:http (t/http-transport {:url "http://x/"}))))
  (let [c (java.net.http.HttpClient/newHttpClient)]
    (is (identical? c (:http (t/http-transport {:url "http://x/" :client c})))))
  (is (.isPresent (.proxy ^java.net.http.HttpClient (:http (t/http-transport {:url "http://x/" :proxy "http://127.0.0.1:9"}))))))
