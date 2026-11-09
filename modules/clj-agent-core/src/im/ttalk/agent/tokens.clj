(ns im.ttalk.agent.tokens
  "token 粗估（零依赖）——上下文压缩（`filter.compaction`）、工具结果截断
   （`filter.tool-output`）与模型审阅者的证据预算共用一份口径。

   口径（宁高勿低：低估会让压缩来不及触发，高估只是早压一点）：
   - ASCII 每 4 个字符 1 token（英文/代码的经验值）；
   - 非 ASCII 每字符 1 token（CJK 实测约 0.6–1.2，取保守端）；
   - 每条消息 +4（角色/分隔开销）；
   - 工具调用 = 名字 + 参数 `pr-str`；
   - 图片 / 文件部件按固定值（`image-tokens` / `document-tokens`，同 Keelson
     `CompactionBudget`：⛔ 按 base64 长度算——那会把一张图算成几万 token）。

   设计见 designs/runtime-borrow.md §1。"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def image-tokens "一张图片折多少 token（Codex：7373 字节 ≈ 1844 token）。" 1844)
(def document-tokens "一个非图片文件部件折多少 token（12000 字符 / 4）。" 3000)
(def message-overhead "每条消息的固定开销。" 4)

(defn estimate-text
  "一段文本的近似 token 数（nil → 0）。"
  ^long [s]
  (if (nil? s)
    0
    (let [^String s (if (string? s) s (str s))
          n (.length s)]
      (loop [i 0, ascii 0, other 0]
        (if (< i n)
          (if (< (int (.charAt s i)) 128)
            (recur (inc i) (inc ascii) other)
            (recur (inc i) ascii (inc other)))
          (+ other (long (Math/ceil (/ ascii 4.0)))))))))

(defn- part-tokens
  [p]
  (cond
    (string? p) (estimate-text p)
    (map? p)
    (let [t (:type p)]
      (cond
        (= t :text) (estimate-text (:text p))
        (= t :file) (if (some-> (:media-type p) (str/starts-with? "image/"))
                      image-tokens document-tokens)
        (= t "text") (estimate-text (:text p))
        (#{"image" "image_url"} t) image-tokens
        :else (estimate-text (pr-str p))))
    :else (estimate-text (pr-str p))))

(defn estimate-content
  "消息 `:content` 的 token 数：字符串或多模态部件向量。"
  ^long [c]
  (cond
    (nil? c) 0
    (string? c) (estimate-text c)
    (sequential? c) (reduce + 0 (map part-tokens c))
    :else (estimate-text (pr-str c))))

(defn estimate-message
  "一条中立消息的 token 数（内容 + 工具调用 + 固定开销）。"
  ^long [m]
  (+ message-overhead
     (estimate-content (:content m))
     (reduce + 0 (map (fn [{:keys [name args]}]
                        (+ (estimate-text name) (estimate-text (pr-str args))))
                      (:tool-calls m)))))

(defn estimate-messages
  "消息序列的 token 总数。"
  ^long [msgs]
  (reduce + 0 (map estimate-message msgs)))

(defn estimate-request
  "整包估算：消息 + system prompt + 工具 schema（都占窗口）。"
  ^long [msgs {:keys [system-prompt tools]}]
  (+ (estimate-messages msgs)
     (estimate-text system-prompt)
     (reduce + 0 (map #(estimate-text (pr-str %)) tools))))
