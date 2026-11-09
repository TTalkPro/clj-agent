(ns im.ttalk.agent.runtime-filters-test
  "designs/runtime-borrow.md 的 core 侧 filter：R-03 tool-guard（单工具层面）、
   R-09 completion-gate、R-10 instructions、R-12 normalize、context 框架槽。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.filter.instructions :as ins]
            [im.ttalk.agent.filter.normalize :as nz]
            [im.ttalk.agent.filter.tool-guard :as tg]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.tool :refer [deftool]]))

;;; ------------------------------------------------------------ context 框架槽

(deftest framework-slot-test
  (testing ":tool-guard/state 两层 merge：同批不同格互不覆盖、无冲突告警"
    (let [{:keys [context conflicts]}
          (ctx/apply-writes {:tool-guard/state {:repeats {"a" 1}}}
                            [{:tool-guard/state {:repeats {"b" 1} :fails {"t" 1}}}
                             {:tool-guard/state {:repeats {"c" 2}}}]
                            nil)]
      (is (= {:repeats {"a" 1 "b" 1 "c" 2} :fails {"t" 1}} (:tool-guard/state context)))
      (is (empty? conflicts))))
  (testing "框架键登记"
    (is (every? ctx/framework-keys [:tool-guard/state :loop/stop :loop/notes]))))

;;; ------------------------------------------------------------ R-03 tool-guard

(deftool flaky "总失败" [[x :int "x"]] (throw (ex-info (str "坏了 " x) {})))
(deftool fine "总成功" [] "ok")

