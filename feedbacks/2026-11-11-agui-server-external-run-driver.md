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

## 复测(锦书,2026-11-11,clj-agent 0.5.440)✅

锦书 `jinshu.server.agui.endpoints` 去掉了全部绕法:占位 runtime、字面量路由覆盖、自写的单路由退路都删了。
- ① 注册表条目改成 `{:id "jinshu" :external? true :description … :capabilities …}`;`/info` 的能力位照旧(判据 `endpoints-info-cors-threads-messages` 原样过)。
- ② `host/host` 的 `:agents {"jinshu" {:run … :stop …}}` 注入;字面量路由与单路由退路的 `agent/run` / `agent/stop` 都到了锦书的处理器
  (`::agui/request-body`、`::agui/external-thread-id` 取值;新判据 `external-driver-connect-replays-the-last-run-and-single-route-runs-jinshu`)。
- ③ 没用 `:connect-source`:锦书的驱动每帧直接写帧环(`replay/begin!` 起跑、`append!` 每帧、`finish!` 收场),`/connect` 走库的缺省
  ⇒ 重放这条线程最近一轮,与 C# 一致(之前是 200 空流)。新一轮整个换掉帧环、没跑过的线程 200 空流,都钉了判据。
- 门禁:锦书 `bb test` 1084 tests / 11119 assertions 全绿;e2e 106 / 106(Playwright 驱动 jpackage 产物)。

**小缺口(🟢,锦书已按约定处理)**:外部 `:run` 处理器拿到的 `::agui/request-body` 在请求体超限时是 `nil`,原因没带过来。
锦书约定「键在、值 nil ⇒ 413 body_too_large」,能用,但靠的是「读失败只有超限这一种」这个隐含前提。
**建议**:超限时直接回库自己的 413(不调外部处理器),或把原因(`[:too-large ex]`)一并挂进 req。

## 库侧回复（2026-11-11，小缺口）✅

已修（只编码未提交）：采纳第一案——字面量路由读到 `[:too-large ex]` 直接回库的
`413 body_too_large`，不再调外部 `:run`；单路由退路同口径（原先会被误判成 404）。
判据钉在 `external_driver_test` 的 `external-run-body-too-large-does-not-call-driver`。
