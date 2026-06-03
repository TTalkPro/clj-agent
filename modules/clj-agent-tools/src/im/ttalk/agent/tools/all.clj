(ns im.ttalk.agent.tools.all
  "便捷聚合入口：所有内置工具"
  (:require [im.ttalk.agent.tools.utility :as utility]
            [im.ttalk.agent.tools.file :as file]
            [im.ttalk.agent.tools.http :as http]
            [im.ttalk.agent.tools.shell :as shell]
            [im.ttalk.agent.core.kernel :as kernel]))

(def all-tools
  "所有内置工具（tool vars 列表）"
  (vec (concat utility/all-tools
               file/all-tools
               http/all-tools
               shell/all-tools)))

(defn add-all-tools
  "将所有内置工具添加到 Kernel Builder

   用法:
   (-> (kernel/create-kernel-builder)
       (add-all-tools)
       (kernel/add-service my-service)
       (kernel/build-kernel))"
  [builder]
  (kernel/add-tools builder all-tools))
