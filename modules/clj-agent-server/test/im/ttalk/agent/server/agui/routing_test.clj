(ns im.ttalk.agent.server.agui.routing-test
  "C# RoutingTests.cs（N-05）：认不出的路径对任何方法都是 404 not_found（⛔ 不是 405 + Allow），预检照旧 204；
   与 clj-net-server 的访问守卫串起来时预检排在 token 之前。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]
            [im.ttalk.agent.server.jetty :as net]
            [im.ttalk.agent.server.support :as t]))

(set! *warn-on-reflection* true)

(defn- reg [] (registry/registry [(s/entry "a" (s/provider []))]))

(defn- send-req [c method path & {:as opts}]
  (let [r (t/send! method (str (net/base-address (:server c)) (str/replace path #"^/+" ""))
                   :headers (:headers opts))
        b (:body r)]
    {:status (:status r)
     :error (when (str/starts-with? b "{") (:error (json/parse-string b true)))
     :allow? (some? (t/header r "allow"))
     :resp r}))

(deftest unknown-paths-are-404-for-every-method
  (doseq [prefix ["/api/copilotkit" "/agui" ""]]
    (with-agui [c (s/start (reg) nil {:prefix prefix})]
      (doseq [p ["nope" "nope/x" "agent/a" "agent/a/x" "threads" "threads/t-1" "threads/t-1/messages" "info/x"]
              m [:get :put :delete :patch :head]]
        (testing (str prefix " " (name m) " " p)
          (let [{:keys [status error allow?]} (send-req c m (str prefix "/" p))]
            (is (= 404 status))
            (when-not (= :head m) (is (= "not_found" error)))
            (is (not allow?))))))))

(deftest preflight-still-204-on-known-and-unknown-paths
  (with-agui [c (s/start (reg) nil {:prefix "/agui"})]
    (doseq [p ["/agui" "/agui/info" "/agui/agent/a/run" "/agui/nope/x"]]
      (testing p
        (let [{:keys [status resp]} (send-req c :options p :headers {"Origin" "http://localhost:3000" "Access-Control-Request-Method" "POST"})]
          (is (= 204 status))
          (is (str/includes? (t/header resp "access-control-allow-methods") "POST")))))))

(deftest under-access-guard-preflight-passes-without-token-real-request-needs-it
  (let [guard {:token "tk" :loopback-host-only? true :protected #(str/starts-with? % "/agui")}]
    (with-agui [c (s/start (reg) nil {:prefix "/agui" :server {:guard guard}})]
      (is (= 204 (:status (send-req c :options "/agui/agent/a/run" :headers {"Origin" "http://127.0.0.1:3000" "Access-Control-Request-Method" "POST"}))))
      (is (= 401 (:status (send-req c :get "/agui/info"))))
      (is (= 200 (:status (send-req c :get "/agui/info" :headers {"Authorization" "Bearer tk"}))))
      ;; 带着 token 的未知路径：404，不是 405
      (is (= [404 "not_found" false] ((juxt :status :error :allow?) (send-req c :get "/agui/threads?t=tk"))))
      ;; DNS rebinding：预检也拒
      (is (= 403 (t/raw-status (net/port (:server c)) :options "/agui/info" {"Host" "evil.example"})))
      ;; 真跑一次 run（token 走 X-Token）
      (let [r (t/send! :post (str (net/base-address (:server c)) "agui/agent/a/run")
                       :headers {"X-Token" "tk" "Content-Type" "application/json"}
                       :body "{\"threadId\":\"t\",\"runId\":\"r\",\"messages\":[]}")]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "RUN_FINISHED"))))))
