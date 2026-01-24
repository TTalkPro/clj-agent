(ns im.ttalk.agent.simpleagent.core
  "SimpleAgent 便捷入口

   根据 :mode 分发创建不同类型的 Agent：
   - :kernel  简单同步模式（默认）
   - :process 支持 pause/resume 的模式

   使用示例：

   ;; Kernel 模式（默认）
   (def agent (create-agent {:provider my-provider :model \"glm-4.7\"}))

   ;; Process 模式
   (def agent (create-agent {:mode :process :provider my-provider :tools [plugin]}))"
  (:require [im.ttalk.agent.simpleagent.kernel-agent :as kernel-agent]
            [im.ttalk.agent.simpleagent.process-agent :as process-agent]))

(defn create-agent
  "创建 Agent

   参数:
   - opts: 配置 map
     {:mode :kernel|:process  模式选择（默认 :kernel）
      ... 其他选项传递给对应的 create 函数}

   返回:
   Agent map"
  [opts]
  (case (:mode opts :kernel)
    :kernel  (kernel-agent/create-agent (dissoc opts :mode))
    :process (process-agent/create-process-agent (dissoc opts :mode))))
