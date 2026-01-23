(ns im.ttalk.agent.plugins.all
  "便捷聚合入口：所有内置插件"
  (:require [im.ttalk.agent.plugins.utility :as utility]
            [im.ttalk.agent.plugins.file :as file]
            [im.ttalk.agent.plugins.http :as http]
            [im.ttalk.agent.plugins.shell :as shell]
            [im.ttalk.agent.core.kernel.core :as kernel]))

(def all-plugins
  "所有内置插件列表"
  [utility/utility-tools file/file-tools http/http-tools shell/shell-tools])

(defn add-all-plugins
  "将所有内置插件添加到 Kernel Builder"
  [builder]
  (reduce kernel/add-plugin builder all-plugins))
