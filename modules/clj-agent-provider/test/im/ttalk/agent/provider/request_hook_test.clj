(ns im.ttalk.agent.provider.request-hook-test
  "feedbacks/2026-11-10-provider-request-hook-and-two-tier-cache-ttl.md 的判据，
   钉在**真正发出去的请求体**上（拦截 http/post / post-deferred / post-stream-*）：

   ① `:configure-request`：OpenAI 兼容 / Responses / Anthropic 四条路径同一处调用，
      看得见思考字段、`:extra-body` 与 `:stream`，删得掉库生成的键。
   ② Anthropic 两档缓存 TTL：system 1h + 对话尾巴 5m，恰好 2 个 cache_control，
      用到 1h 自动并入 beta extended-cache-ttl-2025-04-11；次序非法当场抛。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.common.cache :as cache]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.common.request-hook :as hook]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.agent.provider.openai-responses :as responses]
            [im.ttalk.agent.model :as proto]))

(def ^:private ok-openai
  {:status 200 :success? true
   :body {:id "c1" :model "qwen3-max"
          :choices [{:message {:role "assistant" :content "好"} :finish_reason "stop"}]}})

(def ^:private ok-anthropic
  {:status 200 :success? true
   :body {:id "m1" :model "claude-opus-4-8" :role "assistant" :type "message"
          :content [{:type "text" :text "好"}] :stop_reason "end_turn"
          :usage {:input_tokens 1 :output_tokens 1}}})

