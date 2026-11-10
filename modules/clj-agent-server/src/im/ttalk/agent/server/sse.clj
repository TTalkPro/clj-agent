(ns im.ttalk.agent.server.sse
  "SSE 响应（C# SseResponse / SseStream），**Ring 的写法**：处理器返回 `(response req opts producer)` —— 一个 Ring 响应 map，
   正文是 `ring.core.protocols/StreamableResponseBody`；服务端写正文时调 `(producer s)`，producer 在那条线程上往流里写帧
   （每帧冲一次），返回即收尾。帧由 im.ttalk.net.sse（`event-frame` / `comment-frame`）或调用方自己拼好。

   流（producer 收到的 `s`）：写入串行化（心跳与业务写入互斥）；写失败（对端走了）不抛，记为 `gone?`，后续写入直接丢弃 ——
   调用方据此退订。`aborted` 是一个 CompletableFuture：对端断开（在写的那一刻发现）、服务端停机（宿主经请求里的
   :im.ttalk.agent.server/open-streams 通知）或 `close!` 时完成。

   ⚠️ 对端断开**只在写的时候**发现：Jetty 的阻塞式 servlet 输出在不写的时候不报告连接已断（Ring 的正文协议也没有这个通道），
   所以闲着的流靠心跳（:heartbeat-ms）发现断连。C# 的 RequestAborted 还盯着套接字的读端。"
  (:require [im.ttalk.http.sse :as frame]
            [ring.core.protocols :as protocols])
  (:import (java.io IOException OutputStream)
           (java.util Set)
           (java.util.concurrent CompletableFuture TimeUnit TimeoutException)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)
           (java.util.concurrent.locks ReentrantLock)))

(set! *warn-on-reflection* true)

(def heartbeat-text
  "心跳注释的正文（`: clj-agent-alive`）。"
  "clj-agent-alive")

(def headers
  "SSE 的响应头（与 keel agui/http.ss 的 %sse-headers 同一组，⛔ 不写 Content-Length）。
   X-Accel-Buffering：nginx 缺省缓冲上游响应 ⇒ SSE「一次全到」，这个头是关它的开关。"
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache, no-transform"
   "X-Accel-Buffering" "no"})

(defn- now-ms ^long [] (quot (System/nanoTime) 1000000))

(defn aborted
  "对端断开、服务端停机或 close! 时完成的 CompletableFuture。"
  ^CompletableFuture [s]
  (:aborted s))

(defn gone?
  "写不出去了（对端走了 / 停机 / 已 close!）。"
  [s]
  (or (.get ^AtomicBoolean (:gone s)) (.isDone (aborted s))))

(defn- mark-gone! [s]
  (.set ^AtomicBoolean (:gone s) true)
  (.complete (aborted s) true))

(defn write-frames!
  "写一批帧（编好的 SSE 字节），末尾冲一次。回 false = 写不出去了。"
  [s frames]
  (let [^ReentrantLock lock (:lock s)]
    (if (gone? s)
      false
      (do (.lock lock)
          (try
            (if (gone? s)
              false
              (let [^OutputStream out (:out s)]
                (doseq [^bytes f frames] (.write out f))
                (.flush out)
                (when (seq frames) (.set ^AtomicLong (:last-write s) (now-ms)))
                true))
            (catch IOException _ (mark-gone! s) false)
            (catch IllegalStateException _ (mark-gone! s) false)
            (finally (.unlock lock)))))))

(defn write-frame!
  "写一帧，写完冲出去。回 false = 写不出去了。"
  [s ^bytes f]
  (write-frames! s [f]))

(defn write-event!
  "写一个事件（`data: …\\n\\n`；opts 同 im.ttalk.net.sse/event-frame：:event :id :retry-ms）。"
  ([s data] (write-event! s data nil))
  ([s data opts] (write-frame! s (frame/event-frame data opts))))

(defn write-comment!
  "写一条注释（`: text\\n\\n`）—— 客户端解析器不把它当事件。"
  [s text]
  (write-frame! s (frame/comment-frame text)))

