(ns im.ttalk.agent.provider.schema-test
  "工具 schema 转换 + 服务端工具构造 + 限流头解析单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.schema.openai :as oai]
            [im.ttalk.agent.provider.schema.anthropic :as ant]
            [im.ttalk.agent.provider.anthropic :as anthropic]))

;; deftool 宏生成的 schema 是 Anthropic 风格（:input_schema）
(def deftool-schema
  {:name "get_weather"
   :description "获取天气"
   :input_schema {:type "object"
                  :properties {:city {:type "string" :description "城市"}}
                  :required ["city"]}})

;; OpenAI 风格 schema（:parameters）
(def openai-style-schema
  {:name "calc"
   :description "计算"
   :parameters {:type "object" :properties {:expr {:type "string"}}}})

;; ============================================================
;; OpenAI 工具 schema 转换（含 :input_schema 兼容修复）
;; ============================================================

(deftest openai-tool-schema-input-schema-test
  (testing "deftool 的 :input_schema 不再被丢成空对象（回归测试）"
    (let [[t] (oai/tools->schemas [deftool-schema])
          params (get-in t [:function :parameters])]
      (is (= "function" (:type t)))
      (is (= "get_weather" (get-in t [:function :name])))
      ;; 关键：properties 必须完整保留
      (is (= {:city {:type "string" :description "城市"}} (:properties params)))
      (is (= ["city"] (:required params)))))
  (testing "OpenAI 风格 :parameters 仍正常"
    (let [[t] (oai/tools->schemas [openai-style-schema])]
      (is (= {:type "object" :properties {:expr {:type "string"}}}
             (get-in t [:function :parameters])))))
  (testing "无参数工具兜底空 object"
    (let [[t] (oai/tools->schemas [{:name "ping" :description "p"}])]
      (is (= {:type "object" :properties {}} (get-in t [:function :parameters]))))))

;; ============================================================
;; Anthropic 工具 schema 转换
;; ============================================================

(deftest anthropic-tool-schema-test
  (testing "deftool 的 :input_schema wire 格式原样透传"
    (let [[t] (ant/tools->schemas [deftool-schema])]
      (is (= (:input_schema deftool-schema) (:input_schema t)))
      (is (= "get_weather" (:name t)))))
  (testing "OpenAI 风格 :parameters 转为 :input_schema"
    (let [[t] (ant/tools->schemas [openai-style-schema])]
      (is (= {:type "object" :properties {:expr {:type "string"}}}
             (:input_schema t))))))

;; ============================================================
;; web_search 服务端工具构造器
;; ============================================================

(deftest web-search-tool-test
  (testing "默认仅含 type/name"
    (is (= {:type "web_search_20250305" :name "web_search"}
           (ant/web-search-tool))))
  (testing "选项映射到下划线字段"
    (let [t (ant/web-search-tool {:max-uses 5
                                  :allowed-domains ["docs.anthropic.com"]
                                  :user-location {:type "approximate" :country "CN"}})]
      (is (= 5 (:max_uses t)))
      (is (= ["docs.anthropic.com"] (:allowed_domains t)))
      (is (= {:type "approximate" :country "CN"} (:user_location t)))
      (is (not (contains? t :blocked_domains)))))
  (testing "web_search 工具作为 wire 工具能原样进入 tools（不被转换破坏）"
    (let [ws (ant/web-search-tool {:max-uses 3})
          [t] (ant/tools->schemas [ws])]
      (is (= ws t)))))

;; ============================================================
;; Anthropic 限流头解析
;; ============================================================

(def parse-rate-limit #'anthropic/parse-rate-limit)

(deftest parse-rate-limit-test
  (testing "字符串键 + 数值解析为 Long"
    (let [rl (parse-rate-limit
               {"anthropic-ratelimit-requests-limit" "1000"
                "anthropic-ratelimit-requests-remaining" "999"
                "anthropic-ratelimit-tokens-remaining" "48000"
                "anthropic-ratelimit-tokens-reset" "2026-06-08T00:00:00Z"
                "retry-after" "30"})]
      (is (= 1000 (:requests-limit rl)))
      (is (= 999 (:requests-remaining rl)))
      (is (= 48000 (:tokens-remaining rl)))
      (is (= "2026-06-08T00:00:00Z" (:tokens-reset rl)))
      (is (= 30 (:retry-after rl)))))
  (testing "keyword 键也能识别"
    (let [rl (parse-rate-limit {:anthropic-ratelimit-requests-remaining "5"})]
      (is (= 5 (:requests-remaining rl)))))
  (testing "无相关头返回 nil"
    (is (nil? (parse-rate-limit {"content-type" "application/json"})))
    (is (nil? (parse-rate-limit nil)))))

;; ============================================================
;; Citations：可引用文档块 + 响应引用提取
;; ============================================================

(deftest text-document-test
  (testing "默认启用引用"
    (let [d (ant/text-document "地球绕太阳公转。" {:title "天文"})]
      (is (= "document" (:type d)))
      (is (= {:type "text" :media_type "text/plain" :data "地球绕太阳公转。"} (:source d)))
      (is (= {:enabled true} (:citations d)))
      (is (= "天文" (:title d)))
      (is (not (contains? d :context)))))
  (testing ":citations? false 关闭引用，:context 透传"
    (let [d (ant/text-document "正文" {:citations? false :context "背景"})]
      (is (= {:enabled false} (:citations d)))
      (is (= "背景" (:context d))))))

(def extract-citations #'anthropic/extract-citations)

(deftest extract-citations-test
  (testing "从 text 块的 :citations 聚合引用"
    (let [resp {:content [{:type "text" :text "答案"
                           :citations [{:type "char_location" :cited_text "公转"
                                        :document_index 0 :document_title "天文"
                                        :start_char_index 0 :end_char_index 2}]}
                          {:type "text" :text "续"
                           :citations [{:type "char_location" :cited_text "自转"
                                        :document_index 1}]}]}
          cits (extract-citations resp)]
      (is (= 2 (count cits)))
      (is (= "公转" (:cited_text (first cits))))
      (is (= 1 (:document_index (second cits))))))
  (testing "无引用返回 nil"
    (is (nil? (extract-citations {:content [{:type "text" :text "纯文本"}]})))))

;; ============================================================
;; Skills（beta）：技能引用 / 容器 / code_execution 工具
;; ============================================================

(deftest skills-test
  (testing "skill 默认 type anthropic"
    (is (= {:type "anthropic" :skill_id "xlsx"} (ant/skill "xlsx")))
    (is (= {:type "anthropic" :skill_id "xlsx" :version "v2"}
           (ant/skill "xlsx" {:version "v2"})))
    (is (= {:type "custom" :skill_id "my"} (ant/skill "my" {:type "custom"}))))
  (testing "skills-container 包成 {:skills [...]}"
    (is (= {:skills [{:type "anthropic" :skill_id "xlsx"}
                     {:type "anthropic" :skill_id "pdf"}]}
           (ant/skills-container [(ant/skill "xlsx") (ant/skill "pdf")]))))
  (testing "code-execution-tool 默认 beta 类型"
    (is (= {:type "code_execution_20250825" :name "code_execution"}
           (ant/code-execution-tool)))
    (is (= "x" (:type (ant/code-execution-tool {:type "x"})))))
  (testing "default-skills-beta 含三个 beta 标识"
    (is (= 3 (count ant/default-skills-beta)))
    (is (some #(= "skills-2025-10-02" %) ant/default-skills-beta))))
