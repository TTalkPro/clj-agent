(ns im.ttalk.agent.tool-search-test
  "ToolSearch（渐进式工具披露）测试

   覆盖：关键词索引（latin / 中文二元组 / camelCase）、正则索引、
   :chat filter 的按需展开、with-tool-search 装配、检索工具的 :writes 契约。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.advisor.tool-search :as ts]))

;;; ============================================================
;;; 测试用工具目录
;;; ============================================================

(def ^:private catalog
  [{:name "get_weather"    :description "获取指定城市的天气信息" :input_schema {}}
   {:name "send_email"     :description "发送一封邮件给指定收件人" :input_schema {}}
   {:name "listFiles"      :description "List files in a directory" :input_schema {}}
   {:name "get_stock_data" :description "查询股票行情数据" :input_schema {}}])

(defn- names [schemas] (mapv :name schemas))

;;; ============================================================
;;; 关键词索引
;;; ============================================================

(deftest keyword-index-test
  (let [idx (ts/keyword-tool-index)]
    (ts/index-tools! idx catalog)

    (testing "中文 query 按二元组命中（空白分词对中文无效，故必须切二元组）"
      (is (= ["get_weather"] (names (ts/search-tools idx "天气" 5)))))

    (testing "中文 query 命中描述"
      (is (= ["send_email"] (names (ts/search-tools idx "发送邮件" 5)))))

    (testing "latin query 命中工具名"
      (is (= ["get_weather"] (names (ts/search-tools idx "weather" 5)))))

    (testing "camelCase 工具名被拆开索引"
      (is (= ["listFiles"] (names (ts/search-tools idx "files" 5)))))

    (testing "snake_case 名称拆词后可被词根命中（多个 get_* 工具）"
      (is (= #{"get_weather" "get_stock_data"}
             (set (names (ts/search-tools idx "get" 5))))))

    (testing "limit 截断"
      (is (= 1 (count (ts/search-tools idx "get" 1)))))

    (testing "无命中返回空；空 query 返回空"
      (is (= [] (ts/search-tools idx "量子隧穿" 5)))
      (is (= [] (ts/search-tools idx "" 5))))

    (testing "名称命中权重高于描述命中（get_stock_data 排在前）"
      ;; "股票" 命中 get_stock_data 描述；"stock" 命中其名称
      (is (= "get_stock_data" (first (names (ts/search-tools idx "stock 股票" 5))))))))

(deftest keyword-index-idf-test
  ;; 回归：live 实测（MiniMax，50 工具目录）发现不加 IDF 时「查询」「获取」这类
  ;; 中文常见动词与「天气」同分，检索「查询天气」会把 get_holiday / get_balance
  ;; 一并捞出占满 limit。IDF 让普遍词权重趋近 0。
  (let [many (into [{:name "get_weather" :description "获取城市的天气信息" :input_schema {}}]
                   (map (fn [i] {:name (str "q" i)
                                 :description (str "查询业务数据编号" i)
                                 :input_schema {}}))
                   (range 8))
        idx (doto (ts/keyword-tool-index) (ts/index-tools! many))]

    (testing "常见词 + 区分词的混合 query：区分词命中的排第一"
      (is (= "get_weather" (first (names (ts/search-tools idx "查询天气" 5))))))

    (testing "limit=1 时只剩真正相关的那个（普遍词命中的排在后面）"
      (is (= ["get_weather"] (names (ts/search-tools idx "查询天气" 1)))))

    (testing "出现在所有文档里的词权重恰为 0 → 天然停用词，一个都不召回"
      (let [all-same (mapv (fn [i] {:name (str "t" i) :description "查询数据" :input_schema {}})
                           (range 4))
            idx2 (doto (ts/keyword-tool-index) (ts/index-tools! all-same))]
        (is (= [] (ts/search-tools idx2 "查询" 5)))))

    (testing "纯常见词 query 仍能退化召回（没有更好的信号时不该一无所获）"
      ;; 「业务」只在 q* 里出现（8/9），IDF 低但非 0 —— 召回优先于精确
      (is (seq (ts/search-tools idx "业务" 3))))))

(deftest keyword-index-reindex-test
  (testing "重新建索引替换旧内容"
    (let [idx (ts/keyword-tool-index)]
      (ts/index-tools! idx catalog)
      (is (seq (ts/search-tools idx "天气" 5)))
      (ts/index-tools! idx [{:name "only" :description "别的" :input_schema {}}])
      (is (= [] (ts/search-tools idx "天气" 5))))))

;;; ============================================================
;;; 正则索引
;;; ============================================================

(deftest regex-index-test
  (let [idx (ts/regex-tool-index)]
    (ts/index-tools! idx catalog)

    (testing "按名称模式检索"
      (is (= #{"get_weather" "get_stock_data"}
             (set (names (ts/search-tools idx "get_.*" 5))))))

    (testing "大小写不敏感"
      (is (= ["listFiles"] (names (ts/search-tools idx "listfiles" 5)))))

    (testing "非法正则退化为字面匹配 → 不抛异常（模型乱传 query 不该炸循环）"
      (is (= [] (names (ts/search-tools idx "get_weather[" 5)))))

    (testing "空 query 返回空"
      (is (= [] (ts/search-tools idx "" 5))))))

;;; ============================================================
;;; :chat filter —— 按需展开
;;; ============================================================

;;; react/build-chat-opts 传给 invoke-chat 的 :tools 是**编译后的 schema 向量**
;;; （react.clj），故测试也按这个形状喂——不经 build-kernel 的 :tools
;;; （那里只收 var 或带 :handler 的内联 map）。

