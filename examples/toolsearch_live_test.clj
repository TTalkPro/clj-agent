(ns toolsearch-live-test
  "ToolSearch（渐进式工具披露）× MiniMax 真实 provider 端到端验证。

   对标 Spring AI 2.0 `ToolSearchToolCallingAdvisor`；设计见
   `docs/advisor-alignment-design.md` §2。

   验证点：
   1. **渐进式披露成立**：第 1 轮模型只看见 search_tools（业务工具一个不进
      prompt）→ 检索 → 第 2 轮起看见检索到的工具并调用 → 收敛;
   2. **发现集合跨轮累积**：多次检索的结果取并集（`:writes` + `:state-slots`
      的 `into` 槽 reducer），住在 tool-context 的命名空间槽里;
   3. **任务质量不掉**：需要两种能力的问题，两个工具都被调用（与基线一致）——
      这条曾经真的挂过，见下方「检索工具描述是 prompt 工程」;
   4. **大目录真省 token**：50 工具目录下 prompt token 显著低于基线;
   5. **小目录反而更贵**：12 工具目录下 ToolSearch 多花——检索往返的固定成本
      吃掉省下的 schema。这不是 bug，是适用边界（Spring 自陈 20+ 工具才用）。

   ⚠️ **两个实测教训（会毁掉复现，务必留意）**：

   - **prompt cache 会毁 token 对照**：端点开着 prompt cache，`response-usage`
     的 `:input-tokens` **不含**命中缓存的部分——同一脚本跑第二遍，基线的静态
     工具前缀被整块缓存，看起来只用了几百 token。故本脚本一律用
     `input + cache-read + cache-write` 计算**真实 prompt 规模**（与缓存状态
     无关）。
   - **省 token ≠ 省钱**：基线的静态工具前缀缓存命中率极高（实测 93%），而
     ToolSearch 的工具列表每轮变化 → 命中率近 0。按缓存读 10% 计价折算，
     实测「原始 token 省 73.6%、现金成本反贵 66%」。脚本会把命中率一并打出来。

   - **检索工具的描述是 prompt 工程**：首次实测时模型只检索了一种能力就作答，
     另一半问题静默丢失。tool_search.clj 的描述里那条「任务需要多种能力时必须
     为每一种各检索一次」是**必需的**，不是客套话。

   运行（需 MINIMAX_API_KEY，兼容旧的 MINIMAX_AUTH_TOKEN）：
     clojure -M -e \"(load-file \\\"examples/toolsearch_live_test.clj\\\")\""
  (:require [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.chat-model :as chat-model]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.filter.tool-search :as ts]
            [im.ttalk.agent.react :as react]
            [im.ttalk.agent.provider.minimax :as minimax]))

;;; ============================================================
;;; 环境与公共设施
;;; ============================================================

