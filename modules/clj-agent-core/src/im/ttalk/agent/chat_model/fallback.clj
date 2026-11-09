(ns im.ttalk.agent.chat-model.fallback
  "主备模型切换（designs/provider-reliability.md §3；Keelson `Providers/FallbackChatClient.cs`）。

   主模型**连它自己的重试都耗尽**之后，按备用链依次换模型。它是一个 ChatModel 装饰器，
   挂在每站 DefaultChatModel（内含重试）的**外面**、filter 栈的下面：

       FallbackChatModel ──┬─ 站 0：主模型（重试 → 取票 → provider）
                           ├─ 站 1：备用（惰性构造）
                           └─ …

   - **判据走错误类型**（`switchable?`）：切 5xx 类 :provider-error / :network-error /
     :timeout-error / :rate-limit-error（重试已耗尽）/ :auth-error / :quota-exhausted-error
     （额度是逐家的）/ :empty-completion-error / :parse-error；**不切** :validation-error
     （请求本身有错）、:context-overflow-error（换一家照样超长，交压缩恢复）、取消 / 中断。
     这一站返回空补全（`response/empty-completion?`）且还有下一站 → 也切（`:switch-on-empty?`，缺省真）。
   - **流式**：只在本站第一个 token 之前切；吐过 token 之后的失败原样抛（同「token 出门不重发」）。
   - **跨 provider 剪 thinking**：上一站与本站 ref 的 provider 段不同 → 所有消息 `dissoc :blocks`
     （Anthropic thinking 签名、Responses reasoning item 等不透明回放载荷，别家原样收到只会 400）。
     同家换模型不动。
   - **换站去掉 `:options :model`**：备用站用它自己的 model-options，不拿主模型的名字问备胎。
   - **惰性构造**：`:create` 轮到才调、每站只调一次；构造抛错 = 这一站自己的配置问题，**不看判据**、
     有下一站就硬切。
   - **报账**：成功响应 `assoc :model-ref ref :fallback report`；全链失败原样抛最后一个错误，
     ex-data 追加 `:fallback report`。读法 `report`。

   ```clojure
   (fallback/fallback-chat-model
     primary-cm
     [{:ref \"openai/gpt-4o\" :create (fn [] (cm/create-chat-model openai-p {:model \"gpt-4o\"}))}
      {:ref \"deepseek/deepseek-chat\" :chat-model ds-cm}]
     {:primary-ref \"anthropic/claude-sonnet-4\"
      :on-switch (fn [{:keys [from to error]}] (log/warn from \"→\" to (:message error)))})
   ```"
  (:require [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.model.spec :as spec])
  (:import [java.util.concurrent CancellationException]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 判据
;;; ============================================================

(def ^:private switch-types
  #{:network-error :timeout-error :rate-limit-error :auth-error
    :quota-exhausted-error :empty-completion-error :parse-error})

(defn switchable?
  "这一类失败值不值得换一家（见 ns 文档）。"
  [^Throwable t]
  (let [d (ex-data t)]
    (cond
      (or (instance? InterruptedException t) (instance? CancellationException t)) false
      (contains? switch-types (:type d)) true
      (= :provider-error (:type d)) (boolean (:retryable? d))
      (#{:validation-error :context-overflow-error} (:type d)) false
      (contains? d :retryable?) (boolean (:retryable? d))
      (instance? java.io.IOException t) true
      :else false)))

;;; ============================================================
;;; 站点
;;; ============================================================

(defn- default-ref [chat-model]
  (or (when (instance? im.ttalk.agent.chat_model.DefaultChatModel chat-model)
        (let [p (some-> (:provider chat-model) provider/provider-name name)
              m (:model (:config chat-model))]
          (when (and p m) (str p "/" m))))
      "primary"))

(defn- error-map [^Throwable t]
  (-> (or (when (errors/error? (ex-data t)) (ex-data t))
          (errors/exception->error t))
      (dissoc :cause :fallback :attempts)))

(defn- trail [attempts final-ref switches]
  (vec (for [i (range switches)]
         (let [from (:ref (nth attempts i))
               to (if (< (inc i) (count attempts)) (:ref (nth attempts (inc i))) final-ref)]
           (str from " → " to "：" (:message (:error (nth attempts i))))))))

(defn- build-report [primary model attempts switches]
  {:primary primary :model model :switches switches
   :attempts attempts :trail (trail attempts model switches)})

(defn report
  "这一趟的切换账：传响应（成功时）或异常（全链失败时）。没经过本层 → nil。

   `{:primary 主模型 ref :model 真答话的 ref :switches 换了几次
     :attempts [{:ref :error canonical-error} …] :trail [\"a → b：原因\" …]}`"
  [x]
  (if (instance? Throwable x) (:fallback (ex-data x)) (:fallback x)))

(defrecord FallbackChatModel [primary primary-ref alternatives cache opts])

(defn- stage-count [^FallbackChatModel f] (inc (count (:alternatives f))))

(defn- stage-ref [^FallbackChatModel f i]
  (if (zero? i) (:primary-ref f) (:ref (nth (:alternatives f) (dec i)))))

(defn- stage-model
  "第 i 站的 ChatModel（0 = 主模型）。备用站惰性构造、每站只造一次。"
  [^FallbackChatModel f i]
  (if (zero? i)
    (:primary f)
    (let [{:keys [ref create chat-model]} (nth (:alternatives f) (dec i))]
      (or (get @(:cache f) ref)
          (locking (:cache f)
            (or (get @(:cache f) ref)
                (let [m (cm/as-chat-model (or chat-model (create)))]
                  (swap! (:cache f) assoc ref m)
                  m)))))))

(defn- strip-blocks [messages]
  (mapv (fn [m] (if (and (map? m) (contains? m :blocks)) (dissoc m :blocks) m)) messages))

(defn- stage-request
  "第 i 站要发的请求：换过站去掉 :options :model；跨 provider 剪掉 :blocks。"
  [^FallbackChatModel f request i]
  (if (zero? i)
    request
    (let [prev (spec/ref-provider (stage-ref f (dec i)))
          cur  (spec/ref-provider (stage-ref f i))
          r    (req/without-options request :model)]
      (if (and prev cur (not= prev cur))
        (req/update-messages r strip-blocks)
        r))))

(defn- stamp [^FallbackChatModel f resp ref attempts]
  (if (map? resp)
    (assoc resp :model-ref ref
           :fallback (build-report (:primary-ref f) ref attempts (count attempts)))
    resp))

(defn- give-up ^Throwable [^FallbackChatModel f ^Throwable t attempts]
  (errors/with-ex-data t {:fallback (build-report (:primary-ref f) (:ref (peek attempts)) attempts
                                                  (max 0 (dec (count attempts))))}))

(defn- empty-switch-error [ref]
  (errors/error :empty-completion-error (str ref " 返回空补全，切换下一站") {}))

(defn- decide
  "记一次失败并决定去留 → :switch 或 :give-up。forced = 这一站连 ChatModel 都造不出来。"
  [^FallbackChatModel f i ^Throwable t forced? attempts]
  (let [{:keys [switch? on-switch]} (:opts f)
        next? (< (inc i) (stage-count f))]
    (if (and next? (or forced? ((or switch? switchable?) t)))
      (do (when on-switch
            (on-switch {:from (stage-ref f i) :to (stage-ref f (inc i)) :error (error-map t)}))
          :switch)
      :give-up)))

;;; ============================================================
;;; 同步 / 异步执行骨架
;;; ============================================================

(defn- run-sync
  "invoke: (fn [chat-model request emitted-atom] resp)。emitted 为真后失败不切。"
  [^FallbackChatModel f request invoke]
  (let [request (req/as-chat-request request)
        on-empty? (get (:opts f) :switch-on-empty? true)]
    (loop [i 0 attempts []]
      (let [ref (stage-ref f i)
            m (try {:ok (stage-model f i)} (catch Throwable t {:err t}))]
        (if-let [^Throwable t (:err m)]
          (let [attempts (conj attempts {:ref ref :error (error-map t)})]
            (if (= :switch (decide f i t true attempts))
              (recur (inc i) attempts)
              (throw (give-up f t attempts))))
          (let [emitted (atom false)
                r (try {:ok (invoke (:ok m) (stage-request f request i) emitted)}
                       (catch Throwable t {:err t}))]
            (if-let [^Throwable t (:err r)]
              (let [attempts (conj attempts {:ref ref :error (error-map t)})]
                (if (and (not @emitted) (= :switch (decide f i t false attempts)))
                  (recur (inc i) attempts)
                  (throw (give-up f t attempts))))
              (let [resp (:ok r)]
                (if (and on-empty? (not @emitted) (< (inc i) (stage-count f))
                         (response/response? resp) (response/empty-completion? resp))
                  (let [err (empty-switch-error ref)]
                    (when-let [cb (:on-switch (:opts f))]
                      (cb {:from ref :to (stage-ref f (inc i)) :error err}))
                    (recur (inc i) (conj attempts {:ref ref :error err})))
                  (stamp f resp ref attempts))))))))))

(defn- run-async
  "invoke: (fn [chat-model request emitted-atom] 链结果)。语义同 `run-sync`。"
  [^FallbackChatModel f request invoke]
  (let [request (req/as-chat-request request)
        on-empty? (get (:opts f) :switch-on-empty? true)]
    (letfn [(step [i attempts]
              (let [ref (stage-ref f i)
                    m (try {:ok (stage-model f i)} (catch Throwable t {:err t}))]
                (if-let [^Throwable t (:err m)]
                  (let [attempts (conj attempts {:ref ref :error (error-map t)})]
                    (if (= :switch (decide f i t true attempts))
                      (step (inc i) attempts)
                      (throw (give-up f t attempts))))
                  (let [emitted (atom false)]
                    (flt/fbind
                      (flt/fcatch (flt/fmap (invoke (:ok m) (stage-request f request i) emitted)
                                            (fn [v] [::ok v]))
                                  (fn [t] [::err t]))
                      (fn [[tag x]]
                        (if (= ::err tag)
                          (let [attempts (conj attempts {:ref ref :error (error-map x)})]
                            (if (and (not @emitted) (= :switch (decide f i x false attempts)))
                              (step (inc i) attempts)
                              (throw (give-up f x attempts))))
                          (if (and on-empty? (not @emitted) (< (inc i) (stage-count f))
                                   (response/response? x) (response/empty-completion? x))
                            (let [err (empty-switch-error ref)]
                              (when-let [cb (:on-switch (:opts f))]
                                (cb {:from ref :to (stage-ref f (inc i)) :error err}))
                              (step (inc i) (conj attempts {:ref ref :error err})))
                            (stamp f x ref attempts)))))))))]
      (step 0 []))))

(defn- tracking [on-token emitted]
  (fn [t]
    (when (and (map? t) (some (fn [[_ v]] (not (or (nil? v) (= "" v)))) t))
      (reset! emitted true))
    (when on-token (on-token t))))

(extend-type FallbackChatModel
  cm/IChatModel
  (call [f request]
    (run-sync f request (fn [m r _] (cm/call m r))))
  (stream-call [f request on-token]
    (run-sync f request (fn [m r emitted] (cm/stream-call m r (tracking on-token emitted)))))
  (model-options [f] (cm/model-options (:primary f)))

  cm/IAsyncChatModel
  (call-async [f request]
    (run-async f request (fn [m r _] (cm/call-async* m r))))
  (stream-call-async [f request on-token]
    (run-async f request (fn [m r emitted] (cm/stream-call-async* m r (tracking on-token emitted))))))

;;; ============================================================
;;; 构造
;;; ============================================================

(defn fallback-chat-model
  "包一个主模型 + 备用链成 ChatModel。

   - primary: 主模型（任意 ChatModel 或 `{:chat-fn …}` map）
   - alternatives: `[{:ref \"provider/model\" :create (fn [] chat-model)} …]`，`:create` 惰性；
                   也可直接给 `:chat-model`（现成实例）。按切换次序。空 → 本层等于直通（仍报账）。
   - opts:
     - :primary-ref      主模型 ref；缺省从 DefaultChatModel 的 provider-name + :model 推，推不出为 \"primary\"
     - :switch?          (fn [throwable] bool) 覆盖判据（缺省 `switchable?`）
     - :on-switch        (fn [{:keys [from to error]}]) 每次真要换站前通知
     - :switch-on-empty? 本站返回空补全且有下一站时切换（缺省 true）"
  ([primary alternatives] (fallback-chat-model primary alternatives nil))
  ([primary alternatives opts]
   (let [p (cm/as-chat-model primary)]
     (doseq [{:keys [ref create chat-model] :as a} alternatives]
       (when-not (spec/split-ref ref)
         (throw (ex-info (str "备用模型 ref 形状不对（应为 provider/model）：" (pr-str ref))
                         {:type :validation-error :retryable? false :alternative a})))
       (when-not (or (fn? create) chat-model)
         (throw (ex-info (str "备用模型 " ref " 缺 :create（惰性工厂）或 :chat-model")
                         {:type :validation-error :retryable? false :alternative a}))))
     (->FallbackChatModel p (or (:primary-ref opts) (default-ref p))
                          (vec alternatives) (atom {}) (or opts {})))))

(defn from-registry
  "按注册表（`im.ttalk.agent.model.spec`）造「主 + 备用链」：备选 = opts 的 `:alternatives`
   ?? `spec/fallback-chain`；每站（含主模型）都经 `(create spec)` 造 ChatModel——想给备选也套重试 /
   票据就在 create 里套。主模型立即造，备选惰性造；没登记的备选 ref 轮到才报错（硬切下一站）。

   opts 另可带 `fallback-chat-model` 的全部 opts。"
  [reg ref {:keys [create alternatives] :as opts}]
  (let [s (or (spec/lookup reg ref)
              (throw (ex-info (str "未登记的模型：" ref)
                              {:type :validation-error :retryable? false :ref ref})))
        chain (or alternatives (spec/fallback-chain reg ref))]
    (fallback-chat-model
      (create s)
      (mapv (fn [r] {:ref r
                     :create (fn []
                               (create (or (spec/lookup reg r)
                                           (throw (ex-info (str "未登记的备用模型：" r)
                                                           {:type :validation-error :retryable? false :ref r})))))})
            chain)
      (assoc (dissoc opts :create :alternatives) :primary-ref ref))))