(defn- probe-kernel
  "把 chat-fn 收到的 opts 录进 seen；kernel 只装 tool-search 三件套。"
  [seen ts-opts]
  (kernel/build-kernel
    (ts/with-tool-search
      {:service {:chat-fn (fn [_msgs opts] (reset! seen opts) {:text "ok"})}}
      (merge {:index (ts/keyword-tool-index)} ts-opts))))

(defn- exposed-names
  "跑一次 invoke-chat，返回 provider 实际看到的工具名。"
  [k seen context]
  (kernel/invoke-chat k [{:role :user :content "hi"}]
                      {:tools catalog :context context})
  (names (:tools @seen)))

(deftest filter-progressive-disclosure-test
  (let [seen (atom nil)
        k (probe-kernel seen nil)]

    (testing "初始只暴露 search_tools —— 目录里的工具一个都不进 prompt"
      (is (= ["search_tools"] (exposed-names k seen (ctx/create)))))

    (testing "context 里有已发现工具 → 展开为 search_tools + 已发现"
      (is (= ["search_tools" "get_weather"]
             (exposed-names k seen (ctx/create {ts/discovered-slot #{"get_weather"}})))))

    (testing "多个已发现工具按目录原序暴露"
      (is (= ["search_tools" "get_weather" "send_email"]
             (exposed-names k seen
                            (ctx/create {ts/discovered-slot #{"send_email" "get_weather"}})))))

    (testing "未知名字被忽略（不凭空造工具）"
      (is (= ["search_tools"]
             (exposed-names k seen (ctx/create {ts/discovered-slot #{"nope"}})))))))

(deftest filter-always-include-test
  (testing ":always-include 的工具无需检索即常驻"
    (let [seen (atom nil)
          k (probe-kernel seen {:always-include #{"send_email"}})]
      (is (= ["search_tools" "send_email"] (exposed-names k seen (ctx/create)))))))

(deftest filter-no-tools-passthrough-test
  (testing "无工具时 filter 不插手（:tools 为空 → 原样透传）"
    (let [seen (atom nil)
          {:keys [filter]} (ts/tool-search {:index (ts/keyword-tool-index)})
          k (kernel/build-kernel
              {:service {:chat-fn (fn [_ opts] (reset! seen opts) {:text "ok"})}
               :filters [filter]})]
      (kernel/invoke-chat k [{:role :user :content "hi"}] {})
      (is (nil? (:tools @seen))))))

;;; ============================================================
;;; 检索工具本体（:writes 契约）
;;; ============================================================

(deftest search-tool-handler-test
  (let [{:keys [tool state-slots]} (ts/tool-search {:index (doto (ts/keyword-tool-index)
                                                            (ts/index-tools! catalog))})
        handler (:handler tool)]

    (testing "命中 → :writes 声明发现的工具名，:result 列出名称与描述"
      (let [{:keys [result writes]} (handler {:query "天气"} nil)]
        (is (= {ts/discovered-slot #{"get_weather"}} writes))
        (is (clojure.string/includes? result "get_weather"))
        (is (clojure.string/includes? result "天气"))))

    (testing "未命中 → 无 :writes（不污染 context），:result 给出可行动提示"
      (let [{:keys [result writes]} (handler {:query "量子隧穿"} nil)]
        (is (nil? writes))
        (is (clojure.string/includes? result "未检索到"))))

    (testing "args 兼容字符串键（provider 反序列化差异）"
      (is (= {ts/discovered-slot #{"get_weather"}}
             (:writes (handler {"query" "天气"} nil)))))

    (testing "状态槽声明为集合并 → 跨轮累积而非覆盖"
      (let [{:keys [init reduce]} (get state-slots ts/discovered-slot)]
        (is (= #{} init))
        (is (= #{"a" "b"} (reduce #{"a"} #{"b"})))))))

;;; ============================================================
;;; with-tool-search 装配
;;; ============================================================

(deftest with-tool-search-wiring-test
  (testing "工具 / filter / 状态槽三处一次装好"
    (let [opts (ts/with-tool-search
                 {:service {:chat-fn (fn [_ _] {:text "ok"})}
                  :tools (vec catalog)
                  :filters [{:name :memory}]}
                 {:index (ts/keyword-tool-index)})]
      (is (= "search_tools" (:name (last (:tools opts)))))
      (is (fn? (:handler (last (:tools opts)))) "检索工具是内联工具（带 :handler）")
      (is (= [:memory :tool-search] (mapv :name (:filters opts)))
          "filter 追加在末尾——memory 保持首位")
      (is (contains? (:state-slots opts) ts/discovered-slot))))

  (testing "用 :tool-vars 键时装到 :tool-vars（build-kernel 取 (or tool-vars tools)）"
    (let [opts (ts/with-tool-search
                 {:service {} :tool-vars []}
                 {:index (ts/keyword-tool-index)})]
      (is (= "search_tools" (:name (last (:tool-vars opts)))))
      (is (nil? (:tools opts)))))

  (testing "kernel 编译后检索工具可被 invoke-tool 找到（内联 handler 注册成功）"
    ;; 索引由 :chat filter 在每次 LLM 调用时建——真实循环里模型必须先经
    ;; invoke-chat 才可能发出 tool-call，故先跑一次 chat 再调工具。
    (let [seen (atom nil)
          k (probe-kernel seen nil)]
      (kernel/invoke-chat k [{:role :user :content "hi"}]
                          {:tools catalog :context (ctx/create)})
      (is (= #{ts/discovered-slot}
             (set (keys (:writes (kernel/invoke-tool k :search_tools
                                                     {:query "天气"} (ctx/create)))))))))

  (testing "缺 :index 直接报错（而非静默不检索）"
    (is (thrown? clojure.lang.ExceptionInfo (ts/tool-search {})))))
