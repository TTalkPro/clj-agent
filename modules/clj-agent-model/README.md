# clj-agent-model

LLM Provider 和 Service 工厂模块

[English](#english) | 中文

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              clj-agent-model                                   │
│                                                                              │
│   Layer 1: Provider 实现层                                                   │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐         │
│   │ OpenAI   │ │ Anthropic│ │ Zhipu    │ │ Gemini   │ │ Ollama   │ ...     │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘         │
│                                    ▲                                         │
│   Layer 2: Factory 工厂层 ─────────┘                                         │
│   ┌────────────────┐ ┌────────────────┐ ┌────────────────┐                  │
│   │ registry.clj   │ │ config.clj     │ │ builder.clj    │                  │
│   │ (注册表)       │ │ (配置管理)     │ │ (Provider创建) │                  │
│   └────────────────┘ └────────────────┘ └────────────────┘                  │
│                                    ▲                                         │
│   Layer 3: Service 层 ─────────────┘                                         │
│   ┌─────────────────────────────────────────────────────────────────┐       │
│   │              kernel/chat.clj (创建 Kernel 兼容 Service)          │       │
│   └─────────────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 设计原则

1. **可插拔** - Provider 注册表机制支持动态添加
2. **配置灵活** - 环境变量、代码配置、默认值三级合并
3. **Kernel 兼容** - 输出标准 `{:chat-fn :build-result-msgs}` Service map
4. **延迟加载** - Provider 首次使用时才注册，避免循环依赖

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-model {:local/root "../clj-agent-model"}}}
```

内部依赖：`clj-agent-core`

外部依赖：
- net.clojars.wkok/openai-clojure 0.21.0
- clj-http/clj-http 3.12.3
- cheshire/cheshire 5.12.0
- com.taoensso/timbre 6.3.0

## 支持的 Provider

| Provider | 关键字 | 环境变量前缀 | 说明 |
|----------|--------|-------------|------|
| OpenAI | `:openai` | `OPENAI_*` | GPT 系列，Function Call |
| Anthropic | `:anthropic` | `ANTHROPIC_*` | Claude 系列，Function Call |
| 智谱 | `:zhipu` | `ZHIPU_*` | GLM 系列，Function Call |
| Gemini | `:gemini` | `GOOGLE_*` | Google Gemini |
| Mistral | `:mistral` | `MISTRAL_*` | Mistral |
| Ollama | `:ollama` | `OLLAMA_*` | 本地模型 |
| OpenAI 兼容 | `:openai-compat` | 自定义 | vLLM、LocalAI 等 |
| Mock | `:mock` | - | 测试用 |

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.llm.kernel.chat` | Service 创建（Kernel 兼容） |
| `im.ttalk.agent.llm.factory.builder` | Provider 构建器 |
| `im.ttalk.agent.llm.factory.registry` | Provider 注册表 |
| `im.ttalk.agent.llm.factory.config` | 环境变量配置管理 |
| `im.ttalk.agent.llm.provider.openai` | OpenAI 实现 |
| `im.ttalk.agent.llm.provider.anthropic` | Anthropic 实现 |
| `im.ttalk.agent.llm.provider.zhipu` | Zhipu 实现 |
| `im.ttalk.agent.llm.provider.ollama` | Ollama 实现 |
| `im.ttalk.agent.llm.provider.gemini` | Gemini 实现 |
| `im.ttalk.agent.llm.provider.mistral` | Mistral 实现 |
| `im.ttalk.agent.llm.provider.openai_compat` | OpenAI 兼容 |
| `im.ttalk.agent.llm.provider.mock` | Mock Provider |
| `im.ttalk.agent.llm.schema.openai` | OpenAI Schema 转换 |
| `im.ttalk.agent.llm.schema.anthropic` | Anthropic Schema 转换 |
| `im.ttalk.agent.llm.stream.openai` | OpenAI 流式处理 |
| `im.ttalk.agent.llm.stream.anthropic` | Anthropic 流式处理 |
| `im.ttalk.agent.llm.response.parser` | 响应归一化 |
| `im.ttalk.agent.llm.prompt.template` | Prompt 模板 |
| `im.ttalk.agent.llm.prompt.selector` | Prompt 选择器 |

## 快速开始

```clojure
(require '[im.ttalk.agent.llm.factory.builder :as factory])
(require '[im.ttalk.agent.llm.kernel.chat :as chat])

;; 1. 创建 Provider
(def provider (factory/create-provider-from-env :openai))

;; 2. 创建 Kernel 兼容的 Service
(def service (chat/create-service
               {:provider provider
                :model "gpt-4"
                :max-tokens 4096
                :temperature 0.7}))

;; 3. 注册到 Kernel
(-> (kernel/create-kernel-builder)
    (kernel/add-service service)
    ...)
```

