(ns copilotkit.http-kit-routes
  "把 agui runtime 挂成 CopilotKit v2 前端直连的 HTTP 端点（http-kit 版）。

   **这一层刻意不在库里**（design-principles §2：agent 框架是一个库，不是 web
   应用）。库给的是「凭 conversation-id 找到 run」和「订阅事件」两件事；
   SSE 帧、路由、CORS、鉴权是你的 web 栈的事，就长这样：

   依赖（除 core / client / agui 外）：
     http-kit/http-kit  {:mvn/version \"2.8.0\"}
     cheshire/cheshire  {:mvn/version \"5.12.0\"}

   CopilotKit v2 前端只用四条路由（其余 threads / memories / suggest /
   transcribe 是它的产品功能，缺席即降级）：

     GET  {base}/info                       → 有哪些 agent（agents 是**字典**）
     POST {base}/agent/:id/suggest          → 无状态建议 run（可选，见 handle-suggest）
     GET  {base}/threads …                  → 线程只读面（可选，见 handle-threads）
     POST {base}/agent/:id/run              → 起 run，SSE 回事件流
     POST {base}/agent/:id/connect          → 只订阅（重连/旁观），SSE
     POST {base}/agent/:id/stop/:threadId   → 停（**threadId 在路径里，没有请求体**）

   多 agent：`start-registry!` 收一张 `agui.registry`，路径里的 `:id` 按表分派，
   找不到回 404 `agent_not_found`（designs/agui-borrow.md §4）。单 agent 的
   `start!` 签名不变（内部建一台 `\"default\"` 的表）。

   错误一律 `{\"error\": <码>, \"message\": <话>}`：坏 JSON / 缺 threadId、runId →
   400，请求体超限 → 413，线程面缺槽 → 501（designs/agui-borrow.md §7）。
   SSE 流闲够 `:heartbeat-ms`（缺省 15s）就发一条注释帧保活（§3）。

   这四条的形状全部对着 `packages/core/src/agent.ts` 与 `agent-registry.ts` 抄的
   ——联调时照出来两处：`/info` 的 agents 是字典（给数组前端会去请求
   `/agent/0/run`），`/stop` 的 threadId 是**路径段**不是请求体。

   前端这样接：
     <CopilotKit runtimeUrl=\"http://localhost:3000/api/copilotkit\"> … </CopilotKit>

   运行（REPL）：(start! 3000)"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.state :as agui-state]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :as tool]
            [org.httpkit.server :as hk])
  (:import [java.io InputStream]
           [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture
            ThreadFactory TimeUnit]))

;;; ============================================================
;;; SSE
;;; ============================================================

(def ^:private allowed-origins
  "开发期的前端源白名单：Next dev 默认起在 :3000，端口被占时它自己退到 :3002，
   两个都得认。真部署换成你的域名。"
  #{"http://localhost:3000"  "http://127.0.0.1:3000"
    "http://localhost:3002"  "http://127.0.0.1:3002"})

(def ^:private ^:dynamic cors-headers
  "当前请求的 CORS 头；`handler` 在每次请求最外层按 Origin 绑一次（默认值是
   没有 Origin 时的退化形态）。做成动态变量是为了不用把 request 穿到每一个
   `json-response` 调用点上去。

   **方法表里必须有 `DELETE` 与 `PATCH`**：`DELETE /threads/:id`、
   `PATCH /threads/:id`（上游改名 / 取消归档走它）都不是简单请求，浏览器先发
   预检，表里没有它们那几条路由在浏览器侧根本走不通（服务端那段 cond 永远等
   不到请求）。"
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Methods" "GET, POST, PATCH, DELETE, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Last-Event-ID, X-CopilotKit-Runtime-Client-GQL-Version"
   "Access-Control-Max-Age" "86400"
   "Vary" "Origin"})

(defn- cors-for
  "按请求的 Origin 算 CORS 头。

   白名单内**回声 Origin 并给 `Allow-Credentials`**——`*` 与 credentials 互斥，
   前端一旦带上 cookie，回 `*` 的响应浏览器直接判死。名单外退回 `*`（本例不用
   credentials 时照常可用）。回声了 Origin 就得给 `Vary: Origin`，否则中间缓存
   会把 A 源的响应喂给 B 源。"
  [request]
  (let [origin (get-in request [:headers "origin"])]
    (if (contains? allowed-origins origin)
      (assoc cors-headers
             "Access-Control-Allow-Origin" origin
             "Access-Control-Allow-Credentials" "true")
      cors-headers)))


(def ^:private sse-headers
  {"Content-Type" "text/event-stream; charset=utf-8"
   "Cache-Control" "no-cache, no-transform"
   "Connection" "keep-alive"
   "X-Accel-Buffering" "no"})          ;; nginx 别缓冲，否则「流式」变「一次性」

(defn- sse-frame [{:keys [seq]} agui]
  ;; `id:` 放会话级 `:seq`——浏览器重连时原样回传为 `Last-Event-ID`，
  ;; 那正好就是 subscribe 的 `:since`。这是**偏移续传**，不是全量重放。
  (str "id: " seq "\ndata: " (json/generate-string agui) "\n\n"))

;;; ------------------------------------------------------------
;;; 心跳（designs/agui-borrow.md §3）
;;; ------------------------------------------------------------

(def default-heartbeat-ms
  "缺省 15 秒（Keelson `AguiServerOptions.Heartbeat` 同值）：比 nginx 缺省的
   `proxy_read_timeout 60s` 与常见云 LB 的 30–60s 空闲超时都短一截。"
  15000)

(def default-heartbeat-frame
  "SSE **注释帧**：`EventSource` 与 `@ag-ui/client` 的解析器都直接跳过以 `:` 开头
   的行，于是它只保活、不进事件校验。⛔ 别换成 `data: {}`——那会被当成一条
   AG-UI 事件去校验，`{}` 不是合法事件。前端看门狗真要看得见心跳，自己给
   `:heartbeat-frame`。"
  ": clj-agent-alive\n\n")

(defonce ^:private heartbeat-pool
  ;; **所有连接共用一根 daemon 线程**，不是每连接一根：心跳只是「到点看一眼、
  ;; 闲了就写一行」，一根线程扛得住成千上万条流。懒建：不开心跳就不起
  (delay (Executors/newSingleThreadScheduledExecutor
          (reify ThreadFactory
            (newThread [_ r]
              (doto (Thread. ^Runnable r "agui-sse-heartbeat") (.setDaemon true)))))))

