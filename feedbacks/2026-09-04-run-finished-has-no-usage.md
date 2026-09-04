# `RUN_FINISHED` 不带 `usage` ⇒ 客户端的 token 用量恒空

## 症状

一个字都不报。客户端的用量环（`happy.agui.view/usage-ring`，对齐 CopilotKit 的
用量显示）永远画不出来 —— 因为 `RUN_FINISHED` 里没有 `usage` 这个键：

```
"type":"RUN_FINISHED","threadId":"u-probe","runId":"551c8ebc-…"}
```

对照另一台 AG-UI 实现（keel，`:8080`）同一个事件：

```
"type":"RUN_FINISHED","threadId":"mm-keel","runId":"r-keel-1",
"usage":[{"model":"MiniMax-M2.7","inputTokens":360,"outputTokens":158}]
```

## 位置

`modules/clj-agent-agui/src/im/ttalk/agent/agui/codec.clj`
—— 全 ns `grep usage` 零命中；`:run/finished` 那一支只发 `threadId` / `runId`
（带 outcome 时多一个 `outcome`）。

## 怎么撞上的

```bash
curl -s -X POST http://localhost:4002/api/copilotkit/agent/default/run \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"threadId":"t","runId":"r","messages":[{"role":"user","content":"说一个字"}],
       "tools":[],"context":[]}' | grep RUN_FINISHED
```

## 影响面

- **谁会撞**：任何显示用量的客户端。CopilotKit 的 demo 与我们的 `/agui` 都有这
  一块；我们的 `usage-ring` 明确写了「没有 usage 数据时返回 nil，不占位」，
  所以表现是**那块 UI 干脆不出现**，不报错、也不解释。
- **撞了会怎样**：接入方会先怀疑自己的解析。我们就是先去翻 `usage-totals`
  的单测（绿的），才想到去看 wire 上根本没有这个字段。
- 🟢 不挡事 —— 但每个接入方都要各自撞一次才知道「这台不给」。

## 建议

模型调用那一层本来就拿得到 token 数（provider 的响应里有）。把它挂到
`:run/finished` 事件上，codec 照 AG-UI 的形状发即可：

```json
"usage": [{"model": "…", "inputTokens": N, "outputTokens": M, "totalTokens": K}]
```

⚠️ 是**数组**（一轮里可能换过模型 / 有 subagent），keel 那边也是数组 ——
客户端 `usage-totals` 是按数组求和写的。

⛔ 如果暂时不想接：那就**别在 `/info` 里留想象空间** —— 现在客户端只能靠
「发一轮试试有没有」来判断，这类「探测式能力发现」正是 `/info` 该消灭的东西。
