(ns im.ttalk.agent.provider.http.stream-client
  "真流式 HTTP 传输 —— 基于 JDK 内置 java.net.http（替代 http-kit 的伪流式，修 BUG2）。

   与 http-kit 客户端的根本区别：http-kit 的 `:as :stream` 等整段响应收完才兑现
   promise（伪流式）；本实现用 `BodyHandlers/fromLineSubscriber`（响应式 Flow.Subscriber），
   **每行 SSE 到达即回调**——真增量、非阻塞、可取消、原生背压。

   与现有 stream 处理器无缝衔接：parse-fn / process-fn 复用 stream.anthropic / stream.openai
   的纯函数（parse-sse-line / process-event），传输层只负责把行喂进来。

   回调契约（与 http-kit 版 post-stream-async 一致）：
   - :on-token    (fn [token-data] ...)   每个 token/事件产出
   - :on-complete (fn [final-state] ...)   流正常结束
   - :on-error    (fn [{:keys [error cause status]}] ...)  连接/解析/非 2xx 失败

   返回 {:future CompletableFuture :cancel (fn [])}：
   - future 在流结束（或失败）时完成；
   - cancel 取消上游 HTTP（客户端断连/超时/用户停止时调用，不再消耗 token）。"
  (:require [cheshire.core :as json]
            [clojure.string]
            [taoensso.timbre :as log]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.streaming :as streaming]
            [im.ttalk.agent.provider.http.retry :as retry]
            [im.ttalk.agent.provider.http.client :as http-client])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandler
                          HttpResponse$BodySubscribers HttpResponse$ResponseInfo]
           [java.util.concurrent Flow$Subscriber]
           [java.util.function Function]
           [java.time Duration]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 共享 HttpClient（连接池复用；executor 可换虚拟线程）
;;; ============================================================

(def ^{:doc "默认共享 HttpClient——与非流式 http.client 同一实例（同一连接池 / 虚拟线程 executor），
   避免两套独立连接池。"}
  default-client
  http-client/default-client)

;;; ============================================================
;;; Flow.Subscriber：逐行喂给 parse-fn/process-fn
;;; ============================================================

(defn- line-subscriber
  "Flow.Subscriber<String>：onNext(line) → parse → process → on-token；
   映射 onComplete/onError。state 为累积状态 atom；cancel-p 暴露 subscription 以便取消。"
  [{:keys [parse-fn process-fn on-token on-complete on-error]} state cancel-p]
  (reify Flow$Subscriber
    (onSubscribe [_ s]
      (deliver cancel-p s)
      (.request s Long/MAX_VALUE))     ;; 简单全量拉取；需精细背压可改 request(1) + 每次回调后再 request
    (onNext [_ line]
      (try
        (when-let [ev (parse-fn line)]
          (let [[new-state token] (process-fn ev @state)]
            (reset! state new-state)
            (when (and token on-token) (on-token token))))
        (catch Throwable t
          ;; 单行处理异常不应中断整条流；记录并继续
          (log/warn "流式行处理异常，已跳过" {:error (.getMessage t)}))))
    (onError [_ t]
      ;; 连接/IO 失败 → canonical error（D5：network/timeout 等，统一契约）
      (when on-error (on-error (errors/exception->error t))))
    (onComplete [_]
      (when on-complete (on-complete @state)))))

;;; ============================================================
;;; 请求构建
;;; ============================================================

(defn- has-content-type? [headers]
  (some (fn [[k _]] (= "content-type" (clojure.string/lower-case (name k)))) headers))

(defn- build-request ^HttpRequest [url headers body timeout]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofMillis (or timeout 300000)))
              (.POST (HttpRequest$BodyPublishers/ofString
                       (if (string? body) body (json/generate-string body)))))]
    ;; 默认补 Content-Type: application/json（body 是 JSON）。http-kit 旧版会自动加，
    ;; java.net.http 不会——缺了它部分服务端（如 MiniMax）解析不到 body 报"缺 messages"。
    ;; 尊重调用方显式传入的 Content-Type。
    (when-not (has-content-type? headers)
      (.header b "Content-Type" "application/json"))
    (doseq [[k v] headers] (.header b (name k) (str v)))
    (.build b)))

;;; ============================================================
;;; 入口
;;; ============================================================

