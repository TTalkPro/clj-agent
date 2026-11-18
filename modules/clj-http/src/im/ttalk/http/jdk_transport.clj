(ns im.ttalk.http.jdk-transport
  "把既有 JDK `im.ttalk.http` 实现包成 transport map。旧公开 API 不变。"
  (:require [im.ttalk.http :as http]
            [im.ttalk.http.failure :as failure]
            [im.ttalk.http.transport :as transport])
  (:import [java.io IOException]))

(set! *warn-on-reflection* true)

(defn- cause-chain [t]
  (take-while some? (iterate #(.getCause ^Throwable %) t)))

(defn- has-cause? [^Class cls t]
  (boolean (some #(instance? cls %) (cause-chain t))))

(defn- wrap-throwable ^Throwable [^Throwable t]
  (if (transport/error? t)
    t
    (let [data (ex-data t)
          kind (cond
                 (::http/timeout data) :timeout-error
                 (or (instance? java.net.http.HttpTimeoutException t)
                     (has-cause? java.net.http.HttpTimeoutException t)
                     (instance? java.util.concurrent.TimeoutException t)) :timeout-error
                 (and (not= :unknown (failure/classify t))
                      (not (or (instance? java.net.http.HttpTimeoutException t)
                               (has-cause? java.net.http.HttpTimeoutException t))))
                 (failure/classify t)
                 (has-cause? javax.net.ssl.SSLException t) :secure-connection-error
                 (or (instance? java.net.ConnectException t)
                     (has-cause? java.net.ConnectException t)) :connection-error
                 (instance? java.lang.IllegalArgumentException t) :invalid-request
                 (instance? IOException t) :unknown-io
                 :else :unknown-io)]
      (transport/error (or (.getMessage t) (.getName (class t))) kind t
                       (if-let [phase (::http/timeout data)]
                         {:phase phase :limit-ms (:limit-ms data)}
                         {})))))

(defn- try-call [f]
  (try (f) (catch Throwable t (throw (wrap-throwable t)))))

(defn- wrap-future ^java.util.concurrent.CompletableFuture [f]
  (.handle ^java.util.concurrent.CompletableFuture f
           (reify java.util.function.BiFunction
             (apply [_ v t]
               (if t (throw (wrap-throwable t)) v)))))

(defn transport
  "构造 JDK transport。选项与 `im.ttalk.http/client` 相同；`:client` 可直接注入已构造好的
   JDK client（只属于本 adapter）。"
  ([] (transport nil))
  ([{:keys [client] :as opts}]
    (let [c (delay (or client (http/client opts)))]
      {:name :jdk
       :capabilities #{:string :bytes :discard :input-stream :stream-lines}
       :request (fn [req] (try-call #(http/request (assoc req :client @c))))
       :request-async (fn [req] (wrap-future (try-call #(http/request-async (assoc req :client @c)))))
       :stream-lines (fn [req]
                       (update (http/stream-lines (assoc req :client @c))
                               :future wrap-future))})))
