# Client API

Module: `im.ttalk/clj-agent-client`

Source: [`modules/clj-agent-client/src`](../../../modules/clj-agent-client/src)

## SimpleAgent

Namespace: `im.ttalk.agent.simple-agent`

SimpleAgent is the recommended high-level API. It owns one conversation id, stores
history through a ChatMemory filter, runs the ReAct loop, and normalizes pause and
error outcomes.

| Function | Purpose |
|---|---|
| `create-agent` | Create an agent from provider/model/tools/memory/options |
| `chat` | Synchronous conversation turn |
| `chat-stream` | Streaming turn; callback receives incremental token events |
| `chat-async` | Turn returning a `CompletableFuture` |
| `resume` | Continue a paused turn after an approval decision |
| `resume-async` | Asynchronous resume |
| `paused?` | Whether the agent has a pending pause state |
| `get-history` | Neutral message history for the current conversation |
| `reset!` | Clear history and reset control state |

Status shapes:

```clojure
{:status :completed :text "..."}
{:status :paused :reason ... :pending-tool {...}}
{:status :error :error {:type ... :message ... :retryable? ...}}
```

Do not share one non-thread-safe SimpleAgent across concurrent turns for the same
conversation. Use the AG-UI runtime when runs must survive or be controlled from
independent HTTP requests.

## ReAct loop

Namespace: `im.ttalk.agent.react`

Most applications use SimpleAgent rather than calling the loop directly. The loop
owns these extension points:

| Function/constructor | Contract |
|---|---|
| `invoke` | Run one complete tool-calling turn |
| `invoke-async` | Async variant on a virtual thread |
| `resume` | Continue from a paused loop state |
| `execute-batch` | MapReduce execution for one tool batch |
| `sequential-tool-calling-manager` | Default serial engine |
| `virtual-thread-tool-calling-manager` | One virtual thread per eligible call |
| `thread-pool-tool-calling-manager` | Bounded, instance-owned platform pool |
| `shutdown-tool-calling-manager!` | Release engine resources |

A bounded tool manager belongs to one ChatClient. Do not share it across parent and
subagent clients; doing so can deadlock when both levels wait for the same pool.

## Memory and stores

- `im.ttalk.agent.memory`: `ChatMemory` protocol, `in-memory-store`, and
  `windowed`.
- `im.ttalk.agent.memory.sqlite`: `sqlite-store` and `close-store!`.
- `im.ttalk.agent.pause`: `PauseStore` protocol plus in-memory and SQLite stores.
- `im.ttalk.agent.timeline`: branch records, `fork!`, `rollback!`, `prune!`, and
  lineage queries.

Memory stores persist neutral messages by conversation id. SQLite-backed stores
should be closed on application shutdown.

## Approval and events

- `im.ttalk.agent.approval`: policies, approver chains, session cache, denial
  breaker, and `approval-filter`.
- `im.ttalk.agent.approval.model`: optional model-based reviewer.
- `im.ttalk.agent.events`: closed set of turn lifecycle events and a zero-dependency
  metrics collector.
- `im.ttalk.agent.callbacks`: safe lifecycle callback dispatch.

The default policy pauses `:sensitive` tools when approval is configured. A resume
decision is normally `"approved"` or `"rejected"`; payload support depends on the
pending tool.

## Subagents

- `im.ttalk.agent.subagent.manager`: spawn, await, result, resume, kill, restart,
  and list subagent runs.
- `im.ttalk.agent.subagent.delegate`: synchronous delegate/fan-out tools.
- `im.ttalk.agent.subagent.tools`: async spawn/send/wait/list/interrupt tools with
  agent-count and depth limits.

Subagents are independent agents with their own client and tool manager. Use the
observer hook when parent events must attribute child activity.
