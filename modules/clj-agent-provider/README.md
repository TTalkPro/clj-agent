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
| OpenAI | `:openai` | `OPENAI_*` | GPT 系列，Function Call；`:parallel-tool-calls`、`:reasoning-effort`/`:verbosity`（o 系列 / GPT-5）、结构化输出（`json_object` / `json_schema`+`strict`）、多模态输出（`:modalities`/`:audio`）；prompt cache 命中归一化到 `:cache-read-tokens` |
| Anthropic | `:anthropic` | `ANTHROPIC_*` | Claude 系列，Function Call；服务端 `web_search` 工具、Citations 引用、Skills（beta）、`:service-tier`、prompt caching 策略层（含 `:tool-results` / `:system-and-conversation`）、响应限流头解析（`:rate-limit`） |
| 智谱 | `:zhipu` | `ZHIPU_*` | GLM 系列（glm-5/4.7/4.6），**双协议**：OpenAI 兼容（默认，对话补全文档字段全量支持：`:thinking/:do-sample/:tool-stream/:request-id/:user-id`、预置工具 web_search/retrieval/mcp 透传）+ Anthropic 兼容（`create-anthropic-provider`）；**异步任务**：`submit-async`/`await-async-result` |
| Gemini | `:gemini` | `GOOGLE_*` | Google Gemini |
| Mistral | `:mistral` | `MISTRAL_*` | Mistral |
| DeepSeek | `:deepseek` | `DEEPSEEK_*` | deepseek-chat / reasoner，Function Call；reasoning_content → `:reasoning`；**前缀续写**（`call-prefix-completion{,-stream}`，beta）；SSE 末块 usage（含 cache hit/miss） |
| MiniMax | `:minimax` | `MINIMAX_*` | MiniMax-M 系列，**Anthropic 兼容端点**（`/anthropic/v1/messages`，Bearer 鉴权） |
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
| `im.ttalk.agent.provider.anthropic` | Anthropic 实现（含可配置端点，支持 Anthropic 兼容服务） |
| `im.ttalk.agent.provider.zhipu` | Zhipu 实现（双协议：OpenAI 兼容 + Anthropic 兼容） |
| `im.ttalk.agent.provider.ollama` | Ollama 实现 |
| `im.ttalk.agent.provider.gemini` | Gemini 实现 |
| `im.ttalk.agent.provider.mistral` | Mistral 实现 |
| `im.ttalk.agent.provider.deepseek` | DeepSeek 实现 |
| `im.ttalk.agent.provider.minimax` | MiniMax 实现（复用 anthropic provider 的 Anthropic 兼容端点） |
| `im.ttalk.agent.provider.mock` | Mock Provider |
| `im.ttalk.agent.provider.common.base` | Provider 基座 + defprovider 宏（辅助层） |
| `im.ttalk.agent.provider.common.openai-compat` | OpenAI 兼容协议层（辅助层） |
| `im.ttalk.agent.provider.common.cache` | Anthropic prompt caching 策略层（辅助层） |
| `im.ttalk.agent.provider.common.response-parser` | 响应归一化（辅助层） |
| `im.ttalk.agent.provider.schema.openai` | OpenAI Schema 转换 |
| `im.ttalk.agent.provider.schema.anthropic` | Anthropic Schema 转换 |
| `im.ttalk.agent.provider.stream.openai` | OpenAI 流式处理 |
| `im.ttalk.agent.provider.stream.anthropic` | Anthropic 流式处理 |
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

;; 3. 注册到 Kernel（声明式）
(kernel/build-kernel {:service service :tools [...] :filters [...]})
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
 :tool-choice "auto" :parallel-tool-calls false  ;; 并行工具调用开关（精确透传 false）
 :reasoning-effort "high" :verbosity "low"        ;; o 系列 / GPT-5 推理与冗长度
 :modalities ["text" "audio"] :audio {:voice "alloy" :format "wav"}  ;; gpt-4o-audio 多模态输出
 :response-format {:type "json_object"}
 :extra-body {:enable_thinking false}}  ;; 各家私有字段逃生通道，直接 merge 进请求体
```

### 结构化输出（OpenAI 兼容）

`:response-format` 透传，支持 JSON 模式与严格 JSON Schema：

```clojure
;; 1) JSON 对象模式
{:model "gpt-4" :response-format {:type "json_object"}}

;; 2) 严格 JSON Schema（strict 强约束字段/类型）
{:model "gpt-4"
 :response-format {:type "json_schema"
                   :json_schema {:name "Person" :strict true
                                 :schema {:type "object"
                                          :properties {:name {:type "string"}
                                                       :age {:type "integer"}}
                                          :required ["name"]}}}}
```

可用 core 的 `converter.json-schema/to-openai-response-format` 从 clj-agent schema 直接生成上面的 `response_format`。

### 缓存控制（Anthropic prompt caching）

策略驱动，自动按渲染顺序 tools→system→messages 注入 `cache_control`（≤4 断点）：

```clojure
{:model "claude-opus-4-8"
 :cache-strategy :system-and-tools
 :cache-ttl "1h"}                   ;; nil=5min，"1h"=1小时
