(ns im.ttalk.http.test-server
  "测试用本机回环 HTTP 服务（JDK 自带 com.sun.net.httpserver，不加测试依赖）。

   `(with-server [url handler] body…)`：handler 收 HttpExchange，自己写响应。"
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(defn start ^HttpServer [handler]
  (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
    (.createContext "/" (reify HttpHandler
                          (handle [_ ex]
                            (try (handler ex)
                                 (catch Throwable t (.printStackTrace t))
                                 (finally (.close ^HttpExchange ex))))))
    (.setExecutor (Executors/newVirtualThreadPerTaskExecutor))
    (.start)))

(defn url [^HttpServer s] (str "http://127.0.0.1:" (.getPort (.getAddress s))))

(defmacro with-server [[url-sym handler] & body]
  `(let [s# (start ~handler)
         ~url-sym (url s#)]
     (try ~@body (finally (.stop s# 0)))))

(defn read-body ^String [^HttpExchange ex]
  (String. (.readAllBytes (.getRequestBody ex)) StandardCharsets/UTF_8))

(defn respond!
  "写一个完整响应。`headers`：{名 值或值向量}。"
  ([ex status body] (respond! ex status nil body))
  ([^HttpExchange ex status headers ^String body]
   (doseq [[k v] headers, x (if (sequential? v) v [v])]
     (.add (.getResponseHeaders ex) (str k) (str x)))
   (let [b (.getBytes (or body "") StandardCharsets/UTF_8)]
     (.sendResponseHeaders ex (int status) (if (zero? (alength b)) -1 (alength b)))
     (when (pos? (alength b))
       (with-open [o (.getResponseBody ex)] (.write o b))))))

(defn start-chunked!
  "开始一个分块响应，回 OutputStream（调用方逐块写、flush）。"
  ^java.io.OutputStream [^HttpExchange ex status headers]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) (str k) (str v)))
  (.sendResponseHeaders ex (int status) 0)
  (.getResponseBody ex))
