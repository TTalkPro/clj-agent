# clj-agent-server:AG-UI 路由不认「自带 run 驱动」的 agent

来源:锦书后端改写 JVM(锦书 §K-19 / K-29 Server,`jinshu.server.agui.*`),2026-11-11。依赖 clj-agent-server / clj-agent-agui 0.5.438。
锦书的 `/run` 由它自己的 ChatEngine 驱动(HIL 挂起 / 恢复、`action=attach` 从 seq 0 重放跟到底、`action=cancel`、resume[] 分流),
不走 clj-agent-agui 的 runtime。`/info` `/threads*` 预检 / Origin / CORS 都用了 `im.ttalk.agent.server.agui/routes`,只有 run / stop 自己写。

## ① 注册表条目强制要一个 clj-agent-agui runtime

自带 run 驱动的 agent 只能塞一个占位 runtime 才进得了注册表(`/info` 的 agents 才列得出它)。
**建议**:条目允许只给描述(name / description / capabilities)+ 外部 run 处理器,不要求 runtime。

## ② `routes` 不能按 agent 注入 run / stop 处理器

锦书只能用字面量路由 `/agent/jinshu/run`、`/agent/jinshu/stop/:threadId` 靠具体度盖过库的 `/agent/:id/…`;
单路由退路(`POST /agui`)也得自己转发。
**建议**:`routes` 的选项收 `{:agents {\"jinshu\" {:run h :stop h}}}`,命中就交给它,其余照旧。

## ③ `/connect` 的帧环与泵是私有实现

外部驱动没法把自己的事件喂进库的帧环,于是锦书的 `/agent/jinshu/connect` 只能回 200 空流(锦书前端接续走 `action=attach`,不受影响)。
**建议**:公开帧环的写入口(或让 `/connect` 接受一个 `(fn [thread-id after-seq deliver])` 的外部源)。