(defn heartbeat!
  "给一条 SSE 流起心跳，返回**停止函数**。

   **闲够才发**：`last-sent`（atom，毫秒）由发真实事件的那一侧刷新；每个 tick
   距上次发送不足 `period-ms` 就什么都不写——忙的流上没有一个多余字节。
   tick 间隔取 `period-ms` 的一半，最长静默因此不超过 1.5 个周期。

   - `:period-ms` 0 / nil = 关（返回空操作的停止函数）
   - `:send!`     `(fn [string])`，往连接上写（写失败——连接已断——无所谓）
   - `:frame`     字符串或 `(fn [] string)`，缺省 `default-heartbeat-frame`
   - `:last-sent` 与事件发送共享的 atom；不给就自己建一个
   - `:now`       `(fn [] ms)`，可注入（测试）"
  [{:keys [period-ms send! frame last-sent now]}]
  (let [period (long (or period-ms 0))]
    (if-not (pos? period)
      (fn [] nil)
      (let [now (or now #(System/currentTimeMillis))
            last-sent (or last-sent (atom (now)))
            tick (max 1 (quot period 2))
            ^ScheduledExecutorService pool @heartbeat-pool
            ^ScheduledFuture fut
            (.scheduleAtFixedRate
             pool
             ^Runnable
             (fn []
               (try
                 (when (>= (- (long (now)) (long @last-sent)) period)
                   (reset! last-sent (now))
                   (send! (cond (fn? frame) (frame)
                                (string? frame) frame
                                :else default-heartbeat-frame)))
                 (catch Throwable _ nil)))
             tick tick TimeUnit/MILLISECONDS)]
        (fn [] (.cancel fut false) nil)))))

(defn- open-sse!
  "发 SSE 响应头、起心跳。返回 `{:send! (fn [frame-string]) :close! (fn [])}`；
   连接关掉时（无论哪一侧关）停心跳并调 `on-closed`。

   `:send!` 每发一帧都刷新「最后发送时间」——心跳只在真空期出现。"
  [channel {:keys [heartbeat-ms heartbeat-frame]} on-closed]
  (hk/send! channel {:status 200 :headers (merge cors-headers sse-headers)} false)
  (let [last-sent (atom (System/currentTimeMillis))
        stop-hb (heartbeat! {:period-ms (if (contains? #{nil :default} heartbeat-ms)
                                          default-heartbeat-ms
                                          heartbeat-ms)
                             :frame heartbeat-frame
                             :last-sent last-sent
                             :send! #(hk/send! channel % false)})]
    (hk/on-close channel (fn [_] (stop-hb) (when on-closed (on-closed))))
    {:send! (fn [frame] (reset! last-sent (System/currentTimeMillis)) (hk/send! channel frame false))
     :close! (fn [] (stop-hb) (hk/close channel))}))

(defn- sse-response
  "把一个订阅接到 http-kit 的 channel 上。

   `on-event` 的契约是**立刻返回**——`hk/send!` 正好如此（往 channel 一塞就走）。
   连接关掉时退订；**不取消 run**：断线不该杀掉用户已经发起的那一轮
   （要停请显式调 /stop）。这正是 runtime 存在的理由。

   `close-on-terminal?` 区分两条路：
   - `/run` **要关**——客户端的 `run()` 得在终态处收口，不关它就一直吊着；
   - `/connect` **不关**——它是旁观/重连流，跨 run 连续（`:seq` 本来就是会话级的）。
     HITL 下一次对话由多个 run 组成，在第一个终态就关会把续跑截断（联调实测）。"
  [runtime conv-id since {:keys [close-on-terminal?] :as opts}]
  (fn [request]
    (hk/with-channel request channel
      (let [unsub (volatile! nil)
            {:keys [send! close!]} (open-sse! channel opts (fn [] (when-let [u @unsub] (u))))
            emit (fn [ev]
                   (doseq [agui (codec/->agui-events ev)]
                     (send! (sse-frame ev agui))
                     ;; **终态即关流**：AG-UI 的一条 run 以终态收口，流不关客户端就
                     ;; 一直吊着（联调实测：工具卡片永远停在 inProgress）。
                     ;; 续跑是新的 run，前端用 /connect 接着看。
                     (when (and close-on-terminal? (codec/terminal? agui))
                       (when-let [u @unsub] (u))
                       (close!))))]
        (vreset! unsub (rt/subscribe runtime conv-id
                                     {:since since
                                      :on-event emit
                                      :on-close (fn [_] (close!))}))))))

(defn- sse-frames
  "把**已经算好**的几条 AG-UI 事件当成一条 SSE 流发完就关。

   给「不是 run 的 run」用（MCP UI 的代理调用）：客户端那边仍走
   `HttpAgent`，所以形状还得是一条以终态收口的事件流。"
  [agui-events]
  (fn [request]
    (hk/with-channel request channel
      ;; 发完就关，用不着心跳
      (let [{:keys [send! close!]} (open-sse! channel {:heartbeat-ms 0} nil)]
        (doseq [[i ev] (map-indexed vector agui-events)]
          (send! (sse-frame {:seq i} ev)))
        (close!)))))

(defn- last-event-id
  "浏览器 EventSource 自动重连时带的头；没有就退回请求体里的 since。"
  [request body]
  (some-> (or (get-in request [:headers "last-event-id"]) (:since body))
          str parse-long))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers (merge cors-headers {"Content-Type" "application/json"})
    :body (json/generate-string body)}))

(defn- error-response
  "错误出口：`{\"error\": 码, \"message\": 话}`（Keelson `AguiHttp.WriteError` 同形）。
   码是给程序判的，话是给人看的。"
  [status code message]
  (json-response status {:error code :message message}))

;;; ============================================================
;;; 四条路由
;;; ============================================================

(def default-max-body-bytes
  "请求体上限，缺省 16 MiB（Keelson `AguiServerOptions.MaxBodyBytes` 同值）。
   AG-UI 客户端每次 run 都发全量历史，带图的多模态消息会很大——给得宽，但得有。"
  (* 16 1024 1024))

(def ^:private ^:dynamic *max-body-bytes* default-max-body-bytes)

(defn- read-body*
  "读请求体并解析 JSON。返回 `[:ok 解析结果]`（空体 = `[:ok nil]`）或
   `[:error 响应]`：超限 → 413 `body_too_large`，解析失败 → 400 `bad_json`。

   **多读一个字节判超限**，而不是先整个 slurp 再量——那样上限就形同虚设。"
  [request]
  (let [b (:body request)
        limit (long *max-body-bytes*)
        text (cond
               (nil? b) nil
               (string? b) b
               (instance? InputStream b)
               (let [bs (.readNBytes ^InputStream b (int (min Integer/MAX_VALUE (inc limit))))]
                 (if (> (alength bs) limit)
                   ::too-large
                   (String. bs "UTF-8")))
               :else (slurp b))]
    (cond
      (= ::too-large text)
      [:error (error-response 413 "body_too_large" (str "请求体超过上限 " limit " 字节"))]

      (and (string? text) (> (count text) limit))
      [:error (error-response 413 "body_too_large" (str "请求体超过上限 " limit " 字节"))]

      (str/blank? text)
      [:ok nil]

      :else
      (try
        [:ok (json/parse-string text true)]
        (catch Exception e
          [:error (error-response 400 "bad_json" (str "请求体不是合法 JSON: " (ex-message e)))])))))

(defmacro ^:private with-body
  "读体：成功绑定到 `sym` 执行 body，失败直接回 4xx 响应。"
  [[sym request] & body]
  `(let [[tag# v#] (read-body* ~request)]
     (if (= :error tag#)
       v#
       (let [~sym v#] ~@body))))

(defn- input-error
  "`codec/run-input-problem` → 400 响应（没问题回 nil）。"
  [body opts]
  (when-let [{:keys [error message]} (codec/run-input-problem body opts)]
    (error-response 400 error message)))

(defn- decision-of
  "前端 `respond(...)` 回来的载荷 → resume 决策。

   审批场景下那个载荷是**决策**（\"approved\" / \"rejected\"），不是工具结果——
   这正是它与前端工具的分野：前端工具自己把活干了，回来的是**结果**；审批只是
   点头或摇头，活还在服务端等着干。"
  [content]
  (let [c (str/lower-case (str/trim (str content)))]
    (if (or (str/includes? c "approve") (str/includes? c "同意")
            (= c "true") (= c "yes"))
      "approved"
      "rejected")))

(defn- reason-of
  "载荷里给模型看的拒绝理由：`reason` 优先，其次 `message`；字符串载荷本身就是理由。"
  [payload]
  (let [r (cond
            (map? payload) (or (:reason payload) (:message payload))
            (string? payload) payload)]
    (when (and (string? r) (not (str/blank? r))) r)))

(defn- explicit-false? [v]
  (or (false? v) (and (string? v) (= "false" (str/lower-case (str/trim v))))))

(defn resume-decision
  "AG-UI `resume[]` 条目 → `[decision payload]`，喂给 `rt/resume-run!`
   （designs/agui-borrow.md §1，tasks/G-01.md）。

   `status` 是协议的一等字段（`\"resolved\"` / `\"cancelled\"`），**先看它**：
   取消就是拒绝，没有歧义。`resolved` 才去看载荷——前端可能什么都不带
   （「点了同意」本身就是全部信息），也可能带 `{decision}`（老前端）或
   `{approved, editedArgs?, reason?}`（通用审批客户端，Keelson 同形）。

   | 入参 | decision | payload（client 的 resume 词汇） |
   |---|---|---|
   | `status=cancelled` | `\"rejected\"` | `{:message 理由}`（有才带） |
   | `approved:false`，或 `decision` 判拒 | `\"rejected\"` | `{:message reason}`（有才带） |
   | 批准 + `editedArgs` 是对象 | `\"approved\"` | `{:args editedArgs}`——**整体替换**，不合并 |
   | 其余 | `\"approved\"` | nil |

   ⚠️ **`approved:false` 先于一切判**：从前只认 `decision`，`{approved:false}`
   没有 `decision` 键就落到缺省的「批准」——用户点了拒绝，工具照样执行。

   ⛔ 不把客户端载荷原样透传：它的键名与 client 的 `{:args :message}` 不是一套
   词汇，原样透传等于让客户端往 resume 里塞任意键。"
  [{:keys [status payload]}]
  (let [reject (fn [] (if-let [r (reason-of payload)] ["rejected" {:message r}] ["rejected" nil]))
        approve (fn [] (let [e (when (map? payload) (:editedArgs payload))]
                         (if (map? e) ["approved" {:args e}] ["approved" nil])))]
    (cond
      (= "cancelled" status)                     (reject)
      (map? payload)
      (cond
        (explicit-false? (:approved payload))    (reject)
        (some? (:decision payload))              (if (= "approved" (decision-of (:decision payload)))
                                                   (approve)
                                                   (reject))
        :else                                    (approve))
      (some? payload)                            (if (= "approved" (decision-of payload))
                                                   ["approved" nil]
                                                   (reject))
      :else                                      ["approved" nil])))

(defn- payload-text
  "`resume[].payload` → 交给模型的文本。

   **优先拆 `result`**：我们自己在 `responseSchema` 里就是这么要的
   （`{\"result\": …}`，见 `codec` 的 `frontend-tool-response-schema`），客户端照
   schema 填回来的就是这个形状。不拆的话模型读到的是一层没有意义的包装。

   其余 map 走 JSON —— `(str m)` 会印成 Clojure 字面量（`{:result \"x\"}`），
   模型那边是没见过的方言。"
  [payload]
  (cond
    (nil? payload) nil
    (map? payload) (let [r (or (:result payload) (get payload "result"))]
                     (if (some? r) (str r) (json/generate-string payload)))
    :else (str payload)))

(defn- frontend-reply
  "前端工具那支的 resume 载荷 → 交给模型的**工具结果文本**。

   ⛔ 不能直接 `(str payload)`：取消时协议里 `payload` 本来就可以缺席，`str` 一下
   得到的是**空字符串**——那成了「这次调用的结果」，模型读到一个空结果反而宣布
   「已成功执行」，拒绝往成功的方向被吃掉（实测，见 feedbacks/
   2026-09-04-frontend-tool-pause-looks-like-an-approval.md）。

   所以 `status` 是一等字段，**先看它**（同 `resume-decision` 的口径）：取消就明说
   没执行，让模型知道这一步作废；有载荷才当结果。"
  [resume-entry tool-result]
  (let [text (payload-text (:payload resume-entry))
        content (:content tool-result)]
    (cond
      ;; **取消的框子不能丢**：载荷只是「为什么」，不是「结果」。丢了框子模型会
      ;; 把它当成执行结果；丢了载荷则丢掉用户给的理由——两个都要
      (= "cancelled" (:status resume-entry))
      (str "用户取消了这次调用，该工具**未执行**，没有结果。"
           (when text (str "用户说明：" text)))

      (some? text) text
      (some? content) (str content)
      ;; resolved 但什么都没带：客户端认为「执行完了、没有返回值」——如实说，
      ;; 别让模型把空串当成内容
      :else "该工具已在客户端执行完成，但没有返回任何结果。")))

(defn- bad-resume-entry?
  "AG-UI `ResumeEntry`：`{interruptId, status?, payload?}`，status 只有
   `resolved` / `cancelled` 两种。"
  [e]
  (not (and (map? e)
            (let [id (:interruptId e)]
              (or (and (string? id) (not (str/blank? id))) (number? id)))
            (or (nil? (:status e)) (contains? #{"resolved" "cancelled"} (:status e))))))

(defn- per-call-decision
  "一条 resume 条目 → client 的逐条决策（`{:decisions {id 这个}}` 里的值）。

   服务端工具：载荷是**决策**（`resume-decision`，含 editedArgs → `:args`、理由 →
   `:message`）；前端工具：载荷**即**结果（`:reply`，口径同单条的 `frontend-reply`）。"
  [pending-tool entry server-tool-names]
  (if (contains? server-tool-names (str (:name pending-tool)))
    (let [[d p] (resume-decision entry)]
      (cond-> {:decision (keyword d)}
        (:args p)    (assoc :args (:args p))
        (:message p) (assoc :message (:message p))))
    {:decision :reply :message (frontend-reply entry nil)}))

(defn resume-plan
  "`resume[]` 对着当前暂停算出怎么续（designs/agui-borrow.md §2，tasks/G-02.md）。
   **在起 run 之前**判完所有错，回普通 JSON 4xx——Keelson 是起 run 之后发
   `RUN_ERROR INTERRUPT_*`，我们在路由层就看得见暂停，早判客户端更好处理，也不会
   留下一条只有 `RUN_STARTED` + `RUN_ERROR` 的空 run。

   返回：
   - nil —— 没带 resume，走原来的推断
   - `{:error [status code body-extra]}`
     | 情形 | status | code |
     |---|---|---|
     | resume 不是数组 / 条目形状不对 | 400 | `interrupt_bad_resume`（INTERRUPT_BAD_RESUME） |
     | 没有暂停 | 409 | `no_pending_interrupt` |
     | 一条都对不上（过期重放） | 409 | `interrupt_mismatch` |
     | 对上了、但还夹着不认识的 id | 409 | `interrupt_unknown`（INTERRUPT_UNKNOWN，带 `unknown`） |
     | 多条审批只答了一部分 | 409 | `interrupt_incomplete`（INTERRUPT_INCOMPLETE，带 `missing`） |
   - `{:single entry}` —— 只有一条暂停：走原来的单条路（与 G-01 逐字相同）
   - `{:decisions {id 决策}}` —— 多条：交给 client 的逐条决策（**答全答对**，
     与 client 的校验口径一致；漏答在这里就拦下，不让它变成一次失败的 run）"
  [resume pending server-tool-names]
  (when (and (some? resume) (or (not (sequential? resume)) (seq resume)))
    (cond
      (or (not (sequential? resume)) (some bad-resume-entry? resume))
      {:error [400 "interrupt_bad_resume"
               {:message "resume 必须是 [{interruptId, status: resolved|cancelled, payload?}]"}]}

      (nil? pending)
      {:error [409 "no_pending_interrupt" {:message "这个线程没有在等答复的 interrupt"}]}

      :else
      (let [pts (or (not-empty (vec (:pending-tools pending))) [(:pending-tool pending)])
            multi? (> (count pts) 1)
            ids (if multi?
                  (mapv #(str (get-in % [:tool-call :id])) pts)
                  [(codec/interrupt-id pending)])
            id-set (set ids)
            by-id (reduce (fn [m e] (let [k (str (:interruptId e))]
                                      (if (contains? m k) m (assoc m k e))))
                          {} resume)
            unknown (vec (remove id-set (keys by-id)))
            missing (vec (remove #(contains? by-id %) ids))]
        (cond
          (= (count unknown) (count by-id))
          {:error [409 "interrupt_mismatch"
                   (cond-> {:message "resume 里的 interruptId 对不上当前的暂停（过期的答复？）"
                            :interruptId (first ids)}
                     multi? (assoc :interruptIds ids))]}

          (seq unknown)
          {:error [409 "interrupt_unknown"
                   {:message (str "resume 里有不认识的 interruptId: " (str/join ", " unknown))
                    :unknown unknown :pending ids}]}

          (not multi?)
          {:single (get by-id (first ids))}

          (seq missing)
          {:error [409 "interrupt_incomplete"
                   {:message (str "同一轮有 " (count ids) " 条待审批，还差: " (str/join ", " missing))
                    :missing missing :pending ids}]}

          :else
          {:decisions (into {} (map (fn [p id] [id (per-call-decision p (get by-id id) server-tool-names)])
                                    pts ids))})))))

(declare handle-run*)

(defn handle-run
  "POST {base}/agent/:id/run

   两件值得注意的事：

   1. **历史取服务端权威**（设计文档 §7.3）：前端把它那份完整 messages 发上来，
      我们只取最后一条 user 消息。memory filter 在循环内落库，heal-dangling /
      暂停恢复 / timeline 都依赖服务端历史——认客户端历史等于同时推翻这四样。
   1.5 **审批答复走请求体的 `resume[]`**（interrupt 协议）：上一条 run 以
      `RUN_FINISHED outcome=interrupt` 收口，人点了按钮，客户端把
      `{interruptId, status, payload}` 放在**下一次 run 的请求体**里带回来。
      协议里没有审批端点，就是这一条，所以它排在最前面判。
      （tool-call 型 interrupt 被 resolve 时，客户端**同时**会补一条 tool
      消息；先认 `resume` 才不会把它误当成前端工具的结果。）
   2. **前端工具的结果是以「新的一轮」回来的**（AG-UI 的 client-side tool 就是
      这么设计的）。所以这里要认出「会话正在等 resume，且这次带的是那个工具的
      结果」，转成 `resume-run! :reply`，而不是又起一个新 run。
   3. **前端声明的工具可能与服务端工具重名**——那是 `useHumanInTheLoop` 的用法：
      前端注册一个同名工具只为了**渲染 + 审批**，活还是服务端那个工具去干。
      不剔除就会在 ChatClient 装配期撞「工具名唯一」校验，整个 run 500。
      剔除之后挂起的仍是**服务端**工具，于是它回来的载荷按**决策**处理
      （真的去执行那个工具），而不是当成工具结果。
   4. **客户端也可能永远不回结果**——CopilotKit 的 suggestions 就是这样：它把
      `copilotkitSuggest` 塞进 `tools`，只读模型发出的 `TOOL_CALL_ARGS` 来渲染
      建议按钮，从不回结果。会话于是停在那儿等，用户下一句话全被挡住
      （联调时当场卡死：输入框敲了字，发不出去）。所以新用户消息到达、而挂起的
      正是**这次请求带上来的前端工具**时，`:discard-pause? true` 丢掉它继续。

   `entry` 是 `agui.registry` 里那台 agent 的条目（`:runtime` /
   `:server-tool-names` …）。坏 JSON / 缺 `threadId` / 缺 `runId` 在**开 SSE 之前**
   回 400——开了流状态码就已经是 200 了（designs/agui-borrow.md §7）。"
  [entry opts request]
  (with-body [body request]
   (or (input-error body {:require-run-id? true})
       (handle-run* entry opts request body))))

(defn- handle-run*
  [{:keys [runtime] :as entry} {:keys [thread-store] :as opts} request body]
  (let [server-tool-names (or (:server-tool-names entry) #{})
        input-transform (or (:input-transform entry) (:input-transform opts))
        mcp-proxy (or (:mcp-proxy entry) (:mcp-proxy opts))
        ;; **MCP UI 的代理调用不是一次 run**：前端那块 MCP App 界面（跑在 iframe
        ;; 里）要调 server 时，把请求塞在 `forwardedProps.__proxiedMCPRequest` 里
        ;; 走 `/run` 这条路发上来。这时候不能起 agent——照上游的做法，回一对
        ;; `RUN_STARTED` / `RUN_FINISHED{result}` 就完了。
        proxied (get-in body [:forwardedProps :__proxiedMCPRequest])
        {:keys [conversation-id message agui-tools resume context parent-run-id state]}
        (cond-> (codec/parse-run-input body)
          ;; 入站插件的挂点（对称于 `:event-transform`）：`agui.a2ui` 用它把
          ;; `forwardedProps.a2uiAction`（用户在生成出来的界面上点了什么）接进本轮输入
          input-transform (input-transform body))
        ;; 前端注册的本轮上下文（`useAgentContext`）→ 追加的 system 段。
        ;; **两条腿都要给**：起 run 与 resume 后的续跑是同一轮对话的两段，
        ;; 只给前者的话，审批点完之后模型就突然不知道自己在看哪个页面了。
        ctx-opts (cond-> nil
                   (codec/context->prompt context)
                   (assoc :extra-system-prompts [(codec/context->prompt context)])
                   ;; 客户端声明的父 run：原样回到 `RUN_STARTED.parentRunId`
                   parent-run-id (assoc :parent-run-id parent-run-id))
        pending (rt/awaiting runtime conversation-id)
        pending-name (get-in pending [:pending-tool :name])
        ;; 多条审批 / 坏 resume / 过期答复都在这里先判（见 resume-plan）
        plan (resume-plan (:resume body) pending server-tool-names)
        resume-entry (:single plan)
        tool-result (when pending
                      (->> (:messages body)
                           (filter #(= "tool" (:role %)))
                           last))
        ;; 与服务端工具重名的前端声明 = `useHumanInTheLoop` 的「我要渲染并审批它」，
        ;; **不是**要新增一个工具。留着会撞装配期的工具名唯一校验。
        real-frontend-tools (remove #(contains? server-tool-names (str (:name %))) agui-tools)
        frontend-names (into #{} (map #(str (:name %))) real-frontend-tools)
        ;; 挂起的是本次带上来的前端工具、而客户端这次并没有回结果 → 它不会回了
        stale-frontend-pause? (and pending
                                   (nil? tool-result)
                                   (nil? resume-entry)
                                   (contains? frontend-names pending-name))
        result (cond
                 ;; 带了 resume 却对不上 / 答不全 / 形状不对（过期重放：换页签、重连、
                 ;; 在旧卡片上点了按钮）。**什么都不起**，下面回 4xx——落进 start-run!
                 ;; 会拿旧决定去恢复一个已经不是它的暂停。
                 (:error plan)
                 {:status :resume-error :error (:error plan)}

                 ;; 同一轮多条审批：逐条决策交给 client（顶层 decision 被 :decisions 覆盖）
                 (:decisions plan)
                 (rt/resume-run! runtime conversation-id "approved"
                                 {:decisions (:decisions plan)} ctx-opts)

                 ;; interrupt 协议的答复：`resume[]` 是一等字段，压过一切推断
                 (and resume-entry (contains? server-tool-names pending-name))
                 (let [[decision payload] (resume-decision resume-entry)]
                   (rt/resume-run! runtime conversation-id decision payload ctx-opts))

                 ;; 前端工具被 resolve：载荷**即**结果（ask-user 语义）
                 resume-entry
                 (rt/resume-run! runtime conversation-id "reply"
                                 {:message (frontend-reply resume-entry tool-result)}
                                 ctx-opts)

                 ;; 审批：挂起的是**服务端**工具 → 载荷是决策，工具还要真去执行
                 (and pending tool-result (contains? server-tool-names pending-name))
                 (rt/resume-run! runtime conversation-id
                                 (decision-of (:content tool-result)) nil
                                 ctx-opts)

                 ;; 前端工具：载荷**即**工具结果（ask-user 语义）
                 (and pending tool-result)
                 (rt/resume-run! runtime conversation-id "reply"
                                 {:message (str (:content tool-result))}
                                 ctx-opts)

                 :else
                 (rt/start-run! runtime conversation-id message
                                (merge ctx-opts
                                       {:tools (mapv agui-tools/frontend-tool real-frontend-tools)
                                        :discard-pause? stale-frontend-pause?
                                        ;; 共享状态的基线：客户端那份原样交给
                                        ;; runtime，后续 delta 对着它算
                                        :state state})))]
    ;; 起跑成功（新 run 或续跑）就给线程表记一笔：线程从此在 `/threads` 里，
    ;; **会话被驱逐之后也还在**（designs/agui-borrow.md §6）
    (when (and thread-store (= :started (:status result)))
      (try (ts/ts-touch! thread-store conversation-id {:agent-id (:id entry)})
           (catch Throwable _ nil)))
    (cond
      (and proxied mcp-proxy)
      ((sse-frames [{:type "RUN_STARTED" :threadId conversation-id :runId (:runId body)}
                    {:type "RUN_FINISHED" :threadId conversation-id :runId (:runId body)
                     :result (mcp-proxy proxied)}])
       request)

      (= :busy (:status result))
      (json-response 409 {:error "thread_busy" :message "这个线程上还有一个 run 在跑"
                          :runId (:run-id result)})

      ;; **明说**，别落进下面那个订阅——那会开一条永远不来事件的流（客户端吊在那儿）
      (= :resume-error (:status result))
      (let [[status code extra] (:error result)]
        (json-response status (assoc extra :error code)))

      :else
      ;; **用返回的 `:since` 订阅，不是 nil**：run 已经起跑并发了 `RUN_STARTED`，
      ;; 这里才轮到我们订阅。传 nil = 只收新事件 = 把 `RUN_STARTED` 漏掉，
      ;; 前端第一件事就是 `AGUIError: First event must be 'RUN_STARTED'`。
      ((sse-response runtime conversation-id (:since result)
                     (assoc opts :close-on-terminal? true))
       request))))

(defn- sse-detached
  "把一次**没有会话**的 run 接到 SSE 上（`/suggest` 用）。

   与 `sse-response` 的差别正是这条路的全部特点：不订阅会话（没有会话），
   事件从 run 自己的发射器直接来，终态即关流。"
  [start! opts]
  (fn [request]
    (hk/with-channel request channel
      (let [{:keys [send! close!]} (open-sse! channel opts nil)]
        (start! (fn [ev]
                  (doseq [agui (codec/->agui-events ev)]
                    (send! (sse-frame ev agui))
                    (when (codec/terminal? agui) (close!)))))))))

(defn- ack-tool
  "客户端在建议 run 里声明的工具 → 内联工具。

   **不能用 `agui-tools/frontend-tool`**：那个会让 gate 把 run 暂停下来等前端
   回结果，而建议工具（`copilotkitSuggest`）**从来不回结果**——前端只读
   `TOOL_CALL_ARGS` 把建议渲染成按钮（§9.10 第 5 条那个会话卡死就是这么来的）。
   所以这里给一个空回执的 handler + **`:return-direct`**：工具结果即最终答案，
   不再回灌 LLM。少了它模型会为「ok」这个结果再跑一轮（实测多花了一整轮
   reasoning + 29 块正文 token），而那一轮的产出没有任何人读——建议的载体是
   `TOOL_CALL_ARGS`，不是模型的话。"
  [{:keys [name description parameters]}]
  (cond-> {:name (clojure.core/name name)
           :return-direct true
           :handler (fn [_args _ctx] "")}
    description (assoc :description description)
    parameters  (assoc :parameters parameters)))

(declare handle-suggest*)

(defn handle-suggest
  "POST {base}/agent/:id/suggest —— **无状态**建议 run（「猜用户下一句想说什么」）。

   这条路与 `/run` 的每一处不同都是刻意的（对齐 CopilotKit 的 `handle-suggest`）：

   | | `/run` | `/suggest` |
   |---|---|---|
   | 历史 | **服务端权威**，只取最后一条 user 消息 | **客户端权威**——没有服务端线程，它发上来的就是全部上下文 |
   | 会话 | 进注册表：缓冲、订阅、stop / resume | **不进**（`rt/run-detached!`）——一次性的建议不该留下一堆废会话 |
   | 落库 | memory filter 循环内落库 | 不落：临时 store，跑完就没了 |
   | 工具 | 服务端工具 + 前端工具（会暂停） | 只有客户端这次声明的，**都不执行**（见 `ack-tool`） |
   | 插件 | 挂（genui / a2ui …） | **不挂**：工具选择已被强制，插件注入的工具是白费；MCP 之类还会多打一次网络往返 |

   为什么值得单开一条：不实现它，前端就把 `copilotkitSuggest` 塞进 `/run` 的
   `tools` 里绕过去——那正是「输入框敲了字发不出去」的来源（§9.10 第 5 条）。"
  ([agent-spec request] (handle-suggest agent-spec nil request))
  ([agent-spec opts request]
   (with-body [body request]
    (handle-suggest* agent-spec opts request body))))

(defn- handle-suggest*
  [agent-spec opts request body]
  (let [msgs (codec/agui->messages (:messages body))
        history (vec (butlast msgs))
        last-msg (last msgs)
        tools (mapv ack-tool (:tools body))
        store (memory/in-memory-store)]     ;; 临时的：跑完就没人引用了
    (when (seq history) (memory/mem-add store "suggest" history))
    (if (nil? last-msg)
      (error-response 400 "no_messages" "建议 run 需要至少一条消息")
      ((sse-detached
        (fn [on-event]
          (rt/run-detached!
           {:agent (agent/create-agent
                    (-> agent-spec
                        ;; 不留痕：临时 store、不给 pause-store、不要 on-pause
                        (dissoc :pause-store :on-pause)
                        (assoc :memory store
                               :conversation-id "suggest"
                               :tools tools)))
            :message (:content last-msg)
            :on-event on-event
            ;; 只有一个工具时，中立的 `:required` 就等于上游那句「强制调
            ;; copilotkitSuggest」。第二轮 LLM 由 `ack-tool` 的 `:return-direct`
            ;; 挡掉，不是靠 `:max-iterations`——后者只限制**工具轮**的次数，
            ;; 工具跑完那次「总结一下」的 LLM 调用照发（实测）
            ;; 建议 run 也带前端上下文——CopilotKit 的 suggestion-engine 同样
            ;; 把 `context` 发上来（`suggestion-engine.ts:270`），少了它建议就
            ;; 脱离了用户正在看的那一页
            :opts (cond-> {:tool-choice (if (seq tools) :required :auto)
                           :max-iterations 1}
                    (codec/context->prompt (:context body))
                    (assoc :extra-system-prompts
                           [(codec/context->prompt (:context body))]))}))
        opts)
       request))))

(defn handle-connect
  "POST {base}/agent/:id/connect —— 只订阅，不起 run（重连 / 第二个页签旁观）。"
  ([runtime request] (handle-connect runtime nil request))
  ([runtime opts request]
   (with-body [body request]
    (or (input-error body nil)
        ((sse-response runtime (:threadId body) (last-event-id request body)
                       (assoc opts :close-on-terminal? false))
         request)))))

(defn stop-response
  [stopped?]
  (json-response
   (if stopped?
     {:stopped true
      :interrupt {:type "RUN_ERROR" :message "Run stopped by user" :code "STOPPED"}}
     {:stopped false
      :message "这个线程上没有在跑的 run（或这台 agent 拒绝停止）"})))

(defn handle-stop
  "POST {base}/agent/:id/stop/:threadId

   **threadId 在路径里，请求没有 body**（`agent.ts` 的 `stopPath`）。

   返回 true 只表示**取消已登记**：JVM 上没有抢占原语，正在跑的工具会跑完。
   UI 上该显示「正在停止…」，停稳的信号是事件流里的终态。

   响应体照上游（Keelson `AguiEndpoints.Stop` 同形）：停了就带一条 `interrupt`
   ——那条 `RUN_ERROR code=STOPPED` **由前端合成**，不发进流（流上的终态是
   `RUN_FINISHED result.status=cancelled`，原因 `stop`）。没停（没在跑）给一句
   `message`，而不是一个光秃秃的 false。"
  [reg agent-id thread-id]
  (stop-response (boolean (and thread-id (registry/stop! reg agent-id thread-id)))))

(defn handle-pending
  "GET {base}/pending —— 当前有哪些会话卡在等审批。

   也不属于 AG-UI。真做审批台的话这就是它的数据源：`rt/conversations` +
   `rt/awaiting` 两个调用而已。

   `interruptId` 与事件流里那条 interrupt 是同一个（`codec/interrupt-id`），
   审批台拿它就能凭 `/approve` 或走 `resume[]` 答复。"
  [reg]
  (json-response
   {:pending (into []
                   (for [{:keys [id runtime]} (registry/agents reg)
                         cid (rt/conversations runtime)
                         :let [aw (rt/awaiting runtime cid)]
                         :when aw]
                     {:threadId cid
                      :agentId id
                      :runId (:run-id aw)
                      :interruptId (codec/interrupt-id aw)
                      :reason (:reason aw)
                      :pendingTool (get-in aw [:pending-tool :name])
                      ;; 同一轮多条审批时每条一项（单条时就一项），id 即各自的 interruptId
                      :pendingTools (mapv (fn [p] {:interruptId (str (get-in p [:tool-call :id]))
                                                   :name (:name p)
                                                   :args (:args p)})
                                          (or (not-empty (vec (:pending-tools aw)))
                                              (when (:pending-tool aw) [(:pending-tool aw)])))}))}))

(defn handle-approve
  "POST {base}/agent/:id/approve  {threadId, decision, payload}

   **这条不属于 AG-UI**——协议里的答复路径是下一次 run 的 `resume[]`（见
   `handle-run`），聊天页上的审批按钮走那条就够了。留着这个端点是为了**带外
   审批**：审批台、Slack 按钮、运维脚本——它们手上只有 conversation-id，不发
   聊天消息，也不该被迫伪造一次 run。打完再 `/connect` 接着看续跑（新的 run）。"
  [runtime request]
  (with-body [body request]
   (or (input-error body nil)
       (let [{:keys [threadId decision payload]} body
             result (rt/resume-run! runtime threadId (or decision "approved") payload)]
         (json-response {:status (name (:status result))
                         :runId (:run-id result)})))))


(defn- iso-now [ms]
  (.toString (java.time.Instant/ofEpochMilli (long (or ms (System/currentTimeMillis))))))

(defn- entry-of-thread
  "线程归哪台 agent：线程表里记着 `agentId` 就按它找，找不到（老记录 / 那台已经
   不在表里）退回第一台。"
  [reg rec]
  (or (registry/find-agent reg (:agent-id rec))
      (registry/default-agent reg)))

(defn- thread-record
  "线程表的中立记录 → 上游 `LocalThreadEndpointRecord` 的形状。

   `organizationId` / `createdById` 是 Intelligence（云产品）的字段，本地模式下
   上游也只是填个占位——照填，少了客户端解析会缺字段。"
  [reg rec]
  (let [entry (entry-of-thread reg rec)]
    {:id (:id rec)
     :name (:name rec)                   ;; 没起过名就是 null，前端会自己显示摘要
     :agentId (or (:agent-id rec) (:id entry))
     :organizationId "local"
     :createdById "local"
     :archived (boolean (:archived? rec))
     :createdAt (iso-now (:created-at rec))
     :updatedAt (iso-now (:updated-at rec))}))

(defn- fold-state
  "缓冲里的状态事件折成**当前值**：最后一条快照 + 其后的所有 delta。

   ⚠️ 只取最后一条快照是不够的（这里原来就是那么写的）——有了 `STATE_DELTA`
   之后，「改完再读」会读到改之前的值：增量根本没算进去。实测过：加完第二条
   待办，读面还停在第一条。

   ⚠️ 缓冲是**有界**的（`rt/buffered-events`，缺省 512 条）。快照被挤出去之后
   这里只剩下一串 delta，折出来的就是残的 —— 与「事件缓冲不是真相店」这条既有
   性质同源（真相在客户端那份 state 里，我们只做尽力而为的只读面）。没有任何
   状态事件时返回 nil，与从前一致。"
  [events]
  (reduce (fn [st ev]
            (case (:type ev)
              :state/snapshot (:state ev)
              :state/delta    (agui-state/apply-ops st (:delta ev))
              st))
          nil
          events))

(defn- query-params
  "查询串 → `{\"k\" \"v\"}`（同名多值取第一个；值全是**串**，交给线程存储自己挑）。"
  [request]
  (let [qs (:query-string request)]
    (if (str/blank? qs)
      {}
      (reduce (fn [m kv]
                (let [[k v] (str/split kv #"=" 2)
                      k (java.net.URLDecoder/decode (str k) "UTF-8")]
                  (if (or (str/blank? k) (contains? m k))
                    m
                    (assoc m k (java.net.URLDecoder/decode (str v) "UTF-8")))))
              {}
              (str/split qs #"&")))))

(defn- not-implemented
  "缺槽 → 501（Keelson 同码）。⛔ 不是 404：上游 Inspector 对 501 有专门的一条路
   ——画成「这一栏没有」，而不是报错。"
  [what]
  (error-response 501 "not_implemented" (str "这台服务端没有提供：" what)))

(defn- no-thread [thread-id]
  (error-response 404 "thread_not_found" (str "没有这个线程: " thread-id)))

(defn thread-endpoints
  "`/info.threadEndpoints` 的三格，与路由的 501 **出自同一份事实**——报 true 的路
   一定走得通，走不通的路一定报 false：

   - `list`      线程存储给了 `:list`
   - `inspect`   至少一台 agent 配了 memory（`/messages` 读的是 ChatMemory）
   - `mutations` `:update` 与 `:delete` 都给了"
  [reg store]
  {:list (ts/has-slot? store :list)
   :inspect (boolean (and store (some :memory (registry/agents reg))))
   :mutations (and (ts/has-slot? store :update) (ts/has-slot? store :delete))})

(defn handle-threads
  "`/threads*` —— **三样东西各管一件**，这里只是把它们拼起来：

   | 端点 | 数据从哪来 |
   |---|---|
   | `GET /threads` | **线程存储**（`agui.thread-store`）——会话被驱逐、进程重启后照样在 |
   | `GET /threads/:id/messages` | **ChatMemory**（服务端权威的那份历史） |
   | `GET /threads/:id/events` | 会话的事件存储（缺省环形缓冲：有界、不落库；可经 runtime `:event-store` 换成持久化实现，见 `rt/buffered-events`） |
   | `GET /threads/:id/state` | 缓冲里的状态事件折出来的当前值 |
   | `POST\\|PATCH /threads/:id`（改名 / 取消归档）、`POST …/archive` | 线程存储 |
   | `DELETE /threads/:id`、`POST /threads/clear` | 摘会话 + 清 ChatMemory + 删线程记录 |

   `GET /threads` 认上游会发的四个查询参数：`agentId`（只列这台的）、
   `includeArchived=true`（**缺省不列归档的**）、`limit`、`cursor`（上一页回的
   `nextCursor`）。存储缺哪个槽，对应的路回 **501**（`thread-endpoints` 报 false）。

   ⛔ 线程存储只存**元数据**（designs/agui-borrow.md §6）：事件照旧不落库
   （docs/agent-runtime-design.md §8.2），`/events` 照实返回缓冲里现存的部分。"
  [reg store path request]
  (let [seg (fn [re] (some-> (re-find re path) second java.net.URLDecoder/decode))
        method (:request-method request)
        thread-id (or (seg #"^/threads/([^/]+)/(?:messages|events|state|archive)$")
                      (seg #"^/threads/([^/]+)$"))
        rec (when thread-id (ts/ts-get store thread-id))
        entry (when thread-id (entry-of-thread reg (or rec {})))]
    (cond
      (re-find #"^/threads/subscribe$" path)
      ;; 实时订阅是 Intelligence（云产品）的东西，我们没有——**如实 404**，
      ;; 客户端会降级成轮询，比假装成功强
      (error-response 404 "not_found" "threads/subscribe not supported")

      (re-find #"^/threads/clear$" path)
      (if-not (ts/has-slot? store :delete)
        (not-implemented "删除线程")
        (let [known (when (ts/has-slot? store :list)
                      (:threads (ts/ts-list store {:include-archived? true})))]
          (doseq [{:keys [runtime memory]} (registry/agents reg)
                  cid (rt/conversations runtime)]
            (rt/purge! runtime cid)
            (when memory (memory/mem-clear memory cid)))
          (doseq [r known
                  :let [{:keys [runtime memory]} (entry-of-thread reg r)]]
            (rt/purge! runtime (:id r))
            (when memory (memory/mem-clear memory (:id r)))
            (ts/ts-delete! store (:id r)))
          {:status 204 :headers cors-headers}))

      (re-find #"^/threads/[^/]+/messages$" path)
      (if-let [mem (:memory entry)]
        (json-response {:messages (codec/messages->thread-messages (memory/mem-get mem thread-id))})
        (not-implemented "线程消息（这台 agent 没配 memory）"))

      (re-find #"^/threads/[^/]+/events$" path)
      (json-response {:events (codec/events->agui (rt/buffered-events (:runtime entry) thread-id))})

      (re-find #"^/threads/[^/]+/state$" path)
      (json-response {:state (fold-state (rt/buffered-events (:runtime entry) thread-id))})

      (re-find #"^/threads/[^/]+/archive$" path)
      (if-not (ts/has-slot? store :update)
        (not-implemented "归档线程")
        (if (ts/ts-update! store thread-id {:archived? true})
          (json-response {:threadId thread-id :archived true})
          (no-thread thread-id)))

      (and thread-id (= :delete method))
      (if-not (ts/has-slot? store :delete)
        (not-implemented "删除线程")
        (let [{:keys [runtime memory]} entry
              live? (boolean (some #{thread-id} (rt/conversations runtime)))]
          (rt/purge! runtime thread-id)
          (when memory (memory/mem-clear memory thread-id))
          (if (or (ts/ts-delete! store thread-id) live?)
            (json-response {:threadId thread-id :deleted true})
            (no-thread thread-id))))

      ;; 改名 / 取消归档。上游走 PATCH，我们早先用 POST——两个都认
      (and thread-id (#{:post :patch} method))
      (if-not (ts/has-slot? store :update)
        (not-implemented "修改线程")
        (with-body [body request]
          (if-not (or (nil? body) (map? body))
            (error-response 400 "bad_json" "请求体必须是 JSON 对象")
            (let [updates (cond-> {}
                            (contains? body :name)     (assoc :name (:name body))
                            (contains? body :archived) (assoc :archived? (boolean (:archived body))))]
              (if-let [r (ts/ts-update! store thread-id updates)]
                (json-response (thread-record reg r))
                (no-thread thread-id))))))

      (and thread-id (= :get method))
      (if rec
        (json-response (thread-record reg rec))
        (no-thread thread-id))

      thread-id
      (error-response 405 "method_not_allowed" "这条路只认 GET / POST / PATCH / DELETE")

      :else
      (if-not (ts/has-slot? store :list)
        (not-implemented "线程列表")
        (let [q (query-params request)
              {:keys [threads next-cursor]}
              (ts/ts-list store {:agent-id (get q "agentId")
                                 :include-archived? (get q "includeArchived")
                                 :limit (get q "limit")
                                 :cursor (get q "cursor")})]
          (json-response {:threads (mapv #(thread-record reg %) threads)
                          :nextCursor next-cursor}))))))

(def ^:private agent-route
  #"^/agent/([^/]+)/(run|connect|suggest|approve|stop)(?:/([^/]+))?$")

(defn- info-opts
  "`/info` 的能力位：装配期的开关 + 线程面按存储的槽逐格报。"
  [reg {:keys [thread-store] :as opts}]
  (-> (select-keys opts [:version :open-generative-ui? :a2ui? :multimodal
                         :parallel-tools? :max-iterations :state? :capabilities])
      (assoc :thread-endpoints (thread-endpoints reg thread-store)
             ;; 本路由把 `editedArgs` 翻成 resume 的 `{:args}`（见 resume-decision），
             ;; 能力是真的——缺省报；装配方可显式关
             :approve-with-edits? (get opts :approve-with-edits? true))))

(defn handler
  "Ring handler。`reg` 是 `agui.registry` 的注册表；`opts`：

   - `:thread-store`    线程存储（不给 = 没有 `/threads*`，照旧 404）
   - `:heartbeat-ms`    SSE 心跳周期（缺省 15000，0 = 关）
   - `:heartbeat-frame` 心跳帧，字符串或 `(fn [] string)`（缺省注释帧）
   - `:max-body-bytes`  请求体上限（缺省 16 MiB，超了 413）
   - `:input-transform` / `:mcp-proxy` 入站插件（条目里给了就以条目为准）
   - 其余是 `/info` 的能力位（见 `codec/run-info`）"
  [reg base-path & [opts]]
  (when-not (registry/registry? reg)
    (throw (ex-info "handler 需要 agui.registry/registry 建的注册表" {})))
  (fn [request]
    (binding [cors-headers (cors-for request)
              *max-body-bytes* (or (:max-body-bytes opts) default-max-body-bytes)]
     (let [uri (:uri request)
           path (if (str/starts-with? uri base-path) (subs uri (count base-path)) uri)
           store (:thread-store opts)]
       (cond
         (= :options (:request-method request))
         {:status 204 :headers cors-headers}

         (= "/info" path)    (json-response (registry/info reg (info-opts reg opts)))
         ;; 不属于 AG-UI：应用自己的审批台数据源（见 handle-pending）
         (= "/pending" path) (handle-pending reg)
         ;; 线程面（见 handle-threads）。**锚在开头**：agent 名字里带 threads 的
         ;; （`/agent/threads/run`）不能被它截走
         (re-find #"^/threads(/|$)" path)
         (if store
           (handle-threads reg store path request)
           (error-response 404 "not_found" "threads not enabled"))

         :else
         (if-let [[_ raw-id action tail] (re-find agent-route path)]
           (let [agent-id (java.net.URLDecoder/decode ^String raw-id "UTF-8")]
             (if-let [entry (registry/find-agent reg agent-id)]
               (case action
                 "run"     (handle-run entry opts request)
                 "connect" (handle-connect (:runtime entry) opts request)
                 "suggest" (if-let [spec (and (registry/supports-suggest? entry) (:agent-spec entry))]
                             (handle-suggest spec opts request)
                             (error-response 404 "not_found" (str "agent " agent-id " 没有开 /suggest")))
                 ;; 不属于 AG-UI：应用自己的审批端点（见 handle-approve）
                 "approve" (handle-approve (:runtime entry) request)
                 ;; threadId 是路径段：/agent/:id/stop/:threadId
                 "stop"    (handle-stop reg agent-id
                                        (when tail (java.net.URLDecoder/decode ^String tail "UTF-8"))))
               (error-response 404 "agent_not_found" (str "没有这台 agent: " agent-id))))
           (error-response 404 "not_found" (str "认不出的路径: " path))))))))

;;; ============================================================
;;; 启动
;;; ============================================================

(defn start-registry!
  "起一台挂着**多台 agent** 的服务。`reg` 由 `agui.registry/registry` 建，条目里
   除 `:id` / `:runtime` 外可带路由用的键：

   - `:server-tool-names` 服务端工具名（认出前端同名声明 = 渲染意图）
   - `:memory`            这台的 ChatMemory（`/threads/:id/messages` 读它）
   - `:suggest?` + `:agent-spec` 开 `/suggest`（要现建一次性 agent，所以整份配置得留着）

   `opts` 同 `handler`。"
  [port reg base-path opts]
  {:server (hk/run-server (handler reg base-path opts) {:port port})
   :registry reg
   :runtime (:runtime (registry/default-agent reg))})

(defn start!
  "`agent-spec` 就是 `create-agent` 那份配置（provider / tools / memory /
   pause-store …）。**memory 与 pause-store 要跨 run 共享**，所以建在外面。

   agent id 用 `\"default\"`：CopilotKit 前端不指定 agent 时就找这个名字
   （`@copilotkit/shared` 的 `DEFAULT_AGENT_ID`）。多台 agent 用 `start-registry!`。

   线程面：`:threads? true` 缺省用进程内线程存储；`:thread-store` 可换成任何实现
   （如 `agui.thread-store.sqlite`，重启后侧栏照样在）。`:orphan-cancel-ms` 透传给
   runtime（最后一个订阅者走后多久自动停，缺省关）。"
  ([port agent-spec] (start! port agent-spec "/api/copilotkit" nil))
  ([port agent-spec base-path] (start! port agent-spec base-path nil))
  ([port agent-spec base-path {:keys [event-transform open-generative-ui?
                                      suggestions? threads? thread-store
                                      state-tools? orphan-cancel-ms] :as opts}]
   (let [runtime (rt/runtime (cond-> {:agent-fn (agui-tools/agent-fn agent-spec)
                                      ;; 聊天 UX：用户又发一条就顶掉上一条（旧 run 落 cancelled，
                                      ;; 原因 superseded）
                                      :on-concurrent :supersede
                                      ;; 模型写共享状态的两把工具（见 rt/runtime）
                                      :state-tools? (boolean state-tools?)}
                               ;; 事件流插件（如 agui.a2ui / copilotkit.genui）；不传就完全不存在
                               event-transform (assoc :event-transform event-transform)
                               orphan-cancel-ms (assoc :orphan-cancel-ms orphan-cancel-ms)))
         ;; 服务端工具名：用来把「前端同名声明」认成**渲染意图**而不是新工具
         server-tool-names (into #{}
                                 (comp (filter var?)
                                       (map #(:name (tool/get-schema %))))
                                 (:tools agent-spec))
         reg (registry/registry
              [{:id "default"
                :runtime runtime
                :server-tool-names server-tool-names
                :memory (:memory agent-spec)
                :suggest? (boolean suggestions?)
                ;; `/suggest` 要现建一次性 agent，所以整份配置得留着
                :agent-spec (when suggestions? agent-spec)}])]
     (start-registry! port reg base-path
                      (-> opts
                          (dissoc :event-transform :threads? :suggestions? :state-tools?
                                  :orphan-cancel-ms)
                          (assoc :open-generative-ui? open-generative-ui?
                                 ;; 装了写状态的工具才报 state 能力位——读面一直都在，
                                 ;; 但没有写的那一半，报 true 就是谎报（模型根本写不动）
                                 :state? (boolean state-tools?)
                                 ;; 线程面：存储管「有哪些线程、叫什么」，历史在 ChatMemory
                                 :thread-store (when threads? (or thread-store (ts/in-memory-store)))))))))

(defn stop! [{:keys [server runtime] reg :registry}]
  (if reg
    (registry/shutdown! reg)
    (rt/shutdown! runtime))
  (server))
