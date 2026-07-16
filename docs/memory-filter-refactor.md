# 重构设计：消除 Context、转向 Memory Filter + 中立消息

> 状态：✅ **已完成并合入 main**（2026-07-10 核对：P0-P7 全部落地，见 §5 与「实施结果」）。
> 目标：把"显式 thread Context"的状态模型，替换为 Spring AI 式的"无状态调用 + 按 conversation-id 的 Memory store（经 Memory Filter 自管）"，并消除消息的双重记账。
>
> **落地核对（2026-07-10）**：`context.clj` 已是扁平 ToolContext（无 :messages/:history/:trace）；
> `advisor/memory.clj` 以 around-chat filter 自管历史；`react.clj` 的 run-tool-loop 以 delta+store
> 驱动，无 conv-msgs 影子账——store 是唯一对话来源。
>
> **路径对照**（本文写作期的规划命名 → 实际落地命名）：
> `core/llm/message.clj` → `model/message.clj`；`llm/wire/*` → `provider/wire/*`；
> `core/memory.clj` → `memory.clj`；`core/kernel/memory_filter.clj` → `advisor/memory.clj`；
> `core/kernel/context.clj` → `context.clj`；`simpleagent/*` → `client.clj`（后续 unified-invoke-agent 合并）。

## 1. 背景与动机

当前架构（重构前）：

- `kernel/invoke` 内部跑工具循环，循环局部变量 `conv-msgs` 是真正发给 LLM 的消息源。
- `Context`（嵌套 map：`:variables/:messages/:history/:trace/:metadata`）被显式 thread：调用方传入 `:context`、拿回 `:context`、再传入下一轮。
- `ctx.messages` 与 `conv-msgs` **并行记账**，且工具结果有两套编码（`encode-tool-result` vs 各 provider 的 `build-result-messages`）。
- **对话历史以 provider wire 格式存储**（Anthropic `tool_use`/`tool_result` content block；OpenAI `tool_calls`/`role:"tool"`）——换 provider 回放会损坏。

问题：

1. `Context.messages` 在单次 invoke 内与 `conv-msgs` 重复，是冗余/可能漂移的影子账。
2. 历史按 wire 格式存，绑死 provider。
3. 多轮要调用方手动 thread context，不适合服务端按会话隔离的场景。

## 2. 目标形态

参照 Spring AI 三根支柱：

1. **无内部工具执行**（可选）：LLM 返回 tool_calls 不自动执行，交调用方驱动外部循环。
2. **Memory 按 id 自管**：每次 LLM 调用，pre-chat 从 store 取历史拼 prompt + 存入参；post-chat 存 assistant 回复。
3. **只回传 delta**：调用方每轮只传最新一条，历史由 Memory Filter 拼。

### 决策（已确认）

- **替换式**：移除内部 Context threading，统一走 store-by-id。
- **请求级 flat map（ToolContext）**：有状态工具的共享状态压成单层 map，仅请求级有效；跨轮累积走 store。
- **中立消息格式**：store 存 provider 无关的中立消息，发送时由 provider 转 wire。

## 3. 核心构件

### 3.1 中立消息格式（唯一真相）

```clojure
{:role :system    :content "..."}
{:role :user      :content "..."}
{:role :assistant :content "..."}                       ; 纯文本
{:role :assistant :content nil
 :tool-calls [{:id "c1" :name "get_weather" :args {:city "北京"}}]}  ; 带工具调用
{:role :tool :tool-call-id "c1" :name "get_weather" :content "晴 22°C"}
```

约束：
- role 用 **keyword**（`:system/:user/:assistant/:tool`）。
- 工具调用 `:tool-calls [{:id :name :args}]`，`:name` 为字符串或 keyword，`:args` 为 map。
- 工具结果 `:tool-call-id` 关联到对应调用的 `:id`。
- store 里**只存这种形状**；wire 转换发生在 provider 边界。

### 3.2 中立 ↔ wire 转换（provider 边界）

每个 provider 负责：
- `neutral->wire`：把中立消息列表转成自家请求格式（call-llm 内部完成）。
- `response->neutral`：把原始响应转成**中立 assistant 消息**（含 `:tool-calls`）。

provider 协议调整：
- `call-llm [this config neutral-messages tools]` —— 内部 neutral→wire。
- 新增 `response->neutral-message [this response]` —— 取代 `build-assistant-message`，返回中立 assistant。
- **移除** `build-result-messages` / `build-tool-result`：工具结果消息由 `run-tools` / Memory Filter 直接构造中立形状，provider 无关。
- `extract-text` / `extract-tool-calls` 保留（response→neutral 内部复用）。

### 3.3 ChatMemory store

```clojure
(defprotocol ChatMemory
  (mem-get   [this conv-id])        ; -> [中立消息 ...]
  (mem-add   [this conv-id msgs])   ; 追加中立消息
  (mem-clear [this conv-id]))
```

- in-memory 实现（atom，`{conv-id -> [msgs]}`）。
- 窗口策略包装：`(windowed store {:max-messages N})` 或 token 预算（后续）。
- conv-id 为空 / store 未配置时，Memory Filter no-op（保留一次性无记忆调用）。

