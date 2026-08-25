(ns im.ttalk.agent.filter.structured-output
  "结构化输出校验（对标 Spring AI 2.0 `StructuredOutputValidationAdvisor`）

   `filter/validation-turn-filter` 提供的是**机制**（不合格 → 反馈重入循环 →
   重试上限）；它的 validate-fn 一直得用户自己写。Spring 的价值恰恰在
   validate-fn 本身：按 JSON Schema 校验，并把失败原因写成模型能据以自我修正的
   人话（\"missing required field 'actor'\" / \"expected 'array', got 'string'\"）
   ——而不是干巴巴的「重试」。本 ns 补的就是这块。

   ## 零依赖的代价与切法

   core **无任何依赖**（见 modules/clj-agent-core/deps.edn），JSON 解析器不在
   手边。故切成两半：

   - `validate-value` —— 纯函数，校验**已解析**的值（Clojure map/vector），
     零依赖；
   - `validate-fn` —— 生成喂给 `validation-turn-filter` 的 validate-fn，
     JSON 解析由调用方经 `:parse-fn` 注入
     （`#(cheshire.core/parse-string % true)` 即可）。

   ## 用法

   ```clojure
   (def schema
     {:type \"object\"
      :properties {:actor {:type \"string\"}
                   :films {:type \"array\" :items {:type \"string\"}}}
      :required [\"actor\" \"films\"]})

   (chat-client/build-chat-client
     {:chat-model cm
      :filters [(ma/memory-filter store)                      ;; 递归重入依赖 memory 在位
                (flt/validation-turn-filter
                  (so/validate-fn schema :parse-fn #(json/parse-string % true))
                  :max-retries 2)]})
   ```

   配 provider 原生 json_schema 使用：原生约束负责「大体是对的」，本校验负责
   「真的对」——原生结构化输出并不保证语义完整（Spring 2.0 给
   StructuredOutputValidationAdvisor 自动注册，理由同此）。

   ## 支持的 JSON Schema 子集

   `:type`（object/array/string/number/integer/boolean/null）、`:properties`、
   `:required`、`:items`、`:enum`。够覆盖 deftool 生成的 schema 与常见结构化
   输出；不做 $ref/allOf/oneOf/pattern 等——真要全量 JSON Schema，
   `validate-fn` 换成自己的实现即可（机制不变）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.model.response :as resp]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 路径与类型
;;; ============================================================

