(ns im.ttalk.agent.provider.reliability-test
  "designs/provider-reliability.md 的 provider 侧纯函数：业务码表（§1）、限流快照（§6）、
   thinking 档位（§7）、服务端工具块视图（§9）、anthropic 回放块扩展（§10）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.anthropic-server-tools :as st]
            [im.ttalk.agent.provider.common.business-codes :as codes]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.common.rate-limit :as rl]
            [im.ttalk.agent.provider.common.thinking :as thinking]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.retry :as hretry]
            [im.ttalk.agent.provider.openai-responses :as responses]
            [im.ttalk.agent.model :as proto]))

;;; ============================================================
;;; §1 业务码表
;;; ============================================================

(defn- resp->err [status body provider & [headers]]
  (http/response->error {:status status :body body :headers headers} provider))

(deftest business-codes-test
  (testing "OpenAI 兼容 429 + insufficient_quota → 欠费，不可重试"
    (let [e (resp->err 429 {:error {:message "You exceeded your current quota" :type "insufficient_quota"
                                    :code "insufficient_quota"}} :openai)]
      (is (= :quota-exhausted-error (:type e)))
      (is (false? (:retryable? e)))
      (is (= "insufficient_quota" (:code e)))
      (is (not (errors/rate-limit-error? e)))))
  (testing "普通 429 仍是限流（可重试）"
    (let [e (resp->err 429 {:error {:message "slow down" :type "rate_limit_error"}} :anthropic)]
      (is (= :rate-limit-error (:type e)))
      (is (true? (:retryable? e)))))
  (testing "Anthropic billing_error"
    (is (= :quota-exhausted-error
           (:type (resp->err 400 {:type "error" :error {:type "billing_error" :message "credit"}} :anthropic)))))
  (testing "上下文超长：码 / 文案"
    (is (= :context-overflow-error
           (:type (resp->err 400 {:error {:code "context_length_exceeded" :message "too long"}} :deepseek))))
    (let [e (resp->err 400 {:type "error" :error {:type "invalid_request_error"
                                                  :message "prompt is too long: 210000 tokens > 200000 maximum"}}
                       :anthropic)]
      (is (= :context-overflow-error (:type e)))
      (is (false? (:retryable? e)))))
  (testing "智谱 1309（400 / 429 / 字符串或数字码）"
    (is (= :quota-exhausted-error (:type (resp->err 400 {:code 1309 :msg "欠费"} :zhipu))))
    (is (= :quota-exhausted-error (:type (resp->err 429 {:error {:code "1311" :message "余额不足"}} :zhipu))))
    (is (= :rate-limit-error (:type (resp->err 429 {:error {:code "1302" :message "并发"}} :glm))))
    (is (not= :quota-exhausted-error (:type (resp->err 400 {:code 1309 :msg "x"} :openai)))
        "智谱专属码不在别家生效"))
  (testing "文案只收紧：401 不被文案改判；限流不被改成欠费；5xx 文案像欠费 → 欠费"
    (is (= :auth-error (:type (resp->err 401 {:error {:message "billing problem"}} :openai))))
    (is (= :rate-limit-error (:type (resp->err 429 {:error {:message "billing too fast?"}} :anthropic))))
    (is (= :quota-exhausted-error (:type (resp->err 503 "insufficient balance" :foo)))))
  (testing "Retry-After：List 值（java.net.http 真实形状）与正文 try again in"
    (is (= 7000 (:retry-after-ms (resp->err 429 "x" :openai {"retry-after" (java.util.List/of "7")}))))
    (is (= 1500 (:retry-after-ms (resp->err 429 {:error {:message "Please try again in 1.5s"}} :openai))))
    (is (= 200 (codes/retry-delay-text "try again in 200ms")))
    (is (= 120000 (hretry/parse-retry-after {"retry-after" ["120"]}))))
  (testing "HTTP 200 业务错（MiniMax base_resp）"
    (let [e (codes/body-error :minimax {:base_resp {:status_code 1039 :status_msg "余额不足"}})]
      (is (= :quota-exhausted-error (:type e)))
      (is (= 200 (:status e))))
    (is (= :provider-error (:type (codes/body-error :minimax {:base_resp {:status_code 2013 :status_msg "invalid"}}))))
    (is (nil? (codes/body-error :minimax {:base_resp {:status_code 0}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (http/success-body {:status 200 :body {:base_resp {:status_code 2056 :status_msg "x"}}} :minimax))))
  (testing "extract-message 各家形状"
    (is (= "a" (codes/extract-message {:error {:message "a"}})))
    (is (= "b" (codes/extract-message "{\"msg\":\"b\"}")))
    (is (= "c" (codes/extract-message {:base_resp {:status_msg "c"}})))))

(deftest success-body-test
  (testing "限流头挂 :rate-limit；模型名核对"
    (let [b (http/success-body {:status 200 :body {:model "m" :x 1}
                                :headers {"x-ratelimit-remaining-tokens" (java.util.List/of "99")}}
                               :openai)]
      (is (= 99 (get-in b [:rate-limit :tokens-remaining]))))
    (is (= :model-mismatch
           (try (http/success-body {:status 200 :body {:model "other"}} :minimax {:check-model? true :model "m"})
                nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
    (is (map? (http/success-body {:status 200 :body {:model "m"}} :minimax {:check-model? true :model "m"})))))

;;; ============================================================
;;; §6 限流快照
;;; ============================================================

(deftest rate-limit-test
  (testing "OpenAI 与 Anthropic 头折成同一形状"
    (let [o (rl/parse-rate-limit {"x-ratelimit-limit-requests" "500" "x-ratelimit-remaining-tokens" "1200.6"
                                  "x-ratelimit-reset-tokens" "6m0s"})
          a (rl/parse-rate-limit {"anthropic-ratelimit-requests-limit" (java.util.List/of "500")
                                  "anthropic-ratelimit-tokens-remaining" "1201"})]
      (is (= {:requests-limit 500 :tokens-remaining 1201 :tokens-reset "6m0s"} o))
      (is (= {:requests-limit 500 :tokens-remaining 1201} a))))
  (testing "全空 / 只有 retry-after → nil；anthropic 旧入口委托"
    (is (nil? (rl/parse-rate-limit {"content-type" "x"})))
    (is (nil? (rl/parse-rate-limit {"retry-after" "3"})))
    (is (= 5 (:requests-remaining (anthropic/parse-rate-limit {:anthropic-ratelimit-requests-remaining "5"}))))))

;;; ============================================================
;;; §7 thinking 档位
;;; ============================================================

(deftest thinking-openai-test
  (testing "五种风格"
    (is (= {:reasoning_effort "high"} (thinking/openai-fields {:thinking-effort "high"} false)))
    (is (= {:thinking {:type "enabled" :reasoning_effort "max"}}
           (thinking/openai-fields {:thinking-effort "max" :provider-name :deepseek} false))
        "deepseek 预设 nested，档位原样上 wire")
    (is (= {:thinking {:type "enabled"} :reasoning_effort "low"}
           (thinking/openai-fields {:thinking-effort :low :thinking-style :top-level} false)))
    (is (= {:enable_thinking true} (thinking/openai-fields {:thinking-enabled true :thinking-style :qwen} true)))
    (is (= {:enable_thinking true} (thinking/openai-fields {:thinking-effort "high" :thinking-style :qwen} false))
        "qwen 非流式也开（2026-11-10 放开：qwen3.8 真机非流式开思考不 400）")
    (is (= {:enable_thinking false} (thinking/openai-fields {:thinking-enabled false :thinking-style :qwen} false)))
    (is (= {} (thinking/openai-fields {:thinking-effort "high" :thinking-style :none} false))))
  (testing "只开 / 关；effort 压过 enabled；都没给 → 空"
    (is (= {:thinking {:type "disabled"}} (thinking/openai-fields {:thinking-enabled false} false)))
    (is (= {:reasoning_effort "high"} (thinking/openai-fields {:thinking-effort "high" :thinking-enabled false} false)))
    (is (= {} (thinking/openai-fields {} false))))
  (testing "显式 :thinking / :reasoning-effort 优先（不破坏透传）"
    (let [p (compat/build-params {:model "glm" :thinking {:type "enabled" :clear_thinking true}
                                  :thinking-enabled false}
                                 [{:role "user" :content "hi"}] nil)]
      (is (= {:type "enabled" :clear_thinking true} (:thinking p))))
    (let [p (compat/build-params {:model "o3" :reasoning-effort "low" :thinking-effort "high"}
                                 [{:role "user" :content "hi"}] nil)]
      (is (= "low" (:reasoning_effort p)))))
  (testing "build-params 接入"
    (is (= {:type "enabled" :reasoning_effort "high"}
           (:thinking (compat/build-params {:model "d" :provider-name :deepseek :thinking-effort "high"}
                                           [{:role "user" :content "hi"}] nil))))))

(deftest thinking-anthropic-test
  (is (= {:thinking {:type "adaptive" :display "summarized"} :output_config {:effort "high"}}
         (thinking/anthropic-fields {:thinking-effort "high"})))
  (is (= {:thinking {:type "adaptive"}} (thinking/anthropic-fields {:thinking-enabled true})))
  (is (= {:thinking {:type "disabled"}} (thinking/anthropic-fields {:thinking-enabled false})))
  (is (= {} (thinking/anthropic-fields {:thinking-enabled false :thinking-always-on? true})))
  (is (= {:thinking {:type "enabled" :budget_tokens 2048}}
         (thinking/anthropic-fields {:thinking-effort "high" :thinking-budget-tokens 2048}))
      "预算压过档位")
  (is (= {:thinking {:type "disabled"}}
         (thinking/anthropic-fields {:thinking-enabled false :thinking-budget-tokens 2048}))
      "关压得过预算")
  (is (= {:output_config {:effort "low"}}
         (thinking/anthropic-fields {:thinking {:type "enabled" :budget_tokens 1} :thinking-effort "low"}))
      "显式 :thinking 优先"))

(deftest thinking-responses-test
  (is (= {:effort "medium"}
         (:reasoning (responses/build-params {:model "gpt-5" :thinking-effort "medium"} {:input []} nil))))
  (is (= {:effort "low"}
         (:reasoning (responses/build-params {:model "gpt-5" :reasoning-effort "low" :thinking-effort "high"}
                                             {:input []} nil)))))

;;; ============================================================
;;; §9 服务端工具块视图 / §10 回放块
;;; ============================================================

(def ^:private server-content
  [{:type "text" :text "我查一下"}
   {:type "server_tool_use" :id "srv1" :name "web_search" :input {:query "clojure"}}
   {:type "web_search_tool_result" :tool_use_id "srv1"
    :content [{:type "web_search_result" :url "https://clojure.org" :title "Clojure" :page_age "1d"}]}
   {:type "server_tool_use" :id "srv2" :name "code_execution" :input {:code "print(1)"}}
   {:type "code_execution_tool_result" :tool_use_id "srv2"
    :content {:type "code_execution_result" :stdout "1\n" :stderr "" :return_code 0}}
   {:type "web_search_tool_result" :tool_use_id "srv3"
    :content {:type "web_search_tool_result_error" :error_code "max_uses_exceeded"}}])

(deftest server-tools-test
  (let [views (mapv #(dissoc % :raw) (st/describe-all server-content))]
    (is (= [{:kind :web-search-call :id "srv1" :queries ["clojure"]}
            {:kind :web-search-result :tool-use-id "srv1"
             :results [{:url "https://clojure.org" :title "Clojure" :page-age "1d"}]}
            {:kind :code-execution-call :id "srv2" :name "code_execution" :input "print(1)"}
            {:kind :code-execution-result :tool-use-id "srv2" :block-type "code_execution_tool_result"
             :stdout "1\n" :return-code 0}
            {:kind :web-search-result :tool-use-id "srv3" :results [] :error-code "max_uses_exceeded"}]
           views)))
  (is (nil? (st/describe {:type "text" :text "x"})))
  (is (= :server-tool-call (:kind (st/describe {:type "server_tool_use" :id "x" :name "web_fetch" :input {}})))))

(deftest anthropic-replay-blocks-test
  (let [p (anthropic/create-provider {:api-key "x"})]
    (testing "pause_turn / 服务端工具块 → 捕获整段 content（续跑要原样回放）"
      (is (= :anthropic-content
             (:format (proto/replay-blocks p {:stop_reason "pause_turn" :content server-content}))))
      (is (some? (proto/replay-blocks p {:stop_reason "end_turn" :content server-content}))))
    (testing "普通 text / tool_use 不捕获（重建路径不变）"
      (is (nil? (proto/replay-blocks p {:stop_reason "end_turn"
                                        :content [{:type "text" :text "hi"}
                                                  {:type "tool_use" :id "t" :name "x" :input {}}]}))))))
