(ns im.ttalk.agent.tools.protocol
  "工具系统协议定义

   定义工具系统的核心协议：
   - ITool: 工具协议，定义工具的标准行为（用于动态工具、MCP 工具等）
   - IToolProvider: 工具提供者协议，管理工具集合
   - IToolRegistry: 高级多 Provider 管理

   注意：对于通过 deftool 宏定义的工具，core 模块直接操作 var 元数据，
   不需要这些协议。这些协议用于更高级的场景：
   - 动态创建的工具（SimpleTool）
   - 外部工具集成（MCP Provider）
   - 多 Provider 聚合（CompositeToolProvider）")

;;; ============================================================
;;; ITool - 工具协议
;;; ============================================================

(defprotocol ITool
  "工具协议 - 定义工具的标准行为

   每个工具实现必须提供：
   - 基本信息（名称、描述、参数规范）
   - 执行逻辑
   - 参数验证
   - Schema 转换（支持不同 LLM 格式）"

  ;; -------------------- 基本信息 --------------------

  (tool-name [this]
    "返回工具名称
     返回: keyword")

  (tool-description [this]
    "返回工具描述（LLM 可见）
     返回: string")

  (tool-parameters [this]
    "返回参数规范（JSON Schema 格式）
     返回: map {:type \"object\" :properties {...} :required [...]}")

  (tool-category [this]
    "返回工具分类
     返回: keyword (:general | :file | :http | :search | :shell | :mcp | :custom)")

  (tool-metadata [this]
    "返回扩展元数据
     返回: map")

  ;; -------------------- 执行 --------------------

  (tool-execute [this args]
    "执行工具

     参数:
     - args: 参数 map（已解析的关键字参数）

     返回: {:success bool :result any :error string}")

  (tool-validate [this args]
    "验证工具参数

     参数:
     - args: 参数 map

     返回: {:valid bool :errors [string]}")

  ;; -------------------- Schema 转换 --------------------

  (tool-to-schema
    [this]
    [this format]
    "转换为 LLM 工具 Schema

     参数:
     - format: :anthropic | :openai | :generic（默认 :generic）

     返回: 对应格式的 schema map

     Anthropic 格式:
     {:name \"...\" :description \"...\" :input_schema {...}}

     OpenAI 格式:
     {:type \"function\" :function {:name \"...\" :parameters {...}}}"))

;;; ============================================================
;;; IToolProvider - 工具提供者协议
;;; ============================================================

(defprotocol IToolProvider
  "工具提供者协议 - 管理和提供工具集合

   Provider 是工具的来源，可以是：
   - LocalToolProvider: 本地注册的工具
   - MCPToolProvider: MCP 服务器提供的工具
   - CompositeToolProvider: 组合多个 Provider"

  ;; -------------------- 基本信息 --------------------

  (provider-name [this]
    "返回提供者名称
     返回: keyword")

  (provider-enabled? [this]
    "检查提供者是否启用
     返回: boolean")

  ;; -------------------- 生命周期 --------------------

  (initialize-provider [this]
    "初始化提供者

     执行：
     - 注册内置工具
     - 建立连接（MCP 等）
     - 加载配置

     返回: this")

  (shutdown-provider [this]
    "关闭提供者，清理资源

     执行：
     - 断开连接
     - 清理缓存
     - 释放资源

     返回: this")

  (enable-provider [this]
    "启用提供者
     返回: this")

  (disable-provider [this]
    "禁用提供者
     返回: this")

  ;; -------------------- 工具管理 --------------------

  (list-tools
    [this]
    [this opts]
    "列出所有可用工具

     参数:
     - opts: {:category :keyword} 可选过滤条件

     返回: ITool 实例列表")

  (get-tool [this tool-name]
    "获取指定工具

     参数:
     - tool-name: 工具名称（keyword 或 string）

     返回: ITool 实例或 nil")

  (supports-tool? [this tool-name]
    "检查是否支持指定工具

     参数:
     - tool-name: 工具名称

     返回: boolean")

  (register-tool [this tool]
    "注册工具到提供者

     参数:
     - tool: ITool 实例

     返回: this")

  (unregister-tool [this tool-name]
    "从提供者注销工具

     参数:
     - tool-name: 工具名称

     返回: this")

  ;; -------------------- 执行 --------------------

  (execute-tool [this tool-name args]
    "执行工具

     参数:
     - tool-name: 工具名称
     - args: 参数 map

     返回: {:success bool :result any :error string}

     注意：此方法会委托给对应 ITool 的 tool-execute"))

;;; ============================================================
;;; IToolRegistry - 工具注册表协议（多 Provider 管理）
;;; ============================================================

(defprotocol IToolRegistry
  "工具注册表协议 - 聚合和管理多个 Tool Provider

   Registry 提供：
   - 统一的工具查找接口
   - 多 Provider 聚合
   - 冲突解决策略
   - 缓存管理"

  (add-provider [this provider]
    "添加工具提供者

     参数:
     - provider: IToolProvider 实例

     返回: this")

  (remove-provider [this provider-name]
    "移除工具提供者

     参数:
     - provider-name: 提供者名称

     返回: this")

  (get-provider [this provider-name]
    "获取指定提供者

     参数:
     - provider-name: 提供者名称

     返回: IToolProvider 实例或 nil")

  (list-providers [this]
    "列出所有提供者

     返回: IToolProvider 列表")

  (refresh-cache [this]
    "刷新工具缓存

     重新从所有 Provider 收集工具信息

     返回: this")

  (find-tool-with-provider [this tool-name]
    "查找工具及其提供者

     参数:
     - tool-name: 工具名称

     返回: {:tool ITool :provider IToolProvider} 或 nil"))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn tool?
  "检查是否为 ITool 实例"
  [x]
  (satisfies? ITool x))

(defn provider?
  "检查是否为 IToolProvider 实例"
  [x]
  (satisfies? IToolProvider x))

(defn registry?
  "检查是否为 IToolRegistry 实例"
  [x]
  (satisfies? IToolRegistry x))

;;; ============================================================
;;; Schema 格式常量
;;; ============================================================

(def schema-formats
  "支持的 Schema 格式"
  #{:anthropic :openai :generic})

(defn valid-schema-format?
  "检查是否为有效的 schema 格式"
  [format]
  (contains? schema-formats format))
