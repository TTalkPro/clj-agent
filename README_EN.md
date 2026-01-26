# clj-agent

Clojure AI Agent Framework - Kernel Central Orchestrator

English | [中文](README.md)

## Overview

`clj-agent` is a Clojure AI Agent framework providing a complete solution from simple conversations to complex workflows:

- **Kernel + Tool Orchestration**: `deftool` macro for tool definitions, Kernel uses `add-tools` for unified scheduling
- **Multi-level Invoke API**: `invoke-tool` (function call), `invoke-chat` (pure LLM), `invoke` (tool-calling loop)
- **Filter Middleware**: Ring-style onion model with 4 filter types (pre/post invocation, pre/post chat)
- **Service Abstraction**: LLM services via `{:chat-fn :build-result-msgs}` map, zero coupling
- **Multi-Provider Support**: Anthropic, OpenAI, Zhipu, Ollama, Gemini, Mistral, and OpenAI-compatible protocols
- **SimpleAgent Wrappers**: KernelAgent (synchronous stateful) and ProcessAgent (pause/resume approval)
- **Process Runtime**: core.async-based event-driven workflows with parallel, fan-in/fan-out, human-in-the-loop
- **Multi-backend Storage**: IKeyValueStore + ISnapshotStore protocols (Memory/SQLite/Redis/PostgreSQL)
- **RAG Support**: Retrieval-augmented generation with document splitting, vector storage, semantic retrieval
- **MCP Protocol**: Model Context Protocol server and client implementation

## Architecture Overview

```mermaid
graph TB
    subgraph "User Layer"
        KA[KernelAgent<br/>Synchronous Stateful]
        PA[ProcessAgent<br/>Pause/Resume Approval]
    end

    subgraph "Orchestration Layer"
        K[Kernel<br/>Central Orchestrator]
        F[Filter<br/>Middleware Chain]
        T[deftool<br/>Tool Definition Macro]
    end

    subgraph "Service Layer"
        S[Service<br/>LLM Call Protocol]
        PR[Provider Registry<br/>Multi-Provider Factory]
    end

    subgraph "Provider Implementations"
        AN[Anthropic]
        OA[OpenAI]
        ZP[Zhipu]
        OL[Ollama]
        GM[Gemini]
        MS[Mistral]
    end

    subgraph "Runtime Layer"
        RT[Process Runtime<br/>core.async Event-Driven]
        SM[SnapshotManager<br/>State Snapshots]
    end

    subgraph "Storage Layer"
        MEM[InMemory]
        SQL[SQLite]
        PG[PostgreSQL]
        RD[Redis]
    end

    subgraph "Extension Layer"
        RAG[RAG Pipeline<br/>Retrieval-Augmented Generation]
        MCP[MCP Server/Client<br/>Model Context Protocol]
        PLG[Tool Library<br/>File/HTTP/Shell]
    end

    KA --> K
    PA --> K
    PA --> RT
    K --> T
    K --> F
    K --> S
    S --> PR
    PR --> AN & OA & ZP & OL & GM & MS
    RT --> SM
    SM --> MEM & SQL & PG & RD
    RAG --> K
    PLG --> K
    MCP --> K
```

## Module Dependencies

```mermaid
graph LR
    core[clj-agent-core<br/>Kernel, Tool, Filter<br/>Process Runtime]
    llm[clj-agent-llm<br/>Provider, Service]
    sa[clj-agent-simpleagent<br/>KernelAgent, ProcessAgent]
    plugin[clj-agent-plugin<br/>File, HTTP, Shell]
    rag[clj-agent-rag<br/>RAG Pipeline]
    memory[clj-agent-memory<br/>Store, Snapshot]
    mcp[clj-agent-mcp<br/>MCP Server/Client]

    llm --> core
    sa --> core
    sa --> llm
    plugin --> core
    rag --> core
    mcp --> core
```

> `clj-agent-memory` is a standalone module with no internal module dependencies.

## Module Structure

```
clj-agent/
├── modules/
│   ├── clj-agent-core/         # Core (Kernel, Tool, Filter, deftool, Process Runtime)
│   ├── clj-agent-llm/          # LLM Provider + Service Factory
│   ├── clj-agent-simpleagent/  # High-level Agent Wrappers (KernelAgent, ProcessAgent)
│   ├── clj-agent-plugin/       # Pre-built Plugin Library (File, HTTP, Shell, Security)
│   ├── clj-agent-memory/       # Storage Implementations (InMemory, SQLite, Redis, PostgreSQL)
│   ├── clj-agent-rag/          # RAG Retrieval-Augmented Generation
│   └── clj-agent-mcp/          # MCP Server/Client
├── examples/                   # Usage Examples
├── docs/                       # Design Documents
├── scripts/                    # Development Scripts
└── deps.edn                    # Root Dependency Configuration
```

