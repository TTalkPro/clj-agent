# Changelog

本项目版本号形如 `0.x.<git-count>`（各模块同步）。本文件按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 组织。

## [0.3.0] - 未发布（2026-07-11，S1：工具阶段 MapReduce 化）

设计与动机见 `docs/agent-loop-concurrency-design.md`（§9 为实施设计）。

### 💥 破坏性变更

- **同一轮的多个 tool-call 并行执行**（虚拟线程；批内任一工具声明
  `{:serial true}` 时整批退化为按序执行）。工具的执行环境从「批内穿线」
  改为「轮初快照」：同批工具互相看不到对方的写（此前语义全仓库零真实使用者）。
- **ToolContext 对工具/filter 只读**。工具写共享状态改走返回值
  `{:result r :writes {k v}}`（任意工具均可，不再要求 `{:context true}`）；
  屏障处按 tool-call 原始序经 `build-kernel` 新增的 `:state-slots` 槽级
  reducer 折叠（未声明槽默认 last-writer，冲突记 warn）。失败/超时/被拒的
  调用 `:writes` 不生效（单工具事务性）。
- **`kernel/invoke-tool` 返回 `{:value (:writes)}`**（原 `{:value :context}`）。
  跨轮折叠由调用方（react 循环）负责；直调方自行用 `context/apply-writes`。
- **tool filter 响应契约收窄为 `{:result (:writes)}`**：响应侧 `:context`
  移除——filter 短路分支不再需要手工回传 `(:context req)`（原易错点）。
- **工具/inline handler 返回值不再按「含 `:result` 的 map」拆包**，判据改为
  「含 `:writes`」；返回 `{:result ...}` 包装的旧 inline handler 需改为直接
  返回值（subagent delegate 已随迁）。
- `on-tool-result` 回调改为任务完成时实时触发，批内顺序不确定
  （确定顺序请读 `:tool-calls-made`）。

### ✨ 新增

- `context/apply-writes`：批次写折叠纯函数（槽级 reducer + conflict 上报）。
- `build-kernel :state-slots`：状态槽合并语义声明。
- `deftool {:serial true}` / inline 工具 `:serial` 键 + `kernel/serial-tool?`。
- **工具失败分层路由（S2，纯增量）**：`model.error/classify-exception`
  （显式 `ex-data :error-class` > canonical `:retryable?`/`:auth-error` >
  常见网络异常 > 缺省 `:semantic`）；`invoke-tool`/`execute-batch` 透出
  `:error {:class :message}` / `:errors`。
- **瞬态类自动重试**：`deftool {:retry true|{:max-retries n :initial-delay-ms ms}}`
  （幂等工具 opt-in），仅 `:transient` 类错误指数退避重试，对模型透明；
  timeout-filter 的超时结果标 `:transient`。
- **环境类屏障暂停（HITL）**：react `:on-env-error :pause|:proceed`（缺省
  :proceed）——环境类失败在屏障处带一致快照暂停；resume 决策 `:retry`
  （重跑失败调用，结果按 tool-call-id 替换进原批次）| `:proceed`（错误交给
  模型）。client 层配置了 `:on-tool-call` 的 HITL agent 自动 `:pause`，
  `resume` 接受 `"retry"/"approved"` 表示环境已修复。
- 新语义测试 9 个：批内真并行证明、快照隔离、last-writer 按调用序（非完成序）、
  失败 writes 丢弃、messages/records 原序、serial 整批退化、reject 语义、
  reducer 折叠与跨轮累积。全套 213 tests / 870 assertions / 0 failures。

## [0.2.0] - 未发布（2026-07-10 定稿）

