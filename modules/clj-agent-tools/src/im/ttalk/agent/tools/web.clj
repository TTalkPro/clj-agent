(ns im.ttalk.agent.tools.web
  "web_fetch（T-06；出处 Keelson `WebFetchTool.cs`，借鉴 coxswain `fetch.ss`）。

   它挡的是「模型要看一页文档只能 exec curl ⇒ 回来整页 HTML，几十 KB 的 <script> 把输出上限吃光、正文一个字都没到」
   那一类：抓 → 认 Content-Type → HTML 简化成文本，一步到位。传输走 clj-http（零依赖，JDK `java.net.http`）。

   - **手动**跟随重定向（≤ :max-redirects），每一跳都过 `:allow-url`（宿主的网络策略钩子）；
   - 体积上限数**实际读到的**字节（⛔ 信 Content-Length）；
   - 缺省 Sensitive（它不改盘，但它出网：URL 本身就是一条出口），宿主可 `{:web-fetch {:sensitive false}}` 关掉。

   选项（工作区 `:web-fetch`）：:allow-url (fn [^java.net.URI uri] bool)、:timeout-ms（30000）、
   :default-max-chars（40000）、:max-chars（200000）、:max-body-bytes（5 MiB）、:max-redirects（5）、:user-agent、
   :client（自带 java.net.http.HttpClient；须不自动跟随重定向，否则中间跳转不过 :allow-url）、
   :proxy（没给 :client 时用：`:env` 读 https_proxy / no_proxy …、代理地址串、或 ProxySelector；缺省直连）。"
  (:require [clojure.string :as str]
            [im.ttalk.http :as http]
            [im.ttalk.http.failure :as failure]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpTimeoutException]
           [java.nio.charset Charset StandardCharsets]))

(set! *warn-on-reflection* true)

(def defaults
  {:timeout-ms 30000 :default-max-chars 40000 :max-chars 200000
   :max-body-bytes (* 5 1024 1024) :max-redirects 5 :sensitive true})

(defn opts-of [w] (merge defaults (:web-fetch w)))

;;; ============================================================
;;; HTML → 文本
;;; ============================================================

(def ^:private entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " " "#39" "'"
   "mdash" "—" "ndash" "–" "hellip" "…" "copy" "©" "reg" "®" "laquo" "«" "raquo" "»"})

(defn decode-entities [^String s]
  (str/replace s #"&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);"
               (fn [[m e]]
                 (cond
                   (str/starts-with? e "#x") (try (String. (Character/toChars (Integer/parseInt (subs e 2) 16))) (catch Exception _ m))
                   (str/starts-with? e "#") (try (String. (Character/toChars (Integer/parseInt (subs e 1)))) (catch Exception _ m))
                   :else (get entities e m)))))

