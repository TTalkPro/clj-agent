(ns im.ttalk.http.sse-frame-test
  "S-01：SSE 拼帧。期望值逐条搬自 Keelson clj-net test/im/ttalk/net/sse_frame_test.clj（其期望来自 keel tests/sse.ss），
   外加经 `parse-all` 的往返。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.http.sse :as sse])
  (:import (java.nio.charset StandardCharsets)))

(defn- s [^bytes b] (String. b StandardCharsets/UTF_8))

(deftest fields
  (is (= "data: hi\n\n" (s (sse/event-frame "hi"))))
  (is (= "event: chat\ndata: hi\n\n" (s (sse/event-frame "hi" {:event "chat"}))))
  (is (= "id: 42\ndata: hi\n\n" (s (sse/event-frame "hi" {:id "42"}))))
  (is (= "retry: 3000\nid: 42\nevent: chat\ndata: hi\n\n" (s (sse/event-frame "hi" {:event "chat" :id "42" :retry-ms 3000})))))

(deftest multi-line-data
  (doseq [[data expected] [["a\nb" "data: a\ndata: b\n\n"]
                           ["a\nb\nc" "data: a\ndata: b\ndata: c\n\n"]
                           ["a\n" "data: a\ndata: \n\n"]
                           ["\na" "data: \ndata: a\n\n"]
                           ["" "data: \n\n"]
                           ["a\r\nb" "data: a\ndata: b\n\n"]
                           ["a\rb" "data: a\ndata: b\n\n"]
                           ["a\r\nb\rc" "data: a\ndata: b\ndata: c\n\n"]]]
    (is (= expected (s (sse/event-frame data))) (pr-str data))))

(deftest multi-line-with-event-and-no-cr-left
  (is (= "event: log\ndata: 第一行\ndata: 第二行\n\n" (s (sse/event-frame "第一行\n第二行" {:event "log"}))))
  (is (not (str/includes? (s (sse/event-frame "a\r\nb\rc")) "\r"))))

(defn- code-of [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:im.ttalk.http/code (ex-data e)))))

(deftest bad-fields-throw
  (is (= "sse/field-no-newline" (code-of #(sse/event-frame "x" {:event "a\nb"}))))
  (is (= "sse/field-no-newline" (code-of #(sse/event-frame "x" {:event "a\rb"}))))
  (is (= "sse/field-no-newline" (code-of #(sse/event-frame "x" {:id "a\nb"}))))
  (is (= "sse/field-no-newline" (code-of #(sse/event-frame "x" {:id "a\u0000b"}))))
  (is (= "sse/field-no-newline" (code-of #(sse/comment-frame "a\nb"))))
  (is (= "sse/retry-want-nonneg-int" (code-of #(sse/event-frame "x" {:retry-ms -1}))))
  (is (thrown? IllegalArgumentException (sse/retry-frame -1))))

(deftest comment-and-retry
  (is (= ": ping\n\n" (s (sse/comment-frame "ping"))))
  (is (= ": \n\n" (s (sse/comment-frame ""))))
  (is (= "retry: 5000\n\n" (s (sse/retry-frame 5000)))))

(deftest utf8
  (is (= (alength (.getBytes "data: 你好\n\n" StandardCharsets/UTF_8)) (alength (sse/event-frame "你好"))))
  (is (= "data: 😀\n\n" (s (sse/event-frame "😀")))))

(deftest frames-round-trip-through-the-parser
  (doseq [[data opts] [["hi" nil] ["a\nb\r\nc" {:event "chat" :id "7"}] ["" {:retry-ms 10}] ["你好😀" {:event "log"}]]]
    (let [[e] (sse/parse-all (sse/event-frame data opts))]
      (is (= (str/replace data #"\r\n|\r" "\n") (:data e)) (pr-str data))
      (is (= (or (:event opts) "message") (:event-type e)))
      (is (= (or (:id opts) "") (:last-event-id e)))))
  (is (= [] (sse/parse-all (sse/comment-frame "ping"))) "注释不是事件"))
