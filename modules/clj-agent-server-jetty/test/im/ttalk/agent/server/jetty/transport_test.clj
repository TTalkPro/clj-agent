(ns im.ttalk.agent.server.jetty.transport-test
  "C# ServerExtrasTests.cs 的 ServerTransportTests（Jetty 12）：HTTPS、没私钥的证书、Unix 域套接字、请求体 / 请求头 / 请求行上限。
   WebSocketTests 在 websocket_test.clj。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.jetty :as server]
            [im.ttalk.agent.server.http :as http]
            [im.ttalk.agent.server.support :as t :refer [with-server]])
  (:import (java.io File IOException)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security KeyStore)
           (javax.net.ssl SSLContext TrustManagerFactory)))

(set! *warn-on-reflection* true)

(def ^:private pw "changeit")

(defn- keytool! [& args]
  (let [exe (str (System/getProperty "java.home") File/separator "bin" File/separator "keytool")
        p (-> (ProcessBuilder. ^java.util.List (into [exe] args)) (.redirectErrorStream true) (.start))
        out (slurp (.getInputStream p))]
    (when-not (zero? (.waitFor p)) (throw (ex-info (str "keytool: " out) {})))))

(defn- load-store ^KeyStore [^File f]
  (with-open [in (io/input-stream f)]
    (doto (KeyStore/getInstance "PKCS12") (.load in (.toCharArray ^String pw)))))

(defn- self-signed
  "keytool 造一张自签证书（CN / SAN = 127.0.0.1）⇒ {:key-store 带私钥 :trust-store 只有证书}。"
  []
  (let [dir (.toFile (Files/createTempDirectory "ks-tls" (make-array FileAttribute 0)))
        ks (io/file dir "server.p12") crt (io/file dir "server.crt") ts (io/file dir "trust.p12")]
    (try
      (keytool! "-genkeypair" "-alias" "srv" "-keyalg" "RSA" "-keysize" "2048" "-dname" "CN=127.0.0.1"
                "-ext" "SAN=ip:127.0.0.1" "-validity" "1" "-storetype" "PKCS12"
                "-keystore" (str ks) "-storepass" pw "-keypass" pw)
      (keytool! "-exportcert" "-alias" "srv" "-keystore" (str ks) "-storepass" pw "-rfc" "-file" (str crt))
      (keytool! "-importcert" "-noprompt" "-alias" "srv" "-file" (str crt) "-storetype" "PKCS12"
                "-keystore" (str ts) "-storepass" pw)
      {:key-store (load-store ks) :trust-store (load-store ts)}
      (finally (doseq [^File f (reverse (file-seq dir))] (.delete f))))))

(defn- trusting ^SSLContext [^KeyStore ts]
  (let [tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm)) (.init ts))]
    (doto (SSLContext/getInstance "TLS") (.init nil (.getTrustManagers tmf) nil))))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(def ^:private hello [["GET" "/hello" (fn [_] (http/response 200 "hi"))]])

(deftest https
  (let [{:keys [key-store trust-store]} (self-signed)]
    (with-server [s hello {:key-store key-store :key-password pw}]
      (is (= "https://127.0.0.1:" (subs (server/base-address s) 0 18)))
      (let [c (t/new-client {:ssl-context (trusting trust-store)})]
        (is (= "hi" (:body (t/send! :get (server/url s "/hello") :client c)))))
      ;; 明文连 TLS 端口 ⇒ 失败
      (is (instance? IOException (thrown #(t/send! :get (str "http://127.0.0.1:" (server/port s) "/hello") :timeout-ms 5000)))))))

(deftest certificate-without-private-key-throws
  (let [{:keys [trust-store]} (self-signed)
        e (thrown #(server/start hello {:key-store trust-store :key-password pw}))]
    (is (= "server/certificate-no-private-key" (err/code e)))))

(deftest ssl-context-option
  (let [{:keys [key-store trust-store]} (self-signed)
        kmf (doto (javax.net.ssl.KeyManagerFactory/getInstance (javax.net.ssl.KeyManagerFactory/getDefaultAlgorithm))
              (.init key-store (.toCharArray ^String pw)))
        ctx (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) nil nil))]
    (with-server [s hello {:ssl-context ctx}]
      (is (= "hi" (:body (t/send! :get (server/url s "/hello") :client (t/new-client {:ssl-context (trusting trust-store)}))))))))

(deftest body-limit-is-413
  (with-server [s [["POST" "/echo" (fn [req] (http/response 200 (str (count (http/read-body-string req)))))]]
                {:max-request-body-size 16}]
    (is (= "16" (:body (t/send! :post (server/url s "/echo") :body (apply str (repeat 16 "a"))))))
    (is (= 413 (:status (t/send! :post (server/url s "/echo") :body (apply str (repeat 17 "a"))))))))

(deftest read-body-limit-is-413-when-uncaught
  (with-server [s [["POST" "/echo" (fn [req] (http/response 200 (http/read-body-string req 4)))]] nil]
    (is (= "abcd" (:body (t/send! :post (server/url s "/echo") :body "abcd"))))
    (is (= 413 (:status (t/send! :post (server/url s "/echo") :body "abcde"))))
    (is (= "server/body-too-large" (err/code (http/body-too-large 4))))))

(defn- socket-path ^String []
  (str (System/getProperty "java.io.tmpdir") "/ks-" (subs (str (java.util.UUID/randomUUID)) 0 8) ".sock"))

(deftest unix-socket
  (let [path (socket-path)]
    (with-server [s hello {:unix-socket-path path}]
      (is (= path (server/unix-socket-path s)))
      (is (zero? (server/port s)))
      (is (= "http://localhost/" (server/base-address s)))
      (is (= {:status 200 :body "hi"} (t/unix-request path "/hello")))
      ;; 有人在服务 ⇒ 第二台不抢
      (is (= "server/unix-socket-in-use" (err/code (thrown #(server/start hello {:unix-socket-path path})))))
      (is (.exists (File. path))))
    (is (not (.exists (File. path))) "停机后收拾掉")))

(deftest unix-socket-stale-file-is-replaced
  (let [path (socket-path)]
    ;; 残留：绑过但没人 listen 的套接字文件
    (with-open [stale (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
                        (.bind (java.net.UnixDomainSocketAddress/of path)))]
      (is (.exists (File. path)))
      (.close stale)
      (is (.exists (File. path)) "关了也不删文件 ⇒ 残留")
      (with-server [s hello {:unix-socket-path path}]
        (is (= "hi" (:body (t/unix-request path "/hello"))))))))

(deftest header-limit-is-431
  (with-server [s hello {:max-request-headers-total-size 1024}]
    (is (= 431 (:status (t/send! :get (server/url s "/hello") :headers {"X-Big" (apply str (repeat 2048 "x"))}))))
    (is (= 200 (:status (t/send! :get (server/url s "/hello")))))))

(deftest request-line-limit-is-414
  (with-server [s hello {:max-request-line-size 64}]
    (is (= 414 (:status (t/send! :get (server/url s (str "/hello?q=" (apply str (repeat 80 "a"))))))))
    (is (= 200 (:status (t/send! :get (server/url s "/hello")))))))
