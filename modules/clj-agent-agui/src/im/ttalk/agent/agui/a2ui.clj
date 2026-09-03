(ns im.ttalk.agent.agui.a2ui
  "A2UI —— **声明式**生成式 UI（可选插件）。

   移植自 `@ag-ui/a2ui-middleware`（CopilotKit 在 `agent-utils.ts` 里与
   Open Generative UI 挂在同一个位置）。两者的分工值得先说清楚：

   | | `agui.genui`（Open Generative UI） | 本 ns（A2UI） |
   |---|---|---|
   | 模型生成什么 | 一整块 HTML/CSS/JS | **组件树**（只能用 catalog 里有的组件） |
   | 谁来渲染 | 沙箱 iframe 执行模型写的代码 | 前端按自己的组件库渲染 |
   | 可控性 | 任意 JS（所以要沙箱） | **白名单**：模型编不出 catalog 之外的组件 |
   | 交互回流 | `Websandbox.connection.remote.*` | `forwardedProps.a2uiAction`（见 `input-transform`） |

   要「像素级自由」用 genui，要「设计系统一致 + 不执行任意代码」用 A2UI。

   ## 装

   ```clojure
   (rt/runtime {:agent-fn (tools/agent-fn (a2ui/with-tool spec))
                :event-transform (a2ui/event-transform)})
   ```

   ## 后台干什么

   1. **注入工具与用法**（`with-tool`）：`render_a2ui` 工具 + 一段用法提示词 +
      **catalog**（有哪些组件、各自什么属性）。catalog 是这条路的关键——它既是
      给模型的词汇表，也是「模型编不出别的组件」的保证；
   2. **翻译事件**（`event-transform`）：模型调 `render_a2ui`，参数
      `{surfaceId, components, data}` 翻成一条 `ACTIVITY_SNAPSHOT`：

          {:activityType \"a2ui-surface\"
           :messageId    \"a2ui-surface-<toolCallId>\"
           :replace      true
           :content {\"a2ui_operations\"
                     [{:version \"v0.9\" :createSurface    {…}}
                      {:version \"v0.9\" :updateComponents {…}}
                      {:version \"v0.9\" :updateDataModel  {…}}]}}

      三条 op 的顺序是协议要求的（先建面，再给组件，最后灌数据）；`replace true`
      表示「整块换掉」——所以不需要 delta，每次都是完整的一份；
   3. **回流用户动作**（`input-transform`）：用户点了生成出来的按钮，前端把
      `forwardedProps.a2uiAction` 发上来，翻成一句话喂回模型。

   ## 与上游的两处差异（都记在设计文档 §9.14）

   1. 上游从**流式** tool-call 参数里增量抠出「已经完整的那几个组件」
      （`extractCompleteItems`），一边生成一边往面上贴。我们的运行时不增量流式
      工具参数（docs/agent-runtime-design.md §10 第 4 条），参数一次到齐，所以
      **一条快照发完**——形状与顺序一致，少的是中间态；
   2. 上游把用户动作合成成 `log_a2ui_event` 的一次**工具调用 + 工具结果**塞进
      历史。我们的历史是服务端权威（§7.3），客户端塞不进消息，所以翻成一句
      **用户消息**（措辞与上游的 `formatUserActionResult` 一致）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def tool-name "render_a2ui")

(def activity-type
  "activity 消息的类型标签。前端按它挑 renderer。"
  "a2ui-surface")

(def operations-key
  "`content` 里装 op 数组的键。**字符串键**——它要原样出现在 JSON 里。"
  "a2ui_operations")

(def basic-catalog-id
  "A2UI v0.9 基础 catalog 的 id。CopilotKit 的 `a2ui-renderer` 默认实现的就是它。"
  "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json")

(def description
  "工具描述——逐句移植自上游的 `RENDER_A2UI_TOOL`。"
  (str "Render a dynamic A2UI v0.9 surface with structured parameters. "
       "Follow the A2UI render tool usage guide provided in context."))

(def parameters
  "JSON Schema，对齐上游 `RENDER_A2UI_TOOL.parameters`。

   **没有 catalogId 参数**：catalog 由宿主定，不能让模型自己编一个前端没注册的。"
  {:type "object"
   :properties
   {:surfaceId {:type "string" :description "Unique surface identifier."}
    :components {:type "array"
                 :description (str "A2UI v0.9 component array (flat format). "
                                   "The root component must have id \"root\".")
                 :items {:type "object"}}
    :data {:type "object"
           :description (str "Initial data model for the surface. Written to the root path. "
                             "Use for pre-filling form values (e.g. {\"form\": {\"name\": \"Alice\"}}) "
                             "or providing data for components bound to data model paths.")}}
   :required ["surfaceId" "components"]})

(defn guidelines
  "用法提示词——移植自上游的 `RENDER_A2UI_TOOL_GUIDELINES`。

   **这是提示词不是文档**：flat 组件数组、root 必须是布局组件、数据绑定语法、
   重复渲染语法、按钮 action 的形状，模型全靠这段学会。"
  [tool]
  (str "## How to call " tool "\n\n"
       "You MUST provide ALL required arguments when calling " tool ":\n\n"
       "- **surfaceId** (string, required): Unique ID for the surface (e.g. \"sales-dashboard\").\n"
       "- **components** (array, REQUIRED): A2UI v0.9 flat component array. NEVER omit this.\n"
       "- **data** (object, optional): Initial data model for path-bound component values.\n\n"
       "Note: the catalog id is set by the host, not by you. Do not include a catalogId argument.\n\n"
       "### Component format (v0.9 flat)\n\n"
       "Components are a flat array — children are referenced by ID, not nested:\n"
       "- Every component has `id` (unique) and `component` (type name from the available catalog).\n"
       "- The root component MUST have `id: \"root\"`.\n"
       "- Properties go directly on the component object.\n"
       "- Use `children: [\"id1\", \"id2\"]` for multiple children, `child: \"id\"` for a single child.\n\n"
       "### Minimal example\n\n"
       "```json\n"
       "{\n"
       "  \"surfaceId\": \"my-dashboard\",\n"
       "  \"components\": [\n"
       "    { \"id\": \"root\", \"component\": \"Column\", \"children\": [\"title\", \"row1\"] },\n"
       "    { \"id\": \"title\", \"component\": \"Text\", \"text\": \"Overview\", \"variant\": \"h2\" },\n"
       "    { \"id\": \"row1\", \"component\": \"Row\", \"children\": [\"c1\", \"c2\"] },\n"
       "    { \"id\": \"c1\", \"component\": \"Text\", \"text\": \"Users: 1,200\" },\n"
       "    { \"id\": \"c2\", \"component\": \"Text\", \"text\": \"Revenue: $50K\" }\n"
       "  ]\n"
       "}\n"
       "```\n\n"
       "### Key rules\n\n"
       "1. NEVER call " tool " without the `components` array — the UI will be empty.\n"
       "2. Root must be a layout component (Column, Row, Card) — not Text or Button.\n"
       "3. Component IDs must be unique. A component must NOT reference itself as child.\n"
       "4. Only use component names from the Available Components schema in context.\n"
       "5. For data binding use `{ \"path\": \"/key\" }` (absolute) or `{ \"path\": \"key\" }` (relative inside templates).\n"
       "6. For repeating content: `children: { componentId: \"card-id\", path: \"/items\" }` repeats per array item.\n"
       "7. Button actions: `\"action\": { \"event\": { \"name\": \"action_name\", \"context\": { ... } } }` — event must be an object.\n"
       "8. No placeholder images — only use real URLs or Icon components."))

(def schema-context-description
  "catalog 注入模型上下文时的抬头（上游的 `A2UI_SCHEMA_CONTEXT_DESCRIPTION` 同位）。"
  "Available Components — the A2UI catalog you may use. Only these component names exist.")

;;; ============================================================
;;; 工具 & agent 配置
;;; ============================================================

(declare basic-components)

(defn basic-catalog
  "A2UI v0.9 基础 catalog（18 个组件）。见文件末尾 `basic-components`。"
  []
  {:catalogId basic-catalog-id :components basic-components})

(defn render-tool
  "内联工具 map。

   **handler 不画任何东西**——UI 就是参数本身，后台只负责翻译成 activity 事件；
   handler 只给模型一个「贴上去了」的回执好让它继续说话（与 `genui` 同款取舍）。"
  ([] (render-tool tool-name))
  ([name]
   {:name name
    :description description
    :parameters parameters
    :handler (fn [args _ctx]
               (str "Surface \"" (or (:surfaceId args) (get args "surfaceId")) "\" rendered."))}))

(defn with-tool
  "把 `render_a2ui` 工具、用法提示词、catalog 挂进一份 `create-agent` 配置。

   - `:catalog`   `{:catalogId … :components {名字 → JSON Schema}}`，缺省用
                  `basic-catalog`。**传你前端真正注册的那份**——catalog 是模型的
                  词汇表，报了前端没有的组件，渲染就是空白；
   - `:tool-name` 自定义工具名（上游允许，默认 `render_a2ui`）。"
  ([spec] (with-tool spec nil))
  ([spec {:keys [catalog tool-name] :or {catalog (basic-catalog) tool-name tool-name}}]
   (let [ctx (str (guidelines tool-name)
                  "\n\n### " schema-context-description "\n\n```json\n"
                  (json/generate-string catalog) "\n```")]
     (-> spec
         (update :tools #(conj (vec %) (render-tool tool-name)))
         (update :system-prompt (fn [p] (str (some-> p (str "\n\n")) ctx)))))))

;;; ============================================================
;;; 参数 → activity 事件
;;; ============================================================

(defn surface-ops
  "`render_a2ui` 的参数 → A2UI v0.9 op 数组。

   **顺序是协议要求的**：先 `createSurface` 建面，再 `updateComponents` 给组件，
   最后（有 data 才发）`updateDataModel` 灌数据。少一条或反了，前端要么空白要么
   报「surface 不存在」。"
  [{:keys [surfaceId components data]} catalog-id]
  (let [sid (or surfaceId "default")]
    (cond-> [{:version "v0.9" :createSurface {:surfaceId sid :catalogId catalog-id}}
             {:version "v0.9" :updateComponents {:surfaceId sid :components (vec components)}}]
      (seq data) (conj {:version "v0.9"
                        :updateDataModel {:surfaceId sid :path "/" :value data}}))))

(defn surface-event
  "一条 `:activity/snapshot`。`:replace true` = 整块换掉，所以不需要 delta。"
  [tool-call-id args catalog-id]
  {:type :activity/snapshot
   :message-id (str activity-type "-" tool-call-id)
   :activity-type activity-type
   :replace true
   :content {operations-key (surface-ops args catalog-id)}})

;;; ============================================================
;;; 事件流插件
;;; ============================================================

(defn- normalize-args
  "工具参数的键可能是关键字也可能是字符串（取决于谁解析的 JSON）。"
  [args]
  (reduce-kv (fn [m k v] (assoc m (keyword k) v)) {} (or args {})))

(defn event-transform
  "`runtime` 的 `:event-transform`：`render_a2ui` 的调用 → surface 快照。

   与 `genui` 的一处不同：**不扣住 `:tool/started`**。上游 A2UI 也不扣——
   工具卡片先出、面随后贴上，是它接受的顺序（genui 那边扣住是因为沙箱 UI
   一闪的观感问题，两边取舍不同，照各自的来）。"
  ([] (event-transform nil))
  ([{:keys [tool-name catalog-id] :or {tool-name tool-name catalog-id basic-catalog-id}}]
   (fn [_run]
     (let [calls (volatile! #{})]                ;; 本 run 里属于 A2UI 的 tool-call-id
       (fn [{:keys [type tool-call-id] :as ev}]
         (case type
           :tool/started
           (do (when (= tool-name (:name ev)) (vswap! calls conj tool-call-id))
               [ev])

           :tool/args
           (if (contains? @calls tool-call-id)
             [(surface-event tool-call-id (normalize-args (:args ev)) catalog-id) ev]
             [ev])

           :tool/ended
           (do (vswap! calls disj tool-call-id) [ev])

           [ev]))))))

;;; ============================================================
;;; 入站：用户在生成出来的界面上点了什么
;;; ============================================================

(defn user-action-message
  "`forwardedProps.a2uiAction.userAction` → 一句喂回模型的话。

   措辞与上游 `formatUserActionResult` 一致（它把这句话作为 `log_a2ui_event`
   的工具结果塞进历史；我们的历史是服务端权威，客户端塞不进消息，所以作为
   **用户消息**进来——语义相同，路径不同）。"
  [{:keys [name surfaceId sourceComponentId context]}]
  (str "User performed action \"" (or name "unknown_action") "\""
       " on surface \"" (or surfaceId "unknown_surface") "\""
       (when sourceComponentId (str " (component: " sourceComponentId ")"))
       ". Context: " (json/generate-string (or context {}))))

(defn input-transform
  "`routes/start!` 的 `:input-transform`：把 A2UI 的用户动作接进本轮输入。

   前端点按钮时发的那一轮**可能没有用户消息**（就是个动作），所以只在
   `:message` 空的时候顶上；两者都有就把动作附在后面（用户既打了字又点了按钮）。"
  []
  (fn [parsed body]
    (if-let [action (get-in body [:forwardedProps :a2uiAction :userAction])]
      (let [text (user-action-message action)]
        (update parsed :message (fn [m] (if (str/blank? (str m)) text (str m "\n\n" text)))))
      parsed)))

;;; ============================================================
;;; A2UI v0.9 基础 catalog
;;; ============================================================

(def basic-components
  "**生成的**，别手改：由 `@a2ui/web_core` 的
   `src/v0_9/schemas/catalogs/basic/catalog.json` 摘出来（组件名 + 属性 +
   属性描述 + 枚举 + 必填），去掉了 `$ref` 与公共部分——那些是给校验器看的，
   模型只需要词汇表。要换成你自己的组件库，给 `with-tool` 传 `:catalog`。"
  {
   "Text"
   {:properties {
                 "text" {:description "The text content to display. While simple Markdown formatting is supported (i.e. without HTML, images, or link…"}
                 "variant" {:description "A hint for the base text style." :enum ["h1" "h2" "h3" "h4" "h5" "caption" "body"]}
                }
    :required ["text"]}
   "Image"
   {:properties {
                 "url" {:description "The URL of the image to display."}
                 "description" {:description "Accessibility text for the image."}
                 "fit" {:description "Specifies how the image should be resized to fit its container. This corresponds to the CSS 'object-fit' prope…" :enum ["contain" "cover" "fill" "none" "scaleDown"]}
                 "variant" {:description "A hint for the image size and style." :enum ["icon" "avatar" "smallFeature" "mediumFeature" "largeFeature" "header"]}
                }
    :required ["url"]}
   "Icon"
   {:properties {
                 "name" {:description "The name of the icon to display."}
                }
    :required ["name"]}
   "Video"
   {:properties {
                 "url" {:description "The URL of the video to display."}
                }
    :required ["url"]}
   "AudioPlayer"
   {:properties {
                 "url" {:description "The URL of the audio to be played."}
                 "description" {:description "A description of the audio, such as a title or summary."}
                }
    :required ["url"]}
   "Row"
   {:properties {
                 "children" {:description "Defines the children. Use an array of strings for a fixed set of children, or a template object to generate ch…"}
                 "justify" {:description "Defines the arrangement of children along the main axis (horizontally). Use 'spaceBetween' to push items to th…" :enum ["center" "end" "spaceAround" "spaceBetween" "spaceEvenly" "start" "stretch"]}
                 "align" {:description "Defines the alignment of children along the cross axis (vertically). This is similar to the CSS 'align-items'…" :enum ["start" "center" "end" "stretch"]}
                }
    :required ["children"]}
   "Column"
   {:properties {
                 "children" {:description "Defines the children. Use an array of strings for a fixed set of children, or a template object to generate ch…"}
                 "justify" {:description "Defines the arrangement of children along the main axis (vertically). Use 'spaceBetween' to push items to the…" :enum ["start" "center" "end" "spaceBetween" "spaceAround" "spaceEvenly" "stretch"]}
                 "align" {:description "Defines the alignment of children along the cross axis (horizontally). This is similar to the CSS 'align-items…" :enum ["center" "end" "start" "stretch"]}
                }
    :required ["children"]}
   "List"
   {:properties {
                 "children" {:description "Defines the children. Use an array of strings for a fixed set of children, or a template object to generate ch…"}
                 "direction" {:description "The direction in which the list items are laid out." :enum ["vertical" "horizontal"]}
                 "align" {:description "Defines the alignment of children along the cross axis." :enum ["start" "center" "end" "stretch"]}
                }
    :required ["children"]}
   "Card"
   {:properties {
                 "child" {:description "The ID of the single child component to be rendered inside the card. To display multiple elements, you MUST wr…"}
                }
    :required ["child"]}
   "Tabs"
   {:properties {
                 "tabs" {:description "An array of objects, where each object defines a tab with a title and a child component."}
                }
    :required ["tabs"]}
   "Modal"
   {:properties {
                 "trigger" {:description "The ID of the component that opens the modal when interacted with (e.g., a button). Do NOT define the componen…"}
                 "content" {:description "The ID of the component to be displayed inside the modal. Do NOT define the component inline."}
                }
    :required ["content" "trigger"]}
   "Divider"
   {:properties {
                 "axis" {:description "The orientation of the divider." :enum ["horizontal" "vertical"]}
                }
    }
   "Button"
   {:properties {
                 "child" {:description "The ID of the child component. Use a 'Text' component for a labeled button. Only use an 'Icon' if the requirem…"}
                 "variant" {:description "A hint for the button style. If omitted, a default button style is used. 'primary' indicates this is the main…" :enum ["default" "primary" "borderless"]}
                 "action" {}
                }
    :required ["action" "child"]}
   "TextField"
   {:properties {
                 "label" {:description "The text label for the input field."}
                 "value" {:description "The value of the text field."}
                 "variant" {:description "The type of input field to display." :enum ["longText" "number" "shortText" "obscured"]}
                 "validationRegexp" {:description "A regular expression used for client-side validation of the input."}
                }
    :required ["label"]}
   "CheckBox"
   {:properties {
                 "label" {:description "The text to display next to the checkbox."}
                 "value" {:description "The current state of the checkbox (true for checked, false for unchecked)."}
                }
    :required ["label" "value"]}
   "ChoicePicker"
   {:properties {
                 "label" {:description "The label for the group of options."}
                 "variant" {:description "A hint for how the choice picker should be displayed and behave." :enum ["multipleSelection" "mutuallyExclusive"]}
                 "options" {:description "The list of available options to choose from."}
                 "value" {:description "The list of currently selected values. This should be bound to a string array in the data model."}
                 "displayStyle" {:description "The display style of the component." :enum ["checkbox" "chips"]}
                 "filterable" {:description "If true, displays a search input to filter the options."}
                }
    :required ["options" "value"]}
   "Slider"
   {:properties {
                 "label" {:description "The label for the slider."}
                 "min" {:description "The minimum value of the slider."}
                 "max" {:description "The maximum value of the slider."}
                 "value" {:description "The current value of the slider."}
                }
    :required ["max" "value"]}
   "DateTimeInput"
   {:properties {
                 "value" {:description "The selected date and/or time value in ISO 8601 format. If not yet set, initialize with an empty string."}
                 "enableDate" {:description "If true, allows the user to select a date."}
                 "enableTime" {:description "If true, allows the user to select a time."}
                 "min" {:description "The minimum allowed date/time in ISO 8601 format."}
                 "max" {:description "The maximum allowed date/time in ISO 8601 format."}
                 "label" {:description "The text label for the input field."}
                }
    :required ["value"]}
   })
