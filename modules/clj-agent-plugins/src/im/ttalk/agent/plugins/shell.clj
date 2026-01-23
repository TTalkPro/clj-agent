(ns im.ttalk.agent.plugins.shell
  "Shell 命令工具集"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.plugin :refer [defplugin]]
            [im.ttalk.agent.plugins.helpers :as helpers]))

(deftool execute-command
  "执行 shell 命令（不进行安全检查）"
  [[command :string "要执行的命令"]
   [timeout :int "超时时间（毫秒），默认 30000" :default 30000]]
  {:sensitive true}
  (let [result (helpers/execute-shell command {:timeout (or timeout 30000)})]
    (let [{:keys [stdout stderr exit-code]} result]
      (str (when (seq stdout) stdout)
           (when (seq stderr) (str "\nSTDERR: " stderr))
           "\n[退出码: " exit-code "]"))))

(deftool execute-command-safe
  "安全执行 shell 命令（带危险命令检查）"
  [[command :string "要执行的命令"]
   [timeout :int "超时时间（毫秒），默认 30000" :default 30000]]
  (let [result (helpers/execute-shell-safe command {:timeout (or timeout 30000)})]
    (if (:success result)
      (let [{:keys [stdout stderr exit-code]} (:result result)]
        (str (when (seq stdout) stdout)
             (when (seq stderr) (str "\nSTDERR: " stderr))
             "\n[退出码: " exit-code "]"))
      (str "安全检查未通过: " (:error result)))))

(defplugin shell-tools "Shell 命令工具集" execute-command execute-command-safe)
