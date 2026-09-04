# AG-UI 层不认 `InputContent` 部件 —— 图片被 `(str content)` 压成一串 data URI

## 症状

带图片的用户消息发过去，**一个字都不报**，模型回：

> 抱歉，我无法直接访问或查看您提供的**图片链接**。

模型的 thinking 里说得更明白：「从提供的 **URL** 来看，这是一张 PNG 图片」——
它收到的是一串 `data:image/png;base64,…` 的**文本**，不是图片。

## 位置

`modules/clj-agent-agui/src/im/ttalk/agent/agui/codec.clj`

- `parse-run-input`：`:message (or (:content last-user) (get last-user "content"))`
  —— content 是数组时原样往下传
- `agui->messages`：`"user" (msg/user (str content))` —— `/suggest` 那条路直接 `str`

## 怎么撞上的

AG-UI 的多模态用户消息，`content` 不是字符串而是 `InputContent` 数组
（`ag-ui` 的 `sdks/python/ag_ui/core/types.py`：`TextInputContent` /
`ImageInputContent` / …）：

```json
"content": [
  {"type":"text","text":"这张图左半边是什么颜色？"},
  {"type":"image",
   "source":{"type":"data","value":"<base64>","mimeType":"image/png"},
   "metadata":{"filename":"half.png"}}
]
```

最短复现（不用前端）：

```bash
curl -s -X POST http://localhost:4002/api/copilotkit/agent/default/run \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"threadId":"t","runId":"r","messages":[{"role":"user","content":[
       {"type":"text","text":"这张图左半边是什么颜色"},
       {"type":"image","source":{"type":"data","value":"<base64>","mimeType":"image/png"}}]}],
       "tools":[],"context":[]}'
```

## 影响面

- **谁会撞**：任何按协议发多模态的客户端（CopilotKit 的 `useAttachments`、
  我们的 `happy.agui.attachments`）。
- **撞了会怎样**：内容**全丢**，而且**不报错** —— 前端看到的是一句「我看不到图片」，
  会先去怀疑自己的编码、再怀疑模型，最后才想到中间这一跳。
- ⭐ 值得强调：**内核那一侧本来就是好的**。`im.ttalk.agent.model.content` 就是
  一套中立多模态部件，`provider/wire/anthropic.clj:47` 与 `wire/openai.clj:54`
  都会把 `:file` 翻成各家的 image 块。**缺的只有 AG-UI 那一跳**。

## 建议

在 `codec` 里加一个 `agui-content->neutral`，两个调用点（`parse-run-input` 的
`:message`、`agui->messages` 的 `user` 分支）各调一次：

| AG-UI | 中立 |
|---|---|
| `{type:"text", text}` | `content/text-part` |
| `{type:"image"\|"audio"\|"video"\|"document", source:{type:"data", value, mimeType}}` | `content/file-part` + `:data` |
| 同上但 `source.type = "url"` | `content/file-part` + `:url` |

两条要留神的：

1. **字符串 content 原样返回** —— 纯文本消息的形状一个字都不能变，否则每个下游
   都得先学会拆部件才能收一句「你好」。
2. `file-part` 对内联数据强制要 `:media-type`（它自己抛 `:validation-error`），
   而 AG-UI 的 `data` 源本来就带 `mimeType`，正好对上。

⭐ **补丁已经在工作树里了**（未提交，仓主自行取舍）：`codec.clj` 的
`agui-content->neutral` + `part-value`，以及 `codec_test.clj` 里 3 个 deftest
（纯文本不变形、data/url 两档、document 归 `:file`、字符串 key、混排顺序，
外加 `parse-run-input` / `agui->messages` 两条路）。全仓库 567 tests / 2673
assertions 绿。

## 顺带一条实话（不是 clj-agent 的问题）

补完之后**端到端仍然看不见图**，但**病因不在这里**了：直接打
`https://api.minimaxi.com/anthropic/v1/messages`（完全绕开 clj-agent），
带标准的 `{"type":"image","source":{"type":"base64",…}}` 块，
`MiniMax-M2.7` 自己回「我看不到任何图片」、thinking 里说「没有提供任何图片链接
或图片描述」——**那个端点把 image 块静默丢了**。所以这条反馈只到「翻译缺失」为止；
换一个真支持视觉的模型才验得到最后一公里。
