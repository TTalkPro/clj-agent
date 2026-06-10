# D5 — 错误模型统一方案

> 状态：📐 设计中（待实施）
> 来源：2026-06-10 全量审查 D5「错误模型四套并存」。
> 目标：把"一次操作失败"在全代码库收敛为**一个错误值 + 一套分层信封约定**，
> 让任意层的调用方用一致方式判断成败、读取错误类型、决定是否重试。

---

## 1. 现状盘点

### 1.1 已经存在的规范错误值（好消息）

`im.ttalk.agent.model.error` 已提供一套完整的**错误值**基础设施，本方案以它为唯一标准，不另造：

```clojure
;; 规范错误 map（errors/error 产出）
{:type       :auth-error          ;; 见下方错误类型表
 :message    "Unauthorized"
 :retryable? false                ;; 由 type 推导，或显式覆盖
 :status     401                  ;; 可选：HTTP 状态码
 :provider   :openai              ;; 可选：来源 provider
 :cause      #<Exception ...>     ;; 可选：原始异常
 :context    {...}}               ;; 可选：诊断上下文
```

配套已有：`error` 构造、`error?` 谓词、`exception->error`、`http-response->error`、
`format-error`、`throw!`、`with-error-handling`、分类谓词（`auth-error?` 等）。

错误类型（`:type`）：

| type | retryable? | 含义 |
|------|-----------|------|
| `:network-error`    | ✅ | 网络/连接 |
| `:timeout-error`    | ✅ | 超时 |
| `:rate-limit-error` | ✅ | 429 限流 |
| `:provider-error`   | ✅ | provider 5xx / 不确定的 provider 侧错误 |
| `:auth-error`       | ❌ | 401/403 |
| `:validation-error` | ❌ | 4xx 参数错误 |
| `:parse-error`      | ❌ | 响应解析失败 |
| 其它未知            | ❌ | 保守不可重试（P1 已修） |

### 1.2 当前并存的「失败」表达（坏消息）

| # | 形态 | 位置 | 性质 |
|---|------|------|------|
| 1 | 抛 `ex-info`（**ad-hoc** data：`:status :body :error :headers :retryable?`） | `provider/common/openai_compat.clj:195,245`、`anthropic.clj:413,480`、`bailian.clj`(P0 后) | **错误值** |
| 2 | `{:status :error :error <canonical>}` | `client.clj:144-160`（SimpleAgent 结果） | **信封** |
| 3 | `[:ok v]` / `[:error e]` 元组 | `model/error.clj:248 with-error-handling`、`factory/config.clj:79 validate-config` | **信封** |
| 4 | `{:success bool ...}` / `{:valid bool :errors [...]}` | `tool.clj invoke`、`converter/json.clj`、`converter/api.clj` | **信封** |
| 5 | 工具异常折成字符串 `"错误: ..."` 喂回 LLM | `kernel.clj:228`、`react.clj:75` | **特殊：LLM 可读** |
| 6 | 裸抛 `UnsupportedOperationException`（无 `:retryable?`，不进通道） | `bailian.clj:236`（流式不支持） | **逃逸** |

### 1.3 根因：两个概念被混在一起

- **错误值（error value）**：描述"这次失败是什么"——类型、消息、可重试、来源。
  → 应当**全局唯一** = §1.1 的 canonical map。
- **结果信封（result envelope）**：一次操作"如何表达成功 vs 失败"——抛异常 / 返回元组 / 返回带状态的 map。
  → 不可能也不该全局只用一种（provider I/O 适合抛异常；用户态 SimpleAgent 适合返回结果 map）。
  应当**按层边界收敛成少数几种、并写成明确规则**。

### 1.4 现状里两个真实 bug（本方案顺带修）

1. **provider ex-info 不走 `errors/error`** → `exception->error`（`error.clj:142`）只看异常 class，
   **不读 ex-data**。于是 provider 已经算好的 `:status 401`/`:retryable? false` 在传到 client
   归一化时**全部丢失**，401 被重新归为 `:provider-error`（而 `:provider-error` ∈ retryable-types）
   → **一个不可重试的 401 认证错误，最终被标成 `retryable? true`**。
