# clj-agent 多模块项目

## 模块结构

clj-agent 采用**依赖倒置(DIP)**的两层划分:**Core 定义协议(端口)+ Agent 运行时;Provider 实现协议(适配器)并依赖 Core。** 任何实现了 `im.ttalk.agent.model/ILLMProvider` 的 jar 都能作为 provider 注入 Core 的 agent。

```
clj-agent/
├── modules/
│   ├── clj-agent-core/      # 协议(端口) + Agent 运行时;零内部依赖
│   └── clj-agent-provider/  # 各厂商适配器(实现协议),依赖 core
├── scripts/
└── deps.edn                 # 根配置(开发/测试一次性加载两模块)
```

依赖方向:**Provider → Core**(实现依赖抽象)。应用层在运行时构造一个 provider 注入 agent。

---

## 模块说明

### 1. clj-agent-core — 协议 + Agent 运行时

**职责**: 定义 LLM 抽象契约(端口),并在其上构建 Agent 运行时。**不依赖任何 provider 实现**。

**契约 / 端口**(`im.ttalk.agent.model.*`):
- `im.ttalk.agent.model` — `ILLMProvider` 协议(≈ Spring AI `ChatModel`),以**中立消息**为边界
- `im.ttalk.agent.model.message` / `.response` / `.error` / `.types` — 中立消息、统一响应、错误、构造器
- `im.ttalk.agent.model.service` — **通用** `create-service`:仅凭协议把任意 provider 包成 kernel service

**Agent 运行时**:
- `im.ttalk.agent.kernel` - Kernel（build-kernel / invoke-chat / invoke-tool）
- `im.ttalk.agent.tool` - deftool 宏（≈ `ToolCallback`）
- `im.ttalk.agent.advisor` / `.advisor.memory` - Advisor 洋葱链 / 记忆 advisor（≈ `CallAdvisorChain` / `MessageChatMemoryAdvisor`）
- `im.ttalk.agent.context` - 请求级上下文
- `im.ttalk.agent.converter.*` / `.prompt.*` - 结构化输出 / 提示词模板（≈ `OutputConverter` / `PromptTemplate`）
- `im.ttalk.agent.memory` / `.memory.sqlite` - ChatMemory
- `im.ttalk.agent.react` - ReAct 工具调用循环
- `im.ttalk.agent.client` - 高级 Agent API（≈ `ChatClient`）
- `im.ttalk.agent.common` - 共享 Kernel 构建

**依赖**: 无内部依赖;cheshire, timbre, next.jdbc, sqlite-jdbc

---

### 2. clj-agent-provider — 厂商适配器

**职责**: 实现 `im.ttalk.agent.model/ILLMProvider`;`call-llm` 收**中立消息**、内部转各厂商 wire 格式再请求 API。

**包含**（`im.ttalk.agent.provider.*`）:
- `im.ttalk.agent.provider.{openai,anthropic,zhipu,ollama,gemini,mistral,deepseek,minimax,bailian,mock}` - 各 provider 实现
- `im.ttalk.agent.provider.common.{base,openai-compat,cache,response-parser}` - 辅助层：provider 基座/OpenAI 协议层/Anthropic 缓存策略/响应解析
- `im.ttalk.agent.provider.{wire,schema,stream}.*` - wire 格式 / 工具 schema / 流式解析（provider 内部）
- `im.ttalk.agent.provider.http.{client,retry}` - HTTP 客户端 / 重试与错误分类
- `im.ttalk.agent.provider.factory.*` - Provider 注册/配置/创建
- `im.ttalk.agent.provider.api` - Provider 统一门面

**依赖**: `clj-agent-core`(协议/契约), openai-clojure, clj-http, http-kit, cheshire, timbre

---

## 使用方法

### 方式 1: 根目录开发（推荐）

```bash
clojure -M:test          # 运行所有测试
```

### 方式 2: 单模块开发

```bash
cd modules/clj-agent-core
clojure -M:dev
clojure -M:test
```

---

## 依赖关系

```mermaid
graph LR
    core[clj-agent-core<br/>协议 + Agent 运行时]
    provider[clj-agent-provider<br/>厂商适配器]

    provider --> core
```

> 第三方只需依赖 `clj-agent-core`、实现 `im.ttalk.agent.model/ILLMProvider`，即可作为 provider 被 agent 使用。
