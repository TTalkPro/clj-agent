(ns im.ttalk.agent.filter.tool-guard
  "同参失败保护 + 失败摘除（designs/runtime-borrow.md §3；借 Keelson
   `ToolCallingAdvisor.RepeatLimit`/`Account` 与 `FailureDisableAdvisor` 的 Gate 模式）。

   两种病：
   1. **原样重试**：模型拿同一组错参数反复调用，每次同样失败，烧光迭代预算。
      同指纹（工具名 + 规范化参数）第 `:repeat-limit` 次失败 → 结果换成「别再原样重试」
      + 原报错；第 `:repeat-stop-limit` 次 → 「到此为止」并**停回合**（经 `:loop/stop`）。
      同指纹成功一次清零。
   2. **后端挂了**：某工具连续失败 `:disable-threshold` 次 → 本回合**门禁拒绝**：
      工具**仍在工具列表里**（请求前缀字节不变，提示缓存照中），调用直接回
      「已拒绝执行」而不执行。成功一次清零。
   3. **业务失败扎堆**（同 Keelson FailureDisableAdvisor 的两本账）：`:classify` 回 `:reported`
      （工具正常返回、内容表示失败 / 被拒）⇒ 只记「任意失败」，不进门禁；某工具连续任意失败
      `:report-threshold` 次 ⇒ 结果换成「别原样重试」提醒，然后这本账清零（每 n 次提醒一次）。
      `:raised`（系统错误）= 旧的 `:failure`：两本账都记、到 `:disable-threshold` 门禁。
      缺省 classify 只回 `:failure` / `:success` ⇒ 不改 classify 的宿主行为不变。

   **计数放在 context 的框架槽 `:tool-guard/state`**，经本 filter 返回的 `:writes`
   写入（`ctx/framework-slots` 内建两层 merge：同批不同指纹各写各的格）。context
   本就进暂停快照，所以计数随 HITL 暂停保存、resume 后接着数；simple-agent 每个 turn
   新建 context，作用域天然是**一个回合**。该键在 `ctx/framework-keys` 里，AG-UI
   状态快照不会带出去。

   缺省不挂 = 行为不变。"
  (:require [im.ttalk.agent.filter :as flt]))

(set! *warn-on-reflection* true)

(defn canonical
  "参数规范化：map 键递归排序后 `pr-str`（键序不同、同值的两次调用指纹相同）。"
  [x]
  (letfn [(sortify [v]
            (cond
              (map? v) (into (sorted-map-by (fn [a b] (compare (str a) (str b))))
                             (map (fn [[k x]] [k (sortify x)]) v))
              (sequential? v) (mapv sortify v)
              :else v))]
    (pr-str (sortify x))))

(defn fingerprint
  "调用指纹：工具名 + 规范化参数。"
  [tool-name args]
  (str (name tool-name) "\u0000" (canonical (or args {}))))

(defn default-classify
  "缺省分类：有 `:error` 且不是子 agent 挂起（那不是失败）→ :failure，否则 :success。"
  [_req resp]
  (if (and (:error resp) (not= :subagent-suspended (get-in resp [:error :class])))
    :failure
    :success))

(defn- repeat-note [tool-name n orig]
  (str "这是第 " n " 次以完全相同的参数调用 " tool-name " 并失败。不要再原样重试："
       "请修改参数或换一种办法。原报错：" orig))

(defn- stop-note [tool-name n orig]
  (str "工具 " tool-name " 以相同参数已连续失败 " n " 次，本回合到此为止。原报错：" orig))

(defn- report-note [tool-name n orig]
  (str "工具 " tool-name " 已连续失败 " n " 次。不要原样重试：请检查参数、换一种办法，或向用户说明情况。原报错：" orig))

(defn- gated-note [tool-name n]
  (str "已拒绝执行：工具 " tool-name " 本回合已停用（连续失败 " n " 次）。"
       "它仍在工具列表里，但本回合调用会被直接拒绝；请换别的办法完成任务。"))

