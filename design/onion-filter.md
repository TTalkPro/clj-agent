# 设计：洋葱式 Filter + kernel 瘦身（loop / memory 下沉 simpleagent）

> 状态：🚧 **机制层已实施；模块下沉未做**（2026-07-10 核对）。
>
> | 决策 | 状态 | 落地位置 |
> |---|---|---|
> | 4. 洋葱式 Filter（`build-chain`，before/after 为语法糖） | ✅ 已实施 | `advisor.clj`（filter.clj 落地时改名） |
> | 5. chat/tool 复用同一执行器，仅 terminal 与 filter 集不同 | ✅ 已实施 | `kernel.clj` invoke-chat/invoke-tool 均走 build-chain |
> | 1. kernel 去 `:memory`（record 层面零感知） | ✅ 已实施 | `kernel.clj` Kernel record 无 :memory 字段 |
> | 内置 filter（logging/timeout/approval）改 `:tool` around | ✅ 已实施 | `advisor.clj` |
> | 2. 工具循环下沉 simpleagent | ❌ 未做 | 仍在 core 的 `react.clj`（invoke/resume/execute-batch/run-tool-loop/heal） |
> | 3. ChatMemory 下沉 simpleagent | ❌ 未做 | 协议/store 仍在 core 的 `memory.clj` + `memory/sqlite.clj`（§5 验收标准「core 内 grep ChatMemory 零命中」当前不成立） |
>
> **下沉未做的原因与去留**：`simpleagent` 独立模块始终未创建（现只有 clj-agent-core /
> clj-agent-provider 两模块，agent 门面为 core 的 `client.clj`）。当前没有第二个
> loop/memory 消费者，下沉收益主要是「core 真正 memory 无关」的架构洁癖；
> 排期 v0.2，或修订本设计目标接受「record 层零感知即可」。
>
> 本文合并本轮架构讨论的全部决策：
> 1. kernel 的不可约内核 = `invoke-chat` + `invoke-tool` + Filter 机制 + 统一 Request/Response。
> 2. **工具调用循环**（invoke/resume/execute-batch/run-tool-loop/heal）下沉到 simpleagent。
> 3. **ChatMemory（协议 + store + filter）**下沉到 simpleagent；kernel 去掉 `:memory`，对"记忆"零感知。
> 4. filter 由"4 类型扁平 fold"升级为**洋葱式 Filter**：根抽象 `around(req, chain)`，`before/after` 为语法糖。
> 5. chat 与 tool **复用同一个洋葱执行器**，只是 terminal 与 filter 集不同。
>
> 前序设计见 [[unified-invoke-agent]]、[[memory-filter-refactor]]。

---

## 1. 背景与动机

当前 `filter.clj` 是两条独立的扁平 fold（pre-chat 正序跑完 → LLM → post-chat 正序跑完），且：

- Memory Filter 被拆成 **pre + post 两个注册**，靠 context 里的 conv-id 互相协调。
- `Kernel` record 持有 `:memory` 字段（kernel.clj:56），仅为喂给自动挂载的 memory filter + `invoke` 的 heal/cleanup。
- 工具循环 `invoke`/`resume` 焊在 kernel 里，是"策略"而非"原语"。
- filter 拿不到下游，无法做 around（缓存命中跳过 LLM、重试、跨 before→after 计时）。

目标终态：

- **kernel** = LLM 原语（`invoke-chat`）+ 工具原语（`invoke-tool`）+ 洋葱 Filter 机制 + 统一 Request/Response。**不认识 memory、不认识循环。**
- **simpleagent** = 工具循环 + ChatMemory（协议/store/filter）+ 状态包装。

---

## 2. 核心机制：洋葱 Filter

### 2.1 Filter 形态

```clojure
;; Filter 是个 map：
{:name        :memory          ; 标识
 :phase       :chat            ; :chat | :tool —— 决定挂到哪条链
 :order       100              ; 越小越靠外层（最先 before、最后 after）
 ;; 二选一：
 :around (fn [req chain] -> resp)         ; 高级：完整 around
 ;; 或语法糖：
 :before      (fn [req] -> req')               ; 普通：只改写
 :after       (fn [resp] -> resp')}
```

`chain` 是 `(fn [req] -> resp)`，代表"下游（后续 filter + 最内层 terminal）"。

### 2.2 执行器（形状无关，chat/tool 共用）

```clojure
(defn- ->around [filter]
  (or (:around filter)
      (let [before (or (:before filter) identity)
            after  (or (:after  filter) identity)]
        (fn [req chain] (after (chain (before req)))))))

(defn build-chain
  "把 filters（按 order 排序）折成洋葱，最内层 terminal 真正干活。
   order 最小的在最外层：req 从外向里穿 before，resp 从里向外穿 after。"
  [filters terminal]
  (reduce (fn [downstream filter]
            (let [advise (->around filter)]
              (fn [req] (advise req downstream))))
          terminal
          (reverse (sort-by :order filters))))
```