2. **bailian 流式裸抛 `UnsupportedOperationException`**：不是 ex-info、无 `:retryable?`，
   `exception->error` 归为 `:provider-error`（同样误判可重试）。

---

## 2. 目标设计

### 2.1 一个错误值

全代码库描述失败一律用 `errors/error` 产出的 canonical map（§1.1）。
provider 不再手搓 ex-info 的 data 形状，改为构造 canonical error 再抛/返回。

### 2.2 一套分层信封约定（4 条规则，按边界定）

| 边界 | 信封 | 理由 |
|------|------|------|
| **A. Provider I/O 失败**（HTTP 4xx/5xx、网络、不支持的能力） | **抛 `ex-info`，data 即 canonical error map**（用 `errors/throw!`） | I/O 失败是异常路径，抛比返回元组更自然；data 标准化后 catch 方零成本拿到结构化错误 |
| **B. 纯函数/可预期失败**（配置校验、结构化解析、参数校验） | **返回 `[:ok v]` / `[:error <canonical>]` 元组** | 失败是预期结果之一，不该用异常控制流；元组是 core 已有约定（with-error-handling/validate-config） |
| **C. 用户态 Agent 结果**（SimpleAgent `chat`/`resume`） | **`{:status :completed|:paused|:error ... :error <canonical>}`** | 面向最终用户、需与 `:completed`/`:paused` 并列；已是现状，保留 |
| **D. 工具结果喂回 LLM** | **字符串**（人类/模型可读，由 `format-error` 生成） | LLM 只能读文本；这是"给模型看"的渲染，不是程序判断用的错误值 |

> 关键：A/B/C/D **不是四套错误，而是同一个 canonical error 在不同边界的封装**。
> 信封之间有明确单向转换：A 抛出的 ex-info → `exception->error` 还原 canonical →
> C 包成 `{:status :error}`；或 → `format-error` 渲染成 D 的字符串。

### 2.3 统一转换枢纽

`errors/exception->error` 升级为**唯一**的"异常 → canonical error"入口，并保证幂等：

```clojure
(defn exception->error
  [e & [context]]
  (cond
    ;; ★ 新增：ex-info 且 data 已是 canonical error → 直接取出（保留 provider 算好的
    ;;   :status/:retryable?/:type），叠加 context。修复 §1.4 bug 1。
    (and (instance? clojure.lang.ExceptionInfo e) (error? (ex-data e)))
    (cond-> (ex-data e) context (assoc :context context))

    (instance? IOException e)                  (error :network-error ...)
    (timeout? e)                               (error :timeout-error ...)
    (instance? UnsupportedOperationException e) (error :validation-error ... {:retryable? false}) ;; 修复 §1.4 bug 2
    :else                                      (error :provider-error ...)))
```

---

## 3. 关键改动点

| 模块 | 改动 | 信封 |
|------|------|------|
| `provider/common/openai_compat.clj` | HTTP 失败：用 `errors/http-response->error` 造 canonical，再 `errors/throw!`（取代手搓 ex-info data） | A |
| `provider/anthropic.clj` | 同上（同步 + 流式建链两处） | A |
| `provider/bailian.clj` | 同步路径已对齐（P0）；流式 `UnsupportedOperationException` → 改抛 `errors/throw!`（`:validation-error`/`:retryable? false`，附 `:feature :stream`） | A |
| `model/error.clj` | `exception->error` 加「ex-data 已是 canonical 则透传」+ 显式识别 `UnsupportedOperationException`（§2.3） | — |
| `model/service.clj` / `model.clj` | provider 调用本就抛异常，service 层**不吞**，让其冒泡到 client（C）或调用方（A）。补：若 service 想返回 `[:error]` 给 kernel，统一走 `with-error-handling` | A→B/C |
| `kernel.clj` invoke-tool | 工具异常已折成字符串喂 LLM（D 正确）；改用 `errors/format-error`（先 `exception->error`）取代裸 `(.getMessage e)`，让模型看到类型/状态 | D |
| `converter/*` | `{:success/:valid bool}` 是**解析结果**而非 error；保留 `:success` 布尔，但**失败分支的 `:error` 值统一为 canonical error map**（取代裸字符串） | B |
| `factory/config.clj` | `validate-config` 已是 `[:ok]/[:error]`；把 `[:error <字符串列表>]` 的 payload 升级为 canonical error（`:validation-error`） | B |
| `client.clj` | 已正确（catch → `exception->error` → `{:status :error}`）。无需改，仅因上游修复后 `:error` 里 `:retryable?`/`:status`/`:type` 变准确而自动受益 | C |

