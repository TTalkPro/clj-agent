# AG-UI:共享状态只有**半条链** —— 模型写不了状态、没有 `STATE_DELTA`、`/info` 也不宣告

> ⭐ 先说清楚**已经有的那一半**,免得这条被当成「整件事没做」:
> `agui.emit/emit-state!` 会发 `STATE_SNAPSHOT`,`codec` 有它的出口,
> `/threads/:id/state` 的读面也接好了。缺的是**另一半**。

## 症状

三处一起看:

```
① /info 的 agent capabilities 键:
   humanInTheLoop / transport / tools / reasoning / execution / multimodal
   ⛔ 没有 state 这一类

② 对 :4002 说「把「买牛奶」记进共享状态的 todo 列表」
   → 整条流里 **零条 STATE_ 帧**;模型正文:「我目前没有可用于添加或管理 todo 列表的工具」

③ GET /threads/cs-1/state → {"state":null}      ← 读面是好的,只是从来没有东西可读
```

## 位置

| 文件 | 现状 |
|---|---|
| `modules/clj-agent-agui/src/im/ttalk/agent/agui/emit.clj:36` `emit-state!` | 快照**只从 ToolContext 来**,且 `(when (seq state))` —— demo 那台的 context 里只有框架键 ⇒ 一条都不发 |
| 同上 `:109` | 唯一调用点(`:on-llm-result` 里 `(when-let [c (:context res)] …)`) |
| `…/codec.clj:152` | 只有 `:state/snapshot → STATE_SNAPSHOT`,**`STATE_DELTA` 没有出口** |
| `…/codec.clj:513` | 能力表里没有 `state` 这一格 |
| `examples/copilotkit/http_kit_routes.clj:516` | ⭐ 读面已经对了(取缓冲里最后一条 `:state/snapshot`) |

⇒ **缺的是「模型能写」这一侧**:没有让模型写状态的工具,于是那半条链永远不启动。

## 怎么撞上的

happy 的调试面板有「共享状态」页(对着 `/threads/:id/state` + 会话实时状态)。
在 `:4002` 上它恒空;换到另一台参照运行时(keel `:8080`)同一句话就当场出
`STATE_DELTA`,页面上就有东西了 —— 两台一比,这一格的差别才看得出来。

## 影响面

🟡 **不报错,而且报得诚实**(没有能力位说谎,这点比谎报好得多)。但:

* 上游那一整类「**共享状态驱动的界面**」在这台上接不了 —— CopilotKit 的
  agentic generative UI 就是靠 `STATE_SNAPSHOT/DELTA` 把 agent 状态推给前端渲染的;
* 客户端 `use-agui` 的 `:agent-state` / `set-agent-state!` 这条路在这台上**验证不了**;
* 我们只能拿另一台运行时或 mock 脚本来验这条链。

## 建议(按性价比排)

1. ⭐ **给模型两把写状态的工具**。上游叫 `AGUISendStateSnapshot` / `AGUISendStateDelta`
   (`CopilotKit/packages/runtime/src/agent/index.ts:1304`),keel 也用了同名 ——
   **建议照抄这两个名字**,前端那一层就不用为第三种叫法再写适配。
2. `codec` 补 `STATE_DELTA` 的出口(RFC 6902 op 数组)。
3. `/info` 加 `state` 能力格,照实报:
   `{"state":{"snapshots":true,"deltas":true}}`(keel 现在就是这么报的)。

### ⚠️ 两个坑,keel 刚踩完,别再踩一遍

我们同日在 keel 上实测到这两条,都是**一个字不报**的静默失败:

* 🔴 **`snapshot` 必须是状态对象,不是 JSON 字符串。**
  发成字符串之后,客户端的状态查看器里是一坨转义引号,更要命的是
  **后续每条 delta 都打不上**(patch 打在字符串上)。
  ⚠️ 上游客户端也不防这一手(`state = snapshot` 直接替换)。
* 🟡 **别在没发过 snapshot 的时候直接发 delta。**
  客户端 `state` 是 `{}` 时收到 `add /todos/-`,RFC 6902 要求 `/todos` 已存在 ⇒ 必然失败,
  而客户端**只会保留旧状态 + `console.warn`**(`@ag-ui/client` 的行为逐字如此)。

⭐ 上游把这两件事都放在**服务端**解掉了,代码可以直接照搬:
`CopilotKit/packages/runtime/src/agent/state-delta.ts` 的
**`createStateEventNormalizer(input.state)`** ——

* 首条 delta 之前,补一条 `input.state` 的 `STATE_SNAPSHOT`;
* `add /x/-` 而 `/x` 不存在时,前面插一条 `{"op":"add","path":"/x","value":[]}`;
* 自己留一份运行中的 state,让后续 delta 对着真实值算。

⭐ 你们这边有个现成的起点:`codec` 的注释里写着
「`state` 作为本 run 的**初始 context** 注入」—— 那正是 normalizer 要的
`input.state`,对账的材料已经在手上了。
