(ns im.ttalk.agent.tools.text
  "文本小件：内容 tag、UTF-8 字节截断、「按原样读进来、按原样写回去」的可编辑文本、统一格式 diff。

   - `content-tag`：FNV-1a 32 位、8 位大写十六进制（出处 Keelson `ContentTag.cs`，
     借鉴 coxswain `tag.ss`；测试向量 \"hello\" ⇒ 4F9F2CAB）。是**内容**哈希不是 mtime——
     「同一秒改成同样长度」正是新鲜度闸要抓的那类。
   - `decode-editable` / `encode-editable`：换行 / 编码 / BOM 原样写回，二进制拒绝
     （出处 Keelson `EditableText.cs`）。只认 UTF-8（可带 BOM）、带 BOM 的 UTF-16/32、
     严格 GBK（编回去逐字节相同才认）；其余拒（⛔ 猜一个写回去）。
   - `unified-diff`：审批预览用（出处 Keelson `PatchDiff.cs` 的格式口径，算法用 LCS）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tools.workspace :refer [fail!]])
  (:import [java.nio ByteBuffer CharBuffer]
           [java.nio.charset CharacterCodingException Charset CodingErrorAction StandardCharsets]
           [java.util Arrays]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 内容 tag
;;; ============================================================

(def ^:const fnv-offset 2166136261)
(def ^:const fnv-prime 16777619)

(defn tag-append
  "从 hash 接着喂 bytes 的 [off, off+len)。流式读文件时用。"
  (^long [^long h ^bytes bs] (tag-append h bs 0 (alength bs)))
  (^long [^long h ^bytes bs ^long off ^long len]
   (loop [h h i off]
     (if (< i (+ off len))
       (recur (bit-and (* (bit-xor h (bit-and (long (aget bs i)) 0xff)) fnv-prime) 0xffffffff)
              (inc i))
       h))))

(defn tag-format ^String [^long h] (format "%08X" h))

(defn content-tag
  "字节或字符串（按 UTF-8）的 tag。"
  ^String [x]
  (let [^bytes bs (if (string? x) (.getBytes ^String x StandardCharsets/UTF_8) x)]
    (tag-format (tag-append fnv-offset bs))))

;;; ============================================================
;;; UTF-8 截断
;;; ============================================================

(defn take-bytes-at-char-boundary
  "超过 max-bytes 个 UTF-8 字节就在字符边界上截（Codex take_bytes_at_char_boundary）。"
  ^String [^String s ^long max-bytes]
  (if (<= (alength (.getBytes s StandardCharsets/UTF_8)) max-bytes)
    s
    (loop [i 0 bytes (num 0)]
      (if (>= i (.length s))
        s
        (let [cp (.codePointAt s i)
              n (cond (< cp 0x80) 1 (< cp 0x800) 2 (< cp 0x10000) 3 :else 4)]
          (if (> (+ bytes n) max-bytes)
            (subs s 0 i)
            (recur (+ i (Character/charCount cp)) (+ bytes n))))))))

(defn utf8-len ^long [^String s] (alength (.getBytes s StandardCharsets/UTF_8)))

;;; ============================================================
;;; 严格编解码
;;; ============================================================

(defn strict-decode
  "严格解码；坏字节 ⇒ nil。"
  [^Charset cs ^bytes bs off]
  (try
    (-> (.newDecoder cs)
        (.onMalformedInput CodingErrorAction/REPORT)
        (.onUnmappableCharacter CodingErrorAction/REPORT)
        (.decode (ByteBuffer/wrap bs (int off) (- (alength bs) (int off))))
        str)
    (catch CharacterCodingException _ nil)))

(defn strict-encode
  "严格编码；写不出 ⇒ nil。"
  [^Charset cs ^String s]
  (try
    (let [bb (-> (.newEncoder cs)
                 (.onMalformedInput CodingErrorAction/REPORT)
                 (.onUnmappableCharacter CodingErrorAction/REPORT)
                 (.encode (CharBuffer/wrap s)))
          out (byte-array (.remaining bb))]
      (.get bb out)
      out)
    (catch CharacterCodingException _ nil)))

(defn utf8-strict
  "严格 UTF-8 解码；坏字节 ⇒ nil。"
  [^bytes bs]
  (strict-decode StandardCharsets/UTF_8 bs 0))

;;; ============================================================
;;; 可编辑文本
;;; ============================================================

(defn- starts-with-bytes? [^bytes bs prefix]
  (and (>= (alength bs) (count prefix))
       (every? true? (map-indexed (fn [i b] (= (aget bs i) (unchecked-byte b))) prefix))))

(def ^:private boms
  ;; 次序要紧：UTF-32LE 的 BOM 以 UTF-16LE 的 BOM 开头
  [["UTF-32BE" [0x00 0x00 0xFE 0xFF]]
   ["UTF-32LE" [0xFF 0xFE 0x00 0x00]]
   ["UTF-8"    [0xEF 0xBB 0xBF]]
   ["UTF-16BE" [0xFE 0xFF]]
   ["UTF-16LE" [0xFF 0xFE]]])

(defn- has-nul? [^bytes bs]
  (loop [i 0] (cond (>= i (alength bs)) false (zero? (aget bs i)) true :else (recur (inc i)))))

(defn detect-eol
  "换行风格：只有 CRLF ⇒ :crlf；只有 CR ⇒ :cr；其余（含混合、没有换行）⇒ :lf（混合时多余的 \\r 是内容）。"
  [^String s]
  (let [n (.length s)]
    (loop [i 0 crlf 0 lf 0 cr 0]
      (if (>= i n)
        (cond (and (pos? crlf) (zero? lf) (zero? cr)) :crlf
              (and (pos? cr) (zero? lf) (zero? crlf)) :cr
              :else :lf)
        (let [c (.charAt s i)]
          (cond
            (and (= c \return) (< (inc i) n) (= (.charAt s (inc i)) \newline)) (recur (+ i 2) (inc crlf) lf cr)
            (= c \return) (recur (inc i) crlf lf (inc cr))
            (= c \newline) (recur (inc i) crlf (inc lf) cr)
            :else (recur (inc i) crlf lf cr)))))))

(defn- normalize-eol [^String s eol]
  (case eol
    :crlf (str/replace s "\r\n" "\n")
    :cr (str/replace s "\r" "\n")
    s))

(defn- apply-eol [^String s eol]
  (case eol
    :crlf (str/replace s "\n" "\r\n")
    :cr (str/replace s "\n" "\r")
    s))

(defn- make-text [text charset bom-bytes label]
  (let [eol (detect-eol text)]
    {:text (normalize-eol text eol) :eol eol :charset charset :bom bom-bytes :label label}))

(defn decode-editable
  "解码一个要改的文本文件；认不出 ⇒ fail!（二进制 / 未知编码）。
   返回 {:text 换行统一成 \\n 的文本 :eol :charset :bom 原样写回的 BOM 字节 :label}。"
  [^bytes bs shown]
  (if-let [[cs-name prefix] (some (fn [[_ prefix :as b]] (when (starts-with-bytes? bs prefix) b)) boms)]
    (let [cs (Charset/forName cs-name)
          n (count prefix)]
      (if-let [s (strict-decode cs bs n)]
        (make-text s cs (Arrays/copyOfRange bs 0 (int n)) (str cs-name " BOM"))
        (fail! "tools/text-file-unknown-encoding"
               (format "%s 的编码认不出（不是 UTF-8、带 BOM 的 UTF-16 / UTF-32，也不是 GBK），没有改它。" shown))))
    (cond
      (has-nul? bs)
      (fail! "tools/text-file-binary" (format "%s 看起来是二进制文件（含 NUL 字节），没有改它。" shown))

      :else
      (if-let [s (utf8-strict bs)]
        (make-text s StandardCharsets/UTF_8 (byte-array 0) "UTF-8")
        (let [gbk (Charset/forName "GBK")
              s (strict-decode gbk bs 0)]
          (if (and s (Arrays/equals ^bytes (strict-encode gbk s) bs))
            (make-text s gbk (byte-array 0) "GBK")
            (fail! "tools/text-file-unknown-encoding"
                   (format "%s 的编码认不出（不是 UTF-8、带 BOM 的 UTF-16 / UTF-32，也不是 GBK），没有改它。" shown))))))))

(defn normalize-input
  "模型给的文本 ⇒ 这个文件的换行写法：CRLF / CR 文件先把模型抄来的 \\r\\n 归一成 \\n。"
  [{:keys [eol]} ^String s]
  (if (= eol :lf) s (str/replace s "\r\n" "\n")))

(defn encode-editable
  "按原来的换行 / 编码 / BOM 编出字节；写不出 ⇒ fail!。"
  ^bytes [{:keys [eol ^Charset charset ^bytes bom label]} ^String text shown]
  (if-let [^bytes body (strict-encode charset (apply-eol text eol))]
    (let [out (byte-array (+ (alength bom) (alength body)))]
      (System/arraycopy bom 0 out 0 (alength bom))
      (System/arraycopy body 0 out (alength bom) (alength body))
      out)
    (fail! "tools/text-file-unrepresentable"
           (format "新内容里有字符在 %s 的编码（%s）下写不出来，没有改它。" shown label))))

;;; ============================================================
;;; 统一格式 diff（审批预览）
;;; ============================================================

(defn split-keep-ends
  "按 \\n 切、每行保留自己的 \\n。"
  [^String s]
  (loop [start 0 acc []]
    (if (>= start (.length s))
      acc
      (let [nl (.indexOf s "\n" (int start))]
        (if (neg? nl)
          (conj acc (subs s start))
          (recur (inc nl) (conj acc (subs s start (inc nl)))))))))

(def ^:private max-lcs-cells 4000000)

(defn- diff-ops
  "行级编辑序列 [[:same|:del|:ins line] ...]（LCS）。太大 ⇒ 整段删 + 整段加。"
  [a b]
  (let [a (vec a) b (vec b) n (count a) m (count b)]
    (if (> (* (long n) (long m)) max-lcs-cells)
      (concat (map #(vector :del %) a) (map #(vector :ins %) b))
      (let [dp (make-array Integer/TYPE (inc n) (inc m))]
        (doseq [i (range (dec n) -1 -1)
                j (range (dec m) -1 -1)]
          (aset-int dp i j (if (= (a i) (b j))
                             (inc (aget ^ints (aget ^objects dp (inc i)) (inc j)))
                             (max (aget ^ints (aget ^objects dp (inc i)) j)
                                  (aget ^ints (aget ^objects dp i) (inc j))))))
        (loop [i 0 j 0 acc []]
          (cond
            (and (< i n) (< j m) (= (a i) (b j))) (recur (inc i) (inc j) (conj acc [:same (a i)]))
            (and (< i n) (or (= j m) (>= (aget ^ints (aget ^objects dp (inc i)) j)
                                         (aget ^ints (aget ^objects dp i) (inc j)))))
            (recur (inc i) j (conj acc [:del (a i)]))
            (< j m) (recur i (inc j) (conj acc [:ins (b j)]))
            :else acc))))))

(defn- hunk-range [start0 len]
  (let [beginning (inc start0)]
    (cond (= len 1) (str beginning)
          (zero? len) (str (dec beginning) ",0")
          :else (str beginning "," len))))

(defn unified-diff
  "旧 ⇒ 新的统一格式差分（没有 ---/+++ 头，hunk 头行数为 1 时省掉；同 Codex 的 similar 口径）。"
  ([old new] (unified-diff old new 1))
  ([old new context]
   (let [ops (loop [[op & more] (diff-ops (split-keep-ends old) (split-keep-ends new)) oi 0 ni 0 acc []]
               (if-not op
                 acc
                 (let [[k line] op]
                   (recur more (if (= k :ins) oi (inc oi)) (if (= k :del) ni (inc ni))
                          (conj acc {:op k :old oi :new ni :line line})))))
         n (count ops)
         runs (loop [k (num 0) acc []]
                (cond (>= k n) acc
                      (= :same (:op (ops k))) (recur (inc k) acc)
                      :else (let [e (loop [e k] (if (and (< e n) (not= :same (:op (ops e)))) (recur (inc e)) e))]
                              (recur e (conj acc [k e])))))
         sb (StringBuilder.)]
     (loop [r (num 0)]
       (when (< r (count runs))
         (let [gs (max 0 (- (first (runs r)) context))
               [r ge] (loop [r r ge (second (runs r))]
                        (if (and (< (inc r) (count runs)) (<= (- (first (runs (inc r))) ge) (* 2 context)))
                          (recur (inc r) (second (runs (inc r))))
                          [r ge]))
               ge (min n (+ ge context))
               seg (subvec ops gs ge)
               olds (remove #(= :ins (:op %)) seg)
               news (remove #(= :del (:op %)) seg)
               os (if (seq olds) (:old (first olds)) (:old (first seg)))
               ns (if (seq news) (:new (first news)) (:new (first seg)))]
           (.append sb (str "@@ -" (hunk-range os (count olds)) " +" (hunk-range ns (count news)) " @@\n"))
           (doseq [{:keys [op ^String line]} seg]
             (.append sb (case op :del "-" :ins "+" " "))
             (.append sb line)
             (when-not (str/ends-with? line "\n") (.append sb "\n\\ No newline at end of file\n")))
           (recur (inc r)))))
     (str sb))))
