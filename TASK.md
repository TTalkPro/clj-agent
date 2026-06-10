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

## P1 — 中危正确性 / 文档不符

- [ ] **BUG5 预构建 :kernel 时 store 脱节**：`core/client.clj:57-66` + `core/common.clj:48-51`。create-agent 另造 store 但不挂进已有 kernel，react 要求同一实例。
- [ ] **BUG react max-iterations 时机**：`core/react.clj:114-120`。检查在工具已执行、结果未落库前抛异常，结果丢失、历史悬空被误标"已取消"。resume 同理。改 `(<= remaining 0)` 并调整落库顺序。
- [ ] **BUG builder 注册守卫**：`provider/factory/builder.clj:17`。注册表非空即跳过内置注册，先注册自定义 provider 再用内置会 Unknown。改为逐个缺失才注册 / delay。
- [ ] **BUG factory 默认 URL 拼接**：`provider/factory/config.clj:171-190`。anthropic 拼出 `/v1/v1/messages`，mistral/ollama 缺 `/v1`。
- [ ] **BUG OpenAI 流式 tool_calls 假定 :index**：`provider/stream/openai.clj:97`。index 缺失回退 :id / 顺序号。
- [ ] **BUG SSE/JSON 解析静默吞错**：`provider/stream/openai.clj:58`、`stream/anthropic.clj:67`、`core/converter/json.clj`。至少计数/打日志。
- [ ] **BUG Retry-After 不受 max-delay 约束**：`provider/http/retry.clj:207`。取 `(min retry-after max-delay)`。
- [ ] **BUG prompt/template `$`/`\` 崩溃**：`core/prompt/template.clj:36`。替换串用 `Matcher/quoteReplacement`。
- [ ] **BUG find-balanced-json 不识别字符串字面量**：`core/converter/json.clj:33`。
- [ ] **BUG 异常只取 .getMessage**：`core/tool.clj:417`、`kernel.clj:221`、`react.clj:72`。nil message 时产出空错误；保留类型/ex-data。
- [ ] **BUG prompt/protocol.clj 未 require clojure.set/clojure.string**：`core/prompt/protocol.clj`、`selector.clj`。靠加载顺序掩盖，AOT 会编译失败。
- [ ] **D4 文档承诺不符**：README Filter API（`:phase/:order/:before/:after`）与实现不符示例必崩；`:openai-compat` provider 不存在；流式未接入 kernel/agent。修正 README + 决定是否补 `:openai-compat`。

## P2 — 设计 / 工程债 / 测试盲区（需较大重构，逐步推进）

- [ ] **BUG2 http-kit 伪流式**：`provider/http/client.clj`。`:as :stream` 全量缓冲，首 token 延迟失效、长生成超时掐断。需替换 HTTP 客户端方案（java.net.http / clj-http streaming）。
- [ ] **D5 错误模型四套并存**：统一错误归一化（ex-info / `{:status :error}` / `[:ok v]` / `{:success bool}`），bailian 流式裸抛 UnsupportedOperationException 纳入通道。
- [ ] **D6 core 泄漏厂商 wire 知识**：`core/model/response.clj:55-159` 等。协议补 `extract-usage`/`extract-finish-reason`，wire 知识收回 provider。
- [ ] **D7 重复抽象**：4 份响应归一化合并；core 双消息体系统一；移除旧 `http/client.clj with-retry`（会重试 4xx）；删协议死方法。
- [ ] **并发未设防**：`core/client.clj:90-136` state-atom check-then-act + reset!；`memory/sqlite.clj:36-57` 共享 Connection 非线程安全。
- [ ] **D9 工程/发布**：provider `:local/root` 进 pom 致发布链断；build.clj scm/url 占位符；clj-http/openai-clojure 死依赖；根 deps.edn 手工拍平。
- [ ] **测试盲区**：流式整链路、并发、timeout/approval filter、max-iterations 超限、工厂 env 配置、provider record 端到端 mock。
- [ ] 其余低优先级：mock error-mock 不抛错、`validate-args` 死代码、`combine :separator` 失效、未知错误默认 retryable、余弦零向量 NaN、ephemeral 会话异常路径泄漏 等。
