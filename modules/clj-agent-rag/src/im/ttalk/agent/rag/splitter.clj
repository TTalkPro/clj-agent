(ns im.ttalk.agent.rag.splitter
  "RAG 文本分割模块

   负责将长文本分割为可嵌入的片段（chunk）：
   - 按分隔符切分：默认使用换行符
   - 尺寸控制：确保每个片段不超过指定大小
   - 重叠处理：相邻片段保持部分重叠以保持上下文

   分割算法：
   1. 按分隔符（如换行）将文本切分为段落
   2. 将段落合并为不超过 chunk_size 的块
   3. 块之间保留 chunk_overlap 字节的重叠

   设计原则：
   - 纯函数，无副作用
   - 保持语义：尽量不在句子中间切分
   - 可配置：支持自定义分割参数

   参考 Erlang agent_rag_splitter 设计。"
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

;;; ============================================================
;;; 常量定义
;;; ============================================================

(def ^:const default-chunk-size
  "默认块大小（字符数）"
  1000)

(def ^:const default-chunk-overlap
  "默认块重叠大小（字符数）"
  200)

(def ^:const default-separator
  "默认分隔符"
  "\n")

;;; ============================================================
;;; 分割器类型
;;; ============================================================

(defrecord Splitter [chunk-size chunk-overlap separator])

(defn make-splitter
  "创建文本分割器

   参数：
   - :chunk-size    每个块的最大字符数（默认 1000）
   - :chunk-overlap 相邻块的重叠字符数（默认 200）
   - :separator     切分文本的分隔符（默认换行符）

   返回：
   Splitter 实例

   示例：
   (make-splitter)
   (make-splitter :chunk-size 500 :chunk-overlap 100)"
  [& {:keys [chunk-size chunk-overlap separator]
      :or {chunk-size default-chunk-size
           chunk-overlap default-chunk-overlap
           separator default-separator}}]
  (->Splitter chunk-size chunk-overlap separator))

;;; ============================================================
;;; 配置访问
;;; ============================================================

(defn get-chunk-size
  "获取块大小配置"
  [splitter]
  (:chunk-size splitter))

(defn get-overlap
  "获取重叠配置"
  [splitter]
  (:chunk-overlap splitter))

(defn get-separator
  "获取分隔符配置"
  [splitter]
  (:separator splitter))

;;; ============================================================
;;; 内部辅助函数
;;; ============================================================

(defn- generate-uuid
  "生成 UUID"
  []
  (str (UUID/randomUUID)))

(defn- append-segment
  "追加段落到当前块

   参数：
   - current   当前累积的文本
   - segment   要追加的段落
   - separator 分隔符

   返回：
   合并后的文本"
  [current segment separator]
  (if (str/blank? current)
    segment
    (str current separator segment)))

(defn- extract-overlap
  "提取重叠文本

   从块末尾提取指定长度的文本作为下一块的起始。

   参数：
   - text    源文本
   - overlap 重叠长度

   返回：
   重叠文本"
  [text overlap]
  (let [len (count text)]
    (if (> len overlap)
      (subs text (- len overlap))
      text)))

(defn- merge-segments
  "合并段落为块

   将按分隔符切分的段落合并为不超过指定大小的块。
   使用 loop/recur 实现尾递归，避免栈溢出。

   参数：
   - segments 段落列表
   - chunk-size 块大小限制
   - overlap 重叠大小
   - separator 分隔符

   返回：
   块列表（字符串向量）"
  [segments chunk-size overlap separator]
  (loop [remaining segments
         acc []
         current ""]
    (if (empty? remaining)
      ;; 处理完毕
      (if (str/blank? current)
        acc
        (conj acc current))
      ;; 继续处理
      (let [seg (first remaining)
            new-current (append-segment current seg separator)]
        (if (>= (count new-current) chunk-size)
          ;; 当前块已满，保存并开始新块
          (let [overlap-text (extract-overlap new-current overlap)]
            (recur (rest remaining)
                   (conj acc new-current)
                   overlap-text))
          ;; 继续累积
          (recur (rest remaining)
                 acc
                 new-current))))))

(defn- to-chunk-records
  "将字符串块列表转换为块记录列表

   参数：
   - chunk-strs 块字符串列表

   返回：
   块记录列表，每个包含：
   - :content     块内容
   - :chunk-id    块唯一 ID
   - :chunk-index 块索引（从 0 开始）"
  [chunk-strs]
  (map-indexed
    (fn [idx content]
      {:content content
       :chunk-id (generate-uuid)
       :chunk-index idx})
    chunk-strs))

;;; ============================================================
;;; 分割操作 API
;;; ============================================================

(defn split
  "分割文本为块列表

   使用段落感知的分割算法：
   1. 按分隔符切分为段落
   2. 合并段落为不超过 chunk-size 的块
   3. 块之间保留 chunk-overlap 的重叠

   参数：
   - splitter 分割器实例
   - text     输入文本

   返回：
   块记录列表，每个包含：
   - :content     块内容
   - :chunk-id    块唯一 ID
   - :chunk-index 块索引

   示例：
   (split (make-splitter :chunk-size 500) \"长文本...\")"
  [splitter text]
  (let [{:keys [chunk-size chunk-overlap separator]} splitter
        ;; 按分隔符切分，并过滤空段落
        segments (->> (str/split text (re-pattern (java.util.regex.Pattern/quote separator)))
                      (map str/trim)
                      (remove str/blank?))
        ;; 合并为块
        chunk-strs (merge-segments segments chunk-size chunk-overlap separator)]
    ;; 转换为记录
    (vec (to-chunk-records chunk-strs))))

(defn split-text
  "分割文本（简化版）

   直接返回块内容列表（不含元数据）。

   参数：
   - splitter 分割器实例
   - text     输入文本

   返回：
   块内容字符串列表"
  [splitter text]
  (mapv :content (split splitter text)))

;;; ============================================================
;;; 便捷分割函数
;;; ============================================================

(defn split-by-size
  "按固定大小分割（无重叠）

   参数：
   - text 输入文本
   - size 块大小

   返回：
   块列表"
  [text size]
  (split (make-splitter :chunk-size size :chunk-overlap 0) text))

(defn split-by-paragraphs
  "按段落分割

   使用双换行符作为分隔符。

   参数：
   - text 输入文本

   返回：
   段落列表"
  [text]
  (->> (str/split text #"\n\n+")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn split-by-sentences
  "按句子分割

   参数：
   - text 输入文本

   返回：
   句子列表"
  [text]
  (->> (str/split text #"[.!?。！？]+")
       (map str/trim)
       (remove str/blank?)
       vec))

;;; ============================================================
;;; 统计函数
;;; ============================================================

(defn chunk-stats
  "计算分块统计信息

   参数：
   - chunks 块列表

   返回：
   统计信息 map"
  [chunks]
  (let [sizes (map #(count (:content %)) chunks)]
    {:count (count chunks)
     :total-chars (reduce + sizes)
     :min-size (when (seq sizes) (apply min sizes))
     :max-size (when (seq sizes) (apply max sizes))
     :avg-size (when (seq sizes)
                 (double (/ (reduce + sizes) (count sizes))))}))
