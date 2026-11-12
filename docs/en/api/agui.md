# AG-UI API

Module: `im.ttalk/clj-agent-agui`

Source: [`modules/clj-agent-agui/src`](../../../modules/clj-agent-agui/src)

## Runtime

Namespace: `im.ttalk.agent.agui.runtime`

The runtime decouples an agent run from the HTTP request that started it. Runs
continue when the client disconnects, can be stopped from another request, and can
resume after human approval.

| Function | Contract |
|---|---|
| `runtime` | Create an explicitly scoped runtime; no global singleton |
| `start-run!` | Start a run and return immediately |
| `resume-run!` | Resume a paused run by conversation id |
| `subscribe` | Subscribe from `:since`; returns an unsubscribe function |
| `stop!` | Register cancellation; wait for the terminal event |
| `run-status` | Inspect current status and cancellation reason |
| `buffered-events` | Read currently retained events |
| `forget!` / `purge!` | Evict a conversation or delete retained events |
| `shutdown!` | Stop active runs and close subscriptions |

Subscriptions and events are transport-neutral. The web module is responsible for
SSE encoding, routing, CORS, and HTTP concerns.

## Event model

Namespace: `im.ttalk.agent.agui.event`

Within a conversation, event `:seq` values are monotonic with no gaps. A run ends
with exactly one terminal event. Events are neutral maps such as:

| Type | Meaning |
|---|---|
| `:run/started` | Run began |
| `:message/started` | Assistant or reasoning message began |
| `:message/delta` | Incremental text token |
| `:message/thinking` | Incremental reasoning token |
| `:message/ended` | Message complete |
| `:tool/started` / `:tool/args` / `:tool/result` / `:tool/ended` | Tool lifecycle |
| `:state/snapshot` / `:state/delta` | Shared application state |
| `:run/paused` | Terminal pause state awaiting approval |
| `:run/finished` / `:run/cancelled` / `:run/error` | Terminal outcomes |

`stop!` returning `true` means cancellation was registered, not that execution has
already stopped. JVM tools are cooperative; a running tool completes, and the
terminal `:run/cancelled` event is the authoritative signal.

## AG-UI integration

- `im.ttalk.agent.agui.codec`: neutral events/messages to and from AG-UI payloads;
  also parses `RunAgentInput` and builds `/info`.
- `im.ttalk.agent.agui.tools`: package an agent factory and handle AG-UI frontend
  tools.
- `im.ttalk.agent.agui.registry`: named multi-agent registry and lookup.
- `im.ttalk.agent.agui.emit`: attach token/filter/LLM callbacks to the event
  emitter.
- `im.ttalk.agent.agui.state`: JSON Pointer lookup and JSON Patch normalization.
- `im.ttalk.agent.agui.thread-store`: thread metadata protocol and in-memory or
  SQLite stores.
- `im.ttalk.agent.agui.event-store`: pluggable event retention protocol with an
  in-memory ring default.

History is server-authoritative. The client-supplied `messages` array is used only
for the final user input; the runtime must not replace persisted server history
with client state.
