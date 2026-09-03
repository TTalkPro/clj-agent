(ns mcp.server-example
  "把 clj-agent 的工具暴露成一个 **MCP server**，两种跑法。

   ## stdio（本地 server 的主流跑法）

       clojure -M:mcp -m mcp.server-example

   然后在 Claude Desktop / 别的 MCP host 的配置里指过来即可。
   **stdout 只许走协议，日志一律走 stderr**——往 stdout 打一行日志，对面的解析器
   就废了。

   ## Streamable HTTP

       (start-http! 4300)

   这段就是**模块里没有的那一半**：`clj-agent-mcp` 只给纯函数
   `server/handle-message`，怎么接到 web 上是调用方的事（`design-principles` §2）。
   接线一共三步，缺一不可：

   1. **先过 `server/check-headers`**——`MCP-Protocol-Version` / `Mcp-Method` /
      `Mcp-Name` 三个头要与信封一致，不一致回 `-32020`。规则属于协议，但只有
      web 层看得见头，所以模块把它做成函数交出来；
   2. 再交给 `handle-message`；
   3. **通知返回 nil → 回 202 空体**，不要回一个空 JSON——协议禁止给通知回响应。"
  (:require [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.server :as srv]
            [org.httpkit.server :as hk]))

;;; ============================================================
;;; 一台 server
;;; ============================================================

(def weather-tool
  "工具就是 clj-agent 的**内联工具**形状——现成的工具直接放进来就能暴露出去。"
  {:name "get_weather"
   :description "查一个城市的天气"
   :parameters {"type" "object"
                "properties" {"city" {"type" "string" "description" "城市名"}}
                "required" ["city"]}
   :handler (fn [args _ctx]
              (str (get args "city") "：晴，24℃"))})

(def stats-tool
  {:name "fleet_stats"
   :description "车队统计（返回结构化结果）"
   :parameters {"type" "object" "properties" {}}
   :handler (fn [_ _] {:total 128 :online 97 :unit "辆"})})

(defn make-server []
  (srv/server
   {:name "clj-agent-demo"
    :version "0.3"
    :instructions "这台 server 提供天气查询与车队统计。"
    :tools [weather-tool stats-tool]
    :resources [{:uri "mem://readme"
                 :name "readme"
                 :mimeType "text/plain"
                 :read (fn [_] "这是一台 clj-agent 起的 MCP server。")}]
    :prompts [{:name "daily_brief"
               :description "生成每日简报的提示词"
               :arguments [{"name" "city" "description" "城市" "required" true}]
               :get (fn [args]
                      [{"role" "user"
                        "content" {"type" "text"
                                   "text" (str "用一段话汇报 " (get args "city") " 今天的情况。")}}])}]}))

;;; ============================================================
;;; stdio
;;; ============================================================

(defn -main
  "stdio 入口。**阻塞**到 stdin 关闭。"
  [& _]
  (binding [*out* *err*]                       ;; 保险：任何 println 都别落到 stdout
    (println "[mcp] clj-agent MCP server 启动（stdio）"))
  (srv/stdio-server! (make-server)))

;;; ============================================================
;;; Streamable HTTP
;;; ============================================================

(def ^:private cors
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Headers" "content-type, mcp-protocol-version, mcp-method, mcp-name, mcp-session-id, authorization"
   "Access-Control-Allow-Methods" "POST, OPTIONS"})

(defn handler
  [s]
  (fn [{:keys [request-method body headers]}]
    (cond
      (= :options request-method) {:status 204 :headers cors}

      (not= :post request-method)
      {:status 405 :headers cors :body "MCP Streamable HTTP 只收 POST"}

      :else
      (let [msg (p/parse (slurp body))]
        (cond
          (nil? msg)
          {:status 400 :headers (merge cors {"Content-Type" "application/json"})
           :body (p/encode (p/error-response nil :parse-error "不是合法的 JSON"))}

          ;; 1. 头与信封一致吗（只有这一层看得见头）
          (srv/check-headers msg headers)
          {:status 400 :headers (merge cors {"Content-Type" "application/json"})
           :body (p/encode (srv/check-headers msg headers))}

          :else
          (if-let [resp (srv/handle-message s msg)]
            ;; 校验类错误要回 4xx，业务响应回 200——客户端的双时代探测看状态码
            {:status (if (#{-32602 -32020 -32021 -32022}
                          (get-in resp ["error" "code"])) 400 200)
             :headers (merge cors {"Content-Type" "application/json"})
             :body (p/encode resp)}
            ;; 2. 通知不回响应
            {:status 202 :headers cors}))))))

(defn start-http!
  ([] (start-http! 4300))
  ([port]
   (let [stop (hk/run-server (handler (make-server)) {:port port})]
     (println (str "[mcp] Streamable HTTP 端点: http://localhost:" port))
     stop)))