## Quick Start

### Add to Your Project

```clojure
;; deps.edn
{:deps {im/ttalk-agent {:local/root "/path/to/clj-agent"}}}
```

### Option 1: SimpleAgent (Recommended for Getting Started)

The simplest way to use the framework with automatic state management:

```clojure
(require '[im.ttalk.agent.simpleagent.kernel-agent :as ka])
(require '[im.ttalk.agent.core.kernel.tool :refer [deftool]])
(require '[im.ttalk.agent.llm.factory.builder :as factory])

;; 1. Define tools
(deftool get-weather
  "Get weather information for a city"
  [[city :string "City name"]]
  (str city ": Sunny 25°C"))

;; 2. Create tools collection (tool var vector)
(def my-tools [#'get-weather])

;; 3. Create Provider
(def provider (factory/create-provider-from-env :openai))

;; 4. Create Agent
(def agent (ka/create-agent
             {:provider provider
              :model "gpt-4"
              :system-prompt "You are a weather assistant"
              :tools my-tools}))

;; 5. Chat (auto-accumulates context)
(println (:text (ka/chat agent "What's the weather in Beijing?")))
(println (:text (ka/chat agent "How about Shanghai?")))  ;; remembers context

;; Reset conversation
(ka/reset! agent)
```

### Option 2: ProcessAgent (Sensitive Tool Approval)

Automatically pauses when encountering tools marked as `:sensitive`, awaiting human approval:

```clojure
(require '[im.ttalk.agent.simpleagent.process-agent :as pa])

(deftool delete-file
  "Delete a file"
  [[path :string "File path"]]
  {:sensitive true}   ;; Mark as sensitive operation
  (str "Deleted: " path))

(def file-tools [#'delete-file])

(def agent (pa/create-process-agent
             {:provider provider
              :model "gpt-4"
              :tools file-tools
              :on-pause (fn [{:keys [reason]}]
                          (println "Approval needed:" reason))}))

(let [result (pa/chat agent "Delete /tmp/test.txt")]
  (when (= :paused (:status result))
    (println "Pending tool:" (get-in result [:pending-tool :name]))
    ;; Approve
    (pa/resume agent "approved")
    ;; Or reject: (pa/resume agent "rejected")
    ))
```

### Option 3: Kernel API (Full Control)

Use the Kernel directly for maximum flexibility:

```clojure
(require '[im.ttalk.agent.core.kernel.core :as kernel])
(require '[im.ttalk.agent.core.kernel.filter :as filters])
(require '[im.ttalk.agent.llm.kernel.chat :as chat])

;; Create LLM Service
(def service (chat/create-service
               {:provider provider
                :model "gpt-4"
                :max-tokens 4096}))

;; Build Kernel
(def app-kernel
  (-> (kernel/create-kernel-builder)
      (kernel/add-service service)
      (kernel/add-tools my-tools)
      (kernel/add-filter filters/logging-pre-filter)
      (kernel/add-filter filters/error-handling-filter)
      (kernel/build-kernel)))

;; Tool-calling loop (auto LLM + Tool interaction)
(let [messages [{:role "user" :content "What's the weather in Beijing?"}]
      result (kernel/invoke app-kernel messages {})]
  (println (get-in result [:response :text]))
  (println "Tools called:" (:tool-calls-made result)))

;; Pure LLM call (no tool invocation)
(let [{:keys [response]} (kernel/invoke-chat app-kernel
                           [{:role "user" :content "Hello"}]
                           {})]
  (println (:text response)))

;; Direct tool invocation (through Filter pipeline)
(let [{:keys [value]} (kernel/invoke-tool app-kernel :get-weather
                        {:city "Beijing"} nil)]
  (println value))
```

## Core Concepts

### deftool Macro

Simultaneously defines a Clojure function and generates an LLM tool schema:

