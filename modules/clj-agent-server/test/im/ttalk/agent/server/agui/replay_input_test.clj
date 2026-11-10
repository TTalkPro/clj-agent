(ns im.ttalk.agent.server.agui.replay-input-test
  "本模块自己补上的两块小东西：帧环（C# AguiReplay 的行为）与 RunAgentInput 的形状校验（C# RunAgentInput.Decode 的码）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.server.agui.input :as input]
            [im.ttalk.agent.server.agui.replay :as replay]
            [im.ttalk.agent.server.strings :as err]))

(set! *warn-on-reflection* true)

(defn- b [s] (.getBytes ^String s "UTF-8"))
(defn- strs [frames] (mapv #(String. ^bytes % "UTF-8") frames))

(deftest replay-ring
  (testing "攒、读、收尾、读完即 done"
    (let [r (replay/replay {:max-bytes 10})]
      (replay/begin! r "t" 0)
      (is (= :running (replay/state r "t")))
      (is (true? (replay/append! r "t" [(b "abc")])))
      (let [c (replay/open-cursor r "t" 1)]
        (is (= ["abc"] (strs (replay/take! r c))))
        (is (empty? (replay/take! r c)))
        (is (false? (replay/done? r c)))
        (replay/append! r "t" [(b "de")])
        (replay/finish! r "t" 2)
        (is (= ["de"] (strs (replay/take! r c))))
        (is (true? (replay/done? r c))))))
  (testing "超限 ⇒ truncated、清空、cursor 立刻 done；之后不再攒"
    (let [r (replay/replay {:max-bytes 4})]
      (replay/begin! r "t" 0)
      (let [c (replay/open-cursor r "t" 0)]
        (is (false? (replay/append! r "t" [(b "12345")])))
        (is (= :truncated (replay/state r "t")))
        (is (true? (replay/done? r c)))
        (is (nil? (replay/open-cursor r "t" 0)))
        (is (false? (replay/append! r "t" [(b "1")]))))))
  (testing "keep 过了才清；在跑的不动"
    (let [r (replay/replay {:keep-ms 100})]
      (replay/begin! r "a" 0)
      (replay/begin! r "b" 0)
      (replay/finish! r "a" 10)
      (is (zero? (replay/expire! r 100)))
      (is (= 1 (replay/expire! r 111)))
      (is (nil? (replay/state r "a")))
      (is (= :running (replay/state r "b")))))
  (testing "线程数超了驱逐没在跑的里最久没碰的；全在跑时宁可多一条"
    (let [r (replay/replay {:max-threads 2})]
      (replay/begin! r "a" 0) (replay/finish! r "a" 1)
      (replay/begin! r "b" 2) (replay/finish! r "b" 3)
      (replay/begin! r "c" 4)
      (is (= 2 (replay/thread-count r)))
      (is (nil? (replay/state r "a")))
      (replay/begin! r "d" 5)
      (replay/begin! r "e" 6)
      (is (= 3 (replay/thread-count r)) "c / d / e 都在跑")))
  (testing "关掉 ⇒ 空转"
    (let [r (replay/replay {:enabled? false})]
      (replay/begin! r "t" 0)
      (is (false? (replay/append! r "t" [(b "x")])))
      (is (nil? (replay/open-cursor r "t" 0)))
      (is (zero? (replay/thread-count r)))))
  (is (thrown? IllegalArgumentException (replay/replay {:max-bytes -1}))))

(defn- code+wire [s]
  (try (input/decode s) nil
       (catch clojure.lang.ExceptionInfo e [(err/code e) (input/wire-code e)])))

(deftest run-agent-input
  (is (= ["agui-input/empty-body" "bad_json"] (code+wire "")))
  (is (= ["agui-input/bad-json" "bad_json"] (code+wire "{oops")))
  (is (= ["agui-input/not-object" "bad_input"] (code+wire "[1]")))
  (is (= ["agui-input/missing-string" "bad_input"] (code+wire "{\"runId\":\"r\"}")))
  (is (= ["agui-input/missing-string" "bad_input"] (code+wire "{\"threadId\":\"\",\"runId\":\"r\"}")))
  (is (= ["agui-input/missing-string" "bad_input"] (code+wire "{\"threadId\":\"t\"}")))
  (is (= ["agui-input/want-array" "bad_input"] (code+wire "{\"threadId\":\"t\",\"runId\":\"r\",\"tools\":3}")))
  (is (= ["agui-input/want-non-negative-long" "bad_input"] (code+wire "{\"threadId\":\"t\",\"runId\":\"r\",\"lastSeq\":1.5}")))
  (let [in (input/decode "{\"threadId\":\"t\",\"runId\":\"r\",\"messages\":null,\"lastSeq\":7}")]
    (is (= "t" (:threadId in)))
    (is (= 7 (:lastSeq in))))
  (is (zero? (:lastSeq (input/decode "{\"threadId\":\"t\",\"runId\":\"r\"}")))))
