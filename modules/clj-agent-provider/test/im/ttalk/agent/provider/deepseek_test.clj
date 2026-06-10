(ns im.ttalk.agent.provider.deepseek-test
  "DeepSeek 专属差异单测：前缀续写（beta）+ SSE 末块 usage / finish_reason 捕获"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.agent.provider.stream.openai :as oai-stream]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.deepseek :as deepseek]))

;; ============================================================
;; mark-prefix
;; ============================================================

(deftest mark-prefix-test
  (testing "最后一条 assistant 消息被标记 prefix:true，前面消息不动"
    (let [msgs [{:role "user" :content "写一句诗"}
                {:role "assistant" :content "春天的风"}]
          marked (deepseek/mark-prefix msgs)]
      (is (= {:role "assistant" :content "春天的风" :prefix true} (last marked)))
      (is (= (first msgs) (first marked)))))
  (testing "keyword role 同样支持"
    (is (true? (:prefix (last (deepseek/mark-prefix [{:role :assistant :content "x"}]))))))
  (testing "最后一条非 assistant 抛 ExceptionInfo"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"last message"
                          (deepseek/mark-prefix [{:role "user" :content "hi"}])))
    (is (thrown? clojure.lang.ExceptionInfo (deepseek/mark-prefix [])))))

;; ============================================================
;; 前缀续写（同步，mock HTTP）
;; ============================================================

(deftest prefix-completion-sync-test
  (testing "走 beta 路径，请求体最后一条消息带 prefix:true"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url & {:as opts}]
                                (reset! captured {:url url :opts opts})
                                {:status 200 :success? true
                                 :body {:id "c1" :model "deepseek-chat"
                                        :choices [{:message {:role "assistant"
                                                             :content "，吹绿了江南。"}
                                                   :finish_reason "stop"}]}})]
        (let [r (deepseek/call-prefix-completion
                  {:model "deepseek-chat" :api-key "k" :max-tokens 64 :stop ["。"]}
                  [{:role "user" :content "写一句诗"}
                   {:role "assistant" :content "春天的风"}]
                  nil)]
          (is (= "，吹绿了江南。" (get-in r [:choices 0 :message :content])))
          (is (= "https://api.deepseek.com/beta/chat/completions" (:url @captured)))
          (is (= "Bearer k" (get-in @captured [:opts :headers "Authorization"])))
          (let [body-msgs (get-in @captured [:opts :body :messages])]
            (is (= true (:prefix (last body-msgs))))
            (is (= "春天的风" (:content (last body-msgs)))))
          ;; stop 透传
          (is (= ["。"] (get-in @captured [:opts :body :stop]))))))))

;; ============================================================
;; SSE 端到端：流式续写 + reasoning 分流 + 末块 usage/finish_reason
;; ============================================================

(deftest prefix-completion-sse-test
  (testing "SSE 流式：token 分流、末块 usage（含 cache hit/miss）与真实 finish_reason 被捕获"
    (let [lines ["data: {\"id\":\"c2\",\"model\":\"deepseek-reasoner\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"先想\"}}]}"
                 ""
                 "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"一想\"}}]}"
                 ""
                 "data: {\"choices\":[{\"delta\":{\"content\":\"，吹绿\"}}]}"
                 ""
                 "data: {\"choices\":[{\"delta\":{\"content\":\"江南\"}}]}"
                 ""
                 "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"
                 ""
                 "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":6,\"total_tokens\":16,\"prompt_cache_hit_tokens\":8,\"prompt_cache_miss_tokens\":2}}"
                 ""
                 "data: [DONE]"]
          ans (StringBuilder.)
          think (StringBuilder.)]
      ;; stub 新的真流式传输：回放 SSE 行，驱动与生产同一个 parse-fn/process-fn
      (with-redefs [stream-client/post-stream-async
                    (fn [url {:keys [parse-fn process-fn initial-state on-token on-complete]}]
                      (is (= "https://api.deepseek.com/beta/chat/completions" url))
                      (let [final (reduce (fn [st line]
                                            (if-let [ev (parse-fn line)]
                                              (let [[nst tok] (process-fn ev st)]
                                                (when (and tok on-token) (on-token tok))
                                                nst)
                                              st))
                                          initial-state lines)]
                        (on-complete final))
                      {:future (java.util.concurrent.CompletableFuture/completedFuture nil)
                       :cancel (fn [])})]
        (let [r (deepseek/call-prefix-completion-stream
                  {:model "deepseek-reasoner" :api-key "k"}
                  [{:role "assistant" :content "春天的风"}]
                  nil
                  (fn [{:keys [token reasoning-token]}]
                    (when token (.append ans token))
                    (when reasoning-token (.append think reasoning-token))))]
          ;; token 分流
          (is (= "，吹绿江南" (.toString ans)))
          (is (= "先想一想" (.toString think)))
          ;; 最终响应：reasoning 与答案分离
          (is (= "，吹绿江南" (get-in r [:choices 0 :message :content])))
          (is (= "先想一想" (get-in r [:choices 0 :message :reasoning_content])))
          ;; 末块捕获
          (is (= "stop" (get-in r [:choices 0 :finish_reason])))
          (is (= 10 (get-in r [:usage :prompt_tokens])))
          ;; usage 归一化含 DeepSeek 缓存命中/未命中
          (let [u (response/normalize-usage (:usage r))]
            (is (= 8 (:cache-read-tokens u)))
            (is (= 2 (:cache-miss-tokens u)))
            (is (= 16 (:total-tokens u)))))))))

;; ============================================================
;; 流处理器单元：usage/finish_reason 捕获 + stream_options 透传
;; ============================================================

(deftest stream-usage-capture-unit-test
  (testing "process-chunk 捕获末块 usage 与 finish_reason"
    (let [s1 (first (oai-stream/process-chunk
                      {:choices [{:delta {:content "x"} :finish_reason nil}]}
                      (oai-stream/make-initial-state)))
          s2 (first (oai-stream/process-chunk
                      {:choices [{:delta {} :finish_reason "length"}]} s1))
          s3 (first (oai-stream/process-chunk
                      {:choices [] :usage {:prompt_tokens 1 :completion_tokens 2}} s2))
          raw (oai-stream/build-response s3 :id "i" :model "m")]
      (is (= "length" (get-in raw [:choices 0 :finish_reason])))
      (is (= {:prompt_tokens 1 :completion_tokens 2} (:usage raw)))
      (let [n (oai-stream/normalize-response s3 :id "i" :model "m")]
        (is (= :max-tokens (:finish-reason n)))
        (is (= 1 (get-in n [:usage :input-tokens])))))))

(deftest stream-options-param-test
  (testing ":stream-options 透传为 stream_options"
    (let [p (compat/build-params
              {:model "deepseek-chat" :stream-options {:include_usage true}}
              [{:role "user" :content "hi"}] [])]
      (is (= {:include_usage true} (:stream_options p))))))