(defn- fmt-path
  "路径向量 → 人话路径（`films[0].title`）；根为「根」。"
  [path]
  (if (empty? path)
    "根"
    (-> (apply str (map (fn [seg]
                          (if (number? seg) (str "[" seg "]") (str "." seg)))
                        path))
        (str/replace #"^\." ""))))

(defn- type-of
  "值的 JSON 类型名（用于报告实际类型）。"
  [v]
  (cond
    (nil? v) "null"
    (map? v) "object"
    (or (sequential? v) (set? v)) "array"
    (string? v) "string"
    (boolean? v) "boolean"
    (integer? v) "integer"
    (number? v) "number"
    :else (.getName (class v))))

(defn- type-ok?
  "schema :type 是否接受该值。number 接受整数；integer 不接受浮点。"
  [expected v]
  (case expected
    "number"  (and (number? v) (not (boolean? v)))
    "integer" (and (integer? v) (not (boolean? v)))
    "string"  (string? v)
    "boolean" (boolean? v)
    "object"  (map? v)
    "array"   (or (sequential? v) (set? v))
    "null"    (nil? v)
    true))                                  ;; 未知/未声明 type 一律放行

(defn- lookup
  "按字段名取值，兼容 keyword 键与字符串键（解析器是否 keywordize 不该影响校验）。
   返回 [存在? 值]。"
  [m k]
  (let [kw (keyword k)]
    (cond
      (contains? m kw) [true (get m kw)]
      (contains? m k)  [true (get m k)]
      :else            [false nil])))

;;; ============================================================
;;; 校验
;;; ============================================================

(defn- walk-validate
  "深度优先，返回第一个问题（字符串）或 nil。
   只报第一个——一次给模型一个明确的修正目标，比糊一堆更容易改对。"
  [value schema path]
  (let [t (:type schema)]
    (cond
      (and t (not (type-ok? t value)))
      (str "字段 " (fmt-path path) " 期望 " t "，实为 " (type-of value))

      (and (:enum schema) (not (contains? (set (:enum schema)) value)))
      (str "字段 " (fmt-path path) " 必须是 "
           (str/join " / " (map pr-str (:enum schema))) " 之一，实为 " (pr-str value))

      (and (= t "object") (map? value))
      (or
        ;; 必填字段
        (some (fn [rk]
                (let [[present? _] (lookup value rk)]
                  (when-not present?
                    (str "缺少必填字段 " (fmt-path (conj (vec path) rk))))))
              (:required schema))
        ;; 已声明的属性逐个下钻（未声明的属性不管——等价于
        ;; additionalProperties: true）
        (some (fn [[pk pschema]]
                (let [pk-name (name pk)
                      [present? v] (lookup value pk-name)]
                  (when present?
                    (walk-validate v pschema (conj (vec path) pk-name)))))
              (:properties schema)))

      (and (= t "array") (sequential? value) (:items schema))
      (some (fn [[i v]] (walk-validate v (:items schema) (conj (vec path) i)))
            (map-indexed vector value))

      :else nil)))

(defn validate-value
  "校验**已解析**的值是否符合 schema（JSON Schema 子集）。

   返回 nil（通过）| 问题描述字符串（人话，可直接回给模型自我修正）。
   纯函数、零依赖——已有解析结果时直接用它。"
  [value schema]
  (walk-validate value schema []))

;;; ============================================================
;;; 文本 → 值
;;; ============================================================

(def ^:private fence-re
  #"(?s)```(?:json|JSON)?\s*(.*?)\s*```")

(defn strip-fences
  "剥掉 markdown 代码围栏——模型即便被要求只输出 JSON 也常包一层 ```json。
   无围栏则原样返回（trim）。"
  [text]
  (let [t (str/trim (or text ""))]
    (if-let [[_ inner] (re-find fence-re t)]
      (str/trim inner)
      t)))

;;; ============================================================
;;; validate-fn 工厂
;;; ============================================================

(defn validate-fn
  "生成 `validation-turn-filter` 的 validate-fn：
   取最终答案文本 → 剥围栏 → `:parse-fn` 解析 → 按 schema 校验。

   返回 (fn [turn-result] -> nil | 问题描述)。

   参数:
   - schema:    JSON Schema（支持的子集见 ns 文档）
   - :parse-fn  (fn [text] -> value)，**必填**——core 零依赖不内置 JSON 解析器；
                传 `#(cheshire.core/parse-string % true)` 即可。解析抛异常即
                判为「不是合法 JSON」并把异常消息回给模型
   - :text-fn   (fn [turn-result] -> text)，缺省取 `(:response result)` 的文本"
  [schema & {:keys [parse-fn text-fn]}]
  (when-not (fn? parse-fn)
    (throw (ex-info "structured-output/validate-fn 需要 :parse-fn（core 零依赖，不内置 JSON 解析器）"
                    {:schema schema})))
  (let [get-text (or text-fn
                     (fn [result]
                       (let [r (:response result)]
                         (cond
                           (string? r) r
                           (satisfies? resp/ILLMResponse r) (resp/response-text r)
                           :else (:text r)))))]
    (fn [turn-result]
      (let [text (strip-fences (get-text turn-result))]
        (if (str/blank? text)
          "回答为空，未产出任何 JSON。"
          (let [parsed (try
                         {:ok (parse-fn text)}
                         (catch Throwable t
                           {:err (or (not-empty (.getMessage t))
                                     (.getName (class t)))}))]
            (if-let [err (:err parsed)]
              (str "输出不是合法 JSON：" err)
              (validate-value (:ok parsed) schema))))))))
