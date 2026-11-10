(ns im.ttalk.agent.server.guard-test
  "C# AccessGuardTests.cs（N-05；判据搬锦书 AccessGuard / jinshu2 tests/guard.ss）：真 Jetty；守卫是 Ring 中间件。
   改 Host 头的那几条走原始套接字（JDK HttpClient 不让设 Host）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.jetty :as server]
            [im.ttalk.agent.server.guard :as guard]
            [im.ttalk.agent.server.http :as http]
            [im.ttalk.agent.server.routing :as routing]
            [im.ttalk.agent.server.support :as t :refer [with-server]]))

(set! *warn-on-reflection* true)

(def ^:private tk "s3cret-token")

(def ^:private routes
  [["GET" "/rpc" (fn [_] (http/response 200 "rpc"))]
   ["GET" "/health" (fn [_] (http/response 200 "ok"))]
   ["GET" "/index.html" (fn [_] (http/response 200 "<html>"))]
   ["OPTIONS" "/rpc" (fn [_] (http/response 204))]])

(defn- desktop
  ([] (desktop nil))
  ([protected]
   {:token tk :loopback-host-only? true :protected (or protected #(str/starts-with? % "/rpc"))}))

(defn- status [s method path & {:as headers}]
  (:status (t/send! method (server/url s path) :headers headers)))

(defn- raw [s method path host]
  (t/raw-status (server/port s) method path {"Host" host}))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(deftest token-via-query-header-and-bearer
  (with-server [s routes {:guard (desktop)}]
    (is (= 200 (status s :get (str "/rpc?t=" tk))))
    (is (= 200 (status s :get "/rpc" "X-Token" tk)))
    (is (= 200 (status s :get "/rpc" "Authorization" (str "Bearer " tk))))
    (is (= 200 (status s :get "/rpc" "Authorization" (str "bEaReR " tk))))
    (is (= "rpc" (:body (t/send! :get (server/url s (str "/rpc?t=" tk))))))))

(deftest missing-or-wrong-token-is-401
  (with-server [s routes {:guard (desktop)}]
    (let [r (t/send! :get (server/url s "/rpc"))]
      (is (= 401 (:status r)))
      (is (= "Bearer" (t/header r "www-authenticate"))))
    (is (= 401 (status s :get (str "/rpc?t=" tk "x"))))
    (is (= 401 (status s :get (str "/rpc?t=" (subs tk 0 (dec (count tk)))))))
    (is (= 401 (status s :get "/rpc" "X-Token" "nope")))
    ;; ⛔ 不认裸串与别的 scheme
    (is (= 401 (status s :get "/rpc" "Authorization" tk)))
    (is (= 401 (status s :get "/rpc" "Authorization" (str "Basic " tk))))
    ;; POST 同样要 token
    (is (= 401 (status s :post "/rpc")))))

(deftest preflight-passes-without-token-but-real-request-does-not
  (with-server [s routes {:guard (desktop)}]
    (is (= 204 (status s :options "/rpc" "Origin" "http://127.0.0.1:3000" "Access-Control-Request-Method" "POST")))
    (is (= 401 (status s :get "/rpc")))))

(deftest preflight-exempt-is-configurable
  (with-server [s routes {:guard {:token tk :preflight-exempt (constantly false)}}]
    (is (= 401 (status s :options "/rpc")))))

(deftest unprotected-paths-pass
  (with-server [s routes {:guard (desktop)}]
    (is (= "ok" (:body (t/send! :get (server/url s "/health")))))
    (is (= "<html>" (:body (t/send! :get (server/url s "/index.html")))))
    (is (= 404 (status s :get "/nope")))))

(deftest default-protects-everything
  (with-server [s routes {:guard {:token tk}}]
    (is (= 401 (status s :get "/health")))
    (is (= 200 (status s :get (str "/health?t=" tk))))))

(deftest dns-rebinding-host-is-403-even-with-token-and-for-preflight-and-static
  (with-server [s routes {:guard (desktop)}]
    (doseq [host ["evil.example" "evil.example:80" "127.0.0.1.evil.example" "localhost.evil.example" "192.168.1.10" "[::2]"]]
      (testing host
        (is (= 403 (raw s :get (str "/rpc?t=" tk) host)))
        (is (= 403 (raw s :get "/health" host)))
        (is (= 403 (raw s :options "/rpc" host)))))))

(deftest loopback-hosts-accepted
  (with-server [s routes {:guard (desktop)}]
    (doseq [fmt ["[::1]:%d" "[::1]" "localhost:%d" "LocalHost" "127.0.0.1:%d" "127.8.9.10:%d"]
            :let [h (if (str/includes? fmt "%d") (format fmt (server/port s)) fmt)]]
      (testing h
        (is (= 200 (raw s :get (str "/rpc?t=" tk) h)))
        (is (= 200 (raw s :get "/health" h)))))))

(deftest host-check-off-accepts-any-host
  (with-server [s routes {:guard {:token tk}}]
    (is (= 200 (raw s :get (str "/rpc?t=" tk) "nas.lan")))))

(deftest loopback-host-only-without-token-only-checks-host
  (with-server [s routes {:guard {:loopback-host-only? true}}]
    (is (= 200 (status s :get "/rpc")))
    (is (= 403 (raw s :get "/rpc" "evil.example")))))

(deftest real-ipv6-loopback-connects
  ;; 本机（192.168.3.13）有 ::1；Host 头是 [::1]:port
  (with-server [s routes {:host "::1" :guard (desktop)}]
    (is (= "http://[::1]:" (subs (server/base-address s) 0 13)))
    (is (= "rpc" (:body (t/send! :get (str "http://[::1]:" (server/port s) "/rpc?t=" tk)))))))

(deftest non-loopback-bind-without-token-throws
  (doseq [host ["0.0.0.0" "::" "192.168.1.10"]]
    (testing host
      (let [e (thrown #(server/create routes {:host host :guard {:loopback-host-only? false}}))]
        (is (= "server/bind-needs-token" (err/code e)))
        (is (= :invalid-operation (:im.ttalk.agent.server/kind (ex-data e)))))
      (is (= "server/bind-needs-token" (err/code (thrown #(server/create routes {:host host :guard {:token ""}}))))))))

(deftest non-loopback-bind-with-static-start-throws-by-code
  (is (= "server/bind-needs-token" (err/code (thrown #(server/start routes {:host "0.0.0.0" :guard {}}))))))

(deftest non-loopback-bind-with-token-starts
  (with-server [s routes {:host "0.0.0.0" :guard {:token tk}}]
    (is (str/starts-with? (server/base-address s) "http://127.0.0.1:"))
    (is (= 401 (status s :get "/rpc")))
    (is (= 200 (status s :get (str "/rpc?t=" tk))))))

(deftest no-guard-keeps-old-behaviour-on-any-address
  (with-server [s routes {:host "0.0.0.0"}]
    (is (= 200 (raw s :get "/rpc" "evil.example")))))

(deftest middleware-works-without-server-option
  (with-server [s (guard/wrap-access-guard (routing/router routes) (desktop)) nil]
    (is (= 401 (status s :get "/rpc")))
    (is (= 200 (status s :get "/rpc" "X-Token" tk)))
    (is (= 403 (raw s :get "/health" "evil.example")))))

(deftest custom-carrier-names
  (with-server [s routes {:guard {:token tk :query-name "token" :header-name "X-Jinshu-Token" :accept-bearer? false}}]
    (is (= 200 (status s :get (str "/rpc?token=" tk))))
    (is (= 200 (status s :get "/rpc" "X-Jinshu-Token" tk)))
    (is (= 401 (status s :get (str "/rpc?t=" tk))))
    (is (= 401 (status s :get "/rpc" "X-Token" tk)))
    (let [r (t/send! :get (server/url s "/rpc") :headers {"Authorization" (str "Bearer " tk)})]
      (is (= 401 (:status r)))
      (is (nil? (t/header r "www-authenticate")) "不认 Bearer 就不说 Bearer"))))

(deftest carriers-can-be-switched-off
  (with-server [s routes {:guard {:token tk :query-name nil :header-name nil}}]
    (is (= 401 (status s :get (str "/rpc?t=" tk))))
    (is (= 401 (status s :get "/rpc" "X-Token" tk)))
    (is (= 200 (status s :get "/rpc" "Authorization" (str "Bearer " tk))))))

;; ── 纯函数（判据搬锦书 AccessGuardTests / jinshu2 tests/guard.ss）──────────────

(deftest host-of-header
  (doseq [[raw expected] [["127.0.0.1:5173" "127.0.0.1"] ["[::1]:5173" "[::1]"] ["[::1]" "[::1]"] ["localhost" "localhost"]
                          ["  localhost:80 " "localhost"] ["" ""] [nil ""]]]
    (is (= expected (guard/host-of-header raw)) (pr-str raw))))

(deftest is-loopback-host
  (doseq [[host expected] [["127.0.0.1" true] ["127.255.0.9" true] ["localhost" true] ["LOCALHOST" true] ["::1" true] ["[::1]" true]
                           ["0.0.0.0" false] ["::" false] ["10.0.0.1" false] ["evil.example" false] ["localhost.evil.example" false]
                           ["" false] [nil false]]]
    (is (= expected (guard/loopback-host? host)) (pr-str host))))

(deftest bearer-token
  (doseq [[raw expected] [["Bearer abc" "abc"] ["bearer   abc  " "abc"] ["BEARER abc" "abc"] ["Bearer " nil] ["Bearer" nil]
                          ["abc" nil] ["Basic abc" nil] [nil nil]]]
    (is (= expected (guard/bearer-token raw)) (pr-str raw))))

(deftest bind-needs-token
  (doseq [[host token expected] [["127.0.0.1" nil false] ["localhost" nil false] ["::1" "" false]
                                 ["0.0.0.0" nil true] ["0.0.0.0" "" true] ["0.0.0.0" "t" false]]]
    (is (= expected (guard/bind-needs-token? host token)) (pr-str [host token]))))

(deftest guard-is-plain-ring-middleware
  ;; 不起服务端：直接拿 Ring 请求 map 调（任何 Ring 服务端上都是同一个行为），同步与异步两种元数
  (let [h (guard/wrap-access-guard (fn ([_] {:status 200}) ([_ respond _] (respond {:status 200}))) (desktop))
        req {:request-method :get :uri "/rpc" :query-string (str "t=" tk) :headers {"host" "127.0.0.1:1"}}]
    (is (= 200 (:status (h req))))
    (is (= 401 (:status (h (dissoc req :query-string)))))
    (is (= 403 (:status (h (assoc req :headers {"host" "evil.example"})))))
    (let [p (promise)]
      (h (dissoc req :query-string) #(deliver p %) #(deliver p %))
      (is (= 401 (:status @p))))))
