(ns im.ttalk.agent.agui.a2ui-test
  "A2UI 插件。op 的**顺序**与 `replace` 语义是这条路的全部要害：
   少一条 `createSurface` 前端就报「surface 不存在」，顺序反了同理，
   `replace` 丢了则新旧两块面叠在一起。"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.a2ui :as a2ui]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as support]))

(def ^:private args
  {:surfaceId "s1"
   :components [{:id "root" :component "Column" :children ["t"]}
                {:id "t" :component "Text" :text "hi"}]
   :data {:items [{:name "A"}]}})

(deftest surface-ops-order-test
  (testing "三条 op：建面 → 给组件 → 灌数据，顺序是协议要求的"
    (let [ops (a2ui/surface-ops args a2ui/basic-catalog-id)]
      (is (= ["createSurface" "updateComponents" "updateDataModel"]
             (mapv #(name (first (keys (dissoc % :version)))) ops)))
      (is (every? #(= "v0.9" (:version %)) ops))
      (is (= a2ui/basic-catalog-id (get-in ops [0 :createSurface :catalogId]))
          "catalogId 由宿主给——模型编不出前端没注册的 catalog")
      (is (= 2 (count (get-in ops [1 :updateComponents :components]))))
      (is (= {:items [{:name "A"}]} (get-in ops [2 :updateDataModel :value])))))

  (testing "没有 data 就不发第三条（发一条空的会把数据模型清掉）"
    (is (= 2 (count (a2ui/surface-ops (dissoc args :data) a2ui/basic-catalog-id)))))

  (testing "surfaceId 缺省不崩"
    (is (= "default" (get-in (a2ui/surface-ops {:components []} "c") [0 :createSurface :surfaceId])))))

(deftest surface-event-shape-test
  (let [ev (a2ui/surface-event "tc-1" args a2ui/basic-catalog-id)
        agui (codec/->agui ev)]
    (is (= "a2ui-surface-tc-1" (:message-id ev)) "messageId 与 tool-call 挂钩")
    (is (true? (:replace ev)))
    (is (= "ACTIVITY_SNAPSHOT" (:type agui)))
    (is (= "a2ui-surface" (:activityType agui)))
    (is (true? (:replace agui)) "replace 丢了会让新旧两块面叠着")
    (testing "op 数组挂在字符串键上——它要原样出现在 JSON 里"
      (is (contains? (:content agui) "a2ui_operations"))
      (is (re-find #"\"a2ui_operations\"" (json/generate-string agui))))
    (is (not (codec/terminal? agui)))))

(deftest transform-test
  (let [t ((a2ui/event-transform) {})
        out (into [] (mapcat t)
                  [{:type :tool/started :tool-call-id "tc-9" :name a2ui/tool-name}
                   {:type :tool/args :tool-call-id "tc-9" :args {"surfaceId" "s2"
                                                                "components" [{"id" "root"}]}}
                   {:type :tool/ended :tool-call-id "tc-9"}])]
    (testing "快照在 args 事件之前；工具事件一条不少（**不扣住** tool/started）"
      (is (= [:tool/started :activity/snapshot :tool/args :tool/ended] (mapv :type out))))
    (testing "字符串键的参数也认（谁解析的 JSON 决定键的类型）"
      (is (= "s2" (get-in (second out) [:content "a2ui_operations" 0 :createSurface :surfaceId])))))

  (testing "别的工具原样透传"
    (let [t ((a2ui/event-transform) {})]
      (is (= [:tool/args]
             (mapv :type (t {:type :tool/args :tool-call-id "x" :args {}}))))))

  (testing "自定义工具名"
    (let [t ((a2ui/event-transform {:tool-name "draw"}) {})
          out (into [] (mapcat t) [{:type :tool/started :tool-call-id "c" :name "draw"}
                                   {:type :tool/args :tool-call-id "c" :args {:components []}}])]
      (is (some #(= :activity/snapshot (:type %)) out)))))

(deftest user-action-flows-back-test
  (testing "措辞与上游 formatUserActionResult 一致"
    (is (= "User performed action \"book\" on surface \"s1\" (component: btn). Context: {\"id\":7}"
           (a2ui/user-action-message {:name "book" :surfaceId "s1"
                                      :sourceComponentId "btn" :context {:id 7}})))
    (is (= "User performed action \"unknown_action\" on surface \"unknown_surface\". Context: {}"
           (a2ui/user-action-message {}))))

  (testing "input-transform：只点了按钮没打字时，动作就是本轮输入"
    (let [f (a2ui/input-transform)
          body {:forwardedProps {:a2uiAction {:userAction {:name "book" :surfaceId "s1"}}}}]
      (is (re-find #"action \"book\"" (:message (f {:message nil} body))))
      (testing "既打了字又点了按钮：两个都要，别丢掉用户说的话"
        (let [m (:message (f {:message "帮我订"} body))]
          (is (re-find #"帮我订" m))
          (is (re-find #"action \"book\"" m))))))

  (testing "没有动作就什么都不改"
    (is (= {:message "你好"} ((a2ui/input-transform) {:message "你好"} {})))))

(deftest catalog-test
  (testing "v0.9 基础 catalog 的 18 个组件（摘自 @a2ui/web_core 的 catalog.json）"
    (is (= 18 (count a2ui/basic-components)))
    (is (every? a2ui/basic-components ["Text" "Column" "Row" "Card" "Button" "List"]))
    (is (= ["text"] (get-in a2ui/basic-components ["Text" :required])))
    (is (contains? (set (get-in a2ui/basic-components ["Text" :properties "variant" :enum])) "h2")))

  (testing "with-tool：工具进工具表，用法与 catalog 进 system prompt"
    (let [spec (a2ui/with-tool {:tools [] :system-prompt "你是助手"})
          tool (last (:tools spec))
          prompt (:system-prompt spec)]
      (is (= a2ui/tool-name (:name tool)))
      (is (= ["surfaceId" "components"] (get-in tool [:parameters :required])))
      (is (nil? (get-in tool [:parameters :properties :catalogId]))
          "catalog 由宿主定，不给模型这个参数")
      (is (re-find #"你是助手" prompt) "原来的 prompt 没被顶掉")
      (is (re-find #"How to call render_a2ui" prompt) "用法提示词")
      (is (re-find #"Available Components" prompt) "catalog 抬头")
      (is (re-find #"\"Column\"" prompt) "catalog 本体（模型的词汇表）")
      (is (string? ((:handler tool) {:surfaceId "s1"} nil)))))

  (testing "换成自己的组件库"
    (let [spec (a2ui/with-tool {} {:catalog {:catalogId "mine" :components {"Chart" {}}}})]
      (is (re-find #"\"Chart\"" (:system-prompt spec)))
      (is (not (re-find #"DateTimeInput" (:system-prompt spec)))))))

(deftest end-to-end-through-runtime-test
  (testing "装上插件跑一个 run：surface 快照进事件流，seq 仍无洞"
    (let [spec (a2ui/with-tool
                 {:provider (support/provider
                             [{:text "" :tool-calls [{:id "tc-a" :name a2ui/tool-name
                                                      :args {:surfaceId "dash"
                                                             :components [{:id "root"
                                                                           :component "Card"}]}}]}
                              {:text "画好了"}])
                  :model "mock"
                  :tools []})
          r (support/runtime spec {:event-transform (a2ui/event-transform)})
          c (support/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "画个面板")
      (is (support/wait-for #(support/terminal-event ((:events c)))))
      (let [evs ((:events c))
            snap (first (filter #(= :activity/snapshot (:type %)) evs))]
        (is (= :run/finished (:type (support/terminal-event evs))))
        (is (some? snap) "surface 快照进了事件流")
        (is (= "dash" (get-in snap [:content "a2ui_operations" 0 :createSurface :surfaceId])))
        (let [seqs (mapv :seq evs)]
          (is (= seqs (range (first seqs) (inc (last seqs)))) "seq 单调无洞")))
      (rt/shutdown! r))))
