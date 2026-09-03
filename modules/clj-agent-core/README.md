# clj-agent-core

核心模块 - 协议（端口）+ ChatClient 原语

[English](#english) | 中文

## 概述

`clj-agent-core` 是**协议（端口）层**与 **chat-client 原语**：

- **Facade 入口**：`im.ttalk.agent` —— 常用 API 一个地方找得到，每个函数都是一行转发（对照 beamai `beamai.erl`）
- **协议 / 契约**：`ILLMProvider`、中立消息、统一响应 —— 任何实现协议的 jar 都能作为 provider 注入
- **ChatModel**：一次 LLM 调用的抽象（`IChatModel` 协议）——选项合并、**重试**、响应归一化都在这层
- **ChatClient**：filter 洋葱链 + 工具装配，提供 `invoke-chat` / `invoke-tool` 两个原语
- **ToolRegistry**：工具声明表——装配期建表、运行期一次查表答完
- **deftool**：宏，同时定义函数和生成 LLM tool schema
- **Filter**：洋葱式 around 中间件执行器（对标 Spring AI 2.0 Advisor），五钩子
  `:chat` / `:tool` / `:iteration` / `:turn` / `:token-xform`；内含 ToolSearch、结构化输出判据、
  RAG 注入等对齐实现——**检索/向量库一律经协议注入，本模块外部依赖仍为零**
- **Context**：请求级共享状态
- **streaming**：流式取消令牌

Agent 运行时（client / ReAct 循环 / ChatMemory / 记忆 filter / callbacks / subagent）
已于 2026-07 下沉至 [`clj-agent-client`](../clj-agent-client/README.md)（命名空间不变）；
core 对记忆与循环零感知。

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}}}
```

内部依赖：无

外部依赖：无（纯 Clojure）

## 命名空间

**协议 / 契约（端口）**

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.model` | `ILLMProvider` 协议（端口，中立消息边界） |
| `im.ttalk.agent.model.message` / `.response` / `.error` | 中立消息、统一响应、错误 |
| `im.ttalk.agent.model.content` | 中立多模态内容部件（图片 / PDF / 音频；wire 层按 media type 分派） |
| `im.ttalk.agent.model.embedding` | `IEmbeddingProvider` 协议（**可选**能力，无 Object 兜底 → `satisfies?` 可信） |
| `im.ttalk.agent.model.request` | `ChatRequest`（消息 + 选项）—— 发往 ChatModel 的那一段 |
| `im.ttalk.agent.chat-model` | `IChatModel` 协议 + `DefaultChatModel` / `FnChatModel`；`create-chat-model`；**异步**：可选协议 `IAsyncChatModel` + `call-async*` / `stream-call-async*`（`satisfies?` 探测，否则虚拟线程兜底） |
| `im.ttalk.agent.retry` | 通用重试（判据取自 canonical error 的 `:retryable?`），无外部依赖；`run` / `run-async` 共用同一套判据与退避曲线 |
| `im.ttalk.agent.tool-calling-manager` | `ToolCallingManager` 工具批次执行协议 |