(defn tool-guard-filter
  "失败保护 `:tool` filter。

   opts:
   - :repeat-limit       同指纹第几次失败时提醒（缺省 3；≤0 关闭同参保护）
   - :repeat-stop-limit  同指纹第几次失败时停回合（缺省 repeat-limit+1；小于 limit 按 limit）
   - :disable-threshold  同工具连续（系统）失败几次后门禁拒绝（缺省 3；nil/≤0 关闭）
   - :report-threshold   同工具连续任意失败几次、碰上业务失败时提醒「别原样重试」（缺省 5；nil/≤0 关闭）
   - :classify           (fn [tool-request tool-response] -> :success|:failure|:raised|:reported|:ignore)
                         `:failure`（缺省分类的回法，同旧行为）/ `:raised`：系统错误，计入门禁；`:raised` 另记任意失败。
                         `:reported`：业务失败，只记任意失败（与同参保护），不进门禁
   - :repeat-note / :stop-note / :report-note  (fn [tool-name n 原报错] -> 文案) 覆盖缺省文案
   - :gated-note         (fn [tool-name n] -> 文案)"
  [& {:keys [repeat-limit repeat-stop-limit disable-threshold report-threshold classify]
      :or {repeat-limit 3 disable-threshold 3 report-threshold 5}
      :as opts}]
  (let [classify (or classify default-classify)
        repeat-note* (or (:repeat-note opts) repeat-note)
        stop-note* (or (:stop-note opts) stop-note)
        report-note* (or (:report-note opts) report-note)
        gated-note* (or (:gated-note opts) gated-note)
        repeat-on? (and repeat-limit (pos? repeat-limit))
        stop-at (when repeat-on? (max repeat-limit (or repeat-stop-limit (inc repeat-limit))))
        disable-on? (and disable-threshold (pos? disable-threshold))
        report-on? (and report-threshold (pos? report-threshold))]
    (flt/create-filter :tool-guard
     :tool (fn [req chain]
             (let [st (get (:context req) :tool-guard/state {})
                   tname (str (some-> (get-in req [:function :name]) name))
                   fp (fingerprint tname (:args req))
                   fails (long (get-in st [:fails tname] 0))
                   anys (long (get-in st [:any tname] 0))
                   repeats (long (get-in st [:repeats fp] 0))]
               (if (and disable-on? (>= fails disable-threshold))
                 {:result (gated-note* tname fails)}
                 (flt/fmap
                  (chain req)
                  (fn [resp]
                    (let [cls (classify req resp)
                          orig #(str (or (get-in resp [:error :message]) (:result resp)))
                          ;; 同参保护：任何一类失败都算（停回合 > 提醒）；没触发 ⇒ nil
                          repeat-hit (fn [n w]
                                       (cond
                                         (and repeat-on? (>= n stop-at))
                                         (let [note (stop-note* tname n (orig))]
                                           (-> resp
                                               (assoc :result note)
                                               (update :writes merge w
                                                       {:loop/stop {:reason note :by :tool-guard}})))

                                         (and repeat-on? (>= n repeat-limit))
                                         (-> resp (assoc :result (repeat-note* tname n (orig)))
                                             (update :writes merge w))))]
                      (case cls
                        :success
                        (if (or (pos? fails) (pos? repeats) (pos? anys))
                          (update resp :writes merge
                                  {:tool-guard/state (cond-> {:repeats {fp 0} :fails {tname 0}}
                                                       (pos? anys) (assoc :any {tname 0}))})
                          resp)

                        (:failure :raised)
                        (let [n (inc repeats)
                              w {:tool-guard/state (cond-> {:repeats {fp n} :fails {tname (inc fails)}}
                                                     (= :raised cls) (assoc :any {tname (inc anys)}))}]
                          (or (repeat-hit n w) (update resp :writes merge w)))

                        :reported
                        (let [n (inc repeats)
                              a (inc anys)
                              w {:tool-guard/state {:repeats {fp n} :any {tname a}}}]
                          (or (repeat-hit n w)
                              (if (and report-on? (>= a report-threshold))
                                (-> resp
                                    (assoc :result (report-note* tname a (orig)))
                                    (update :writes merge {:tool-guard/state {:repeats {fp n} :any {tname 0}}}))
                                (update resp :writes merge w))))

                        resp))))))))))

(defn guard-state
  "读 context 里的失败保护计数（测试 / 观察用）。"
  [ctx]
  (get ctx :tool-guard/state {}))
