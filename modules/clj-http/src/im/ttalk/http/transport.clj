(ns im.ttalk.http.transport
  "Ring 风格出站 HTTP transport 契约：普通 request map 进，普通 response map 出，
   `CompletableFuture` / 按行流式是异步与流式边界。transport 是普通 map
   （`:request` / `:request-async` / `:stream-lines` / `:capabilities`），
   provider 只认识本 ns，不认识 JDK / Apache / Netty 类型。"
  (:require [clojure.string :as str])
  (:import [java.util.concurrent CompletableFuture]))

(set! *warn-on-reflection* true)

;;; error

(def ^:private io-kinds
  #{:name-resolution-error :connection-error :secure-connection-error
    :timeout-error :cancelled-error :unknown-io})

(defn error
  "构造 transport exception。`kind` 见任务 H-07；`:invalid-request` 不是 IO 错。"
  [message kind cause data]
  (ex-info message {::error kind ::cause cause ::data (or data {})}))

(defn error?
  ([t] (error? t nil))
  ([t kind]
   (let [d (ex-data t)]
     (boolean (and (contains? d ::error)
                   (or (nil? kind) (= kind (::error d))))))))

(defn io-error?
  [t]
  (boolean (contains? io-kinds (::error (ex-data t)))))

(defn invalid-request!
  ([message] (invalid-request! message nil nil))
  ([message cause data] (throw (error message :invalid-request cause data))))

;;; request/response helpers

(defn- header-name ^String [x]
  (str/lower-case (if (keyword? x) (name x) (str x))))

(defn normalize-headers
  "任意调用方便利形状 ⇒ 小写 key + string vector。"
  [headers]
  (persistent!
   (reduce (fn [acc [k v]]
             (let [values (cond
                            (nil? v) []
                            (string? v) [v]
                            (or (sequential? v) (instance? java.util.List v)) (vec v)
                            :else [(str v)])
                   values (mapv str values)]
               (if (seq values)
                 (assoc! acc (header-name k) values)
                 acc)))
           (transient {})
           (or headers {}))))

(defn normalize-request
  "契约 request map 归一化：header 规范化、补 JSON content-type、校验 body 与 `:as`。"
  [{:keys [method url headers body as] :or {method :get} :as req}]
  (when-not (map? req) (invalid-request! "HTTP request 必须是 map"))
  (when (nil? url) (invalid-request! "HTTP request 缺 :url"))
  (when-not (or (string? url) (instance? java.net.URI url))
    (invalid-request! ":url 只收 string / URI"))
  (when (and (some? body) (not (or (string? body) (bytes? body))))
    (invalid-request! ":body v1 只收 nil / string / bytes"))
  (let [as (or as :string)]
    (when-not (#{:string :bytes :discard} as)
      (invalid-request! ":as 只收 :string / :bytes / :discard"))
    (let [hs (normalize-headers headers)]
      (-> req
        (assoc :method (if (keyword? method) method (keyword (str/lower-case (str method))))
               :url url
               :as as
               :headers hs)
          (cond-> (and (some? body) (not (contains? hs "content-type")))
            (assoc-in [:headers "content-type"] ["application/json"]))))))

(defn normalize-response
  "adapter response ⇒ 契约形状（status 整数、header 规范化）。"
  [{:keys [status headers] :as resp}]
  (when-not (integer? status) (invalid-request! "response :status 必须是整数"))
  (-> resp
      (assoc :status (int status) :headers (normalize-headers headers))
      (dissoc :error)))

;;; contract

(def ^:private required-fns [:request :request-async :stream-lines])

(defn transport?
  [t]
  (boolean (and (map? t)
                (set? (:capabilities t))
                (every? #(fn? (get t %)) required-fns)
                (or (nil? (:close t)) (fn? (:close t))))))

(defn assert-transport!
  [t]
  (when-not (map? t) (invalid-request! ":http-transport 必须是 map"))
  (doseq [k required-fns]
    (when-not (fn? (get t k))
      (invalid-request! (str ":http-transport 缺函数 " k))))
  (when-not (set? (:capabilities t))
    (invalid-request! ":http-transport :capabilities 必须是 set"))
  (when (and (contains? t :close) (not (fn? (:close t))))
    (invalid-request! ":http-transport :close 必须是函数"))
  t)

(defn request
  [t req]
  (assert-transport! t)
  (let [req (normalize-request req)]
    ((:request t) req)))

(defn request-async
  ^CompletableFuture [t req]
  (assert-transport! t)
  (let [req (normalize-request req)]
    ((:request-async t) req)))

(defn stream-lines
  [t req]
  (assert-transport! t)
  (let [req (normalize-request req)]
    (when-not (fn? (:on-line req))
      (invalid-request! "stream request 缺 :on-line"))
    ((:stream-lines t) req)))

(defn close
  [t]
  (when-some [f (:close t)] (f)))

;;; middleware helpers（设计 §5 的最小集合）

(defn wrap-default-headers
  [t defaults]
  (let [defaults (normalize-headers defaults)
        wrap (fn [f] (fn [req] (f (update req :headers #(merge defaults (normalize-headers %))))))]
    (-> t
        (update :request wrap)
        (update :request-async wrap)
        (update :stream-lines wrap))))

(defn wrap-request
  [t f]
  (let [wrap (fn [g] (fn [req] (g (f req))))]
    (-> t
        (update :request wrap)
        (update :request-async wrap)
        (update :stream-lines wrap))))
