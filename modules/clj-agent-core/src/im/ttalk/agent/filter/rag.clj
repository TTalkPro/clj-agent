(ns im.ttalk.agent.filter.rag
  "检索增强（对标 Spring AI `QuestionAnswerAdvisor`）

   > **决策变更（2026-07-15）**：此前记录为「不跟本体（需 vector store，超出
   > 定位），留 :turn 挂点」（filter-chain-design.md §4 / agent-loop-concurrency
   > §14.3）。现予推翻——但推翻的只是「不做本体」，不是「不引 vector store」：
   > 本 ns **仍不引任何检索依赖**，只定义 `IRetriever` 协议 + 注入 filter，
   > 向量库/embedding 由用户注入。与 ToolSearch 的 `IToolIndex` 同一取舍。
   >
   > 之所以值得做：QuestionAnswerAdvisor 的实质是**提示词编排**（把问题、检索
   > 结果、grounding 指令拼成一条增强消息）而非检索本身。这块编排每个用户都要
   > 重写一遍，且容易写错——尤其「每 turn 只注入一次」这个点。

   ## 为什么挂 :turn

   §14.3 早已点明这正是 turn 链解锁的场景：挂 :chat 会让工具循环**每轮**重复
   检索（浪费且污染 transcript——第 2 轮起 :messages 是 memory 拼出的完整
   历史，拿它当 query 检索毫无意义）。挂 :turn 则每 turn 恰好注入一次。

   `:resume?` 时跳过：延续暂停的 turn 没有入口消息可改写（filter 作者指引见
   filter-chain-design.md §2.4）。

   ## 用法

   ```clojure
   (kernel/build-kernel
     {:service svc
      :filters [(ma/memory-filter store)
                (rag/qa-turn-filter my-retriever :top-k 4)]})
   ```

   `my-retriever` 实现 `IRetriever`——包一层你的向量库即可：

   ```clojure
   (reify rag/IRetriever
     (retrieve [_ query top-k]
       (map (fn [hit] {:text (:content hit) :metadata {:source (:id hit)}})
            (my-vector-store/search query top-k))))
   ```

   ## 不跟的

   `RetrievalAugmentationAdvisor`（模块化 RAG：查询改写/扩展/压缩/重排）——
   那是一整套 `org.springframework.ai.rag` 构件的门面。需要的话，这些环节
   在 `IRetriever` 实现内部做，或自己写 turn filter；机制都在。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; Retriever 协议
;;; ============================================================

(defprotocol IRetriever
  "文档检索。实现方自行决定检索策略（向量/BM25/混合…）。"
  (retrieve [this query top-k]
    "按 query 检索，返回**至多 top-k 篇**文档，相关度降序：
     `[{:text \"...\" :metadata {...}} ...]`（:metadata 可选）。"))

;;; ============================================================
;;; 提示词编排
;;; ============================================================

(defn- default-template
  "缺省增强模板（对齐 Spring QuestionAnswerAdvisor 的形状：问题在前，
   检索结果以分隔线圈起，末尾给 grounding 指令）。"
  [question context-text]
  (str question
       "\n\n以下是检索到的上下文信息，位于分隔线之间：\n"
       "---------------------\n"
       context-text
       "\n---------------------\n\n"
       "请**仅**依据上述上下文与对话历史作答，不要使用上下文之外的知识。"
       "若上下文中没有答案，直言你无法回答，不要编造。"))

(defn- docs->text
  [docs]
  (str/join "\n\n" (keep #(let [t (:text %)] (when-not (str/blank? t) t)) docs)))

(defn- last-user-index
  "入口消息里**最后一条纯文本 user 消息**的下标；没有则 nil。

   限定 string content：增强的做法是把问题重写成「问题 + 上下文」，对多模态
   content（向量）改写会丢掉其中的图片片段——宁可不插手也不悄悄毁数据。
   （re-reading-filter 同款取舍。）"
  [messages]
  (->> messages
       (map-indexed vector)
       (filter (fn [[_ m]] (and (= :user (:role m)) (string? (:content m)))))
       last
       first))

;;; ============================================================
;;; QA turn filter
;;; ============================================================

(defn qa-turn-filter
  "检索增强 turn filter（对标 Spring AI `QuestionAnswerAdvisor`）。

   每 turn 一次：取入口的用户问题 → `retrieve` → 把检索结果按模板拼进该条
   用户消息 → 进循环。

   **检索不到时不注入**（原样进循环，模型按自身知识/历史作答）。这是对
   Spring 的**刻意偏离**：Spring 照样注入空上下文 + 「上下文里没有就说不知道」
   指令，于是检索一旦落空模型会拒答一切。要 Spring 那种严格 grounding，
   传 `:inject-when-empty? true`。

   参数:
   - retriever: `IRetriever` 实现
   - :top-k               检索篇数（缺省 4，同 Spring 默认）
   - :template            (fn [问题 上下文文本] -> 增强后的问题)，缺省见上
   - :inject-when-empty?  检索为空时是否照样注入（缺省 false）"
  [retriever & {:keys [top-k template inject-when-empty?]
                :or {top-k 4 inject-when-empty? false}}]
  (when-not (satisfies? IRetriever retriever)
    (throw (ex-info "qa-turn-filter 需要 IRetriever 实现" {:retriever retriever})))
  (let [render (or template default-template)]
    (flt/create-filter :qa
     :turn (fn [req chain]
             (let [idx (when-not (:resume? req)
                         (last-user-index (:messages req)))
                   question (when idx (get-in req [:messages idx :content]))]
               (if (str/blank? question)
                 (chain req)                       ;; resume / 无可增强的用户问题：不插手
                 (let [docs (retrieve retriever question top-k)
                       context-text (docs->text docs)]
                   (if (and (str/blank? context-text) (not inject-when-empty?))
                     (chain req)
                     (chain (assoc-in req [:messages idx :content]
                                      (render question context-text)))))))))))
