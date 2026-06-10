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
            [im.ttalk.agent.model.error :as errors])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandler HttpResponse$BodySubscribers]
           [java.util.concurrent Flow$Subscriber]
           [java.util.function Function]
           [java.time Duration]
           [java.nio.charset StandardCharsets]))

;;; ============================================================
;;; 共享 HttpClient（连接池复用；executor 可换虚拟线程）
;;; ============================================================

(defonce ^{:doc "默认共享 HttpClient。executor 用虚拟线程：回调跑在虚拟线程上，
   高并发流不被固定线程池饿死；JDK 24+ 起 synchronized 不再 pin 虚拟线程。"}
  default-client
  (delay
    (-> (HttpClient/newBuilder)
        (.connectTimeout (Duration/ofSeconds 30))
        (.executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))
        (.build))))

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
                     (if (<= 200 (.statusCode info) 299)
                       (HttpResponse$BodySubscribers/fromLineSubscriber
                         (line-subscriber {:parse-fn parse-fn :process-fn process-fn
                                           :on-token on-token :on-complete on-complete
                                           :on-error on-error}
                                          state cancel-p))
                       (HttpResponse$BodySubscribers/ofString StandardCharsets/UTF_8))))
        req      (build-request url headers body timeout)
        cf       (.sendAsync ^HttpClient (or client @default-client) req handler)
        ;; 非 2xx：在 future 完成时把错误体转 canonical error → on-error（D5）
        cf2      (.thenApply cf
                   (reify Function
                     (apply [_ resp]
                       (let [status (.statusCode resp)]
                         (when-not (<= 200 status 299)
                           (let [parsed (try (json/parse-string (.body resp) true)
                                             (catch Exception _ (.body resp)))]
                             (when on-error
                               (on-error (errors/http-response->error
                                           {:status status :body parsed}
                                           (or provider :unknown))))))
                         resp))))]
    {:future cf2
     :cancel (fn []
               (when (realized? cancel-p) (.cancel ^java.util.concurrent.Flow$Subscription @cancel-p))
               (.cancel cf true))}))