(defn- guard-client [& opts]
  (cc/build-chat-client {:chat-model {:chat-fn (fn [_ _] nil)}
                         :tools [#'flaky #'fine]
                         :filters [(apply tg/tool-guard-filter opts)]}))

(defn- call
  "调一次工具并按屏障语义把 :writes 折进 context。"
  [c ctx tool args]
  (let [r (cc/invoke-tool c tool args ctx)]
    [r (:context (ctx/apply-writes ctx (keep identity [(:writes r)]) nil))]))

(deftest repeat-limit-test
  (let [c (guard-client :disable-threshold nil)
        [r1 c1] (call c {} :flaky {:x 1})
        [r2 c2] (call c c1 :flaky {:x 1})
        [r3 c3] (call c c2 :flaky {:x 1})
        [r4 c4] (call c c3 :flaky {:x 1})]
    (testing "前两次原样"
      (is (str/includes? (:value r1) "坏了 1"))
      (is (not (str/includes? (:value r2) "不要再原样重试"))))
    (testing "第 3 次换成提醒 + 原报错"
      (is (str/includes? (:value r3) "第 3 次"))
      (is (str/includes? (:value r3) "不要再原样重试"))
      (is (str/includes? (:value r3) "坏了 1")))
    (testing "第 4 次停回合（:loop/stop）"
      (is (str/includes? (:value r4) "到此为止"))
      (is (= :tool-guard (get-in c4 [:loop/stop :by]))))
    (testing "参数键序不同指纹相同；不同参数另算"
      (is (= (tg/fingerprint "t" {:a 1 :b 2}) (tg/fingerprint "t" {:b 2 :a 1})))
      (let [[r _] (call c c2 :flaky {:x 2})]
        (is (not (str/includes? (:value r) "原样重试")))))))

(deftest disable-threshold-test
  (let [calls (atom 0)
        c (cc/build-chat-client {:chat-model {:chat-fn (fn [_ _] nil)}
                                 :tools [{:name "svc" :description "后端" :input_schema {:type "object"}
                                          :handler (fn [args _] (swap! calls inc)
                                                     (if (:ok args) "fine" (throw (ex-info "503" {}))))}]
                                 :filters [(tg/tool-guard-filter :repeat-limit 0)]})
        [_ c1] (call c {} :svc {:i 1})
        [_ c2] (call c c1 :svc {:i 2})
        [ok c2'] (call c c1 :svc {:ok true})
        [_ c3] (call c c2 :svc {:i 3})
        [r4 _] (call c c3 :svc {:i 4})]
    (testing "成功清零"
      (is (= "fine" (:value ok)))
      (is (= 0 (get-in c2' [:tool-guard/state :fails "svc"]))))
    (testing "连续 3 次失败 → 门禁拒绝，不执行"
      (is (str/starts-with? (:value r4) "已拒绝执行"))
      (is (str/includes? (:value r4) "本回合已停用"))
      (is (= 4 @calls)))))

;;; ------------------------------------------------------------ R-09 completion gate

(defn- turn-run [f results]
  (let [n (atom -1) seen (atom [])
        chain (flt/build-chain [(:turn f)]
                               (fn [req] (swap! seen conj (:messages req))
                                 (nth results (min (swap! n inc) (dec (count results))))))]
    [(chain {:messages [(msg/user "q")]}) @seen]))

(defn- done [t] {:status :completed :response (resp/make-response :text t)})

(deftest completion-gate-test
  (testing "多闸门顺序：第一个非 accept 生效；continue 带一句话重入"
    (let [f (flt/completion-gate-filter
             [(constantly :accept)
              (fn [{:keys [result]}]
                (when (= "bad" (get-in result [:response :text])) {:continue "再查一下"}))])
          [r seen] (turn-run f [(done "bad") (done "good")])]
      (is (= "good" (get-in r [:response :text])))
      (is (= "再查一下" (:content (first (second seen)))))))
  (testing "stop → :stopped-by + 理由"
    (let [f (flt/completion-gate-filter [(constantly {:stop "预算用完"})])
          [r _] (turn-run f [(done "x")])]
      (is (= :completion-gate (:stopped-by r)))
      (is (= "预算用完" (:stop-reason r)))))
  (testing "耗尽原样返回；gate-active? 从第二次起为真"
    (let [actives (atom [])
          f (flt/completion-gate-filter [(fn [{:keys [gate-active?]}] (swap! actives conj gate-active?)
                                           {:continue "again"})]
                                        :max-continues 2)
          [r seen] (turn-run f [(done "x")])]
      (is (= :completed (:status r)))
      (is (= 3 (count seen)))
      (is (= [false true true] @actives))))
  (testing "暂停透传不问闸门"
    (let [asked (atom false)
          f (flt/completion-gate-filter [(fn [_] (reset! asked true) :stop)])
          [r _] (turn-run f [{:status :paused}])]
      (is (= :paused (:status r)))
      (is (false? @asked))))
  (testing "非法返回值抛"
    (is (thrown? clojure.lang.ExceptionInfo
                 (turn-run (flt/completion-gate-filter [(constantly :maybe)]) [(done "x")])))))

;;; ------------------------------------------------------------ R-10 instructions

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "ins" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest hierarchical-source-test
  (let [root (tmp-dir)
        sub (io/file root "a" "b")]
    (.mkdirs sub)
    (spit (io/file root "AGENTS.md") "根指令")
    (spit (io/file root "a" "OVERRIDE.md") "a 层覆盖")
    (spit (io/file root "a" "AGENTS.md") "a 层（不该选）")
    (spit (io/file sub "AGENTS.md") "b 层指令")
    (testing "根 → cwd，每层首个候选"
      (let [r ((ins/hierarchical-file-source {:root (str root) :cwd "a/b"
                                              :names ["OVERRIDE.md" "AGENTS.md"]}))]
        (is (= "根指令\n\na 层覆盖\n\nb 层指令" (:text r)))
        (is (= 3 (count (:origins r))))
        (is (false? (:truncated? r)))))
    (testing "字节预算截断不劈中文"
      (let [r ((ins/hierarchical-file-source {:root (str root) :cwd "a/b" :max-bytes 11}))]
        (is (:truncated? r))
        (is (= "根指令" (:text r)))))
    (testing "cwd 越界抛"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ins/hierarchical-file-source {:root (str sub) :cwd (str root)}))))
    (testing "filter：接到 system prompt 后面，消息不变"
      (let [seen (atom nil)
            c (cc/build-chat-client
               {:chat-model {:chat-fn (fn [ms opts] (reset! seen [ms opts]) (resp/make-response :text "ok"))}
                :filters [(ins/instructions-filter (ins/hierarchical-file-source {:root (str root)}))]})]
        (cc/invoke-chat c [(msg/user "hi")] {:system-prompt "人设"})
        (is (= "人设\n\n根指令" (:system-prompt (second @seen))))
        (is (= [(msg/user "hi")] (vec (first @seen))))))))

;;; ------------------------------------------------------------ R-12 normalize

(deftest normalize-test
  (let [call (msg/assistant-tool-calls [(msg/tool-call "c1" "t" {}) (msg/tool-call "c2" "t" {})])]
    (testing "删孤儿结果"
      (is (= [(msg/user "q") (msg/assistant "a")]
             (nz/normalize-messages [(msg/user "q") (msg/tool-result "zz" "t" "孤儿") (msg/assistant "a")]))))
    (testing "补悬空调用（接在结果串尾），末条 assistant 的调用不补"
      (let [out (nz/normalize-messages [(msg/user "q") call (msg/tool-result "c1" "t" "r1") (msg/user "next")])]
        (is (= ["c1" "c2"] (mapv :tool-call-id (filter msg/tool? out))))
        (is (= nz/missing-result-text (:content (nth out 3)))))
      (is (= 2 (count (nz/normalize-messages [(msg/user "q") call])))))
    (testing "媒体占位"
      (let [m {:role :user :content [{:type :text :text "看图"} {:type :file :media-type "image/png" :url "u"}]}
            out (nz/normalize-messages [m] {:images? false})]
        (is (= "[图片已省略：模型不支持图片输入]" (get-in out [0 :content 1 :text])))))
    (testing "只裁本轮之前的思考块"
      (let [old (msg/with-blocks (msg/assistant "旧") {:format :x :data [1]})
            cur (msg/with-blocks (msg/assistant-tool-calls [(msg/tool-call "c9" "t" {})]) {:format :x :data [2]})
            out (nz/normalize-messages [(msg/user "q1") old (msg/user "q2") cur (msg/tool-result "c9" "t" "r")]
                                       {:thinking :before-current-turn})]
        (is (nil? (:blocks (nth out 1))))
        (is (some? (:blocks (nth out 3))))))
    (testing "无改动原样、幂等"
      (let [ms [(msg/user "q") call (msg/tool-result "c1" "t" "r1") (msg/tool-result "c2" "t" "r2")]]
        (is (= ms (nz/normalize-messages ms)))
        (is (= (nz/normalize-messages ms) (nz/normalize-messages (nz/normalize-messages ms))))))))
