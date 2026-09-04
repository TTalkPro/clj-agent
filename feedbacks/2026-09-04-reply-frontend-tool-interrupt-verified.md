# 回执:两类 interrupt **分开了** —— 三个维度都能判,拒绝语义也回来了

> 关联:[`…-frontend-tool-pause-looks-like-an-approval.md`](2026-09-04-frontend-tool-pause-looks-like-an-approval.md)
> 环境:`:4002`。打法:curl 看两类的形状 + happy 整页把两支都点一遍。

## ① 建议的三条你们全做了(我原本以为会挑一条)

| | 前端工具 | 真审批(`:sensitive`) |
|---|---|---|
| `reason` | `需要客户端执行并回传结果: generateSandboxedUi` | `需要审批: wipe-database` |
| `responseSchema` | `{"result":{"description":"这次工具调用的结果（客户端执行后回传）"}}` | `{"decision":{"enum":["approved","rejected"]}}` |
| `metadata.kind` | `frontend-tool` | `approval` |

⭐ **三个维度各自独立可判**,我们优先认 `metadata.kind`(你们明说的),
没有它时按 `responseSchema` 的属性名推 —— 这样对没升级的运行时也不会瞎。
⭐⭐ `responseSchema` 那一条尤其省事:**客户端照 schema 填就自然回对了**,
不用把「这是哪一类」这件事编进客户端的 if。

## ② 拒绝语义:回来了

我们这边跟着改成**照 schema 拼载荷**(先前一律发 `{decision}`)。两支实测:

```
前端工具 · 拒绝  → resume [{status:"cancelled", payload:{"result":"用户拒绝了这次调用，未执行"}}]
   模型:「看起来用户的工具调用被取消了……你可以重新尝试」        ✅ 读懂了
前端工具 · 执行  → resume [{status:"resolved",  payload:{"result":"用户已确认"}}]
   模型:「卡片已生成！标题：今天 正文：晴，26 度」                ✅ 继续跑
真审批   · 批准/拒绝 → 仍按 {decision} 走,行为未变                ✅
```

⚠️ 对照一下改之前:同样点「拒绝」,模型的原话是
**「我刚才已经调用了这个工具并获得了成功响应」** —— 空载荷被当成空结果。
这一条现在两侧都堵上了:你们把 schema 说清楚,我们照 schema 填。

## ③ 我们这边同一次改的另外两处(与你们无关,记在这里让链条完整)

* **文案跟着分**:前端工具那条现在写「这一步要在你这边执行：X」、按钮是
  「执行 / 拒绝」;真审批仍是「服务端在等你批准：…」「批准 / 拒绝」。
  ⛔ 全写成「服务端在等确认」会让人以为服务端在替他做主。
* **沙箱等批准再跑**:它原本跟着 `TOOL_CALL_ARGS` 边流边画,而 interrupt 要到
  `RUN_FINISHED` 才到 ⇒ iframe 在按钮出现之前就挂上去了,两颗钮纯装饰。
  现在等审批只画一句说明,拒了就永远不跑。
  ⇒ 通则:**渲染器拿到 `interrupt` 时一个副作用都不能有。**

**这一轮没有新账。** 🙏
