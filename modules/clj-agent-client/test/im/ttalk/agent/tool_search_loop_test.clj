(ns im.ttalk.agent.tool-search-loop-test
  "ToolSearch 端到端（真实 react 循环）

   钉住渐进式披露的完整往返：第 1 轮模型只看见 search_tools → 检索 →
   :writes 经屏障折叠进 tool-context → 第 2 轮模型看见检索到的工具 → 调用它。

   这条链路跨了三个契约（context 每轮进 ChatRequest / terminal 由 request 重建
   chat-opts / :writes 槽级折叠），单测各测各的，唯有本测试证明它们真能拼起来。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.filter.tool-search :as ts]
            [im.ttalk.agent.react :as react]))

(deftool get-weather
  "获取指定城市的天气信息"
  [[city :string "城市名称"]]
  (str city "：晴，26°C"))

(deftool send-email
  "发送一封邮件给指定收件人"
  [[to :string "收件人"]]
  (str "已发送给 " to))

(defn- build-kernel-with-search [svc store]
  (kernel/build-kernel
    (ts/with-tool-search
      {:service svc
       :tools [#'get-weather #'send-email]
       :filters [(ma/memory-filter store)]}
      {:index (ts/keyword-tool-index)})))

(deftest progressive-disclosure-round-trip-test
  (let [seen-tools (atom [])          ;; 每轮模型看见的工具名
        turn (atom 0)
        svc {:chat-fn
             (fn [_msgs opts]
               (swap! seen-tools conj (mapv :name (:tools opts)))
               (case (swap! turn inc)
                 ;; 第 1 轮：只有 search_tools 可用 → 检索天气能力
                 1 (response/make-response
                     :text nil
                     :tool-calls [{:id "s1" :name "search_tools"
                                   :args {:query "查询天气"}}])
                 ;; 第 2 轮：get_weather 应已出现在工具列表里 → 调用它
                 2 (response/make-response
                     :text nil
                     :tool-calls [{:id "w1" :name "get-weather"
                                   :args {:city "北京"}}])
                 (response/make-response :text "北京今天晴，26°C" :tool-calls nil)))}
        store (memory/in-memory-store)
        k (build-kernel-with-search svc store)
        result (react/invoke k store
                             [{:role :user :content "北京天气怎么样？"}]
                             {:context (ctx/create)})]

    (testing "循环正常收敛"
      (is (= :completed (:status result)))
      (is (= "北京今天晴，26°C" (get-in result [:response :text]))))

    (testing "第 1 轮：只暴露 search_tools —— 两个业务工具都不进 prompt"
      (is (= ["search_tools"] (first @seen-tools))))

    (testing "第 2 轮：检索到的 get-weather 加入工具列表（send-email 仍不暴露）"
      (is (= ["search_tools" "get-weather"] (second @seen-tools))))

    (testing "第 3 轮：已发现工具保持可见（发现集合随 tool-context 累积）"
      (is (= ["search_tools" "get-weather"] (nth @seen-tools 2))))

    (testing "发现集合落在 tool-context 的命名空间槽里"
      (is (= #{"get-weather"}
             (ctx/get-var (:tool-context result) ts/discovered-slot))))

    (testing "工具确实被执行（search + get-weather 各一次）"
      (is (= ["search_tools" "get-weather"]
             (mapv #(name (:name %)) (:tool-calls-made result)))))))

(deftest discovery-accumulates-across-searches-test
  (testing "两次检索的发现集合并集累积（槽 reducer 为 into，非 last-writer）"
    (let [seen-tools (atom [])
          turn (atom 0)
          svc {:chat-fn
               (fn [_msgs opts]
                 (swap! seen-tools conj (mapv :name (:tools opts)))
                 (case (swap! turn inc)
                   1 (response/make-response
                       :text nil
                       :tool-calls [{:id "s1" :name "search_tools"
                                     :args {:query "查询天气"}}])
                   2 (response/make-response
                       :text nil
                       :tool-calls [{:id "s2" :name "search_tools"
                                     :args {:query "发送邮件"}}])
                   (response/make-response :text "都找到了" :tool-calls nil)))}
          store (memory/in-memory-store)
          k (build-kernel-with-search svc store)
          result (react/invoke k store
                               [{:role :user :content "查天气再发邮件"}]
                               {:context (ctx/create)})]
      (is (= :completed (:status result)))
      (is (= ["search_tools" "get-weather"] (second @seen-tools))
          "第 2 轮只发现了 get-weather")
      (is (= ["search_tools" "get-weather" "send-email"] (nth @seen-tools 2))
          "第 3 轮两次检索的结果并存——后一次检索没有覆盖前一次")
      (is (= #{"get-weather" "send-email"}
             (ctx/get-var (:tool-context result) ts/discovered-slot))))))

(deftest search-miss-does-not-poison-context-test
  (testing "检索未命中 → 不写 context，模型收到可行动提示后照常收尾"
    (let [turn (atom 0)
          svc {:chat-fn
               (fn [_msgs _opts]
                 (if (= 1 (swap! turn inc))
                   (response/make-response
                     :text nil
                     :tool-calls [{:id "s1" :name "search_tools"
                                   :args {:query "量子隧穿"}}])
                   (response/make-response :text "没有这个能力" :tool-calls nil)))}
          store (memory/in-memory-store)
          k (build-kernel-with-search svc store)
          result (react/invoke k store
                               [{:role :user :content "帮我算量子隧穿"}]
                               {:context (ctx/create)})]
      (is (= :completed (:status result)))
      (is (nil? (ctx/get-var (:tool-context result) ts/discovered-slot))
          "未命中不应在 context 里留下空集合")
      (is (clojure.string/includes?
            (:result (first (:tool-calls-made result))) "未检索到")))))