## API 参考

### Provider 创建

```clojure
(require '[im.ttalk.agent.llm.factory.builder :as factory])

;; 基本创建
(factory/create-provider :openai)
(factory/create-provider :anthropic {:api-key "sk-..."})

;; 从环境变量创建
(factory/create-provider-from-env :openai)
;; 使用 OPENAI_API_KEY, OPENAI_BASE_URL 等

;; 使用默认配置 + 用户覆盖
(factory/create-provider-with-defaults :openai {:model "gpt-4-turbo"})

;; 自动配置（合并：默认 < 环境变量 < 用户参数）
(factory/create-provider-auto :openai {:model "gpt-4"})
(factory/create-provider-auto :openai {:api-key "sk-..."} false)  ;; 不读环境变量
```

### Service 创建

```clojure
(require '[im.ttalk.agent.llm.kernel.chat :as chat])

(def service (chat/create-service
               {:provider provider        ;; Provider 实例（必需）
                :model "gpt-4"            ;; 模型名称（必需）
                :max-tokens 4096          ;; 最大 token（默认 4096）
                :temperature 0.7}))       ;; 温度（可选）

;; Service 是一个 map:
;; {:chat-fn           (fn [messages opts] -> {:text "..." :tool-calls [...] :assistant-msg {...}})
;;  :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}
```

### Provider 注册表

```clojure
(require '[im.ttalk.agent.llm.factory.registry :as registry])

;; 注册自定义 Provider
(registry/register-provider! :my-provider
  (fn [opts] (create-my-provider opts)))

;; 查询
(registry/get-providers)          ;; 所有已注册 Provider
(registry/get-factory :openai)    ;; 获取工厂函数
(registry/supported-providers)    ;; 支持的类型列表
```

### 配置管理

```clojure
(require '[im.ttalk.agent.llm.factory.config :as config])

;; 从环境变量加载
(config/load-config-from-env :openai)
;; => {:api-key "sk-..." :base-url "..."}

;; 验证配置
(config/validate-config :openai config-map)
;; => [:ok validated-config] 或 [:error errors]

;; 合并解析
(config/resolve-config :openai user-opts use-env?)
;; => [:ok merged-config] 或 [:error errors]

;; 默认配置
(config/get-default-config :openai)
```

## 环境变量

```bash
# OpenAI
export OPENAI_API_KEY="sk-..."
export OPENAI_BASE_URL="https://api.openai.com/v1"  # 可选
export OPENAI_MODEL="gpt-4"                          # 可选

# Anthropic
export ANTHROPIC_API_KEY="sk-ant-..."
export ANTHROPIC_MODEL="claude-3-5-sonnet-20241022"

# 智谱
export ZHIPU_API_KEY="..."
export ZHIPU_MODEL="glm-4"

# Gemini
export GOOGLE_API_KEY="..."

# Mistral
export MISTRAL_API_KEY="..."

# Ollama（本地部署，无需 API Key）
export OLLAMA_BASE_URL="http://localhost:11434"
```

## 扩展自定义 Provider

```clojure
;; 1. 实现 Provider 创建函数
(defn create-my-provider
  ([] (create-my-provider {}))
  ([opts]
   (let [api-key (or (:api-key opts) (System/getenv "MY_API_KEY"))]
     {:provider-type :my-provider
      :api-key api-key
      :base-url (:base-url opts "http://localhost:8000")})))

;; 2. 注册到 Registry
(registry/register-provider! :my-provider create-my-provider)

;; 3. 使用
(def provider (factory/create-provider :my-provider {:api-key "..."}))
(def service (chat/create-service {:provider provider :model "my-model"}))
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-model` provides a unified LLM provider integration layer with:

- **Provider Registry**: Lazy-loaded provider registration
- **Factory Builder**: Multiple creation methods (manual, env vars, auto-merge)
- **Service Creation**: Wraps providers into Kernel-compatible `{:chat-fn :build-result-msgs}` maps
- **Schema Translation**: Request/response format translation for each provider

### Supported Providers

OpenAI, Anthropic, Zhipu, Ollama, Gemini, Mistral, OpenAI-compatible, Mock

### Key APIs

```clojure
(require '[im.ttalk.agent.llm.factory.builder :as factory])
(require '[im.ttalk.agent.llm.kernel.chat :as chat])

(def provider (factory/create-provider-from-env :openai))
(def service (chat/create-service {:provider provider :model "gpt-4"}))
```

- `factory/create-provider` - Create by type with opts
- `factory/create-provider-from-env` - Create from environment variables
- `factory/create-provider-auto` - Smart config resolution
- `chat/create-service` - Create Kernel-compatible Service
- `registry/register-provider!` - Register custom providers
