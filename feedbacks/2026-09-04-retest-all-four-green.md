# 回执:四条**全部复测通过**(glm-5.3-flash)—— 外加你们多给的两样

> 关联:同日四份
> [`…-agui-drops-multimodal-content-parts.md`](2026-09-04-agui-drops-multimodal-content-parts.md)、
> [`…-info-does-not-advertise-multimodal.md`](2026-09-04-info-does-not-advertise-multimodal.md)、
> [`…-threads-delete-blocked-by-cors.md`](2026-09-04-threads-delete-blocked-by-cors.md)、
> [`…-run-finished-has-no-usage.md`](2026-09-04-run-finished-has-no-usage.md)
> 复测环境:`:4002` = `examples/copilotkit/demo_server.clj`,模型 **glm-5.3-flash**(有视觉);
> 客户端 = happy 的 `/agui` 整页 + 直连 curl 两路各打一遍。

## 一句话

**四条都实到了**,而且每条我们都用「能观察到的那一格」验的,不是看 diff 认的。

---

## ① 🔴 多模态部件 —— 通了

造一张左半 `#e11d48` / 右半白的 64×64 PNG,问「左半边是什么颜色?只回颜色两个字」。

| 路 | 结果 |
|---|---|
| 直连 `POST /agent/default/run`,`content` 是 `InputContent` 数组 | 正文 **「红色」**,途中 60 帧 `REASONING_MESSAGE_CONTENT` |
| happy UI(拖进输入框 → 发送) | 正文 **「红色」**,气泡里图片正常回显 |

⭐ 这一条**只有换了有视觉的模型才测得出来** —— 上一轮在 M2.7 上,即便管道是对的,
拿到的也是「我看不见图」。所以我们把结论钉在这里:**管道 + 模型两侧同时对了。**

## ② 🟡 `/info` 宣告多模态 —— 到了

```
default → multimodal.input {image:true, pdf:false, audio:false, video:false}
```

⭐ 关键是这一格现在**跟实测一致**:报 `image:true` 而模型真看得见。
「装配方传、库不猜」这个取舍我们照单收下 —— 它把
「我这一层做得到」与「这条链做得到」分开了,跟 keel 那边独立得出的结论是同一条。

## ③ 🟡 DELETE 的 CORS —— 开了

`Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS`。
跨源(`Origin: http://localhost:3002`)实删回 200。

## ④ 🟢 `RUN_FINISHED` 带 usage —— 有了,而且比我们要的多

```json
"usage":[{"provider":"zhipu","model":"glm-5.3-flash",
          "inputTokens":248,"outputTokens":45,"totalTokens":293,"cachedInputTokens":0}]
```

⭐ `provider` / `totalTokens` / `cachedInputTokens` 三格是我们没提的。
`cachedInputTokens` 尤其有用 —— 用量环上「缓存命中」是单独一段,
实测一轮多轮对话之后它读到 `Cached 832`,那一段第一次画出来。

---

## 你们多给的两样,我们这边跟着点亮了

### 1. `threadEndpoints.mutations: true`

上一轮我们**故意没画删除钮**(理由写在那份反馈里:摆一颗点了必失败的钮比没有更糟)。
这一格现在报 true 且 CORS 放行,于是:

* 客户端能力表加了 `:thread-mutations`,**只读声明不实探** —— 探的方式是真删一条,
  代价太大;
* 抽屉里的删除钮按这一格出现,实测「就地二次确认 → DELETE → 行消失 + 服务端列表也没了」全通;
* `POST /threads/:id` 改名也回 200(我们 UI 暂时没做改名入口,先记着)。

### 2. `suggestions: true` 那一格

建议药丸回来了,而且**跟上下文走** —— 问完那张图之后给的是
「右半边呢?」「这是哪国国旗?」「画一面类似的旗」,不是通用文案。

---

## 还剩的两格(⛔ 不是账,只是记一下)

| 格 | 现状 | 我们的看法 |
|---|---|---|
| `realtimeMetadata` | false | 合理。它要一整条云侧通道,报了就是假装有 |
| `a2uiEnabled` / `openGenerativeUIEnabled` | false | 合理。生成式 UI 我们已经搬去 demo 侧,不指望运行时给 |

**这一轮我们没有新账。** 🙏
