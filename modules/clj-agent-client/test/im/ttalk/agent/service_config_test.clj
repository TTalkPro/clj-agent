(ns im.ttalk.agent.service-config-test
  "create-agent 的 opts → provider 调用 config（common/service-config）。

   动机（docs/provider-variant-design.md P1）：这里早先是白名单
   {:model :max-tokens :temperature}，于是 provider 侧明明实现了的能力
   （Anthropic/MiniMax 的 :thinking 等）走 create-agent **递不到底**——
   `anthropic/build-params` 认 :thinking，agent 门面却把它挡在外面。

   改成排除法后，真正要钉住的是**反向**那条：编排层的键不许漏到 provider。
   `:tools` 漏下去尤其致命——service config 的 :tools 是已编译 schema，
   agent 的 :tools 是 tool var 向量，provider 会转出 {:name nil}，
   MiniMax 报 400「function name is empty」（P0 实验里真撞过）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.common :as common]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool sample-tool
  "示例工具"
  [[x :string "参数"]]
  (str "ok " x))

(defrecord RecordingProvider [seen]
  proto/ILLMProvider
  (provider-name [_] :recording)
  (call-llm [_ config _messages _tools]
    (reset! seen config)
    {:content [{:type "text" :text "ok"}]})
  (call-llm-stream [this config messages tools _on-token]
    (proto/call-llm this config messages tools))
  (extract-tool-calls [_ _] nil)
  (extract-text [_ response] (-> response :content first :text))
  (build-tool-result [_ id content] {:role "tool" :tool_call_id id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] false)
  (tool->schema [_ tool] tool))

;;; ============================================================
;;; service-config：纯函数层
;;; ============================================================

(deftest service-config-passes-provider-keys
  (testing "provider 专属键透传（P1 要修的就是这条）"
    (let [c (common/service-config {:provider :dummy
                                    :model "MiniMax-M2.7"
                                    :thinking {:type "adaptive"}
                                    :cache-strategy :system-and-tools
                                    :service-tier "auto"
                                    :top-k 40})]
      (is (= {:type "adaptive"} (:thinking c)) ":thinking 必须到得了 provider")
      (is (= :system-and-tools (:cache-strategy c)))
      (is (= "auto" (:service-tier c)))
      (is (= 40 (:top-k c)))))

  (testing "缺省值仍在"
    (let [c (common/service-config {})]
      (is (= "glm-4" (:model c)))
      (is (= 4096 (:max-tokens c)))))

  (testing "显式值覆盖缺省"
    (let [c (common/service-config {:model "gpt-4" :max-tokens 512 :temperature 0.2})]
      (is (= "gpt-4" (:model c)))
      (is (= 512 (:max-tokens c)))
      (is (= 0.2 (:temperature c)))))

  (testing "显式 nil 不覆盖 provider 侧默认（否则 (some? temperature) 那类判据会被 nil 骗过）"
    (is (not (contains? (common/service-config {:temperature nil}) :temperature)))))

(deftest service-config-excludes-orchestration-keys
  (testing "编排层的键一个都不许漏进 provider config"
    (let [c (common/service-config
              {:provider :dummy :model "m"
               :tools [#'sample-tool] :tool-vars [#'sample-tool]
               :kernel :k :filters [] :memory :store :pause-store :ps
               :callbacks {:on-turn-start identity} :conversation-id "cid"
               :max-iterations 3 :state-slots {:a inc} :tool-manager :tm
               :eligibility-fn identity :system-prompt "sys"
               :on-pause identity :on-error identity :on-env-error identity
               :cancel-token :ct :tool-choice :auto :id "agent-1"
               ;; 这个不是编排层的，必须留下——否则等于换了个白名单
               :thinking {:type "adaptive"}})]
      (is (= #{:model :max-tokens :thinking} (set (keys c)))
          "只该剩 model/max-tokens/thinking")))

  (testing ":tools 绝不下沉——漏了它 provider 会拿 tool var 当 schema 转（{:name nil}）"
    (is (not (contains? (common/service-config {:tools [#'sample-tool]}) :tools)))))

;;; ============================================================
;;; 端到端：build-kernel 造出的 service 真的把 config 递到了 provider
;;; ============================================================

(deftest build-kernel-threads-config-to-provider
  (testing "走 build-kernel → service → provider 一整条，:thinking 抵达 call-llm"
    (let [seen (atom nil)
          k (common/build-kernel {:provider (->RecordingProvider seen)
                                  :model "MiniMax-M2.7"
                                  :max-tokens 512
                                  :thinking {:type "adaptive"}
                                  :tools [#'sample-tool]
                                  :max-iterations 3})]
      ((get-in k [:service :chat-fn]) [{:role :user :content "hi"}] {})
      (is (= {:type "adaptive"} (:thinking @seen)) ":thinking 递到了 provider")
      (is (= "MiniMax-M2.7" (:model @seen)))
      (is (= 512 (:max-tokens @seen)))
      ;; kernel 会在调用时下发**已编译 schema**；基础 config 里不该有 agent 的 tool var
      (is (not (some var? (:tools @seen)))
          "provider 收到的 :tools 不能是 tool var")
      (is (nil? (:max-iterations @seen)) "编排层的键没漏下去"))))