```clojure
(deftool fn-name
  "Description (becomes the LLM tool description)"
  [[param1 :string "Parameter description"]
   [param2 :int "Optional parameter" :default 10]
   [param3 :boolean "Boolean parameter"]]
  {:sensitive true    ;; Optional: mark as sensitive (ProcessAgent will pause for approval)
   :context true}     ;; Optional: needs Context access (adds ctx parameter to function signature)
  (body ...))

;; Supported parameter types: :string :int :float :boolean :array :object
```

### Kernel API

Kernel provides three categories of APIs:

```clojure
;; Build API - Construct Kernel
(-> (kernel/create-kernel-builder)
    (kernel/add-tools my-tools)         ;; Add tools
    (kernel/add-service service)        ;; Set LLM service
    (kernel/add-filter filter-def)      ;; Add filter
    (kernel/build-kernel))              ;; Build

;; Invoke API - Invocation
(kernel/invoke-tool kernel :fn-name {:arg "val"} context)  ;; Function call (through Filters)
(kernel/invoke-chat kernel messages opts)                   ;; Pure LLM (no tool loop)
(kernel/invoke kernel messages opts)                        ;; Tool-calling loop (main entry)

;; Query API - Query
(:tools kernel)                       ;; All tool schemas
(:service kernel)                     ;; Get service
(kernel/find-function kernel :name)   ;; Find function
(kernel/list-functions kernel)        ;; List all function names
```

### Service Interface

Service is a map defining the LLM call protocol:

```clojure
{:chat-fn           (fn [messages opts] -> {:text "..." :tool-calls [...] :assistant-msg {...}})
 :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}
```

`chat/create-service` from `clj-agent-llm` module creates this automatically. You can also implement this map yourself to integrate any LLM.

### Filter Middleware

Four types of Filters in a Ring-style onion model:

```clojure
;; Create custom Filter
(filters/create-filter :my-filter :pre-invocation
  (fn [filter-ctx]
    (println "Before tool call:" (:tool-name filter-ctx))
    {:action :continue :context filter-ctx})
  :priority 10)

;; Built-in Filters
filters/logging-pre-filter         ;; Pre-invocation logging
filters/logging-post-filter        ;; Post-invocation logging
filters/error-handling-filter      ;; Exception handling
(filters/timeout-filter 5000)      ;; Timeout control (ms)
filters/approval-filter            ;; Sensitive tool approval

;; Filter types
;; :pre-invocation   Before tool call (can modify args/context, can skip execution)
;; :post-invocation  After tool call (can modify result/context)
;; :pre-chat         Before LLM call (can modify messages/context)
;; :post-chat        After LLM call (can modify response/context)
```

### Context (Shared State)

Context manages shared state within a conversation:

```clojure
(require '[im.ttalk.agent.core.kernel.context :as ctx])

(def my-ctx (ctx/create {:user-id "u123"}))   ;; Create (with initial variables)
(ctx/get-var my-ctx :user-id)                  ;; Get variable
(ctx/set-var my-ctx :key "value")              ;; Set variable (returns new ctx)
(ctx/get-messages my-ctx)                      ;; Get working messages
(ctx/get-history my-ctx)                       ;; Get full history
(ctx/track-message my-ctx msg)                 ;; Track message (returns new ctx)
```

## LLM Providers

### Supported Providers

| Provider | Description | Environment Variable |
|----------|-------------|---------------------|
| `:openai` | OpenAI GPT series | `OPENAI_API_KEY` |
| `:anthropic` | Anthropic Claude series | `ANTHROPIC_API_KEY` |
| `:zhipu` | Zhipu GLM series | `ZHIPU_API_KEY` |
| `:ollama` | Local Ollama models | - |
| `:gemini` | Google Gemini | `GEMINI_API_KEY` |
| `:mistral` | Mistral | `MISTRAL_API_KEY` |
| `:openai-compat` | OpenAI-compatible protocol | Custom |

### Creating Providers

```clojure
(require '[im.ttalk.agent.llm.factory.builder :as factory])

;; Auto-configure from environment variables
(def provider (factory/create-provider-from-env :openai))

;; Manual configuration
(def provider (factory/create-provider :anthropic
                {:api-key "sk-..."
                 :base-url "https://api.anthropic.com"}))

;; OpenAI-compatible protocol (e.g., vLLM, LocalAI)
(def provider (factory/create-provider :openai-compat
                {:api-key "key"
                 :base-url "http://localhost:8000/v1"}))
```

## Process Runtime

A core.async-based event-driven workflow engine supporting:

