# **前端工具的暂停**被报成「需要审批」,与真审批在 wire 上一模一样 ⇒ 客户端分不出该回决策还是回结果

## 症状

前端声明一个 `generateSandboxedUi`(纯客户端工具,服务端没有同名实现),
模型调它 ⇒ `RUN_FINISHED` 带的是:

```json
{"type":"interrupt","interrupts":[{
  "id":"call_function_lsz04quapzs7_1",
  "reason":"需要审批: generateSandboxedUi",
  "responseSchema":{"type":"object","properties":{
      "decision":{"type":"string","enum":["approved","rejected"]}},"required":["decision"]},
  "toolCallId":"call_function_lsz04quapzs7_1",
  "metadata":{"pendingTool":{"name":"generateSandboxedUi","args":{…}}}}]}
```

⛔ 这与 `wipe-database`(真的 `:sensitive` 服务端工具)那条**逐字段同形**:
同样的 `需要审批: ` 前缀、同样的 `decision: approved|rejected` schema。
**客户端没有任何字段能把两者分开。**

## 为什么这不只是文案问题

你们自己的路由里,这两类的 `resume[]` 走的是**完全不同的两支**
(`examples/copilotkit/http_kit_routes.clj:265` 与 `:272`):

| 挂起的是 | `resume[].payload` 被当成 |
|---|---|
| 服务端 `:sensitive` 工具 | **决策**(`resume-decision`:approved / rejected) |
| **前端工具** | **这次调用的结果**(`:reply` + `{:message (str payload)}`,ask-user 语义) |

⇒ 客户端按「这是一次审批」去回 `{"status":"cancelled"}`(取消不需要载荷,协议里
`payload` 本来就是可选的),落到第二支就变成:

```clojure
(rt/resume-run! rt conv "reply" {:message (str (or nil nil))})   ; => ""
```

**空字符串成了这次工具调用的结果。** 实测后果:用户点了「拒绝」,模型下一句是

> 我刚才已经调用了这个工具并**获得了成功响应**。已生成卡片……

⇒ 🔴 **拒绝在语义上被吃掉了**,而且是往「成功」的方向吃。

## 怎么撞上的

1. 前端 `tools` 里声明 `generateSandboxedUi`(不带服务端实现);
2. 「用 generateSandboxedUi 画一张卡片」;
3. 收到上面那条 interrupt ⇒ 界面按审批渲染「批准 / 拒绝」;
4. 点「拒绝」,回 `resume:[{interruptId, status:"cancelled"}]`;
5. 模型宣布成功。

## 影响面

🔴 不报错。任何按 AG-UI 标准把 interrupt 渲染成审批的客户端都会撞上:
**「拒绝」这一路对前端工具无效**,而 UI 上它看起来生效了。

⚠️ 我们这一轮**自己也补了一刀**(不是替你们兜底,是我们本来就该做):拒绝时
一并带 `payload`,于是落到 `:reply` 那支时模型能读到「用户拒绝了这次调用,未执行」。
实测改完之后模型不再宣称成功。⭐ 但这只是让**我们这一个**客户端不再踩,
分不出两类这件事本身还在。

## 建议(任选,按侵入性从小到大)

1. ⭐ **最小**:前端工具那条 interrupt 换一个 `reason` 前缀,别叫「需要审批」——
   它要的不是批准,是**客户端去执行**。
2. ⭐⭐ **推荐**:`responseSchema` 如实反映那一支要什么。
   前端工具要的是**结果**,不是 `decision` 枚举 ⇒ 给一个
   `{"type":"object","properties":{"result":{}}}` 之类的形状,
   客户端照 schema 填就自然回对了。
   ⭐ 顺带把 `metadata` 里加一格 `{"kind":"frontend-tool" | "approval"}` 最省事。
3. **可选**:前端工具压根**不必**走 interrupt —— AG-UI 里 client-side tool 的常规路径
   就是「服务端发 TOOL_CALL_*、收口、客户端下一轮把 tool 结果带回来」,
   你们路由里那条 `resume-entry` 之外的分支(`pending + tool-result`)已经支持它了。
   ⛔ 但这条改动最大,而且会动到既有客户端,不一定划算。

## ⭐ 顺带一条**我们自己的**账,写在这里给别的宿主看

同一次联调里我们还发现:沙箱 UI 是跟着 `TOOL_CALL_ARGS` **边流边画**的,
而 interrupt 要到 `RUN_FINISHED` 才到 ⇒ **iframe 在审批条出现之前就已经挂上去、
模型写的 JS 也跑完了**,「批准 / 拒绝」两颗钮纯装饰。
这条与你们无关(客户端渲染时序),但它与上面那条叠在一起,才凑出用户看到的
「审批完全没用」。⇒ 规矩是:**渲染器拿到 `interrupt` 时一个副作用都不能有。**
