(ns im.ttalk.http.sse
  "SSE 分帧核（WHATWG HTML 9.2.6，与客户端无关）：字节进、事件出；外加服务端用的拼帧（`event-frame` /
   `comment-frame` / `retry-frame`，帧是 UTF-8 字节）。移植自 Keelson `jvm/modules/clj-net/src/im/ttalk/net/sse.clj`
   （5c745869），逻辑未改；**不搬**阻塞读者与断线续读循环（designs/http-module.md §1.3：无调用方）。拼帧起初也没搬，
   clj-agent-server 的 SSE 成了调用方后补上（designs/server-module.md §2）。

   - 事件 = map：`:event-type`（缺省 \"message\"）`:data`（多行 data 以 \\n 连接）`:last-event-id`（到这个事件为止最近一次
     id: 的值，跨事件保留）`:reconnection-ms`（这个事件自己带的 retry:，没有 nil）。
   - 行结束收 \\n、\\r\\n、\\r（CRLF 被拆在两次 feed 之间也只算一次）；按 UTF-8 解码、去 BOM，charset= 被规范忽略；
     流在事件中途结束时那半个事件不派发。

   ```clojure
   (parse-all \"event: chat\\ndata: a\\ndata: b\\n\\n\")   ;; => [{:event-type \"chat\" :data \"a\\nb\" …}]
   (let [p (parser)] (feed! p bytes) (take-event! p))  ;; 增量
   ```"
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.util ArrayDeque)))

(set! *warn-on-reflection* true)

;; ── 拼帧 ──────────────────────────────────────────────

(defn- check-single-line [field ^String s]
  (when (some #{\newline \return \u0000} s)
    (throw (ex-info (str "SSE 的 " field " 字段不能含换行或 NUL")
                    {:im.ttalk.http/code "sse/field-no-newline" :field field}))))

(defn- split-lines [^String s]
  (loop [i 0 start 0 lines []]
    (if (>= i (count s))
      (conj lines (subs s start))
      (let [c (.charAt s i)]
        (cond (= c \newline) (recur (inc i) (inc i) (conj lines (subs s start i)))
              (= c \return) (let [j (if (and (< (inc i) (count s)) (= \newline (.charAt s (inc i)))) (+ i 2) (inc i))]
                              (recur j j (conj lines (subs s start i))))
              :else (recur (inc i) start lines))))))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn event-frame
  "一个事件帧（UTF-8 字节）。`opts`：`:event` `:id` `:retry-ms`（非负）。字段次序钉死为 retry → id → event → data；
   多行 data 按 \\r\\n、\\r、\\n 拆成多个 data: 字段，输出里不留 \\r。id / event 含换行或 NUL ⇒ ex-info
   （ex-data `:im.ttalk.http/code` = \"sse/field-no-newline\"）；retry-ms 为负 ⇒ \"sse/retry-want-nonneg-int\"。"
  (^bytes [data] (event-frame data nil))
  (^bytes [^String data {:keys [event id retry-ms]}]
   (when (nil? data) (throw (IllegalArgumentException. "data")))
   (when id (check-single-line "id" id))
   (when event (check-single-line "event" event))
   (when (and retry-ms (neg? retry-ms))
     (throw (ex-info "SSE 的 retry 必须是非负整数" {:im.ttalk.http/code "sse/retry-want-nonneg-int"})))
   (let [sb (StringBuilder.)
         field (fn [n v] (.append sb (str n ": " v "\n")))]
     (when retry-ms (field "retry" retry-ms))
     (when id (field "id" id))
     (when event (field "event" event))
     (doseq [l (split-lines data)] (field "data" l))
     (.append sb \newline)
     (utf8 (str sb)))))

(defn comment-frame
  "注释帧（常用作心跳）：\": text\\n\\n\"。客户端解析器不把它当事件。"
  ^bytes [^String text]
  (when (nil? text) (throw (IllegalArgumentException. "text")))
  (check-single-line "comment" text)
  (utf8 (str ": " text "\n\n")))

(defn retry-frame
  "只发 retry：\"retry: ms\\n\\n\"。负数 ⇒ IllegalArgumentException。"
  ^bytes [ms]
  (when (neg? ms) (throw (IllegalArgumentException. (str "ms: " ms))))
  (utf8 (str "retry: " ms "\n\n")))

;; ── 分帧核 ─────────────────────────────────────────────

(definterface IParser
  (^void feed [^bytes chunk ^int offset ^int count])
  (take [])
  (^String lastEventId [])
  (reconnectionMs []))

(definterface ^:private IParserSteps
  (^void endLine [])
  (^void takeLine [^String l])
  (^void dispatch []))

