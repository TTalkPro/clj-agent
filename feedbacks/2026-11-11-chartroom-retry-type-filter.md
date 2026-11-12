# 2026-11-11 · chartroom:重试策略想按错误类型排除(429 不重试)

来源:观澜(chartroom)后端 C# → Clojure 移植(D57,AK-06 Llm 层),clj-agent 0.5.449。

## 现象

观澜的重试口径(C# 版 `ChatClients.WithRetry`,Keelson 1.1.6 缺省):**429 不重试** ——
国内几家把「限流」与「欠费 / 额度用尽」都回成 429,重试等于替用户再花钱 / 再撞一次风控。
clj-agent 的 `retry/run` 只看 canonical error 的 `:retryable?`,而 provider 边界把 429 判成
`:rate-limit-error`、`:retryable? true`;`resolve-opts` 也没有「按类型排除」的口子。

## 观澜侧的顶法

`chartroom.llm.client/no-retry-on-rate-limit`:在 ILLMProvider 外面包一层(`wrap-provider`),
捕获 `rate-limit-error?` 的异常,`errors/with-ex-data e {:retryable? false}` 再抛。
副作用:包装层不实现 `IAsyncLLMProvider`,ChatModel 的异步路径退回虚拟线程兜底(语义相同,只是多占一根虚拟线程)。

## 建议

`:retry` 配置加一格判据,例如:

```clojure
{:max-retries 3 :skip-types #{:rate-limit-error}}      ; 或
{:max-retries 3 :retryable? (fn [err] …)}              ; 覆盖缺省判据
```

`http-retryable?` 里先问它。这样宿主不必为了改一条判据去包 provider(包了还会丢掉原生异步)。

## clj-agent 回复（2026-11-12，未提交）

已按建议做了，两种写法都支持（`designs/provider-reliability.md` §2.7）：

```clojure
{:retry {:max-retries 3 :skip-types #{:rate-limit-error}}}     ; 429 不重试
{:retry {:max-retries 3 :retryable? (fn [err] …)}}             ; 覆盖缺省判据（:skip-types 仍先生效）
```

- 判据收成公开函数 `im.ttalk.agent.retry/retry-error?`。ChatModel 的 `:max-retries` / `:stream-retries`（同步 `run`、异步
  `run-async`）、provider 流式建链重试、embeddings 的 `http.retry/with-retry` 都走它。三级合并照旧：
  provider config 的 `:retry` 或单次 opts 的 `:retry` 里给都行。
- 不给这两格 ⇒ 行为逐字不变；空补全仍只归 `:empty-retries`。
- 测试：core retry_test 加了判据、同步 / 异步、ChatModel 从 config 读 `:skip-types` 三组；provider 的 retry_test 和
  stream_client_test 各加一组（429 建链只打一次）。

观澜侧：升级后可以删掉 `chartroom.llm.client/no-retry-on-rate-limit` 这层包装，provider config 写
`:retry {:skip-types #{:rate-limit-error}}`，原生异步路径也回来了。


## 观澜侧跟进（2026-11-12）

已采纳（clj-agent 0.5.456）：`chartroom.llm.client/no-retry-on-rate-limit` 那层 provider 包装删掉，重试 config 写
`:skip-types #{:rate-limit-error}`。判据 `client-test/rate-limit-is-not-retried` 照旧绿，并补了流式那条（建链 429 也只打一次）。
不挂审计装饰的路径（摘要、测试、一问一答）原生异步回来了；Agent 层的审计装饰仍包 provider（`wrap-provider`），那条照旧走虚拟线程兜底。
