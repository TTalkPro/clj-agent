(ns im.ttalk.agent.provider.common.cache
  "Anthropic Prompt Caching 策略层（对标 Spring AI AnthropicCacheStrategy）

   ========================================
   背景
   ========================================

   Anthropic 通过在内容块上挂 `cache_control` 来缓存请求前缀，命中后该段
   输入 token 仅按约 0.1x 计费。要点（以官方为准）：

   - 渲染顺序：tools -> system -> messages。`cache_control` 标记的是\"断点\"
     (breakpoint)，断点之前的全部内容作为可缓存前缀。
   - 单请求最多 4 个 breakpoint。
   - 最小可缓存前缀与模型相关（Opus/Haiku4.5 = 4096 token，Sonnet4.x = 1024/2048）；
     过短不会缓存（无报错，cache_creation_input_tokens=0）。
   - 任意字节变化都会使该位置之后的缓存失效 —— 稳定内容靠前，易变内容靠后。

   ========================================
   策略（cache-strategy）
   ========================================

   :none              不缓存（默认）
   :system            仅缓存 system
   :tools             仅缓存工具定义
   :system-and-tools  缓存 system + tools（两个 breakpoint）
   :conversation      缓存到\"当前用户问题之前\"的对话历史
                      （标记最后一条消息的最后一个内容块）
   :tool-results      缓存到最后一个 tool_result（多轮工具循环里跨轮复用工具结果，
                      标记 messages 中最后一个 type=tool_result 的内容块）
   :system-and-conversation  同时缓存 system 与对话历史（两个 breakpoint）

   ttl（:cache-ttl）两种形状：
   - 标量：nil -> 默认 5 分钟；\"1h\" -> 1 小时；本策略的**所有**断点同一档（老用法）。
   - 按断点给档的 map：键 :tools / :system / :conversation / :tool-results，
     缺键或 nil = 5 分钟（cache_control 不带 ttl 字段）。典型：
       {:cache-strategy :system-and-conversation
        :cache-ttl {:system \"1h\" :conversation nil}}
     ⇒ 前缀（tools+system）1h、对话尾巴 5m——尾巴每轮被取代，付 1h 的 2× 写入费纯亏
     （feedbacks/2026-11-10-provider-request-hook-and-two-tier-cache-ttl.md）。

   服务端规则：**长 TTL 的断点必须排在短 TTL 之前**（渲染顺序 tools → system → messages）。
   apply-anthropic-cache 在注入前校验，违者抛 ex-info（:type :invalid-cache-ttl），
   不等服务端 400。任一断点为 \"1h\" 时 anthropic provider 自动并入 beta
   `extended-cache-ttl-2025-04-11`（兼容端点如 MiniMax /anthropic 少了它会静默退回 5m）。

   用法见 apply-anthropic-cache。"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def max-breakpoints
  "Anthropic 单请求 cache_control breakpoint 上限"
  4)

(defn ephemeral
  "构造 ephemeral cache_control

   (ephemeral nil)  ; => {:type \"ephemeral\"}
   (ephemeral \"1h\") ; => {:type \"ephemeral\" :ttl \"1h\"}"
  [ttl]
  (cond-> {:type "ephemeral"}
    ttl (assoc :ttl ttl)))

(defn- has-cache-control?
  "内容块/工具是否已带 cache_control"
  [block]
  (and (map? block) (contains? block :cache_control)))

(defn- count-breakpoints
  "统计 params 中已有的 cache_control 断点数（system blocks + tools + messages 末块）"
  [params]
  (let [sys (:system params)
        sys-n (cond
                (string? sys) 0
                (sequential? sys) (count (filter has-cache-control? sys))
                :else 0)
        tools-n (count (filter has-cache-control? (:tools params)))
        msgs-n (->> (:messages params)
                    (mapcat (fn [m] (let [c (:content m)]
                                      (if (sequential? c) c []))))
                    (filter has-cache-control?)
                    count)]
    (+ sys-n tools-n msgs-n)))

;; ============================================================
;; system 块化 + 打标
;; ============================================================

(defn- system->blocks
  "把 system 规整为内容块向量

   - 字符串 -> [{:type \"text\" :text s}]
   - 已是块向量 -> 原样
   - nil/空 -> nil"
  [system]
  (cond
    (and (string? system) (not (str/blank? system)))
    [{:type "text" :text system}]

    (sequential? system) (vec system)

    :else nil))

(defn- mark-last
  "给向量最后一个元素挂 cache_control（若尚未挂）"
  [v cc]
  (if (seq v)
    (let [idx (dec (count v))]
      (update v idx (fn [el]
                      (if (has-cache-control? el)
                        el
                        (assoc el :cache_control cc)))))
    v))

(defn- cache-system
  "缓存 system：块化并在最后一块打断点"
  [params cc]
  (if-let [blocks (system->blocks (:system params))]
    (assoc params :system (mark-last blocks cc))
    params))

(defn- cache-tools
  "缓存 tools：在最后一个工具上打断点"
  [params cc]
  (if (seq (:tools params))
    (update params :tools mark-last cc)
    params))

(defn- message-content->blocks
  "把单条消息的 content 规整为块向量（字符串 -> text 块）"
  [content]
  (cond
    (sequential? content) (vec content)
    (and (string? content) (not (str/blank? content)))
    [{:type "text" :text content}]
    :else nil))

(defn- cache-conversation
  "缓存对话历史：标记最后一条消息的最后一个内容块"
  [params cc]
  (let [msgs (vec (:messages params))]
    (if (seq msgs)
      (let [idx (dec (count msgs))
            m (nth msgs idx)
            blocks (message-content->blocks (:content m))]
        (if blocks
          (assoc params :messages
                 (assoc msgs idx (assoc m :content (mark-last blocks cc))))
          params))
      params)))

(defn- tool-result-block?
  "内容块是否为 tool_result（Anthropic 工具结果块）"
  [block]
  (and (map? block) (= "tool_result" (:type block))))

(defn- cache-tool-results
  "缓存工具结果：在「最后一个 tool_result 内容块」上打断点。

   遍历所有消息的内容块，定位最后一个 type=tool_result 的块并挂 cache_control，
   使多轮工具循环中此前的工具结果作为可缓存前缀（已带 cache_control 则跳过）。"
  [params cc]
  (let [msgs (vec (:messages params))
        ;; 找到最后一个 tool_result 块的 [消息下标 块下标]
        loc (reduce
              (fn [acc mi]
                (let [c (:content (nth msgs mi))]
                  (if (sequential? c)
                    (reduce (fn [a bi]
                              (if (tool-result-block? (nth c bi)) [mi bi] a))
                            acc
                            (range (count c)))
                    acc)))
              nil
              (range (count msgs)))]
    (if loc
      (let [[mi bi] loc
            m (nth msgs mi)
            blocks (vec (:content m))
            block (nth blocks bi)]
        (if (has-cache-control? block)
          params
          (assoc params :messages
                 (assoc msgs mi
                        (assoc m :content
                               (assoc blocks bi (assoc block :cache_control cc)))))))
      params)))

;; ============================================================
;; TTL：按断点给档 + 次序闸
;; ============================================================

(def extended-ttl-beta
  "1 小时缓存 TTL 的 beta 名。官方端点已 GA（多发无害）；兼容端点缺它会静默退回 5m。"
  "extended-cache-ttl-2025-04-11")

(def ^:private strategy-slots
  "各策略打的断点，**按渲染顺序**（tools → system → messages）。"
  {:system                  [:system]
   :tools                   [:tools]
   :system-and-tools        [:tools :system]
   :conversation            [:conversation]
   :tool-results            [:tool-results]
   :system-and-conversation [:system :conversation]})

(defn ttl-for
  "断点 slot 的 TTL：ttl 是 map 就按 slot 取（缺键 = nil = 5m），否则标量即全部同档。"
  [ttl slot]
  (if (map? ttl) (get ttl slot) ttl))

(defn- ttl-rank
  "TTL 长短序：\"1h\" > nil / \"5m\"。"
  [t]
  (if (= "1h" t) 1 0))

(defn validate-ttl!
  "校验 ttl 对 strategy 合法，返回 ttl。

   - map 只认 :tools / :system / :conversation / :tool-results 四键（拼错的键会
     静默落成 5m，所以当场抛）；
   - 按渲染顺序，长 TTL 的断点必须排在短 TTL 之前（服务端规则）。"
  [strategy ttl]
  (when (map? ttl)
    (when-let [bad (seq (remove #{:tools :system :conversation :tool-results} (keys ttl)))]
      (throw (ex-info (str ":cache-ttl 含未知断点键 " (vec bad)
                           "（只认 :tools :system :conversation :tool-results）")
                      {:type :invalid-cache-ttl :keys (vec bad) :cache-ttl ttl}))))
  (let [slots (get strategy-slots strategy)]
    (doseq [[a b] (partition 2 1 slots)
            :let [ta (ttl-for ttl a) tb (ttl-for ttl b)]]
      (when (< (ttl-rank ta) (ttl-rank tb))
        (throw (ex-info (str ":cache-ttl 次序非法：" b " 的 TTL（" tb "）长于排在它之前的 "
                             a "（" (or ta "5m") "）——长 TTL 必须排在短 TTL 之前")
                        {:type :invalid-cache-ttl :strategy strategy :cache-ttl ttl})))))
  ttl)

(defn extended-ttl?
  "该策略实际打的断点里是否有 \"1h\"（决定要不要并入 extended-ttl-beta）。"
  [strategy ttl]
  (boolean (some #(= "1h" (ttl-for ttl %)) (get strategy-slots strategy))))

;; ============================================================
;; 入口
;; ============================================================

(defn apply-anthropic-cache
  "按策略给 Anthropic 请求参数注入 cache_control

   参数:
   - params:   已构建好的 Anthropic 请求参数（含 :system :tools :messages）
   - strategy: :none | :system | :tools | :system-and-tools | :conversation
               | :tool-results | :system-and-conversation
   - ttl:      nil（5 分钟）| \"1h\"（1 小时）——所有断点同档；
               或按断点给档的 map {:tools .. :system .. :conversation .. :tool-results ..}

   返回: 注入 cache_control 后的 params

   说明:
   - :none 或未知策略 -> 原样返回。
   - ttl 次序非法 / map 含未知键 -> 抛 ex-info（:type :invalid-cache-ttl），见 validate-ttl!。
   - 注入后若断点数超过 4 会记到 meta，但不抛错（交由服务端校验 / 上层处理）。"
  ([params strategy] (apply-anthropic-cache params strategy nil))
  ([params strategy ttl]
   (if (or (nil? strategy) (= :none strategy))
     params
     (let [_ (validate-ttl! strategy ttl)
           cc (fn [slot] (ephemeral (ttl-for ttl slot)))
           result (case strategy
                    :system           (cache-system params (cc :system))
                    :tools            (cache-tools params (cc :tools))
                    :system-and-tools (-> params (cache-tools (cc :tools)) (cache-system (cc :system)))
                    :conversation     (cache-conversation params (cc :conversation))
                    :tool-results     (cache-tool-results params (cc :tool-results))
                    :system-and-conversation (-> params
                                                 (cache-system (cc :system))
                                                 (cache-conversation (cc :conversation)))
                    ;; 未知策略：原样
                    params)
           n (count-breakpoints result)]
       (if (> n max-breakpoints)
         (vary-meta result assoc ::breakpoints-exceeded n)
         result)))))

(defn breakpoint-count
  "返回 params 中的 cache_control 断点数（测试 / 诊断用）"
  [params]
  (count-breakpoints params))
