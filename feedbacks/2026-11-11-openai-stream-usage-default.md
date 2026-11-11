# provider:OpenAI 兼容线的流式用量缺省不开,开了又不分流式与否

来源:锦书后端改写 JVM(§K-31 真模型验收,LiteGate OpenAI 兼容网关 + MiniMax-M3.1-Flash),2026-11-11。依赖 clj-agent-provider 0.5.440。

## 症状

一个字都不报。经 OpenAI 兼容端点跑的每一次调用,`:usage` 都是空的 ⇒ 下游的调用账本 input / output / cache 全记 0,
「上下文快满了要压缩」的判断按 0 算,永远不触发。同一套代码走 Anthropic 方言(用量在 `message_delta` 里)一切正常,所以很难想到是 provider 的事。

## 位置

`clj-agent-provider` `im/ttalk/agent/provider/common/openai_compat.clj`:

- `build-params` 第 133 行:`stream-options (assoc :stream_options stream-options)` —— 只有调用方显式给了 `:stream-options` 才带,**缺省不带**;
- 同一行**不看 `stream?`**:给了就连非流式请求也带上。OpenAI 规范只许流式请求带 `stream_options`(非流式会被拒),
  所以下游没法「在 config 里开一次就完事」—— 同一个客户端既跑流式对话、也跑非流式的连通探针 / 一次性小任务。

## 怎么撞上的

1. OpenAI 兼容端点里,只有部分厂商在末块**原生**带 usage(DeepSeek);LiteGate、DashScope 兼容模式、OpenAI 本家都要请求里带
   `stream_options: {include_usage: true}` 才在最后一帧给用量。
2. `(cm/stream-call client req sink)` 走流式,不给 `:stream-options` ⇒ 末帧没有 usage ⇒ `:usage` 为 nil。
3. 给了 `:stream-options {:include_usage true}` ⇒ 同一客户端的 `cm/call`(非流式)请求体里也出现 `stream_options` ⇒ 严格的端点 400。

## 影响面

所有经 OpenAI 兼容线、且末块不原生带 usage 的接入方:用量 / 计费 / 压缩判断静默失效。对照:Keelson.Agent(C#)的
OpenAI 兼容客户端是**流式时缺省开** `include_usage`、非流式不带,锦书 C# 版因此从没撞上。

锦书眼下的绕法:在 `:configure-request` 钩子里按最终请求体的 `:stream` 判,流式才补 `:stream_options {:include_usage true}`
(与 qwen / moonshot 的钩子串起来)。能用,但每个下游都要知道这件事、都要自己写一遍。

## 建议

- 流式请求**缺省**带 `stream_options: {include_usage: true}`(与 Keelson.Agent 对齐);`:stream-options` 留作覆盖 / 关闭的口子
  (比如显式给 `false` 或 `{}` 表示不要);
- 不论缺省还是显式,**只在 `stream?` 为真时**写进请求体。

## 库侧回复（2026-11-11）✅

已修（只编码未提交）：OpenAI 兼容线流式请求缺省带 `stream_options {:include_usage true}`；`:stream-options`
显式值原样覆盖，`false` / `{}` 表示关闭；显式 `{:include_usage false}` 这类形状也原样透传。四条调用路径共用
`request-body`，因此钩子 `:configure-request` 仍看得见并最后可删。
不论缺省还是显式，非流式一律不写 `stream_options`。判据钉在 `params_test` 的
`openai-compat-stream-usage-default-test`（真正发出的请求体口径）。
