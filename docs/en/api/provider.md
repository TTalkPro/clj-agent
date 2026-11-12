# Provider API

Module: `im.ttalk/clj-agent-provider`

Source: [`modules/clj-agent-provider/src`](../../../modules/clj-agent-provider/src)

## Provider factory

Namespace: `im.ttalk.agent.provider.factory.builder`

| Function | Configuration order |
|---|---|
| `create-provider` | Explicit provider type and opts only |
| `create-provider-with-defaults` | Built-in defaults, then user opts |
| `create-provider-from-env` | Environment variables |
| `create-provider-auto` | Built-in defaults, environment, then user opts |

```clojure
(require '[im.ttalk.agent.provider.factory.builder :as factory])

(def openai (factory/create-provider-from-env :openai))
(def anthropic (factory/create-provider :anthropic {:api-key "..."}))
(def compatible
  (factory/create-provider :openai-compatible
                           {:base-url "https://internal.example.com/v1"
                            :api-key "..."}))
```

Factory configuration resolution lives in
`im.ttalk.agent.provider.factory.config`; provider registration is in
`im.ttalk.agent.provider.factory.registry`.

## Supported conversation providers

| Provider key | Notes |
|---|---|
| `:openai` | OpenAI Chat Completions |
| `:openai-responses` | OpenAI Responses wire protocol |
| `:anthropic` | Anthropic Messages |
| `:deepseek`, `:zhipu`, `:ollama`, `:gemini`, `:mistral`, `:minimax` | First-class adapters |
| `:dashscope` | Alibaba Cloud DashScope |
| `:xai`, `:moonshot`, `:openrouter`, `:siliconflow` | OpenAI-compatible ecosystems |
| `:openai-compat` | Generic adapter; base URL required |
| `:mock` | Offline tests and examples |

Exact keys are provider-specific. Common keys include `:api-key`, `:base-url`,
`:model`, `:max-tokens`, `:temperature`, timeout keys, proxy settings, and
`:thinking` controls.

## Embeddings

Namespace: `im.ttalk.agent.provider.embeddings`

Embeddings use the optional `IEmbeddingProvider` protocol in
`im.ttalk.agent.model.embedding`. The provider module includes OpenAI-compatible
and DashScope-native implementations. Conversation provider support does not imply
embedding support; probe with `embedding-provider?` or equivalent checks.

## Streaming and transport

Provider stream namespaces convert vendor Server-Sent Events into neutral token
and response callbacks. `im.ttalk.agent.provider.http.stream-client` adds request
model validation, cancellation, retry classification, and canonical provider
errors. HTTP itself is delegated to `im.ttalk/clj-http`; do not instantiate vendor
HTTP clients in application code.

Wire and schema namespaces under `im.ttalk.agent.provider.wire.*` and
`im.ttalk.agent.provider.schema.*` are implementation details unless you are adding
a new provider dialect.