- Linear/parallel/fan-in/fan-out execution patterns
- Human-in-the-loop pause/resume
- External event injection (interactive Agents, webhook callbacks)
- Safe snapshot points (on-quiescent callback)
- Step lifecycle management (init → can-activate? → on-activate → on-terminate)

```clojure
(require '[im.ttalk.agent.core.kernel.process.builder :as process])
(require '[im.ttalk.agent.core.kernel.process.runtime :as runtime])

;; Define Process
(def spec
  (-> (process/builder :document-gen)
      (process/add-step {:id :gather
                         :on-activate (fn [state ctx event]
                                        {:emit [{:target :generate
                                                 :data (:data event)}]})})
      (process/add-step {:id :generate
                         :on-activate (fn [state ctx event]
                                        {:result (generate-doc (:data event))})})
      (process/on-event :start :gather :input)
      (process/on-event :ready :generate :data)
      (process/build)))

;; Execute
(def result (runtime/run-process spec {:input {:topic "AI"}}))
```

### External Event Support

Inject external events into running Processes for interactive scenarios (chat Agents, webhook callbacks):

```clojure
;; Build Process with external event bindings
(def interactive-spec
  (-> (process/builder :chat)
      (process/add-step
        {:id :handler
         :on-activate (fn [inputs state ctx]
                        (if (= (:input inputs) "/quit")
                          {:terminate true}  ;; Termination signal
                          {:events [{:name :response :data "..."}]}))})
      (process/on-external-event :user-input :handler :input)
      (process/build)))

;; Start asynchronously (returns ProcessHandle)
(def handle (runtime/start-process-async interactive-spec {}))

;; Send external events
(runtime/send-event handle :user-input "Hello!")
(runtime/send-event handle :user-input "/quit")

;; Wait for completion
(runtime/wait-for-completion handle)
```

See [docs/process-framework-design.md](docs/process-framework-design.md) and [docs/process-parallel-design.md](docs/process-parallel-design.md) for detailed design.

## Memory Storage

Multi-backend storage for conversation history, snapshot persistence, and long/short-term memory management.

### Basic Storage Operations

```clojure
(require '[im.ttalk.agent.memory.api :as mem])

;; Create storage backend
(def store (mem/create-in-memory-store))           ;; In-memory (dev/test)
(def store (mem/create-sqlite-store "agent.db"))   ;; SQLite (single-node persistence)
(def store (mem/create-postgres-store conn-opts))  ;; PostgreSQL (production)
(def store (mem/create-redis-store redis-opts))    ;; Redis (distributed cache)

;; Key-Value operations
(mem/kv-put store "user-123" "preferences" {:lang "en" :theme "dark"})
(mem/kv-get store "user-123" "preferences")
;; => {:lang "en" :theme "dark"}

(mem/kv-list-keys store "preferences")
(mem/kv-exists? store "user-123" "preferences")
(mem/kv-delete store "user-123" "preferences")
```

### Agent Conversation State Save & Restore

Use SnapshotManager to persist conversation state, enabling resume-from-checkpoint and time-travel:

```clojure
(require '[im.ttalk.agent.simpleagent.kernel-agent :as ka])
(require '[im.ttalk.agent.memory.store.in-memory :as mem-store])
(require '[im.ttalk.agent.memory.snapshot.manager :as snap-mgr])
(require '[im.ttalk.agent.memory.protocol :as mem-proto])
(require '[im.ttalk.agent.core.kernel.context :as ctx])

;; 1. Create SnapshotManager
(def store (mem-store/create-in-memory-store))
(def snap-manager (snap-mgr/create-snapshot-manager store))
(def thread-id "session-001")

;; 2. Create Agent and chat
(def agent (ka/create-agent
             {:provider provider
              :model "gpt-4"
              :system-prompt "You are an assistant."}))

(ka/chat agent "My name is John, I work in New York.")
(ka/chat agent "I love programming.")

;; 3. Save current conversation state
(let [context (ka/get-context agent)
      snapshot {:context context
                :settings {:model "gpt-4"}}]
  (mem-proto/snap-put snap-manager
                      {:thread-id thread-id}
                      snapshot
                      {:reason :user-save
                       :created-at (System/currentTimeMillis)}))

;; 4. Restore state in new Agent (resume conversation)
(let [loaded (mem-proto/snap-get snap-manager {:thread-id thread-id})
      restored-context (:context (:snapshot loaded))
      new-agent (ka/create-agent {:provider provider :model "gpt-4"})]
  ;; Restore context
  (reset! (:context-atom new-agent) restored-context)
  ;; Continue conversation, Agent retains previous memory
  (ka/chat new-agent "What's my name?"))
;; => Agent remembers the user is named John
```