**ChatClient 原语**

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent` | **Facade 入口**：常用 API 的统一门面（一行转发，不实现逻辑） |
| `im.ttalk.agent.chat-client` | `ChatClient` record + `build-chat-client` + 三个 invoke 原语 |
| `im.ttalk.agent.tool-registry` | 工具声明表：装配期建表/校验 + 运行期查询（8 个） |
| `im.ttalk.agent.tool` | `deftool` 宏定义 |
| `im.ttalk.agent.filter` | Filter 契约、链合成与预编译、`ChatClientRequest`/`Response`、内置 filter、链结果组合子（`fmap`/`fbind`/`fcatch` + `IChainResult`） |
| `im.ttalk.agent.async` | 异步适配层：`IChainResult` 的 CompletionStage 实现 + 虚拟线程入口 `vthread` + Ring 回调 sink `on-complete` / `join`（给 `react/invoke-async`、`agent/chat-async` 用） |
| `im.ttalk.agent.context` | Context 状态管理 |
| `im.ttalk.agent.streaming` | 流式取消令牌 |

**对齐 Spring AI 2.0 Advisor 的内置 filter（各自独立命名空间）**

| 命名空间 | 对标 | 说明 |
|---------|------|------|
| `im.ttalk.agent.filter.tool-search` | `ToolSearchToolCallingAdvisor` | 渐进式工具披露；`IToolIndex` 协议 + 零依赖内置索引 |
| `im.ttalk.agent.filter.structured-output` | `StructuredOutputValidationAdvisor` | JSON Schema 判据 + 人话报错（配 `validation-turn-filter` 用） |
| `im.ttalk.agent.filter.rag` | `QuestionAnswerAdvisor` | 检索增强注入；`IRetriever` 协议 |

> 三者**都不引任何检索/向量依赖**（core 外部依赖仍为零）——向量库/embedding
> 经协议注入。`filter.memory`（memory filter）在 `clj-agent-client`。

> 各厂商实现（`im.ttalk.agent.provider.*`）在 `clj-agent-provider`；Agent 运行时
> （`client`/`react`/`memory`/`filter.memory`/`callbacks`/`subagent`/`common`）在
> `clj-agent-client`——两者都依赖本模块。

## API 参考

### 高层 Agent API

`create-agent`/`chat`/`resume` 等高层 API 见
[`clj-agent-client`](../clj-agent-client/README.md)。

### ChatClient Build API

```clojure
(require '[im.ttalk.agent.chat-client :as chat-client])

;; 声明式构建：一次性传入 chat-model / tools / filters / settings
(chat-client/build-chat-client
  {:chat-model my-chat-model
   :tools      [#'get-weather #'get-time]  ;; tool var 向量，也可混入内联工具 map
   :filters    [memory-filter logging-filter]
   :settings   {:max-tool-iterations 10}}) ;; 返回 ChatClient record
```

**装配期把能算的都算掉**，运行期不再重建：

| 预计算 | 内容 | 省掉的运行期开销 |
|---|---|---|
| `hooks` | 四条 around 链各预折一次 + `:token-xform` 预 `comp` | 每次 invoke 的 `keep` 全量扫描 + `reverse` + `reduce` |
| `tool-meta` | 每个工具的 `:func-def` / `:serial` / `:retry` / `:timeout` / `:return-direct` | 四个查询各自的 var/inline 双分支 + ToolRequest `:function` 段的重建 |

**装配期即拒绝**（宁可构建时炸，不要运行期静默走错）：

```clojure
;; 工具名重复（var 之间、内联之间、var 与内联之间都算）
;; => ex-info "工具名重复: :foo……" {:duplicates [:foo]}
;; 同名没有合理用例：两份 schema 都会发给 LLM，而 handler 只留得下一个,
;; 「模型看到的」与「实际执行的」就此对不上，且无运行期症状可查。
;; 要替换某个工具，请在传 :tools 之前处理自己的列表。

;; 非法 :timeout（非正整数）/ 非法 :retry（非 nil/true/正整数 map）
;; / :tool-manager 的非法 :timeout —— 同样装配期抛
```

### ChatClient Invoke API

chat-client 只提供两个原语（均经 filter 洋葱链）；**工具调用循环不在 chat-client**，在
`clj-agent-client` 模块（`im.ttalk.agent.simple-agent` 的 `create-agent` + `chat`，
或 `im.ttalk.agent.react/invoke`）。

```clojure
;; 调用工具函数（经 :tool filter 链）
(chat-client/invoke-tool cc :fn-name {:arg "val"} context)
;; => {:value result (:writes {k v}) (:error {:class :message})}
;; 注意（v0.3 破坏性变更）：context 是**只读**入参，返回值里不再有 :context——
;; 工具的写意图经 :writes 声明，由调用方（react 循环）在批次屏障处折叠。
;; 直调方自行用 context/apply-writes。

;; LLM 调用（经 :chat filter 链，不含工具循环）
(chat-client/invoke-chat cc messages opts)
;; => ChatClientResponse{:response <ChatResponse> :context ctx}
```

### 工具声明查询（`tool-registry`）

工具那一摊从 ChatClient 拆了出去：判据是「这些函数认识什么」——它们只认识
**工具声明**，一个都不认识 ChatModel、filter 链或消息。查询函数仍吃 ChatClient
（只读三个字段），故调用形状不变，只是所在 ns 变了。

```clojure
(require '[im.ttalk.agent.tool-registry :as registry])

(registry/tool-schemas cc)        ;; 所有 tool schema 列表（原 (:tools cc)）
(registry/find-function cc :name) ;; => {:tool-var v} 或 nil
(registry/list-functions cc)      ;; => [:fn1 :fn2 ...]

;; 查询函数吃 ChatClient **或**裸 ToolRegistry —— 注册表是个独立的值
(registry/registry-of cc)         ;; => ToolRegistry{:tools :tool-vars
                                  ;;                 :inline-handlers :tool-meta}

;; 全部是 tool-meta 表的薄封装（var 与内联工具同表，装配期汇好，
;; 运行期一次 map 查找 + 一次字段读）
(registry/tool-meta cc :name)           ;; => ToolMeta record，未注册则 nil
(registry/serial-tool? cc :name)        ;; 是否声明 :serial
(registry/return-direct-tool? cc :name) ;; 是否声明 :return-direct
(registry/retry-policy cc :name)        ;; :retry 声明（归一化已在装配期做掉）或 nil
(registry/tool-timeout cc :name)        ;; 工具自己声明的 :timeout，或 nil
(registry/effective-tool-timeout cc :name) ;; 实际生效的超时（含引擎缺省）
```

### filter 链访问（`filter`）

`filter-hooks` / `with-filters` 住在 `filter` ns——它们认识的是链
（`CompiledHooks` / `compile-hooks`），对 ChatClient 只用两个关键字取值，
依赖方向因此是 chat-client → filter 单向。

```clojure
(require '[im.ttalk.agent.filter :as flt])

(flt/filter-hooks cc)     ;; 预编译的四条链（CompiledHooks）
(flt/with-filters cc fs)  ;; 换 filter 链并重编 hooks —— **改 :filters 走这里**
```

### deftool 宏

```clojure
(require '[im.ttalk.agent.tool :refer [deftool]])

(deftool get-weather
  "获取天气信息"
  [[city :string "城市名称"]
   [unit :string "温度单位" :default "C"]]
  {:sensitive true      ;; 可选：标记敏感操作
   :context true}       ;; 可选：需要（只读）Context——多一个 ctx 参数
  (str city ": 25°" unit))

;; 支持的参数类型: :string :int :float :boolean :array :object
;; 可选项: :sensitive :context :tags :category :serial :retry :return-direct
;;         :timeout（毫秒；**开箱即生效**，无需挂 filter。缺省不超时；
;;                   优先级：工具声明 > 引擎 {:timeout ms} > 不超时）
;; 生成的 metadata: :tool/schema :tool/params :tool/sensitive :tool/context
;;                  :tool/tags :tool/category :tool/serial :tool/retry
;;                  :tool/return-direct :tool/function
```

### Filter API（洋葱式 around，对标 Spring AI 2.0 Advisor）

```clojure
(require '[im.ttalk.agent.filter :as filters])

;; 创建 Filter —— 根抽象 around(req, chain)：chain 是下游，
;; 由 filter 决定调不调（短路）、调几次（重试/递归重入）、前后干什么
(filters/create-filter :my-filter
  :chat (fn [req chain] ... (chain req') ...)   ;; 单次 LLM 调用
  :tool (fn [req chain] ... (chain req') ...))  ;; 单次工具执行
;; 产出 Filter record；也可直接写 map：{:name :x :chat (fn [req chain] ...)}
;; —— build-chat-client 经 as-filter 归一化，两种写法等价。五个钩子之外的键
;; （如 memory filter 的 :store）进 ext-map，照常可读。

;; 五个钩子（可任意并存，各挂各的链）：
;;   :chat        单次 LLM 调用（工具循环内每轮；memory 在此）
;;   :tool        单次工具执行（并行任务内各自生效）
;;   :iteration   单轮迭代 = LLM 调用 + 本轮工具批次（与 :chat 同频，但看得见
;;                本轮工具结果；可多次 (chain req) 重跑这一轮）
;;   :turn        整个工具循环（每 turn 一次；可多次 (chain req) 递归重入）
;;   :token-xform 流式出站 token 变换（transducer，非 around 形状）
;; 执行顺序 = :filters 向量的注册顺序（无 order/phase）；靠前者在最外层

;; 内置 filter
filters/logging-filter          ;; :tool  调用前后日志
(filters/logging-chat-filter)   ;; :chat  LLM 请求/响应日志（≈ SimpleLoggerAdvisor）
;; 超时不是 filter：deftool {:timeout ms} 或引擎 {:timeout ms} 即生效
;; （工具声明优先；都不给则不超时。超时=放弃等待≠终止执行，见 tool/call-with-timeout）
(filters/approval-filter)       ;; :tool  敏感工具人工审批，拒绝则短路
(filters/validation-turn-filter validate-fn :max-retries 2)
                                ;; :turn  答案校验，不合格带反馈重入循环
(filters/safeguard-turn-filter ["敏感词"])
                                ;; :turn  命中即不进循环直接拒答（≈ SafeGuardAdvisor）
(filters/re-reading-filter)     ;; :turn  RE2 重读（≈ ReReadingAdvisor）

;; 独立 filter 命名空间
im.ttalk.agent.filter.tool-search        ;; 渐进式工具披露（≈ ToolSearchToolCallingAdvisor）
im.ttalk.agent.filter.structured-output  ;; JSON Schema 判据（≈ StructuredOutputValidationAdvisor）
im.ttalk.agent.filter.rag                ;; 检索增强注入（≈ QuestionAnswerAdvisor）

;; 挂载 + 执行
(chat-client/build-chat-client {:chat-model cm :filters [my-filter]}) ;; 经 :filters 挂载
(filters/build-chain around-fns terminal) ;; 折成洋葱，返回 (fn [req] -> resp)
```

对齐记录见 `docs/advisor-alignment-design.md`，机制契约见 `docs/filter-chain-design.md`。

`:iteration` 与 `:chat` 同频（每轮一次），差别是它看得见**本轮工具跑出了什么**：

```clojure
;; 单轮墙钟预算：LLM + 本轮工具一起计时（:chat 只能计到 LLM 那一半）
(filters/create-filter :round-budget
  :iteration (fn [req chain]
               (let [t0 (System/currentTimeMillis)
                     r  (chain req)]
                 (println "第" (:index req) "轮耗时" (- (System/currentTimeMillis) t0) "ms")
                 r)))

;; 改写下一轮 delta / 重跑这一轮
(filters/create-filter :round-guard
  :iteration (fn [req chain]
               (let [r (chain req)]
                 (if (= :continue (:status r))
                   (update r :messages conj (msg/system "本轮工具结果已复核"))
                   r))))                        ;; :paused/:cancelled 必须原样透传
;; IterationRequest  {:messages 本轮 delta :context :index 轮序 :remaining 剩余预算}
;; IterationResult   {:status :continue :messages 下一轮 delta :context}
;;                   或终态 :completed / :paused / :cancelled
;; 重入即记账：重跑一轮 = LLM 与工具真的又跑一遍，remaining/records 如实计入，
;; 故 max-iterations 对重入仍是硬上限。契约见 docs/filter-chain-design.md §2.3
```

**改 chat-client 的 `:filters` 请走 `chat-client/with-filters`**——链在装配期预编译好存在
`hooks` 字段，直接 `(assoc chat-client :filters …)` 会让两者脱钩（`filter-hooks` 检测到
不同源会现场重编译兜底，语义始终正确，但每次 invoke 都重编）。

### ToolSearch API（渐进式工具披露，对标 `ToolSearchToolCallingAdvisor`）

初始只暴露 `search_tools`，模型检索到的工具**下一轮**才进工具列表。

```clojure
(require '[im.ttalk.agent.filter.tool-search :as ts])

;; 装配：工具 / :chat filter / 状态槽三处一次装好
(chat-client/build-chat-client
  (ts/with-tool-search
    {:chat-model cm :tools [#'t1 ... #'t80] :filters [(ma/memory-filter store)]}
    {:index (ts/keyword-tool-index)   ;; 或 (ts/regex-tool-index)
     :limit 5                          ;; 单次检索返回上限（缺省 5）
     :always-include #{"handoff"}}))   ;; 无需检索即常驻

;; 手工接线：拿三件套自己装
(ts/tool-search {:index idx})          ;; => {:tool ... :filter ... :state-slots ...}

;; 内置索引（零依赖）
(ts/keyword-tool-index)   ;; 名称/描述分词 × IDF；中文二元组切分，拆 snake/camelCase
(ts/regex-tool-index)     ;; query 当正则匹配工具名；非法正则退化字面匹配不抛异常

;; 自带向量库
(reify ts/IToolIndex
  (index-tools! [_ schemas] ...)
  (search-tools [_ query limit] ...))  ;; -> [schema ...]

ts/discovered-slot        ;; 发现集合在 tool-context 中的槽 key（命名空间限定）
```

**机制**：`search_tools` 是普通内联工具，返回 `{:writes {::discovered #{名字}}}`
→ 屏障按槽 reducer（`into` = 集合并）折叠进 tool-context → 每轮进 ChatRequest
`:context` → `:chat` filter 据此重写 `:tools`。**零新增钩子**；发现集合住在
tool-context 里，故暂停/resume/持久化白拿正确。

**三件套必须同装**：少 `:state-slots` 则发现集合退化 last-writer（不累积）；
索引由 filter 在每次 LLM 调用时建，故绕开 chat 直调 `invoke-tool` 会检索到空。

> **用之前先读 `docs/advisor-alignment-design.md` §2.3–2.5**：实测 50 工具目录省
> 78% prompt token，但赚不赚看**工具定义总量**而非工具个数；且**省 token 未必
> 省钱**——静态工具前缀本会被 provider 的 prompt cache 整块命中，本 filter 每轮
> 改写 `:tools` 会把它打碎。

### 结构化输出校验 API（对标 `StructuredOutputValidationAdvisor`）

`validation-turn-filter` 是机制（不合格 → 反馈重入 → 重试上限），本 ns 是判据。

```clojure
(require '[im.ttalk.agent.filter.structured-output :as so])

;; 纯函数：校验已解析的值，零依赖
(so/validate-value {:actor "K"} schema)
;; => "缺少必填字段 films"（nil = 通过）

;; 生成 validation-turn-filter 的 validate-fn
(so/validate-fn schema :parse-fn #(cheshire.core/parse-string % true))
;;                     ^^^^^^^^^ 必填：core 零依赖，不内置 JSON 解析器

(so/strip-fences "```json\n{...}\n```")   ;; => "{...}"
```

支持的 JSON Schema 子集：`:type`（object/array/string/number/integer/boolean/null）、
`:properties`、`:required`、`:items`、`:enum`。路径可读（`films[1]` / `user.name`）；
keyword 与字符串键都认；只报第一个问题（一次给模型一个明确目标）。

> 自我修正只在**模型有能力照做**时收敛——schema 若索要模型无从得知的信息，
> 只会空转到 `:max-retries` 耗尽（此时「原样返回而非抛异常」正是兜底）。

### RAG API（检索增强注入，对标 `QuestionAnswerAdvisor`）

```clojure
(require '[im.ttalk.agent.filter.rag :as rag])

(chat-client/build-chat-client
  {:chat-model cm
   :filters [(ma/memory-filter store)
             (rag/qa-turn-filter retriever
                                 :top-k 4                    ;; 缺省 4（同 Spring）
                                 :template (fn [q ctx] ...)  ;; 可选
                                 :inject-when-empty? false)]}) ;; 缺省 false

;; 检索经协议注入——本 ns 不含向量库
(reify rag/IRetriever
  (retrieve [_ query top-k]
    [{:text "..." :metadata {...}}]))
```

挂 `:turn`，故**每 turn 只检索一次**（挂 `:chat` 会在工具循环内每轮重复检索）；
`:resume?` 时跳过；只改写 content 为 string 的用户消息（多模态不动，免得丢图片
片段）。**检索为空时不注入**——刻意偏离 Spring（它注入空上下文 + 拒答指令，
于是检索一落空模型会拒答一切）；要 Spring 语义传 `:inject-when-empty? true`。

### Context API

```clojure
(require '[im.ttalk.agent.context :as ctx])

(ctx/create)                          ;; 空 Context
(ctx/create {:user-id "u1"})          ;; 带变量
(ctx/context? x)                      ;; 谓词
(ctx/get-var ctx :key)                ;; 获取变量
(ctx/set-var ctx :key val)            ;; 设置单个变量（返回新 ctx）
(ctx/set-vars ctx {:a 1 :b 2})        ;; 批量设置
(ctx/conversation-id ctx)             ;; 取会话 id
(ctx/with-conversation-id ctx "u1")   ;; 设会话 id（返回新 ctx）
```

### 中立消息 / 通用 ChatModel

```clojure
(require '[im.ttalk.agent.model.message :as msg]
         '[im.ttalk.agent.chat-model :as chat-model])

;; 中立消息构造
(msg/system "...") (msg/user "...") (msg/assistant "...")
(msg/assistant-tool-calls [(msg/tool-call "id" "name" {:arg 1})])
(msg/tool-result "id" "name" "result")

;; 通用 ChatModel：仅凭协议把任意 provider 包成 ChatModel map
(chat-model/create-chat-model provider {:model "gpt-4" :max-tokens 4096})
;; => {:chat-fn ... :stream-fn ...}
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-core` is the **protocol (port) layer** plus **chat-client primitives**:

- **Protocol / contract**: `ILLMProvider`, neutral messages, unified response, generic ChatModel — any jar implementing the protocol can be injected as a provider
- **ChatClient**: Central orchestrator exposing `invoke-chat` / `invoke-tool` primitives (through the filter onion chain)
- **deftool**: Macro that defines a function and generates its LLM tool schema
- **Advisor**: Onion-style around middleware executor (mirrors Spring AI 2.0 Advisor),
  five hooks — `:chat` / `:tool` / `:iteration` / `:turn` / `:token-xform` — plus the aligned filters
  (ToolSearch, structured-output validation, RAG injection). **Retrieval and vector
  stores are injected through protocols, so this module still has zero external deps.**
- **Context**: Per-request shared state

The Agent runtime (client / ReAct loop / ChatMemory / memory filter / callbacks /
subagent) moved to [`clj-agent-client`](../clj-agent-client/README.md) in 2026-07
(namespaces unchanged); core knows nothing about memory or loops.

### Key APIs

- `chat-client/build-chat-client {:chat-model :tools :filters :settings}` - Declarative chat-client
  construction. Assembly time pre-folds the four around chains (`hooks`) and collects
  every tool declaration into one `tool-meta` table; duplicate tool names and illegal
  `:timeout` / `:retry` values are rejected here rather than surfacing at runtime
- `chat-client/invoke-tool` / `chat-client/invoke-chat` - Primitives through the :tool / :chat filter chains
  (the tool-calling loop lives in `clj-agent-client`, not the chat-client)
- `chat-client/tool-meta` / `serial-tool?` / `return-direct-tool?` / `retry-policy` /
  `effective-tool-timeout` - Tool declaration queries; all thin wrappers over the
  one `tool-meta` table (vars and inline tools share it)
- `chat-client/filter-hooks` / `chat-client/with-filters` - The pre-compiled chains, and the
  supported way to swap a chat-client's `:filters`
- `deftool` - Define tool with auto-generated schema (`:sensitive` / `:serial` / `:retry` / `:return-direct` / `:timeout`)
- `chat-model/create-chat-model` - Wrap any `ILLMProvider` into a chat-client chat-model (protocol-only)
- `ctx/create`, `ctx/get-var`, `ctx/set-var`, `ctx/set-vars`, `ctx/with-conversation-id` - Context
- `filter.tool-search/with-tool-search` - Progressive tool disclosure; bring your own
  index via the `IToolIndex` protocol (mirrors `ToolSearchToolCallingAdvisor`)
- `filter.structured-output/validate-fn` - JSON-Schema predicate for
  `validation-turn-filter`; you inject the JSON parser via `:parse-fn`
  (mirrors `StructuredOutputValidationAdvisor`)
- `filter.rag/qa-turn-filter` - Retrieval injection, once per turn; bring your own
  retriever via the `IRetriever` protocol (mirrors `QuestionAnswerAdvisor`)

Full alignment record: `docs/advisor-alignment-design.md`.