```

可用策略（`:cache-strategy`）：

| 策略 | 断点位置 | 适用场景 |
|------|---------|---------|
| `:none`（默认） | 不缓存 | — |
| `:system` | system 末块 | 长系统提示 |
| `:tools` | 最后一个工具 | 工具定义大 |
| `:system-and-tools` | system + tools（2 断点） | 两者都大 |
| `:conversation` | 最后一条消息末块 | 缓存历史到当前问题前 |
| `:tool-results` | 最后一个 `tool_result` 块 | **多轮工具循环**跨轮复用工具结果 |
| `:system-and-conversation` | system + 对话历史（2 断点） | 系统提示 + 历史都长 |

命中情况见归一化 usage 的 `:cache-read-tokens` / `:cache-write-tokens`
（OpenAI 兼容协议缓存自动生效，无需 `cache-strategy`，命中同样落在 `:cache-read-tokens`）。

### Anthropic 服务端工具与限流

```clojure
(require '[im.ttalk.agent.provider.schema.anthropic :as schema])

;; web_search 服务端工具（已是 wire 格式，直接放入 tools）
(anthropic/call-anthropic
  {:model "claude-opus-4-8" :max-tokens 4096
   :service-tier "auto"}                     ;; 容量路由："auto" | "standard_only"
  messages
  [(schema/web-search-tool {:max-uses 5
                            :allowed-domains ["docs.anthropic.com"]})])
```

同步调用成功时，响应体附带 `:rate-limit`（源自 `anthropic-ratelimit-*` 头）：

```clojure
{:requests-limit 1000 :requests-remaining 999 :requests-reset "2026-..."
 :tokens-limit 80000 :tokens-remaining 48000 :tokens-reset "2026-..."
 :retry-after 30}
```

### Anthropic Citations（引用）

把可引用文档作为 `document` 内容块放入消息，模型回答时会摘引，响应 `text` 块带 `citations`：

```clojure
(require '[im.ttalk.agent.provider.schema.anthropic :as schema]
         '[im.ttalk.agent.provider.anthropic :as anthropic])

(let [resp (anthropic/call-anthropic
             {:model "claude-opus-4-8" :max-tokens 1024}
             [{:role "user"
               :content [(schema/text-document "地球绕太阳公转。" {:title "天文常识"})
                         {:type "text" :text "地球绕什么转？"}]}]
             [])]
  (anthropic/extract-citations resp))
;; => [{:type "char_location" :cited_text "..." :document_index 0
;;      :document_title "天文常识" :start_char_index .. :end_char_index ..}]
```

### Anthropic Skills（beta）

> ⚠️ Skills / code_execution 为 Anthropic **beta** 功能，需开 `anthropic-beta` 头；
> 下列 beta 标识与工具类型字符串可能随官方调整，必要时用构造器 opts 覆盖。

```clojure
(anthropic/call-anthropic
  {:model "claude-opus-4-8" :max-tokens 4096
   :beta schema/default-skills-beta                     ;; 启用相应 anthropic-beta 头
   :container (schema/skills-container                  ;; => 请求体 container.skills
                [(schema/skill "xlsx") (schema/skill "pdf")])}
  messages
  [(schema/code-execution-tool)])                       ;; Skills 通常需配代码执行工具
```

### 重试机制（opt-in）

在 config 设 `:retry` 即启用：指数退避 + 满抖动、可重试/不可重试错误二分类、尊重 `Retry-After`：

```clojure
{:model "..." :retry {:max-retries 3 :base-delay 1000 :multiplier 2.0 :max-delay 30000}}
{:model "..." :retry true}   ;; 用默认配置
;; 不设 :retry 则零开销、不改变默认行为
```

可重试：408/409/425/429/5xx/529 及网络层错误；不可重试：其余 4xx。
失败抛 `ex-info`，data 含 `:status :request-id :retryable? :headers`。

### Anthropic 兼容端点（复用 anthropic provider）

`anthropic` provider 的端点可配置（base-url / 路径 / 鉴权方式 / 版本头），
任何「Anthropic Messages API 兼容」的服务都能直接复用其请求/响应/流式/工具调用/
缓存/重试机制。MiniMax 即按此实现：

```clojure
(anthropic/create-provider
  {:provider-name :minimax
   :base-url "https://api.minimaxi.com"
   :api-path "/anthropic/v1/messages"
   :auth-scheme :bearer        ;; :x-api-key（官方）| :bearer（MiniMax 等）
   :anthropic-version nil       ;; nil 则不发送该头
   :api-key "..."})
```

MiniMax 用 `MiniMax-M2.7` 等推理模型，Anthropic 格式下推理内容独立成块，
`extract-text` 直接得到干净答案；流式也能拿到 usage（含 cache token）。

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
