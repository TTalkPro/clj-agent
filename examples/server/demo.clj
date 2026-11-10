(ns server.demo
  "同一套 AG-UI + MCP 路由（纯 Ring，clj-agent-server）跑在三种服务端上：Jetty（缺省 adapter，clj-agent-server-jetty）、
   http-kit、Undertow。离线可跑：模型是 provider 模块自带的 mock。设计见 designs/server-module.md。

       clojure -M:server -e \"(require 'server.demo) (server.demo/start! :jetty 3000)\"

   然后：
       curl -s localhost:3000/api/copilotkit/info
       curl -N -X POST localhost:3000/api/copilotkit/agent/echo/run -H 'Content-Type: application/json' \\
            -d '{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"你好\"}],\"tools\":[],\"context\":[],\"state\":{},\"forwardedProps\":{}}'
       curl -s -X POST localhost:3000/mcp -H 'Content-Type: application/json' \\
            -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"curl\",\"version\":\"0\"}}}'

   换服务端只换最后那一步：库代码（路由、SSE、AG-UI、MCP）一行不动。http-kit / Undertow 的 adapter 没实现 Ring SPEC 的流式正文，
   各要一个垫片（im.ttalk.agent.server.adapter.*）；http-kit 上 SSE 发现不了断连（见该垫片的 ns 文档）。"
  (:require [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.mcp.server :as mcp]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.provider.mock :as mock]
            [im.ttalk.agent.server.adapter.http-kit :as hk-shim]
            [im.ttalk.agent.server.adapter.undertow :as ut-shim]
            [im.ttalk.agent.server.agui :as agui]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.jetty :as jetty]
            [im.ttalk.agent.server.mcp :as mcp-http]
            [im.ttalk.agent.server.routing :as routing]))

(defn agent-entry
  "一台 AG-UI agent（注册表条目）：mock 模型 + 进程内 memory。"
  [id]
  (let [mem (memory/in-memory-store)
        spec {:provider (mock/create-mock-provider) :memory mem :pause-store (pause/in-memory-pause-store) :tools []}]
    {:id id
     :description "mock 回声 agent"
     :runtime (rt/runtime {:agent-fn (agui-tools/agent-fn spec)})
     :memory mem}))

(def mcp-server
  (mcp/server {:name "clj-agent-demo" :version "1.0"
               :tools [{:name "get_weather" :description "查一个城市的天气"
                        :parameters {"type" "object" "properties" {"city" {"type" "string"}} "required" ["city"]}
                        :handler (fn [args _] (str (get args "city") "：晴，24℃"))}]}))

(defn routes
  "AG-UI（/api/copilotkit/*）+ MCP（/mcp）的路由表。⛔ 生产里把 :origin-allowed 换成白名单（agui.http/origin-allow）。"
  [h]
  (into (agui/routes "/api/copilotkit" h) (mcp-http/routes "/mcp" mcp-server)))

(defn start!
  "起一台：`kind` 是 :jetty | :http-kit | :undertow。回 (fn [] 停)。"
  [kind port]
  (let [reg (registry/registry [(agent-entry "echo")])
        h (host/host reg {:version "1.0.0"})
        table (routes h)
        stop-runs! (fn [] (host/cancel-all! h) (registry/shutdown! reg))]
    (case kind
      ;; 缺省 adapter：直接收路由表；停机钩子（按停在飞的 run）从路由表元数据里自动登记
      :jetty (let [s (jetty/start table {:port port})]
               (println "Jetty:" (jetty/base-address s))
               (fn [] (jetty/stop! s) (registry/shutdown! reg)))
      ;; http-kit：Ring 处理器 + 流式垫片
      :http-kit (let [stop ((requiring-resolve 'org.httpkit.server/run-server)
                            (hk-shim/wrap-streaming (routing/router table)) {:port port})]
                  (println "http-kit: http://127.0.0.1:" port)
                  (fn [] (stop-runs!) (stop)))
      ;; Undertow：装一次垫片，然后就是普通 Ring 处理器
      :undertow (let [_ (ut-shim/install!)
                      u ((requiring-resolve 'ring.adapter.undertow/run-undertow)
                         (routing/router table) {:port port :host "127.0.0.1"})]
                  (println "Undertow: http://127.0.0.1:" port)
                  (fn [] (stop-runs!) (.stop ^java.io.Closeable u))))))
