# clj-agent-llm

clj-agent LLM 模块，提供统一的多 Provider 大语言模型调用接口。

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              clj-agent-llm                                   │
│                                                                              │
│   Layer 1: Core 核心层                                                       │
│   ┌────────────────┐ ┌────────────────┐ ┌────────────────┐                  │
│   │ protocol.clj   │ │ types.clj      │ │ errors.clj     │                  │
│   │ (ILLMProvider) │ │ (ToolCall等)   │ │ (错误处理)     │                  │
│   └────────────────┘ └────────────────┘ └────────────────┘                  │
│                                    ▲                                         │
│   Layer 2: Provider 实现层 ────────┘                                         │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐         │
│   │ OpenAI   │ │ Anthropic│ │ Zhipu    │ │ Gemini   │ │ Ollama   │ ...     │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘         │
│                                    ▲                                         │
│   Layer 3: Factory 工厂层 ─────────┘                                         │
│   ┌────────────────┐ ┌────────────────┐ ┌────────────────┐                  │
│   │ registry.clj   │ │ config.clj     │ │ builder.clj    │                  │
│   │ (注册表)       │ │ (配置管理)     │ │ (Provider创建) │                  │
│   └────────────────┘ └────────────────┘ └────────────────┘                  │
│                                    ▲                                         │
│   Layer 4: API 入口层 ─────────────┘                                         │
│   ┌─────────────────────────────────────────────────────────────────┐       │
│   │                          api.clj                                 │       │
│   └─────────────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 设计原则

1. **统一接口** - 所有 Provider 实现相同的 `ILLMProvider` 协议
2. **可插拔** - 通过注册表机制支持动态添加新 Provider
3. **配置灵活** - 支持环境变量、代码配置、默认值三级配置
4. **错误统一** - 统一的错误类型和可重试判断

## 支持的 Provider

| Provider | 环境变量前缀 | 默认模型 | 功能支持 |
|----------|-------------|----------|----------|
| OpenAI | `OPENAI_*` | gpt-4 | 同步/流式/Function Call |
| Anthropic | `ANTHROPIC_*` | claude-3-5-sonnet-20241022 | 同步/流式/Function Call |
| 智谱 | `ZHIPU_*` | glm-4 | 同步/流式/Function Call |
| Gemini | `GOOGLE_*` | gemini-2.0-flash-exp | 同步/流式/Function Call |
| Mistral | `MISTRAL_*` | mistral-large-latest | 同步/流式/Function Call |
| Ollama | `OLLAMA_*` | llama2 | 同步/流式 |
| Mock | - | - | 测试用 |

## 快速开始

```clojure
(require '[im.ttalk.llm.api :as llm])

;; 创建 Provider（从环境变量读取 API Key）
(def provider (llm/create-provider :openai))

;; 同步调用
(def response
  (llm/call provider
            {:model "gpt-4" :max-tokens 1000}
            [{:role "user" :content "你好，请介绍下自己"}]))

;; 提取文本
(llm/extract-text provider response)
;; => "你好！我是一个AI助手..."

;; 流式调用
(llm/call-stream provider
                 {:model "gpt-4" :max-tokens 1000}
                 [{:role "user" :content "你好"}]
                 []
                 (fn [{:keys [token]}]
                   (print token)
                   (flush)))
```

## 环境变量配置

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

## Provider 创建

### 自动配置（推荐）

```clojure
;; 自动从环境变量加载配置
(def provider (llm/create-provider-auto :openai))

;; 覆盖部分配置
(def provider (llm/create-provider-auto :openai {:model "gpt-4-turbo"}))
```

### 手动配置

```clojure
;; 完全手动配置
(def provider (llm/create-provider :anthropic
                {:api-key "sk-ant-..."
                 :model "claude-3-5-sonnet-20241022"
                 :max-tokens 4096}))
```

### 查看支持的 Provider

```clojure
(llm/supported-providers)
;; => (:openai :anthropic :zhipu :ollama :gemini :mistral :mock)
```

## 核心 API

### 同步调用

```clojure
(llm/call provider config messages)
(llm/call provider config messages tools)

;; config 参数
{:model "gpt-4"           ; 模型名称
 :max-tokens 4096         ; 最大 token 数
 :temperature 0.7         ; 温度（0-1）
 :top-p 1.0}              ; Top-P 采样
```

### 流式调用

```clojure
(llm/call-stream provider config messages tools on-token)

;; on-token 回调参数
{:token "hello"           ; 当前 token
 :index 0                 ; token 索引
 :accumulated "hello"}    ; 累积文本
```

### 响应处理

```clojure
;; 提取文本
(llm/extract-text provider response)

;; 提取工具调用
(llm/extract-tool-calls provider response)
;; => [{:id "call_123" :name :calculator :input {:expression "2+2"}}]

;; 构建工具结果
(llm/build-tool-result provider "call_123" "4")
```

## Function Calling

```clojure
;; 定义工具
(def tools
  [{:name :calculator
    :description "计算数学表达式"
    :parameters {:type "object"
                 :properties {:expression {:type "string"
                                           :description "数学表达式"}}
                 :required ["expression"]}}])

;; 调用带工具的 LLM
(def response
  (llm/call provider
            {:model "gpt-4"}
            [{:role "user" :content "计算 2+2"}]
            tools))

;; 提取工具调用
(let [tool-calls (llm/extract-tool-calls provider response)]
  (doseq [{:keys [id name input]} tool-calls]
    (println "调用工具:" name "参数:" input)))
```

