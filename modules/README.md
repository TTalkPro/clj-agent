# clj-agent 多模块项目

## 模块结构

clj-agent 分为多个独立模块：

```
clj-agent/
├── modules/
│   ├── clj-agent-core/         # 核心框架（macros, store protocol, common）
│   ├── clj-agent-llm/          # LLM Provider + Kernel 架构
│   ├── clj-agent-tools/        # 工具系统（ITool, KernelFunction, KernelPlugin）
│   ├── clj-agent-memory/       # Memory 系统（Store, SnapshotStore, 长短期记忆）
│   ├── clj-agent-rag/          # RAG 检索增强生成
│   └── clj-agent-mcp/          # MCP 服务器
├── scripts/                     # 构建脚本
└── deps.edn                     # 根配置（开发用）
```

---

## 模块说明

### 1. clj-agent-core

**职责**: Kernel 框架（Build/Invoke/Query API）

**包含**:
- `im.ttalk.agent.core.kernel.tool` - deftool 宏（工具函数定义 + var 元数据）
- `im.ttalk.agent.core.kernel.plugin` - KernelPlugin（defplugin + 工具管理）
- `im.ttalk.agent.core.kernel.filter` - Filter 拦截链（Ring-style 中间件）
- `im.ttalk.agent.core.kernel.context` - 调用上下文构建
- `im.ttalk.agent.core.kernel.history` - ChatHistory 管理
- `im.ttalk.agent.core.kernel.core` - Kernel（Build/Invoke/Query API）
- `im.ttalk.agent.core.http.client` - HTTP 客户端（基于 http-kit）
- `im.ttalk.agent.core.common` - defdefault 宏（实例管理）
- `im.ttalk.agent.core.common.result` - Either Monad（Success/Failure）

**依赖**: cheshire, timbre, http-kit

---

### 2. clj-agent-llm

**职责**: LLM Provider 实现 + Service 工厂

**包含**:
- `im.ttalk.agent.llm.provider.*` - LLM 提供商（Anthropic, OpenAI, Zhipu 等）
- `im.ttalk.agent.llm.kernel.chat` - Service 工厂（create-service）

**依赖**: `clj-agent-core`, 第三方库

---

### 3. clj-agent-tools

**职责**: 工具注册和执行系统（高级功能）

**包含**:
- `im.ttalk.agent.tools.protocol` - IToolRegistry + ITool/IToolProvider 重导出
- 其他工具注册表实现

**依赖**: `clj-agent-core`

---

### 4. clj-agent-memory

**职责**: 记忆系统

**包含**:
- `im.ttalk.agent.memory.store.*` - 存储后端（InMemory, SQLite, PostgreSQL, Redis）
- `im.ttalk.agent.memory.snapshot.*` - 快照（StoreBackedSnapshotStore）
- `im.ttalk.agent.memory.long_term.*` - 长期记忆（语义/情景/程序记忆）

**依赖**: `clj-agent-core`

---

### 5. clj-agent-rag

**职责**: RAG 检索增强生成

**包含**:
- `im.ttalk.agent.rag.*` - 文本嵌入、向量存储、RAG 管道

**依赖**: 仅第三方库

---

### 6. clj-agent-mcp

**职责**: MCP 服务器

**包含**:
- `im.ttalk.mcp.*` - MCP 协议实现

**依赖**: `clj-agent-core`, `clj-agent-tools`

---

## 使用方法

### 方式 1: 根目录开发（推荐）

在根目录开发，可以同时加载所有模块：

```bash
# 启动 REPL（加载所有模块）
clojure -M:dev

# 运行所有测试
clojure -M:test
```

### 方式 2: 单模块开发

在单个模块目录中开发：

```bash
cd modules/clj-agent-core
clojure -M:dev
clojure -M:test
```

---

## 构建和发布

```bash
./scripts/build-all.sh      # 构建所有模块
./scripts/install-all.sh    # 安装到本地 Maven
./scripts/test-all.sh       # 测试所有模块
```

---

## 依赖关系

```
clj-agent-llm
    └── clj-agent-core (kernel framework + protocols)

clj-agent-tools
    └── clj-agent-core (kernel/protocol)

clj-agent-memory
    └── clj-agent-core (store protocol)

clj-agent-mcp
    ├── clj-agent-core
    └── clj-agent-tools

clj-agent-core      (独立，无内部依赖)
clj-agent-rag       (独立)
```

架构图：

```
┌──────────┐   ┌──────────┐   ┌──────────┐
│   LLM    │   │  Tools   │   │  Memory  │
│  (Chat)  │   │(Registry)│   │ (Store)  │
└────┬─────┘   └────┬─────┘   └────┬─────┘
     │               │              │
     ▼               ▼              ▼
┌──────────────────────────────────────────┐
│                 Core                      │
│ (Kernel, Plugin, Filter, Protocol, etc.) │
└──────────────────────────────────────────┘

独立模块：
┌──────────┐   ┌──────────┐
│   RAG    │   │   MCP    │
└──────────┘   └──────────┘
```
