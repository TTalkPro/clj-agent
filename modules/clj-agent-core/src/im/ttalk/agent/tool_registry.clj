(ns im.ttalk.agent.tool-registry
  "工具注册表：装配期把工具声明汇成一张表，运行期一次查表答完

   从 `chat-client` 拆出来的一块。判据是「这些函数认识什么」——它们全部只认识
   **工具声明**（`:serial` / `:retry` / `:timeout` / `:return-direct` / schema），
   一个都不认识 ChatModel、filter 链或消息。`chat-client` 因此瘦回它的本分：
   record + 装配 + 三个 invoke 原语。

   对照 beamai 的 `beamai_tool` / `beamai_tool_index`——同样是把工具那一摊从
   ChatClient 里分出去；Spring AI 对应的是 `ToolCallback` 注册表那一层。

   **装配期做掉，运行期只查表**：`build-tool-meta` 在 `build-chat-client` 时把
   var 工具与内联工具两个来源汇成 `{keyword -> ToolMeta}`；此前四个查询函数
   各自写一遍 `(if-let [v (get tool-vars k)] (读 var 元数据) (查 inline-meta))`
   ——同一段双分支手抄四遍，`:timeout` 就是在那种重复里漏掉 inline 分支、对内联
   工具静默失效的。

   本 ns 的查询函数**吃 ChatClient**（只读 `:tool-meta` / `:tool-vars` /
   `:tool-manager` 三个键，不 require chat-client，故无循环依赖）。"
  (:require [clojure.string]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.tool-calling-manager :as tcm]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 装配期：校验
;;; ============================================================

(defn- validate-tool-timeouts!
  "工具声明的 `:timeout` 必须是正整数毫秒，否则装配期即抛（消息串统一走 `tool/check-timeout!`）。"
  [var-map inline-tools]
  (doseq [[fn-key v] var-map
          :let [t (tool/timeout-spec v)]
          :when (not (tool/valid-timeout? t))]
    (tool/check-timeout! (str "工具 " fn-key) t))
  (doseq [{:keys [name timeout]} inline-tools
          :when (not (tool/valid-timeout? timeout))]
    (tool/check-timeout! (str "内联工具 " name) timeout)))

(defn- validate-tool-retries!
  "工具声明的 `:retry` 必须合法（nil / true / 正整数 map），否则装配期即抛。
   与 `validate-tool-timeouts!` 对称——`:retry` 此前零校验。"
  [var-map inline-tools]
  (doseq [[fn-key v] var-map
          :let [r (tool/retry-spec v)]
          :when (not (tool/valid-retry? r))]
    (throw (ex-info (str "工具 " fn-key " 的 :retry 必须为 nil / true / 正整数 map，实为 " (pr-str r))
                    {:tool fn-key :retry r})))
  (doseq [{:keys [name retry]} inline-tools
          :when (not (tool/valid-retry? retry))]
    (throw (ex-info (str "内联工具 " name " 的 :retry 必须为 nil / true / 正整数 map，实为 " (pr-str retry))
                    {:tool name :retry retry}))))

(defn- validate-unique-tool-names!
  "工具名必须唯一（var 之间、内联之间、var 与内联之间都算），否则装配期即抛。

   同名工具没有合理用例，只有坏结果：`:tools` schema 列表里两份定义都发给 LLM
   （模型看见两个同名工具），而 `tool-meta` / `inline-handlers` 只留得下一个——
   于是「模型看到的」与「实际执行的」对不上，且这种错配没有任何运行期症状可查。

   **刻意不给优先级规则**。此前是两套：四个声明查询 var 优先、`invoke-tool` 的
   执行分派内联优先——同名时「按 var 的策略执行内联的 handler」。合表时曾把它们
   统一成内联优先，但「选一个赢家」本身就是在给配置错误编造语义：调用方要替换
   某个工具，该在传 `:tools` 之前处理自己的列表，而不是指望框架替他猜。"
  [var-tools inline-tools]
  (let [names (concat (for [v var-tools :when (tool/tool-function? v)]
                        (keyword (:name (tool/get-schema v))))
                      (map #(keyword (:name %)) inline-tools))
        dups  (->> names frequencies (keep (fn [[k n]] (when (> n 1) k))) sort vec)]
    (when (seq dups)
      (throw (ex-info (str "工具名重复: " (clojure.string/join ", " (map str dups))
                           "——同一个 chat-client 内工具名必须唯一（var 与内联工具共用一个命名空间）")
                      {:duplicates dups})))))

(defn- build-func-def
  "构建 ToolRequest 的 :function 信息（供 tool filter 读取）。装配期调用，
   结果存进 `ToolMeta` 的 `:func-def`——运行期不再重建。

   **var 工具与内联工具共用本函数**——曾经两个构造点分头维护，正是 `:timeout`
   对内联工具静默失效的根因（`:serial`/`:retry`/`:return-direct` 都有 inline
   分支，独 `:timeout` 漏了）。新增字段请只加在这里。"
  [fn-name tool-var]
  ;; 超时不在此列：它由 terminal 在 filter 链**之内**强制（只包裹工具本体，
  ;; 不包裹 filter 链——R1: 审批等待不再吃掉超时预算）。
  {:name      fn-name
   :schema    (when tool-var (:tool/schema (meta tool-var)))
   :sensitive (boolean (when tool-var (:tool/sensitive (meta tool-var))))})

;;; ------------------------------------------------------------
;;; ToolMeta：一个工具的全部装配期声明，一张表答完
;;; ------------------------------------------------------------

;; 曾经是四个查询函数各自 `(if-let [v (get tool-vars k)] (读 var 元数据)
;; (查 inline-meta))`——同一段双分支手抄四遍。`:timeout` 就是在这种重复里漏掉
;; inline 分支、对内联工具静默失效的（见 build-func-def docstring）。现在两个
;; 来源在**装配期**汇成一张表，运行期只有一条路径。
(defrecord ToolMeta [func-def serial retry timeout return-direct])

(def ^:private default-retry-policy
  {:max-retries 2 :initial-delay-ms 200})

(defn- normalize-retry
  "`:retry` 声明（nil / true / map）→ 归一化策略 map 或 nil。装配期做一次。"
  [spec]
  (when spec
    (merge default-retry-policy (when (map? spec) spec))))

(defn- var-tool-meta
  "var 工具的声明取自 `:tool/*` 元数据。"
  [fn-key v]
  (->ToolMeta (build-func-def fn-key v)
              (tool/serial-tool? v)
              (normalize-retry (tool/retry-spec v))
              (tool/timeout-spec v)
              (tool/return-direct-tool? v)))

(defn- inline-tool-meta
  "内联工具（delegate-tool 等动态构建）的声明取自其 map 自身的同名键。"
  [fn-key t]
  (->ToolMeta (build-func-def fn-key nil)
              (boolean (:serial t))
              (normalize-retry (:retry t))
              (:timeout t)
              (boolean (:return-direct t))))

;;; ============================================================
;;; 装配期：建表
;;; ============================================================

;; ChatClient 上原本平铺着四个工具字段（tools / tool-vars / inline-handlers /
;; tool-meta）。它们**总是一起产生、一起使用、一起被子 agent 整体替换**，
;; 平铺在 record 上只是让 ChatClient 的 arity 里多了三个位置。收成一个值之后：
;; 「一个 ChatClient 有一个工具注册表」这句话在类型上成立，而不是靠约定。
;;
;; - tools:           [tool-schema ...] 发给 LLM 的 schema 列表（var + 内联同列）
;; - tool-vars:       {keyword -> var} var 工具，供 tag 过滤与执行分派
;; - inline-handlers: {keyword -> (fn [args ctx] result)} 内联工具处理函数
;; - tool-meta:       {keyword -> ToolMeta} 装配期预计算的**全部**声明
(defrecord ToolRegistry [tools tool-vars inline-handlers tool-meta])

(defn registry-of
  "取出工具注册表：`ToolRegistry` 原样返回，ChatClient 取其 `:tool-registry`。

   查询函数一律经它，于是「传 ChatClient」与「传裸注册表」两种调用都成立——
   注册表是个独立的值，不是必须挂在客户端上才能用的一摊字段。"
  [x]
  (if (instance? ToolRegistry x) x (:tool-registry x)))

(defn tool-schemas
  "发给 LLM 的 tool schema 列表（var 工具与内联工具同列）。

   **`(:tools chat-client)` 的替代**——四个工具字段收进 `:tool-registry` 之后，
   ChatClient 上不再有 `:tools`。"
  [x]
  (:tools (registry-of x)))

(defn build-registry
  "把 var 工具与内联工具两个来源汇成一个 `ToolRegistry`。

   **校验先于建表**：`normalize-retry` 会把非法声明 merge 成看似合法的策略，
   先归一化就等于把错误藏起来。名字唯一又先于其余校验——重名时 var-map /
   inline-handler-map 已经被 `into` 把重复悄悄吃掉了，再校验别的就是在错误的
   地基上校验。

   参数:
   - var-tools:    tool var 列表
   - var-map:      {keyword -> var}（调用方已建好，避免重复计算）
   - inline-tools: 内联工具 map 列表（含 :handler）
   - schemas:      已编译的 tool schema 列表（var + 内联）

   返回: `ToolRegistry`"
  [var-tools var-map inline-tools schemas]
  (validate-unique-tool-names! var-tools inline-tools)
  (validate-tool-timeouts! var-map inline-tools)
  (validate-tool-retries! var-map inline-tools)
  (->ToolRegistry
    (vec schemas)
    var-map
    (into {} (map #(vector (keyword (:name %)) (:handler %))) inline-tools)
    ;; 名字唯一已校验过，故这里的 into 不存在覆盖——两个来源的键集互不相交。
    (into (into {} (map (fn [[k v]] [k (var-tool-meta k v)])) var-map)
          (map (fn [t] [(keyword (:name t)) (inline-tool-meta (keyword (:name t)) t)]))
          inline-tools)))

;;; ============================================================
;;; 运行期：查询 API
;;; ============================================================

(defn tool-key
  "函数名（关键字或字符串）→ 表的键。"
  [fn-name]
  (if (keyword? fn-name) fn-name (keyword fn-name)))

(defn find-function
  "查找 var 工具（内联工具无 var，返回 nil）

    参数:
    - x:       ChatClient 或 ToolRegistry
    - fn-name: 函数名（关键字或字符串）

    返回:
    {:tool-var var} 或 nil"
  [x fn-name]
  (when-let [v (get (:tool-vars (registry-of x)) (tool-key fn-name))]
    {:tool-var v}))

(defn list-functions
  "列出所有注册的 var 工具名（吃 ChatClient 或 ToolRegistry）

    返回:
    关键字列表"
  [x]
  (keys (:tool-vars (registry-of x))))

(defn tool-meta
  "工具的装配期预计算声明（`ToolMeta` record），未注册则 nil。

   下面四个查询都是它的一层薄封装——**var 与内联工具在装配期就汇成了一张表**，
   运行期没有分支可走岔。"
  [x fn-name]
  (get (:tool-meta (registry-of x)) (tool-key fn-name)))

(defn serial-tool?
  "工具是否声明 :serial（副作用工具；批内并行时整批退化为按序执行）。"
  [chat-client fn-name]
  (boolean (:serial (tool-meta chat-client fn-name))))

(defn return-direct-tool?
  "工具是否声明 :return-direct（结果即最终答案，不再回灌 LLM）。

   对标 Spring AI ToolCallingAdvisor 的 return direct。"
  [chat-client fn-name]
  (boolean (:return-direct (tool-meta chat-client fn-name))))

(defn retry-policy
  "工具的 :retry 声明（归一化已在装配期做掉）。仅 :transient 类错误按此策略
   重试；声明即承诺幂等（重试会重跑整条 tool filter 链）。

   返回: nil（未声明，不重试）| {:max-retries n :initial-delay-ms ms}"
  [chat-client fn-name]
  (:retry (tool-meta chat-client fn-name)))

(defn tool-timeout
  "工具**自己声明**的 `:timeout`（毫秒）。

   只答「这个工具声明了什么」，不含引擎缺省——那一层见 `effective-tool-timeout`。

   返回: nil（未声明）| 正整数毫秒"
  [chat-client fn-name]
  (:timeout (tool-meta chat-client fn-name)))

(defn effective-timeout*
  "`effective-tool-timeout` 的表体，吃已查好的 `ToolMeta`——让 `invoke-tool`
   能复用它已经查到的那一份，不必为超时再查一次表。"
  [chat-client tmeta]
  (or (:timeout tmeta)
      tcm/*active-manager-timeout*
      (tcm/manager-timeout (:tool-manager chat-client))))

(defn effective-tool-timeout
  "该工具**实际生效**的超时（毫秒），nil = 不超时。

   **缺省不超时**——框架不替调用方决定何时放弃。要限时须显式给出，两个来源：

     工具声明 `deftool {:timeout ms}`  >  引擎缺省 `(...-tool-calling-manager
     {:timeout ms})`  >  **不超时**

   工具声明最优先——它最清楚自己要跑多久（长任务的逃生舱就是「声明一个大的」）；
   引擎缺省次之，让部署方能整体封顶而不必逐个改工具。

   引擎缺省的读取优先走 `*active-manager-timeout*`（**当前正在执行的** manager
   的值，R4），回落到 chat-client 的 `:tool-manager` 字段（直调 invoke-tool 不经
   manager 时）。

   由 `invoke-tool` 的 terminal 消费并强制：**开箱即生效，不需要挂任何 filter**。
   超时只包裹工具本体（terminal 内），不包裹 filter 链——审批等待不再吃掉超时
   预算（R1）。"
  [chat-client fn-name]
  (effective-timeout* chat-client (tool-meta chat-client fn-name)))