## 消息构建

```clojure
;; 使用便捷函数构建消息
(llm/user-message "你好")
;; => {:role "user" :content "你好"}

(llm/assistant-message "你好！")
;; => {:role "assistant" :content "你好！"}

(llm/system-message "你是一个助手")
;; => {:role "system" :content "你是一个助手"}

(llm/tool-message "call_123" "执行结果")
;; => {:role "tool" :tool_call_id "call_123" :content "执行结果"}
```

## 错误处理

### 错误类型

| 类型 | 可重试 | 说明 |
|------|--------|------|
| `:network-error` | 是 | 网络连接错误 |
| `:timeout-error` | 是 | 请求超时 |
| `:rate-limit-error` | 是 | 速率限制（429） |
| `:auth-error` | 否 | 认证失败（401/403） |
| `:validation-error` | 否 | 参数验证失败 |
| `:parse-error` | 否 | 响应解析失败 |
| `:provider-error` | 视情况 | Provider 特定错误 |

### 错误处理示例

```clojure
(require '[im.ttalk.llm.api :as llm])

;; 安全执行
(let [[status result] (llm/with-error-handling
                        #(llm/call provider config messages))]
  (if (= :ok status)
    (println "成功:" result)
    (println "失败:" (:message result))))

;; 检查错误
(when (llm/error? result)
  (if (llm/retryable? result)
    (println "可重试错误")
    (println "不可重试错误")))
```

## Mock Provider（测试）

```clojure
(require '[im.ttalk.llm.provider.mock :as mock])

;; 创建返回固定响应的 Mock
(def mock-provider
  (mock/create-mock-provider
    {:response {:role "assistant" :content "模拟响应"}}))

;; 创建模拟工具调用的 Mock
(def tool-mock
  (mock/create-calculator-mock))

;; 创建模拟错误的 Mock
(def error-mock
  (mock/create-error-mock :rate-limit-error))

;; 获取调用历史
(mock/get-call-history mock-provider)
```

## 文件结构

```
src/im/ttalk/llm/
├── api.clj                      # 统一 API 入口
├── core/
│   ├── protocol.clj             # ILLMProvider 协议定义
│   ├── types.clj                # ToolCall/Response 类型
│   └── errors.clj               # 错误类型和处理
├── provider/
│   ├── base.clj                 # Provider 基础抽象
│   ├── openai_compat.clj        # OpenAI 兼容基础
│   ├── openai.clj               # OpenAI Provider
│   ├── anthropic.clj            # Anthropic (Claude) Provider
│   ├── zhipu.clj                # 智谱 Provider
│   ├── gemini.clj               # Google Gemini Provider
│   ├── mistral.clj              # Mistral Provider
│   ├── ollama.clj               # Ollama Provider
│   └── mock.clj                 # Mock Provider（测试）
├── factory/
│   ├── registry.clj             # Provider 注册表
│   ├── config.clj               # 配置管理
│   └── builder.clj              # Provider 构建器
├── stream/
│   ├── openai.clj               # OpenAI 流式处理
│   └── anthropic.clj            # Anthropic 流式处理
├── schema/
│   ├── openai.clj               # OpenAI Schema 转换
│   └── anthropic.clj            # Anthropic Schema 转换
└── response/
    └── parser.clj               # 响应解析
```

## 扩展 Provider

### 注册新 Provider

```clojure
(require '[im.ttalk.llm.factory.registry :as registry])

;; 注册自定义 Provider
(registry/register-provider! :my-provider
  (fn [opts]
    (->MyProvider (:api-key opts))))

;; 使用
(def provider (llm/create-provider :my-provider {:api-key "..."}))
```

### 实现 ILLMProvider

```clojure
(require '[im.ttalk.llm.core.protocol :as proto])

(defrecord MyProvider [api-key]
  proto/ILLMProvider

  (provider-name [_] :my-provider)

  (call-llm [this config messages tools]
    ;; 实现 API 调用
    )

  (call-llm-stream [this config messages tools on-token]
    ;; 实现流式调用
    )

  (extract-text [_ response]
    ;; 从响应提取文本
    )

  (extract-tool-calls [_ response]
    ;; 从响应提取工具调用
    )

  (build-tool-result [_ tool-id content]
    ;; 构建工具结果消息
    )

  (supports-function-calling? [_] true)
  (supports-stream? [_] true)

  (tool->schema [_ tool]
    ;; 转换工具定义为 Provider 特定格式
    tool))
```

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}
        org.clojure/clojure {:mvn/version "1.11.4"}
        net.clojars.wkok/openai-clojure {:mvn/version "0.21.0"}
        clj-http/clj-http {:mvn/version "3.12.3"}
        cheshire/cheshire {:mvn/version "5.12.0"}
        com.taoensso/timbre {:mvn/version "6.3.0"}}}
```

## 版本历史

- **2.0.0** - 重构目录结构，统一 API 入口
- **1.5.0** - 添加 Gemini、Mistral Provider
- **1.0.0** - 初始版本，支持 OpenAI、Anthropic、智谱、Ollama
