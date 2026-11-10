(ns im.ttalk.agent.server.adapter.http-kit
  "http-kit 的垫片：让它也能发 **Ring SPEC 的流式正文**（`ring.core.protocols/StreamableResponseBody`）。

   http-kit 2.8 的 adapter 不认识 SPEC 的流式正文（直接 500「not understandable」），惰性 seq / 管道 InputStream 正文
   它也是攒满了才发（designs/server-module.md §1 的实测）。本中间件把这样的响应改走 http-kit 自己的 `as-channel`：
   先发状态码与头，再在一条虚拟线程上 `write-body-to-stream` 到一个转发给 `send!` 的 OutputStream，每次 write 即推送。

   ```clojure
   (require '[org.httpkit.server :as hk]
            '[im.ttalk.agent.server.adapter.http-kit :as hk-shim])
   (hk/run-server (hk-shim/wrap-streaming handler) {:port 3000})
   ```

   - 只改**直接实现** `StreamableResponseBody` 接口的正文（reify / deftype，如 `im.ttalk.agent.server.sse` 的 SSE 正文）；
     String、byte[]、File、InputStream、seq 这些 Ring 经 `extend` 支持的照旧交给 http-kit。不是 http-kit 发来的请求
     （没有 `:async-channel`）原样放过。
   - 对端断开（http-kit 的 `:on-close`）⇒ 之后的写抛 IOException ⇒ SSE 流记 `gone?`、producer 据此收尾。
     ⚠️ **已知局限**（http-kit 2.8 实测，2026-11-10）：客户端断开后 http-kit 的 `send!` 仍回 true、`open?` 仍为 true、
     `:on-close` 不触发（关套接字后连写 40 次、每次 8 KB 都「成功」）。所以在 http-kit 上 **SSE 发现不了断连**：流一直写到
     producer 自己结束——AG-UI 的 run 照常跑完、`/stop` 照常可用，但 `:orphan-cancel-ms`（最后一个订阅者走了就按停）不会生效。
     要断连检测请用 Jetty（缺省）或 Undertow。
   - 不引 http-kit 的硬依赖：`requiring-resolve` 取 `org.httpkit.server`，用 http-kit 的人自己的 classpath 上有它。"
  (:require [ring.core.protocols :as protocols])
  (:import (java.io IOException OutputStream)
           (java.util Arrays)
           (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defn streaming-body?
  "正文直接实现 SPEC 的流式正文接口（reify / deftype）？"
  [body]
  (instance? ring.core.protocols.StreamableResponseBody body))

(defn- channel-stream
  "转发给 http-kit channel 的 OutputStream：每次 write 即 `send!`（不关连接）；channel 关了 ⇒ 抛 IOException。"
  ^OutputStream [ch send! ^AtomicBoolean closed?]
  (let [push (fn [^bytes b]
               (when (or (.get closed?) (not (send! ch b false)))
                 (throw (IOException. "http-kit channel 已关闭"))))]
    (proxy [OutputStream] []
      (write
        ([b] (if (bytes? b)
               (push b)
               (push (byte-array [(unchecked-byte b)]))))
        ([b off len] (push (Arrays/copyOfRange ^bytes b (int off) (int (+ (int off) (int len)))))))
      (flush [])
      (close []))))

(defn wrap-streaming
  "Ring 中间件：把正文是 SPEC 流式正文的响应改走 http-kit 的 `as-channel`（见 ns 文档）。同步处理器用。"
  [handler]
  (fn [req]
    (let [resp (handler req)
          body (:body resp)]
      (if-not (and (:async-channel req) (streaming-body? body))
        resp
        (let [as-channel (requiring-resolve 'org.httpkit.server/as-channel)
              send! (requiring-resolve 'org.httpkit.server/send!)
              close (requiring-resolve 'org.httpkit.server/close)
              closed? (AtomicBoolean. false)]
          (as-channel req
                      {:on-close (fn [_ch _status] (.set closed? true))
                       :on-open (fn [ch]
                                  (send! ch (assoc (dissoc resp :body) :status (or (:status resp) 200)) false)
                                  (Thread/startVirtualThread
                                    ^Runnable
                                    (fn []
                                      (try (protocols/write-body-to-stream body resp (channel-stream ch send! closed?))
                                           (catch IOException _ nil)
                                           (finally (close ch))))))}))))))
