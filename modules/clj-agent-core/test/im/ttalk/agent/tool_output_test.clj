(ns im.ttalk.agent.tool-output-test
  "R-02 工具结果截断 + 全文另存（designs/runtime-borrow.md §2）+ tokens 粗估。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.filter.tool-output :as to]
            [im.ttalk.agent.tokens :as tokens]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftest estimate-test
  (testing "ASCII 4 字符 1 token，CJK 每字 1 token"
    (is (= 1 (tokens/estimate-text "abcd")))
    (is (= 2 (tokens/estimate-text "abcde")))
    (is (= 3 (tokens/estimate-text "你好吗")))
    (is (= 0 (tokens/estimate-text nil))))
  (testing "图片按固定值，不看 base64 多长"
    (is (= tokens/image-tokens
           (tokens/estimate-content [{:type :file :media-type "image/png" :data (apply str (repeat 100000 "A"))}]))))
  (testing "消息含工具调用与开销"
    (is (> (tokens/estimate-message {:role :assistant :tool-calls [{:id "1" :name "x" :args {:a 1}}]})
           tokens/message-overhead))))

(deftest truncate-middle-test
  (let [s (str (apply str (repeat 100 "a")) "MIDDLE" (apply str (repeat 100 "z")))]
    (testing "没超原样"
      (is (= "abc" (to/truncate-middle "abc" 10))))
    (testing "结果连同标记不超预算，头尾都在"
      (let [r (to/truncate-middle s 60)]
        (is (<= (count r) 60))
        (is (str/starts-with? r "aaaa"))
        (is (str/ends-with? r "zzzz"))
        (is (str/includes? r "chars truncated"))))
    (testing "幂等：再截一次不变"
      (let [r (to/truncate-middle s 60)]
        (is (= r (to/truncate-middle r 60)))))
    (testing "预算连标记都放不下 → 只留前缀"
      (is (= "aaaaa" (to/truncate-middle s 5))))
    (testing "不劈代理对"
      (let [emoji (apply str (repeat 50 "😀"))
            r (to/truncate-middle emoji 41)]
        (is (<= (count r) 41))
        (is (re-matches #"(😀)*…\d+ chars truncated…(😀)*" r))))
    (testing "按 token 预算"
      (let [r (to/truncate-middle-tokens s 20)]
        (is (<= (tokens/estimate-text r) 20))
        (is (str/includes? r "truncated"))))))

(deftool big-tool "大输出" [[n :int "长度"]]
  (apply str (repeat n "x")))

(deftool small-tool "小输出" [] "ok")

(defn- client [& filters]
  (cc/build-chat-client {:chat-model {:chat-fn (fn [_ _] nil)}
                         :tools [#'big-tool #'small-tool]
                         :filters (vec filters)}))

(deftest tool-output-filter-test
  (testing "超预算 → 警告头 + 中段截断；小结果不动"
    (let [c (client (to/tool-output-filter :max-chars 100))
          r (:value (cc/invoke-tool c :big-tool {:n 5000} {}))]
      (is (str/starts-with? r "[警告：输出过长已截断"))
      (is (< (count r) 200))
      (is (= "ok" (:value (cc/invoke-tool c :small-tool {} {}))))))
  (testing "spill 收到全文与 call-id，位置进结果"
    (let [seen (atom nil)
          c (client (to/tool-output-filter :max-chars 100
                                           :spill (fn [m] (reset! seen m) "/tmp/out-1.txt")))
          r (:value (cc/invoke-tool c :big-tool {:n 5000} {:tool/call-id "c9"}))]
      (is (= 5000 (count (:content @seen))))
      (is (= "c9" (:call-id @seen)))
      (is (= "big-tool" (:tool-name @seen)))
      (is (str/includes? r "完整输出已保存到：/tmp/out-1.txt"))))
  (testing "spill 抛异常不影响截断"
    (let [c (client (to/tool-output-filter :max-chars 100 :spill (fn [_] (throw (ex-info "boom" {})))))
          r (:value (cc/invoke-tool c :big-tool {:n 5000} {}))]
      (is (str/includes? r "chars truncated"))
      (is (not (str/includes? r "完整输出已保存")))))
  (testing "per-tool 覆盖与 exclude"
    (let [c (client (to/tool-output-filter :max-chars 100 :per-tool {:big-tool {:max-chars 10000}}))]
      (is (= 5000 (count (:value (cc/invoke-tool c :big-tool {:n 5000} {}))))))
    (let [c (client (to/tool-output-filter :max-chars 100 :exclude #{"big-tool"}))]
      (is (= 5000 (count (:value (cc/invoke-tool c :big-tool {:n 5000} {})))))))
  (testing "缺省按 10000 token"
    (let [c (client (to/tool-output-filter))]
      (is (= 30000 (count (:value (cc/invoke-tool c :big-tool {:n 30000} {})))))
      (is (str/includes? (:value (cc/invoke-tool c :big-tool {:n 50000} {})) "truncated")))))
