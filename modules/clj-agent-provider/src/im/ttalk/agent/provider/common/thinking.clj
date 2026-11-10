(ns im.ttalk.agent.provider.common.thinking
  "thinking 档位统一（designs/provider-reliability.md §7；Keelson `Providers/ThinkingKeys.cs`、
   `OpenAICompatibleChatClient.ApplyThinkingCall`、`AnthropicChatClient.ApplyThinkingCall`）。

   上层只说一句「档位 / 开关」，这里按各家风格生成 wire：

   | config 键 | 含义 |
   |---|---|
   | `:thinking-effort`  | 档位名（字符串 / 关键字），**原样上 wire**（DeepSeek 的 \"max\" 也行）；有档位即开 |
   | `:thinking-enabled` | 只开 / 关，不带档位（effort 在场时被忽略） |
   | `:thinking-style`   | OpenAI 兼容线的写法：`:plain` / `:nested` / `:top-level` / `:qwen` / `:none`；缺省按 provider 预设 |
   | `:thinking-budget-tokens` | Anthropic 旧式预算（压过档位与「只开」，只有「关」压得过它） |
   | `:thinking-always-on?`    | Anthropic：关不掉思考的模型，「关」时省略字段 |

   **不破坏现有透传**：config 里显式给了 `:thinking`（或 OpenAI 的 `:reasoning-effort`、Responses 的
   `:reasoning`）→ 对应字段不由本 ns 生成，显式值原样发。两个新键都没给 → 什么都不生成。")

(set! *warn-on-reflection* true)

(defn- ->str [x]
  (cond (nil? x) nil
        (keyword? x) (name x)
        :else (not-empty (str x))))

(defn resolve-call
  "读两格：→ `{:effort \"high\"}` / `{:enabled bool}` / nil（都没给）。effort 压过 enabled。"
  [config]
  (let [effort (->str (:thinking-effort config))
        enabled (:thinking-enabled config)]
    (cond
      effort {:effort effort}
      (some? enabled) {:enabled (boolean enabled)}
      :else nil)))

(def style-presets
  "provider 名 → OpenAI 兼容线的缺省思考写法（Keelson ChatProviders.cs：DeepSeek=nested、GLM=plain）。"
  {:deepseek :nested
   ;; :dashscope 是**原生** API（dashscope.clj 不走本线）：它在 thinking-params 里固定按
   ;; qwen 风格调本函数，这格只是兜底——比如宿主把 openai-compat provider 起名 :dashscope。
   :dashscope :qwen
   :qwen :qwen
   :zhipu :plain
   :openai :plain})

(defn- style-of [config]
  (or (some-> (:thinking-style config) keyword)
      (get style-presets (some-> (:provider-name config) keyword))
      :plain))

(defn openai-fields
  "OpenAI 兼容请求体里要并进去的思考字段（map；可能为空）。

   `stream?` 目前没有风格在用（保留参数位以免破坏调用方）：qwen 曾按 Keelson / 旧版百炼文档
   「非流式必须 false」只在流式时写 true；2026-11-10 LiteGate 真机 qwen3.8-flash / max 非流式
   `enable_thinking:true` 正常返回思考内容、不 400，于是放开——开就是 true，不看流式。

   - 档位 e：plain → 顶层 `reasoning_effort`；nested → `thinking{type:enabled, reasoning_effort:e}`；
     top-level → `thinking{type:enabled}` + 顶层 `reasoning_effort`
   - 只开 / 关：各风格一律 `thinking{type:enabled|disabled}`
   - qwen：`enable_thinking`（开 true / 关 false，流式与否一样；档位不上线）；none：不写"
  [config stream?]
  (let [call (resolve-call config)
        style (style-of config)
        explicit-thinking? (some? (:thinking config))
        explicit-effort? (some? (:reasoning-effort config))
        drop-explicit (fn [m]
                        (cond-> m
                          explicit-thinking? (dissoc :thinking)
                          explicit-effort?   (dissoc :reasoning_effort)))]
    (if (or (nil? call) (= :none style))
      {}
      (drop-explicit
        (if (= :qwen style)
          (if (some? (:enable-thinking config))
            {}
            {:enable_thinking (boolean (or (:effort call) (:enabled call)))})
          (if-let [e (:effort call)]
            (case style
              :nested    {:thinking {:type "enabled" :reasoning_effort e}}
              :top-level {:thinking {:type "enabled"} :reasoning_effort e}
              {:reasoning_effort e})
            {:thinking {:type (if (:enabled call) "enabled" "disabled")}}))))))

(defn anthropic-fields
  "Anthropic 请求体里要并进去的思考字段：`{:thinking {...}}`，档位时另有 `{:output_config {:effort e}}`。

   - 关 → `thinking{type:disabled}`（`:thinking-always-on?` 时省略）；关压得过预算
   - `:thinking-budget-tokens` n → `thinking{type:enabled, budget_tokens:n}`（不带 effort）
   - 档位 e → `thinking{type:adaptive, display:summarized}` + `output_config.effort e`
   - 只开 → 裸 `thinking{type:adaptive}`
   显式 `:thinking` 在场 → 不生成 `:thinking`（`output_config` 仍按档位生成）。"
  [config]
  (let [call (resolve-call config)
        budget (:thinking-budget-tokens config)
        explicit? (some? (:thinking config))
        fields (cond
                 (and (nil? call) (nil? budget)) {}
                 (= {:enabled false} call)
                 (if (:thinking-always-on? config) {} {:thinking {:type "disabled"}})
                 budget {:thinking {:type "enabled" :budget_tokens budget}}
                 (:effort call) {:thinking {:type "adaptive" :display "summarized"}
                                 :output_config {:effort (:effort call)}}
                 :else {:thinking {:type "adaptive"}})]
    (cond-> fields explicit? (dissoc :thinking))))

(defn responses-fields
  "OpenAI Responses API：档位 e → `{:reasoning {:effort e}}`；显式 `:reasoning` 优先（不生成）。"
  [config]
  (let [call (resolve-call config)]
    (if (and (:effort call) (nil? (:reasoning config)))
      {:reasoning {:effort (:effort call)}}
      {})))

(defn merge-output-config
  "把 `anthropic-fields` 的结果并进 params（`output_config` 与已有的合并而不是覆盖）。"
  [params fields]
  (let [oc (:output_config fields)]
    (cond-> (merge params (dissoc fields :output_config))
      oc (update :output_config merge oc))))
