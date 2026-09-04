# 回执:共享状态**全条链通了** —— 而且**堵上了上游与另一台参照实现都还漏着的那个边界**

> 关联:[`…-agui-no-shared-state-tools-or-delta.md`](2026-09-04-agui-no-shared-state-tools-or-delta.md)
> 环境:`:4002`,`/info` 现在报 `state:{snapshots:true,deltas:true}`。
> 打法:curl 直连 + happy 的 `/agui` 整页,两路都走;每条都按「客户端**真的能不能打上**」验,
> 不是看有没有发。

## 逐条

### ① 缺的那一半 —— 补齐了

`AGUISendStateDelta` 出现在工具调用里(名字与上游 / 另一台参照实现一致 ⇒ 前端不用写第三种适配),
`codec` 有了 `STATE_DELTA` 出口,`/info` 有了 `state` 能力格。

### ② ⭐ 规范化是**照上游那两条逐条做的**

`state:{}` 起一轮「把「买牛奶」记进 todo 列表」,线上出来的是:

```json
{"type":"STATE_SNAPSHOT","snapshot":{}}
{"type":"STATE_DELTA","delta":[{"op":"add","path":"/todos","value":[]},
                               {"op":"add","path":"/todos/-","value":"买牛奶"}]}
```

两件事都在:**首条 delta 之前补了 `input.state` 的快照**;模型要的 `add /todos/-`
被**前插了一条建容器的 op**。这正是
`CopilotKit/packages/runtime/src/agent/state-delta.ts` 的 `createStateEventNormalizer` 那两条。

⭐ 而且 `snapshot` 是**对象**(另一台参照实现在这一格上发过 JSON 字符串,后果是
后续每条 delta 都打不上)。

### ③ 第二轮:状态往返,delta 干净打上

```
客户端发上去   {"todos":["买牛奶"]}
keel… 不,clj-agent 发下来   STATE_SNAPSHOT {"todos":["买牛奶"]} + STATE_DELTA [add /todos/- "取快递"]
客户端算出来   {"todos":["买牛奶","取快递"]}
```

### ④ ⭐⭐ 那个边界你们堵上了 —— **上游和另一台都还漏着**

请求里**完全不带 `state` 字段**时:

```json
{"type":"STATE_SNAPSHOT","snapshot":{}}
{"type":"STATE_DELTA","delta":[{"op":"add","path":"/todos","value":[]}, …]}
```

⇒ 你们把「字段缺席」与 `{}` 当成**同一件事**。
⛔ 上游不是这样:`createStateEventNormalizer` 的守卫逐字是
`!hasEmittedState && initialState !== undefined`,`state` 缺席时它**不补快照**;
另一台参照实现照抄了这个条件,于是那条路上仍会发出打不上的裸 delta。
上游撞不到只是因为它的 `AbstractAgent` 永远发 `state ?? {}`。

**这一格你们比参照实现严**,我记在这里免得以后有人「对齐上游」时又把它改回去。

### ⑤ `/threads/:id/state` **把 delta 折进去了**

```
GET /threads/…/state → {"todos":[{"text":"买牛奶","completed":false},
                                 {"text":"取快递","completed":false}]}
```

⭐ 是**当前状态**,不是「最后一份快照」。另一台参照实现的存储只记快照,
读面会落后一条 delta;你们这个语义对得上「共享状态」这个名字。

## 端到端(我们的客户端,两轮)

| | 发上去的 `state` | 收到的 | 算出来的 |
|---|---|---|---|
| 第一轮 | `{}` | `SNAPSHOT {}` + `DELTA [建容器, append]` | `{"todos":[{买牛奶}]}` |
| 第二轮 | `{"todos":[{买牛奶}]}` | `SNAPSHOT {…}` + `DELTA [append]` | 两项都在 |

⭐ 调试面板那条「STATE_DELTA 打不上」的红条**从头到尾没出现过**;
主题页的「共享状态」栏读到的是对象,与会话里那份一致。
时间线上 `AGUISendStateDelta` / `状态快照` / `状态增量` 三条各就各位。

**这一轮没有新账。** 🙏
