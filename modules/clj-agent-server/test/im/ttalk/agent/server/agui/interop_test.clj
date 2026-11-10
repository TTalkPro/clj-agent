(ns im.ttalk.agent.server.agui.interop-test
  "C# InteropTests.cs 能在 JVM 上做的那一半。C# 用官方 AG-UI .NET SDK（AGUI.Client）当客户端；JVM 上没有官方 AG-UI SDK，
   这里按线上形状逐条对（SDK 解码要的那几个字段都在）。

   跳过：
   - SdkChatClientRunsToolRun —— 要 AGUI.Client 的 IChatClient（JVM 无对应物）；
   - SdkDecodesEveryEvent 里 RUN_FINISHED.usage 的数值断言、SdkDecodesRunErrorUsageAndProvider 的 usage / provider ——
     用量由 provider 的响应带上来（clj-agent 的 response-usage），脚本化 provider 不报用量，且 clj-agent 的 usage 是
     「每次 LLM 往返一条」的数组，C# 是聚合成一条。RUN_ERROR 本身在下面验了。"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]))

(set! *warn-on-reflection* true)

(defn- body []
  (json/generate-string {:threadId "t-1" :runId "r-1"
                         :messages [{:id "u1" :role "user" :content "跑一下"}]
                         :tools [] :context []}))

(deftest wire-decodes-every-event
  (let [p (s/provider [(s/tool-call "tc-1" "echo" {:text "喂"}) (s/text "跑完了")])
        reg (registry/registry [(s/entry "keel" p {:tools [#'s/echo]})])]
    (with-agui [c (s/start reg {:version "1.0.0"})]
      (let [evs (:events (s/sse! c "agent/keel/run" (body)))]
        (is (= ["RUN_STARTED" "TOOL_CALL_START" "TOOL_CALL_ARGS" "TOOL_CALL_END" "TOOL_CALL_RESULT"
                "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"] (s/types evs)))
        (let [start (evs 1)]
          (is (= "tc-1" (:toolCallId start)))
          (is (= "echo" (:toolCallName start))))
        (is (= "{\"text\":\"喂\"}" (:delta (evs 2))))
        (is (= "tc-1" (:toolCallId (evs 4))))
        (is (string? (:content (evs 4))))
        (is (= "跑完了" (:delta (evs 6))))
        (is (= "assistant" (:role (evs 5))))
        (let [fin (peek evs)]
          (is (= "t-1" (:threadId fin)))
          (is (= "r-1" (:runId fin))))))))

(deftest provider-failure-is-run-error
  (let [p (s/provider [(fn [_] (throw (IllegalStateException. "炸了")))])
        reg (registry/registry [(s/entry "keel" p)])]
    (with-agui [c (s/start reg {:version "1.0.0"})]
      (let [evs (:events (s/sse! c "agent/keel/run" (body)))
            err (peek evs)]
        (is (= "RUN_STARTED" (:type (first evs))))
        (is (= "RUN_ERROR" (:type err)))
        (is (re-find #"炸了" (str (:message err))))))))
