(ns im.ttalk.agent.provider.http.client
  "HTTP 客户端工具模块（基于 http-kit）——**仅非流式**请求/响应。

   流式（SSE）已迁移到 `im.ttalk.agent.provider.http.stream-client`（java.net.http 真增量，
   见 design/streaming-async-design.md）；本模块不再提供流式 API。

   用法：
   (require '[im.ttalk.agent.provider.http.client :as http])
   (http/get  \"https://api.example.com/users\")
   (http/post \"https://api.example.com/users\" :body {:name \"张三\"})
   (http/post-async url callback :body {...})   ; 异步回调
   (http/request :get url :async? true :callback (fn [resp] ...))"
  (:refer-clojure :exclude [get])
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

;; ============================================================
;; 配置默认值
;; ============================================================

(def ^:dynamic *default-timeout*
  "默认超时时间（毫秒）"
  30000)

(def ^:dynamic *default-headers*
  "默认请求头"
  {"Accept" "application/json"
   "Content-Type" "application/json"})

;; ============================================================
;; URL 工具函数
;; ============================================================

(defn url-encode
  "URL 编码字符串

   示例:
   (url-encode \"hello world\") ; => \"hello+world\""
  [s]
  (when s
    (URLEncoder/encode (str s) "UTF-8")))

(defn build-query-string
  "将 map 转换为查询字符串

   示例:
   (build-query-string {:q \"test\" :page 1})
   ; => \"q=test&page=1\""
  [params]
  (when (seq params)
    (->> params
         (map (fn [[k v]]
                (str (url-encode (name k)) "=" (url-encode v))))
         (str/join "&"))))

(defn build-url
  "构建带查询参数的 URL

   示例:
   (build-url \"https://api.example.com/search\" {:q \"test\"})
   ; => \"https://api.example.com/search?q=test\""
  [base-url params]
  (if (seq params)
    (let [query (build-query-string params)
          separator (if (str/includes? base-url "?") "&" "?")]
      (str base-url separator query))
    base-url))

;; ============================================================
;; 响应处理
;; ============================================================

(defn request-id
  "从响应头提取请求 ID（用于排障 / 上报），大小写无关

   Anthropic 用 request-id，OpenAI 用 x-request-id。"
  [headers]
  (when headers
    (or (clojure.core/get headers :request-id)
        (clojure.core/get headers "request-id")
        (clojure.core/get headers :x-request-id)
        (clojure.core/get headers "x-request-id"))))

(defn- parse-response
  "解析 HTTP 响应

   根据 as 参数决定 body 解析方式：
   - :json       尝试 JSON 解析（默认）
   - :text/:stream/:byte-array  原样返回"
  [{:keys [status headers body error]} as]
  (if error
    {:status 0 :error (str error) :success? false}
    {:status status
     :headers headers
     :request-id (request-id headers)
     :body (if (= as :json)
             (if (string? body)
               (try (json/parse-string body true)
                    ;; 解析失败回退原始字符串（保留 body 供上层排查），但记 debug 不再完全无声
                    (catch Exception e
                      (log/debug "HTTP 响应体 JSON 解析失败，回退原始字符串"
                                 {:status status :error (.getMessage e)})
                      body))
               body)
             body)
     :success? (<= 200 status 299)}))

;; ============================================================
;; 核心请求函数
;; ============================================================

(defn request
  "发送 HTTP 请求

   参数:
   - method: 请求方法 (:get :post :put :patch :delete)
   - url: 请求 URL

   选项:
   - :async?       是否异步（默认 false）
   - :callback     异步回调 (fn [response])
   - :headers      请求头 map
   - :body         请求体（会自动 JSON 序列化）
   - :query-params 查询参数
   - :timeout      超时毫秒数
   - :as           响应格式 (:json :text :stream :byte-array)

   同步用法:
   (request :get \"https://api.example.com/data\")
   => {:status 200, :body {...}, :success? true}

   异步用法:
   (request :get \"https://api.example.com/data\"
     :async? true
     :callback (fn [resp]
                 (println \"Status:\" (:status resp))))"
  [method url & {:keys [async? callback headers body query-params timeout as]
                 :or {async? false
                      timeout *default-timeout*
                      as :json}}]
  (let [opts (cond-> {:method method
                      :url url
                      :timeout timeout
                      :as (case as
                            :json :text
                            :stream :stream
                            :byte-array :byte-array
                            :text)}
                 true (assoc :headers (merge *default-headers* headers))
                 body (assoc :body (if (string? body)
                                    body
                                    (json/generate-string body)))
                 query-params (assoc :query-params query-params))]
    (if async?
      ;; 异步模式
      (http/request opts
        (fn [{:keys [status headers body error] :as resp}]
          (let [parsed (parse-response resp as)]
            (when callback
              (callback parsed)))))
      ;; 同步模式
      (let [resp @(http/request opts)]
        (parse-response resp as)))))

;; ============================================================
;; HTTP 便捷方法（宏批量生成）
;; ============================================================

(defmacro ^:private defhttp-methods
  "批量生成同步和异步 HTTP 便捷方法

   对每个 [method has-body?] 对，生成:
   - 同步函数: (method url & opts)
   - 异步函数: (method-async url callback & opts)"
  [& specs]
  `(do
     ~@(mapcat
         (fn [[method has-body?]]
           (let [sync-name (symbol (name method))
                 async-name (symbol (str (name method) "-async"))
                 method-kw (keyword method)]
             [(if has-body?
                `(defn ~sync-name
                   ~(str "同步 " (clojure.string/upper-case (name method)) " 请求")
                   [~'url & {:keys [~'headers ~'body ~'query-params ~'timeout]
                             :or {~'timeout *default-timeout*}}]
                   (request ~method-kw ~'url
                            :headers ~'headers :body ~'body
                            :query-params ~'query-params
                            :timeout ~'timeout :async? false))
                `(defn ~sync-name
                   ~(str "同步 " (clojure.string/upper-case (name method)) " 请求")
                   [~'url & {:keys [~'headers ~'query-params ~'timeout]
                             :or {~'timeout *default-timeout*}}]
                   (request ~method-kw ~'url
                            :headers ~'headers
                            :query-params ~'query-params
                            :timeout ~'timeout :async? false)))
              (if has-body?
                `(defn ~async-name
                   ~(str "异步 " (clojure.string/upper-case (name method)) " 请求")
                   [~'url ~'callback & {:keys [~'headers ~'body ~'query-params ~'timeout]
                                        :or {~'timeout *default-timeout*}}]
                   (request ~method-kw ~'url
                            :headers ~'headers :body ~'body
                            :query-params ~'query-params
                            :timeout ~'timeout
                            :async? true :callback ~'callback))
                `(defn ~async-name
                   ~(str "异步 " (clojure.string/upper-case (name method)) " 请求")
                   [~'url ~'callback & {:keys [~'headers ~'query-params ~'timeout]
                                        :or {~'timeout *default-timeout*}}]
                   (request ~method-kw ~'url
                            :headers ~'headers
                            :query-params ~'query-params
                            :timeout ~'timeout
                            :async? true :callback ~'callback)))]))
         specs)))

(defhttp-methods
  [get false]
  [post true]
  [put true]
  [patch true]
  [delete false])

;; ============================================================
;; JSON 便捷方法
;; ============================================================

(defn post-json
  "POST JSON 请求（简化版）

   自动设置 Content-Type 和 Accept 为 application/json

   示例:
   (post-json \"https://api.example.com/users\" {:name \"test\"})"
  ([url body]
   (post-json url body {}))
  ([url body opts]
   (post url
         :body body
         :headers (merge {"Content-Type" "application/json"
                          "Accept" "application/json"}
                         (:headers opts))
         :timeout (or (:timeout opts) *default-timeout*))))

(defn get-json
  "GET JSON 请求（简化版）

   示例:
   (get-json \"https://api.example.com/users\")"
  ([url]
   (get-json url {}))
  ([url opts]
   (get url
        :headers (merge {"Accept" "application/json"}
                        (:headers opts))
        :query-params (:query-params opts)
        :timeout (or (:timeout opts) *default-timeout*))))
