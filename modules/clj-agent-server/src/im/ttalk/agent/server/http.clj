(ns im.ttalk.agent.server.http
  "Ring 请求 / 响应的小工具（C# 里 ASP.NET 的 HttpContext 那一侧）。**只认 Ring 规范**（请求 map / 响应 map），不认识任何具体的
   服务端 —— 同一个处理器挂在 Jetty（im.ttalk.agent.server.jetty）或别的 Ring 服务端（http-kit …）上都行。

   请求就是 Ring 的请求 map（:request-method :uri :query-string :headers（小写名）:body）。本模块另认几个键：
     :path-params                  路由参数 {:id \"…\"}（已解码；im.ttalk.agent.server.routing 填）
     :im.ttalk.agent.server/body-limit   请求体上限（`wrap-body-limit` 填；`read-body` 守它）
     :im.ttalk.agent.server/stopping     服务端停机时完成的 CompletableFuture（宿主填；没有 = 宿主不报停机）
     :im.ttalk.agent.server/open-streams 宿主的「开着的长连接」表（SSE / WebSocket 登记进去，停机时逐个中止）

   响应就是 Ring 的响应 map；`response` / `json-response` / `text-response` 造它。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.server.strings :as err]
            [cheshire.core :as json]
            [im.ttalk.agent.server.strings :refer [msg]])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def default-max-body-bytes
  "`read-body` 的缺省上限：16 MiB（C# RequestBody.ReadAsStringAsync 同值）。"
  (* 16 1024 1024))

;; ── 解码 ─────────────────────────────────────────────

(defn decode-component
  "百分号解码（UTF-8）。`plus-space?` ⇒ + 当空格（查询串）；路径段里 + 是字面量。坏的 % 序列 ⇒ 原样返回。"
  ^String [^String s plus-space?]
  (try
    (URLDecoder/decode (if plus-space? s (str/replace s "+" "%2B")) StandardCharsets/UTF_8)
    (catch IllegalArgumentException _ s)))