(defn html->text
  "HTML 简化成近似 Markdown 的文本：丢掉 script/style/noscript/svg/head/template 与注释；
   有 <main> / <article> 只取它；标题加 #、列表项加 - 、链接写成 [文本](地址)、块级元素换行；解实体、压空白。"
  [^String html]
  (let [strip (fn [s tag] (str/replace s (re-pattern (str "(?is)<" tag "\\b[^>]*>.*?</" tag "\\s*>")) " "))
        s (str/replace html #"(?s)<!--.*?-->" " ")
        s (reduce strip s ["script" "style" "noscript" "svg" "head" "template" "iframe"])
        s (or (some (fn [tag] (second (re-find (re-pattern (str "(?is)<" tag "\\b[^>]*>(.*)</" tag "\\s*>")) s)))
                    ["main" "article"])
              (second (re-find #"(?is)<body\b[^>]*>(.*)</body\s*>" s))
              s)
        s (str/replace s #"(?is)<h([1-6])\b[^>]*>(.*?)</h\1\s*>"
                       (fn [[_ n inner]] (str "\n\n" (apply str (repeat (parse-long n) "#")) " " inner "\n\n")))
        s (str/replace s #"(?is)<a\b[^>]*href\s*=\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a\s*>"
                       (fn [[_ href inner]]
                         (let [t (str/trim (str/replace inner #"<[^>]+>" ""))]
                           (if (or (str/blank? t) (str/blank? href) (str/starts-with? href "#") (str/starts-with? href "javascript:"))
                             t
                             (str "[" t "](" href ")")))))
        s (str/replace s #"(?i)<li\b[^>]*>" "\n- ")
        s (str/replace s #"(?i)<br\s*/?>" "\n")
        s (str/replace s #"(?i)</?(p|div|section|ul|ol|table|tr|blockquote|pre|header|footer|nav|aside|figure|dl|dt|dd|hr)\b[^>]*>" "\n")
        s (str/replace s #"(?i)</?(td|th)\b[^>]*>" " | ")
        s (str/replace s #"<[^>]+>" "")
        s (decode-entities s)
        s (str/replace s #"[ \t\x0B\f\r\u00A0]+" " ")
        s (str/replace s #" *\n *" "\n")
        s (str/replace s #"\n{3,}" "\n\n")]
    (str/trim s)))

;;; ============================================================
;;; 抓取
;;; ============================================================

(defn- http-uri ^URI [url]
  (try
    (let [u (URI. (str/trim (str url)))]
      (when (and (#{"http" "https"} (some-> (.getScheme u) str/lower-case))
                 (not (str/blank? (.getHost u))))
        u))
    (catch Exception _ nil)))

(defn- textual? [^String mime ^bytes body]
  (if (= "" mime)
    (not (some zero? body))
    (or (str/starts-with? mime "text/")
        (#{"application/json" "application/xml" "application/javascript" "application/x-javascript"
           "application/ecmascript" "application/x-ndjson" "application/yaml" "application/x-yaml" "application/toml"} mime)
        (str/ends-with? mime "+json") (str/ends-with? mime "+xml"))))

(defn- decode-body ^String [^bytes body charset ^String mime]
  (let [cs (or (when charset (try (Charset/forName charset) (catch Exception _ nil)))
               (when (str/includes? mime "html")
                 (when-let [m (re-find #"(?i)<meta[^>]+charset\s*=\s*[\"']?([\w-]+)" (String. body 0 (min 2048 (alength body)) StandardCharsets/ISO_8859_1))]
                   (try (Charset/forName (second m)) (catch Exception _ nil))))
               StandardCharsets/UTF_8)]
    (String. body ^Charset cs)))

(defn- read-limited
  "读响应体，超过上限 ⇒ nil。"
  [^InputStream in max-bytes]
  (with-open [in in]
    (let [out (ByteArrayOutputStream.) buf (byte-array 16384)]
      (loop []
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toByteArray out)
            (> (+ (.size out) n) (long max-bytes)) nil
            :else (do (.write out buf 0 n) (recur))))))))

(defn- client-of ^HttpClient [w o]
  (or (:client o)
      (let [state (:state w)]
        (or (::client @state)
            (::client (swap! state update ::client
                             #(or % (http/client {:redirect :never
                                                  :connect-timeout-ms (:timeout-ms o)
                                                  :proxy (:proxy o)}))))))))

(defn- io-failure-text
  "IO 失败的文案按 `im.ttalk.http.failure/classify` 细分——模型据此决定换地址还是重试。"
  [uri ^Throwable e]
  (case (failure/classify e)
    :name-resolution-error (format "抓 %s 失败：域名解析不了（主机名写错了，或这台机器上不了网）。" uri)
    :connection-error (format "抓 %s 失败：连不上对方（拒绝连接、不可达或建连超时）。" uri)
    :secure-connection-error (format "抓 %s 失败：TLS 握手失败（证书或协议问题）：%s" uri (or (.getMessage e) (class e)))
    (format "抓 %s 失败：%s" uri (or (.getMessage e) (class e)))))

(defn web-fetch*
  [w {:keys [url max_chars]}]
  (let [o (opts-of w)
        uri0 (or (http-uri url) (fail! "tools/web-fetch-bad-url" (str "URL 必须是完整的 http:// 或 https:// 地址并带主机名：" url)))
        max-chars (long (or max_chars (:default-max-chars o)))
        _ (when (<= max-chars 0) (fail! "tools/web-fetch-max-chars" (format "max_chars 必须是正整数（最多 %d）。" (:max-chars o))))
        max-chars (min max-chars (long (:max-chars o)))
        deadline (+ (System/currentTimeMillis) (long (:timeout-ms o)))
        client (client-of w o)]
    (loop [^URI uri uri0 hop 0]
      (when-let [allow (:allow-url o)]
        (when-not (allow uri)
          (fail! "tools/web-fetch-denied" (format "网络策略不允许访问 %s。换一个允许的地址，或请用户放开这个域名。" uri))))
      (let [remaining (- deadline (System/currentTimeMillis))
            _ (when (<= remaining 0)
                (fail! "tools/web-fetch-timeout" (format "抓 %s 超时（%d 秒）。" uri (quot (long (:timeout-ms o)) 1000))))
            resp (try (http/request {:url uri
                                     :timeout-ms remaining
                                     :headers (cond-> {"Accept" "text/html;q=1.0, application/xhtml+xml;q=0.9, text/markdown;q=0.9, text/plain;q=0.8, */*;q=0.1"}
                                                (:user-agent o) (assoc "User-Agent" (str (:user-agent o))))
                                     :as :input-stream
                                     :client client})
                      (catch HttpTimeoutException _
                        (fail! "tools/web-fetch-timeout" (format "抓 %s 超时（%d 秒）。" uri (quot (long (:timeout-ms o)) 1000))))
                      (catch java.io.IOException e
                        (fail! "tools/web-fetch-failed" (io-failure-text uri e)))
                      (catch IllegalArgumentException e
                        (fail! "tools/web-fetch-failed" (format "抓 %s 失败：%s" uri (.getMessage e)))))
            status (:status resp)
            ^InputStream body-in (:body resp)
            location (http/header resp "location")]
        (if (and (<= 300 status 399) location)
          (do (.close body-in)
              (when (>= hop (long (:max-redirects o)))
                (fail! "tools/web-fetch-too-many-redirects" (format "%s 重定向超过 %d 次，已停止。" url (:max-redirects o))))
              (let [next-uri (http-uri (str (.resolve uri ^String location)))]
                (when-not next-uri (fail! "tools/web-fetch-bad-url" (str "重定向到了不支持的地址：" location)))
                (recur next-uri (inc hop))))
          (let [declared (http/header resp "content-length")
                _ (when (and declared (> (or (parse-long (str/trim declared)) 0) (long (:max-body-bytes o))))
                    (.close body-in)
                    (fail! "tools/web-fetch-too-large" (format "%s 的响应超过 %d 字节，没有读。" uri (:max-body-bytes o))))
                body (or (read-limited body-in (:max-body-bytes o))
                         (fail! "tools/web-fetch-too-large" (format "%s 的响应超过 %d 字节，没有读。" uri (:max-body-bytes o))))
                ctype (or (http/header resp "content-type") "")
                mime (str/lower-case (str/trim (first (str/split ctype #";"))))
                charset (second (re-find #"(?i)charset\s*=\s*\"?([\w-]+)" ctype))]
            (if-not (<= 200 status 299)
              (let [snippet (if (textual? mime body) (decode-body body charset mime) "")]
                (fail! "tools/web-fetch-http-status"
                       (format "%s 回了 HTTP %d。响应开头：%s" uri status (subs snippet 0 (min 500 (count snippet))))))
              (let [[text how] (cond
                                 (#{"text/html" "application/xhtml+xml"} mime) [(html->text (decode-body body charset mime)) "HTML → 文本"]
                                 (textual? mime body) [(decode-body body charset mime) "原样"]
                                 :else (fail! "tools/web-fetch-not-text" (format "%s 的内容是 %s，不是文本，web_fetch 不收。" uri mime)))
                    total (count text)
                    text (if (> total max-chars)
                           (str (subs text 0 max-chars) "\n\n" (format "[已截断：只给了前 %d 个字符，共 %d 个；要更多就加大 max_chars]" max-chars total))
                           text)]
                (str (format "已抓取 %s（%s，%d 字符，%s）" uri (if (= "" mime) "?" mime) total how) "\n\n" text)))))))))

(deftool web_fetch
  "抓一个 URL 的内容给你读：HTML 会抽出正文简化成文本（保留标题、列表与链接），其它文本（Markdown、JSON、纯文本）原样给；图片与二进制不收。URL 必须是完整的 http:// 或 https://。正文超过 max_chars 会截断。看网页优先用这件工具，不要用 exec 跑 curl。"
  [[url :string "完整的 URL，以 http:// 或 https:// 开头"]
   [max_chars :int "最多返回多少字符的正文（缺省 40000）" :default nil]]
  {:sensitive true :tags [:web]}
  (web-fetch* (ws/current) {:url url :max_chars max_chars}))
