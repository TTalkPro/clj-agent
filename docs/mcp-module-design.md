# `clj-agent-mcp` 设计：按 2026-07-28 实现 MCP 的两侧

> 规范：[`modelcontextprotocol.io/specification/2026-07-28`](https://modelcontextprotocol.io/specification/2026-07-28)
> （SEP-2575 + SEP-2567 + SEP-2577）。实现细节的第二来源是官方 **C# SDK**
> （`~/workspace/csharp-sdk`）——它已跟到这一版，且把「为什么这么做」写在注释里。

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| 建了什么 | 独立模块 `clj-agent-mcp`：client + server 两侧、Streamable HTTP + stdio、**双时代** |
| 为什么不是升级 | 2026-07-28 **删掉了 `initialize` 握手**，改成每请求 `_meta` + 无会话 HTTP。原来那份 356 行的 `agui/mcp.clj` 是 legacy 时代的实现 |
| 分层 | 协议归 `clj-agent-mcp`；MCP Apps 的前端约定（activity 事件、UI 代理）留在 `agui.mcp`。判据同 genui 那次：**协议认不认** |
| 依赖 | **零内部依赖**；JSON + 日志 + JDK。HTTP 客户端走 `java.net.http`，**HTTP 服务端绑定不进模块** |
| 验证 | 41 个单测 + client↔server 往返 + **两次真 HTTP 端到端**（modern 打我们自己的 server；legacy 打仓里那台老 server 验回退） |

---

## 1. 2026-07-28 到底改了什么

一句话：**MCP 从有状态会话协议变成了无状态请求协议**。

| | legacy（≤ 2025-11-25） | modern（≥ 2026-07-28） |
|---|---|---|
| 开场 | `initialize` + `notifications/initialized` | **没有握手**；`server/discover` 可选（服务端 MUST 实现） |
| 版本 / 能力 / 身份 | 握手协商一次，之后靠会话 | **每条请求自带**（`params._meta` 的三个 `io.modelcontextprotocol/*` 键） |
| HTTP | `Mcp-Session-Id` | 无会话；`Mcp-Method` / `Mcp-Name` / `MCP-Protocol-Version` 必填且**必须与信封一致** |
| 结果 | result 对象 | 多一个 `resultType`（`complete` / `input_required`，后者是 MRTR） |
| roots / sampling / logging | 正常特性 | **全部 deprecated**（SEP-2577） |
| 错误码 | JSON-RPC 标准码 | 多三个：`-32020` / `-32021` / `-32022`，且 `-32000..-32019` 段作废 |

规范原文里最要紧的一句：**"服务器不得依赖同一连接上的先前请求建立上下文"**。
所以「每条请求都带版本与能力」不是冗余，是协议要求。

---

## 2. 四问（design-principles §1.2）

| 问题 | 答 |
|---|---|
| 现在有人要用吗？ | 有。仓里已经有一份 MCP 客户端在跑（`agui/mcp.clj`），但它停在 2025-06-18；而生态已经开始按 2026-07-28 发 server |
| 不建的话怎么办？ | 客户端侧：老实现连不上 modern server（它没有 `initialize`）。服务端侧：**做不到**——把 clj-agent 的工具暴露给 Claude Desktop 之类的 host，今天没有任何路径 |
| 换来什么？ | **能力**：连得上两个时代的 server；以及「clj-agent 可以当 MCP server」这件从前不存在的事 |
| 触发条件写得出吗？ | 已经发生：规范 2026-07-28 已发布且官方 SDK 已跟进 |

---

## 3. 设计

### 3.1 分层：五个 ns，越往下越纯

```
protocol.clj   纯数据：方法名 / _meta 键 / 错误码 / 版本 / 信封构造与解析   ← 零 I/O
transport.clj  Streamable HTTP + stdio；`(fn [msg] resp)` 也是合法传输
client.clj     判时代 + 特性方法 + 翻页
tools.clj      MCP 工具 → clj-agent 内联工具
server.clj     handle-message（纯函数）+ stdio 循环
```

**零内部依赖**是刻意的：工具接入产出的是普通 map（`{:name :description :parameters
:handler}`），那是 clj-agent 的内联工具**约定**，不需要 require 任何东西就能满足。
于是这个模块可以被别的 Clojure 项目单独拿走用。

### 3.2 判时代：规则集中在一个函数里

`client/probe-modern` 是唯一做判定的地方。散开写的话，「哪些错该回退」这条规则会在
三处各长一份，然后慢慢长歪。判据表见模块 README。

两条**反直觉但规范明写**的：

1. **`-32021` / `-32020` 不回退**。它们说明对面**就是** modern，只是我们发的东西
   它不收；退回 `initialize` 治不好，只会把真正的错误藏起来；
2. **回退判据不能只认一个错误码**（规范 PR #2844）。`-32601` / `-32602` / `-32700`
   / 别的任何错误、HTTP 400/404、超时——都算 legacy 信号。

### 3.3 三个头与信封必须是同一个值的两次投影

`MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` 与 body 不一致，server 回
`-32020`。所以传输层**从信封反读**这三个值（`protocol/protocol-version-of`、
`protocol/routing-name`），而不是自己记一份——两处各记一份就是 `-32020` 的来源。

对称地，服务端把这条校验做成 `server/check-headers` **交出去**：规则属于协议，
但只有 web 层看得见头。

### 3.4 服务端不碰 web

`handle-message` 是纯函数（一条进、一条出，通知返回 nil）。HTTP 绑定在
`examples/mcp/server_example.clj`，三步接线在那儿有注释。stdio 循环留在模块内
——它不碰 web。这与刚做完的 genui 外移是同一条线。

### 3.5 施工时才看清的三处

1. **`protocol/request` 不能按版本号猜要不要写 `_meta`**。初版加了道
   `(modern? version)` 守卫，结果是**探测一个没列进 `modern-versions` 的新版本时
   `_meta` 被静默丢掉**——请求悄悄退化成 legacy 形状、server 按老语义服务，两边都
   不报错。改成「调用方给了版本就写」，时代由 `client` 判。有测试钉住；
2. **MCP Apps 的键是 `_meta.ui.resourceUri`（嵌套对象）**，不是老实现里那个扁平的
   `ui/resourceUri`，也不是我一开始按命名惯例拍的 `io.modelcontextprotocol/ui-resource-uri`。
   查规范才定下来。猜错**不会报错**，只会「明明是 App 工具却没画出界面」——所以
   两种写法都认；
3. **服务端默认不外泄工具异常消息**。初版写了注释说「不回栈」，但代码把
   `(.getMessage t)` 原样发了出去——测试当场把这条谎话照出来。现在缺省只回类名，
   完整消息进日志，`:expose-error-messages?` 显式打开才回。理由与研究 CopilotKit 时
   记下的那条一致（agno 的 `_delegate` 只回异常类名，因为 provider 错误串里可能有
   URL / request-id）。

---

## 4. 明确不做（否决记录）

| # | 不做 | 理由 | 重启条件 |
|---|---|---|---|
| 4.1 | MRTR 的多轮状态机 | `input_required` 结果**原样返回**，`protocol/input-required?` 可判。把整套状态机做出来是替想象中的用户写代码 | 出现真的要 server 中途问参数的场景 |
| 4.2 | `subscriptions/listen` 长流 | 本传输只做请求/响应。长流要重做传输的读端与背压 | 有 server 真的推订阅事件时 |
| 4.3 | HTTP 上的 server→client 请求（elicitation） | 那要从 POST 响应的 SSE 流里持续读；stdio 侧已经能收（读线程交给 `:on-notification`） | 同 4.2，一并做 |
| 4.4 | OAuth / 授权框架 | MCP 的 auth 只管 HTTP，而 token 怎么来是调用方的事：`:headers` 自己塞 | 出现需要 SDK 走完整 OAuth 流程的场景 |
| 4.5 | roots / sampling / logging | 2026-07-28 起 deprecated（SEP-2577）。**新建的东西没有理由去实现一个已经废弃的特性** | 永不（除非只为连某个老 server，且那时也只做客户端侧） |
| 4.6 | `notifications/tools/list_changed` 热更新 | 连接在装配期完成，工具集固定。热更新要引入「工具表会变」这件事，牵动 TCM 的装配 | server 真的在运行中改工具集时 |

---

## 5. 验证

| 层 | 怎么验 |
|---|---|
| 信封 / 错误码 / 版本 | `protocol_test`：字段名逐条对着规范与 C# SDK |
| 判时代 | `client_test`：8 种「对面这样答」的场景，含两个**不回退**的信号 |
| 工具接入 | `tools_test`：schema 原样、UI 资源两种写法、一个 server 挂了不拖垮别的 |
| 服务端 | `server_test`：`_meta` 校验、`-32022` 带 supported、工具错误 vs 协议错误、stdio 循环 |
| **两侧一致** | `server_test` 的往返：client 的传输直接接到 `handle-message`，modern / legacy 各一遍 |
| **真 HTTP** | [`examples/mcp/live_test.clj`](../examples/mcp/live_test.clj)：modern 打我们自己的 server（11 项）；legacy 打仓里那台老 server 验回退（6 项，协商到 2025-11-25 后正常调工具） |

真 HTTP 那份不进 `clojure -M:test` —— 与仓里既有的 live 测试同一个待遇（要起服务）。
但它**不需要任何 API key**，两台 server 都在本地起，所以随时可跑：

```bash
clojure -J-Dclj-agent.embedded-examples=1 -M:mcp -m mcp.live-test
```

它证的是单测证不到的东西：三个必填头发对没有、状态码分得对不对、JSON/SSE 两种
响应体认不认、以及**双时代回退在真网络上走不走得通**。

---

## 相关文档

- [`design-principles.md`](design-principles.md) —— §1 四问、§2 框架无关（web 依赖不进库）
- [`agent-runtime-design.md`](agent-runtime-design.md) —— agui 模块与事件流；MCP Apps 的 activity 事件走那条路
- `modules/clj-agent-mcp/README.md` —— 用法与 API
