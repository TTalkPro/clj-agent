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

(defn all-tools-into
  "将所有内置工具合入 Kernel 构建配置

    用法:
    (kernel/build-kernel
      (-> {:service my-service}
          (all-tools-into)))"
  [opts]
  (update opts :tools (fn [ts] (into (or ts []) all-tools))))