### AgentMemory Unified Wrapper

AgentMemory provides one-stop memory management, integrating snapshots, time-travel, knowledge base, and message management:

```clojure
(require '[im.ttalk.agent.memory.api :as mem])

;; Create complete memory system
(def am (mem/create-agent-memory
          {:context-store (mem/create-in-memory-store)      ;; Hot data
           :persistent-store (mem/create-sqlite-store "data.db")}))  ;; Cold data

;; State management
(mem/save-state am {:messages [...] :variables {...}})
(mem/load-state am)

;; Time travel
(mem/go-back am)           ;; Go to previous state
(mem/go-forward am)        ;; Go to next state
(mem/goto am 3)            ;; Jump to version 3
(mem/list-history am)      ;; View history

;; Branch management (A/B testing, experimental conversations)
(mem/create-branch am "experiment-a")
(mem/switch-branch am "experiment-a")
(mem/list-branches am)

;; Knowledge base (long-term memory)
(mem/remember am {:type :fact :content "User prefers English"})
(mem/remember am {:type :episode :content "User asked about weather last time"})
(mem/recall am "user preference")                  ;; Semantic retrieval
(mem/recall-by-type am :fact)                      ;; Retrieve by type
(mem/search-knowledge am "preference" {:limit 5})  ;; Search

;; Message management
(mem/add-message-to-memory am {:role "user" :content "Hello"})
(mem/get-messages-from-memory am)
(mem/clear-messages-from-memory am)

;; Session archiving
(mem/archive-session! am)
(mem/list-archived am)
(mem/load-archived am "session-id")
```

### Long-term Memory Types

```clojure
;; Semantic memory (facts/knowledge)
(def sem (mem/create-semantic-memory store))
(mem/store-fact sem {:key "capital" :value "Beijing is the capital of China" :category "geography"})
(mem/get-fact sem "capital")
(mem/query-facts sem {:category "geography"})

;; Episodic memory (events/experiences)
(def epi (mem/create-episodic-memory store))
(mem/store-episode epi {:action "weather-query"
                        :query "Beijing weather"
                        :outcome :success
                        :timestamp (System/currentTimeMillis)})
(mem/get-recent-episodes epi 5)

;; Procedural memory (rules/skills)
(def proc (mem/create-procedural-memory store))
(mem/set-system-prompt proc "You are a professional assistant")
(mem/add-rule proc (mem/create-rule {:id "r1" :content "Always respond in English"}))
(mem/get-active-rules proc)
```

## RAG (Retrieval-Augmented Generation)

```clojure
(require '[im.ttalk.agent.rag.plugin :as rag])

;; Index documents
(rag/rag-index-text "Document content..." {:source "doc-001"})

;; Retrieve relevant documents
(rag/rag-retrieve "Search query" {:top-k 5})

;; RAG-powered Q&A
(rag/rag-query "Answer this question" {:top-k 5})
```

The RAG module can also be registered as Kernel tools via `rag/all-tools`, enabling the LLM to automatically invoke retrieval.

## MCP Protocol

Model Context Protocol server/client implementation:

```clojure
;; Start MCP server
;; clj -M:mcp-server

;; Client connection
(require '[im.ttalk.agent.mcp.client.core :as mcp-client])

(def client (mcp-client/connect {:transport :stdio
                                  :command ["clj" "-M:mcp-server"]}))
```

Supports both Stdio and SSE transport protocols.

## Development

```bash
# Run all tests
./scripts/test-all.sh

# Build all modules
./scripts/build-all.sh

# Install to local Maven
./scripts/install-all.sh
```

## Dependencies

Core:

- org.clojure/clojure 1.11.4
- org.clojure/core.async 1.6.681
- cheshire/cheshire 5.12.0
- com.taoensso/timbre 6.3.0
- http-kit/http-kit 2.8.0
- com.github.seancorfield/next.jdbc 1.3.939
- net.clojars.wkok/openai-clojure 0.21.0

Storage backends (as needed):

- org.xerial/sqlite-jdbc 3.45.1.0
- org.postgresql/postgresql 42.7.3
- com.taoensso/carmine 3.2.0 (Redis)

Testing:

- lambdaisland/kaocha 1.85.1342

## License

MIT License
