(ns im.ttalk.agent.agui.genui-test
  "Open Generative UI 插件。

   断言逐条对着 CopilotKit 的
   `packages/runtime/src/v2/runtime/__tests__/open-generative-ui-middleware.e2e.test.ts`
   写——patch 的 `op` / `path` / `value` 形状猜错了前端**不会报错**，只会静默
   少渲染一块（与 codec 那份测试同源的理由）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.genui :as genui]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as support]))

;;; ============================================================
;;; 参数解析（喂什么形状的 chunk 都得一样）
;;; ============================================================

(defn- parse
  "喂若干 chunk，返回 `[params 事件]`。"
  [& chunks]
  (let [out (atom [])
        p (genui/args-parser "tc-1" #(swap! out conj %))]
    (doseq [c chunks] ((:write! p) c))
    [((:params p)) @out]))

(defn- patches
  "所有 delta 的 patch 操作，摊平。"
  [events]
  (into [] (mapcat :patch) (filter #(= :activity/delta (:type %)) events)))

(defn- op-for [events path]
  (first (filter #(= path (:path %)) (patches events))))

(deftest parse-whole-object-test
  (let [[params _] (parse (str "{\"initialHeight\":400,\"html\":\"<div>hi</div>\","
                               "\"jsFunctions\":\"function foo(){}\","
                               "\"jsExpressions\":[\"expr1\",\"expr2\"]}"))]
    (is (= {:initialHeight 400
            :html "<div>hi</div>"
            :jsFunctions "function foo(){}"
            :jsExpressions ["expr1" "expr2"]}
           params))))

(deftest parse-is-incremental-test
  (testing "逐字符喂：结果与一口喂完完全一样"
    (let [json "{\"initialHeight\":300,\"html\":\"<p>hello</p>\"}"
          [params _] (apply parse (map str (seq json)))]
      (is (= 300 (:initialHeight params)))
      (is (= "<p>hello</p>" (:html params)))))
  (testing "切在键名与值中间也不散架"
    (let [[params _] (parse "{\"ini" "tialHeight\":" "25" "0,\"ht" "ml\":\"<div" ">test</div>\"}")]
      (is (= 250 (:initialHeight params)))
      (is (= "<div>test</div>" (:html params)))))
  (testing "数组一个个到"
    (let [out (atom [])
          p (genui/args-parser "tc-1" #(swap! out conj %))]
      ((:write! p) "{\"jsExpressions\":")
      (is (nil? (:jsExpressions ((:params p)))) "数组还没开始")
      ((:write! p) "[\"alert(1)\",")
      (is (= ["alert(1)"] (:jsExpressions ((:params p)))))
      ((:write! p) "\"console.log(2)\",")
      (is (= ["alert(1)" "console.log(2)"] (:jsExpressions ((:params p)))))
      ((:write! p) "\"document.title\"]}")
      (is (= ["alert(1)" "console.log(2)" "document.title"] (:jsExpressions ((:params p))))))))

(deftest parse-ignores-unknown-keys-test
  (let [[params _] (parse "{\"initialHeight\":100,\"unknown_field\":\"ignored\",\"html\":\"ok\"}")]
    (is (= {:initialHeight 100 :html "ok"} params) "模型多给的键直接忽略")))

(deftest parse-unescapes-test
  (let [[params _] (parse "{\"html\":\"a\\\"b\\nc\\u4e2d\"}")]
    (is (= "a\"b\nc中" (:html params)) "转义与 \\uXXXX 都要解开")))

;;; ============================================================
;;; activity 事件的形状与顺序
;;; ============================================================

(deftest snapshot-comes-first-test
  (testing "initialHeight 第一个到：snapshot 带着它出去"
    (let [[_ out] (parse "{\"initialHeight\":400}")]
      (is (= 1 (count out)))
      (is (= {:type :activity/snapshot
              :message-id "tc-1-activity"
              :activity-type "open-generative-ui"
              :content {:generating true :initialHeight 400}}
             (first out)))))
  (testing "key 顺序是模型定的：css 先到时 snapshot 仍必须是第一条"
    ;; 前端把 delta 打在 messageId 对应的 activity 消息上，消息还没建就悄悄丢弃
    (let [[_ out] (parse "{\"css\":\"body{margin:0}\",\"html\":\"<div/>\",\"initialHeight\":300}")]
      (is (= :activity/snapshot (:type (first out))))
      (is (= 1 (count (filter #(= :activity/snapshot (:type %)) out))) "只发一条")
      (is (= {:op "add" :path "/initialHeight" :value 300} (op-for out "/initialHeight"))
          "迟到的高度改发 delta")))
  (testing "压根没给 initialHeight：snapshot 照发，只是不带这个键"
    (let [[_ out] (parse "{\"css\":\"body{margin:0}\",\"html\":\"<p>hi</p>\"}")]
      (is (= {:generating true} (:content (first out)))))))

(deftest html-is-an-array-of-chunks-test
  (testing "先建数组再逐块追加——前端 join 起来才有「一点点长出来」"
    (let [out (atom [])
          p (genui/args-parser "tc-1" #(swap! out conj %))]
      ((:write! p) "{\"initialHeight\":200,")
      (reset! out [])
      ((:write! p) "\"html\":\"chunk1")
      (is (= [{:op "add" :path "/html" :value []}
              {:op "add" :path "/html/-" :value "chunk1"}]
             (patches @out)))
      (reset! out [])
      ((:write! p) "chunk2")
      (is (= [{:op "add" :path "/html/-" :value "chunk2"}] (patches @out))
          "字符串还没收口就已经推出去了——这是流式的关键")
      (reset! out [])
      ((:write! p) "\",")
      (is (= [{:op "add" :path "/htmlComplete" :value true}] (patches @out)))
      (is (= "chunk1chunk2" (:html ((:params p))))))))

(deftest value-less-patch-is-skipped-test
  (testing "模型给 null 时不能发没有 value 的 add——整批 patch 会被前端判非法丢掉"
    (let [[_ out] (parse "{\"initialHeight\":200,\"jsFunctions\":null}")]
      (is (every? #(contains? % :value) (patches out)))
      (is (nil? (op-for out "/jsFunctions")) "值那条整条跳过")
      (is (= {:op "add" :path "/jsFunctionsComplete" :value true}
             (op-for out "/jsFunctionsComplete"))
          "但完成标记照发"))))

(deftest patches-rebuild-the-content-test
  (testing "按顺序应用 snapshot + patch，能还原出完整内容"
    ;; CopilotKit 的 CPK-7634 回归：模型不按顺序给参数时，snapshot 之前的 delta
    ;; 全被丢掉，聊天里只剩一个空灰框
    (let [[_ out] (parse "{\"css\":\"body{margin:0}\","
                         "\"html\":\"<div>calc</div>\","
                         "\"jsFunctions\":\"function f(){}\","
                         "\"initialHeight\":760,"
                         "\"jsExpressions\":[\"f()\"],"
                         "\"placeholderMessages\":[\"Building…\"]}")
          content (reduce (fn [acc ev]
                            (case (:type ev)
                              :activity/snapshot (:content ev)
                              :activity/delta
                              (reduce (fn [c {:keys [path value]}]
                                        (if (.endsWith ^String path "/-")
                                          (update c (keyword (subs path 1 (- (count path) 2)))
                                                  (fnil conj []) value)
                                          (assoc c (keyword (subs path 1)) value)))
                                      acc (:patch ev))
                              acc))
                          {} out)]
      (is (= :activity/snapshot (:type (first out))))
      (is (= "<div>calc</div>" (apply str (:html content))))
      (is (true? (:htmlComplete content)))
      (is (= "body{margin:0}" (:css content)))
      (is (true? (:cssComplete content)))
      (is (= "function f(){}" (:jsFunctions content)))
      (is (= 760 (:initialHeight content)))
      (is (= ["f()"] (:jsExpressions content)))
      (is (= ["Building…"] (:placeholderMessages content))))))

;;; ============================================================
;;; 事件流插件
;;; ============================================================

(defn- run-through
  "把一串中立事件过一遍 transform，返回摊平后的产出。"
  [events]
  (let [t ((genui/event-transform) {:run-id "r1" :conversation-id "c1"})]
    (into [] (mapcat t) events)))

(def ^:private genui-call
  [{:type :tool/started :tool-call-id "tc-1" :name genui/tool-name}
   {:type :tool/args :tool-call-id "tc-1" :args {:initialHeight 300 :html "<p>hi</p>"}}
   {:type :tool/ended :tool-call-id "tc-1"}])

(deftest transform-holds-tool-call-until-first-activity-test
  (let [out (run-through genui-call)
        types (mapv :type out)]
    (testing "snapshot 排在工具卡片之前——否则前端先闪一张卡片再出 UI"
      (is (< (.indexOf types :activity/snapshot) (.indexOf types :tool/started))))
    (testing "工具调用本身一条不少，顺序也没乱"
      (is (= [:tool/started :tool/args :tool/ended]
             (filterv #{:tool/started :tool/args :tool/ended} types))))
    (testing "生成结束时补一条 generating:false，前端据此收掉占位"
      (is (= {:op "add" :path "/generating" :value false}
             (last (patches out))))
      (is (= :activity/delta (:type (last (butlast out))))
          "它在 :tool/ended 之前"))
    (is (= 1 (count (filter #(= :activity/snapshot (:type %)) out))))))

(deftest transform-passes-other-tools-through-test
  (let [out (run-through [{:type :run/started}
                          {:type :tool/started :tool-call-id "x" :name "get-weather"}
                          {:type :tool/args :tool-call-id "x" :args {:city "北京"}}
                          {:type :tool/ended :tool-call-id "x"}
                          {:type :tool/result :tool-call-id "x" :content "晴"}])]
    (is (= [:run/started :tool/started :tool/args :tool/ended :tool/result]
           (mapv :type out))
        "别的工具一个字都不动")))

(deftest transform-never-swallows-held-events-test
  (testing "参数是空的（一条 activity 都解析不出来）：扣住的必须在 tool/ended 放出来"
    (let [out (run-through [{:type :tool/started :tool-call-id "tc-1" :name genui/tool-name}
                            {:type :tool/args :tool-call-id "tc-1" :args {}}
                            {:type :tool/ended :tool-call-id "tc-1"}])]
      (is (= [:tool/started :tool/args :tool/ended] (mapv :type out)))
      (is (empty? (filter #(= :activity/delta (:type %)) out))
          "没有 activity 消息就不发 generating:false——那条 delta 会被前端丢掉"))))

(deftest args-order-is-normalized-test
  (testing "模型的原始 key 顺序在 provider 解析 JSON 时就没了，按约定重排"
    (is (= "{\"initialHeight\":100,\"css\":\"x\",\"html\":\"<p/>\",\"jsExpressions\":[\"a()\"],\"zzz\":1}"
           (genui/args->json {:jsExpressions ["a()"] :html "<p/>" :zzz 1
                              :initialHeight 100 :css "x"})))
    (is (= "{}" (genui/args->json nil)))))

;;; ============================================================
;;; codec：中立 activity 事件 → AG-UI
;;; ============================================================

(deftest codec-activity-events-test
  (let [snap (codec/->agui {:type :activity/snapshot :message-id "tc-1-activity"
                            :activity-type genui/activity-type
                            :content {:generating true :initialHeight 300}})
        delta (codec/->agui {:type :activity/delta :message-id "tc-1-activity"
                             :activity-type genui/activity-type
                             :patch [{:op "add" :path "/html" :value []}]})]
    (is (= {:type "ACTIVITY_SNAPSHOT" :messageId "tc-1-activity"
            :activityType "open-generative-ui"
            :content {:generating true :initialHeight 300}}
           snap))
    (is (= {:type "ACTIVITY_DELTA" :messageId "tc-1-activity"
            :activityType "open-generative-ui"
            :patch [{:op "add" :path "/html" :value []}]}
           delta))
    (is (not (codec/terminal? snap)) "activity 不是终态")))

(deftest info-advertises-open-generative-ui-test
  (is (false? (:openGenerativeUIEnabled (codec/run-info ["default"])))
      "不装插件就不报——与「不谎报能力位」同源")
  (is (true? (:openGenerativeUIEnabled
              (codec/run-info ["default"] {:open-generative-ui? true})))))

;;; ============================================================
;;; 端到端：装上插件跑一个 run
;;; ============================================================

(deftest end-to-end-through-runtime-test
  (testing "工具 + transform 装上之后，一个 run 里 activity 事件与工具事件都在"
    (let [spec (genui/with-tool
                 {:provider (support/provider
                             [{:text "" :tool-calls [{:id "tc-9" :name genui/tool-name
                                                      :args {:initialHeight 240
                                                             :css "body{margin:0}"
                                                             :html "<h1>hi</h1>"}}]}
                              {:text "画好了"}])
                  :model "mock"
                  :tools []})
          r (support/runtime spec {:event-transform (genui/event-transform)})
          c (support/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "画个卡片")
      (is (support/wait-for #(support/terminal-event ((:events c)))) "跑完了")
      (let [evs ((:events c))
            types (mapv :type evs)]
        (is (= :run/finished (:type (support/terminal-event evs))))
        (is (some #{:activity/snapshot} types) "snapshot 进了事件流")
        (is (< (.indexOf types :activity/snapshot) (.indexOf types :tool/started)))
        (is (= {:op "add" :path "/css" :value "body{margin:0}"} (op-for evs "/css")))
        (is (= {:op "add" :path "/html/-" :value "<h1>hi</h1>"} (op-for evs "/html/-")))
        (testing "seq 仍然单调无洞——插件插进来的事件各自取号"
          (let [seqs (mapv :seq evs)]
            (is (= seqs (range (first seqs) (inc (last seqs))))))))
      (rt/shutdown! r)))

  (testing "插件的工具确实进了发给模型的工具表"
    (let [spec (genui/with-tool {:tools [] :system-prompt "你是助手"})]
      (is (= genui/tool-name (:name (last (:tools spec)))))
      (is (re-find #"shadcn" (:system-prompt spec)) "设计规范追加进了 system prompt")
      (is (re-find #"你是助手" (:system-prompt spec)) "原来的 prompt 没被顶掉")
      (is (= "UI generated" ((:handler (last (:tools spec))) {} nil))
          "handler 只给模型一个回执——UI 是参数本身，不是它的返回值"))))