### 3.4 Memory Filter

```clojure
;; pre-chat：存 delta、用完整历史替换 messages
(create-filter :memory :pre-chat
  (fn [fctx]
    (let [cid   (get-in fctx [:context :conversation-id])
          delta (:messages fctx)]
      (if (and store cid)
        (do (mem-add store cid delta)
            {:action :continue
             :context (assoc-messages fctx (mem-get store cid))})
        {:action :continue :context fctx}))))         ; 无记忆 → 原样

;; post-chat：存 assistant（可能含 tool-calls）
(create-filter :memory :post-chat
  (fn [fctx]
    (when (and store cid)
      (mem-add store cid [(response->neutral (:response fctx))]))
    {:action :continue :context fctx}))
```

存储顺序天然正确：`[..., user, assistant(tool_calls), tool_result(s), assistant(...)]`。

### 3.5 ToolContext（Context 的退化形态）

单层 flat map，承载请求/会话级 k/v：

```clojure
{:conversation-id "abc" :user-id "u1" :任意工具共享 k v}
```

- 没有 `:messages/:history/:trace`。
- `{:context true}` 工具读写它（请求级，返回但不自动持久化）。
- conv-id 放这里，Memory Filter 从这里读 —— 顺带解决 per-call 参数通道。
- `get-var`/`set-var` 退化为普通 map 操作。

## 4. invoke 新循环

```
invoke kernel [user-msg] {:tool-context {:conversation-id cid}}:
  1. invoke-chat [user-msg]                 ; 只传 delta
       pre-chat 存 user + 拼历史 → LLM → post-chat 存 assistant
  2. while has-tool-calls(response):
       results = run-tools kernel tool-calls tool-context   ; 中立 tool 消息
       invoke-chat results                  ; 只传 delta
       ... 同上 ...
  3. return {:response ... :tool-context tc}
```

- 删除 `conv-msgs`、删除 threaded `ctx.messages`：store 是唯一对话来源。
- 外部循环：`invoke-chat`（单步、不执行工具）+ 新公开 `run-tools`（批量执行 → 中立 tool 消息）。

## 5. 分阶段实施

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 中立消息 spec + 转换契约（本文档 §3.1）→ `core/llm/message.clj` | ✅ |
| P1 | provider 边界 neutral↔wire → `llm/wire/{openai,anthropic}.clj` + 单测（7 tests） | ✅ wire 单元完成；service.chat-fn 接入留待 P4 整合 |
| P2 | ChatMemory protocol + in-memory + 窗口策略 → `core/memory.clj` + 单测 | ✅ |
| P3 | Memory Filter（pre/post-chat）→ `core/kernel/memory_filter.clj` + 单测 | ✅ |
| P4 | 改 invoke：delta + store；Context→flat ToolContext；`{:context true}` 工具改吃 flat map；kernel 内建 store + 自动挂 Memory Filter；process runtime 改扁平合并 | ✅ |
| P5 | 暴露 `run-tools`（public）+ 外部手搓循环测试（`external-tool-loop-test`） | ✅ |
| P6 | 重写 SimpleAgent：kernel-agent / process-agent / common 全部改 conversation-id + store | ✅ |
| P7 | 重写 context_test；修 kernel_agent_test/process_agent_test；修 examples（integration/kernel/process_test） | ✅ |

## 实施结果

- 全量测试 **189 tests, 701 assertions, 0 failures**。
- 生产代码无 removed-API 残留；所有模块加载通过。
- 分支：`refactor/memory-filter-neutral-messages`（未提交，待 review）。

### 关键落点
- `core/llm/message.clj`：中立消息（构造/谓词/normalize）
- `llm/wire/{openai,anthropic}.clj` + `llm/kernel/chat.clj`：neutral↔wire，service.chat-fn 吃中立消息
- `core/memory.clj`：ChatMemory（in-memory + pairing-safe 窗口）
- `core/kernel/memory_filter.clj`：pre/post-chat 自管历史
- `core/kernel.clj`：`invoke` 走 delta+store（无 conv-msgs/双账）、`run-tools` 公开、kernel 内建 store
- `core/kernel/context.clj`：扁平 ToolContext
- `simpleagent/*`：conversation-id + store（去 context-atom）

## 6. 破坏性变更清单

- `core/kernel/context.clj`：移除 `track-message`/`get-messages`/`get-history`/双轨，只剩 flat-map 的 `get-var`/`set-var`。
- `invoke`/`invoke-chat`：`:context` → `:tool-context`；返回 `:tool-context`。
- provider 协议：`call-llm` 吃中立消息；新增 `response->neutral-message`；移除 `build-result-messages`/`build-tool-result`/`build-assistant-message`。
- SimpleAgent 对外 API：`create-agent`/`chat`/`reset!`/`get-history` 语义变化（context-atom → conversation-id + store）。
- 相关 tests 重写。

## 7. 最大风险

P1（provider neutral↔wire）是地基与工作量主体。必须先打通"中立 ↔ Anthropic / OpenAI wire"并单测覆盖（mock + schema 比对），再往上叠 store/filter/invoke。