---

## 4. 迁移步骤（每步 `clojure -M:test` 必须全绿）

1. **枢纽先行（零行为回归）**：升级 `exception->error`（透传 canonical ex-data + 识别
   UnsupportedOperationException）。加单测：provider 风格 ex-info 进去，`:status`/`:retryable?`
   不丢；401 → `retryable? false`。
2. **Provider 抛标准错误**：openai_compat / anthropic / bailian 的 HTTP 失败改用
   `errors/http-response->error` + `throw!`。原 ex-info 的 `:body`/`:headers` 等并入 canonical 的
   `:context`。更新 provider 错误相关单测断言（从读 ex-data 裸键 → 读 canonical 键）。
3. **bailian 流式**：`UnsupportedOperationException` → `throw!`（canonical）。更新
   `bailian-registered-and-creatable` 测试（现断言 `thrown? UnsupportedOperationException`
   → 改为 `thrown? ExceptionInfo` + 校验 `:retryable? false`）。
4. **kernel 工具错误渲染**：invoke-tool catch 用 `format-error (exception->error e)`，
   react 同步。加测：工具抛 NPE → 喂 LLM 的字符串含类型而非空。
5. **converter / factory error payload**：失败分支 `:error` 值升级为 canonical map。更新
   converter 测试。
6. **文档**：README「错误归一化」段落改写为本方案的"一个错误值 + 四条边界规则"，
   不再含混宣称全局统一。

> 顺序要点：**第 1 步是安全垫**——先让枢纽幂等透传，后续 provider 即使分批迁移，
> 已迁移的走 canonical、未迁移的走旧 ad-hoc，`exception->error` 两者都能正确还原，
> 中间态不破。

---

## 5. 影响面 / 风险 / 回归

- **影响面**：provider 3 文件 + error.clj + kernel/react + converter/factory + 对应测试。
  client.clj **不动**。
- **主要风险**：provider 错误单测目前直接断言 ex-data 的裸键（`:status`/`:body`），
  迁移后键路径变（并入 `:context`）。须同步改测试——但这正是"统一"的收益落地处。
- **回归测试新增**：
  - `exception->error` 幂等透传 canonical（含 401 → 不可重试）；
  - provider HTTP 401/429/500 → canonical error 的 `:type`/`:retryable?` 正确；
  - bailian 流式 → `ExceptionInfo` + `:retryable? false`；
  - 工具 NPE → 喂 LLM 字符串非空且含类型；
  - SimpleAgent `chat` 遇 provider 401 → 返回 `{:status :error}` 且 `(:retryable? (:error r))` 为 false（端到端验证 §1.4 bug 1 已修）。

---

## 6. 不在本方案范围（避免与其它 P2 项纠缠）

- **不**改 provider 是否抛异常 vs 返回值的根本风格（保留"I/O 抛、纯函数返元组"）。
- **不**做 D6（core 收回 wire 知识）——那是响应**成功**路径的解析，与错误模型正交。
- **不**引入 Result/Either 库或 monad；沿用 core 既有的 `[:ok]/[:error]` 朴素元组。
- **不**改 `{:success bool}` 的布尔字段名（仅统一其失败 payload 为 canonical error）。
