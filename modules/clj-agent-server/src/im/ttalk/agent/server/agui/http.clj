(ns im.ttalk.agent.server.agui.http
  "`routes` 每条路外面那一层（C# AguiHttp，A-16 公开）：预检、Origin 闸、CORS 回声，外加 JSON / 错误出口 —— 全是 Ring
   （请求 map 进、响应 map 出）。宿主在自己补的路上用它，⛔ 不用照抄 CORS：

   ```clojure
   (conj (agui/routes \"/agui\" host)
         [\"POST\" \"/agui/agent/mine/stop/:threadId\"
          (fn [req] (ahttp/guard req host (fn [req] (ahttp/json-response 200 {:stopped true}))))])
   ```

   与 clj-net-server 的访问守卫（im.ttalk.agent.server.guard）是两层：守卫在路由之前（Host → 预检豁免 → token）；
   这里在路由之后，只管浏览器那一侧的 Origin / CORS。预检到这里永远 204（403 的预检在浏览器里只报一句 CORS error）。

   CORS 缺省**拒**：带 Origin 的请求一律 403（host 选项 :origin-allowed；用 `origin-allow` 列白名单，开发时 `origin-any`）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.server.strings :refer [msg]]
            [im.ttalk.agent.server.agui.thread-store :as store]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.http :as nh]))

(set! *warn-on-reflection* true)

(def default-allow-headers
  "预检放行的请求头（默认那串；宿主的 :allow-headers 并在后面，⛔ 不替换：CopilotKit 那几个头是它自己要发的）。"
  ["Content-Type" "Accept" "Authorization" "X-CopilotKit-Runtime-Client-GQL-Version" "x-copilotcloud-public-api-key"])

(defn origin-allow
  "白名单谓词（区分大小写，逐字比）。一个都不给 / 有 nil ⇒ 抛 `agui-registry/origin-allow-need-one`。"
  [& origins]
  (when (or (empty? origins) (some nil? origins))
    (throw (err/error (msg :agui-registry/origin-allow-need-one) {:im.ttalk.agent.server/kind :argument})))
  (let [s (set origins)] (fn [origin] (contains? s origin))))

(defn origin-any
  "⚠️⚠️ 只给开发用（生产用 `origin-allow` 列白名单）。"
  []
  (constantly true))

(defn origin-ok?
  "这个 Origin 放不放行：没有 Origin（curl / 服务端）⇒ 放行；没配谓词 ⇒ 拒；谓词抛 ⇒ 拒。"
  [opts origin]
  (cond (empty? origin) true
        (nil? (:origin-allowed opts)) false
        :else (try (boolean ((:origin-allowed opts) origin)) (catch Throwable _ false))))

(defn allow-headers-line
  "默认那串 + 宿主那串（大小写不敏感去重，保序）。⛔ 不写 *：带凭据时浏览器不认通配。"
  [opts]
  (->> (concat default-allow-headers (:allow-headers opts))
       (reduce (fn [[seen out] h] (let [k (str/lower-case h)] (if (seen k) [seen out] [(conj seen k) (conj out h)])))
               [#{} []])
       second
       (str/join ", ")))

(defn allow-methods-line
  "mutations 报 true 才列 PATCH / DELETE（浏览器发它们之前要预检）；没注册的宿主这一行一个字节不变。"
  [thread-store]
  (if (store/mutations? thread-store) "GET, POST, PATCH, DELETE, OPTIONS" "GET, POST, OPTIONS"))

(defn json-response
  "JSON 出口：状态码 + `application/json; charset=utf-8` + 正文（cheshire 编码，保键序）。"
  ([status body] (json-response status nil body))
  ([status headers body]
   (nh/response status (merge {"Content-Type" "application/json; charset=utf-8"} headers) (json/generate-string body))))

(defn error-response
  "错误出口：`{\"error\": code, \"message\": message}`（message 可以是 i18n 的 {:code :text}）。"
  ([status code message] (error-response status nil code message))
  ([status headers code message]
   (json-response status headers {:error code :message (if (map? message) (:text message) (str message))})))

(defn- origin [req] (let [o (nh/header req "Origin")] (when (seq o) o)))

(defn- cors-headers [req opts]
  (let [o (origin req)]
    (when (and o (origin-ok? opts o))
      (cond-> {"Access-Control-Allow-Origin" o
               "Vary" "Origin"}                          ; 回声了 Origin 就必须 Vary，否则中间缓存会串站
        (:credentials? opts) (assoc "Access-Control-Allow-Credentials" "true")))))

(defn- preflight [req host]
  (let [opts (:options host)]
    (nh/response 204 (merge (cors-headers req opts)
                            {"Access-Control-Allow-Methods" (allow-methods-line (:thread-store host))
                             "Access-Control-Allow-Headers" (allow-headers-line opts)
                             "Access-Control-Max-Age" "600"})
                 nil)))

(defn guard
  "包一条路：OPTIONS ⇒ 预检 204（不调 next）；带 Origin 且不在白名单 ⇒ 403 `forbidden_origin`；否则调 next，把 CORS 头
   并进它的响应。白名单、凭据、放行的头与方法都取 host（im.ttalk.agent.server.agui.host）的选项。"
  [req host next]
  (let [opts (:options host)]
    (cond
      (= :options (:request-method req)) (preflight req host)
      (not (origin-ok? opts (origin req))) (error-response 403 "forbidden_origin" (msg :agui-server/forbidden-origin (origin req)))
      :else (let [resp (next req)]
              (if-let [h (cors-headers req opts)]
                (update resp :headers merge h)
                resp)))))
