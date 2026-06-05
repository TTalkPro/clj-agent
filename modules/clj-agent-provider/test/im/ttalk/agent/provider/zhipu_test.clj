(ns im.ttalk.agent.provider.zhipu-test
  "智谱 GLM 对话补全文档字段与异步方案单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.schema.openai :as oai-schema]
            [im.ttalk.agent.provider.schema.anthropic :as ant-schema]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.zhipu :as zhipu]))

;; ============================================================
;; 对话补全请求字段
;; ============================================================

(deftest glm-request-fields-test
  (testing "GLM 文档字段透传：do_sample / tool_stream / request_id / user_id"
    (let [p (compat/build-params
              {:model "glm-4.7"
               :do-sample false
               :tool-stream true
               :request-id "req-123456"
               :user-id "user-123456"}
              [{:role "user" :content "hi"}] [])]
      (is (= false (:do_sample p)))
      (is (= true (:tool_stream p)))
      (is (= "req-123456" (:request_id p)))
      (is (= "user-123456" (:user_id p)))))
  (testing "未提供时不发送"
    (let [p (compat/build-params {:model "glm-4.7"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :do_sample)))
      (is (not (contains? p :tool_stream)))
      (is (not (contains? p :request_id)))
      (is (not (contains? p :user_id))))))

;; ============================================================
;; 预置工具类型透传
;; ============================================================

(deftest builtin-tools-passthrough-test
  (testing "OpenAI/GLM：web_search / retrieval / mcp 原样透传，简单定义仍包装为 function"
    (let [ws {:type "web_search" :web_search {:enable true :search_result true}}
          rt {:type "retrieval" :retrieval {:knowledge_id "kb1"}}
          fn-tool {:name :calc :description "计算" :parameters {:type "object"}}
          [a b c] (oai-schema/tools->schemas [ws rt fn-tool])]
      (is (= ws a))
      (is (= rt b))
      (is (= "function" (:type c)))
      (is (= "calc" (get-in c [:function :name])))))
  (testing "Anthropic：带 :type 服务端工具与已带 :input_schema 的定义透传"
    (let [server-tool {:type "web_search_20260209" :name "web_search"}
          wire-tool {:name "f" :description "d" :input_schema {:type "object"}}
          simple {:name :f2 :description "d2" :parameters {:type "object"}}
          [a b c] (ant-schema/tools->schemas [server-tool wire-tool simple])]
      (is (= server-tool a))
      (is (= wire-tool b))
      (is (contains? c :input_schema)))))

;; ============================================================
;; GLM finish_reason 归一化
;; ============================================================

(deftest glm-finish-reason-test
  (is (= :content-filter (response/normalize-finish-reason "sensitive")))
  (is (= :error (response/normalize-finish-reason "network_error")))
  (is (= :context-window-exceeded
         (response/normalize-finish-reason "model_context_window_exceeded"))))

;; ============================================================
;; 异步方案（mock HTTP）
;; ============================================================

(deftest async-submit-test
  (testing "submit-async 走 /async/chat/completions，携带 Bearer 与请求体"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url & {:as opts}]
                                (reset! captured {:url url :opts opts})
                                {:status 200 :success? true
                                 :body {:id "task-1" :task_status "PROCESSING"
                                        :request_id "r1" :model "glm-4.7"}})]
        (let [receipt (zhipu/submit-async {:model "glm-4.7" :api-key "k" :max-tokens 64}
                                          [{:role "user" :content "hi"}] nil)]
          (is (= "task-1" (:id receipt)))
          (is (= "PROCESSING" (:task_status receipt)))
          (is (= "https://open.bigmodel.cn/api/paas/v4/async/chat/completions"
                 (:url @captured)))
          (is (= "Bearer k" (get-in @captured [:opts :headers "Authorization"])))
          (is (= "glm-4.7" (get-in @captured [:opts :body :model]))))))))

(deftest async-await-test
  (testing "await-async-result 轮询至 SUCCESS"
    (let [calls (atom 0)]
      (with-redefs [http/get (fn [url & _]
                               (swap! calls inc)
                               {:status 200 :success? true
                                :body (if (< @calls 3)
                                        {:task_status "PROCESSING" :id "t1"}
                                        {:task_status "SUCCESS" :id "t1"
                                         :choices [{:message {:role "assistant" :content "好"}
                                                    :finish_reason "stop"}]
                                         :usage {:prompt_tokens 5 :completion_tokens 2}})})]
        (let [r (zhipu/await-async-result {:api-key "k"} "t1" :poll-interval-ms 1)]
          (is (= "SUCCESS" (:task_status r)))
          (is (= "好" (get-in r [:choices 0 :message :content])))
          (is (= 3 @calls))))))
  (testing "FAIL 抛 ex-info"
    (with-redefs [http/get (fn [url & _]
                             {:status 200 :success? true
                              :body {:task_status "FAIL" :id "t2"}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"async task failed"
                            (zhipu/await-async-result {:api-key "k"} "t2"
                                                      :poll-interval-ms 1)))))
  (testing "超时抛 ex-info"
    (with-redefs [http/get (fn [url & _]
                             {:status 200 :success? true
                              :body {:task_status "PROCESSING" :id "t3"}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timeout"
                            (zhipu/await-async-result {:api-key "k"} "t3"
                                                      :poll-interval-ms 1
                                                      :timeout-ms 10)))))
  (testing "查询 URL 正确"
    (let [captured (atom nil)]
      (with-redefs [http/get (fn [url & _]
                               (reset! captured url)
                               {:status 200 :success? true
                                :body {:task_status "SUCCESS"}})]
        (zhipu/query-async-result {:api-key "k"} "task-9")
        (is (= "https://open.bigmodel.cn/api/paas/v4/async-result/task-9" @captured))))))
