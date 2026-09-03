(ns copilotkit.genui
  "Open Generative UI —— 模型直接生成一块**沙箱 UI**（HTML/CSS/JS），边生成边渲染。

   移植自 CopilotKit 的
   `packages/runtime/src/v2/runtime/open-generative-ui-middleware.ts`
   （工具名 `generateSandboxedUi`、活动类型 `open-generative-ui`、
   `ACTIVITY_SNAPSHOT` / `ACTIVITY_DELTA` 的 JSON Patch 形状全部对齐，
   前端那半边——`OpenGenerativeUIRenderer` + websandbox iframe——原样可用）。

   ## 它在 examples 而不在 `clj-agent-agui` 模块里

   Open Generative UI **不是 AG-UI 的核心能力**，是 CopilotKit Runtime 的一个
   可选中间件——上游自己的分层也是这样：协议层（`@ag-ui/core`）只认
   `ACTIVITY_SNAPSHOT` / `ACTIVITY_DELTA` 这对**通用**事件，`generateSandboxedUi`
   这个工具名、`open-generative-ui` 这个活动类型、以及那套 JSON Patch 形状，全都
   是中间件（`packages/runtime/src/v2/runtime/open-generative-ui-middleware.ts`）
   自己的约定。

   我们照同一条线切：**通用的那半留在模块里**（`agui.codec` 认
   `:activity/snapshot` / `:activity/delta`，`/info` 有 `openGenerativeUIEnabled`
   这个位），**约定的那半在这里**。所以它是一份可以照抄改写的示例，不是库的一部分
   ——换个工具名、换套 patch 形状，复制这个文件改就是了（design-principles §2）。

   ## 它是**可选插件**，不是新机制

   两个挂点，都不进主路径，不装就完全不存在：

   | 要什么 | 挂在哪 |
   |---|---|
   | 让模型看得见这个工具 | `with-tool`：往 `create-agent` 配置里塞一个内联工具 + 设计规范 |
   | 把工具调用翻译成 activity 事件 | `event-transform`：`runtime` 的 `:event-transform`（`agui.event/emitter` 的 `:transform`） |

   ```clojure
   (rt/runtime {:agent-fn (tools/agent-fn (genui/with-tool spec))
                :event-transform (genui/event-transform)})
   ;; 需要 `examples` 在 classpath 上：`clojure -M:copilotkit …`（见根 deps.edn）
   ```

   ## 后台干的事

   模型调 `generateSandboxedUi`，参数是一大坨 `{initialHeight, placeholderMessages,
   css, html, jsFunctions, jsExpressions}`。后台**不执行**它——把参数**增量解析**成
   一串 activity 事件推给前端：

       ACTIVITY_SNAPSHOT  {initialHeight, generating: true}   ← 必须最早，且只一条
       ACTIVITY_DELTA     [{op add path /css        value …}]
       ACTIVITY_DELTA     [{op add path /cssComplete value true}]
       ACTIVITY_DELTA     [{op add path /html       value []}]   ← 先建数组
       ACTIVITY_DELTA     [{op add path /html/-     value \"<div\"}] ← 再一块块追加
       …
       ACTIVITY_DELTA     [{op add path /generating value false}]  ← tool/ended 时

   三条**猜错了前端不报错、只会静默少渲染**的细节（都是 CopilotKit 踩出来的）：

   1. **snapshot 必须在任何 delta 之前**，而且只发一条。前端把 delta 打在
      `messageId` 对应的 activity 消息上，消息还没建就悄悄丢弃。`initialHeight`
      不一定第一个到（key 顺序是模型定的），所以 snapshot 是**惰性的**：
      第一个要发的 delta 会先把它顶出去；`initialHeight` 迟到就改发 delta。
   2. **`add` 操作必须带 `value`**。模型经常给 `\"jsFunctions\": null`，
      发一条没有 value 的 patch 会让 `fast-json-patch` 把**整批**判非法丢掉。
      所以值是 nil 就整条跳过，只留 `…Complete` 标记。
   3. **`html` 是数组不是字符串**：`/html` 先建空数组，之后每块 `/html/-` 追加。
      前端把它 join 起来渲染，这样才有「UI 一点点长出来」的效果。

   ## 我们与 CopilotKit 的一处真实差异

   CopilotKit 的中间件吃的是**流式 tool-call 参数**（`TOOL_CALL_ARGS` 一片片来，
   用 clarinet 增量解析）。**我们的运行时不增量流式工具参数**
   （docs/agent-runtime-design.md §10 第 4 条）——`:tool/args` 一次给出完整参数。
   所以这里的扫描器同样是增量的（喂多少吃多少，见 `scanner`），只是今天**一口
   喂完**：事件序列、顺序、形状与 CopilotKit 逐条一致，只是「边生成边长出来」
   要等参数流式落地才有。真落地那天，这个 ns 一个字都不用改。

   一处顺带的修正：参数按**约定顺序**重新序列化后再喂扫描器（`args->json`）。
   模型的 key 顺序经过 provider 的 JSON 解析已经丢了，而这个特性的提示词恰恰
   要求按顺序生成——重排比听天由命诚实。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def tool-name "generateSandboxedUi")

(def activity-type "open-generative-ui")

(def description
  "工具描述——**这是提示词，不是文档**：沙箱的限制、可用的逃生口、以及参数顺序
   都靠它传达给模型。逐句移植自 CopilotKit 的 `GENERATE_SANDBOXED_UI_DESCRIPTION`。"
  (str "Generate sandboxed UI. "
       "IMPORTANT: The generated code runs in a sandboxed iframe WITHOUT same-origin access. "
       "Do NOT use localStorage, sessionStorage, document.cookie, IndexedDB, or fetch/XMLHttpRequest to same-origin URLs. "
       "To communicate with the host application, use Websandbox.connection.remote.<functionName>(args) which returns a Promise.\n\n"
       "You CAN use external libraries from CDNs by including <script> or <link> tags in the HTML <head> "
       "(e.g., Chart.js, D3, Three.js, x-data-spreadsheet, etc.). CDN resources load normally inside the sandbox.\n\n"
       "PARAMETER ORDER IS CRITICAL — generate parameters in exactly this order:\n"
       "1. initialHeight + placeholderMessages (shown to user while generating)\n"
       "2. css (all styles FIRST — the user sees a placeholder until CSS is complete)\n"
       "3. html (streams in live — the user watches the UI build as HTML is generated)\n"
       "4. jsFunctions (reusable helper functions)\n"
       "5. jsExpressions (applied one-by-one — the user sees each expression take effect)"))

(def parameters
  "JSON Schema（对应 CopilotKit 的 `GenerateSandboxedUiArgsSchema`）。
   `:properties` 的书写顺序就是 `args->json` 的重排顺序。"
  {:type "object"
   :properties {:initialHeight {:type "number"
                                :description "Initial iframe height in pixels"}
                :placeholderMessages {:type "array" :items {:type "string"}
                                      :description "Messages shown while generating"}
                :css {:type "string" :description "All styles, generated first"}
                :html {:type "string" :description "The UI markup"}
                :jsFunctions {:type "string" :description "Reusable helper functions"}
                :jsExpressions {:type "array" :items {:type "string"}
                                :description "Expressions applied one by one"}}})

(def design-skill
  "设计规范——CopilotKit 把它作为 agent context 每轮带给模型（`DEFAULT_DESIGN_SKILL`）。
   这里作为 system prompt 的追加段，语义相同、少一个机制。"
  (str "When generating UI with generateSandboxedUi, follow these design principles inspired by shadcn/ui:\n\n"
       "- Use a minimal, flat aesthetic. Avoid drop shadows and gradients — rely on subtle borders (1px solid, light gray like #e5e7eb) to define surfaces.\n"
       "- Neutral base palette: white backgrounds, zinc/slate gray text (#09090b for headings, #71717a for secondary text). One accent color for interactive elements.\n"
       "- Use system font stacks (system-ui, -apple-system, sans-serif) at readable sizes (14px body, 600 weight for headings). Tight line-heights.\n"
       "- Small, consistent border-radius (6–8px). Cards and containers use border, not shadow, for separation.\n"
       "- Buttons: solid fill for primary (dark bg, white text), outline for secondary (border + transparent bg). Subtle hover state (slight opacity or background shift).\n"
       "- Use CSS Grid or Flexbox for layout. Ensure the UI looks good at any width.\n"
       "- Minimal transitions (150ms) for hover/focus states only. No decorative animations.\n"
       "- Keep the UI focused and dense — avoid excessive padding. Use compact spacing (8–12px gaps, 10–14px padding in controls)."))

;;; ============================================================
;;; 增量 JSON 扫描器
;;; ============================================================

(defn- close-literal
  "把攒着的裸字面量（数字 / true / false / null）收成值。JSON 里它没有结束符，
   靠下一个 `,` `}` `]` 或空白才知道完了。"
  [^StringBuilder sb]
  (let [s (str/trim (str sb))]
    (cond
      (= s "") ::none
      (= s "true") true
      (= s "false") false
      (= s "null") nil
      :else (try
              (if (re-matches #"-?\d+" s)
                (parse-long s)
                (parse-double s))
              (catch Throwable _ ::none)))))

(defn- unescape
  "JSON 字符串转义。`\\uXXXX` 由调用方攒够四位再进来。"
  [c]
  (case c
    \n \newline, \t \tab, \r \return, \b \backspace, \f \formfeed
    \" \", \\ \\, \/ \/
    c))

(defn scanner
  "**增量** JSON 扫描器：喂多少吃多少，只关心**根对象第一层**。

   为什么自己写而不引一个库：这里要的不是「解析出一个值」，而是**边扫边报告**
   ——尤其是「字符串还没结束，但已经攒了这么多内容」（`on-string-partial`），
   html 一块块长出来全靠它。标准 JSON 库没有这个出口，CopilotKit 那边也是为此
   用了 clarinet 并去读它的 `textNode` 私有字段。

   回调（都可省）：

   - `:on-key`            (fn [k])            第一层的键
   - `:on-scalar`         (fn [k v])          第一层的标量值（v 可能是 nil）
   - `:on-array-open`     (fn [k])            第一层的数组开始
   - `:on-array-item`     (fn [k v])          数组元素（字符串化）
   - `:on-array-close`    (fn [k])            数组结束
   - `:on-string-partial` (fn [k so-far])     第一层字符串值的**未完成**内容

   嵌套结构只按深度记账，不上报——这个特性的参数就是一层。

   返回 `{:write! (fn [chunk])}`。"
  [{:keys [on-key on-scalar on-array-open on-array-item on-array-close on-string-partial]}]
  (let [noop (fn [& _])
        on-key (or on-key noop)
        on-scalar (or on-scalar noop)
        on-array-open (or on-array-open noop)
        on-array-item (or on-array-item noop)
        on-array-close (or on-array-close noop)
        on-string-partial (or on-string-partial noop)
        st (volatile! {:stack []          ;; :obj / :arr，末尾是栈顶
                       :expect-key? false
                       :in-str? false
                       :str-role nil      ;; :key | :value
                       :sb nil
                       :esc? false
                       :uni nil           ;; \uXXXX 的四位收集器
                       :lit nil
                       :key nil           ;; 第一层的当前键
                       :array-key nil})   ;; 正在收的第一层数组的键
        depth1? (fn [{:keys [stack]}] (and (= 1 (count stack)) (= :obj (peek stack))))
        in-arr1? (fn [{:keys [stack array-key]}]
                   (and array-key (= 2 (count stack)) (= :arr (peek stack))))
        deliver-value!
        (fn [s v]
          (cond
            (in-arr1? s) (on-array-item (:array-key s) (str v))
            (depth1? s)  (when-let [k (:key s)] (on-scalar k v))
            :else        nil))
        flush-lit!
        (fn [s]
          (if-let [^StringBuilder sb (:lit s)]
            (let [v (close-literal sb)]
              (when-not (= ::none v) (deliver-value! s v))
              (assoc s :lit nil))
            s))]
    {:write!
     (fn write! [chunk]
       (doseq [^Character ch (str chunk)]
         (let [s @st]
           (vreset!
            st
            (if (:in-str? s)
              ;; ---- 字符串内部 ----
              (let [^StringBuilder sb (:sb s)]
                (cond
                  (:uni s)
                  (let [u (str (:uni s) ch)]
                    (if (= 4 (count u))
                      (do (.append sb (char (Integer/parseInt u 16)))
                          (assoc s :uni nil))
                      (assoc s :uni u)))

                  (:esc? s)
                  (if (= ch \u)
                    (assoc s :esc? false :uni "")
                    (do (.append sb ^Character (unescape ch))
                        (assoc s :esc? false)))

                  (= ch \\) (assoc s :esc? true)

                  (= ch \")
                  (let [text (str sb)]
                    (if (= :key (:str-role s))
                      (let [s' (assoc s :in-str? false :sb nil :str-role nil
                                      :expect-key? false)]
                        (when (depth1? s') (on-key text))
                        (cond-> s' (depth1? s') (assoc :key text)))
                      (let [s' (assoc s :in-str? false :sb nil :str-role nil)]
                        (deliver-value! s' text)
                        s')))

                  :else (do (.append sb ch) s)))

              ;; ---- 字符串外部 ----
              (case ch
                \{ (let [s (flush-lit! s)]
                     (-> s (update :stack conj :obj) (assoc :expect-key? true)))
                \} (let [s (flush-lit! s)]
                     (-> s (update :stack pop) (assoc :expect-key? false)))
                \[ (let [s (flush-lit! s)
                         open1? (and (depth1? s) (some? (:key s)))
                         s (-> s (update :stack conj :arr) (assoc :expect-key? false))]
                     (if open1?
                       (do (on-array-open (:key s)) (assoc s :array-key (:key s)))
                       s))
                \] (let [s (flush-lit! s)
                         close1? (in-arr1? s)
                         k (:array-key s)
                         s (-> s (update :stack pop) (assoc :expect-key? false))]
                     (when close1? (on-array-close k))
                     (cond-> s close1? (assoc :array-key nil)))
                \: (assoc s :expect-key? false)
                \, (let [s (flush-lit! s)]
                     (assoc s :expect-key? (= :obj (peek (:stack s)))))
                \" (assoc s :in-str? true :sb (StringBuilder.)
                          :str-role (if (and (= :obj (peek (:stack s))) (:expect-key? s))
                                      :key :value))
                (\space \tab \newline \return) (flush-lit! s)
                (let [^StringBuilder lit (or (:lit s) (StringBuilder.))]
                  (.append lit ch)
                  (assoc s :lit lit)))))))
       ;; 一个 chunk 吃完，把「字符串还没收口但已经攒了内容」报出去
       (let [s @st]
         (when (and (:in-str? s) (= :value (:str-role s)) (depth1? s)
                    (nil? (:array-key s)) (:key s))
           (on-string-partial (:key s) (str ^StringBuilder (:sb s))))))}))

;;; ============================================================
;;; 参数 → activity 事件
;;; ============================================================

(defn- delta-event [message-id path value]
  {:type :activity/delta
   :message-id message-id
   :activity-type activity-type
   :patch [{:op "add" :path path :value value}]})

(defn args-parser
  "一个 tool-call 的参数解析器：喂 JSON 片段，吐**中立 activity 事件**。

   返回 `{:write! (fn [chunk]) :params (fn [])}`。`:params` 是已解析出的参数
   （调试与测试用；事件才是产品面）。"
  [tool-call-id on-event]
  (let [message-id (str tool-call-id "-activity")
        params (volatile! {})
        snapshot? (volatile! false)
        html (volatile! {:streaming? false :emitted 0 :array? false})
        emit-snapshot!
        (fn []
          (when-not @snapshot?
            (vreset! snapshot? true)
            (on-event {:type :activity/snapshot
                       :message-id message-id
                       :activity-type activity-type
                       ;; `generating true` 让前端先画占位；`initialHeight` 缺席
                       ;; 就不写这个键（与 TS 那边 undefined 不序列化等价）
                       :content (cond-> {:generating true}
                                  (:initialHeight @params)
                                  (assoc :initialHeight (:initialHeight @params)))})))
        emit-delta!
        (fn [k v]
          ;; 值是 nil 就整条跳过（见 ns 文档第 2 条）
          (when (some? v)
            (emit-snapshot!)
            (on-event (delta-event message-id (str "/" k) v))))
        emit-item!
        (fn [k v]
          (emit-snapshot!)
          (on-event (delta-event message-id (str "/" k "/-") v)))
        flush-html!
        (fn [text]
          (let [{:keys [emitted array?]} @html
                fresh (subs text (min emitted (count text)))]
            (when (seq fresh)
              (when-not array?
                (vswap! html assoc :array? true)
                (emit-delta! "html" []))
              (emit-item! "html" fresh)
              (vswap! html assoc :emitted (count text)))))
        sc (scanner
            {:on-key (fn [k]
                       (when (= "html" k)
                         (vreset! html {:streaming? true :emitted 0 :array? false})))
             :on-string-partial (fn [k text]
                                  (when (and (= "html" k) (:streaming? @html))
                                    (flush-html! text)))
             :on-scalar
             (fn [k v]
               (cond
                 (= "html" k)
                 (let [text (if (some? v) (str v) "")]
                   (vswap! params assoc :html (not-empty text))
                   (flush-html! text)
                   (emit-delta! "htmlComplete" true)
                   (vswap! html assoc :streaming? false))

                 (= "initialHeight" k)
                 (let [h (when (number? v) v)]
                   (vswap! params assoc :initialHeight h)
                   ;; snapshot 已经出去了 → 高度只能补一条 delta
                   (if @snapshot? (emit-delta! "initialHeight" h) (emit-snapshot!)))

                 (= "css" k)
                 (let [css (when (some? v) (str v))]
                   (vswap! params assoc :css css)
                   (emit-delta! "css" css)
                   (emit-delta! "cssComplete" true))

                 (= "jsFunctions" k)
                 (let [fns (when (some? v) (str v))]
                   (vswap! params assoc :jsFunctions fns)
                   (emit-delta! "jsFunctions" fns)
                   (emit-delta! "jsFunctionsComplete" true))

                 ;; 认不出的键：忽略（模型经常多给）
                 :else nil))
             :on-array-open
             (fn [k]
               (when (#{"jsExpressions" "placeholderMessages"} k)
                 (vswap! params assoc (keyword k) [])
                 (emit-delta! k [])))
             :on-array-item
             (fn [k v]
               (when (#{"jsExpressions" "placeholderMessages"} k)
                 (vswap! params update (keyword k) (fnil conj []) v)
                 (emit-item! k v)))
             :on-array-close
             (fn [k]
               (when (= "jsExpressions" k)
                 (emit-delta! "jsExpressionsComplete" true)))})]
    {:write! (:write! sc)
     :message-id message-id
     :params (fn [] @params)
     ;; 有没有发过 snapshot——没发过就没有 activity 消息，任何 delta 都会被前端
     ;; 静默丢弃，`generating false` 也不例外，所以那时候干脆不发
     :activity-started? (fn [] @snapshot?)
     :generating-done (fn [] (delta-event message-id "/generating" false))}))

;;; ============================================================
;;; 工具 & agent 配置
;;; ============================================================

(defn sandboxed-ui-tool
  "内联工具 map（`:handler` 之外的键原样就是发给模型的 schema）。

   **handler 不生成任何 UI**——UI 是参数本身，后台只负责把它翻译成 activity
   事件；handler 只是给模型一个「干完了」的回执，好让它继续说话。这与
   CopilotKit 前端那个 `handler: async () => \"UI generated\"` 是同一件事，
   只是挪到了服务端（于是**不依赖前端注册工具**，任何 AG-UI 客户端都能用）。"
  []
  {:name tool-name
   :description description
   :parameters parameters
   :handler (fn [_args _ctx] "UI generated")})

(defn with-tool
  "把工具与设计规范挂进一份 `create-agent` 配置。

   `:design-skill` 传 nil 就不追加提示词（你自己在 system prompt 里写）。"
  ([spec] (with-tool spec nil))
  ([spec {:keys [design-skill] :or {design-skill design-skill}}]
   (cond-> (update spec :tools #(conj (vec %) (sandboxed-ui-tool)))
     design-skill
     (update :system-prompt (fn [p] (str (some-> p (str "\n\n")) design-skill))))))

;;; ============================================================
;;; 事件流插件
;;; ============================================================

(def ^:private param-order
  "喂给扫描器的键顺序。模型的原始 key 顺序在 provider 解析 JSON 时就没了，而这个
   特性的提示词恰恰按顺序要求生成——按约定重排，比听天由命诚实（见 ns 文档）。"
  [:initialHeight :placeholderMessages :css :html :jsFunctions :jsExpressions])

(defn args->json
  "工具参数 map → **按约定顺序**的 JSON 串。认不出的键排在后面，原样带上。"
  [args]
  (let [args (into {} (map (fn [[k v]] [(keyword k) v])) (or args {}))
        ordered (concat (keep (fn [k] (when (contains? args k) [k (get args k)])) param-order)
                        (remove (fn [[k _]] (some #{k} param-order)) args))]
    (json/generate-string (apply array-map (mapcat identity ordered)))))

(defn event-transform
  "`runtime` 的 `:event-transform`：把 `generateSandboxedUi` 的工具调用事件翻译成
   activity 事件。返回**工厂**（每 run 现造一个有状态的 transform）。

   两处顺序上的讲究（都是 CopilotKit 的做法，理由在 ns 文档）：

   1. **`:tool/started` 被扣住**，直到第一条 activity 事件发出去才放行——否则
      前端先看见一张工具卡片，再看见 UI，闪一下；
   2. **`:tool/ended` 时补一条 `generating false`**，前端据此收掉占位、定稿。

   与 CopilotKit 的一处不同：那边靠流 `complete()` 兜住「扣住的事件没人放」，
   我们没有那个钩子，所以 `:tool/ended` 一律先把扣住的放出去——事件流的终态
   不过 transform（`event/expand`），扣住不放就是真丢了。

   `:tool/args` 认两种载荷：`:args-delta`（字符串片段，将来参数流式落地时走这条）
   与 `:args`（完整 map，今天走这条）。"
  ([] (event-transform nil))
  ([{:keys [tool-name] :or {tool-name tool-name}}]
   (fn [_run]
     (let [parsers (volatile! {})    ;; tool-call-id -> parser
           bufs (volatile! {})       ;; tool-call-id -> volatile[activity 事件]
           held (volatile! {})       ;; tool-call-id -> [被扣住的原事件]
           flushed (volatile! #{})]
       (fn [{:keys [type tool-call-id] :as ev}]
         (case type
           :tool/started
           (if (= tool-name (:name ev))
             (let [buf (volatile! [])]
               (vswap! bufs assoc tool-call-id buf)
               (vswap! parsers assoc tool-call-id
                       (args-parser tool-call-id (fn [a] (vswap! buf conj a))))
               (vswap! held assoc tool-call-id [ev])
               [])
             [ev])

           :tool/args
           (if-let [p (get @parsers tool-call-id)]
             (let [buf (get @bufs tool-call-id)]
               ((:write! p) (or (:args-delta ev) (args->json (:args ev))))
               (let [acts @buf]
                 (vreset! buf [])
                 (if (contains? @flushed tool-call-id)
                   (into [ev] acts)
                   (let [h (conj (get @held tool-call-id []) ev)]
                     (if (seq acts)
                       ;; 第一条 activity 先走，随后放行扣住的（`:tool/started` +
                       ;; 本条 args），剩下的 activity 跟在后面
                       (do (vswap! flushed conj tool-call-id)
                           (vswap! held dissoc tool-call-id)
                           (into (into [(first acts)] h) (rest acts)))
                       ;; 一条都没解析出来（空参数）：继续扣着
                       (do (vswap! held assoc tool-call-id h) []))))))
             [ev])

           :tool/ended
           (if-let [p (get @parsers tool-call-id)]
             (let [h (get @held tool-call-id [])]
               (vswap! parsers dissoc tool-call-id)
               (vswap! bufs dissoc tool-call-id)
               (vswap! held dissoc tool-call-id)
               (vswap! flushed conj tool-call-id)
               ;; 扣住的一律在这里放完——终态不过 transform，扣着就是真丢了
               (into (vec h) (if ((:activity-started? p))
                               [((:generating-done p)) ev]
                               [ev])))
             [ev])

           [ev]))))))
