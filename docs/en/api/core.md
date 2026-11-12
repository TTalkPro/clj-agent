# Core API

Module: `im.ttalk/clj-agent-core`

Source: [`modules/clj-agent-core/src`](../../../modules/clj-agent-core/src)

## Facade

Namespace: `im.ttalk.agent`

The facade exposes the most common core operations without requiring callers to
know every internal namespace.

| Function | Contract |
|---|---|
| `chat` | One LLM call through the `:chat` filter chain; no tool loop |
| `chat-stream` | One streaming LLM call; opts must contain `:on-token` |
| `chat-client` | Build a ChatClient from a declarative map |
| `chat-model` | Create a ChatModel from an `ILLMProvider` |
| `call-model` | Call a ChatModel directly, bypassing filters |

## ChatClient

Namespace: `im.ttalk.agent.chat-client`

```clojure
(require '[im.ttalk.agent.chat-client :as chat-client])

(def client
  (chat-client/build-chat-client
    {:chat-model model
     :tools [#'get-weather]
     :filters [my-filter]
     :tool-manager (react/sequential-tool-calling-manager)}))
```

Important `build-chat-client` options:

| Key | Meaning |
|---|---|
| `:chat-model` | Required; the configured `IChatModel` |
| `:tools` | Vector of `deftool` vars or inline tool maps |
| `:filters` | Ordered filter definitions |
| `:tool-manager` | Tool batch execution engine |
| `:settings` | Base settings merged into each ChatRequest |
| `:state-slots` | Typed, barrier-folded state slots for parallel tools |

Invoke API:

| Function | Returns | Notes |
|---|---|---|
| `invoke-chat` | `ChatClientResponse` | Normal, non-streaming `:chat` chain |
| `invoke-chat-stream` | `ChatClientResponse` | Token callback driven by `:on-token` |
| `invoke-tool` | Normal tool result | Executes one named tool on the `:tool` chain |
| `invoke-chat-async` | Deferred async chain result | Async variant of `invoke-chat`; response filters must use `fmap`/`fbind` |
| `invoke-chat-stream-async` | Deferred async chain result | Async streaming variant |

## ChatModel

Namespace: `im.ttalk.agent.chat-model`

`create-chat-model` wraps an `ILLMProvider` and retry policy in the ChatModel
contract used by ChatClient. `FnChatModel` accepts `:chat-fn` and optional
`:stream-fn`, making it the adapter point for tests and custom transports.
`IAsyncChatModel` is optional; it is discovered with `satisfies?` and falls back
to a virtual thread when absent.

## Tools

Namespace: `im.ttalk.agent.tool`

`deftool` creates a normal Clojure function and attaches an LLM tool schema to its
var.

```clojure
(require '[im.ttalk.agent.tool :refer [deftool]])

(deftool get-weather
  "Get current weather for a city."
  [[city :string "City name"]
   [unit :string "Temperature unit" :default "celsius"]]
  {:timeout 5000}
  (format "%s: 24 %s" city unit))
```

Common tool metadata keys:

| Key | Effect |
|---|---|
| `:sensitive true` | Subject to approval when approval is enabled |
| `:timeout ms` | Enforced execution timeout |
| `:serial true` | Keep this tool out of parallel batches |
| `:return-direct true` | Return the tool result as the final answer |
| `:retry policy` | Opt into idempotent retry behavior |
| `:context true` | Add a read-only context argument to the function |

Parameter types are `:string`, `:int`, `:float`, `:boolean`, `:array`, and
`:object`. Arguments are fully validated against the generated JSON Schema before
the function is invoked.

## Filters

Namespace: `im.ttalk.agent.filter`

Filters are assembled once and precompiled into five chains:

| Chain | Scope |
|---|---|
| `:chat` | One ChatModel request/response |
| `:tool` | One tool invocation |
| `:turn` | The whole ReAct turn |
| `:iteration` | One LLM response plus its tool batch |
| `:token-xform` | Outbound streaming token transducer |

Create a filter with `create-filter`, compose chain results with `fmap`, `fbind`,
and `fcatch`, and replace filters on an existing client with `with-filters`.

## Neutral model data

The `im.ttalk.agent.model.*` namespaces define the stable boundary:

- `model`: `ILLMProvider`, optional async provider protocol.
- `model.message`: system/user/assistant/tool message constructors and accessors.
- `model.response`: normalized `ChatResponse`, text, finish reason, and usage.
- `model.request`: `ChatRequest` and immutable option helpers.
- `model.content`: multimodal text/image/file/audio parts.
- `model.error`: canonical error constructors, predicates, and formatting.
- `model.embedding`: optional embedding provider protocol.

Keep application and adapter code on these types whenever possible; vendor wire
maps should not leak above the provider layer.