(defn- heartbeat-loop
  "闲够 period 就写一帧心跳（缺省 SSE 注释，给了 :heartbeat-frame 就每次现调它）—— 空闲超时的代理会把不出声的流当死连接掐掉；
   在 JVM 上它还是发现对端断开的那一次写。"
  [s ^long period frame-fn]
  (let [comment (frame/comment-frame heartbeat-text)]
    (loop []
      (when-not (gone? s)
        (let [idle (- (now-ms) (.get ^AtomicLong (:last-write s)))
              wait (- period idle)]
          (if (pos? wait)
            (when (try (.get (aborted s) wait TimeUnit/MILLISECONDS) false
                       (catch TimeoutException _ true)
                       (catch InterruptedException _ false))
              (recur))
            (when (write-frame! s (if frame-fn (frame-fn) comment))
              (recur))))))))

(defn await-aborted
  "阻塞到对端断开 / 停机 / close!，最多 `timeout-ms`（nil = 不限）。回 true = 已中止。"
  ([s] (await-aborted s nil))
  ([s timeout-ms]
   (try
     (if timeout-ms
       (do (.get (aborted s) (long timeout-ms) TimeUnit/MILLISECONDS) true)
       (do (.get (aborted s)) true))
     (catch TimeoutException _ false))))

(defn close!
  "停心跳、不再写（⛔ 不关正文流：正文随 producer 返回而结束）。幂等。"
  [s]
  ;; ⛔ 不 interrupt 心跳线程：虚拟线程在套接字 IO 上被中断会把套接字关掉。置位 + 完成 aborted，它自己退出
  (mark-gone! s)
  (when-let [^Set streams (:open-streams s)] (.remove streams (aborted s)))
  (when-let [^Thread t @(:heartbeat s)] (.join t 2000))
  nil)

(defn- open-stream
  "正文流到手：登记停机信号、冲出响应头、起心跳。"
  [req ^OutputStream out {:keys [heartbeat-ms heartbeat-frame]}]
  (let [ab (CompletableFuture.)
        streams (:im.ttalk.agent.server/open-streams req)
        s {:req req :out out :lock (ReentrantLock.) :gone (AtomicBoolean. false)
           :aborted ab :last-write (AtomicLong. (now-ms)) :heartbeat (atom nil) :open-streams streams}]
    ;; 停机信号：登记进宿主的「开着的流」表，宿主停机时逐个完成（⛔ 不在一只服务端级的 future 上挂回调：长跑的服务端上
    ;; 它会一条连接一条地攒下去）
    (when streams (.add ^Set streams ab))
    (when-let [^CompletableFuture stopping (:im.ttalk.agent.server/stopping req)]
      (when (.isDone stopping) (.complete ab true)))
    (write-frames! s [])                                   ; 立刻把头冲出去（客户端据此知道流开了）
    (when (and heartbeat-ms (pos? (long heartbeat-ms)))
      (reset! (:heartbeat s)
              (Thread/startVirtualThread ^Runnable (fn [] (heartbeat-loop s (long heartbeat-ms) heartbeat-frame)))))
    s))

(defn body
  "SSE 正文（StreamableResponseBody）：服务端写正文时开流、调 `(producer s)`、收尾 close!。"
  [req opts producer]
  (reify protocols/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [s (open-stream req out opts)]
        (try (producer s)
             (finally
               (close! s)
               (try (.close ^OutputStream out) (catch IOException _ nil))))))))

(defn response
  "SSE 响应（Ring map）：状态码（缺省 200）+ SSE 头（并上 opts 的 :headers）+ 流式正文。
   opts：:heartbeat-ms（闲够这么久写一帧心跳；nil / <= 0 = 不发）、:heartbeat-frame（(fn [] 字节)，每次心跳现调；
   缺省注释帧 `: clj-agent-alive` —— EventSource 不把注释交给任何回调，前端看门狗要看得见心跳时给一个数据帧）、:status、:headers。"
  ([req producer] (response req nil producer))
  ([req opts producer]
   {:status (or (:status opts) 200)
    :headers (merge headers (:headers opts))
    :body (body req opts producer)}))
