(ns im.ttalk.agent.server.guard
  "本机访问守卫：token + Host 头（C# AccessGuard，N-05；语义同锦书 AccessGuard ≙ jinshu2 (jinshu httpd guard)）。纯判定 + 中间件。

   `wrap-access-guard` 是 Ring 中间件（不认识具体服务端）。选项（map；经 im.ttalk.agent.server.jetty 的 `:guard` 挂到最前面，或自己包）：
     :token               要求的 token；nil / 空串 = 不鉴权（只做 Host 校验，若开了的话）
     :loopback-host-only? Host 头只认回环（localhost、127.0.0.0/8、::1，带不带端口、IPv6 带方括号都认）—— 挡 DNS rebinding。
                          ⚠️ 要经局域网 IP / 域名访问的部署别开
     :protected           (fn [path] bool)，哪些路径要 token（参数是解码后的路径，如 \"/rpc\"）。缺省全部
     :preflight-exempt    (fn [req] bool)，免 token 的请求（在 token 之前、Host 之后判）。缺省：方法是 OPTIONS（CORS 预检）
     :query-name          query 里带 token 的参数名（EventSource 不能自定义头）。缺省 \"t\"；显式 nil = 不认这条带法
     :header-name         带 token 的请求头名。缺省 \"X-Token\"；显式 nil = 不认
     :accept-bearer?      认 `Authorization: Bearer <token>`（scheme 大小写不敏感）。缺省 true

   判定次序（`evaluate`）：① :loopback-host-only? ⇒ Host 不是回环 ⇒ 403（**所有**请求，含静态资源与预检 —— DNS rebinding 的请求
   带着对的 token 也拒）；② :preflight-exempt ⇒ 放行；③ token 非空且 :protected ⇒ 三种带法里有一种对得上才放行，否则 401。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.server.http :as http])
  (:import (java.net InetAddress)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(set! *warn-on-reflection* true)

;; ── 纯函数 ───────────────────────────────────────────

(defn- ipv4-literal? [^String s]
  (when-let [[_ & parts] (re-matches #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})" s)]
    (every? #(<= (parse-long %) 255) parts)))

(defn ip-literal
  "IP 字面量 ⇒ InetAddress（⛔ 不查 DNS）；不是 ⇒ nil。IPv6 不带方括号（可带 %zone）。"
  ^InetAddress [^String s]
  (let [s (str/trim (or s ""))]
    (cond
      (ipv4-literal? s) (InetAddress/getByName s)
      (and (str/includes? s ":") (re-matches #"[0-9A-Fa-f:.]+(%[0-9A-Za-z_.\-]+)?" s))
      (try (InetAddress/getByName s) (catch Exception _ nil))
      :else nil)))

(defn loopback-host?
  "主机名（已去端口）是不是回环：localhost（大小写不敏感）、127.0.0.0/8、::1 / [::1]。"
  [host]
  (let [h (str/trim (or host ""))]
    (cond
      (empty? h) false
      (.equalsIgnoreCase "localhost" h) true
      :else (let [h (if (and (str/starts-with? h "[") (str/ends-with? h "]") (> (count h) 1))
                      (subs h 1 (dec (count h)))
                      h)]
              (boolean (some-> (ip-literal h) (.isLoopbackAddress)))))))

(defn host-of-header
  "Host 头去端口：127.0.0.1:5173 → 127.0.0.1，[::1]:5173 → [::1]。
   ⚠️ IPv6 字面量是方括号包着的，按冒号切会把 ::1 切碎（回环被判成非回环）。"
  [raw]
  (let [h (str/trim (or raw ""))]
    (cond
      (empty? h) ""
      (str/starts-with? h "[") (let [close (.indexOf ^String h "]" 1)]
                                 (if (neg? close) h (subs h 0 (inc close))))
      :else (let [colon (.indexOf ^String h ":")]
              (if (neg? colon) h (subs h 0 colon))))))

(defn bearer-token
  "Authorization 头里的 Bearer token（scheme 大小写不敏感；⛔ 不认裸串与别的 scheme）。"
  [raw]
  (let [s (str/trim (or raw ""))]
    (when (and (> (count s) 7) (.equalsIgnoreCase "bearer " (subs s 0 7)))
      (let [t (str/trim (subs s 7))]
        (when (seq t) t)))))

(defn bind-needs-token?
  "绑定地址（IP 字面量或 localhost）不是回环而又没有 token ⇒ true（必须拒绝启动）。"
  [host token]
  (and (empty? token) (not (loopback-host? host))))

;; ── 判定 ─────────────────────────────────────────────

(defn- expected-bytes [{:keys [token]}]
  (when (seq token) (.getBytes ^String token StandardCharsets/UTF_8)))

(defn- matches?
  "定长比较（⚠️ 逐字节短路会漏出时序侧信道）。"
  [supplied ^bytes expected]
  (and (seq supplied)
       (MessageDigest/isEqual (.getBytes ^String supplied StandardCharsets/UTF_8) expected)))

(defn- has-token? [o req expected]
  (let [q (get o :query-name "t")
        h (get o :header-name "X-Token")
        oks (concat (when (seq q) (map #(matches? % expected) (http/query-values req q)))
                    (when (seq h) (map #(matches? % expected) (http/header-values req h)))
                    (when (get o :accept-bearer? true)
                      (map #(matches? (bearer-token %) expected) (http/header-values req "Authorization"))))]
    ;; 每一种都比完（⛔ 不 some 短路）
    (reduce #(or %1 %2) false (doall oks))))

(defn- default-exempt [req] (= :options (:request-method req)))

(defn- decide [o req expected]
  (cond
    (and (:loopback-host-only? o) (not (loopback-host? (host-of-header (http/header req "Host")))))
    :forbidden-host

    (or (nil? expected) ((or (:preflight-exempt o) default-exempt) req))
    :allowed

    (not ((or (:protected o) (constantly true)) (http/path req)))
    :allowed

    (has-token? o req expected) :allowed
    :else :unauthorized))

(defn evaluate
  "对一个请求下结论（不写响应）：`:allowed` / `:forbidden-host`（⇒ 403）/ `:unauthorized`（⇒ 401）。"
  [opts req]
  (decide opts req (expected-bytes opts)))

(defn wrap-access-guard
  "挂守卫中间件：403（Host 不是回环）/ 401（token 不对，带 `WWW-Authenticate: Bearer`），正文为空。
   既不鉴权也不校验 Host 时原样返回 handler。⚠️ 包在要守的端点外面（im.ttalk.agent.server.jetty 的 :guard 包在最外层）。
   Ring 的两种元数都认。"
  [handler opts]
  (let [expected (expected-bytes opts)
        verdict (fn [req]
                  (case (decide opts req expected)
                    :forbidden-host (http/response 403)
                    :unauthorized (http/response 401 (when (get opts :accept-bearer? true) {"WWW-Authenticate" "Bearer"}) nil)
                    nil))]
    (if (and (nil? expected) (not (:loopback-host-only? opts)))
      handler
      (fn
        ([req] (or (verdict req) (handler req)))
        ([req respond raise]
         (if-let [r (try (verdict req) (catch Throwable t (raise t) ::raised))]
           (when-not (= ::raised r) (respond r))
           (handler req respond raise)))))))
