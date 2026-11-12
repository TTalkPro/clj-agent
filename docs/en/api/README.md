# API Reference Directory

This directory is the English API map for `clj-agent`. It is organized by module
so that readers can move from a use case to the owning namespace without reading
vendor-specific wire details.

Each page links to source files for exact arities and docstrings. A published
cljdoc build exposes the same namespaces in its **API** tab.

## Directory

| Page | Module | Start here when you need to... |
|---|---|---|
| [Core](core.md) | `im.ttalk/clj-agent-core` | Define tools, call a model, build a ChatClient, or write filters |
| [Client](client.md) | `im.ttalk/clj-agent-client` | Maintain state, run the tool loop, stream, persist memory, or approve tools |
| [Provider](provider.md) | `im.ttalk/clj-agent-provider` | Configure OpenAI, Anthropic, Gemini, Ollama, DashScope, or compatible endpoints |
| [AG-UI](agui.md) | `im.ttalk/clj-agent-agui` | Run agents detached from HTTP requests and emit reconnectable events |
| [MCP](mcp.md) | `im.ttalk/clj-agent-mcp` | Connect to MCP servers or expose agent tools through MCP |
| [Server](server.md) | `im.ttalk/clj-agent-server` | Serve AG-UI/MCP endpoints as Ring handlers or host them on Jetty |
| [Tools and HTTP](tools-and-http.md) | `im.ttalk/clj-agent-tools`, `im.ttalk/clj-http` | Give agents workspace/file capabilities or make standalone HTTP calls |

## Recommended reading order

1. [Core API](core.md): understand `deftool`, `ChatModel`, and `build-chat-client`.
2. [Client API](client.md): use SimpleAgent unless you need explicit loop control.
3. [Provider API](provider.md): choose and configure the provider adapter.
4. Optional integration APIs: [AG-UI](agui.md), [MCP](mcp.md), and
   [Server](server.md).

## Public surface policy

The framework intentionally distinguishes the primary API from internal helpers:

- **Primary API**: facade functions, SimpleAgent, ChatClient constructors and
  invoke functions, tool/filter creation, provider factories, store constructors,
  runtime/server constructors, and protocols intended for extension.
- **Implementation namespaces**: wire converters, stream parsers, and provider
  support namespaces are documented here only when an application must select or
  inject them explicitly.
- **Stable data contracts**: neutral messages, canonical errors, tool metadata,
  pause snapshots, and AG-UI events. Prefer these over vendor-shaped maps.

For the complete generated symbol list, read the cljdoc API tab for the selected
release, or inspect the linked `src` directory in this repository.