;; provider 默认读 MINIMAX_API_KEY；MINIMAX_AUTH_TOKEN 为旧变量名的兼容回退
(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(when-not auth-token
  (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
  (System/exit 1))

(def p (minimax/create-provider {:api-key auth-token}))
(def MODEL minimax/default-model)

(def failures (atom 0))

(defn check [desc ok?]
  (if ok?
    (println "  ✓" desc)
    (do (swap! failures inc)
        (println "  ✗ FAIL:" desc))))

;;; ============================================================
;;; 工具目录：2 个真工具 + N 个填充工具（模拟 MCP 多服务器场景）
;;; ============================================================

(deftool get-weather "获取指定城市的实时天气信息"
  [[city :string "城市名称"]]
  (str city "：晴，26°C，东南风 2 级，湿度 45%"))

(deftool list-clothing-shops "查询指定城市当前正在营业的服装店"
  [[city :string "城市名称"]]
  (str city "在营服装店：优衣库（三里屯店，营业至 22:00）、ZARA（王府井店，营业至 21:30）"))

(def filler-specs
  [["get_stock_quote" "查询股票实时行情与涨跌幅"] ["send_email" "发送电子邮件给指定收件人"]
   ["create_calendar_event" "在日历中创建日程安排"] ["translate_text" "把文本翻译成指定语言"]
   ["convert_currency" "按实时汇率进行货币换算"] ["get_news_headlines" "获取指定分类的新闻头条"]
   ["find_restaurant" "按菜系查找附近餐厅并给出评分"] ["book_flight" "预订出发地到目的地的航班机票"]
   ["calc_bmi" "根据身高体重计算 BMI 健康指数"] ["query_express" "查询快递物流单号当前状态"]
   ["book_hotel" "预订指定城市的酒店房间"] ["get_traffic" "查询实时路况与拥堵情况"]
   ["play_music" "播放指定歌手或歌曲的音乐"] ["set_alarm" "设置一个闹钟提醒"]
   ["get_recipe" "根据食材推荐菜谱做法"] ["order_taxi" "呼叫网约车前往目的地"]
   ["search_web" "在互联网上搜索关键词相关网页"] ["summarize_doc" "对长文档生成摘要"]
   ["ocr_image" "识别图片中的文字内容"] ["gen_image" "根据描述生成一张图片"]
   ["query_db" "在数据库中执行只读查询语句"] ["run_sql" "执行 SQL 语句并返回结果集"]
   ["list_files" "列出目录下的文件清单"] ["read_file" "读取指定文件的文本内容"]
   ["write_file" "把内容写入指定文件"] ["delete_file" "删除指定路径的文件"]
   ["git_commit" "提交代码变更到版本库"] ["git_diff" "查看代码改动差异"]
   ["deploy_service" "把服务部署到指定环境"] ["restart_service" "重启指定的后台服务"]
   ["check_health" "检查服务健康状态与存活情况"] ["get_metrics" "获取服务的监控指标数据"]
   ["query_logs" "检索服务日志中的关键字"] ["create_ticket" "创建一张工单"]
   ["assign_ticket" "把工单指派给指定处理人"] ["close_ticket" "关闭一张已解决的工单"]
   ["get_user_profile" "获取用户的档案资料"] ["update_user" "更新用户的档案信息"]
   ["list_orders" "列出用户的历史订单"] ["refund_order" "为指定订单发起退款"]
   ["get_balance" "查询账户余额"] ["transfer_money" "在账户之间转账"]
   ["calc_tax" "计算应缴税额"] ["gen_invoice" "开具发票"]
   ["send_sms" "发送短信到指定手机号"] ["make_call" "拨打电话给指定号码"]
   ["get_holiday" "查询法定节假日安排"] ["count_days" "计算两个日期之间的天数"]])

(defn filler-tools
  "取前 n 个填充工具（内联形态：带 :handler 的 map，build-chat-client 直接收）。"
  [n]
  (mapv (fn [[nm d]]
          {:name nm :description d
           :input_schema {:type "object"
                          :properties {:arg {:type "string" :description "参数"}}
                          :required ["arg"]}
           :handler (fn [_args _ctx] "（示例结果）")})
        (take n filler-specs)))

(defn catalog [size]
  (into [#'get-weather #'list-clothing-shops] (filler-tools (- size 2))))

;;; ============================================================
;;; 探针：记录每轮实际发给 provider 的工具与 token
;;; ============================================================

;; 真实 prompt 规模 = input + cache-read + cache-write。
;; 只看 :input-tokens 会被 prompt cache 骗（见 ns 文档）。
(defn- prompt-size [u]
  (+ (or (:input-tokens u) 0)
     (or (:cache-read-tokens u) 0)
     (or (:cache-write-tokens u) 0)))

(defn probe [log]
  {:name :probe
   :chat (fn [req chain]
           (let [tools (mapv :name (flt/req-option req :tools))
                 r (chain req)
                 u (resp/response-usage (:response r))]
             (swap! log conj {:tools tools
                              :prompt (prompt-size u)
                              :cached (or (:cache-read-tokens u) 0)
                              :calls (mapv :name (resp/response-tool-calls (:response r)))})
             r))})

(def question "北京今天天气怎么样？根据天气告诉我穿什么合适，并推荐现在还在营业的服装店。")

(defn run-case
  "跑一次对话，返回 {:log :result :prompt-total :cached-total}。
   probe 挂在 filters 末尾 = 洋葱最内层 → 看到的是 tool-search 改写**之后**的工具集。"
  [label cm tools ts-opts]
  (let [log (atom [])
        store (memory/in-memory-store)
        base {:chat-model cm :tools tools :filters [(ma/memory-filter store)]}
        k (chat-client/build-chat-client
            (-> (if ts-opts (ts/with-tool-search base ts-opts) base)
                (update :filters conj (probe log))))
        result (react/invoke k store [{:role :user :content question}]
                             {:context (ctx/create) :max-iterations 6})]
    (println (str "\n── " label))
    (doseq [[i {:keys [tools prompt cached calls]}] (map-indexed vector @log)]
      (println (format "   轮 %d | 暴露 %2d 个工具 | prompt=%-6s (缓存命中 %-5s) | 调用: %s"
                       (inc i) (count tools) (str prompt) (str cached) (pr-str calls))))
    {:log @log
     :result result
     :prompt-total (reduce + (map :prompt @log))
     :cached-total (reduce + (map :cached @log))}))

(defn called-tools
  "本次对话实际执行过的工具名集合（含 search_tools）。"
  [{:keys [result]}]
  (set (map #(name (:name %)) (:tool-calls-made result))))

;;; ============================================================
;;; 场景 1：大目录（50 工具）—— 渐进式披露的主场
;;; ============================================================

(defn test-large-catalog [cm]
  (println "\n══════════ 场景 1：50 工具目录 ══════════")
  (let [tools (catalog 50)
        base (run-case "A. 基线：50 个工具全量进 prompt" cm tools nil)
        srch (run-case "B. ToolSearch：渐进式披露" cm tools
                       {:index (ts/keyword-tool-index) :limit 3})
        first-round (first (:log srch))
        discovered (ctx/get-var (:tool-context (:result srch)) ts/discovered-slot)]

    (println)
    (check "基线：循环收敛" (= :completed (:status (:result base))))
    (check "基线：两个业务工具都被调用（对照组的任务质量基准）"
           (every? (called-tools base) ["get-weather" "list-clothing-shops"]))

    (check "ToolSearch：循环收敛" (= :completed (:status (:result srch))))
    (check "ToolSearch：第 1 轮只暴露 search_tools（50 个业务工具一个都不进 prompt）"
           (= ["search_tools"] (:tools first-round)))
    (check "ToolSearch：模型确实调用了检索工具"
           (contains? (called-tools srch) "search_tools"))
    (check "ToolSearch：检索到的工具在后续轮次进入工具列表"
           (some #(> (count (:tools %)) 1) (rest (:log srch))))
    (check "ToolSearch：发现集合落在 tool-context 的命名空间槽里"
           (set? discovered))
    (check "ToolSearch：两种能力都被检索到（发现集合累积，非 last-writer 覆盖）"
           (every? (or discovered #{}) ["get-weather" "list-clothing-shops"]))
    (check "ToolSearch：任务质量不掉——两个业务工具都被调用（与基线一致）"
           (every? (called-tools srch) ["get-weather" "list-clothing-shops"]))
    (check (format "ToolSearch：真实 prompt token 显著低于基线（%d < %d）"
                   (:prompt-total srch) (:prompt-total base))
           (< (:prompt-total srch) (:prompt-total base)))

    {:base base :srch srch}))

;;; ============================================================
;;; 场景 2：小目录（12 工具）—— 适用边界的反面
;;; ============================================================

(defn test-small-catalog [cm]
  (println "\n══════════ 场景 2：12 工具目录（适用边界的反面）══════════")
  (let [tools (catalog 12)
        base (run-case "A. 基线：12 个工具全量进 prompt" cm tools nil)
        srch (run-case "B. ToolSearch：渐进式披露" cm tools
                       {:index (ts/keyword-tool-index) :limit 3})]
    (println)
    (check "两种模式都能收敛（小目录下 ToolSearch 仍功能正确，只是不划算）"
           (and (= :completed (:status (:result base)))
                (= :completed (:status (:result srch)))))
    ;; 刻意**不断言** srch > base：这是模型行为，会波动；只作为数据点报告。
    (println (format "   ⓘ 基线 %d token vs ToolSearch %d token → %s"
                     (:prompt-total base) (:prompt-total srch)
                     (if (> (:prompt-total srch) (:prompt-total base))
                       (format "ToolSearch 多花 %.0f%%（符合预期：小目录不划算）"
                               (* 100.0 (/ (double (- (:prompt-total srch) (:prompt-total base)))
                                           (max 1 (:prompt-total base)))))
                       "本次 ToolSearch 未变贵（模型行为波动，非稳定结论）")))
    {:base base :srch srch}))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn report [large small]
  (let [{:keys [base srch]} large
        b (:prompt-total base) s (:prompt-total srch)
        saved (- b s)
        cache-rate (fn [{:keys [prompt-total cached-total]}]
                     (if (pos? prompt-total) (/ (double cached-total) prompt-total) 0.0))
        ;; 缓存读按 10% 计价折算的等效成本
        equiv (fn [{:keys [prompt-total cached-total]}]
                (+ (- prompt-total cached-total) (* 0.1 cached-total)))
        eb (equiv base) es (equiv srch)]
    (println "\n══════════ 汇总 ══════════")
    (println (format "  50 工具目录：基线 %d token（%d 轮）→ ToolSearch %d token（%d 轮）"
                     b (count (:log base)) s (count (:log srch))))
    (println (format "               节省 %d token（%.1f%%）"
                     saved (* 100.0 (/ (double saved) (max 1 b)))))
    (println (format "  12 工具目录：基线 %d token → ToolSearch %d token"
                     (:prompt-total (:base small)) (:prompt-total (:srch small))))
    (println)
    (println "  ⚠️ 省 token ≠ 省钱（静态工具前缀会被 prompt cache 整块命中）：")
    (println (format "     缓存命中率：基线 %.0f%% vs ToolSearch %.1f%%"
                     (* 100 (cache-rate base)) (* 100 (cache-rate srch))))
    (println (format "     缓存读按 10%% 计价折算：基线等效 %.0f vs ToolSearch %.0f → ToolSearch %s"
                     eb es
                     (if (> es eb)
                       (format "贵 %.0f%%" (* 100.0 (/ (- es eb) (max 1.0 eb))))
                       (format "省 %.0f%%" (* 100.0 (/ (- eb es) (max 1.0 eb)))))))
    (println "     → 主场是上下文吃紧 / 工具选择准确率下降 / provider 无 prompt cache；")
    (println "       工具集静态且缓存便宜时，基线的静态前缀几乎白送，别用。")))

(defn run []
  (println "ToolSearch live 验证 | model =" MODEL "| provider = :minimax")
  (let [cm (chat-model/create-chat-model p {:model MODEL :max-tokens 4096})]
    (try
      (let [large (test-large-catalog cm)
            small (test-small-catalog cm)]
        (report large small))
      (catch Throwable t
        (swap! failures inc)
        (println "  ✗ 场景异常:" (.getMessage t))))
    (println)
    (if (zero? @failures)
      (println "全部通过 ✓")
      (println @failures "项失败 ✗"))
    (System/exit (if (zero? @failures) 0 1))))

(run)
