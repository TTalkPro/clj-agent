(ns im.ttalk.agent.provider.factory.config-test
  "Factory 配置：环境变量名规范、三级合并优先级、validate 各分支。

   env 读取经私有 getenv 注入桩（不依赖真实环境变量）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.factory.config :as config]))

(defn- with-fake-env
  "以 {\"NAME\" \"value\"} 桩替 getenv 执行 f。"
  [env f]
  (with-redefs [im.ttalk.agent.provider.factory.config/getenv
                (fn [n] (get env n))]
    (f)))

;;; ============================================================
;;; 环境变量名规范（回归：曾生成 OPENAI_API-KEY，连字符致 env 读取从未生效）
;;; ============================================================

(deftest env-var-name-uses-underscores-test
  (testing ":api-key/:base-url 映射到 OPENAI_API_KEY/OPENAI_BASE_URL（下划线，非连字符）"
    (with-fake-env {"OPENAI_API_KEY"  "sk-from-env"
                    "OPENAI_BASE_URL" "https://proxy.example.com/v1"}
      #(let [cfg (config/load-config-from-env :openai)]
         (is (= "sk-from-env" (:api-key cfg)))
         (is (= "https://proxy.example.com/v1" (:base-url cfg))))))

  (testing "连字符形式的变量名不再被查找"
    (with-fake-env {"OPENAI_API-KEY" "sk-dash"}
      #(let [cfg (config/load-config-from-env :openai)]
         ;; 默认配置的 api-key 是 ""，env 未命中 → 保持默认
         (is (= "" (:api-key cfg)))))))

(deftest env-timeout-parsed-as-long-test
  (testing ":timeout 从 env 读取时 parse-long"
    (with-fake-env {"DEEPSEEK_TIMEOUT" "45000"}
      #(is (= 45000 (:timeout (config/load-config-from-env :deepseek)))))))

;;; ============================================================
;;; 三级合并优先级：default < env < user
;;; ============================================================

(deftest resolve-config-precedence-test
  (testing "env 覆盖 default，user 覆盖 env"
    (with-fake-env {"OPENAI_API_KEY" "sk-env"
                    "OPENAI_MODEL"   "gpt-4o-env"}
      #(let [[status cfg] (config/resolve-config :openai {:model "gpt-4o-user"})]
         (is (= :ok status))
         (is (= "sk-env" (:api-key cfg))       "env 覆盖 default 的空 api-key")
         (is (= "gpt-4o-user" (:model cfg))    "user 覆盖 env 的 model")
         (is (= "https://api.openai.com/v1" (:base-url cfg)) "未覆盖时取 default"))))

  (testing "use-env? false 时跳过 env 层"
    (with-fake-env {"OPENAI_API_KEY" "sk-env"}
      #(let [[status errors] (config/resolve-config :openai {} false)]
         (is (= :error status) "无 user api-key 且不读 env → 校验失败")
         (is (contains? errors :api-key))))))

;;; ============================================================
;;; validate-config 各分支
;;; ============================================================

(deftest validate-config-branches-test
  (testing "api-key 缺失 / 空串"
    (let [[s e] (config/validate-config :openai {})]
      (is (= :error s))
      (is (= ["is required"] (:api-key e))))
    (let [[s e] (config/validate-config :openai {:api-key "  "})]
      (is (= :error s))
      (is (= ["must not be empty"] (:api-key e)))))

  (testing "免 api-key provider（ollama/mock）不校验 api-key"
    (is (= :ok (first (config/validate-config :ollama {}))))
    (is (= :ok (first (config/validate-config :mock {})))))

  (testing "timeout 必须为正整数"
    (let [[s e] (config/validate-config :ollama {:timeout 0})]
      (is (= :error s))
      (is (contains? e :timeout)))
    (let [[s e] (config/validate-config :ollama {:timeout "fast"})]
      (is (= :error s))
      (is (contains? e :timeout))))

  (testing "base-url / model 空串报错"
    (let [[s e] (config/validate-config :ollama {:base-url " "})]
      (is (= :error s))
      (is (contains? e :base-url)))
    (let [[s e] (config/validate-config :ollama {:model ""})]
      (is (= :error s))
      (is (contains? e :model)))))
