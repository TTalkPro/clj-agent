(ns im.ttalk.agent.tools.web-test
  "本地 JDK HttpServer，不出网。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools :as tools]
            [im.ttalk.agent.tools.test-util :as u :refer [ok err]]
            [im.ttalk.agent.tools.web :as web])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- serve!
  "routes: {path [status content-type body-string-or-bytes extra-headers]}"
  [routes]
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              [status ctype body headers] (get routes (.getPath (.getRequestURI ex)) [404 "text/plain" "nope" {}])
                              ^bytes bs (if (bytes? body) body (.getBytes (str body) "UTF-8"))]
                          (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
                          (when ctype (.add (.getResponseHeaders ex) "Content-Type" ctype))
                          (.sendResponseHeaders ex status (if (zero? (alength bs)) -1 (alength bs)))
                          (with-open [os (.getResponseBody ex)] (.write os bs))))))
    (.start srv)
    srv))

(defn- base [^HttpServer s] (str "http://127.0.0.1:" (.getPort (.getAddress s))))

(deftest html-to-text
  (let [t (web/html->text "<html><head><title>x</title><style>.a{}</style></head><body><nav>menu</nav><main><h1>Title</h1><script>evil()</script><p>Hello &amp; <a href=\"https://e.com\">link</a></p><ul><li>one</li><li>two</li></ul></main></body></html>")]
    (is (= "# Title\n\nHello & [link](https://e.com)\n\n- one\n- two" t))))

(deftest web-fetch
  (let [root (u/tmp-dir)
        srv (serve! {"/page" [200 "text/html; charset=utf-8" "<body><h2>标题</h2><p>正文</p><script>x()</script></body>" {}]
                     "/json" [200 "application/json" "{\"a\":1}" {}]
                     "/img" [200 "image/png" (byte-array [1 2 3]) {}]
                     "/big" [200 "text/plain" (apply str (repeat 5000 "x")) {}]
                     "/hop" [302 nil "" {"Location" "/page"}]
                     "/evil-hop" [302 nil "" {"Location" "http://blocked.example/"}]
                     "/loop" [302 nil "" {"Location" "/loop"}]
                     "/err" [500 "text/plain" "boom" {}]})
        b (base srv)
        mk (fn [o] (tools/workspace-tools {:root (str root) :web-fetch o} {:groups #{:web-fetch}}))
        ts (mk {:allow-url (fn [^java.net.URI u] (not= "blocked.example" (.getHost u)))})]
    (try
      (testing "HTML ⇒ 文本，头里写清来源与方式"
        (let [r (ok ts "web_fetch" {:url (str b "/page")})]
          (is (str/starts-with? r (str "已抓取 " b "/page（text/html，")))
          (is (str/includes? r "## 标题\n\n正文"))
          (is (not (str/includes? r "x()")))))
      (testing "其它文本原样；二进制拒"
        (is (str/ends-with? (ok ts "web_fetch" {:url (str b "/json")}) "{\"a\":1}"))
        (is (re-find #"不是文本" (err ts "web_fetch" {:url (str b "/img")}))))
      (testing "max_chars 截断并注明"
        (is (str/includes? (ok ts "web_fetch" {:url (str b "/big") :max_chars 10}) "[已截断：只给了前 10 个字符，共 5000 个")))
      (testing "重定向：手动跟随，每跳都过 :allow-url"
        (is (str/includes? (ok ts "web_fetch" {:url (str b "/hop")}) "正文"))
        (is (re-find #"网络策略不允许" (err ts "web_fetch" {:url (str b "/evil-hop")})))
        (is (re-find #"重定向超过 5 次" (err ts "web_fetch" {:url (str b "/loop")}))))
      (testing "非 2xx ⇒ 失败带状态与开头"
        (is (re-find #"HTTP 500。响应开头：boom" (err ts "web_fetch" {:url (str b "/err")}))))
      (testing "体积上限按实读字节"
        (is (re-find #"超过 100 字节" (err (mk {:max-body-bytes 100}) "web_fetch" {:url (str b "/big")}))))
      (testing "URL 校验"
        (is (re-find #"http:// 或 https://" (err ts "web_fetch" {:url "file:///etc/passwd"})))
        (is (re-find #"http:// 或 https://" (err ts "web_fetch" {:url "not a url"}))))
      (testing "缺省敏感；可按实例关"
        (is (true? (:tool/sensitive (meta (first ts)))))
        (is (false? (:tool/sensitive (meta (first (mk {:sensitive false}))))))
        (is (true? (:tool/sensitive (meta #'web/web_fetch))) "deftool 原件不受实例覆盖影响"))
      (finally
        (.stop srv 0)
        (u/delete-tree! root)))))

;; H-05（designs/http-module.md §5）：IO 失败按网络错分类给文案；:proxy 选项接进 client
(deftest web-fetch-network-failures-are-classified
  (let [root (u/tmp-dir)
        ts (tools/workspace-tools {:root (str root) :web-fetch {:timeout-ms 5000}} {:groups #{:web-fetch}})
        port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
    (is (re-find #"域名解析不了" (err ts "web_fetch" {:url "http://no-such-host.invalid/"})))
    (is (re-find #"连不上对方" (err ts "web_fetch" {:url (str "http://127.0.0.1:" port "/")})))))

(deftest web-fetch-proxy-option-reaches-the-client
  (let [root (u/tmp-dir)
        ;; 黑洞代理：127.0.0.1:<关着的端口>——走了代理就连不上，证明 :proxy 生效
        port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
        srv (serve! {"/p" [200 "text/plain" "direct" {}]})
        b (base srv)
        direct (tools/workspace-tools {:root (str root)} {:groups #{:web-fetch}})
        proxied (tools/workspace-tools {:root (str root) :web-fetch {:proxy (str "http://10.255.255.1:" port)
                                                                    :timeout-ms 1500}}
                                       {:groups #{:web-fetch}})]
    (try
      (is (str/ends-with? (ok direct "web_fetch" {:url (str b "/p")}) "direct"))
      ;; 回环目标不绕过 web-proxy（未设 :bypass-on-local?）⇒ 必然走代理 ⇒ 失败
      (is (some? (err proxied "web_fetch" {:url (str b "/p")})))
      (finally (.stop srv 0)))))
