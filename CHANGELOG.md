# Changelog

本项目版本号形如 `0.x.<git-count>`（各模块同步）。本文件按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 组织。

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

- **Process Framework V1（新模块 `im.ttalk/clj-agent-process`）**：事件驱动的
  步骤编排引擎（≈ SK Process / beamai process）。纯函数式同步 runtime（零外部
  依赖、确定性执行）：builder 校验 + 事件路由 + required-inputs 激活模型 +
  step 私有 state / 共享 context 双层状态；支持线性/Fan-out/Fan-in/循环/
  pause-resume/error-handler/on-quiescent 快照/快照恢复。
- **Process Framework V2 并行引擎（`process.parallel`，2026-07-11）**：core.async
  事件循环，与 V1 并存（同一 spec 两引擎都能跑，event/step/builder 层复用）。
  fan-out 真并行（router/worker go-loop + in-flight 计数完成判定）；ProcessHandle
  外部事件（`start-process`/`send-event`/`get-status`/`wait-for-completion`/
  `stop-process`，`:auto-complete? false` 支持外部事件驱动的常驻 process）；
  单步与全局 `:timeout-ms`；同步 `run-process`/`resume` 与 V1 同构。context
  写回只合并相对执行前快照有变化的 key（并行 step 不互相覆盖）。core.async
  依赖仅进 process 模块。
- **Timeline / Snapshot（`clj-agent-process` 内）**：通用版本链管理
  （时间旅行 go-back/go-forward/goto、分支实验、血缘、prune；in-memory + SQLite
  两个 store）+ Process 快照适配（`checkpointer` 挂 on-quiescent 自动存档、
  `resume-checkpoint` 跨进程重启续跑暂停的流程、分支时间线互不污染）。
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
  DashScope 同步回归、流式建链重试。加上 Process V1/V2 + Timeline 后
  全套 249 tests / 1020 assertions / 0 failures。
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