折叠结果 `a1(a2(a3(terminal)))`。这是真洋葱：单个 filter 的 before/after 段共享闭包局部状态，可 try/finally、可重试、可缓存短路（不调 `chain`）。

### 2.3 旧概念的"溶解"

| 今天（扁平） | 洋葱后 |
|---|---|
| `:action :skip :value v` | filter 不调 `chain`，直接返回 resp |
| `:action :error :reason r` | 直接 throw，或返回错误 resp（下游可 try/catch） |
| pre-chat + post-chat **两个** filter | **一个** filter 的 before/after 段 |
| `:pre/post-invocation` + `:pre/post-chat` 四类型表 | 一个 `:phase :chat\|:tool` + 一个 `build-chain` |
| `apply-pre-chat-filters` / `apply-post-chat-filters` 等 4 个入口 | `build-chain` + 两个 terminal |

---

## 3. 统一 Request / Response

```clojure
;; ChatRequest —— chat filter 能改写的全在此（比今天多了 tools/tool-choice/system-prompt）
{:messages     [...]          ; 中立消息
 :tools        [...]          ; 工具 schema
 :tool-choice  :auto
 :system-prompt "..."
 :context      {...}}         ; ToolContext（conv-id 在这）

;; ChatResponse
{:response <ILLMResponse> :context {...}}

;; ToolRequest —— tool filter 改写的
{:function {:name ... :schema ...} :args {...} :context {...}}
;; ToolResponse
{:result <any> :context {...}}
```

> 收益：chat filter 现在能注入工具、改 tool-choice、改 system-prompt（今天只能改 messages）。

---

## 4. invoke-chat / invoke-tool：洋葱包裹一次终点调用

```clojure
(defn invoke-chat [kernel req]
  (let [terminal (fn [r] (call-llm (:service kernel) r))   ; 最内层：真正调 LLM
        chat-filters (filter #(= :chat (:phase %)) (:filters kernel))
        chain (build-chain chat-filters terminal)]
    (chain req)))

(defn invoke-tool [kernel fn-key args context]
  (let [func (find-function kernel fn-key)
        terminal (fn [r] (assoc r :result (apply-fn func (:args r) (:context r))))
        tool-filters (filter #(= :tool (:phase %)) (:filters kernel))
        chain (build-chain tool-filters terminal)]
    (chain {:function func :args args :context context})))
```

一套机制，两个终点。

---

## 5. Memory 作为 filter：ChatMemory 纯闭包，kernel 零感知

```clojure
;; ↓ 这段连同 ChatMemory 协议/store，全部住在 simpleagent
(defn memory-filter [store]
  {:name :memory :phase :chat :order 100
   :around
   (fn [req chain]
     (if-let [cid (get-in req [:context :conversation-id])]
       (do
         (mem-add store cid (:messages req))                          ; before 段：存 delta
         (let [resp (chain (assoc req :messages (mem-get store cid)))] ; 展开历史 → 下游
           (mem-add store cid [(->neutral (:response resp))])         ; after 段：存回复
           resp))
       (chain req)))})
```

验收标准：`grep ChatMemory` 在 `clj-agent-core` 内**零命中**。

---

## 6. 工具循环移交 simpleagent

`invoke`/`resume`/`execute-batch`/`run-tool-loop`/`heal-dangling-tool-calls!`，连同其 kernel 私有依赖 `build-chat-opts`、`filter-tools-by-tags`，整体搬到 simpleagent（如 `simpleagent/loop.clj`）。循环内部：

- 构造 ChatRequest（含 tags 过滤后的 tools） → `kernel/invoke-chat`。
- tool_calls → `kernel/invoke-tool` 逐个执行（execute-batch + gate）。
- heal/ephemeral 不变量留在 simpleagent。

> 注意：heal（悬空 tool_use 自愈）原本覆盖"直连 kernel 的循环用户"，下沉后**仅 simpleagent 受保护**。这与"kernel 不再拥有循环"自洽，需接受。

---

## 7. kernel 变更清单

### Record
```clojure
;; 前
(defrecord Kernel [service filters tools tool-vars settings memory])
;; 后
(defrecord Kernel [service filters tools tool-vars settings])
```

### API delta

