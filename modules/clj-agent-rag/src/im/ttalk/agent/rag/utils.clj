(ns im.ttalk.agent.rag.utils
  "RAG 公共工具模块

   提供 RAG 相关模块共享的工具函数：
   - UUID 生成
   - 类型转换
   - 向量运算
   - 文本处理
   - 安全操作

   设计原则：
   - 纯函数，无副作用
   - 可复用于所有 RAG 模块
   - 简化常见操作

   参考 Erlang agent_rag_utils 设计。"
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

;;; ============================================================
;;; UUID 生成
;;; ============================================================

(defn generate-uuid
  "生成 UUID v4

   返回标准格式的 UUID 字符串。"
  []
  (str (UUID/randomUUID)))

;;; ============================================================
;;; 类型转换
;;; ============================================================

(defn ensure-string
  "确保值为字符串

   参数：
   - v 任意值

   返回：
   字符串"
  [v]
  (cond
    (string? v) v
    (nil? v) ""
    :else (str v)))

(defn to-float
  "转换为浮点数

   支持转换：float, integer, string

   参数：
   - v 输入值

   返回：
   浮点数（转换失败返回 0.0）"
  [v]
  (cond
    (float? v) v
    (integer? v) (double v)
    (string? v) (try
                  (Double/parseDouble v)
                  (catch Exception _ 0.0))
    :else 0.0))

(defn to-int
  "转换为整数

   参数：
   - v 输入值

   返回：
   整数（转换失败返回 0）"
  [v]
  (cond
    (integer? v) v
    (float? v) (int v)
    (string? v) (try
                  (Integer/parseInt v)
                  (catch Exception _ 0))
    :else 0))

;;; ============================================================
;;; 向量运算
;;; ============================================================

(defn dot-product
  "计算两个向量的点积

   参数：
   - vec1 向量 1
   - vec2 向量 2

   返回：
   点积值"
  [vec1 vec2]
  (reduce + (map * vec1 vec2)))

(defn vector-norm
  "计算向量的模（L2 范数）

   参数：
   - vec 输入向量

   返回：
   模长值"
  [vec]
  (Math/sqrt (reduce + (map #(* % %) vec))))

(defn safe-divide
  "安全除法

   当除数为零时返回默认值，避免除零错误。

   参数：
   - numerator   分子
   - denominator 分母
   - default     除零时的默认值（默认 0.0）

   返回：
   商或默认值"
  ([numerator denominator]
   (safe-divide numerator denominator 0.0))
  ([numerator denominator default]
   (if (zero? denominator)
     default
     (/ numerator denominator))))

(defn cosine-similarity
  "计算余弦相似度

   参数：
   - vec1 向量 1
   - vec2 向量 2

   返回：
   相似度值（-1 到 1，1 表示完全相似）"
  [vec1 vec2]
  (let [dp (dot-product vec1 vec2)
        norm1 (vector-norm vec1)
        norm2 (vector-norm vec2)]
    (safe-divide dp (* norm1 norm2))))

(defn normalize-vector
  "向量归一化

   参数：
   - vec 输入向量

   返回：
   归一化后的向量（模长为 1）"
  [vec]
  (let [norm (vector-norm vec)]
    (if (zero? norm)
      vec
      (mapv #(/ % norm) vec))))

(defn euclidean-distance
  "计算欧几里得距离

   参数：
   - vec1 向量 1
   - vec2 向量 2

   返回：
   距离值（越小越相似）"
  [vec1 vec2]
  (let [diff-squared (map (fn [v1 v2]
                            (let [d (- v1 v2)]
                              (* d d)))
                          vec1 vec2)]
    (Math/sqrt (reduce + diff-squared))))

;;; ============================================================
;;; 文本处理
;;; ============================================================

(defn truncate
  "截断文本

   参数：
   - text       输入文本
   - max-length 最大长度
   - suffix     截断后缀（默认 \"...\"）

   返回：
   截断后的文本"
  ([text max-length]
   (truncate text max-length "..."))
  ([text max-length suffix]
   (if (> (count text) max-length)
     (str (subs text 0 (- max-length (count suffix))) suffix)
     text)))

(defn clean-text
  "清理文本

   移除多余空白、规范化换行等。

   参数：
   - text 输入文本

   返回：
   清理后的文本"
  [text]
  (-> text
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")
      (str/replace #"[ \t]+" " ")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn word-count
  "计算单词数

   参数：
   - text 输入文本

   返回：
   单词数"
  [text]
  (count (str/split (str/trim text) #"\s+")))

(defn char-count
  "计算字符数（不含空白）

   参数：
   - text 输入文本

   返回：
   字符数"
  [text]
  (count (str/replace text #"\s" "")))

;;; ============================================================
;;; 集合操作
;;; ============================================================

(defn take-safe
  "安全取前 N 个元素

   当列表长度小于 N 时返回整个列表。

   参数：
   - coll 集合
   - n    数量

   返回：
   前 n 个元素"
  [n coll]
  (take n coll))

(defn zip-with-index
  "为集合元素添加索引

   返回 [[index element] ...] 格式，索引从 0 开始。

   参数：
   - coll 输入集合

   返回：
   带索引的向量"
  [coll]
  (map-indexed vector coll))

(defn group-by-key
  "按键分组并提取值

   参数：
   - key-fn 提取键的函数
   - coll   集合

   返回：
   {key [values...]} 的 map"
  [key-fn coll]
  (reduce
    (fn [acc item]
      (let [k (key-fn item)]
        (update acc k (fnil conj []) item)))
    {}
    coll))

;;; ============================================================
;;; Map 操作
;;; ============================================================

(defn get-opt
  "从 Map 获取值，支持默认值

   参数：
   - m       map
   - key     键
   - default 默认值

   返回：
   值或默认值"
  [m key default]
  (get m key default))

(defn deep-merge
  "深度合并多个 map

   参数：
   - maps 要合并的 map 列表

   返回：
   合并后的 map"
  [& maps]
  (apply merge-with
         (fn [v1 v2]
           (if (and (map? v1) (map? v2))
             (deep-merge v1 v2)
             v2))
         maps))

;;; ============================================================
;;; 错误处理
;;; ============================================================

(defn try-or
  "尝试执行，失败返回默认值

   参数：
   - f       要执行的函数（无参）
   - default 失败时的默认值

   返回：
   执行结果或默认值"
  [f default]
  (try
    (f)
    (catch Exception _ default)))

(defn result-ok
  "构建成功结果

   参数：
   - value 结果值

   返回：
   {:ok true :value value}"
  [value]
  {:ok true :value value})

(defn result-error
  "构建错误结果

   参数：
   - error 错误信息

   返回：
   {:ok false :error error}"
  [error]
  {:ok false :error error})

(defn ok?
  "检查结果是否成功

   参数：
   - result 结果 map

   返回：
   boolean"
  [result]
  (:ok result false))