(defn split-path
  "原样路径 ⇒ 解码后的段（空段去掉；%2F 解出来的 / 留在段里，不另切）。"
  [^String raw-path]
  (into [] (comp (remove empty?) (map #(decode-component % false))) (str/split (or raw-path "") #"/")))

(defn parse-query
  "原样查询串 ⇒ {名 [值 …]}（同名多值按出现顺序；没有 = 的项值为空串）。"
  [^String raw]
  (if (str/blank? raw)
    {}
    (reduce (fn [m ^String kv]
              (if (empty? kv)
                m
                (let [i (.indexOf kv "=")
                      k (decode-component (if (neg? i) kv (subs kv 0 i)) true)
                      v (if (neg? i) "" (decode-component (subs kv (inc i)) true))]
                  (update m k (fnil conj []) v))))
            {} (str/split raw #"&"))))

;; ── 请求 ─────────────────────────────────────────────

(defn method
  "大写方法名（\"GET\"）。"
  ^String [req]
  (str/upper-case (name (:request-method req))))

(defn path
  "解码后的路径（C# 的 PathBase + Path；访问守卫的 :protected 谓词收它）。"
  ^String [req]
  (str "/" (str/join "/" (split-path (:uri req)))))

(defn header
  "请求头（名字大小写不敏感）；Ring 把同名多个头用逗号拼成一个串。没有 ⇒ nil。"
  ^String [req ^String name]
  (get (:headers req) (str/lower-case name)))

(defn header-values
  "请求头按逗号拆开的各个值（Ring 已把同名多个头拼在一起）。"
  [req ^String name]
  (if-let [v (header req name)]
    (into [] (comp (map str/trim) (remove empty?)) (str/split v #","))
    []))

(defn query
  "{名 [值 …]}。"
  [req]
  (parse-query (:query-string req)))

(defn query-values [req name] (get (query req) name []))

(defn query-param [req name] (first (query-values req name)))

(defn param
  "路由参数（`:id` 之类）。"
  [req k]
  (get (:path-params req) k))

(defn head? [req] (= :head (:request-method req)))

;; ── 响应 ─────────────────────────────────────────────

(defn response
  "Ring 响应 map。"
  ([status] (response status nil nil))
  ([status body] (response status nil body))
  ([status headers body] {:status status :headers (or headers {}) :body body}))

(defn text-response
  "纯文本（text/plain; charset=utf-8）。"
  [status ^String s]
  (response status {"Content-Type" "text/plain; charset=utf-8"} s))

(defn json-response
  "JSON（application/json; charset=utf-8；cheshire 编码：紧凑）。已经编好的串原样发。"
  ([status data] (json-response status nil data))
  ([status headers data]
   (response status (merge {"Content-Type" "application/json; charset=utf-8"} headers)
             (if (string? data) data (json/generate-string data)))))

(defn with-header
  "给响应加 / 换一个头。"
  [resp k v]
  (assoc-in resp [:headers k] v))

;; ── 请求体 ───────────────────────────────────────────

(defn body-too-large
  "请求体超限的 ex-info（`server/body-too-large`；`wrap-body-limit` / `wrap-errors` 把没接住的它答成 413）。"
  [limit]
  (err/error (msg :server/body-too-large limit) {:im.ttalk.agent.server/kind :invalid-data :im.ttalk.agent.server/limit limit}))

(defn read-body
  "把请求体整个读成字节（上限 `max-bytes`，缺省 16 MiB；请求带着 `wrap-body-limit` 的上限时取两者小的）。
   超了抛 `server/body-too-large`（多读一个字节就判，⛔ 不是先整个读进来再量）。没有请求体 ⇒ 空字节。"
  (^bytes [req] (read-body req default-max-body-bytes))
  (^bytes [req max-bytes]
   (let [limit (long (if-let [l (:im.ttalk.agent.server/body-limit req)] (min (long l) (long max-bytes)) max-bytes))
         body (:body req)]
     (cond
       (nil? body) (byte-array 0)
       (string? body) (let [b (.getBytes ^String body StandardCharsets/UTF_8)]
                        (when (> (alength b) limit) (throw (body-too-large limit)))
                        b)
       :else
       (let [^InputStream in body
             out (ByteArrayOutputStream.)
             buf (byte-array 8192)]
         (loop [total 0]
           (let [n (.read in buf)]
             (if (neg? n)
               (.toByteArray out)
               (let [total (+ total n)]
                 (when (> total limit) (throw (body-too-large limit)))
                 (.write out buf 0 n)
                 (recur total))))))))))

(defn read-body-string
  "同 `read-body`，按 UTF-8 解成串（C# RequestBody.ReadAsStringAsync）。"
  (^String [req] (read-body-string req default-max-body-bytes))
  (^String [req max-bytes] (String. (read-body req max-bytes) StandardCharsets/UTF_8)))

;; ── 中间件 ───────────────────────────────────────────

(defn- sync-and-async
  "由同步的 (fn [req] resp) 造出 Ring 的两种元数（3 元：respond / raise）。"
  [f]
  (fn
    ([req] (f req))
    ([req respond raise]
     (try (respond (f req)) (catch Throwable t (raise t))))))

(defn wrap-errors
  "处理器抛了：`server/body-too-large` ⇒ 413；别的 ⇒ 500（正文为空，⛔ 不把异常文本吐给客户端）。"
  [handler]
  (sync-and-async
   (fn [req]
     (try (handler req)
          (catch Throwable t
            (response (if (= "server/body-too-large" (err/code t)) 413 500)))))))

(defn wrap-body-limit
  "请求体上限（C# KeelsonServerOptions.MaxRequestBodySize）：Content-Length 超了 ⇒ 413（不进处理器）；
   否则把上限放进请求（`read-body` 守它），处理器里读超了抛的 `server/body-too-large` 也答 413。"
  [handler limit]
  (if-not limit
    handler
    (sync-and-async
     (fn [req]
       (let [cl (or (:content-length req) (some-> (header req "Content-Length") str/trim parse-long))]
         (if (and cl (> (long cl) (long limit)))
           (response 413)
           (try (handler (assoc req :im.ttalk.agent.server/body-limit limit))
                (catch clojure.lang.ExceptionInfo e
                  (if (= "server/body-too-large" (err/code e)) (response 413) (throw e))))))))))
