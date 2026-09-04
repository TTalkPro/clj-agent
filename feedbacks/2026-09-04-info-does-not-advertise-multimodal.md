# `/info` 不宣告多模态能力 ⇒ 客户端没法 gate 附件 UI，只能盲发

## 症状

一个字都不报。客户端问不出「这台运行时收不收图片 / PDF」，于是要么**永远**
摆一颗回形针（点了才发现发过去没用），要么**永远不摆**（有能力也用不上）。

## 位置

`modules/clj-agent-agui/src/im/ttalk/agent/agui/codec.clj:445` `run-info`
—— 每个 agent 的 `capabilities` 现在只有 `humanInTheLoop` 一族。

## 怎么撞上的

```bash
curl -s http://localhost:4002/api/copilotkit/info | jq '.agents.default.capabilities'
# => {"humanInTheLoop":{"supported":true,"approvals":true,"interrupts":true}}
```

对照另一台 AG-UI 实现（keel，`:8080`）同一个位置：

```json
"capabilities": {
  "transport":  {"streaming": true},
  "tools":      {"supported": true, "parallelCalls": true, "clientProvided": true},
  "reasoning":  {"supported": true, "streaming": true},
  "multimodal": {"input": {"image": true, "pdf": true, "audio": false, "video": false},
                 "output":{"image": false, "audio": false}},
  "execution":  {"maxIterations": 6},
  "humanInTheLoop": {"interrupts": false}
}
```

## 影响面

- **谁会撞**：任何想按能力决定渲染什么的客户端。我们的调试面板有一页「能力」，
  专门答「这台运行时会什么」——多模态那一格现在只能空着。
- **撞了会怎样**：不报错。用户拖一张图进去、发出去、模型说看不见 —— 三方都
  没错，只是没人告诉客户端「别发」。
- ⚠️ 注意这条与 [多模态部件那条](2026-09-04-agui-drops-multimodal-content-parts.md)
  是**两件事**：那条是「发过去了但被压成字符串」，这条是「压根问不出该不该发」。
  两条都补上，客户端才能既发得对、又知道什么时候别发。

## 建议

`run-info` 的 `capabilities` 加一格（**只报真支持的**，与该函数 docstring 里
「不谎报能力位」那条一致）。能不能报为 true，取决于**装配时的 provider**：

- provider 的 wire 认部件（`wire/anthropic.clj` / `wire/openai.clj` 都认）
- 且模型本身有视觉

第二条 clj-agent 自己判不了 —— 所以更稳的形状是**让装配方传进来**
（`run-info` 已经有 `:capabilities` 参数，`demo_server` 传一份即可），
而不是由 codec 猜。⛔ 别按「provider 是谁」硬编码：同一个 provider 下
`MiniMax-M2.7` 没视觉、`qwen-vl` 有。
