(ns im.ttalk.http.proxy-test
  "H-02：代理选择。用例移植自 Keelson clj-net test/im/ttalk/net/connect_ip_test.clj 中与代理有关的断言
   （原断言经 connect-ip/proxy-for 间接判，这里直接用 proxy/select 判同一组输入）。"
  (:require [clojure.test :refer [deftest is]]
            [im.ttalk.http :as http]
            [im.ttalk.http.proxy :as proxy])
  (:import (java.net Proxy URI)))

(defn- u [s] (URI/create s))
(defn- direct? [p uri] (= [Proxy/NO_PROXY] (proxy/select p (u uri))))

(deftest environment-proxy-reads-the-usual-variables
  (is (nil? (proxy/from-env {})))
  (let [p (proxy/from-env {"https_proxy" "127.0.0.1:9" "no_proxy" ".internal, example.com"})]
    (is (some? p))
    (let [^java.net.InetSocketAddress a (.address ^Proxy (first (proxy/select p (u "https://a.example.org/"))))]
      (is (= ["127.0.0.1" 9] [(.getHostString a) (.getPort a)])))
    (is (direct? p "http://a.example.org/") "只设了 https_proxy")
    (is (direct? p "https://example.com/"))
    (is (direct? p "https://x.example.com/"))
    (is (direct? p "https://svc.internal/"))))

(deftest env-variants
  (is (some? (:http (proxy/from-env {"HTTP_PROXY" "http://p:8080"}))))
  (is (nil? (:http (proxy/from-env {"HTTP_PROXY" "http://p:8080" "GATEWAY_INTERFACE" "CGI/1.1"}))) "CGI 下不读大写（httpoxy）")
  (is (some? (:https (proxy/from-env {"ALL_PROXY" "p:1"}))) "all_proxy 兜底")
  (is (direct? (proxy/from-env {"https_proxy" "p:1" "NO_PROXY" "*"}) "https://any/")))

(deftest web-proxy-bypass
  (let [bypass (proxy/web-proxy "http://127.0.0.1:9" {:bypass ["example\\.com"]})]
    (is (direct? bypass "https://example.com/x"))
    (is (not (direct? bypass "https://other.example/x"))))
  (let [local (proxy/web-proxy "http://127.0.0.1:9" {:bypass-on-local? true})]
    (is (direct? local "http://intranet/"))
    (is (direct? local "http://127.0.0.1/"))
    (is (not (direct? local "http://a.b/")))))

(deftest loopback-host-is-judged-by-the-literal
  (doseq [[h expected] [["localhost" true] ["LOCALHOST" true] ["127.0.0.1" true] ["127.1.2.3" true] ["::1" true] ["[::1]" true]
                        ["127.example.com" false] ["10.0.0.1" false] ["example.com" false]]]
    (is (= expected (proxy/loopback-host? h)) h)))

(deftest selector-roundtrip-and-client-wiring
  (let [ps (proxy/->proxy-selector (proxy/web-proxy "http://127.0.0.1:9"))]
    (is (identical? ps (proxy/->proxy-selector ps)))
    (is (nil? (proxy/->proxy-selector nil)))
    (is (.isPresent (.proxy (http/client {:proxy ps}))))
    (is (.isPresent (.proxy (http/client {:proxy (proxy/from-env {"https_proxy" "p:1"})}))))))
