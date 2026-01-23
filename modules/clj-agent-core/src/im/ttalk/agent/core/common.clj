(ns im.ttalk.agent.core.common
  "公共宏工具

   提供跨模块复用的基础设施：
   - 默认实例管理宏 (defdefault)

   使用示例：

   (defdefault embedding-model
     :constructor make-openai-embeddings
     :fallback make-local-embeddings)

   ;; 这会生成:
   ;; - default-embedding-model (atom)
   ;; - get-default-embedding-model
   ;; - set-default-embedding-model!
   ;; - reset-default-embedding-model!"
  (:require [taoensso.timbre :as log]))

(defmacro defdefault
  "定义默认实例管理函数组

   参数：
     name        - 实例名称（符号）
     opts        - 选项 map：
       :constructor - 默认构造函数
       :fallback    - 构造失败时的备选函数（可选）
       :doc         - 文档字符串（可选）

   生成：
     - default-<name>       : 私有 atom
     - get-default-<name>   : 获取默认实例（懒初始化）
     - set-default-<name>!  : 设置默认实例
     - reset-default-<name>!: 重置为 nil

   示例：
     (defdefault embedding-model
       :constructor make-openai-embeddings
       :fallback make-local-embeddings)

     ;; 使用
     (get-default-embedding-model)
     (set-default-embedding-model! my-model)
     (reset-default-embedding-model!)"
  [name & {:keys [constructor fallback doc]
           :or {doc "默认实例"}}]
  (let [atom-name (symbol (str "default-" name))
        get-fn (symbol (str "get-default-" name))
        set-fn (symbol (str "set-default-" name "!"))
        reset-fn (symbol (str "reset-default-" name "!"))]
    `(do
       (def ^:private ~atom-name
         ~(str doc " (atom)")
         (atom nil))

       (defn ~get-fn
         ~(str "获取" doc "\n\n"
               "如果未设置，会自动创建实例")
         []
         (when-not (deref ~atom-name)
           ~(if fallback
              `(try
                 (reset! ~atom-name (~constructor))
                 (catch Exception e#
                   (log/warn "Failed to create default instance, using fallback:" (.getMessage e#))
                   (reset! ~atom-name (~fallback))))
              `(reset! ~atom-name (~constructor))))
         (deref ~atom-name))

       (defn ~set-fn
         ~(str "设置" doc "\n\n"
               "参数：\n  instance - 实例对象")
         [instance#]
         (reset! ~atom-name instance#))

       (defn ~reset-fn
         ~(str "重置" doc "为 nil")
         []
         (reset! ~atom-name nil)))))
