# clj-agent-provider

LLM Provider 和 Service 工厂模块

[English](#english) | 中文

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              clj-agent-provider                                   │
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
│   实现 core 的协议 ────────────────┘                                         │
│   ┌─────────────────────────────────────────────────────────────────┐       │
│   │   provider/*.clj 实现 im.ttalk.agent.model/ILLMProvider           │       │
│   │   （call-llm 收中立消息，内部转各厂商 wire；通用 Service 在 core）│       │
│   └─────────────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 设计原则

1. **可插拔** - Provider 注册表机制支持动态添加
2. **配置灵活** - 环境变量、代码配置、默认值三级合并
3. **Kernel 兼容** - 输出标准 `{:chat-fn :build-result-msgs}` Service map
4. **延迟加载** - Provider 首次使用时才注册，避免循环依赖
5. **声明式** - OpenAI 兼容 provider 一律用 `base/defprovider` 一处声明（base-url/env-key/default-model + 可选 `:api-key`/`:require-api-key?`/`:require-model?`），自动生成 config、调用函数与 `create-provider`，消除样板

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-provider {:local/root "../clj-agent-provider"}}}
```

内部依赖：`clj-agent-core`（实现其 `im.ttalk.agent.model/ILLMProvider` 协议）

外部依赖：
- net.clojars.wkok/openai-clojure 0.21.0
- clj-http/clj-http 3.12.3
- http-kit/http-kit 2.8.0
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
| DeepSeek | `:deepseek` | `DEEPSEEK_*` | deepseek-chat / reasoner，Function Call |
| MiniMax | `:minimax` | `MINIMAX_*` | abab 系列（chatcompletion_v2 端点） |
| Ollama | `:ollama` | `OLLAMA_*` | 本地模型 |
| OpenAI 兼容 | `:openai-compat` | 自定义 | vLLM、LocalAI 等 |
| Mock | `:mock` | - | 测试用 |

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.provider.factory.builder` | Provider 构建器 |
| `im.ttalk.agent.provider.factory.registry` | Provider 注册表 |
| `im.ttalk.agent.provider.factory.config` | 环境变量配置管理 |
| `im.ttalk.agent.provider.openai` | OpenAI 实现 |
| `im.ttalk.agent.provider.anthropic` | Anthropic 实现 |
| `im.ttalk.agent.provider.zhipu` | Zhipu 实现 |
| `im.ttalk.agent.provider.ollama` | Ollama 实现 |
| `im.ttalk.agent.provider.gemini` | Gemini 实现 |
| `im.ttalk.agent.provider.mistral` | Mistral 实现 |
| `im.ttalk.agent.provider.deepseek` | DeepSeek 实现 |
| `im.ttalk.agent.provider.minimax` | MiniMax 实现 |
| `im.ttalk.agent.provider.openai_compat` | OpenAI 兼容 |
| `im.ttalk.agent.provider.mock` | Mock Provider |
| `im.ttalk.agent.provider.schema.openai` | OpenAI Schema 转换 |
| `im.ttalk.agent.provider.schema.anthropic` | Anthropic Schema 转换 |
| `im.ttalk.agent.provider.stream.openai` | OpenAI 流式处理 |
| `im.ttalk.agent.provider.stream.anthropic` | Anthropic 流式处理 |
| `im.ttalk.agent.provider.response-parser` | 响应归一化 |
| `im.ttalk.agent.provider.cache` | Anthropic prompt caching 策略层 |
| `im.ttalk.agent.provider.http.retry` | 重试与错误分类（指数退避 + Retry-After） |

## 快速开始

```clojure
(require '[im.ttalk.agent.provider.factory.builder :as factory])
(require '[im.ttalk.agent.model.service :as service])

;; 1. 创建 Provider
(def provider (factory/create-provider-from-env :openai))

;; 2. 创建 Kernel 兼容的 Service
(def service (service/create-service
               provider
               {:model "gpt-4"
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
(require '[im.ttalk.agent.provider.factory.builder :as factory])

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
(require '[im.ttalk.agent.model.service :as service])

(def service (service/create-service
               provider                   ;; Provider 实例（必需）
               {:model "gpt-4"            ;; 模型名称（必需）
                :max-tokens 4096          ;; 最大 token（默认 4096）
                :temperature 0.7}))       ;; 温度（可选）

;; Service 是一个 map:
;; {:chat-fn           (fn [messages opts] -> {:text "..." :tool-calls [...] :assistant-msg {...}})
;;  :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}
```

### Provider 注册表

```clojure
(require '[im.ttalk.agent.provider.factory.registry :as registry])

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
(require '[im.ttalk.agent.provider.factory.config :as config])

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

### 提示词控制（采样参数）

调用 config（即传给 Service / `call-llm` 的 map）按「存在才设」透传，不再强塞默认值：

```clojure
;; Anthropic
{:model "claude-opus-4-8" :max-tokens 4096
 :temperature 0.3 :top-p 0.8 :top-k 40
 :stop ["END"]                 ;; => stop_sequences
 :metadata {:user_id "u1"}
 :thinking {:type "adaptive"}} ;; 原样透传

;; OpenAI 兼容（openai / zhipu / deepseek / ...）
{:model "gpt-4" :max-tokens 512
 :temperature 0.2 :top-p 0.9
 :frequency-penalty 0.1 :presence-penalty 0.2 :seed 7 :n 1
 :tool-choice "auto" :response-format {:type "json_object"}
 :extra-body {:enable_thinking false}}  ;; 各家私有字段逃生通道，直接 merge 进请求体
```

### 缓存控制（Anthropic prompt caching）

策略驱动，自动按渲染顺序 tools→system→messages 注入 `cache_control`（≤4 断点）：

```clojure
{:model "claude-opus-4-8"
 :cache-strategy :system-and-tools  ;; :none | :system | :tools | :system-and-tools | :conversation
 :cache-ttl "1h"}                   ;; nil=5min，"1h"=1小时
```

命中情况见归一化 usage 的 `:cache-read-tokens` / `:cache-write-tokens`
（OpenAI 兼容协议缓存自动生效，无需 `cache-strategy`，命中同样落在 `:cache-read-tokens`）。

### 重试机制（opt-in）

在 config 设 `:retry` 即启用：指数退避 + 满抖动、可重试/不可重试错误二分类、尊重 `Retry-After`：

```clojure
{:model "..." :retry {:max-retries 3 :base-delay 1000 :multiplier 2.0 :max-delay 30000}}
{:model "..." :retry true}   ;; 用默认配置
;; 不设 :retry 则零开销、不改变默认行为
```

可重试：408/409/425/429/5xx/529 及网络层错误；不可重试：其余 4xx。
失败抛 `ex-info`，data 含 `:status :request-id :retryable? :headers`。

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
(def service (service/create-service provider {:model "my-model"}))
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-provider` provides a unified LLM provider integration layer with:

- **Provider Registry**: Lazy-loaded provider registration
- **Factory Builder**: Multiple creation methods (manual, env vars, auto-merge)
- **Service Creation**: Wraps providers into Kernel-compatible `{:chat-fn :build-result-msgs}` maps
- **Schema Translation**: Request/response format translation for each provider

### Supported Providers

OpenAI, Anthropic, Zhipu, Ollama, Gemini, Mistral, OpenAI-compatible, Mock

### Key APIs

```clojure
(require '[im.ttalk.agent.provider.factory.builder :as factory])
(require '[im.ttalk.agent.model.service :as service])

(def provider (factory/create-provider-from-env :openai))
(def service (service/create-service provider {:model "gpt-4"}))
```

- `factory/create-provider` - Create by type with opts
- `factory/create-provider-from-env` - Create from environment variables
- `factory/create-provider-auto` - Smart config resolution
- `service/create-service` - Create Kernel-compatible Service
- `registry/register-provider!` - Register custom providers
