(ns im.ttalk.agent.tools.api
  "Tools API - clj-agent 工具系统统一入口

   提供：
   - 工具定义与管理
   - 工具执行（同步、异步、并行）
   - 安全控制
   - JSON Schema 转换
   - 内置工具
   - Provider 系统（本地、MCP、组合）
   - ToolRegistry（统一管理工具和 Provider）

   ========================================
   快速开始
   ========================================

   (require '[im.ttalk.agent.tools.api :as tools])

   ;; 方式 1: 使用 ToolRegistry（推荐）
   (def registry
     (-> (tools/create-tool-registry)
         (tools/register-tool! :calc \"计算\" {...} calc-fn)))
   (tools/execute-tool registry :calc {:expression \"2+2\"})

   ;; 方式 2: 使用 Provider
   (def my-tools
     (-> (tools/create-local-provider)
         (tools/register-tool-to-provider! :calc \"计算\" {...} calc-fn)))

   ;; 方式 3: 组合多个 Provider
   (def all-tools
     (tools/create-composite-provider
       [local-provider mcp-provider]))

   ;; 方式 4: 内置工具
   (def registry-with-builtin
     (tools/register-builtin-tools (tools/create-tool-registry)))

   ========================================
   架构层次
   ========================================

   Layer 0 - Protocol (协议定义)
     ITool, IToolProvider, IToolRegistry

   Layer 1 - Impl (工具实现)
     SimpleTool, make-tool

   Layer 2 - Provider (提供者)
     LocalToolProvider, CompositeToolProvider

   Layer 3 - Registry (实例化注册中心)
     ToolRegistry - 管理工具和 Provider

   Layer 4 - Executor (执行策略)
     execute-with-retry, execute-with-timeout

   Layer 5 - Security (安全控制)
     set-file-whitelist!, set-shell-whitelist!

   Layer 6 - Builtin (内置工具)
     builtin-tools, register-builtin-tools"

  (:require
    ;; Protocol
    [im.ttalk.agent.tools.protocol :as proto]
    ;; Implementations
    [im.ttalk.agent.tools.impl.simple :as simple]
    ;; Providers
    [im.ttalk.agent.tools.provider.local :as local]
    [im.ttalk.agent.tools.provider.composite :as composite]
    ;; ToolRegistry (instance-based)
    [im.ttalk.agent.tools.tool-registry :as tool-registry]
    ;; Builtin
    [im.ttalk.agent.tools.builtin :as builtin]
    ;; Security
    [im.ttalk.agent.tools.security :as security]
    ;; Executor
    [im.ttalk.agent.tools.executor :as executor]
    ;; Resilience
    [im.ttalk.agent.tools.resilience :as resilience]
    ;; Chain
    [im.ttalk.agent.tools.chain :as chain]))

;;; ============================================================
;;; ToolRegistry 导出（主要 API）
;;; ============================================================

;; 工厂函数
(def create-tool-registry
  "创建 ToolRegistry 实例

   参数:
   - opts: 选项 map（可选）
     - :conflict-strategy  冲突策略 :local-first | :provider-first | :error

   返回: ToolRegistry 实例

   示例:
   (def registry (create-tool-registry))
   (def registry (create-tool-registry {:conflict-strategy :local-first}))"
  tool-registry/create-tool-registry)

(def from-tools
  "从工具列表创建 Registry

   参数:
   - tools: 工具定义列表

   返回: ToolRegistry 实例"
  tool-registry/from-tools)

(def from-providers
  "从 Provider 列表创建 Registry

   参数:
   - providers: IToolProvider 列表

   返回: ToolRegistry 实例"
  tool-registry/from-providers)

(def from-tools-and-providers
  "从工具和 Provider 创建 Registry

   参数:
   - tools:     工具定义列表
   - providers: IToolProvider 列表

   返回: ToolRegistry 实例"
  tool-registry/from-tools-and-providers)

;; 注册操作
(def register-tool!
  "注册工具到 Registry

   支持多种调用方式:
   - (register-tool! registry tool)
   - (register-tool! registry name description parameters handler)

   参数:
   - registry:    ToolRegistry 实例
   - tool:        ITool 实例或工具定义 map
   - name:        工具名称（keyword）
   - description: 工具描述
   - parameters:  JSON Schema 格式参数定义
   - handler:     处理函数

   返回: registry

   示例:
   (register-tool! registry :calc \"计算\" {...} calc-fn)"
  tool-registry/register-tool!)

(def register-tools!
  "批量注册工具

   参数:
   - registry: ToolRegistry 实例
   - tools:    工具列表

   返回: registry"
  tool-registry/register-tools!)

(def register-provider!
  "注册 Provider 到 Registry

   参数:
   - registry: ToolRegistry 实例
   - provider: IToolProvider 实例

   返回: registry"
  tool-registry/register-provider!)

(def register-providers!
  "批量注册 Provider

   参数:
   - registry:  ToolRegistry 实例
   - providers: IToolProvider 列表

   返回: registry"
  tool-registry/register-providers!)

;; 注销操作
(def unregister-tool!
  "注销工具

   参数:
   - registry:  ToolRegistry 实例
   - tool-name: 工具名称

   返回: registry"
  tool-registry/unregister-tool!)

(def unregister-provider!
  "注销 Provider

   参数:
   - registry:      ToolRegistry 实例
   - provider-name: Provider 名称

   返回: registry"
  tool-registry/unregister-provider!)

;; 查询操作
(def list-tools
  "列出所有工具

   参数:
   - registry: ToolRegistry 实例
   - opts:     选项 map（可选）

   返回: 工具列表"
  tool-registry/list-tools)

(def list-providers
  "列出所有 Provider

   参数:
   - registry: ToolRegistry 实例

   返回: Provider 列表"
  tool-registry/list-providers)

(def get-tool
  "获取工具

   参数:
   - registry:  ToolRegistry 实例
   - tool-name: 工具名称

   返回: ITool 或 nil"
  tool-registry/get-tool)

(def get-provider
  "获取 Provider

   参数:
   - registry:      ToolRegistry 实例
   - provider-name: Provider 名称

   返回: IToolProvider 或 nil"
  tool-registry/get-provider)

(def tool-count
  "获取工具数量

   参数:
   - registry: ToolRegistry 实例

   返回: 整数"
  tool-registry/tool-count)

(def provider-count
  "获取 Provider 数量

   参数:
   - registry: ToolRegistry 实例

   返回: 整数"
  tool-registry/provider-count)

(def local-tool-count
  "获取本地工具数量

   参数:
   - registry: ToolRegistry 实例

   返回: 整数"
  tool-registry/local-tool-count)

;; 执行
(def execute-tool
  "执行工具

   参数:
   - registry:  ToolRegistry 实例
   - tool-name: 工具名称
   - args:      参数 map

   返回: {:success bool :result any :error string}

   示例:
   (execute-tool registry :calc {:expression \"2+2\"})"
  tool-registry/execute-tool)

;; 统计
(def registry-stats
  "获取 Registry 统计信息

   参数:
   - registry: ToolRegistry 实例

   返回: 统计 map"
  tool-registry/registry-stats)

;; 类型检查
(def registry?
  "检查是否为 Registry

   参数:
   - x: 任意值

   返回: boolean"
  tool-registry/registry?)

;;; ============================================================
;;; Builtin 导出
;;; ============================================================

(def builtin-tools
  "所有内置工具定义列表"
  builtin/builtin-tools)

(def builtin-tool-names
  "内置工具名称列表"
  builtin/builtin-tool-names)

(defn register-builtin-tools
  "将内置工具注册到 Registry

   参数:
   - registry: ToolRegistry 实例

   返回: registry

   示例:
   (def registry
     (-> (create-tool-registry)
         (register-builtin-tools)))"
  [registry]
  (builtin/register-builtin-tools registry))

(def list-builtin-tools
  "列出所有内置工具定义"
  builtin/list-builtin-tools)

(def get-builtin-tool
  "获取内置工具定义

   参数:
   - tool-name: 工具名称

   返回: 工具定义 map 或 nil"
  builtin/get-builtin-tool)

(def list-builtin-tools-by-category
  "按分类列出内置工具

   参数:
   - category: 分类关键字

   返回: 工具定义列表"
  builtin/list-builtin-tools-by-category)

(def describe-builtin-tools
  "描述所有内置工具

   返回: 描述字符串"
  builtin/describe-builtin-tools)

;;; ============================================================
;;; Security 导出
;;; ============================================================

;; 文件安全
(def set-file-whitelist! security/set-file-whitelist!)
(def allow-file-path! security/allow-file-path!)
(def set-file-max-size! security/set-file-max-size!)
(def enable-file! security/enable-file!)
(def disable-file! security/disable-file!)
(def file-enabled? security/file-enabled?)
(def check-file-path security/check-file-path)
(def check-file-size security/check-file-size)

;; Shell 安全
(def set-shell-whitelist! security/set-shell-whitelist!)
(def allow-shell-command! security/allow-shell-command!)
(def set-shell-timeout! security/set-shell-timeout!)
(def enable-shell! security/enable-shell!)
(def disable-shell! security/disable-shell!)
(def shell-enabled? security/shell-enabled?)
(def dangerous-command? security/dangerous-command?)
(def check-shell-command security/check-shell-command)
(def get-shell-timeout security/get-shell-timeout)

;; HTTP 安全
(def set-http-whitelist! security/set-http-whitelist!)
(def allow-http-domain! security/allow-http-domain!)
(def set-http-timeout! security/set-http-timeout!)
(def enable-http! security/enable-http!)
(def disable-http! security/disable-http!)
(def http-enabled? security/http-enabled?)
(def check-http-url security/check-http-url)
(def get-http-timeout security/get-http-timeout)

;; 安全配置
(def get-security-config security/get-config)
(def reset-security-config! security/reset-config!)
(def set-security-verbose! security/set-verbose!)

;; 安全模式
(def enable-strict-mode! security/enable-strict-mode!)
(def enable-sandbox-mode! security/enable-sandbox-mode!)
(def enable-development-mode! security/enable-development-mode!)

;;; ============================================================
;;; Executor 导出
;;; ============================================================

(def execute-with-timeout executor/execute-with-timeout)
(def execute-with-retry executor/execute-with-retry)
(def execute-with-fallback executor/execute-with-fallback)
(def execute-parallel executor/execute-parallel)
(def execute-parallel-with-timeout executor/execute-parallel-with-timeout)
(def execute-batch executor/execute-batch)
(def execute-streaming executor/execute-streaming)
(def execute-with-strategy executor/execute-with-strategy)

;;; ============================================================
;;; Resilience 导出
;;; ============================================================

(def retry resilience/retry)
(def fallback resilience/fallback)
(def fallback-value resilience/fallback-value)
(def with-timeout-fn resilience/with-timeout-fn)
(def execute-with-circuit-breaker resilience/execute-with-circuit-breaker)
(def execute-with-rate-limit resilience/execute-with-rate-limit)
(def with-resilience-fn resilience/with-resilience-fn)

;; Factory functions
(def create-circuit-breaker resilience/create-circuit-breaker)
(def create-rate-limiter resilience/create-rate-limiter)

;;; ============================================================
;;; Chain 导出
;;; ============================================================

(def defchain chain/defchain)
(def make-chain chain/chain)
(def execute-chain chain/execute)
(def make-step chain/make-step)
(def run-chain chain/run-chain)
(def concat-chains chain/concat-chains)
(def append-step chain/append-step)
(def prepend-step chain/prepend-step)

;;; ============================================================
;;; Protocol 导出
;;; ============================================================

;; 类型检查
(def tool? proto/tool?)
(def provider? proto/provider?)

;; Protocol 函数（用于多态调用）
(def tool-name proto/tool-name)
(def tool-description proto/tool-description)
(def tool-parameters proto/tool-parameters)
(def tool-category proto/tool-category)
(def tool-metadata proto/tool-metadata)
(def tool-execute proto/tool-execute)
(def tool-validate proto/tool-validate)
(def tool-to-schema proto/tool-to-schema)

(def provider-name proto/provider-name)
(def provider-enabled? proto/provider-enabled?)
(def initialize-provider proto/initialize-provider)
(def shutdown-provider proto/shutdown-provider)
(def supports-tool? proto/supports-tool?)

;;; ============================================================
;;; SimpleTool 导出
;;; ============================================================

(def make-tool simple/make-tool)
(def make-simple-tool simple/make-simple-tool)
(def make-string-tool simple/make-string-tool)
(def tool-from-map simple/from-map)

;;; ============================================================
;;; LocalToolProvider 导出
;;; ============================================================

(def create-local-provider local/create-local-provider)
(def from-tool-list local/from-tool-list)

;;; ============================================================
;;; CompositeToolProvider 导出
;;; ============================================================

(def create-composite-provider composite/create-composite-provider)
(def add-provider! composite/add-provider!)
(def remove-provider! composite/remove-provider!)

;;; ============================================================
;;; Provider 相关便捷函数
;;; ============================================================

(defn register-tool-to-provider!
  "注册工具到 Provider

   支持多种格式：
   - ITool 实例
   - 工具定义 map
   - 参数列表

   示例:
   (register-tool-to-provider! provider my-tool)
   (register-tool-to-provider! provider {:name :calc :handler fn ...})
   (register-tool-to-provider! provider :calc \"计算\" {...} calc-fn)"
  ([provider tool]
   (local/register-tool! provider tool))
  ([provider name description parameters handler]
   (local/register-tool! provider name description parameters handler)))

(defn list-provider-tools
  "列出 Provider 中的所有工具

   参数:
   - provider: IToolProvider 实例
   - opts:     选项 {:category :keyword}（可选）

   返回: ITool 列表"
  ([provider]
   (proto/list-tools provider))
  ([provider opts]
   (proto/list-tools provider opts)))

(defn execute-provider-tool
  "通过 Provider 执行工具

   参数:
   - provider:  IToolProvider 实例
   - tool-name: 工具名称
   - args:      参数 map

   返回: {:success bool :result any :error string}"
  [provider tool-name args]
  (proto/execute-tool provider tool-name args))

;;; ============================================================
;;; 工具规范化
;;; ============================================================

(defn normalize-tools
  "规范化工具参数，支持多种输入格式

   输入格式:
   1. IToolProvider 实例 -> 直接返回
   2. IToolRegistry 实例 -> 直接返回
   3. 工具列表 [{:name :handler ...}] -> 包装为 LocalToolProvider
   4. nil -> 空的 LocalToolProvider

   返回: IToolProvider 或 IToolRegistry 实例

   示例:
   (normalize-tools my-provider)
   (normalize-tools [{:name :calc :description \"...\" :handler fn ...}])"
  [tools]
  (cond
    ;; 已经是 Provider
    (provider? tools)
    tools

    ;; 已经是 Registry
    (registry? tools)
    tools

    ;; 工具定义列表
    (and (sequential? tools)
         (seq tools)
         (every? map? tools))
    (local/from-tool-list tools)

    ;; 空或 nil -> 空 Provider
    (or (nil? tools) (empty? tools))
    (local/create-local-provider)

    :else
    (throw (ex-info "Invalid tools format. Expected IToolProvider, IToolRegistry, tool list, or nil."
                    {:tools tools
                     :type (type tools)}))))

;;; ============================================================
;;; Provider Schema 导出
;;; ============================================================

(defn provider-tools-to-schemas
  "将 Provider 中的工具转换为 LLM Schema

   参数:
   - provider: IToolProvider 实例
   - format:   :anthropic | :openai | :generic（可选）

   返回: Schema 列表"
  ([provider]
   (provider-tools-to-schemas provider :generic))
  ([provider format]
   (mapv #(proto/tool-to-schema % format)
         (proto/list-tools provider))))

(defn registry-tools-to-schemas
  "将 Registry 中的工具转换为 LLM Schema

   参数:
   - registry: ToolRegistry 实例
   - format:   :anthropic | :openai | :generic（可选）

   返回: Schema 列表"
  ([registry]
   (registry-tools-to-schemas registry :generic))
  ([registry format]
   (mapv #(proto/tool-to-schema % format)
         (tool-registry/list-tools registry))))
