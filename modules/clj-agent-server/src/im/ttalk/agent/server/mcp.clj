(ns im.ttalk.agent.server.mcp
  "MCP 的 Streamable HTTP 端点——**Ring 处理器**（designs/server-module.md §4）。协议在 clj-agent-mcp：
   `im.ttalk.agent.mcp.server/handle-message`（纯函数，一条进一条出）与 `check-headers`（HTTP 头与信封一致性）；
   这里只做 HTTP 那一层。转正自 examples/mcp/server_example.clj 的 http-kit 接线。

   ```clojure
   (require '[im.ttalk.agent.mcp.server :as srv]
            '[im.ttalk.agent.server.mcp :as mcp-http])
   (def s (srv/server {:name \"demo\" :version \"1.0\" :tools [...]}))
   (mcp-http/handler s {:origin-allowed nil})        ;; 一个 Ring 处理器，交给任何 Ring 服务端
   (mcp-http/routes \"/mcp\" s opts)                   ;; 或 im.ttalk.agent.server.routing 的路由表
   ```

   - `POST`：读体（受 `:max-body-bytes`，超了 413）→ 解析 JSON（坏 ⇒ 400 `-32700`；JSON 数组 ⇒ 400 `-32600`，批量自
     2025-06-18 起已从协议删除）→ `check-headers`（头与信封不一致 ⇒ 400 `-32020`）→ `handle-message` → 有响应 ⇒ JSON，
     校验类错误（`-32602 -32020 -32021 -32022`）400、其余 200（客户端的双时代探测看状态码）；通知 ⇒ 202 空体（协议禁止给
     通知回响应）。`handle-message` 意外抛出 ⇒ 500 `-32603`（不把异常文本吐给客户端）。
   - `GET` / `DELETE` / 其他 ⇒ 405 带 `Allow: POST, OPTIONS`：本端点不开服务端推送流、也没有会话（规范允许）。
   - `OPTIONS` ⇒ 预检 204。
   - **Origin 缺省拒**：带 Origin 且不在 `:origin-allowed` 里 ⇒ 403（规范要求服务端校验 Origin 防 DNS rebinding）；
     不带 Origin（非浏览器客户端）放行。白名单用 `im.ttalk.agent.server.agui.http/origin-allow`。
   - 只回 JSON、不回 SSE：一条请求一条响应，没有要推的中间消息（规范允许服务端选 JSON）。

   选项：`:origin-allowed`（Origin 谓词；缺省 nil = 拒所有带 Origin 的）`:credentials?`（回 Allow-Credentials）
   `:allow-headers`（预检额外放行的请求头）`:max-body-bytes`（缺省 4 MiB）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.server :as srv]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.http :as http]
            [im.ttalk.agent.server.strings :as strings]))

(set! *warn-on-reflection* true)

(def default-max-body-bytes (* 4 1024 1024))

(def ^:private validation-codes
  "这些错误码回 400：客户端的双时代探测靠状态码分辨「请求被拒」与「业务结果」。"
  #{-32602 -32020 -32021 -32022})

(def ^:private allow-headers
  ["Content-Type" "Accept" "Authorization" "MCP-Protocol-Version" "Mcp-Method" "Mcp-Name" "Mcp-Session-Id"])

(defn- origin [req] (let [o (http/header req "Origin")] (when (seq o) o)))

(defn- cors-headers [req opts]
  (let [o (origin req)]
    (when (and o (ahttp/origin-ok? opts o))
      (cond-> {"Access-Control-Allow-Origin" o "Vary" "Origin"}
        (:credentials? opts) (assoc "Access-Control-Allow-Credentials" "true")))))

(defn- rpc-response [status headers msg]
  (http/response status (merge {"Content-Type" "application/json; charset=utf-8"} headers) (p/encode msg)))

(defn- dedup-headers [hs]
  (->> hs
       (reduce (fn [[seen out] h] (let [k (str/lower-case h)] (if (seen k) [seen out] [(conj seen k) (conj out h)])))
               [#{} []])
       second
       (str/join ", ")))

(defn- handle-post [server req opts cors]
  (let [body (try (http/read-body-string req (or (:max-body-bytes opts) default-max-body-bytes))
                  (catch clojure.lang.ExceptionInfo e
                    (if (= "server/body-too-large" (strings/code e)) ::too-large (throw e))))]
    (if (= ::too-large body)
      (http/response 413 cors nil)
      (let [msg (p/parse body)]
        (cond
          (nil? msg)
          (rpc-response 400 cors (p/error-response nil :parse-error "请求体不是合法的 JSON"))

          (not (map? msg))
          (rpc-response 400 cors (p/error-response nil :invalid-request "要一条 JSON-RPC 消息（对象）；批量请求已从协议删除"))

          :else
          (if-let [mismatch (srv/check-headers msg (:headers req))]
            (rpc-response 400 cors mismatch)
            (let [resp (try (srv/handle-message server msg)
                            (catch Throwable _
                              (p/error-response (get msg "id") :internal-error "服务端内部错误")))]
              (if (nil? resp)
                (http/response 202 cors nil)
                (rpc-response (if (validation-codes (get-in resp ["error" "code"])) 400 200) cors resp)))))))))

(defn handler
  "MCP Streamable HTTP 的 Ring 处理器（见 ns 文档）。同步与 Ring 异步（3 元）两种元数都有。"
  ([server] (handler server nil))
  ([server opts]
   (let [f (fn [req]
             (let [cors (cors-headers req opts)
                   method (:request-method req)]
               (cond
                 (= :options method)
                 (http/response 204 (merge cors {"Access-Control-Allow-Methods" "POST, OPTIONS"
                                                 "Access-Control-Allow-Headers" (dedup-headers (concat allow-headers (:allow-headers opts)))
                                                 "Access-Control-Max-Age" "600"})
                                nil)

                 (not (ahttp/origin-ok? opts (origin req)))
                 (rpc-response 403 nil (p/error-response nil :invalid-request (str "Origin 不被允许：" (origin req))))

                 (not= :post method)
                 (http/response 405 (merge cors {"Allow" "POST, OPTIONS"}) nil)

                 :else (handle-post server req opts cors))))]
     (fn
       ([req] (f req))
       ([req respond raise] (try (respond (f req)) (catch Throwable t (raise t))))))))

(defn routes
  "挂在 `path` 下的路由表（im.ttalk.agent.server.routing 的格式）：任何方法都交给 `handler`（它自己回 405 / 预检）。"
  ([path server] (routes path server nil))
  ([path server opts]
   [[:any path (handler server opts)]]))
