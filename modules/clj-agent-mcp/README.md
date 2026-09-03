# clj-agent-mcp

MCP（Model Context Protocol）的 **client + server 两侧**，实现到规范
[`2026-07-28`](https://modelcontextprotocol.io/specification/2026-07-28)，
并**向下兼容** `initialize` 握手时代的老 server / 老客户端。

**零内部依赖**：本模块不认识 agent / chat-client / 事件流。工具接入产出的是普通
map（`{:name :description :parameters :handler}`），谁要用谁 conj 进 `:tools`。

## 2026-07-28 改了什么（为什么这不是"升级"而是重写）

| | legacy（≤ 2025-11-25） | modern（≥ 2026-07-28） |
|---|---|---|
| 开场 | `initialize` 握手 + `notifications/initialized` | **没有握手**；`server/discover` 可选但服务端 MUST 实现 |
| 版本 / 能力 / 身份 | 握手协商一次，之后靠会话记住 | **每条请求自带**，在 `params._meta` 里 |
| HTTP | 有 `Mcp-Session-Id` 会话 | **无会话**；`Mcp-Method` / `Mcp-Name` / `MCP-Protocol-Version` 变必填头 |
| 结果 | 就是 result 对象 | 多一个 `resultType`（`complete` / `input_required`） |
| roots / sampling / logging | 正常特性 | **全部 deprecated**（SEP-2577） |
| 新错误码 | — | `-32020` 头不匹配 / `-32021` 缺客户端能力 / `-32022` 版本不支持 |

## 命名空间

| ns | 干什么 |
|---|---|
| `im.ttalk.agent.mcp.protocol` | **纯数据**：方法名、`_meta` 键、错误码、版本、信封构造与解析。零 I/O |
| `im.ttalk.agent.mcp.transport` | Streamable HTTP 与 stdio。**`(fn [msg] resp)` 也是合法传输** |
| `im.ttalk.agent.mcp.client` | 双时代客户端：判时代、`tools/*`、`resources/*`、`prompts/*`、翻页 |
| `im.ttalk.agent.mcp.tools` | MCP 工具 → clj-agent 内联工具（含 MCP Apps 的 UI 资源标记） |
| `im.ttalk.agent.mcp.server` | 服务端：`handle-message` 纯函数 + stdio 循环 |

## 客户端

```clojure
(require '[im.ttalk.agent.mcp.client :as mc]
         '[im.ttalk.agent.mcp.tools :as mcp-tools])

(def c (mc/client {:url "https://example.com/mcp"}))       ;; 或 {:command ["npx" "-y" "…"]}
(mc/connect! c)          ;; => {:era :modern|:legacy :protocol-version … :capabilities …}
(mc/list-tools c)
(mc/call-tool c "get_weather" {"city" "北京"})

;; 直接接进 agent：
(mcp-tools/with-tools agent-spec [{:url "https://example.com/mcp" :server-id "demo"}])
```

**判时代的规则**（照规范 PR #2844 与官方 C# SDK）：先按 modern 发一条
`server/discover`——

- 成功且版本对得上 → modern；
- `-32021` / `-32020` → **原样抛，不回退**（那是"对面确实是 modern，只是我们发的
  东西它不收"，退回握手治不好，只会把真错藏起来）；
- `-32022` → 按 `data.supported` 重挑一个版本；只剩 legacy 版就退回握手；
- **其他任何** JSON-RPC 错误 / HTTP 400 / 404 / 超时 → 退回 `initialize`。
  判据不能只认一个错误码，这是规范明写的。

时代是 **server 的属性**，判一次记住，后续请求都按它走。

## 服务端

```clojure
(require '[im.ttalk.agent.mcp.server :as srv])

(def s (srv/server {:name "clj-agent" :version "0.3"
                    :instructions "这台机器上的工具。"
                    :tools [{:name "get_weather" :description "查天气"
                             :parameters {"type" "object" …}
                             :handler (fn [args ctx] "晴")}]}))

(srv/stdio-server! s)          ;; 阻塞
(srv/handle-message s msg)     ;; 或者自己接到别的传输上——纯函数
```

同一个 `handle-message` **同时服务两种客户端**，靠请求自己的形状分流
（带 modern `_meta` → 无状态语义；`initialize` → legacy 语义）。

**HTTP 绑定不在模块里**（`design-principles` §2：web 服务端依赖不进库）。
接线三步见 [`examples/mcp/server_example.clj`](../../examples/mcp/server_example.clj)：
先 `check-headers`（三个头要与信封一致，不一致回 `-32020`）→ `handle-message`
→ 通知返回 nil 时回 202 空体。

## 几条要知道的

1. **传输就是一个函数**。`(fn [msg] resp)` 就是合法 `Transport`——测试因此不起服务、
   不起进程，一个纯函数把整条双时代逻辑跑完；
2. **连接发生在装配期**。`connect-servers` 会真的去连、去 `tools/list`：server 挂了
   在起服务时就知道，而不是每轮对话多一次网络往返。代价是 server 中途加工具要重启；
3. **工具失败 ≠ 协议失败**。`isError` 是"活没干成"（回给模型让它换个说法），
   JSON-RPC 错误是"我们之间说话出了问题"（抛出来）。两者在客户端与服务端都分开；
4. **服务端默认不外泄异常消息**。对面是外部客户端，异常消息里常有路径与内部结构；
   缺省只回类名，完整消息进日志。本地开发用 `:expose-error-messages? true`；
5. **没做**：MRTR 的多轮状态机（`input_required` 结果原样返回，`protocol/input-required?`
   可判）、`subscriptions/listen` 长流、OAuth（`:headers` 自己塞 token）。
   server→client 的请求（elicitation）只在 stdio 上到得了——HTTP 那侧要从 SSE 流里
   持续读，本模块的 HTTP 传输只取第一条 `data:`。

## 测试

```bash
clojure -M:test                 # 模块内
clojure -M:test --focus im.ttalk.agent.mcp.server-test   # 含 client↔server 往返
```

往返测试把 client 的传输直接接到 server 的 `handle-message` 上——两侧对不上的地方，
它比任何一侧的单测都先红。
