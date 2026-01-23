(ns im.ttalk.agent.llm.prompt.api-test
  "提示词模板测试"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.llm.prompt.api :as prompt]))

;; ============================================================
;; PromptTemplate 测试
;; ============================================================

(deftest prompt-template-test
  (testing "基础变量替换"
    (let [t (prompt/template "你好，{name}！")
          result (prompt/render t {:name "张三"})]
      (is (= "你好，张三！" result))))

  (testing "多变量替换"
    (let [t (prompt/template "你好，{name}！今年 {age} 岁。")
          result (prompt/render t {:name "张三" :age 25})]
      (is (= "你好，张三！今年 25 岁。" result))))

  (testing "获取输入变量"
    (let [t (prompt/template "你好，{name}！今年 {age} 岁。")
          vars (prompt/get-input-variables t)]
      (is (= #{:name :age} (set vars)))))

  (testing "变量提取"
    (let [vars (prompt/extract-variables "Hello {name}, your age is {age}")]
      (is (= #{:name :age} (set vars))))))

;; ============================================================
;; 变量验证测试
;; ============================================================

(deftest validation-test
  (testing "变量验证 - 有效"
    (let [t (prompt/template "{name}")
          result (prompt/validate-variables t {:name "张三"})]
      (is (:valid result))))

  (testing "变量验证 - 缺失变量"
    (let [t (prompt/template "{name} {age}")
          result (prompt/validate-variables t {:name "张三"})]
      (is (not (:valid result)))
      (is (= [:age] (:missing result)))))

  (testing "安全格式化 - 成功"
    (let [t (prompt/template "{name}")
          result (prompt/format-safe t {:name "张三"})]
      (is (:success result))
      (is (= "张三" (:result result)))))

  (testing "安全格式化 - 失败"
    (let [t (prompt/template "{name} {age}")
          result (prompt/format-safe t {:name "张三"})]
      (is (not (:success result)))
      (is (.contains (:error result) "age")))))

;; ============================================================
;; 部分格式化测试
;; ============================================================

(deftest partial-format-test
  (testing "部分格式化"
    (let [t (prompt/template "你好，{name}！今年 {age} 岁。")
          partial-t (prompt/partial-format t {:name "张三"})]
      (is (= [:age] (prompt/get-input-variables partial-t)))
      (is (= "你好，张三！今年 30 岁。" (prompt/render partial-t {:age 30}))))))

;; ============================================================
;; FewShotPromptTemplate 测试
;; ============================================================

(deftest few-shot-template-test
  (testing "少样本模板格式化"
    (let [t (prompt/few-shot-template
              {:prefix "请翻译以下文本："
               :suffix "输入：{input}\n输出："
               :examples [{:input "Hello" :output "你好"}
                          {:input "World" :output "世界"}]})
          result (prompt/render t {:input "Good morning"})]
      (is (.contains result "请翻译以下文本"))
      (is (.contains result "Hello"))
      (is (.contains result "你好"))
      (is (.contains result "Good morning")))))

;; ============================================================
;; ChatPromptTemplate 测试
;; ============================================================

(deftest chat-template-test
  (testing "聊天模板创建"
    (let [t (prompt/chat-template
              [(prompt/system "你是一个翻译助手")
               (prompt/human "{input}")])]
      (is (= [:input] (prompt/get-input-variables t)))))

  (testing "格式化为消息列表"
    (let [t (prompt/chat-template
              [(prompt/system "你是一个翻译助手")
               (prompt/human "{input}")])
          messages (prompt/format-messages t {:input "Hello"})]
      (is (= 2 (count messages)))
      (is (= "system" (:role (first messages))))
      (is (= "user" (:role (second messages))))
      (is (= "Hello" (:content (second messages))))))

  (testing "从消息描述创建"
    (let [t (prompt/from-messages
              [["system" "你是一个助手"]
               ["user" "{query}"]])
          messages (prompt/format-messages t {:query "测试"})]
      (is (= 2 (count messages)))
      (is (= "测试" (:content (second messages))))))

  (testing "AI 消息"
    (let [t (prompt/chat-template
              [(prompt/system "你是助手")
               (prompt/human "你好")
               (prompt/ai "你好！有什么可以帮助你的？")
               (prompt/human "{input}")])
          messages (prompt/format-messages t {:input "帮我翻译"})]
      (is (= 4 (count messages)))
      (is (= "assistant" (:role (nth messages 2)))))))

;; ============================================================
;; Example 选择器测试
;; ============================================================

(deftest length-selector-test
  (testing "基于长度选择"
    (let [examples [{:input "短" :output "S"}
                    {:input "这是一个较长的输入" :output "这是一个较长的输出"}
                    {:input "中等" :output "M"}]
          selector (prompt/length-selector {:examples examples :max-length 30})
          selected (prompt/select-examples selector {})]
      (is (<= (count selected) 3))
      (is (= "短" (:input (first selected)))))))

(deftest similarity-selector-test
  (testing "基于相似度选择"
    (let [examples [{:input "Hello" :output "你好"}
                    {:input "World" :output "世界"}
                    {:input "Hello World" :output "你好世界"}]
          selector (prompt/similarity-selector {:examples examples :k 2})
          selected (prompt/select-examples selector {:input "Hello"})]
      (is (= 2 (count selected)))
      ;; 最相似的应该在前面
      (is (some #(= "Hello" (:input %)) selected)))))

(deftest mmr-selector-test
  (testing "MMR 选择（多样性）"
    (let [examples [{:input "苹果" :output "apple"}
                    {:input "苹果手机" :output "iPhone"}
                    {:input "香蕉" :output "banana"}]
          selector (prompt/mmr-selector {:examples examples :k 2 :lambda 0.5})
          selected (prompt/select-examples selector {:input "苹果"})]
      (is (= 2 (count selected))))))

(deftest add-example-test
  (testing "添加示例"
    (let [selector (prompt/length-selector {:examples [] :max-length 100})
          updated (prompt/add-example selector {:input "新示例" :output "新输出"})
          selected (prompt/select-examples updated {})]
      (is (= 1 (count selected)))
      (is (= "新示例" (:input (first selected)))))))

;; ============================================================
;; 模板组合测试
;; ============================================================

(deftest template-combination-test
  (testing "聊天模板追加消息"
    (let [base (prompt/chat-template [(prompt/system "你是助手")])
          extended (prompt/append base [(prompt/human "{input}")])
          messages (prompt/format-messages extended {:input "测试"})]
      (is (= 2 (count messages))))))

;; ============================================================
;; 消息占位符测试
;; ============================================================

(deftest messages-placeholder-test
  (testing "消息占位符创建"
    (let [placeholder (prompt/messages-placeholder :history)]
      (is (= [:history] (prompt/get-input-variables placeholder))))))
