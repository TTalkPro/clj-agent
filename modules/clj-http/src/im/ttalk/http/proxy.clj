(ns im.ttalk.http.proxy
  "代理选择（与客户端无关）。移植自 Keelson `jvm/modules/clj-net/src/im/ttalk/net/proxy.clj`（5c745869），逻辑未改。

   一个「代理」是下面三样之一，`select` 都收：
   - `(from-environment)` / `(from-env m)`：环境变量那一档（同 C# HttpClient.DefaultProxy、curl）：
     http_proxy / HTTP_PROXY（CGI 环境下不读大写的，防 httpoxy）、https_proxy / HTTPS_PROXY、all_proxy / ALL_PROXY 兜底；
     no_proxy / NO_PROXY：逗号分隔，* = 全部绕过，.a.com / a.com 都匹配 a.com 本身与子域。一个都没设 ⇒ nil。
   - `(web-proxy \"http://h:p\" {:bypass [正则…] :bypass-on-local? b})`：固定 HTTP 代理 + 绕过表：绕过表的正则
     对主机名、scheme://host、scheme://host:port 任一整串匹配（大小写不敏感）即绕过；bypass-on-local? ⇒ 不带点的主机名与回环也绕过。
   - 任何 java.net.ProxySelector。
   `->proxy-selector` 把前两种变成 ProxySelector。平常不直接用本 ns：`(im.ttalk.http/client {:proxy :env})` 即可。"
  (:require [clojure.string :as str])
  (:import (java.net InetSocketAddress Proxy Proxy$Type ProxySelector URI)
           (java.util Locale)
           (java.util.regex Pattern)))

(set! *warn-on-reflection* true)

(defn- lower ^String [^String s] (if s (.toLowerCase s Locale/ROOT) ""))

(defn- strip-brackets ^String [^String h]
  (if (and (str/starts-with? h "[") (str/ends-with? h "]")) (subs h 1 (dec (count h))) h))

