# 待完成任务

> 来源：2026-06-10 全量代码审查（core / provider / 安全 / 架构 四路并行审查，关键结论经 REPL 实测复现）。
> 测试基线：`clojure -M:test` 182 tests / 707 assertions / 0 failures。

## P0 — 高危、改动局部、优先修复 ✅ 全部完成（2026-06-10）

> 测试：182 → 189 tests / 722 assertions / 0 failures。新增 7 个回归测试覆盖各修复。

- [x] **S1 examples RCE**：两处 `calculate` 改为白名单算术求值器 `safe-eval-arith`（仅 `+ - * / mod quot rem`）+ `clojure.edn/read-string`，禁用 `eval`。已验证拦截 `sh`/`getenv`/`eval`。
- [x] **BUG1 Anthropic 流式工具参数丢失**：`provider/stream/anthropic.clj` input_json_delta 累积时非字符串累加器（`{}`）视作空串起步。回归测试 `tool-use-input-accumulation-test`。
- [x] **BUG3 bailian 吞掉 HTTP 错误**：`provider/bailian.clj` do-request 对网络错误 / 4xx/5xx / JSON 解析失败均抛 ex-info（带 `:status :retryable? :request-id`），对齐 anthropic 契约。
- [x] **BUG4 deftool `:default` 失效**：`core/tool.clj` deftool 解构注入 `:or` 默认值 map（普通 + context 工具两分支）。回归测试 `tool_test.clj`。
- [x] **D1 defprovider 全局 config atom 串 key**：`provider/common/base.clj` create-provider-with-opts 改为基于默认值快照创建独立 atom；macro require-api-key? 改查实例 config。回归测试 `multi-instance-config-isolation`。
- [x] **D2 tool-choice 归一化错位**：core 只下发中立关键字（仅有 tools 时）；openai_compat / anthropic 各加 `->wire-tool-choice` 在边界翻译。回归测试 `test-service-chat-fn-tool-choice-without-tools` + 更新旧断言。
- [x] **D3 `extend-type Object` 致 `provider?` 恒真**：`core/model.clj` provider? 改用协议接口 `(:on-interface)` + 非 Object `:impls` 精确判定，保留 Object 默认实现。已验证对 map/string 返回 false。

## P1 — 中危正确性 / 文档不符 ✅ 全部完成（2026-06-10）

> 测试：189 → 194 tests / 744 assertions / 0 failures。新增回归测试覆盖 max-iterations、预构建 kernel、tool-choice wire、template、find-balanced-json、openai-compat。

