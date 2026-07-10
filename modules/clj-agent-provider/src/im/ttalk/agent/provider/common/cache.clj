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

   ttl: nil -> 默认 5 分钟；\"1h\" -> 1 小时。

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
;; 入口
;; ============================================================

(defn apply-anthropic-cache
  "按策略给 Anthropic 请求参数注入 cache_control

   参数:
   - params:   已构建好的 Anthropic 请求参数（含 :system :tools :messages）
   - strategy: :none | :system | :tools | :system-and-tools | :conversation
               | :tool-results | :system-and-conversation
   - ttl:      nil（5 分钟）| \"1h\"（1 小时）

   返回: 注入 cache_control 后的 params

   说明:
   - :none 或未知策略 -> 原样返回。
   - 注入后若断点数超过 4 会记到 meta，但不抛错（交由服务端校验 / 上层处理）。"
  ([params strategy] (apply-anthropic-cache params strategy nil))
  ([params strategy ttl]
   (if (or (nil? strategy) (= :none strategy))
     params
     (let [cc (ephemeral ttl)
           result (case strategy
                    :system           (cache-system params cc)
                    :tools            (cache-tools params cc)
                    :system-and-tools (-> params (cache-tools cc) (cache-system cc))
                    :conversation     (cache-conversation params cc)
                    :tool-results     (cache-tool-results params cc)
                    :system-and-conversation (-> params (cache-system cc) (cache-conversation cc))
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
