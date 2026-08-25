(ns im.ttalk.agent.token-xform-test
  "Token 流变换链（:token-xform）测试

    覆盖设计文档 docs/token-stream-filter-design.md §6 的 7 个锚点：
    1→N flush / 异常不 flush / 组合顺序 / hold-release 两分支 /
    退化路径 / reasoning-token 透传 / 最终响应不被变换。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.model.response :as resp]))

;;; ============================================================
;;; 测试基建：假 stream 服务
;;; ============================================================

(defn- stream-service
  "假 service：stream-fn 依次 emit tokens 后返回 final-resp。
   mid-check（可选）在全部 emit 之后、返回之前调用（观测缓冲行为）。"
  [tokens final-resp & {:keys [mid-check]}]
  {:stream-fn (fn [_messages _opts on-token]
                (doseq [t tokens] (when on-token (on-token t)))
                (when mid-check (mid-check))
                final-resp)})

(defn- run-stream
  "构建带 filters 的 kernel 并跑一次 invoke-chat-stream。
   返回 {:sunk <sink 收到的向量> :out <链结果>}。"
  [service filters]
  (let [sunk (atom [])
        out (kernel/invoke-chat-stream
              (kernel/build-kernel {:service service :filters filters})
              [{:role :user :content "hi"}]
              {:on-token (fn [tok] (swap! sunk conj tok))})]
    {:sunk @sunk :out out}))

(def ^:private final-r (resp/make-response :text "你好世界" :tool-calls nil))

;;; ============================================================
;;; 锚点 1：1→N 与流末 flush
;;; ============================================================

(deftest partition-flush-test
  (testing "(partition-all 3)：7 个 token 进 → 3 批出，尾巴由 completion 冲出"
    (let [tokens (mapv #(hash-map :token (str %)) (range 7))
          {:keys [sunk]} (run-stream (stream-service tokens final-r)
                                     [{:name :batch :token-xform (partition-all 3)}])]
      (is (= 3 (count sunk)))
      (is (= [3 3 1] (mapv count sunk)))
      (is (= (map :token tokens) (map :token (apply concat sunk)))))))

;;; ============================================================
;;; 锚点 2：异常不 flush
;;; ============================================================

(deftest exception-no-flush-test
  (testing "stream-fn 抛异常：hold-release 缓冲丢弃，sink 一无所获"
    (let [sunk (atom [])
          service {:stream-fn (fn [_m _o on-token]
                                (on-token {:token "半"})
                                (on-token {:token "截"})
                                (throw (ex-info "网络断开" {})))}
          k (kernel/build-kernel
              {:service service
               :filters [(flt/hold-release-filter (constantly nil))]})]
      (is (thrown? Exception
            (kernel/invoke-chat-stream k [{:role :user :content "hi"}]
                                       {:on-token #(swap! sunk conj %)})))
      (is (= [] @sunk)))))

;;; ============================================================
;;; 锚点 3：组合顺序 = 注册顺序（靠前者先见原始 token）
;;; ============================================================

(deftest composition-order-test
  (testing "先注册的 xform 先作用于原始 token"
    (let [append (fn [s] (map #(if (:token %) (update % :token str s) %)))
          {:keys [sunk]} (run-stream
                           (stream-service [{:token "r"}] final-r)
                           [{:name :a :token-xform (append "a")}
                            {:name :b :token-xform (append "b")}])]
      (is (= ["rab"] (mapv :token sunk))))))

;;; ============================================================
;;; 锚点 4：hold-release 两分支
;;; ============================================================

(deftest hold-release-pass-test
  (testing "通过：流中一个不漏、完流前一个不放，完流后按原序放行"
    (let [sunk (atom [])
          tokens [{:token "你"} {:token "好"} {:token "吗"}]
          service (stream-service tokens final-r
                    ;; emit 全部完成、stream-fn 尚未返回：sink 必须还是空的
                    :mid-check #(is (= [] @sunk) "完流前不得外泄"))
          k (kernel/build-kernel
              {:service service
               :filters [(flt/hold-release-filter
                           (fn [text] (is (= "你好吗" text)) nil))]})]
      (kernel/invoke-chat-stream k [{:role :user :content "hi"}]
                                 {:on-token #(swap! sunk conj %)})
      (is (= tokens @sunk)))))

(deftest hold-release-reject-test
  (testing "不通过：只收到一个替换 token"
    (let [{:keys [sunk]} (run-stream
                           (stream-service [{:token "机"} {:token "密"}] final-r)
                           [(flt/hold-release-filter
                              (fn [_text] "[内容未通过审查]"))])]
      (is (= [{:token "[内容未通过审查]"}] sunk)))))

;;; ============================================================
;;; 锚点 5：退化路径
;;; ============================================================

(deftest no-xform-passthrough-test
  (testing "无 :token-xform：sink 收到原始 token（与现状一致）"
    (let [tokens [{:token "a"} {:reasoning-token "想"} {:token "b"}]
          {:keys [sunk out]} (run-stream (stream-service tokens final-r) [])]
      (is (= tokens sunk))
      (is (= final-r (:response out))))))

(deftest sync-path-ignores-token-xform-test
  (testing "同步 invoke-chat 完全忽略 :token-xform"
    (let [k (kernel/build-kernel
              {:service {:chat-fn (fn [_m _o] final-r)}
               :filters [{:name :batch :token-xform (partition-all 3)}]})
          out (kernel/invoke-chat k [{:role :user :content "hi"}] {})]
      (is (= final-r (:response out))))))

;;; ============================================================
;;; 锚点 6：reasoning-token 透传（token-redact-filter）
;;; ============================================================

(deftest redact-preserves-reasoning-test
  (testing "redact 只改 :token，:reasoning-token 原样透传"
    (let [tokens [{:token "key=sk-abc123"} {:reasoning-token "sk-abc123"}]
          {:keys [sunk]} (run-stream
                           (stream-service tokens final-r)
                           [(flt/token-redact-filter #"sk-\w+" "[REDACTED]")])]
      (is (= [{:token "key=[REDACTED]"} {:reasoning-token "sk-abc123"}] sunk)))))

;;; ============================================================
;;; 锚点 7：最终 :response 不被变换（硬边界 §3.1）
;;; ============================================================

(deftest response-untouched-test
  (testing "hold-release 吞掉全部交付，:response 仍是原始完整答案"
    (let [{:keys [sunk out]} (run-stream
                               (stream-service [{:token "秘"} {:token "密"}] final-r)
                               [(flt/hold-release-filter (constantly "×"))])]
      (is (= [{:token "×"}] sunk))
      (is (= final-r (:response out)) "memory/turn 看到的是原文"))))

;;; ============================================================
;;; 补充：create-filter 支持 :token-xform
;;; ============================================================

(deftest create-filter-token-xform-test
  (let [f (flt/create-filter :my-xform :token-xform (map identity))]
    (is (= :my-xform (:name f)))
    (is (fn? (:token-xform f)))))
