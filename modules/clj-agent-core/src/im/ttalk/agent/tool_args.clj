(ns im.ttalk.agent.tool-args
  "按 JSON Schema 完整校验工具参数（designs/runtime-borrow.md §8，借 Keelson
   `Tools/ToolArgsValidator.cs`）。

   模型给了枚举外的值、负数、错类型时，与其让工具函数体炸成一句看不懂的异常，
   不如**一次报全**每处错误、带 JSON 路径、告诉模型期望什么——它多半下一轮就改对了。

   认的关键字：`type` `required` `properties` `items` `enum` `pattern`
   `minimum` `maximum` `exclusiveMinimum` `exclusiveMaximum` `minLength` `maxLength`
   `minItems` `maxItems`。`$ref` / `anyOf` / `oneOf` / `allOf` / `format` 不深入。

   **宽容的地方（向后兼容，刻意）**：
   - integer / number 接受数字字符串（模型常给数字加引号，函数体以前也照样拿到）；
     boolean 接受 \"true\"/\"false\"；
   - 可选参数给 `null` 视同没给；
   - 未知键不报错。

   schema 与参数的键既可以是 keyword 也可以是字符串（deftool 产 keyword、MCP 给字符串）。"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- sget [m k]
  (when (map? m)
    (let [v (get m k)]
      (if (some? v) v (get m (name k))))))

(defn- aget* [args k]
  (cond
    (contains? args (keyword k)) [true (get args (keyword k))]
    (contains? args (name k))    [true (get args (name k))]
    :else [false nil]))

(defn- parse-number [x]
  (cond
    (number? x) (double x)
    (string? x) (try (Double/parseDouble (str/trim x)) (catch Exception _ nil))
    :else nil))