- [x] **BUG5 预构建 :kernel 时 store 脱节**：`core/advisor/memory.clj` 暴露 `:store`；`core/client.clj` create-agent 在传入预构建 kernel 时复用其 memory-filter 的 store（不同/缺失时 warn）。回归测试 `prebuilt-kernel-reuses-its-store-test`。
- [x] **BUG react max-iterations 时机**：`core/react.clj` 移除 loop 顶部检查，改为「先 invoke-chat 落库上一批结果，仅当 LLM 仍要调工具却无预算时在 execute-batch 前抛出」。同时修复负数 max-iterations 无限循环。回归测试 `max-iterations-exceeded-throws-test` / `negative-max-iterations-does-not-loop-test`。
- [x] **BUG builder 注册守卫**：`provider/factory/builder.clj` 改为逐个补齐缺失的内置 provider（数据驱动 `builtin-providers`），用户先注册自定义 provider 不再屏蔽内置。
- [x] **BUG factory 默认 URL 拼接**：`provider/factory/config.clj` anthropic 去掉多余 `/v1`，mistral/ollama 补 `/v1`，与各自 defprovider 默认对齐。
- [x] **BUG OpenAI 流式 tool_calls 假定 :index**：`provider/stream/openai.clj` key 回退 `(or :index :id (count acc))`。
- [x] **BUG SSE/JSON 解析静默吞错**：`provider/stream/openai.clj` / `stream/anthropic.clj` / `http/client.clj` 三处 catch 改为 `log/warn|debug` 记录（含 data 预览），不再完全无声。
- [x] **BUG Retry-After 不受 max-delay 约束**：`provider/http/retry.clj` 取 `(min retry-after max-delay)`。
- [x] **BUG prompt/template `$`/`\` 崩溃**：`core/prompt/template.clj` 变量名用 `Pattern/quote`、替换串用 `Matcher/quoteReplacement`。回归测试见 prompt api_test。
- [x] **BUG find-balanced-json 不识别字符串字面量**：`core/converter/json.clj` 扫描跳过字符串字面量与转义。回归测试 `structured-parse-brace-in-string-test`。
- [x] **BUG 异常只取 .getMessage**：`core/tool.clj` / `kernel.clj` / `react.clj` 三处回退 `(.getName (class e))`，nil message 不再产出空错误。
- [x] **BUG prompt/protocol.clj 未 require**：`core/prompt/protocol.clj` 补 `clojure.set`/`clojure.string`；`selector.clj` 补 `clojure.set`。
- [x] **D4 文档承诺不符**：README Filter 段改写为真实 `:chat/:tool` API；**实现了 `:openai-compat` provider**（`openai_compat_provider.clj`，base-url 必填，注册进 factory）；README 流式说明改为「仅 provider 层可用，未接入 kernel/agent」。回归测试 `openai-compat-registered-and-creatable`。

## P2 — 设计 / 工程债 / 测试盲区

### ✅ 已完成（2026-06-10，局部、低风险）

> 测试：194 → 196 tests / 754 assertions / 0 failures。

- [x] **D9 工程/发布**：移除死依赖 `openai-clojure`（根+provider）与 `clj-http`（provider）；两个 build.clj scm/url 占位符改为真实仓库 `TTalkPro/clj-agent`；provider build.clj 用 `:override-deps` 把 core 的 `:local/root` 在构建期替换为 `:mvn/version`，修复发布 pom 缺失 core 依赖；README 依赖章节同步。
- [x] **并发设防（SQLite）**：`memory/sqlite.clj` 所有操作 `(locking conn ...)` 串行化，修复共享 Connection 非线程安全 + with-transaction 全局 autocommit 切换的竞态。（InMemoryStore 经核查 swap! 已原子，无需改。）client ns 补「单实例不可并发 chat/resume」声明。
- [x] **低优先级 bugs**：
  - mock `create-error-mock` 现真正抛错（函数型 mock-response 被调用）；`record-history? false` 时 get/clear 不再 NPE。回归测试 `mock-error-and-history-test`。
  - `tool/validate-args` 接入 `invoke`：缺必填参数返回明确错误而非进函数体 NPE。回归测试 `invoke-validates-required-args`。
  - `prompt/api combine` 的 `:separator` 用 `(apply concat opts)` 摊平 kwargs，不再静默失效。
  - `model/error` 未知错误类型默认 `retryable? false`（保守）。回归测试见 model_test。
  - `prompt/selector` 余弦相似度零向量分母为 0 时记 0 分，避免 NaN 排序。
  - `react/invoke` try/catch：ephemeral 会话异常路径也清理，不再泄漏 store 条目。
  - `converter/api validate` 对不支持的 parser fail-closed（返回未通过）而非谎报 `{:valid true}`。
- [x] **D7（部分）**：`http/client.clj with-retry`（会盲目重试 4xx 的重复实现）标记 `^:deprecated` 并在 docstring 警示，引导改用 `http/retry/maybe-with-retry`。

### ⏳ 待办（架构级，建议各自专项推进，勿在常规改动中草率重构）

- [ ] **BUG2 http-kit 伪流式**：`provider/http/client.clj`。`:as :stream` 全量缓冲，首 token 延迟失效、长生成超时掐断。**需替换 HTTP 客户端方案**（java.net.http / clj-http streaming），影响所有 provider 流式路径，须对真实端点验证。
- [ ] **D5 错误模型统一**：把 ex-info / `{:status :error}` / `[:ok v]` / `{:success bool}` 四套收敛为一套；bailian 流式裸抛 UnsupportedOperationException 纳入归一化通道。跨多文件、改契约。
- [ ] **D6 core 收回厂商 wire 知识**：`core/model/response.clj:55-159` 等硬编码各家 usage/finish-reason/thinking 形态。协议补 `extract-usage`/`extract-finish-reason`，wire 知识下沉 provider。
- [ ] **D7（剩余）重复抽象合并**：4 份响应归一化（model.clj / service.clj / response_parser.clj / stream/openai.clj）合并为一处；core 内 `model/types.clj` 与 `model/message.clj` 双消息体系统一；删协议中无人消费的死方法（build-result-messages 等）。
- [ ] **测试盲区（增量）**：流式整链路（call-api-stream / process-sse-stream / 断流 / 非 2xx）、并发、timeout/approval filter、provider record 端到端 mock、工厂 env 配置。
