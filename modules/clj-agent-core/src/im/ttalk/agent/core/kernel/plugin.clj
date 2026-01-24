(ns im.ttalk.agent.core.kernel.plugin
  "KernelPlugin - Semantic Kernel 风格的 Plugin

   KernelPlugin 是一组相关 deftool 函数的命名集合。
   直接操作 tool var 的元数据，无需协议中间层。

   使用示例：

   (require '[im.ttalk.agent.core.kernel.tool :refer [deftool]])
   (require '[im.ttalk.agent.core.kernel.plugin :as kp])

   ;; 定义函数
   (deftool get-weather ...)
   (deftool get-forecast ...)

   ;; 创建 Plugin
   (def weather-plugin
     (kp/create-plugin :weather \"天气查询工具集\"
                       [#'get-weather #'get-forecast]))

   ;; 或使用 defplugin 宏
   (kp/defplugin weather-plugin
     \"天气查询工具集\"
     get-weather
     get-forecast)"
  (:require [im.ttalk.agent.core.kernel.tool :as tool]))

;;; ============================================================
;;; KernelPlugin Record
;;; ============================================================

(defrecord KernelPlugin [plugin-name description functions])

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-plugin
  "创建 KernelPlugin

   将一组 deftool 定义的 var 组织为 Plugin。

   参数:
   - plugin-name: Plugin 名称（关键字）
   - description: Plugin 描述
   - fn-vars:     tool function var 引用列表

   返回:
   KernelPlugin 实例

   示例:
   (create-plugin :weather \"天气工具\"
                  [#'get-weather #'get-forecast])"
  [plugin-name description fn-vars]
  (let [fn-map (reduce (fn [acc v]
                         (when-not (tool/tool-function? v)
                           (throw (ex-info "var 不是 tool function（缺少 :tool/function 元数据）"
                                           {:var v :meta (meta v)})))
                         (let [schema (tool/get-schema v)
                               fn-name (keyword (:name schema))]
                           (assoc acc fn-name v)))
                       {}
                       fn-vars)]
    (->KernelPlugin plugin-name description fn-map)))

;;; ============================================================
;;; defplugin 宏
;;; ============================================================

(defmacro defplugin
  "声明式定义 Plugin

   用法:
   (defplugin plugin-name
     \"插件描述\"
     fn1
     fn2
     ...)

   展开为:
   (def plugin-name
     (create-plugin :plugin-name \"描述\" [#'fn1 #'fn2 ...]))

   示例:
   (defplugin weather-plugin
     \"天气相关工具集\"
     get-weather
     get-forecast)"
  [plugin-sym description & fn-names]
  `(def ~plugin-sym
     (create-plugin ~(keyword plugin-sym)
                    ~description
                    [~@(map (fn [n] `(var ~n)) fn-names)])))

;;; ============================================================
;;; Plugin 查询
;;; ============================================================

(defn get-schemas
  "获取 Plugin 中所有函数的 tool schema（Anthropic 格式）

   参数:
   - plugin: KernelPlugin 实例

   返回:
   Anthropic 格式 schema 列表

   示例:
   (get-schemas weather-plugin)
   ;; => [{:name \"get-weather\" :description \"...\" :input_schema {...}} ...]"
  [plugin]
  (mapv (fn [v] (tool/get-schema v))
        (vals (:functions plugin))))

(defn list-function-names
  "获取 Plugin 中所有函数名称

   参数:
   - plugin: KernelPlugin 实例

   返回:
   关键字列表"
  [plugin]
  (keys (:functions plugin)))

(defn function-count
  "获取 Plugin 中函数数量"
  [plugin]
  (count (:functions plugin)))

(defn has-sensitive?
  "检查 Plugin 中是否包含敏感函数

   参数:
   - plugin: KernelPlugin 实例

   返回: boolean"
  [plugin]
  (boolean (some tool/sensitive? (vals (:functions plugin)))))

(defn get-sensitive-functions
  "获取 Plugin 中所有敏感函数的名称

   参数:
   - plugin: KernelPlugin 实例

   返回:
   敏感函数名称（keyword）列表"
  [plugin]
  (->> (:functions plugin)
       (filter (fn [[_k v]] (tool/sensitive? v)))
       (mapv first)))

;;; ============================================================
;;; Plugin 工具执行
;;; ============================================================

(defn get-tool-var
  "获取 Plugin 中指定名称的 tool var

   参数:
   - plugin:    KernelPlugin 实例
   - tool-name: 工具名称（keyword 或 string）

   返回:
   var 引用或 nil"
  [plugin tool-name]
  (get (:functions plugin) (keyword tool-name)))

(defn execute-tool
  "通过 Plugin 执行工具

   先验证参数，再执行函数。

   参数:
   - plugin:    KernelPlugin 实例
   - tool-name: 工具名称（keyword 或 string）
   - args:      参数 map
   - context:   (可选) Context 对象

   返回:
   {:success bool :result any :error string :context ctx}"
  ([plugin tool-name args]
   (execute-tool plugin tool-name args nil))
  ([plugin tool-name args context]
   (let [fn-key (keyword tool-name)]
     (if-let [v (get-tool-var plugin fn-key)]
       (let [{:keys [valid errors]} (tool/validate-args v args)]
         (if valid
           (tool/invoke v args context)
           {:success false :error (str "参数验证失败: " (first errors))}))
       {:success false :error (str "函数未找到: " tool-name)}))))

