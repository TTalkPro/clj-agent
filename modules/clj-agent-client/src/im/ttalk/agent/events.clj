(ns im.ttalk.agent.events
  "回合生命周期事件 + 指标挂点（designs/runtime-borrow.md §11；借 Keelson
   `AgentEvents.cs` / `AgentMetrics.cs`，它们又借 Codex 的 TurnStarted / TokenCount /
   `codex.turn.ttft`）。

   **一个封闭的小集合**：UI / 遥测从一处拿到回合、轮次、LLM、工具始末与用量，不必
   各自从零散回调里拼。出口是 callbacks 的 `:on-event (fn [event])`——react 在关键节点
   直接触发（`react/emit-event!`），simple-agent 的 `:callbacks` 原样透传。

   | `:type` | 键 |
   |---|---|
   | `:turn/started`      | `:resumed?` |
   | `:iteration/started` | `:index` |
   | `:llm/finished`      | `:index :elapsed-ms :ttft-ms(仅流式) :usage :finish-reason` |
   | `:usage`             | `:index :round :total`（本轮 / 本回合累计） |
   | `:tool/started`      | `:index :call-id :name :args` |
   | `:tool/finished`     | `:index :call-id :name :status :error-class? :elapsed-ms` |
   | `:turn/finished`     | `:status :elapsed-ms :usage :stopped-by? :error?` |

   每个事件都带 `:type` `:ts`（毫秒），以及回调元数据里的 `:conversation-id` / `:run-id`。
   `:tool/finished` 的 `:status` ∈ `:ok :error :rejected :replied`（gate 拒绝 / 答复即结果
   也有始末）。⚠️ 并发引擎下工具事件可能来自不同线程；出口抛的异常被吞掉（UI 的问题
   不打断回合）。零依赖：要接 OpenTelemetry / Micrometer 自己在 `:on-event` 里转。")

(set! *warn-on-reflection* true)

(def event-types
  "封闭事件集——新增类型须同步改本 ns 文档与 designs/runtime-borrow.md §11。"
  #{:turn/started :iteration/started :llm/finished :usage
    :tool/started :tool/finished :turn/finished})

(defn- hist-add [h v]
  (if (number? v)
    (-> h (update :count (fnil inc 0)) (update :sum (fnil + 0) v)
        (update :max (fnil max v) v))
    h))

(defn metrics-collector
  "现成的指标聚合器（零依赖）：`{:on-event f :snapshot (fn [] -> map) :reset! (fn [])}`。

   snapshot：
   {:turns n :llm-calls n :tool-calls n :tool-errors n
    :llm-ms {:count :sum :max} :ttft-ms {...} :tool-ms {...}
    :tokens {:input-tokens n :output-tokens n ...}
    :by-tool {\"name\" {:calls n :errors n :ms {...}}}}"
  []
  (let [init {:turns 0 :llm-calls 0 :tool-calls 0 :tool-errors 0
              :llm-ms {} :ttft-ms {} :tool-ms {} :tokens {} :by-tool {}}
        st (atom init)]
    {:on-event
     (fn [{:keys [type] :as e}]
       (swap! st
              (fn [m]
                (case type
                  :turn/finished (update m :turns inc)
                  :llm/finished (cond-> (-> m (update :llm-calls inc)
                                            (update :llm-ms hist-add (:elapsed-ms e))
                                            (update :tokens #(merge-with (fn [a b] (if (and (number? a) (number? b)) (+ a b) b))
                                                                         % (:usage e))))
                                  (:ttft-ms e) (update :ttft-ms hist-add (:ttft-ms e)))
                  :tool/finished (let [err? (= :error (:status e))
                                       nm (str (:name e))]
                                   (-> m
                                       (update :tool-calls inc)
                                       (cond-> err? (update :tool-errors inc))
                                       (update :tool-ms hist-add (:elapsed-ms e))
                                       (update-in [:by-tool nm :calls] (fnil inc 0))
                                       (cond-> err? (update-in [:by-tool nm :errors] (fnil inc 0)))
                                       (update-in [:by-tool nm :ms] hist-add (:elapsed-ms e))))
                  m))))
     :snapshot (fn [] @st)
     :reset! (fn [] (reset! st init) nil)}))