(defmacro ^:private capturing
  "执行 body，返回四条传输入口实际收到的 {:url :headers :body} 列表。"
  [ok & body]
  `(let [seen# (atom [])
         rec# (fn [url# opts#] (swap! seen# conj {:url url# :headers (:headers opts#) :body (:body opts#)}))]
     (with-redefs [http/post (fn [url# & {:as opts#}] (rec# url# opts#) ~ok)
                   http/post-deferred (fn [url# & {:as opts#}] (rec# url# opts#) ~ok)
                   stream-client/post-stream-sync (fn [url# opts#] (rec# url# opts#) (:body ~ok))
                   stream-client/post-stream-deferred (fn [url# opts#] (rec# url# opts#) (:body ~ok))]
       ~@body)
     @seen#))

;; qwen（百炼）的钩子：库生成的 thinking / reasoning_effort 删掉，改写为 enable_thinking；
;; 「非流式必须 false」是老模型 / 锦书 C# 版的规则（库缺省已放开）——这里借它演示钩子看得见 :stream。
(defn- qwen-hook [payload]
  (let [want? (boolean (:thinking payload))]
    (-> payload
        (dissoc :thinking :reasoning_effort)
        (assoc :enable_thinking (and want? (true? (:stream payload)))))))

(def ^:private qwen-config
  {:model "qwen3-max" :provider-name :dashscope
   :thinking-effort :high :thinking-style :top-level
   :configure-request qwen-hook})

;;; ============================================================
;;; ① :configure-request
;;; ============================================================

(deftest hook-contract-test
  (testing "没有钩子：原样"
    (is (= {:a 1} (hook/apply-hook {} {:a 1}))))
  (testing "钩子返回值即请求体"
    (is (= {:b 2} (hook/apply-hook {:configure-request (constantly {:b 2})} {:a 1}))))
  (testing "返回非 map：当场抛，不发半截请求体"
    (let [e (try (hook/apply-hook {:configure-request (constantly nil)} {:a 1}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-request-hook (:type (ex-data e)))))))

(deftest openai-compat-hook-sees-library-fields-test
  (testing "钩子看得见库生成的思考字段与 :extra-body，且在 :stream 之后"
    (let [seen (atom nil)]
      (compat/request-body (assoc qwen-config
                                  :extra-body {:x_private 1}
                                  :configure-request (fn [p] (reset! seen p) p))
                           [{:role "user" :content "hi"}] [] true)
      (is (true? (:stream @seen)))
      (is (= 1 (:x_private @seen)))
      (is (or (contains? @seen :thinking) (contains? @seen :reasoning_effort))
          "思考字段由 common.thinking 生成后才交给钩子"))))

(deftest openai-compat-four-paths-test
  (let [msgs [{:role "user" :content "hi"}]
        sent (capturing ok-openai
               (compat/call-api "http://x/v1/chat/completions" "k" qwen-config msgs [])
               (compat/call-api-deferred "http://x/v1/chat/completions" "k" qwen-config msgs [])
               (compat/call-api-stream "http://x/v1/chat/completions" "k" qwen-config msgs [] nil)
               (compat/call-api-stream-deferred "http://x/v1/chat/completions" "k" qwen-config msgs [] nil))]
    (is (= 4 (count sent)))
    (testing "qwen + 档位 high：线上没有 thinking / reasoning_effort，有 enable_thinking"
      (doseq [{:keys [body]} sent]
        (is (not (contains? body :thinking)))
        (is (not (contains? body :reasoning_effort)))
        (is (contains? body :enable_thinking))))
    (testing "非流式 false、流式 true（钩子按 :stream 判）"
      (is (= [false false true true] (mapv (comp :enable_thinking :body) sent))))))

(deftest kimi-drop-temperature-test
  (testing "kimi-k2：钩子删掉 temperature（:extra-body 删不掉的那种）"
    (let [[{:keys [body]}] (capturing ok-openai
                             (compat/call-api "http://x" "k"
                                              {:model "kimi-k2.7" :temperature 0.6
                                               :configure-request #(dissoc % :temperature)}
                                              [{:role "user" :content "hi"}] []))]
      (is (not (contains? body :temperature))))))

(deftest openai-responses-hook-test
  (let [p (responses/create-provider {:api-key "k" :model "gpt-5"})
        hookf (fn [b] (assoc b :hooked (boolean (:stream b))))
        cfg {:configure-request hookf}
        sent (capturing {:status 200 :success? true :body {:id "r" :output []}}
               (try (proto/call-llm p cfg [{:role "user" :content "hi"}] []) (catch Exception _))
               (try (proto/call-llm-stream p cfg [{:role "user" :content "hi"}] [] nil)
                    (catch Exception _)))]
    (is (= [false true] (mapv (comp :hooked :body) sent)))))

(deftest anthropic-hook-four-paths-test
  (let [cfg {:model "claude-opus-4-8" :api-key "k"
             :temperature 0.2
             :configure-request (fn [b] (-> b (dissoc :temperature) (assoc :seen-stream (boolean (:stream b)))))}
        msgs [{:role "user" :content "hi"}]
        sent (capturing ok-anthropic
               (anthropic/call-anthropic cfg msgs [])
               (anthropic/call-anthropic-deferred cfg msgs [])
               (anthropic/call-anthropic-stream cfg msgs [] nil)
               (anthropic/call-anthropic-stream-deferred cfg msgs [] nil))]
    (is (= 4 (count sent)))
    (is (every? #(not (contains? (:body %) :temperature)) sent))
    (is (= [false false true true] (mapv (comp :seen-stream :body) sent)))))

;;; ============================================================
;;; ② Anthropic 两档缓存 TTL
;;; ============================================================

(defn- count-cache-controls [x]
  (count (re-seq #":cache_control" (pr-str x))))

(deftest anthropic-two-tier-ttl-on-the-wire-test
  (let [cfg {:model "MiniMax-M3" :api-key "k" :auth-scheme :bearer
             :base-url "https://api.minimaxi.com/anthropic"
             :system-prompt "长系统提示"
             :cache-strategy :system-and-conversation
             :cache-ttl {:system "1h" :conversation nil}}
        [{:keys [body headers]}]
        (capturing ok-anthropic
          (anthropic/call-anthropic cfg [{:role "user" :content "一"}
                                         {:role "assistant" :content "二"}
                                         {:role "user" :content "三"}] []))]
    (testing "system 变块数组，唯一块 ttl 1h"
      (is (vector? (:system body)))
      (is (= 1 (count (:system body))))
      (is (= {:type "ephemeral" :ttl "1h"} (:cache_control (first (:system body))))))
    (testing "最后一条消息最后一块：ephemeral 且不带 ttl"
      (is (= {:type "ephemeral"} (-> body :messages last :content last :cache_control))))
    (testing "整份 payload 恰好 2 个 cache_control"
      (is (= 2 (count-cache-controls body))))
    (testing "anthropic-beta 自动含 extended-cache-ttl-2025-04-11"
      (is (str/includes? (get headers "anthropic-beta" "") "extended-cache-ttl-2025-04-11")))))

(deftest anthropic-cache-tail-skip-on-the-wire-test
  ;; 锦书反馈 2026-11-10 ④：幽灵续写 [前文, 应答, 正在写的一段] ⇒ :cache-tail-skip 1，断点落在应答上
  (let [cfg {:model "MiniMax-M3" :api-key "k" :auth-scheme :bearer
             :base-url "https://api.minimaxi.com/anthropic"
             :cache-strategy :conversation
             :cache-tail-skip 1}
        [{:keys [body]}]
        (capturing ok-anthropic
          (anthropic/call-anthropic cfg [{:role "user" :content "前文"}
                                         {:role "assistant" :content "应答"}
                                         {:role "user" :content "正在写的一段"}] []))
        [m0 m1 m2] (:messages body)]
    (is (= {:type "ephemeral"} (-> m1 :content last :cache_control)))
    (is (not (str/includes? (pr-str m2) "cache_control")) "最后一条不打")
    (is (not (str/includes? (pr-str m0) "cache_control")))
    (is (= 1 (count-cache-controls body)))))

(deftest anthropic-beta-merge-test
  (testing "用户 :beta 保留并去重"
    (let [[{:keys [headers]}]
          (capturing ok-anthropic
            (anthropic/call-anthropic {:model "m" :api-key "k" :system-prompt "s"
                                       :beta ["skills-2025-10-02" "extended-cache-ttl-2025-04-11"]
                                       :cache-strategy :system :cache-ttl "1h"}
                                      [{:role "user" :content "hi"}] []))]
      (is (= "skills-2025-10-02,extended-cache-ttl-2025-04-11" (get headers "anthropic-beta")))))
  (testing "全 5m：不加 beta 头（缺省不变）"
    (let [[{:keys [headers]}]
          (capturing ok-anthropic
            (anthropic/call-anthropic {:model "m" :api-key "k" :system-prompt "s"
                                       :cache-strategy :system-and-conversation}
                                      [{:role "user" :content "hi"}] []))]
      (is (not (contains? headers "anthropic-beta")))))
  (testing "1h 只给了策略不打的断点：不加 beta"
    (let [[{:keys [headers]}]
          (capturing ok-anthropic
            (anthropic/call-anthropic {:model "m" :api-key "k" :system-prompt "s"
                                       :cache-strategy :conversation :cache-ttl {:system "1h"}}
                                      [{:role "user" :content "hi"}] []))]
      (is (not (contains? headers "anthropic-beta"))))))

(deftest ttl-validation-test
  (let [params {:system "s" :tools [{:name "f" :input_schema {}}]
                :messages [{:role "user" :content "hi"}]}
        thrown (fn [strategy ttl]
                 (try (cache/apply-anthropic-cache params strategy ttl) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (testing "标量照旧：所有断点同档"
      (let [r (cache/apply-anthropic-cache params :system-and-tools "1h")]
        (is (= "1h" (-> r :system last :cache_control :ttl)))
        (is (= "1h" (-> r :tools last :cache_control :ttl)))))
    (testing "长在前、短在后：合法"
      (is (nil? (thrown :system-and-tools {:tools "1h" :system nil})))
      (is (nil? (thrown :system-and-conversation {:system "1h"}))))
    (testing "短在前、长在后：抛"
      (is (= :invalid-cache-ttl (thrown :system-and-conversation {:system nil :conversation "1h"})))
      (is (= :invalid-cache-ttl (thrown :system-and-tools {:tools nil :system "1h"}))))
    (testing "未知键（拼错）：抛，不静默落成 5m"
      (is (= :invalid-cache-ttl (thrown :system {:sytem "1h"}))))
    (testing "按断点取档"
      (let [r (cache/apply-anthropic-cache params :system-and-tools {:tools "1h"})]
        (is (= "1h" (-> r :tools last :cache_control :ttl)))
        (is (= {:type "ephemeral"} (-> r :system last :cache_control)))))))
