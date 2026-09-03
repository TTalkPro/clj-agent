(ns im.ttalk.agent.agui.emit
  "接线：把 agent 跑起来时**已经有的**观察点接到发射器上。

   三个采集点，各自有不可替代的理由（docs/agent-runtime-design.md §4.9）：

   | 采集点 | 拿到什么 | 为什么非它不可 |
   |---|---|---|
   | `:on-token` | 正文 / 思维 token 增量 | 唯一的 token 粒度出口 |
   | `:on-llm-result` callback | ChatResponse——**含 tool-call `:id`** | `:on-tool-call` 的签名是 `(fn [tool-name args])`，`:tool` 链的 ToolRequest 是 `{:function :args :context}`——**两个都没有 id**，而事件流要靠 id 把 start/args/result 串起来。而且 `:tool` 链跑在并行任务里 |
   | `:iteration` 链 | 本轮 delta（带 `:tool-call-id` 的结果消息）+ 折叠后的 context | 每轮一次、在循环线程上、手里有完整的本轮消息 |

   **不新增 callback**，也不改 react——三个点全是现成的。"
  (:require [im.ttalk.agent.agui.event :as event]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.pause :as pause]))

(set! *warn-on-reflection* true)

(defn- tool-result-messages
  "从一批消息里挑出 tool 结果（带 `:tool-call-id`）。"
  [messages]
  (filter #(and (map? %) (msg/tool? %) (:tool-call-id %)) messages))

(defn- emit-tool-results!
  [em messages]
  (doseq [m (tool-result-messages messages)]
    (event/emit! em :tool/result
                 (cond-> {:tool-call-id (:tool-call-id m)
                          :name (:name m)
                          :content (:content m)}
                   (seq (:writes m)) (assoc :writes (:writes m))))))

(defn- emit-state!
  "状态槽快照。两类东西不发：

   1. **框架键**（`ctx/framework-keys`）——`:conversation-id` 是循环自己钉的路由键，
      `:tool/call-id` 是工具调用侧钉的，都不算业务状态；
   2. **不可 EDN 往返的值**——ToolContext 本来就允许装活对象（`pause/strip-unserializable`
      存在的全部理由就是「混进 `:chat-client` 这类活对象是正常的」）。把它整块发出去，
      到 JSON 编码那一步会当场抛，而那时已经在 SSE 流中间了。这里滤掉比在传输层炸掉好。"
  [em context]
  (let [state (into {} (remove (fn [[k v]]
                                 (or (contains? ctx/framework-keys k)
                                     (not (pause/edn-safe? v)))))
                    context)]
    (when (seq state)
      (event/emit! em :state/snapshot {:state state}))))

(defn on-llm-result
  "`:on-llm-result` 回调：收本轮 LLM 的产出。

   顺序按 AG-UI 的块语义：先把这一轮的文本消息收口（流式下 token 已逐条发过，
   这里只补 `:message/ended`；非流式下整段文本在这里一次性补出），再发工具调用
   的 `start → args → end`（结果在工具真跑完之后由 `:iteration` 链发）。"
  [em]
  (fn [response _meta]
    (event/end-message! em (:text response))
    (doseq [tc (response/response-tool-calls response)]
      (event/emit! em :tool/started {:tool-call-id (:id tc) :name (:name tc)})
      (event/emit! em :tool/args {:tool-call-id (:id tc) :args (:args tc)})
      (event/emit! em :tool/ended {:tool-call-id (:id tc)}))))

(defn iteration-filter
  "`:iteration` 链上的采集 filter。

   请求侧：声明本轮的消息 id（真到第一个 token 才发 `:message/started`），
   并补发**上一轮遗留**的工具结果——resume 那半批（审批后执行的 pending 工具）
   不经过本链，它的结果是作为**下一轮的 delta** 进来的，只有在请求侧才捞得到。

   响应侧：发本轮工具结果与状态快照。**必须走 `flt/fmap`**——异步驱动下终端
   返回的是 deferred（filter-chain-design §2.6.4）。"
  [em]
  (flt/create-filter
   ::emit
   :iteration
   (fn [req chain]
     (emit-tool-results! em (:messages req))
     ;; 消息 id 按 **lane** 分道：lane 共用父 run 的 run-id，拿 run-id 打头会让两条
     ;; 并发 lane 的第 0 轮撞成同一个 message-id（契约 4）。
     (event/begin-message! em (str (or (event/lane-id em) (:run-id em)) "-m" (:index req)))
     (flt/fmap (chain req)
               (fn [res]
                 (when-let [msgs (or (:messages res) (:direct-messages res)
                                     ;; 环境类暂停：批已执行，结果被封在快照里
                                     (get-in res [:loop-state :batch-messages]))]
                   (when (not= :continue (:status res))
                     ;; :continue 的 :messages 是**下一轮 delta**，会在下一轮请求侧
                     ;; 发；终态结果里的才是「本轮跑完、没有下一轮」的那批
                     (emit-tool-results! em msgs)))
                 (when-let [c (:context res)] (emit-state! em c))
                 res)))))

(defn token-fn
  "`:on-token` 回调：把增量 token 变成事件。

   `on-token` 收到的是 `{:token \"…\"}` 或 `{:reasoning-token \"…\"}`（无 `:accumulated`，
   要全文自行累积——docs/token-stream-filter-design.md）。"
  [em]
  (fn [t]
    (cond
      (:token t)           (event/emit-token! em :token (:token t))
      (:reasoning-token t) (event/emit-token! em :reasoning-token (:reasoning-token t)))))

(defn compose-callbacks
  "把 agui 的采集回调与用户自己的回调合成一份。

   callbacks 只有一个槽，agui 要占 `:on-llm-result`，用户也想挂自己的——
   合成器就是几行，**不进框架**（design-principles §1：用户几行就等价的不长 API 面）。
   顺序：先用户后 agui，且各自吞异常（`cb/invoke` 的语义在外层照旧生效）。"
  [user-callbacks agui-callbacks]
  (reduce-kv
   (fn [m k agui-cb]
     (if-let [user-cb (get m k)]
       (assoc m k (fn [& args]
                    (try (apply user-cb args) (catch Throwable _ nil))
                    (apply agui-cb args)))
       (assoc m k agui-cb)))
   (or user-callbacks {})
   agui-callbacks))

(defn attach
  "给一个 agent 挂上采集：`:iteration` filter + `:on-llm-result` 回调。

   `create-agent` 明确拒绝 `:filters`（agent 层只暴露 `:callbacks`），所以这里
   走 `flt/with-filters` 在**已建好的 ChatClient** 上追加——它同步重编 hooks，
   比 `assoc :filters` 少一次每 invoke 的重编译。"
  [agent em]
  (-> agent
      (update :chat-client
              (fn [cc] (flt/with-filters cc (conj (vec (:filters cc)) (iteration-filter em)))))
      (update :callbacks compose-callbacks {:on-llm-result (on-llm-result em)})))
