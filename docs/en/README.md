# clj-agent Documentation

English documentation for `clj-agent`, a Clojure AI Agent framework.

English root README: [`README_EN.md`](../../README_EN.md) · 中文: [`README.md`](../../README.md)

## Documentation map

| Document | Description |
|---|---|
| [Introduction](#introduction) | Architecture, packaging, and first steps |
| [API reference directory](api/README.md) | Public API surfaces grouped by module |
| [Core API](api/core.md) | Facade, ChatClient, ChatModel, tools, filters, messages |
| [Client API](api/client.md) | SimpleAgent, ReAct loop, memory, HITL, subagents |
| [Provider API](api/provider.md) | Provider factory, supported providers, options |
| [AG-UI API](api/agui.md) | Detached runs, event streams, and AG-UI integration |
| [MCP API](api/mcp.md) | MCP clients, servers, transports, and tool bridging |
| [HTTP server API](api/server.md) | Ring handlers, routes, SSE, and Jetty hosting |
| [Tool and HTTP utilities](api/tools-and-http.md) | Workspace tools and the standalone HTTP client |
| [Design documents](../README.md) | Implementation notes and design history (currently Chinese) |

## Introduction

`clj-agent` separates the framework into small modules:

- `clj-agent-core` defines neutral message, provider, ChatModel, ChatClient, tool,
  and filter contracts.
- `clj-agent-client` implements the stateful agent runtime, ReAct tool loop, memory,
  pause/resume, and subagents.
- `clj-agent-provider` contains adapters for OpenAI-compatible, Anthropic, Gemini,
  DashScope, and other providers.
- Optional modules add AG-UI, MCP, server bindings, coding tools, and a standalone
  HTTP client.

The architecture is dependency-inverted: client and provider code depend on the core
contracts, not on each other. Consequently, an application can replace the provider
implementation without changing agent or ChatClient code.

```clojure
(require '[im.ttalk.agent.simple-agent :as sa]
         '[im.ttalk.agent.provider.factory.builder :as factory])

(def provider (factory/create-provider-from-env :openai))
(def agent (sa/create-agent
             {:provider provider
              :model "gpt-4"
              :system-prompt "You are a helpful assistant."}))

(sa/chat agent "Explain Clojure protocols in one paragraph.")
```

See [`README_EN.md`](../../README_EN.md) for installation methods, complete
quick-start examples, provider configuration, and advanced concepts.

## API conventions

- Public entry points are in namespaces beginning with `im.ttalk.agent`.
- Functions accept maps of keywords; optional keys are documented on each page.
- Normalized responses use neutral Clojure data rather than vendor JSON.
- Errors are canonical maps with `:type`, `:message`, and `:retryable?`; provider
  I/O failures throw `ex-info` with this map as its data.
- This repository treats source docstrings as the source of truth for detailed
  arities. The pages below identify stable entry points and their contracts.

## Related resources

- [Architecture overview](../../README_EN.md#architecture-overview)
- [Quick start](../../README_EN.md#quick-start)
- [Core concepts](../../README_EN.md#core-concepts)
- [Provider support](../../README_EN.md#llm-providers)
- [Development](../../README_EN.md#development)
