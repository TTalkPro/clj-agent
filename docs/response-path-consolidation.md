# D6 / D7 — 响应路径与 wire 知识整理

> 状态：✅ 全部完成。D7 死代码清理 2026-06；D6 按「中立层容许别名」定调；
> **双消息体系统一已于 2026-07-10 落地（v0.2）**：删除 `model/types.clj`，
> tool-call 全库统一为 `{:id :name(字符串) :args}`（与中立消息同构），
> response→neutral 桥退化为形状复位。
> 来源：2026-06-10 全量审查 D6「core 泄漏厂商 wire 知识」、D7「重复抽象」。

---

## 1. 已完成（2026-06-10）

### D7 — 删除死抽象 / 死方法

审查指「4 份响应归一化 + 协议死方法 + 双消息体系」。经调用方核查，先清掉**确证为死**的部分：

| 删除项 | 位置 | 核查结论 |
|--------|------|---------|
| 协议方法 `build-result-messages` / `build-assistant-message` | `model.clj` 协议定义 + `extend-type Object` 默认实现 + 4 个 provider record（base/anthropic/bailian/mock）+ 全部测试 reify | `:build-result-msgs` 由 service 构造但**从不被调用**；历史由 `advisor/memory/response->neutral`（中立消息）构建。全链路无消费者。 |
| `call-with-tools` | `model.clj` | 核心里的「第 4 份归一化」，仅测试调用，生产路径走 `service/normalize-response`。 |
| `:build-result-msgs` service 键 | `model/service.clj/create-service` | 同上，构造即死。 |
| `response-assistant-msg` / `:assistant-msg` | `model/response.clj`（ILLMResponse 协议 + LLMResponse 字段 + make-response 参数）| 仅由已删的 `build-assistant-message` 填充，从不被读取。 |
| 独立函数 `build-assistant-message` | `common/openai_compat.clj` | 无调用方。 |

附带一致性修复：`service/normalize-response`（同步路径）现也调 `response/extract-reasoning`，
与 stream / response_parser 路径对齐（此前只有流式/解析器路径提取 reasoning）。

**净效果**：协议从 11 方法减到 9（必需 5 + 可选 4）；响应归一化从「4 份」收敛为
**2 份活路径**（`service/normalize-response` 同步 + `stream/*` 流式，二者输入不同——
原始响应 map vs 流式累积 state——是合理分工，不再有第 3、4 份冗余）。测试 196→192
（删掉 4 个针对死方法的测试），断言数稳定，0 失败。

### D6 — wire 知识收敛到单一来源

清掉 `call-with-tools` 后，"原始响应里 usage/finish-reason/reasoning 在哪" 的知识
**只剩一处权威实现**：`model/response.clj` 的 `normalize-usage` /
`normalize-finish-reason` / `extract-reasoning`。`service/normalize-response` 与
`common/response_parser` 都委托它，不再各写一份。

---

## 2. D6 的设计定调：中立层「容许已知别名」

`model/response.clj` 的归一化函数仍然"认识"各家字段名（OpenAI `prompt_tokens`、
Anthropic `cache_creation_input_tokens`、DeepSeek `prompt_cache_hit_tokens`、
GLM `sensitive` 等）。这是**有意保留**的中立层设计，而非 D6 残留 bug：

- 这些是**permissive 归一化器**——接受任一已知别名并产出统一 key，不需要"知道这是哪个 provider"。
- 与 `extract-text` / `extract-tool-calls`（协议方法，provider 自取）不同，usage/finish-reason
  在多数 provider 间只有两种常见位置（顶层 or `choices[0]`），permissive 双查足够鲁棒。
- **彻底 DIP 化**（给协议加 `extract-usage` / `extract-finish-reason`，每个 provider 自实现）
  是**破坏性变更**：协议加方法 → 所有 provider record + 所有测试 reify 都要实现。
  收益（新增"第三种 usage 位置"的 provider 才需改 core）与破坏面不成比例。

**结论**：保留 permissive 单一来源；新增 provider 若用非主流字段，只需在
`model/response.clj` 一处加别名。若未来要做破坏性大版本，再考虑协议化。

---

## 3. 暂缓（需专项 / 破坏性大版本）

### D7 剩余 — 双消息体系统一

`model/types.clj`（字符串 role、`:tool_calls`/`:tool_call_id`、name 为 keyword）与
`model/message.clj`（keyword role、`:tool-calls`/`:tool-call-id`、name 为 string）并存：

- `types/*`：provider **从响应里抽取**工具调用/构造消息时用（`make-tool-call` 6 处、
  `*-message` 构造若干）——是"provider 响应形状"。
- `message/*`：core 运行时（react/client/memory/advisor）+ wire 适配器用——是"中立历史形状"。
- 两者之间有**明确转换边界**：`advisor/memory/response->neutral`、`msg/normalize`。

统一二者要改动**整条消息数据流**（provider 抽取 → response → 中立历史 → wire 回写），
是高风险大重构，且当前转换边界工作正常、无正确性 bug。**建议作为 v0.2 破坏性版本专项**，
配合充分的端到端流式/工具/多轮回归一起做，不在常规改动中草率推进。

### D6 剩余 — converter/json_schema 的 provider 分发

`converter/json_schema.clj` 按 `:openai/:anthropic/:zhipu/:gemini` 生成结构化输出格式，
是 core 里另一处"认识各家 wire"。同样属破坏性协议化范畴，随 v0.2 一并处理。

---

## 4. 影响 / 风险

- **破坏性**：移除了协议方法 `build-result-messages`/`build-assistant-message` 与响应协议
  `response-assistant-msg`。外部若有自定义 provider 实现了这些方法——**删掉即可**（运行时本就不调用）；
  若读取过 `response-assistant-msg`——该值本就恒为 nil。属安全的破坏。
- **回归**：全套 192 tests / 759 assertions / 0 失败；删除的是死方法专属测试。