(defn- type-ok? [t v]
  (case t
    "string"  (string? v)
    "integer" (when-let [d (parse-number v)]
                (and (not (Double/isInfinite d)) (== d (Math/floor d))))
    "number"  (some? (parse-number v))
    "boolean" (or (boolean? v) (#{"true" "false"} v))
    "object"  (map? v)
    "array"   (sequential? v)
    "null"    (nil? v)
    true))

(def ^:private type-label
  {"string" "字符串" "integer" "整数" "number" "数字" "boolean" "布尔值"
   "object" "对象" "array" "数组" "null" "null"})

(defn- kind-of [v]
  (cond (nil? v) "null" (string? v) "字符串" (boolean? v) "布尔值"
        (integer? v) "整数" (number? v) "数字" (map? v) "对象"
        (sequential? v) "数组" (keyword? v) "字符串" :else (str (type v))))

(defn- show [v]
  (let [s (pr-str v)]
    (if (> (count s) 80) (str (subs s 0 77) "...") s)))

(defn- same-value? [a b]
  (let [norm #(if (keyword? %) (name %) %)
        a (norm a) b (norm b)]
    (if (and (number? a) (number? b)) (== a b) (= a b))))

(defn- join-path [path k] (if (str/blank? path) (name k) (str path "." (name k))))

(declare validate-node)

(defn- validate-object [schema obj path errors]
  (let [props (sget schema :properties)
        required (map name (or (sget schema :required) []))
        errors (reduce (fn [errs r]
                         (let [[present? v] (aget* obj r)]
                           (if (and present? (some? v))
                             errs
                             (conj errs {:path (join-path path r)
                                         :description (sget (sget props (keyword r)) :description)
                                         :message "缺少必需参数"}))))
                       errors required)]
    (reduce-kv (fn [errs k ps]
                 (let [[present? v] (aget* obj k)]
                   (if (and present? (some? v))
                     (validate-node ps v (join-path path k) errs)
                     errs)))
               errors
               (or props {}))))

(defn- validate-node [schema v path errors]
  (let [t (some-> (sget schema :type) name)
        err (fn [errs msg] (conj errs {:path path :description (sget schema :description)
                                       :message msg}))]
    (if (and t (not (type-ok? t v)))
      (err errors (str "类型应为" (type-label t t) "，实为" (kind-of v) " " (show v)))
      (let [errors (if-let [en (sget schema :enum)]
                     (if (some #(same-value? % v) en)
                       errors
                       (err errors (str "应为以下之一：" (str/join "、" (map show en))
                                        "；实为 " (show v))))
                     errors)
            errors (if (and (string? v) (not (#{"integer" "number"} t)))
                     (let [len (.codePointCount ^String v 0 (count v))
                           pat (sget schema :pattern)
                           mn (sget schema :minLength)
                           mx (sget schema :maxLength)]
                       (cond-> errors
                         (and pat (not (try (re-find (re-pattern pat) v) (catch Exception _ true))))
                         (err (str "不匹配格式 /" pat "/；实为 " (show v)))
                         (and mn (< len mn)) (err (str "长度至少 " mn "，实为 " len))
                         (and mx (> len mx)) (err (str "长度至多 " mx "，实为 " len))))
                     errors)
            errors (if-let [num (and (#{"integer" "number"} t) (parse-number v))]
                     (let [mn (sget schema :minimum) mx (sget schema :maximum)
                           xmn (sget schema :exclusiveMinimum) xmx (sget schema :exclusiveMaximum)]
                       (cond-> errors
                         (and mn (< num mn)) (err (str "应 ≥ " mn "，实为 " (show v)))
                         (and mx (> num mx)) (err (str "应 ≤ " mx "，实为 " (show v)))
                         (and (number? xmn) (<= num xmn)) (err (str "应 > " xmn "，实为 " (show v)))
                         (and (number? xmx) (>= num xmx)) (err (str "应 < " xmx "，实为 " (show v)))))
                     errors)]
        (cond
          (map? v)
          (if (or (= t "object") (sget schema :properties) (sget schema :required))
            (validate-object schema v path errors)
            errors)

          (sequential? v)
          (let [mn (sget schema :minItems) mx (sget schema :maxItems)
                errors (cond-> errors
                         (and mn (< (count v) mn)) (err (str "至少 " mn " 项，实为 " (count v) " 项"))
                         (and mx (> (count v) mx)) (err (str "至多 " mx " 项，实为 " (count v) " 项")))
                item-schema (sget schema :items)]
            (if (map? item-schema)
              (reduce (fn [errs [i x]]
                        (if (nil? x)
                          (conj errs {:path (str path "[" i "]") :message "数组元素不能为 null"})
                          (validate-node item-schema x (str path "[" i "]") errs)))
                      errors
                      (map-indexed vector v))
              errors))

          :else errors)))))

(defn validate
  "按 schema（JSON Schema 的 object 形状，即工具的 `input_schema` / `parameters`）
   校验 args，返回全部错误 `[{:path :message :description?}]`（无错为空向量）。"
  [schema args]
  (let [args (or args {})]
    (if (map? args)
      (validate-object schema args "" [])
      [{:path "$" :message (str "参数应为对象，实为" (kind-of args))}])))

(defn error-lines
  "错误 → 每处一行的人话：`path（描述）：问题`。"
  [errors]
  (mapv (fn [{:keys [path description message]}]
          (let [desc (when-not (str/blank? description) (str "（" description "）"))]
            ;; 缺参沿用旧措辞「缺少必需参数: x」（已有调用方/测试按它匹配）
            (if (= "缺少必需参数" message)
              (str "缺少必需参数: " path desc)
              (str path desc "：" message))))
        errors))

(defn format-errors
  "给模型看的完整报错：几处、每处的路径与问题、怎么改。"
  [tool-name errors]
  (str "工具 " tool-name " 参数校验失败（" (count errors) " 处）：\n- "
       (str/join "\n- " (error-lines errors))
       "\n请按工具的参数 schema 修正上述参数后重试。"))
