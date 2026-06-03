(ns im.ttalk.agent.mcp.server.main
  "MCP Server 启动入口

   用于通过命令行启动 MCP Server。

   使用方法:
   clj -M:mcp-server"
  (:require [im.ttalk.agent.mcp.server :as server])
  (:gen-class))

(defn -main
  "启动 MCP Server

   命令行参数:
   --name NAME     服务器名称（默认: clj-agent-tools）
   --version VER   版本号（默认: 1.0.0）
   --port PORT     SSE 模式端口（默认: stdio 模式）"
  [& args]
  (let [;; 解析命令行参数
        args-map (apply hash-map args)
        name (or (get args-map "--name") "clj-agent-tools")
        version (or (get args-map "--version") "1.0.0")
        port (when-let [p (get args-map "--port")]
               (Integer/parseInt p))

        ;; 创建服务器配置
        transport (if port
                    {:type :sse :port port}
                    :stdio)

        ;; 创建并配置服务器
        mcp-server (server/create-server
                     {:name name
                      :version version
                      :transport transport})]

    ;; 注册示例工具
    (server/register-tool mcp-server
      {:name "echo"
       :description "返回输入的消息"
       :inputSchema {:type "object"
                     :properties {:message {:type "string"
                                            :description "要回显的消息"}}
                     :required ["message"]}
       :handler (fn [{:keys [message]}]
                  message)})

    (server/register-tool mcp-server
      {:name "add"
       :description "计算两个数的和"
       :inputSchema {:type "object"
                     :properties {:a {:type "number" :description "第一个数"}
                                  :b {:type "number" :description "第二个数"}}
                     :required ["a" "b"]}
       :handler (fn [{:keys [a b]}]
                  (str (+ a b)))})

    ;; 启动服务器
    (server/start mcp-server)

    ;; 打印启动信息到 stderr（不干扰 stdio 通信）
    (binding [*out* *err*]
      (println "[MCP Server] Started:" name "v" version)
      (if port
        (println "[MCP Server] SSE mode on port:" port)
        (println "[MCP Server] Stdio mode")))

    ;; 保持进程运行
    (Thread/sleep Long/MAX_VALUE)))