| API | 处置 |
|---|---|
| `create-kernel-builder` / `add-tool(s)` / `add-service` | 保留 |
| `add-filter` | 改名 `add-filter`（接收 filter map；破坏性） |
| `add-memory` | **删除**（memory 不再是 kernel 概念） |
| `build-kernel` | 删掉 memory 默认 store + memory filter 自动挂载；`:filters`→`:filters` |
| `invoke-chat` | 保留，签名改 `(kernel chat-request)`，内部走 `build-chain` |
| `invoke-tool` | 保留，内部走 tool 链 |
| `find-function` / `list-functions*` / `get-tool-var` | 保留 |
| `invoke` / `resume` / `execute-batch` / `run-tools` / `heal-dangling-tool-calls!` | **移出** → simpleagent |
| `build-chat-opts` / `filter-tools-by-tags`（私有） | 移出 → simpleagent |

### 内置 filter
`logging-pre/post`、`error-handling`、`timeout`、`approval` → 改写为 `:phase :tool` 的 tool-filter（before/after）。`approval` 与 simpleagent 的 gate 暂停是两套机制，保留前者为同步 stdin 审批。

---

## 8. 文件移动清单

| 文件 | 动作 |
|---|---|
| `core/kernel/filter.clj` | 重写为 filter 机制（`->around`/`build-chain`/`add-filter`/内置 tool-filter） |
| `core/kernel/memory_filter.clj` | **移** → `simpleagent/memory_filter.clj`，改写为 around filter |
| `core/memory.clj` | **移** → `simpleagent/memory.clj`（协议 + InMemory + Windowed） |
| `core/memory/sqlite.clj` | **移** → `simpleagent/memory/sqlite.clj` |
| `core/kernel.clj` | 删 record `:memory`、`add-memory`、auto-mount、loop 系列；`invoke-chat`/`invoke-tool` 改走 `build-chain` |
| `simpleagent/loop.clj`（新） | 接收 invoke/resume/execute-batch/run-tool-loop/heal |
| `simpleagent/common.clj` | `build-kernel` 改 `add-filter`，显式挂 `memory-filter`（替代 `add-memory`） |
| `simpleagent.clj` | `chat`/`resume` 改调 `simpleagent.loop/*` |
| `core/deps.edn` | next.jdbc / sqlite-jdbc 从 core 移到 simpleagent（sqlite store 走了） |
| `simpleagent/deps.edn` | 增 next.jdbc / sqlite-jdbc |

---

## 9. 测试搬迁与等价性验证

| 测试 | 处置 |
|---|---|
| `memory_test`：`in-memory-*` / `windowed-*` | 移 → simpleagent（store 测试随 store 走） |
| `memory_test`：`memory-filter-*` | 移 → simpleagent，改写为 memory-filter 测试 |
| `context_test`：`filter-*`（pre-invocation） | 留 core，改写为 tool-filter 测试 |
| `agent_test`（simpleagent） | 留，调整 require；pause/resume/heal 用例不变（验证行为等价） |
| **新增** core | `build-chain` 洋葱序测试（before 正序 / after 逆序）、短路（不调 chain）、around（计时/重试 filter 样例） |

等价性基线：重构前 `clojure -M:test` = 128 tests / 447 assertions / 0 failures（除无关 http 网络超时）。重构后须保持行为等价，尤其：
- 无 filter == 裸 LLM 调用；
- memory filter 串历史结果与今天逐字节一致；
- simpleagent pause / approved / rejected / mixed / 未-resume 五个场景不回归。

---

## 10. 兼容性与风险

- **破坏性 API**：`add-filter`→`add-filter`、`add-memory` 删除、`kernel/invoke` 移出、`invoke-chat` 签名变。属内部框架重构，影响 examples + 测试，已纳入清单。
- **around 脚枪**：filter 作者须恰好调 `chain` 一次（漏调 = 静默丢下游；多调 = LLM 跑两遍）。文档 + `before/after` 糖默认覆盖，降低裸写 `around` 的频率。
- **core 依赖收敛**：next.jdbc / sqlite-jdbc 随 sqlite store 离开 core；core 依赖进一步变轻（延续删 process 的方向）。
- **heal 覆盖面收窄**：见 §6。

---

## 11. 实施顺序（分阶段，每阶段保持测试绿）

1. **P1 — 洋葱执行器**：在 filter.clj 落 `->around`/`build-chain`，新增 core 单元测试（洋葱序/短路/around）。暂不改 invoke-chat。
2. **P2 — invoke-chat/invoke-tool 切换**：改走 `build-chain`；旧 `apply-*-filters` 退役。tool 内置 filter 转 tool-filter，迁 `context_test`。
3. **P3 — memory 下沉**：移 memory.clj / sqlite.clj / memory_filter 到 simpleagent；删 kernel `:memory`/`add-memory`/auto-mount；common.clj 显式挂 filter；迁 memory 测试；挪 deps。
4. **P4 — loop 下沉**：移 invoke/resume/execute-batch/run-tool-loop/heal + 私有助手到 `simpleagent/loop.clj`；simpleagent 改调；agent_test 验证等价。
5. **P5 — 收尾**：examples / README 同步；全量回归对齐基线。