v0.2 是一次**破坏性**版本：统一消息/tool-call 词汇、模块重组、删除无消费者的子系统。
从 v0.1 迁移请看文末[迁移指南](#从-v01-迁移到-v02)。

### 💥 破坏性变更

- **tool-call 全库统一为 `{:id, :name(字符串), :args}`**（原响应层为
  `{:id, :name(keyword), :input}`，与中立历史层两套词汇并存靠桥接互转）。
  影响：`on-tool-call` 回调收到的工具名从 keyword 变为**字符串**；读取响应
  `:tool-calls` 的代码须把 `:input` 改为 `:args`。
- **删除 `im.ttalk.agent.model.types`**：`make-tool-call` → 用
  `im.ttalk.agent.model.message/tool-call`；4 个字符串-role 消息构造器
  （`user-message` 等）→ 用 `model.message` 的中立构造器（keyword role）。
  `im.ttalk.agent.provider.api` 的同名 re-export 已重指向中立构造器（返回形状变化）。
- **流式 `on-token` 回调不再携带 `:accumulated` / `:reasoning-accumulated`**，
  只发增量 `{:token :index}` / `{:reasoning-token}`。需要全文请自行累积
  （每 token 物化全文是 O(n²) 的根源）。
- **模块重组：新增 `im.ttalk/clj-agent-client`**。agent 运行时
  （`client`/`react`/`memory`/`memory.sqlite`/`advisor.memory`/`callbacks`/
  `subagent.*`/`common` 共 8 个 ns）自 core 迁入；**命名空间不变**，仅依赖坐标变化。
  core 现为零依赖纯 Clojure（协议 + kernel 原语），timbre/next.jdbc/sqlite-jdbc
  依赖随迁 client。
- **删除 `converter.*` 与 `prompt.*` 两个子系统**（结构化输出解析 / LangChain 式
  提示词模板，共 10 个 ns，约 2900 行）：无任何 runtime 消费者。如需结构化输出，
  用 provider 侧原生 `:response-format`（`json_object` / `json_schema`+`strict`）。
- **死代码清理**（均无仓库内调用方）：kernel 的 `get-tool-var`/
  `list-functions-by-tag(-s)`/`list-functions-with-all-tags`；tool 的
  `get-params`/`sensitive?`/`get-category`/`has-tag?`；error 的
  `throw-if-error!`/`safe-execute`；common 的 `ensure-kernel`；
  `http/client.clj` 已废弃的 `with-retry`（此前已标 deprecated）。

### 🐛 修复

- **DashScope 同步调用必抛 `:parse-error`**：http-kit → java.net.http 迁移后
  body 被二次 JSON 解析（ClassCastException），成功响应也被误报；现按
  `:success?` 分派。
- **deftool 工具在 DashScope 下参数静默丢失**：其 schema 转换不识别
  `:input_schema`（deftool 宏生成的键）；现复用 `schema.openai`（两种键名都认）。
- **PATCH 请求必然运行时崩溃**：`HttpRequest.Builder` 没有 `.PATCH` 方法，
  反射解析失败；改走 `.method "PATCH"`。
- **factory 环境变量读取从未生效（`:api-key`/`:base-url`）**：生成的变量名带
  连字符（如 `OPENAI_API-KEY`），POSIX 不可设置；现连字符转下划线
  （`OPENAI_API_KEY`）。
- **发布构建链从未跑通**（三个叠加 bug，本次全修）：
  ① build.clj require 了不存在的 `deps-deploy.deploy-deps`（实为 `deps-deploy.deps-deploy`）
  → 加载即失败；② `b/install` 误传 `:src-dirs` 缺必填 `:class-dir` → install 必错；
  ③ `b/create-basis` 无顶层 `:override-deps` 参数（被静默忽略）→ client/provider 的
  pom 一直缺失 core 依赖，消费方解析即断；现经 alias 启用 override。
  已验证：三模块 jar + pom 正确产出并 install，纯 mvn 坐标的消费端项目可解析并运行。
- **子 agent 状态竞态**：`spawn!`/`restart!` 曾在注册表条目写入前启动 worker，
  秒完成的任务状态永远卡 `:running`；`kill!` 后 `await!`/`result` 曾返回 nil，
  且被中断的 worker 会把 `:killed` 覆盖成 `:failed`。现先登记后启动 +
  `finish!` 终态守卫 + `kill!` 写入明确 `{:error :killed}`。

### ⚡ 性能

- **流式累积 O(n²) → O(n)**：stream/{openai,anthropic,dashscope} 的所有累积器
  （正文/推理/块内文本/工具参数 JSON）改 StringBuilder 原地 append。
- **`tools->schemas` 有界缓存**：ReAct 循环每轮 LLM 调用不再对不变的工具列表
  重跑 wire 转换（openai/anthropic 两个 schema 命名空间）。
- **HttpClient 单例共享**：流式与非流式此前各持一个虚拟线程连接池，现共用。
- **子 agent 改虚拟线程**：`subagent/manager` 从 clojure `future`（无界平台
  线程池）改为 `newVirtualThreadPerTaskExecutor`（`kill!` 语义不变）。
- **反射清零**：全部 60 个 src 命名空间开启 `*warn-on-reflection*` 并修复
  暴露的反射/装箱点；新增反射会在编译期告警。

### ✨ 新增

- **流式建链重试（opt-in）**：`post-stream-sync` 支持 `:retry`（约定同
  `http.retry/maybe-with-retry`：`true`=默认配置 / map=合并），仅当错误可重试
  **且尚未流出任何 token** 时指数退避重试；provider config 传 `:retry` 即启用。
- **`stream_client/post-stream-sync`**：流式同步编排（promise 对 / cancel 登记 /
  结果分派）统一入口，openai-compat / anthropic / dashscope 三路共用。
- **`http/client.clj/response->error`**：HTTP 失败 → canonical error 的统一实现。

### 🔧 内部 / 测试

- 测试基线 196 → 204 tests / 840 assertions / 0 failures；新增覆盖：
  timeout/approval filter（含后台中断不泄漏线程）、factory 配置（env 名规范/
  三级合并/validate 全分支）、SQLite 与 InMemory 并发写、子 agent 生命周期、
  DashScope 同步回归、流式建链重试。
- Process Framework（V1 同步引擎 / V2 core.async 并行引擎 / Timeline 快照，
  曾以 `clj-agent-process` 第四模块形态完整落地并全绿）在发布前整体撤下——
  设计经评审判定需重新思考（V1/V2 快照与并行的语义撕裂，对照 SK Process /
  MS Agent Framework superstep 模型）；未进入任何发布版本，设计文档留档。
- `client_test` 的 httpbin.org 外网用例本地化（com.sun.net.httpserver），
  消除 CI flake。
- 设计文档状态与代码对齐：memory-filter-refactor（已完成）、onion-filter
  （全部实施，落地为 clj-agent-client）、streaming-async-design（全部落地）、
  response-path-consolidation（双消息体系统一完成）。

---

## 从 v0.1 迁移到 v0.2

### 1. 依赖坐标（若直接引用 core 且使用 agent API）

```clojure
;; v0.1
{:deps {im.ttalk/clj-agent-core {...}
        im.ttalk/clj-agent-provider {...}}}

;; v0.2 —— agent 运行时在新模块（命名空间不变，require 无需改动）
{:deps {im.ttalk/clj-agent-client {...}      ;; 传递引入 core
        im.ttalk/clj-agent-provider {...}}}
```

### 2. tool-call 形状

```clojure
;; v0.1：响应层 tool-call
{:id "c1" :name :get-weather :input {:city "北京"}}
;; v0.2：全库统一
{:id "c1" :name "get-weather" :args {:city "北京"}}
```

- 读 `(:input tc)` → 改 `(:args tc)`
- 匹配 `(= :get-weather (:name tc))` → 改 `(= "get-weather" (:name tc))`
- `on-tool-call` 回调：`(fn [name args] ...)` 中 name 现为字符串

### 3. 构造器

```clojure
;; v0.1                                    ;; v0.2
(types/make-tool-call id name input)       (msg/tool-call id name args)
(types/user-message "hi")                  (msg/user "hi")        ; {:role :user ...}
(types/assistant-message "ok")             (msg/assistant "ok")
(types/system-message "sys")               (msg/system "sys")
(types/tool-message id content)            (msg/tool-result id name content)
;; msg = im.ttalk.agent.model.message；注意中立消息 role 为 keyword
```

### 4. 流式 on-token

```clojure
;; v0.1：可以直接读全文
(fn [{:keys [token accumulated]}] (render! accumulated))
;; v0.2：自行累积
(let [acc (StringBuilder.)]
  (fn [{:keys [token]}] (when token (.append acc token)) (render! (str acc))))
```

### 5. 结构化输出（converter 已删除）

直接用 provider 原生能力（OpenAI 兼容系列）：

```clojure
{:model "..." :response-format {:type "json_schema"
                                :json_schema {:name "Person" :strict true
                                              :schema {...}}}}
```
