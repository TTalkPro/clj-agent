# MCP API

Module: `im.ttalk/clj-agent-mcp`

Source: [`modules/clj-agent-mcp/src`](../../../modules/clj-agent-mcp/src)

## Client

Namespace: `im.ttalk.agent.mcp.client`

```clojure
(require '[im.ttalk.agent.mcp.client :as client]
         '[im.ttalk.agent.mcp.transport :as transport])

(def c (client/client {:transport (transport/http-transport {:url "https://example.com/mcp"})}))
(client/connect! c)
(client/list-tools c)
(client/call-tool c "search" {"query" "clojure"})
```

| Function | Contract |
|---|---|
| `client` | Create a client; no network call yet |
| `connect!` | Negotiate protocol capabilities; idempotent |
| `list-tools` | Return the server's raw tool descriptors |
| `call-tool` | Call a tool and return its MCP result |
| `read-resource` | Read an MCP resource |
| `get-prompt` | Read an MCP prompt template |
| `instructions` | Server usage guidance suitable for a system prompt |
| `request!` | Send a named protocol request |

Transports are in `im.ttalk.agent.mcp.transport`: Streamable HTTP and stdio. A
plain `(fn [message] response)` is also a valid transport for tests.

## Server

Namespace: `im.ttalk.agent.mcp.server`

```clojure
(def s (mcp/server {:name "my-server"
                    :version "1.0"
                    :tools [weather-tool]
                    :instructions "Use tools only when needed."}))
```

`handle-message` is a pure one-message-in/one-response-out function and is the
preferred entry point for HTTP hosts. `stdio-server!` runs the stdio transport and
blocks until stdin closes. The server reports only capabilities it can actually
serve.

Protocol constants and envelopes are isolated in
`im.ttalk.agent.mcp.protocol`. It supports the modern per-request protocol era and
the legacy `initialize` handshake era.

## Tool bridging

Namespace: `im.ttalk.agent.mcp.tools`

| Function | Purpose |
|---|---|
| `connect-servers` | Connect to servers and list converted tools |
| `->inline-tool` | Convert one MCP tool descriptor to an inline tool map |
| `with-tools` | Merge converted MCP tools into an agent configuration |
| `result-text` | Render an MCP result for the LLM history |
| `app-tools` | Identify tools advertising MCP Apps UI resources |

MCP tools become ordinary inline tools from the agent loop's perspective, while
retaining annotations needed for approval and UI integration.
