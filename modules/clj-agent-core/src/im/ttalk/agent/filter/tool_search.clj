(ns im.ttalk.agent.filter.tool-search
  "ToolSearch —— 渐进式工具披露（对标 Spring AI 2.0 `ToolSearchToolCallingAdvisor`）

   **动机**：工具一多，全量 schema 每轮都进 prompt。Spring 的实测是 28 个工具
   ≈ 5K–17K token，且模型在 30+ 同名工具间的选择准确率下降。渐进式披露把
   「一次性全塞」换成「按需检索」：初始只暴露一个检索工具，模型自己搜出需要
   的能力，检索到的工具在后续回合才进工具列表。

   ## 机制：零新增钩子

   我们不需要 Spring 那样的新 advisor 类型——现有三条契约天然拼出全部能力：

   1. `run-tool-loop` 每轮都把**当轮 tool-context** 塞进 ChatRequest 的
      `:context`（react.clj）；
   2. `invoke-chat` 的 terminal 由 **request 当前字段重建** chat-opts
      （chat_client.clj），故 `:chat` filter 改写 `:tools` 会抵达 provider；
   3. v0.3 的 `:writes` + `:state-slots` 槽级 reducer 让工具的写意图在屏障处
      按序折叠进 context。

   于是：`search_tools` 是一个**普通内联工具**，返回
   `{:writes {::discovered #{名字}}}`；槽 reducer 为集合并（`into`），
   跨轮累积；`:chat` filter 从 `(:context req)` 读出已发现集合，把 `:tools`
   重写为 `[search_tools] + 已发现`。

   **白拿的性质**：发现集合住在 tool-context 里 → 暂停/resume/持久化全都
   自动正确（tool-context 本就随快照走），无需任何额外状态。

   ## 索引（零依赖 + 可插拔）

   Spring 提供 vector / Lucene / regex 三种 ToolIndex。我们只内置**零依赖**的
   两种，语义检索留给协议注入（与本仓一贯取舍一致：拒 Reactor、拒 vector
   store 本体、只留挂点）：

   - `(keyword-tool-index)` —— 名称/描述分词重叠打分。**中文按二元组切分**
     （本仓工具描述以中文为主，空白分词对中文无效——同 Lucene CJKBigramFilter
     的做法）；同时拆 snake_case 与 camelCase；
   - `(regex-tool-index)` —— query 当正则匹配工具名（`get_.*_data` 之类）；
     非法正则退化为字面子串匹配（不抛异常）。

   自带向量库的用户 `(reify IToolIndex ...)` 注入即可。

   ## 用法

   ```clojure
   (chat-client/build-chat-client
     (ts/with-tool-search
       {:chat-model cm :tools [#'t1 #'t2 ... #'t80]
        :filters [(ma/memory-filter store)]}
       {:index (ts/keyword-tool-index) :limit 5}))
   ```

   `with-tool-search` 一次装好三处（工具 / filter / 状态槽）；要手工接线用
   `(tool-search opts)` 拿 `{:tool :filter :state-slots}`。

   ## 边界（含 live 实测结论，详见 docs/advisor-alignment-design.md §2.3–2.5，
   ##       可复现脚本 examples/toolsearch_live_test.clj）

   - **工具定义太少会亏**：实测 50 工具目录省 78% prompt token。但固定成本
     （多一轮 LLM 往返 + 检索结果进历史，约 600–1000 token）要先赚回来——
     **看 schema 总量，不是工具个数**（实测两个 12 工具目录结论相反：描述短的
     多花 13%，描述长的反而省）。沿用 Spring 的度量：工具定义 >5K token 才用；
   - **⚠️ 省 token 未必省钱**：基线的静态工具前缀天然适合 prompt cache，本
     filter 每轮改写 `:tools` 会把可缓存前缀打碎。按缓存读 10% 计价折算，实测
     **冷缓存下 ToolSearch 省 65%，热缓存下反贵 66%**——差别全在基线前缀的
     冷热。工具集静态 + 高频会话 + 廉价 prompt cache 时，省的是上下文窗口
     而非钱；
   - **发现集合的作用域 = tool-context 的作用域**。调用方把上一轮的
     `:tool-context` 回传给下一轮 `:context`，发现即跨轮累积（≈ Spring 的
     per-conversation）；不回传则每轮从零开始检索；
   - **召回优先于精确**：索引按 IDF 加权，但不设相对分数截断——实测的真实失败
     是召回（模型只检索一种能力就作答，另一半问题静默丢失），不是精确
     （多召回两个无关工具，模型直接忽略，成本 ~100 token）。`:limit` 是控制
     暴露量的旋钮。"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; ToolIndex 协议
;;; ============================================================

(defprotocol IToolIndex
  "工具索引。实现方自行决定检索策略（关键词/正则/向量…）。"
  (index-tools! [this schemas]
    "用工具 schema 向量建（重建）索引。schemas 形如
     [{:name \"...\" :description \"...\" :input_schema {...}} ...]")
  (search-tools [this query limit]
    "按 query 检索，返回**至多 limit 个** schema（相关度降序）。"))

;;; ============================================================
;;; 分词（零依赖；latin + CJK 二元组）
;;; ============================================================

(def ^:private cjk-run-re #"[一-鿿]+")
(def ^:private latin-re #"[a-z0-9]+")
(def ^:private camel-re #"([a-z0-9])([A-Z])")

(defn- bigrams
  "把一段 CJK 文字切成二元组；单字则取其本身。"
  [^String run]
  (if (< (count run) 2)
    [run]
    (mapv #(subs run % (+ % 2)) (range (dec (count run))))))

(defn- tokenize
  "文本 → token 集合。latin 按非字母数字切分（camelCase 先拆），
   CJK 按二元组切分。"
  [s]
  (let [s (-> (or s "") (str/replace camel-re "$1 $2") str/lower-case)]
    (into #{}
          (concat (re-seq latin-re s)
                  (mapcat bigrams (re-seq cjk-run-re s))))))

;;; ============================================================
;;; 内置索引：关键词
;;; ============================================================

(defn- entry
  [schema]
  {:schema schema
   :name-tokens (tokenize (:name schema))
   :desc-tokens (tokenize (:description schema))})

(defn- build-index
  "建索引并算 IDF 权重。

   **为什么必须有 IDF**（live 实测教训）：不加权时「查询」「获取」这类中文常见
   动词与「天气」这类真正区分性的词同分——50 个工具的目录里「查询」出现在 7 条
   描述中，于是检索「查询天气」会把 get_holiday / get_balance 一并捞出来占满
   limit。IDF 让普遍词权重趋近 0（出现在所有文档中的词恰好为 0，天然停用词），
   罕见词权重高。"
  [schemas]
  (let [entries (mapv entry schemas)
        n (count entries)
        df (reduce (fn [acc e]
                     (reduce (fn [a t] (update a t (fnil inc 0)))
                             acc
                             (into (:name-tokens e) (:desc-tokens e))))
                   {} entries)
        idf (into {} (map (fn [[t d]]
                            [t (Math/log (/ (double (inc n)) (double (inc d))))]))
                  df)]
    {:entries entries :idf idf}))

(defn- overlap-score
  "命中 token 的 IDF 之和；名称命中权重 2，描述命中权重 1。"
  [q-tokens idf {:keys [name-tokens desc-tokens]}]
  (let [w (fn [toks] (reduce + 0.0 (map #(get idf % 0.0)
                                        (set/intersection q-tokens toks))))]
    (+ (* 2.0 (w name-tokens)) (w desc-tokens))))

(defrecord KeywordToolIndex [state]
  IToolIndex
  (index-tools! [_ schemas]
    (reset! state (build-index schemas)))
  (search-tools [_ query limit]
    (let [q (tokenize query)
          {:keys [entries idf]} @state]
      (if (empty? q)
        []
        (->> entries
             (map (fn [e] [(overlap-score q idf e) e]))
             (filter (fn [[s _]] (pos? s)))
             ;; 同分按名称排序——检索结果稳定可测
             (sort-by (fn [[s e]] [(- s) (:name (:schema e))]))
             (take limit)
             (mapv (fn [[_ e]] (:schema e))))))))

(defn keyword-tool-index
  "关键词索引：名称/描述分词重叠 × IDF 打分（中文二元组切分）。零依赖。

   IDF 按当前目录现算：普遍词（「查询」「获取」）权重趋近 0，罕见词权重高。
   目录越大区分度越好。"
  []
  (->KeywordToolIndex (atom {:entries [] :idf {}})))

;;; ============================================================
;;; 内置索引：正则
;;; ============================================================

(defn- safe-pattern
  "把 query 编译成正则；非法正则退化为字面子串匹配（不抛异常）。"
  [query]
  (try
    (re-pattern (str "(?i)" query))
    (catch java.util.regex.PatternSyntaxException _
      (re-pattern (str "(?i)" (java.util.regex.Pattern/quote query))))))

(defrecord RegexToolIndex [schemas]
  IToolIndex
  (index-tools! [_ ss] (reset! schemas (vec ss)))
  (search-tools [_ query limit]
    (if (str/blank? query)
      []
      (let [re (safe-pattern query)]
        (->> @schemas
             (filterv #(re-find re (str (:name %))))
             (take limit)
             vec)))))

(defn regex-tool-index
  "正则索引：query 当正则匹配**工具名**（`get_.*_data` 之类）。
   自然语言 query 基本匹配不到——按名称约定检索时才选它。"
  []
  (->RegexToolIndex (atom [])))

;;; ============================================================
;;; 检索工具 + filter
;;; ============================================================

(def discovered-slot
  "发现集合在 tool-context 中的槽位 key（命名空间限定，避免与用户状态撞名）。"
  ::discovered)

(def ^:private default-search-tool-name "search_tools")

(defn- search-tool-schema
  [tool-name]
  {:name tool-name
   :description
   (str "按自然语言查询检索当前可用的工具。你的工具列表是**按需展开**的："
        "当你需要某种能力、但工具列表里没有对应工具时，先用本工具检索"
        "（如 query=\"天气\" / query=\"发送邮件\"）。"
        "检索命中的工具会加入你的工具列表，随后即可直接调用。\n"
        "重要：\n"
        "1. 若任务需要**多种**能力，必须为**每一种**能力各检索一次"
        "（可在同一轮并行多次调用本工具），不要只检索其中一种就动手；\n"
        "2. query 用**能力关键词**，别加「查询」「获取」这类通用词；\n"
        "3. 一次检索不到就换个说法再试；确实没有对应工具时，如实告诉用户。")
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "描述你需要的能力（自然语言）"}}
                  :required ["query"]}})

(defn- format-hits
  [query hits]
  (if (empty? hits)
    (str "未检索到匹配「" query "」的工具。换个说法再试，或改用更宽泛的描述。")
    (str "检索到 " (count hits) " 个工具（已加入你的工具列表，可直接调用）：\n"
         (str/join "\n" (map #(str "- " (:name %) ": " (:description %)) hits)))))

(defn tool-search
  "构造 ToolSearch 三件套，返回 `{:tool :filter :state-slots}`。

   一般直接用 `with-tool-search`；本函数供手工接线/自定义装配。

   **三件套必须同装**：索引由 `:filter` 在每次 LLM 调用时按当轮 `:tools` 建
   （tag 过滤可能改变工具集，故值变即重建），`:tool` 只负责查——真实循环里
   模型必须先经 invoke-chat 才可能发出 tool-call，这个先后天然成立；但**绕开
   chat 直调 `invoke-tool` 会检索到空**（索引尚未建）。少装 `:state-slots`
   则发现集合退化为 last-writer（每轮只剩最后一次检索的结果，不累积）。

   opts:
   - :index             IToolIndex 实现（必需）
   - :limit             单次检索返回上限（缺省 5）
   - :always-include    始终暴露的工具名集合（缺省 #{}）——检索之外的常驻工具
   - :search-tool-name  检索工具名（缺省 \"search_tools\"）"
  [{:keys [index limit always-include search-tool-name]
    :or {limit 5 search-tool-name default-search-tool-name}}]
  (when-not (satisfies? IToolIndex index)
    (throw (ex-info "tool-search 需要 :index（IToolIndex 实现）"
                    {:index index})))
  (let [st-schema (search-tool-schema search-tool-name)
        always (set always-include)
        ;; 已建索引的 catalog 快照——:tools 每轮由 build-chat-opts 现算
        ;; （tag 过滤可能改变工具集），值变了就重建索引。
        indexed (atom ::none)
        ensure-indexed! (fn [catalog]
                          (when (not= @indexed catalog)
                            (index-tools! index catalog)
                            (reset! indexed catalog)))]
    {:tool
     (assoc st-schema
            :handler
            (fn [args _ctx]
              (let [query (str (or (:query args) (get args "query") ""))
                    hits (if (str/blank? query) [] (search-tools index query limit))]
                (cond-> {:result (format-hits query hits)}
                  (seq hits) (assoc :writes
                                    {discovered-slot (into #{} (map :name) hits)})))))

     :filter
     (flt/create-filter :tool-search
      :chat (fn [req chain]
              (let [all (flt/req-option req :tools)]
                (if (empty? all)
                  (chain req)
                  (let [catalog (filterv #(not= search-tool-name (:name %)) all)
                        _ (ensure-indexed! catalog)
                        discovered (get (flt/req-context req) discovered-slot #{})
                        exposed (filterv #(or (contains? discovered (:name %))
                                              (contains? always (:name %)))
                                         catalog)]
                    (chain (flt/with-option req :tools (into [st-schema] exposed))))))))

     :state-slots {discovered-slot {:init #{} :reduce into}}}))

(defn with-tool-search
  "把 ToolSearch 装进 `build-chat-client` 的 opts map（工具 / filter / 状态槽三处
   一次装好）。filter 追加在末尾——memory 若在首位仍保持首位。

   ```clojure
   (chat-client/build-chat-client
     (with-tool-search {:chat-model cm :tools [...] :filters [(memory-filter store)]}
                       {:index (keyword-tool-index)}))
   ```"
  [chat-client-opts opts]
  (let [{:keys [tool filter state-slots]} (tool-search opts)
        ;; build-chat-client 取 (or tool-vars tools)——用户用哪个键就往哪个键装
        tools-key (if (contains? chat-client-opts :tool-vars) :tool-vars :tools)]
    (-> chat-client-opts
        (update tools-key (fnil conj []) tool)
        (update :filters (fnil conj []) filter)
        (update :state-slots merge state-slots))))
