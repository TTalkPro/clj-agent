(ns im.ttalk.agent.server.adapter.undertow
  "Undertow（`luminus/ring-undertow-adapter`）的垫片：让它也能发 **Ring SPEC 的流式正文**
   （`ring.core.protocols/StreamableResponseBody`）。

   luminus 的 adapter 不认识 SPEC 的流式正文（直接 500「Body class not supported」），InputStream / seq 正文又是
   攒满了才发（designs/server-module.md §1 的实测）。它的正文分派是一个**开放协议**
   `ring.adapter.undertow.response/RespondBody`——`install!` 给它补上 `StreamableResponseBody` 接口的实现：
   离开 IO 线程、`startBlocking`、把 exchange 的 OutputStream 交给 `write-body-to-stream`（每次 flush 即推送）、收尾。

   ```clojure
   (require '[ring.adapter.undertow :refer [run-undertow]]
            '[im.ttalk.agent.server.adapter.undertow :as ut-shim])
   (ut-shim/install!)                       ;; 进程里调一次（幂等）
   (run-undertow handler {:port 3000})
   ```

   只影响**直接实现**该接口的正文（reify / deftype）；String、InputStream 等照旧走 adapter 自己的实现。
   不引 Undertow 的硬依赖：`requiring-resolve` 取协议，用 Undertow 的人自己的 classpath 上有它。"
  (:require [ring.core.protocols :as protocols])
  (:import (java.io IOException)))

;; ⚠️ 本 ns 故意走反射：给 io.undertow.server.HttpServerExchange 加类型提示，编译期就要求 Undertow 在 classpath 上——
;; 那样不用 Undertow 的人（以及 check-docs 加载全部 ns 时）连这个 ns 都加载不了。每个响应只调几次，反射开销可忽略。
(set! *warn-on-reflection* false)

(defonce ^:private installed? (atom false))

(defn- respond [body ex]
  (do
    (if (.isInIoThread ex)
      (.dispatch ex ^Runnable (fn [] (respond body ex)))
      (do (.startBlocking ex)
          (try (protocols/write-body-to-stream body nil (.getOutputStream ex))
               (catch IOException _ nil)
               (finally (.endExchange ex)))))))

(defn install!
  "给 luminus adapter 的 `RespondBody` 协议补上 SPEC 流式正文的实现。幂等；回 true。"
  []
  (when (compare-and-set! installed? false true)
    (extend ring.core.protocols.StreamableResponseBody
      @(requiring-resolve 'ring.adapter.undertow.response/RespondBody)
      {:respond respond}))
  true)
