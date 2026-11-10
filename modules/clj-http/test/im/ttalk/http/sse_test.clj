(ns im.ttalk.http.sse-test
  "H-02：SSE 分帧核。期望值逐条搬自 Keelson clj-net test/im/ttalk/net/sse_read_test.clj（其期望来自 keel 的
   make-sse-parser）；读者 / 续读循环 / 拼帧不搬，相应用例（sse_core_test、sse_frame_test）不移植。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http.sse :as sse])
  (:import (java.nio.charset StandardCharsets)))

(defn parse [in] (sse/parse-all in))

;; 期望值来自 keel 的分帧核 make-sse-parser（Chez 直接跑，同一组输入），每行是 (type, last-id, data)
(def keel-parser-rows
  [["basic" "data: hi\n\n" ["message|''|hi"]]
   ["event+id" "event: chat\nid: 42\ndata: hi\n\n" ["chat|'42'|hi"]]
   ["multi-data" "data: a\ndata: b\n\n" ["message|''|a\nb"]]
   ["id-persists" "id: 1\ndata: a\n\ndata: b\n\n" ["message|'1'|a" "message|'1'|b"]]
   ["crlf" "data: a\r\ndata: b\r\n\r\n" ["message|''|a\nb"]]
   ["cr" "data: a\rdata: b\r\r" ["message|''|a\nb"]]
   ["comment" ": ping\ndata: x\n\n" ["message|''|x"]]
   ["no-space" "data:x\n\n" ["message|''|x"]]
   ["two-spaces" "data:  x\n\n" ["message|''| x"]]
   ["no-colon" "data\n\n" ["message|''|"]]
   ["empty-dispatch" "event: e\n\ndata: y\n\n" ["message|''|y"]]
   ["no-trailing-blank" "data: lost\n" []]
   ["bom" "﻿data: b\n\n" ["message|''|b"]]
   ["id-nul" "id: 1\ndata: a\n\nid: x\u0000y\ndata: b\n\n" ["message|'1'|a" "message|'1'|b"]]
   ["unknown-field" "foo: bar\ndata: z\n\n" ["message|''|z"]]
   ["retry-field" "retry: 10\ndata: r\n\n" ["message|''|r"]]
   ["empty-id-resets" "id: 5\ndata: a\n\nid\ndata: b\n\n" ["message|'5'|a" "message|''|b"]]
   ["middle-empty-data" "data: a\ndata: \ndata: b\n\n" ["message|''|a\n\nb"]]
   ["trailing-empty-data" "data: a\ndata: \n\n" ["message|''|a\n"]]
   ["event-then-retry-only" "event: x\nretry: 5\n\ndata: z\n\n" ["message|''|z"]]
   ["invalid-utf8" "data: �\n\n" ["message|''|�"]]])

(deftest parses-like-keel
  (doseq [[name wire expected] keel-parser-rows]
    (testing name
      (is (= expected (mapv #(str (:event-type %) "|'" (:last-event-id %) "'|" (:data %)) (parse wire)))))))

;; ⚠️ 有意与 keel 不同的一格：开头的空 data 行照规范保留（keel 吞掉它）
(deftest leading-empty-data-line-follows-spec-not-keel
  (let [evs (parse "data: \ndata: a\n\n")]
    (is (= 1 (count evs)))
    (is (= "\na" (:data (first evs))))))

(deftest invalid-utf8-bytes-become-replacement-char
  (let [evs (parse (byte-array [100 97 116 97 58 32 -1 10 10]))]
    (is (= 1 (count evs)))
    (is (= "�" (:data (first evs))))))

;; 一个字节一个字节地喂，CRLF 被拆在两次 feed 之间也只算一次行结束
(deftest byte-by-byte-chunks
  (let [wire (.getBytes "﻿event: 你\r\ndata: a\r\n\r\ndata: b\r\r" StandardCharsets/UTF_8)
        p (sse/parser)]
    (doseq [i (range (alength wire))] (sse/feed! p wire i 1))
    (is (= ["你|a" "message|b"]
           (loop [acc []] (if-let [e (sse/take-event! p)] (recur (conj acc (str (:event-type e) "|" (:data e)))) acc))))))

(deftest retry-field-is-surfaced
  (let [evs (parse "retry: 1500\ndata: r\n\ndata: s\n\n")]
    (is (= 1500 (:reconnection-ms (first evs))))
    (is (nil? (:reconnection-ms (second evs))))))

(deftest parser-state-and-resume-id
  (let [p (sse/parser "7")]
    (sse/feed! p (.getBytes "retry: 9\ndata: x\n\n" StandardCharsets/UTF_8))
    (is (= "7" (:last-event-id (sse/take-event! p))))
    (is (= "7" (sse/parser-last-event-id p)))
    (is (= 9 (sse/parser-reconnection-ms p)))))

(deftest parse-all-shapes
  (is (= [] (parse nil)))
  (is (= [] (parse "")))
  (is (= "{\"a\":1}" (:data (first (parse "event: message\ndata: {\"a\":1}\n\n"))))))
