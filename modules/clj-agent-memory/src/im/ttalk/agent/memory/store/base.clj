(ns im.ttalk.agent.memory.store.base
  "Store 基础实现模块

   提供:
   - 通用辅助函数
   - 批量操作默认实现

   使用示例:
   (require '[im.ttalk.agent.memory.store.base :as base])

   ;; 在 Store 实现中复用批量操作
   (put-batch [this namespace items]
     (base/default-put-batch this namespace items))"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;;; ============================================================
;;; 通用辅助函数
;;; ============================================================

(defn generate-id
  "生成唯一 ID

   返回:
   UUID 字符串"
  []
  (str (java.util.UUID/randomUUID)))

(defn now
  "获取当前时间戳

   返回:
   毫秒级时间戳"
  []
  (System/currentTimeMillis))

(defn make-storage-key
  "构建内部存储 key

   参数:
   - namespace: 命名空间
   - key:       键名

   返回:
   格式为 'namespace:key' 的字符串"
  [namespace key]
  (str namespace ":" key))

(defn parse-storage-key
  "解析内部存储 key

   参数:
   - storage-key: 格式为 'namespace:key' 的字符串

   返回:
   [namespace key] 或 nil（解析失败时）"
  [storage-key]
  (let [idx (.indexOf ^String storage-key ":")]
    (when (pos? idx)
      [(.substring ^String storage-key 0 idx)
       (.substring ^String storage-key (inc idx))])))

(defn matches-prefix?
  "检查 key 是否匹配前缀

   参数:
   - key:    要检查的键
   - prefix: 前缀字符串

   返回:
   布尔值"
  [key prefix]
  (if (empty? prefix)
    true
    (.startsWith ^String key ^String prefix)))

;;; ============================================================
;;; 批量操作默认实现
;;; ============================================================

(defn default-put-batch
  "批量存储的默认实现

   通过循环调用单个 put 操作实现。

   参数:
   - store:     Store 实例
   - namespace: 命名空间
   - items:     [{:key k :value v} ...] 列表

   返回:
   记录列表"
  [store namespace items]
  (mapv (fn [{:keys [key value]}]
          (proto/put store namespace key value))
        items))

(defn default-get-batch
  "批量获取的默认实现

   参数:
   - store:     Store 实例
   - namespace: 命名空间
   - keys:      键列表

   返回:
   {key -> value} map"
  [store namespace keys]
  (reduce (fn [result k]
            (if-let [v (proto/get-value store namespace k)]
              (assoc result k v)
              result))
          {}
          keys))

(defn default-delete-batch
  "批量删除的默认实现

   参数:
   - store:     Store 实例
   - namespace: 命名空间
   - keys:      键列表

   返回:
   删除的记录数"
  [store namespace keys]
  (reduce (fn [count k]
            (if (proto/delete store namespace k)
              (inc count)
              count))
          0
          keys))

(defn default-search
  "简单字符串搜索的默认实现

   用于内存和非向量数据库的 Store。

   参数:
   - store:           Store 实例
   - namespace:       命名空间
   - query:           查询 map {:query \"搜索词\"}
   - opts:            选项 {:top-k n}
   - get-all-keys-fn: 获取所有键的函数 (fn [] -> keys)

   返回:
   [{:key k :value v :score s} ...] 列表"
  [store namespace query opts get-all-keys-fn]
  (let [{:keys [top-k] :or {top-k 10}} opts
        query-str (:query query)
        all-keys (get-all-keys-fn)]
    (->> all-keys
         (map (fn [k]
                (when-let [v (proto/get-value store namespace k)]
                  (let [value-str (pr-str v)
                        score (if (and query-str
                                       (.contains ^String value-str ^String query-str))
                                1.0
                                0.0)]
                    {:key k :value v :score score}))))
         (filter some?)
         (filter #(pos? (:score %)))
         (sort-by :score >)
         (take top-k)
         (vec))))

;;; ============================================================
;;; 记录构建辅助函数
;;; ============================================================

(defn make-record
  "创建存储记录

   参数:
   - namespace: 命名空间
   - key:       键
   - value:     值
   - opts:      可选参数
     :created-at 创建时间（默认当前时间）
     :updated-at 更新时间（默认当前时间）

   返回:
   {:namespace :key :value :created-at :updated-at}"
  [namespace key value & {:keys [created-at updated-at]}]
  (let [ts (now)]
    {:namespace namespace
     :key key
     :value value
     :created-at (or created-at ts)
     :updated-at (or updated-at ts)}))

(defn update-record
  "更新现有记录

   保留 created-at，更新 updated-at 和 value。

   参数:
   - existing: 现有记录
   - value:    新值

   返回:
   更新后的记录"
  [existing value]
  (assoc existing
         :value value
         :updated-at (now)))
