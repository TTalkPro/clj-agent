(ns im.ttalk.agent.filter.normalize
  "消息规范化补全（designs/runtime-borrow.md §12；借 Keelson `MessageNormalizer` /
   `MessagePruner`，二者又借 Codex `normalize_history` 与 keel `prune-messages`）。

   发给模型之前把历史修成 provider 一定收的形状——**只改请求视图，store 不动**：

   1. 删**孤儿结果**：找不到对应 assistant 调用的 tool 消息（窗口裁剪 / 手拼历史会产生，
      OpenAI / Anthropic 一律拒）；
   2. 补**悬空调用**：非末条 assistant 的 tool-call 没有结果 → 紧跟在其结果串之后补一条
      固定文本的结果（固定文本 → 同一份历史规范化多少次都字节相同，提示缓存照中）。
      react 的 `heal-dangling-tool-calls!` 在 turn 开始时给 store 补，这里管的是请求视图；
   3. 模型不收的**媒体**换占位文字（`:images? false` / `:files? false`）；
   4. `:thinking :before-current-turn` 时去掉**本轮之前**的 assistant 思考块（`:blocks`）——
      本轮工具往返里的思考 Anthropic / DeepSeek 要求原样回传，更早的它们本就忽略。

   `normalize-filter` 应注册在 `:filters` 的**最后**（memory / compaction 之后，紧贴模型）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model.message :as msg]))

(set! *warn-on-reflection* true)

(def missing-result-text "已取消（该工具调用没有结果）")

(defn- reminder? [s] (str/starts-with? (str/triml (str s)) "<system-reminder>"))

(defn- real-user? [m]
  (and (msg/user? m)
       (let [c (:content m)]
         (cond
           (string? c) (and (not (str/blank? c)) (not (reminder? c)))
           (sequential? c) (some #(or (string? %) (#{:text "text"} (:type %))) c)
           :else false))))

(defn- last-real-user-index [msgs]
  (or (last (keep-indexed (fn [i m] (when (real-user? m) i)) msgs)) 0))

(defn- drop-orphans [msgs]
  (let [calls (into #{} (comp (filter msg/assistant?) (mapcat :tool-calls) (map :id)) msgs)]
    (filterv #(or (not (msg/tool? %)) (contains? calls (:tool-call-id %))) msgs)))

(defn- fill-missing [msgs missing-text]
  (let [results (into #{} (keep :tool-call-id) msgs)
        n (count msgs)]
    (loop [i 0, out []]
      (if (>= i n)
        out
        (let [m (nth msgs i)]
          (if (and (msg/assistant? m) (seq (:tool-calls m)) (< i (dec n)))
            (let [missing (remove #(contains? results (:id %)) (:tool-calls m))
                  ;; 紧跟其后的那一串 tool 消息原样搬过去，补的结果接在串尾
                  run-end (loop [j (inc i)] (if (and (< j n) (msg/tool? (nth msgs j))) (recur (inc j)) j))]
              (recur (long run-end)
                     (-> out (conj m)
                         (into (subvec (vec msgs) (inc i) run-end))
                         (into (map #(msg/tool-result (:id %) (:name %) missing-text)) missing))))
            (recur (inc i) (conj out m))))))))

(defn- replace-media [msgs images? files?]
  (if (and images? files?)
    msgs
    (mapv (fn [m]
            (let [c (:content m)]
              (if (sequential? c)
                (assoc m :content
                       (mapv (fn [p]
                               (if (and (map? p) (= :file (:type p)))
                                 (let [img? (some-> (:media-type p) (str/starts-with? "image/"))]
                                   (cond
                                     (and img? (not images?)) {:type :text :text "[图片已省略：模型不支持图片输入]"}
                                     (and (not img?) (not files?)) {:type :text :text "[文件已省略：模型不支持文件输入]"}
                                     :else p))
                                 p))
                             c))
                m)))
          msgs)))

(defn- prune-thinking [msgs mode]
  (case mode
    :all (mapv #(dissoc % :blocks) msgs)
    :before-current-turn
    (let [k (last-real-user-index msgs)]
      (vec (map-indexed (fn [i m] (if (and (< i k) (:blocks m)) (dissoc m :blocks) m)) msgs)))
    msgs))

(defn normalize-messages
  "规范化一份中立消息序列（纯函数，不改入参）。

   opts:
   - :drop-orphans? 删孤儿结果（缺省 true）
   - :fill-missing? 补悬空调用（缺省 true）
   - :missing-text  补出来的结果文本（缺省 `missing-result-text`）
   - :images?       模型收不收图片（缺省 true；false → 占位文字）
   - :files?        模型收不收非图片文件（缺省 true）
   - :thinking      :keep（缺省）| :before-current-turn | :all"
  ([msgs] (normalize-messages msgs nil))
  ([msgs {:keys [drop-orphans? fill-missing? missing-text images? files? thinking]
          :or {drop-orphans? true fill-missing? true missing-text missing-result-text
               images? true files? true thinking :keep}}]
   (cond-> (vec msgs)
     drop-orphans? drop-orphans
     fill-missing? (fill-missing missing-text)
     true (replace-media images? files?)
     true (prune-thinking thinking))))

(defn normalize-filter
  "规范化 `:chat` filter（opts 同 `normalize-messages`）。注册在 `:filters` 最后。"
  ([] (normalize-filter nil))
  ([opts]
   (flt/create-filter :normalize
    :chat (fn [req chain]
            (chain (flt/update-messages req normalize-messages opts))))))
