(ns im.ttalk.agent.server.strings
  "clj-agent-server 的文案与错误。

   `(msg k & args)` ⇒ `{:code \"区/事件\" :text \"渲染后的话\"}`（`{0}` `{1}` … 占位）；`(error k args data)` ⇒ ex-info，
   ex-data 带 `:im.ttalk.agent.server/code`（= :code）。码与文案移植自 Keelson（`clj-net-server` / `clj-agui-server` 由 C# resx
   生成的中文文案包，提交 5899776c）；线上的错误种类码（`bad_json`、`thread_not_found` …）不在这里，它们是 AG-UI 的线协议。"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def messages
  "文案表（中文）。"
  {
   :agui-input/bad-json "请求体不是合法 JSON"
   :agui-input/empty-body "请求体是空的"
   :agui-input/missing-string "RunAgentInput 缺少 {0}（要一个非空字符串）"
   :agui-input/not-object "RunAgentInput 要是一个 JSON 对象"
   :agui-input/want-array "RunAgentInput.{0} 要一个 JSON 数组"
   :agui-input/want-non-negative-long "RunAgentInput.{0} 要一个不小于 0 的整数"
   :agui-registry/allow-headers-empty-string "host 选项 :allow-headers 里有空串（这一格错了的表现是浏览器里一句 CORS error，指不出是哪个头）"
   :agui-registry/origin-allow-need-one "origin-allow 至少要一个源"
   :agui-registry/thread-store-no-slot "线程存储一个槽都没给（:list / :messages / :events / :state / :update / :delete）—— 它的表现与「没注册」一模一样，多半是写漏了"
   :agui-server/absent "（缺席）"
   :agui-server/agent-not-found "没有名叫 {0} 的 agent"
   :agui-server/endpoints "这个端点只认 /info、/agent/:agentId/run、/agent/:agentId/connect{0} 与 /agent/:agentId/stop/:threadId"
   :agui-server/endpoints-suggest "、/agent/:agentId/suggest"
   :agui-server/forbidden-origin "Origin 不被允许：{0} —— host 选项 :origin-allowed 传 (im.ttalk.agent.server.agui.http/origin-allow \"{0}\")"
   :agui-server/frontend-cancelled "用户取消了这次调用，该工具未执行，没有结果。"
   :agui-server/frontend-cancelled-reason "用户取消了这次调用，该工具未执行，没有结果。用户说明：{0}"
   :agui-server/frontend-no-result "该工具已在客户端执行完成，但没有返回任何结果。"
   :agui-server/interrupt-bad-resume "resume 要是 [{interruptId, status: resolved|cancelled, payload?}]"
   :agui-server/interrupt-mismatch "resume 里的 interruptId 对不上当前的暂停（过期的答复？）：{0}"
   :agui-server/method-not-allowed "这条路只收 {0}"
   :agui-server/negative-heartbeat ":heartbeat-ms 不能是负数"
   :agui-server/no-messages "建议 run 至少要一条消息"
   :agui-server/no-pending-interrupt "线程 {0} 上没有在等答复的 interrupt"
   :agui-server/no-running-run "线程 {0} 上没有在跑的 run"
   :agui-server/post-only "这条路只收 POST"
   :agui-server/stop-declined "agent {0} 没有停下线程 {1} 上的 run（registry 条目的 :stop 回了 false）"
   :agui-server/stop-failed "agent {0} 的 :stop 抛了：{1}"
   :agui-server/thread-awaiting-resume "线程 {0} 停在一个等答复的 interrupt 上 —— 把答复放进下一次 /run 的 resume[]（或带上那个前端工具的结果）"
   :agui-server/thread-mutation-bad-json "改动类的请求体要是一个 JSON 对象，例如 {{\"agentId\":\"keel\"}}"
   :agui-server/thread-mutation-no-agent "请求体里要有一个非空的 agentId"
   :agui-server/thread-not-found "没有这条线程：{0}"
   :agui-server/thread-running "线程 {0} 上一轮还在跑 —— 先 /stop，或者用 /connect 接着看；要「新的顶掉旧的」给那台 runtime 配 :on-concurrent :supersede"
   :agui-server/thread-slot-not-implemented "这份线程存储没有 {0} 那个槽 —— 给 host 选项 :thread-store 的存储补上 {0} 槽才会有这条路"
   :agui-server/thread-store-error "线程存储抛了：{0}"
   :agui-server/thread-store-list-shape "线程存储的 :list 要回一个数组，或一个带 \"threads\" 键的对象"
   :agui-server/unknown-method "认不出的 method：{0}"
   :agui-stock/interrupt-incomplete "resume 没有覆盖全部未决的 interrupt（上游规则 3：不支持部分答复）；还没答的：{0}"
   :agui-stock/interrupt-unknown "resume 里的 interruptId 对不上这一趟 messages 里任何未决的工具调用：{0} —— 它已经答过（messages 里已有它的结果）、过期，或者不是这条线程的（H6.2 / H6.5）"
   :agui-stock/list-separator "、"
   :server/bad-host "host 要一个 IP 字面量或 localhost：{0}"
   :server/bind-needs-token "绑定地址 {0} 不是回环，访问守卫却没有 token（同一网段里任何人都能访问）；给 :guard {:token …} 或改绑回环"
   :server/body-too-large "请求体超过 {0} 字节"
   :server/certificate-no-private-key "HTTPS 证书没有私钥（TLS 握手要用它）"
   :server/localhost-port-zero "host 为 localhost 时端口不能是 0（它要绑两个地址）；用 127.0.0.1"
   :server/unix-socket-in-use "Unix 域套接字 {0} 上已有服务在监听"})

(defn- render
  "`{0}` `{1}` … 换成参数；`{{` / `}}` 是字面量花括号（resx 的转义）。"
  [^String template args]
  (-> (reduce-kv (fn [^String t i a] (str/replace t (str "{" i "}") (str a))) template (vec args))
      (str/replace "{{" "{")
      (str/replace "}}" "}")))

(defn msg
  "文案：`{:code :text}`。键缺失 ⇒ text 是码本身。"
  [k & args]
  (let [code (str (namespace k) "/" (name k))]
    {:code code :text (render (get messages k code) args)}))

(defn error
  "造 ex-info：消息是 `(msg k args…)` 的 text，ex-data = `data` + `:im.ttalk.agent.server/code`。"
  ([m] (error m nil nil))
  ([m data] (error m data nil))
  ([{:keys [code text]} data cause]
   (ex-info text (assoc data :im.ttalk.agent.server/code code) cause)))

(defn code
  "异常（或其 ex-data）的码；不是本模块的 ⇒ nil。"
  [e]
  (cond (instance? clojure.lang.IExceptionInfo e) (:im.ttalk.agent.server/code (ex-data e))
        (map? e) (:im.ttalk.agent.server/code e)))
