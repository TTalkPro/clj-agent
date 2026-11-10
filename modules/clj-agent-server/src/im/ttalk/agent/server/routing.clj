(ns im.ttalk.agent.server.routing
  "路由（C# 里 ASP.NET 的 MapGet / MapPost / Map / MapGroup 那一层，够 Agui.Server 用的大小）：路由表 ⇒ 一个 **Ring 处理器**。

   为什么不用 reitit：它对「字面量段 vs 参数段」的冲突要么装配期报错、要么退成按登记顺序的线性匹配，而 C# 的契约是**按具体度**选
   （宿主后登记的 `agent/mine/stop/:id` 要胜过先登记的 `agent/:id/stop/:id`），还要「路径中、方法不中 ⇒ 405 + Allow，
   兜底段（任何方法）在时 ⇒ 不是 405」。这一百来行比把 reitit 的冲突解析与缺省处理器拧成这个样子更直接。

   一条路 = `[方法 模式 处理器]`：
     方法   :any | \"GET\" | #{\"GET\" \"HEAD\"}（大写）
     模式   \"/agent/:id/run\"：字面量段、`:名` 参数段（匹配一段，值已解码）、末尾 `*名` 兜底段（匹配零或多段，值是余下的
            路径，段间以 / 连接）。字面量段大小写不敏感（ASP.NET 同）；末尾的 / 不计（\"/a/\" 与 \"/a\" 是同一条）。
     处理器 Ring 处理器 (fn [req] 响应)，req 带 :path-params。返回 nil ⇒ 200 空正文（ASP.NET 同：处理器什么都没写）。

   选路（ASP.NET 的口径）：路径匹配的那些里挑**最具体**的 —— 逐段比，字面量 < 参数 < 兜底，同等时先登记的胜；方法不中的跳过。
   路径中了而方法一条都不中 ⇒ 405 + `Allow`；路径都不中 ⇒ 404（正文为空）。
   产出的处理器两种元数都有（Ring 异步的 3 元：respond / raise 在同一线程上调）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.server.http :as http]))

(set! *warn-on-reflection* true)

(defn- compile-segment [^String s]
  (cond
    (str/starts-with? s ":") {:kind :param :rank 1 :name (keyword (subs s 1))}
    (str/starts-with? s "*") {:kind :rest :rank 2 :name (when (> (count s) 1) (keyword (subs s 1)))}
    :else {:kind :literal :rank 0 :value s}))

(defn compile-pattern
  "模式串 ⇒ 段描述的向量。兜底段只能在最后。"
  [^String pattern]
  (let [segs (mapv compile-segment (remove empty? (str/split (or pattern "") #"/")))]
    (when (some #(= :rest (:kind %)) (butlast segs))
      (throw (IllegalArgumentException. (str "catch-all segment must be last: " pattern))))
    segs))

(defn- method-set [m]
  (cond (= :any m) :any
        (string? m) #{(str/upper-case m)}
        :else (into #{} (map str/upper-case) m)))

(defn route
  "造一条路（也可以直接写 `[方法 模式 处理器]` 向量）。"
  [methods pattern handler]
  {:methods (method-set methods) :pattern pattern :segments (compile-pattern pattern) :handler handler})

(defn- ->route [r]
  (if (map? r) r (let [[m p h] r] (route m p h))))

(defn join-path
  "前缀 + 模式（处理两头的 /）。"
  [prefix pattern]
  (let [a (str/replace (or prefix "") #"/+$" "")
        b (str/replace (or pattern "") #"^/+" "")]
    (cond (empty? b) (if (empty? a) "/" a)
          :else (str a "/" b))))

(defn group
  "给一组路加前缀（C# MapGroup）。保留 `routes` 的元数据。"
  [prefix routes]
  (with-meta (mapv (fn [r] (let [{:keys [methods pattern handler]} (->route r)]
                             (route methods (join-path prefix pattern) handler)))
                   routes)
    (meta routes)))

(defn- match [segments path-segs]
  (loop [ss segments ps path-segs params {}]
    (if-let [s (first ss)]
      (case (:kind s)
        :rest (cond-> params (:name s) (assoc (:name s) (str/join "/" ps)))
        :literal (when (and (seq ps) (.equalsIgnoreCase ^String (:value s) ^String (first ps)))
                   (recur (rest ss) (rest ps) params))
        :param (when (seq ps)
                 (recur (rest ss) (rest ps) (assoc params (:name s) (first ps)))))
      (when (empty? ps) params))))

(defn- specificity-compare
  "逐段比 rank；某一边先走完 ⇒ 它更具体（\"/a\" 胜过 \"/a/*rest\" 对路径 /a 而言）。"
  [a b]
  (loop [a (map :rank (:segments a)) b (map :rank (:segments b))]
    (cond (and (empty? a) (empty? b)) 0
          (empty? a) -1
          (empty? b) 1
          (not= (first a) (first b)) (compare (first a) (first b))
          :else (recur (rest a) (rest b)))))

(defn- allows? [{:keys [methods]} method]
  (or (= :any methods) (contains? methods method)))

(defn select
  "对 (方法, 解码后的段) 选路 ⇒ `[:ok 路 参数]` / `[:method-not-allowed 允许的方法集]` / `[:not-found]`。"
  [routes method path-segs]
  (let [hits (keep-indexed (fn [i r] (when-let [p (match (:segments r) path-segs)] [i r p])) routes)
        ordered (sort (fn [[ia ra] [ib rb]]
                        (let [c (specificity-compare ra rb)] (if (zero? c) (compare ia ib) c)))
                      hits)]
    (if-let [[_ r p] (first (filter (fn [[_ r]] (allows? r method)) ordered))]
      [:ok r p]
      (if (seq ordered)
        [:method-not-allowed (into (sorted-set) (mapcat (comp :methods second)) ordered)]
        [:not-found]))))

(defn router
  "路由表 ⇒ Ring 处理器。"
  [routes]
  (let [routes (mapv ->route routes)
        handle (fn [req]
                 (let [[tag a b] (select routes (http/method req) (http/split-path (:uri req)))]
                   (case tag
                     :ok (or ((:handler a) (assoc req :path-params b :route (:pattern a)))
                             (http/response 200))
                     :method-not-allowed (http/response 405 {"Allow" (str/join ", " a)} nil)
                     (http/response 404))))]
    (fn
      ([req] (handle req))
      ([req respond raise]
       (try (respond (handle req)) (catch Throwable t (raise t)))))))