(defn loopback-host?
  "keel %loopback-host?：localhost、::1、[::1]、127. 开头的点分地址（只看字面，⛔ 不解析）。"
  [^String host]
  (when (nil? host) (throw (IllegalArgumentException. "host")))
  (boolean
   (or (.equalsIgnoreCase host "localhost") (= host "::1") (= host "[::1]")
       (and (> (count host) 4) (str/starts-with? host "127.")
            (every? #(or (Character/isDigit (char %)) (= % \.)) (subs host 4))
            (every? #(< (int %) 0x80) host)))))

;; ── 环境变量 ───────────────────────────────────────────

(defn- first-var [env a b]
  (let [v (get env a)
        v (if (str/blank? v) (get env b) v)]
    (when-not (str/blank? v) (str/trim v))))

;; 「host:port」也收（补 http://）；只认 http 代理（同 .NET：https:// 的代理地址当 http 用）
(defn- parse-proxy-uri ^URI [^String v]
  (when v
    (try
      (let [u (URI/create (if (str/includes? v "://") v (str "http://" v)))
            h (.getHost u)]
        (when (and h (#{"http" "https"} (lower (.getScheme u))))
          (URI/create (str "http://" (if (and (str/includes? h ":") (not (str/starts-with? h "["))) (str "[" h "]") h)
                           ":" (if (>= (.getPort u) 0) (.getPort u) 80)))))
      (catch IllegalArgumentException _ nil))))

(defn from-env
  "读给定的环境表（string ⇒ string）；没有任何代理变量 ⇒ nil。"
  [env]
  (let [cgi (contains? env "GATEWAY_INTERFACE")
        all (parse-proxy-uri (first-var env "all_proxy" "ALL_PROXY"))
        http (or (parse-proxy-uri (if cgi (let [v (get env "http_proxy")] (when-not (str/blank? v) (str/trim v)))
                                      (first-var env "http_proxy" "HTTP_PROXY")))
                 all)
        https (or (parse-proxy-uri (first-var env "https_proxy" "HTTPS_PROXY")) all)]
    (when (or http https)
      (let [entries (->> (str/split (or (first-var env "no_proxy" "NO_PROXY") "") #",")
                         (map #(lower (str/trim %)))
                         (remove empty?))]
        {::kind :env
         :http http
         :https https
         :bypass (mapv #(if (str/starts-with? % ".") (subs % 1) %) entries)
         :bypass-all? (boolean (some #{"*"} entries))}))))

(defn from-environment
  "读进程环境（≙ C# HttpClient.DefaultProxy）；没有任何代理变量 ⇒ nil。"
  []
  (from-env (into {} (System/getenv))))

;; ── 固定代理 ───────────────────────────────────────────

(defn web-proxy
  "固定的 HTTP 代理（`address`：URI 或串，如 http://127.0.0.1:9）。`:bypass`：正则串表；`:bypass-on-local?`。"
  ([address] (web-proxy address nil))
  ([address {:keys [bypass bypass-on-local?]}]
   (when (nil? address) (throw (IllegalArgumentException. "address")))
   {::kind :web
    :address (if (instance? URI address) address (URI/create (str address)))
    :bypass (mapv #(Pattern/compile % Pattern/CASE_INSENSITIVE) bypass)
    :bypass-on-local? (boolean bypass-on-local?)}))

;; ── 选择 ──────────────────────────────────────────────

(defn bypassed?
  "目标绕过这个代理？（只对 from-env / web-proxy 的 map 有意义）"
  [p ^URI target]
  (case (::kind p)
    :env (or (:bypass-all? p)
             (let [host (strip-brackets (lower (.getHost target)))]
               (boolean (some (fn [b] (let [bb (strip-brackets b)] (or (= host bb) (str/ends-with? host (str "." bb)))))
                              (:bypass p)))))
    :web (let [host (or (.getHost target) "")
               scheme (or (.getScheme target) "")]
           (or (and (:bypass-on-local? p) (or (not (str/includes? host ".")) (loopback-host? host)))
               (let [with-scheme (str scheme "://" host)
                     port (cond (>= (.getPort target) 0) (.getPort target)
                                (#{"https" "wss"} (lower scheme)) 443
                                :else 80)
                     with-port (str with-scheme ":" port)]
                 (boolean (some (fn [^Pattern re] (some #(.matches (.matcher re ^String %)) [host with-scheme with-port]))
                                (:bypass p))))))
    false))

(defn- http-proxy ^Proxy [^String host port]
  (Proxy. Proxy$Type/HTTP (InetSocketAddress/createUnresolved host (int port))))

(defn select
  "目标 `uri` 该走的代理：java.net.Proxy 表（直连 = [Proxy/NO_PROXY]）。`p` 是 map 或 ProxySelector。"
  [p ^URI uri]
  (cond
    (instance? ProxySelector p) (vec (.select ^ProxySelector p uri))
    (bypassed? p uri) [Proxy/NO_PROXY]
    (= :env (::kind p))
    (let [^URI target (if (#{"https" "wss"} (lower (.getScheme uri))) (:https p) (:http p))]
      (if target [(http-proxy (strip-brackets (.getHost target)) (.getPort target))] [Proxy/NO_PROXY]))
    (= :web (::kind p))
    (let [^URI a (:address p)]
      [(http-proxy (.getHost a) (if (>= (.getPort a) 0) (.getPort a) 80))])
    :else (throw (IllegalArgumentException. (str "not a proxy: " p)))))

(defn ->proxy-selector
  "map 代理 ⇒ java.net.ProxySelector（已经是 ⇒ 原样；nil ⇒ nil）。"
  ^ProxySelector [p]
  (cond
    (nil? p) nil
    (instance? ProxySelector p) p
    :else (proxy [ProxySelector] []
            (select [uri] (java.util.ArrayList. ^java.util.Collection (select p uri)))
            (connectFailed [_ _ _]))))
