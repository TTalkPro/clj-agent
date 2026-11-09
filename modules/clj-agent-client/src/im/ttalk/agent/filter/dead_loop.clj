(ns im.ttalk.agent.filter.dead-loop
  "死循环提醒（designs/runtime-borrow.md §4；借 Keelson `Advisors/DeadLoopAdvisor.cs`，
   它又借 keel `dead-loop-filter`）。

   模型在两三个调用之间打转（读同一个文件、查同一个状态），每轮都**成功**，所以失败保护
   （`filter.tool-guard`）管不到。本 filter 记最近 `:window` 轮的调用指纹，最近 `:recent`
   轮里不超过 `:distinct` 种、且这几轮没有真进展 → 给**下一轮**挂一条临时提醒
   （react 的 `:notes` 挂点：只附在那一次请求末尾，不进 ChatMemory）。

   ⛔ **只提醒、不阻断**：误阻断会把正在做的事拦腰截断，漏检只是多烧几轮 token
   （上面还有 max-iterations 与 tool-guard）。提醒过就清空窗口。

   挂法：`:iteration` + `:turn`（后者在新 turn 开始时清零状态）。状态按 conversation-id
   放在 filter 闭包里——只是提醒，不进暂停快照，丢了无害。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.filter.tool-guard :as tool-guard]))

(set! *warn-on-reflection* true)

(def default-reminder
  (str "你最近几轮一直在用相同的工具和参数打转，没有新的进展。"
       "停下来想一想：换一种办法、换参数，或者基于已有信息直接给出结论。"))

(defn round-print
  "一轮调用的指纹：每个调用「名字:规范化参数」截前 100 字符，排序后拼接。"
  [calls]
  (->> calls
       (map (fn [{:keys [name args]}]
              (let [p (str name ":" (tool-guard/canonical (or args {})))]
                (if (> (count p) 100) (subs p 0 100) p))))
       sort
       (str/join "|")))

(defn- default-progress?
  "缺省进展判据：本轮有调用成功、且其工具在 `writes` 集合里（写工具成功 = 状态真的变了）。
   `writes` 缺省为空 → 纯按指纹重复判。"
  [writes calls errors]
  (let [failed (into #{} (map :id) errors)]
    (boolean (some #(and (contains? writes (str (:name %))) (not (failed (:id %)))) calls))))

(defn dead-loop-filter
  "死循环提醒 filter。

   opts:
   - :window    记几轮（缺省 6）
   - :recent    看最近几轮（缺省 4）
   - :distinct  最多几种算打转（缺省 2）
   - :reminder  提醒文案
   - :writes    写工具名集合：这些工具成功 = 有进展，清空窗口
   - :progress? (fn [calls errors] -> bool) 完全自定义进展判据（优先于 :writes）"
  [& {:keys [window recent distinct reminder writes progress?]
      :or {window 6 recent 4 distinct 2 reminder default-reminder}}]
  (let [state (atom {})                 ;; conv-id -> {:seen [最新在前] :pending? bool}
        writes (set (map name writes))
        progress? (or progress? (partial default-progress? writes))
        conv-of (fn [ctx] (or (:conversation-id ctx) ::no-conv))]
    (flt/create-filter :dead-loop
     :turn (fn [req chain]
             (when-not (:resume? req) (swap! state dissoc (conv-of (:context req))))
             (chain req))
     :iteration
     (fn [req chain]
       (let [conv (conv-of (:context req))
             pending? (get-in @state [conv :pending?])
             req (cond-> req
                   pending? (update :notes (fnil conj []) reminder))]
         (when pending? (swap! state assoc-in [conv :pending?] false))
         (flt/fmap
          (chain req)
          (fn [result]
            (when (and (= :continue (:status result)) (seq (:calls result)))
              (let [{:keys [calls errors]} result]
                (swap! state update conv
                       (fn [{:keys [seen] :as st}]
                         (if (progress? calls errors)
                           {:seen [] :pending? false}
                           (let [seen (vec (take window (cons (round-print calls) seen)))
                                 stuck? (and (>= (count seen) recent)
                                             (<= (count (set (take recent seen))) distinct))]
                             (if stuck?
                               {:seen [] :pending? true}
                               (assoc st :seen seen :pending? false))))))))
            result)))))))