(deftype ^:private Parser [^ByteArrayOutputStream line ^StringBuilder data ^ArrayDeque ready
                           ^:unsynchronized-mutable skip-lf
                           ^:unsynchronized-mutable first-line
                           ^:unsynchronized-mutable has-data
                           ^:unsynchronized-mutable ^String event-type
                           ^:unsynchronized-mutable retry-in-block
                           ^:unsynchronized-mutable ^String last-id
                           ^:unsynchronized-mutable reconnection]
  IParser
  (feed [this chunk offset n]
    (loop [i offset]
      (when (< i (+ offset n))
        (let [b (aget chunk i)]
          (if (and skip-lf (do (set! skip-lf false) (== b 10)))
            nil
            (cond (== b 13) (do (set! skip-lf true) (.endLine this))
                  (== b 10) (.endLine this)
                  :else (.write line (int b)))))
        (recur (unchecked-inc-int i)))))
  (take [_] (.pollFirst ready))
  (lastEventId [_] last-id)
  (reconnectionMs [_] reconnection)
  IParserSteps
  (endLine [this]
    (let [bytes (.toByteArray line)
          start (if (and first-line (>= (alength bytes) 3)
                         (== (bit-and (aget bytes 0) 0xFF) 0xEF) (== (bit-and (aget bytes 1) 0xFF) 0xBB)
                         (== (bit-and (aget bytes 2) 0xFF) 0xBF))
                  3 0)]
      (set! first-line false)
      ;; EventSource 流必须按 UTF-8 解码（非法字节换 U+FFFD），charset= 被规范忽略
      (let [s (String. bytes (int start) (int (- (alength bytes) start)) StandardCharsets/UTF_8)]
        (.reset line)
        (.takeLine this s))))
  (takeLine [this l]
    (cond
      (empty? l) (.dispatch this)
      (= \: (.charAt l 0)) nil
      :else
      (let [colon (.indexOf l ":")
            field (if (neg? colon) l (subs l 0 colon))
            value (if (neg? colon) "" (let [v (subs l (inc colon))] (if (str/starts-with? v " ") (subs v 1) v)))]
        (case field
          "data" (do (when has-data (.append data \newline))
                     (.append data value)
                     (set! has-data true))
          "event" (set! event-type value)
          "id" (when-not (str/includes? value "\u0000") (set! last-id value))
          "retry" (when (and (seq value) (<= (count value) 18) (every? #(<= (int \0) (int %) (int \9)) value))
                    (let [t (Long/parseLong value)]
                      (set! reconnection t)
                      (set! retry-in-block t)))
          nil))))
  (dispatch [_]
    (when has-data
      (.addLast ready {:event-type (if (empty? event-type) "message" event-type)
                       :data (str data)
                       :last-event-id last-id
                       :reconnection-ms retry-in-block})
      (.setLength data 0)
      (set! has-data false))
    (set! event-type "")
    (set! retry-in-block nil)))

(defn parser
  "新的分帧核（可变，非线程安全）。`last-event-id`：续读时从上一条连接的 last event ID 接着算。"
  (^im.ttalk.http.sse.IParser [] (parser nil))
  (^im.ttalk.http.sse.IParser [last-event-id]
   (Parser. (ByteArrayOutputStream. 256) (StringBuilder.) (ArrayDeque.) false true false "" nil (or last-event-id "") nil)))

(defn feed!
  "喂一段字节。"
  ([p ^bytes chunk] (feed! p chunk 0 (alength chunk)))
  ([^im.ttalk.http.sse.IParser p ^bytes chunk offset n] (.feed p chunk (int offset) (int n))))

(defn take-event!
  "取出一个已经派发的事件；没有 ⇒ nil。"
  [^im.ttalk.http.sse.IParser p]
  (.take p))

(defn parser-last-event-id
  "最近一次 id: 的值（跨事件保留）。"
  ^String [^im.ttalk.http.sse.IParser p]
  (.lastEventId p))

(defn parser-reconnection-ms
  [^im.ttalk.http.sse.IParser p]
  (.reconnectionMs p))

;; ── 一次性解析（MCP 的「整段响应体是 SSE」用） ────────────

(defn parse-all
  "整段 SSE（串按 UTF-8，或字节数组）⇒ 事件向量。结尾没有空行收尾的半个事件不算。"
  [body]
  (let [^bytes b (cond (nil? body) (byte-array 0)
                       (bytes? body) body
                       :else (.getBytes (str body) StandardCharsets/UTF_8))
        p (parser)]
    (feed! p b)
    (loop [acc []]
      (if-let [e (take-event! p)] (recur (conj acc e)) acc))))
