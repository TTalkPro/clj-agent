(ns im.ttalk.agent.agui.registry
  "多 agent 注册表：agent id → runtime（designs/agui-borrow.md §4，对照 Keelson
   `AguiRegistry.cs`）。

   为什么要有：AG-UI 前端按 id 找 agent（`/info` 的 `agents` 是字典、请求路径是
   `/agent/:id/run`），而一个服务挂多台 agent（便宜模型的「客服」+ 带敏感工具的
   「运维」）是常规用法。没有这张表时路由只能写死一台，路径里的 id 被忽略——
   请求 `ops` 跑的是 `default`，**静默串台**。

   **纯数据 + 函数，零 web 依赖**（design-principles §2）：找不到 agent 回 404 是
   路由的事，这里只回 nil。

   ```clojure
   (def reg (registry [{:id \"default\" :runtime rt1 :description \"通用助手\"}
                       {:id \"ops\" :runtime rt2 :suggest? true}]))
   (find-agent reg \"ops\")   ;; => 条目 map
   (stop! reg \"ops\" \"t1\")   ;; => true / false
   ```

   条目是**开放 map**：除下面这几个键，路由层自己的东西（服务端工具名、
   `/suggest` 要的 agent 配置、入站插件…）照样放在条目里，本 ns 不解释。

   - `:id`          **必填**，非空字符串，全表唯一
   - `:runtime`     内置驱动时**必填**，`agui.runtime/runtime` 的返回值（多台可共享一个）。
                    自带 run 驱动的 agent 不必塞占位 runtime：`:external? true` 即可（run / stop / connect 由
                    clj-agent-server 的路由选项或宿主自己的处理器接管）。
   - `:external?`   true = 这台没有 runtime，只有名录与元数据；缺省 false
   - `:description` 进 `/info`
   - `:suggest?`    这台支持无状态 `/suggest`（`/info.suggestions` = 任一台支持）
   - `:stop`        可选 `(fn [{:keys [runtime thread-id agent]}] bool)`——这台自己
                    决定怎么停（例如拒绝停某些线程）。缺省 `runtime/stop!`，原因 `:stop`"
  (:require [clojure.string :as str]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]))

(set! *warn-on-reflection* true)

(defn- check-entry! [{:keys [id runtime external?] :as entry}]
  (when-not (map? entry)
    (throw (ex-info "registry 条目必须是 map" {:entry entry})))
  (when-not (and (string? id) (not (str/blank? id)))
    (throw (ex-info "registry 条目需要非空字符串 :id" {:entry (dissoc entry :runtime)})))
  (if (true? external?)
    (when (contains? entry :runtime)
      (throw (ex-info (str "registry 条目 " id " 标了 :external? true，就不要再给 :runtime")
                      {:id id})))
    (when-not (and (map? runtime) (contains? runtime :conversations))
      (throw (ex-info (str "registry 条目 " id " 需要 :runtime（agui.runtime/runtime 的返回值），"
                           "自带驱动则给 :external? true")
                      {:id id}))))
  (when-let [f (:stop entry)]
    (when-not (fn? f)
      (throw (ex-info (str "registry 条目 " id " 的 :stop 必须是函数") {:id id}))))
  entry)

(defn registry
  "由条目序列建注册表。**装配期抛**（不是运行期兜）：空表、条目缺 `:id` /
   `:runtime`、id 重名——两台同名的表现是「`/info` 列出来一台、跑的是另一台」。

   返回值不可变；要增删 agent 就重建一份（没有热插拔的真实场景）。"
  [entries]
  (let [entries (vec entries)]
    (when (empty? entries)
      (throw (ex-info "registry 至少要一台 agent" {})))
    (run! check-entry! entries)
    (when-let [dups (seq (for [[id n] (frequencies (map :id entries)) :when (> n 1)] id))]
      (throw (ex-info (str "registry 的 agent id 重名: " (str/join ", " dups)) {:ids (vec dups)})))
    {::registry true
     :agents entries
     :by-id (into {} (map (juxt :id identity)) entries)}))

(defn registry?
  [x]
  (boolean (and (map? x) (::registry x))))

(defn agent-ids
  "按加入顺序（`/info` 名录的顺序因此稳定）。"
  [reg]
  (mapv :id (:agents reg)))

(defn agents
  "全部条目，按加入顺序。"
  [reg]
  (:agents reg))

(defn find-agent
  "按 id 取条目；没有返回 nil（路由据此回 404 `agent_not_found`）。"
  [reg id]
  (when (string? id) (get (:by-id reg) id)))

(defn default-agent
  "第一台（单 agent 部署里就是唯一那台）。"
  [reg]
  (first (:agents reg)))

(defn supports-suggest?
  [entry]
  (boolean (:suggest? entry)))

(defn stop!
  "让 `agent-id` 那台停 `thread-id` 上在跑的 run。返回 true 只表示**取消已登记**
   （同 `runtime/stop!`）。没这台 agent / 没在跑 → false。

   条目给了 `:stop` 就听它的；没给就是 `runtime/stop!`，原因 `:stop`（用户按停）。"
  [reg agent-id thread-id]
  (if-let [entry (find-agent reg agent-id)]
    (boolean
     (if-let [f (:stop entry)]
       (f {:runtime (:runtime entry) :thread-id thread-id :agent entry})
       (and thread-id (some-> entry :runtime (rt/stop! thread-id nil :stop)))))
    false))

(defn info
  "`/info` 的响应体：`codec/run-info` + 每台的 description +
   `suggestions` = **任一台**支持（Keelson `AguiRegistry.Info` 同口径）。

   `opts` 其余键原样交给 `codec/run-info`（插件能力位、线程面…）。"
  ([reg] (info reg nil))
  ([reg opts]
   (let [body (codec/run-info (agent-ids reg)
                              (assoc opts
                                     :descriptions (merge (into {} (keep (fn [{:keys [id description]}]
                                                                           (when description [id description])))
                                                          (:agents reg))
                                                          (:descriptions opts))
                                     :suggestions? (boolean (some supports-suggest? (:agents reg)))))]
     ;; 内置 runtime 的缺省能力位由 codec 判；外部驱动本层不知道它能不能审批 / 收前端工具，
     ;; 不覆盖就是谎报。条目显式给了 :capabilities 才按装配方宣告。
     (update body :agents (fn [m]
                            (reduce (fn [m {:keys [id external? capabilities]}]
                                      (if external?
                                        (assoc-in m [id :capabilities] (or capabilities {}))
                                        m))
                                    m
                                    (:agents reg)))))))

(defn runtimes
  "去重后的 runtime（多台共享一个时只出现一次）。"
  [reg]
  (into [] (comp (map :runtime) (remove nil?) (distinct)) (:agents reg)))

(defn shutdown!
  "每个**不同**的 runtime 关一次。"
  [reg]
  (run! rt/shutdown! (runtimes reg))
  nil)
