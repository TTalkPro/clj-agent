# clj-agent

Clojure AI Agent Framework - Kernel 中央编排器

## 项目概述

`clj-agent` 是一个 Clojure AI Agent 框架，参考 beamai_kernel 设计：

- **Kernel + Plugin 编排**：`deftool` 宏定义工具，Plugin 组织工具集，Kernel 统一调度
- **多级 Invoke API**：`invoke`（函数调用）、`invoke-chat`（纯 LLM）、`invoke-chat-with-tools`（工具循环）
- **Filter 中间件**：Ring-style 洋葱模型拦截工具调用
- **Service 抽象**：LLM 服务通过 `{:chat-fn :build-result-msgs}` map 接入，无耦合
- **多后端存储**：IKeyValueStore + ISnapshotStore 协议（Memory/SQLite/Redis/PostgreSQL）

## 模块结构

```
clj-agent/
├── modules/
│   ├── clj-agent-core/     # 核心（Kernel, Plugin, Filter, deftool）
│   ├── clj-agent-llm/      # LLM Provider + Service 工厂
│   ├── clj-agent-tools/    # 工具注册表（IToolRegistry）
│   ├── clj-agent-memory/   # 存储实现（InMemory, SQLite, Redis, PostgreSQL）
│   ├── clj-agent-rag/      # RAG 检索增强生成
│   └── clj-agent-mcp/      # MCP 服务器
├── examples/
├── scripts/
└── deps.edn
```

## 快速开始

### 在项目中使用

```clojure
;; deps.edn
{:deps {im/ttalk-agent {:local/root "/path/to/lib/clj-agent"}}}
```

### 定义工具

```clojure
(require '[im.ttalk.agent.core.kernel.tool :refer [deftool]])

(deftool get-weather
  "获取天气信息"
  [[city :string "城市名称"]]
  (str city ": 晴天 25°C"))

(deftool calculate
  "数学计算"
  [[expression :string "表达式"]]
  (str (eval (read-string expression))))
```

### 创建 Plugin

```clojure
(require '[im.ttalk.agent.core.kernel.plugin :as kp])

(kp/defplugin my-tools "工具集" get-weather calculate)
```

### 构建 Kernel 并对话

```clojure
(require '[im.ttalk.agent.core.kernel.core :as kernel])
(require '[im.ttalk.agent.core.kernel.filter :as filters])
(require '[im.ttalk.agent.llm.kernel.chat :as chat])

;; 创建 LLM Service
(def service (chat/create-service
               {:model "glm-4-flash-250414"
                :base-url "https://open.bigmodel.cn/api/anthropic"
                :api-key (System/getenv "ZHIPU_API_KEY")}))

;; 构建 Kernel
(def app-kernel
  (-> (kernel/create-kernel-builder)
      (kernel/add-service service)
      (kernel/add-plugin my-tools)
      (kernel/add-filter filters/logging-filter)
      (kernel/build-kernel)))

;; 对话（自动工具调用循环）
(let [messages [{:role "user" :content "北京天气怎么样？"}]
      result (kernel/invoke-chat-with-tools app-kernel messages {})]
  (println (:text result)))
```

## 核心概念

### Kernel API

Kernel 提供三类 API：

```clojure
;; Build API - 构建 Kernel
(-> (kernel/create-kernel-builder)
    (kernel/add-plugin my-plugin)
    (kernel/add-service service)
    (kernel/add-filter logging-filter)
    (kernel/build-kernel))

;; Invoke API - 调用函数/LLM
(kernel/invoke kernel :get-weather {:city "北京"})           ;; 函数调用（经过 Filter）
(kernel/invoke-chat kernel messages opts)                    ;; 纯 LLM 调用
(kernel/invoke-chat-with-tools kernel messages opts)         ;; 工具调用循环

;; Query API - 查询状态
(:tools kernel)                      ;; 所有 tool schema（直接关键字访问）
(kernel/find-function kernel :name)  ;; 查找函数
(kernel/list-functions kernel)       ;; 列出所有函数名
(:service kernel)                    ;; 获取 service（直接关键字访问）
```

### deftool 宏

同时定义 Clojure 函数和生成 tool schema：

```clojure
(deftool fn-name
  "描述"
  [[param1 :string "参数描述"]
   [param2 :int "可选参数" :default 10]]
  {:sensitive true}  ;; 可选选项
  (body ...))
```

### Service 接口

Service 是一个 map，定义 LLM 调用接口：

```clojure
{:chat-fn           (fn [messages opts] -> {:text "..." :tool-calls [...] :assistant-msg {...}})
 :build-result-msgs (fn [assistant-msg tool-results] -> [msg1 msg2 ...])}
```

`clj-agent-llm` 模块提供 `create-service` 创建 Anthropic 兼容的 service。
也可自行实现 service map 接入任意 LLM。

### Filter 链

```clojure
;; 自定义 Filter（Ring-style middleware）
(defn my-filter [ctx next-fn]
  (println "before:" (:tool-name ctx))
  (let [result (next-fn ctx)]
    (println "after:" result)
    result))

;; 内置 Filter
filters/logging-filter          ;; 日志
filters/error-handling-filter   ;; 异常捕获
(filters/timeout-filter 5000)   ;; 超时控制
filters/approval-filter         ;; 敏感工具审批
```

## 开发

```bash
# 运行所有测试
./scripts/test-all.sh

# 构建所有模块
./scripts/build-all.sh

# 安装到本地 Maven
./scripts/install-all.sh
```

## 依赖

- org.clojure/clojure 1.11.4
- cheshire/cheshire 5.12.0
- com.github.seancorfield/next.jdbc 1.3.939
- com.taoensso/timbre 6.3.0
- http-kit/http-kit 2.8.0

可选：
- com.taoensso/carmine (Redis)
- org.postgresql/postgresql (PostgreSQL)
- org.xerial/sqlite-jdbc (SQLite)

## 许可证

MIT License