(defn post-stream-async
  "真流式 POST。opts:
   - :headers :body :timeout
   - :parse-fn :process-fn :initial-state   （复用 stream 处理器）
   - :on-token :on-complete :on-error
   - :provider  （错误归一化用，可选）
   - :client    （自定义 HttpClient，可选）

   返回 {:future :cancel}。"
  [url {:keys [headers body timeout parse-fn process-fn initial-state
               on-token on-complete on-error provider client]
        :or   {initial-state {}}}]
  (let [state    (atom initial-state)
        cancel-p (promise)
        ;; BodyHandler：先看状态码——2xx 走流式 subscriber；非 2xx 收错误体字符串
        handler  (reify HttpResponse$BodyHandler
                   (apply [_ info]
                     (if (<= 200 (.statusCode ^HttpResponse$ResponseInfo info) 299)
                       (HttpResponse$BodySubscribers/fromLineSubscriber
                         (line-subscriber {:parse-fn parse-fn :process-fn process-fn
                                           :on-token on-token :on-complete on-complete
                                           :on-error on-error}
                                          state cancel-p))
                       (HttpResponse$BodySubscribers/ofString StandardCharsets/UTF_8))))
        req      (build-request url headers body timeout)
        ^HttpClient http-client (or client @default-client)
        ^java.util.concurrent.CompletableFuture cf (.sendAsync http-client req handler)
        ;; 非 2xx：在 future 完成时把错误体转 canonical error → on-error（D5）
        cf2      (.thenApply cf
                   (reify Function
                     (apply [_ resp]
                       (let [^HttpResponse resp resp
                             status (.statusCode resp)]
                         (when-not (<= 200 status 299)
                           (let [body (.body resp)
                                 parsed (try (json/parse-string body true)
                                             (catch Exception _ body))]
                             (when on-error
                               (on-error (errors/http-response->error
                                           {:status status :body parsed}
                                           (or provider :unknown))))))
                         resp))))]
    {:future cf2
     :cancel (fn []
               (when (realized? cancel-p) (.cancel ^java.util.concurrent.Flow$Subscription @cancel-p))
               (.cancel cf true))}))

(defn post-stream-sync
  "post-stream-async 的同步包装：阻塞当前线程直至流结束，返回最终响应（保持同步签名）。

   之前 openai_compat / anthropic / dashscope 三处各自手写同一套
   「promise 对 + 包装 cancel + cond 分派」编排，现统一收敛于此。

   在 post-stream-async 的 opts 之外新增/替代：
   - :make-initial-state (fn [] state)        初始状态构造器（取消时再次调用以构建空响应）
   - :build-response     (fn [final-state])   流结束时构建最终响应
   - :retry              建链阶段重试（opt-in，同 http.retry/maybe-with-retry 约定：
                         true → 默认配置；map → 合并 default-retry-opts）。
                         仅当失败可重试（canonical error :retryable?）**且尚未向
                         on-token 流出任何 token** 时退避重试——token 已出说明调用方
                         已观察到部分输出，重试会重复内容，此时按原样抛错。
                         流式错误体不带响应头，故不支持 Retry-After，只走指数退避。
   （:initial-state / :on-complete / :on-error 由本函数接管，调用方不再传。）

   取消语义：包装的 cancel 登记到 im.ttalk.agent.streaming 的在途注册表（每次
   attempt 重新登记，替换为最新 cancel）；被调用时先标记本地 cancelled? 再取消上游。
   取消会让 java.net.http 触发 onError（连接中止）或让 future 抛 CancellationException
   ——故 @future 宽 catch，且 cond 里 cancelled? 优先于 err。取消返回空响应，不抛错、
   不重试。

   失败：err 已兑现 → 按 D5 canonical error 抛出；流结束但无结果 → :provider-error。"
  [url {:keys [make-initial-state build-response provider on-token retry] :as opts}]
  (let [retry-cfg (cond
                    (true? retry) retry/default-retry-opts
                    (map? retry)  (merge retry/default-retry-opts retry)
                    :else         nil)]
    (loop [attempt 0]
      (let [result (promise)
            err    (promise)
            tokens-out? (atom false)
            {:keys [future cancel]}
            (post-stream-async
              url
              (-> opts
                  (dissoc :make-initial-state :build-response :retry)
                  (assoc :initial-state (make-initial-state)
                         :on-token (when on-token
                                     (fn [t] (reset! tokens-out? true) (on-token t)))
                         :on-complete (fn [state] (deliver result (build-response state)))
                         :on-error (fn [e] (deliver err e)))))
            cancelled? (atom false)
            _ (streaming/register-cancel! (fn [] (reset! cancelled? true) (when cancel (cancel))))]
        (try @future                     ;; 阻塞直到流结束（保持同步签名）
             (catch Throwable _ nil))
        (cond
          @cancelled?        (build-response (make-initial-state))
          (realized? err)
          (let [e @err]
            (if (and retry-cfg
                     (< attempt (:max-retries retry-cfg))
                     (:retryable? e)
                     (not @tokens-out?))
              (do (Thread/sleep (long (retry/compute-backoff attempt retry-cfg)))
                  (recur (inc attempt)))
              (errors/throw! e)))
          (realized? result) @result
          :else (errors/throw! (errors/error :provider-error
                                             "流式响应未产出结果"
                                             {:provider provider})))))))
