(ns tool-calling-manager-live-test
  "ToolCallingManager × MiniMax 真实工具循环机制验证。

   运行：clojure -M -e load-file，文件参数为 examples/tool_calling_manager_live_test.clj。"
  (:require [clojure.string :as str]
             [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.chat-client :as chat-client]
             [im.ttalk.agent.model.response :as response]
             [im.ttalk.agent.chat-model :as chat-model]
            [im.ttalk.agent.provider.factory.builder :as factory]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.react :as react]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tool-calling-manager :as manager]))

(set! *warn-on-reflection* true)

(def failures (atom 0))

(defn check [description ok?]
  (if ok?
    (println "  PASS" description)
    (do (swap! failures inc)
        (println "  FAIL" description))))

(deftool get-weather
  "Return deterministic weather for a city. Always call this tool for weather requests."
  [[city :string "City name"]]
  (str city ": sunny, 25 C"))

(defn run []
  (println "ToolCallingManager x MiniMax live test")
  (try
    (let [provider (factory/create-provider-from-env :minimax)
          cm (chat-model/create-chat-model provider {:model minimax/default-model
                                                     :max-tokens 512
                                                     :temperature 0})
          llm-calls (atom 0)
          observed-llm-tool-calls (atom [])
          ;; 包一层 IChatModel 转调内层——chat-model 是 record，
          ;; (assoc cm :chat-fn ...) 的旧写法会被协议分派无视（装配期即抛）。
          instrumented-cm
          (reify chat-model/IChatModel
            (call [_ request]
              (let [response (chat-model/call cm request)]
                (swap! llm-calls inc)
                (swap! observed-llm-tool-calls into
                       (or (response/response-tool-calls response) []))
                response))
            (stream-call [_ request on-token]
              (chat-model/stream-call cm request on-token))
            (model-options [_] (chat-model/model-options cm)))
          manager-calls (atom [])
          delegate (react/virtual-thread-tool-calling-manager)
          instrumented-manager
          (reify manager/ToolCallingManager
            (execute-tool-calls [_ k resp opts]
              (let [tool-calls (response/response-tool-calls resp)]
                (swap! manager-calls conj tool-calls)
                (manager/execute-tool-calls delegate k resp opts))))
          k (chat-client/build-chat-client {:chat-model instrumented-cm
                                            :tools [#'get-weather]
                                            :tool-manager instrumented-manager})
          a (agent/create-agent {:chat-client k})
          result (agent/chat a
                   "Use get-weather exactly once with city 'Beijing', then briefly report the result."
                   {:max-iterations 2})
          manager-tool-calls (vec (mapcat identity @manager-calls))
          llm-tool-calls @observed-llm-tool-calls]
      (check "default manager wrapper was used through the instrumented delegate"
             (pos? (count @manager-calls)))
      (check "one tool loop used no more than two LLM calls"
             (<= 1 @llm-calls 2))
      (check "manager received tool calls produced by the LLM"
             (= llm-tool-calls manager-tool-calls))
      (check "recorded tool-call args have the expected map shape"
             (every? map? (map :args manager-tool-calls)))
      (check "final response text is non-empty"
              (not (str/blank? (str (:text result)))))
      (println "  LLM calls:" @llm-calls)
      (println "  Manager calls:" (count @manager-calls))
      (println "  Tool calls:" (pr-str manager-tool-calls)))
    (catch Throwable t
      (swap! failures inc)
      (println "  ERROR" (.getMessage t))))
  (if (zero? @failures)
    (println "All checks passed")
    (println @failures "checks failed"))
  (System/exit (if (zero? @failures) 0 1)))

(run)
