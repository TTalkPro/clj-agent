(ns im.ttalk.agent.model.content
  "中立多模态内容部件（content parts）—— provider 无关的图片 / 文件 / 音频表示。

   消息的 `:content` 有两种合法形态：

   1. **字符串**（既有形态，纯文本，最常用）
   2. **部件向量**（多模态）：

   ```clojure
   {:role :user
    :content [(content/text-part \"这张图里有什么？\")
              (content/image-part \"https://example.com/cat.png\")]}
   ```

   部件形状（对齐 Vercel AI SDK 的 `LanguageModelPrompt` 部件模型——
   图片 / 音频 / PDF **不各立一类**，统一是 `:file` + `:media-type`，
   wire 层按 media type 的顶层类别分派；这样新格式不需要新部件类型）：

   ```clojure
   {:type :text :text \"...\"}
   {:type :file :media-type \"image/png\"       :data \"<base64>\"}   ; 内联数据
   {:type :file :media-type \"image/png\"       :url  \"https://...\"} ; 远端 URL
   {:type :file :media-type \"application/pdf\" :data \"<base64>\" :filename \"a.pdf\"}
   ```

   约束与边界：

   - **中立层只搬运，不解释**：能不能发（Anthropic 不收音频、OpenAI 的 PDF 只收内联）
     由各 provider 的 wire 转换器判定，不支持时抛规范错误（`:validation-error`，
     不可重试），**不静默丢内容**。
   - **与厂商原生块共存**：`:content` 向量里 `:type` 为**字符串**的元素（如
     Anthropic 的 `{:type \"text\"}` / `{:type \"document\"}`）被视为厂商原生块，
     wire 层**原样透传**——既有那条逃生通道不受影响，两者可混用。
   - 部件里的二进制一律以 **base64 字符串**落地：对话历史要经 EDN / JSON 往返
     （SQLite store），字节数组过不去。

   (require '[im.ttalk.agent.model.content :as content]
            '[im.ttalk.agent.model.message :as msg])

   (msg/user [(content/text-part \"描述这张图\")
              (content/image-part (io/file \"cat.png\"))])"
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.net URI URL]
           [java.nio.file Files]
           [java.util Base64]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 二进制 → base64
;;; ============================================================

(defn bytes->base64
  "字节数组 → base64 字符串"
  ^String [^bytes data]
  (.encodeToString (Base64/getEncoder) data))

(def ^:private extension->media-type
  "常见扩展名 → media type。查不到时回落 JDK 的
   `Files/probeContentType`，仍查不到才要求调用方显式给 :media-type。"
  {"png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "webp" "image/webp"
   "bmp"  "image/bmp"
   "pdf"  "application/pdf"
   "txt"  "text/plain"
   "md"   "text/markdown"
   "csv"  "text/csv"
   "json" "application/json"
   "wav"  "audio/wav"
   "mp3"  "audio/mpeg"
   "m4a"  "audio/mp4"
   "ogg"  "audio/ogg"
   "flac" "audio/flac"})

(defn- guess-media-type
  "从文件名猜 media type（猜不出返回 nil）"
  [^String filename]
  (when filename
    (let [ext (some-> (re-find #"\.([A-Za-z0-9]+)$" filename) second str/lower-case)]
      (or (get extension->media-type ext)
          (try (Files/probeContentType (.toPath (File. filename)))
               (catch Exception _ nil))))))

(defn- data-uri?
  [s]
  (and (string? s) (str/starts-with? s "data:")))

(defn- http-url?
  [s]
  (and (string? s)
       (or (str/starts-with? s "http://")
           (str/starts-with? s "https://"))))

(defn- parse-data-uri
  "data:image/png;base64,AAAA → {:media-type \"image/png\" :data \"AAAA\"}
   非 base64 的 data URI（明文）不支持，抛错而非静默截断。"
  [^String s]
  (let [[header payload] (str/split (subs s 5) #"," 2)]
    (when (or (nil? payload) (not (str/includes? (or header "") "base64")))
      (throw (ex-info "只支持 base64 形态的 data URI"
                      {:type :validation-error :retryable? false
                       :context {:data-uri (subs s 0 (min 40 (count s)))}})))
    {:media-type (-> header (str/replace #";base64.*$" "") (str/replace #"^;" ""))
     :data payload}))

(defn- coerce-source
  "把各种来源规整为 {:data <base64>} 或 {:url <string>}（可能带上猜到的
   :media-type / :filename）。

   支持：http(s) URL 字符串、data URI 字符串、base64 字符串、byte[]、
   java.io.File、java.net.URL/URI。"
  [source]
  (cond
    (data-uri? source) (parse-data-uri source)
    (http-url? source) {:url source}
    (string? source)   {:data source}
    (bytes? source)    {:data (bytes->base64 source)}

    (instance? File source)
    (let [^File f source]
      {:data (bytes->base64 (Files/readAllBytes (.toPath f)))
       :media-type (guess-media-type (.getName f))
       :filename (.getName f)})

    (or (instance? URL source) (instance? URI source))
    {:url (str source)}

    :else
    (throw (ex-info "无法识别的内容来源（支持 URL / data URI / base64 字符串 / byte[] / File）"
                    {:type :validation-error :retryable? false
                     :context {:source-class (str (class source))}}))))

;;; ============================================================
;;; 部件构造
;;; ============================================================

(defn text-part
  "文本部件 {:type :text :text \"...\"}"
  [text]
  {:type :text :text text})

(defn file-part
  "文件部件（图片 / 音频 / PDF 等统一走这里）。

   参数：
   - source: URL 字符串 / data URI / base64 字符串 / byte[] / java.io.File /
             java.net.URL / java.net.URI
   - opts:   {:media-type \"image/png\" :filename \"a.png\"}
             内联数据（非 URL、非 File）**必须**给 :media-type——猜不出来的东西
             不猜，否则错的 media type 会让服务端报一个与真实原因无关的错。

   返回：{:type :file :media-type ... (:data | :url) ... (:filename)}"
  ([source] (file-part source nil))
  ([source {:keys [media-type filename]}]
   (let [{:as coerced :keys [data url]} (coerce-source source)
         mt (or media-type
                (:media-type coerced)
                (when url (guess-media-type url)))
         fname (or filename (:filename coerced))]
     (when (and (nil? mt) data)
       (throw (ex-info "内联文件部件必须提供 :media-type"
                       {:type :validation-error :retryable? false})))
     (cond-> {:type :file}
       mt    (assoc :media-type mt)
       data  (assoc :data data)
       url   (assoc :url url)
       fname (assoc :filename fname)))))

(defn image-part
  "图片部件。`(image-part \"https://.../a.png\")` / `(image-part (io/file \"a.png\"))` /
   `(image-part base64 {:media-type \"image/png\"})`。

   URL 来源猜不出 media type 时缺省 \"image/*\"——OpenAI 的 image_url 用不到它，
   Anthropic 的 url source 也不带 media_type，留个顶层类别足够 wire 层分派。"
  ([source] (image-part source nil))
  ([source opts]
   (let [p (file-part source opts)]
     (cond-> p
       (nil? (:media-type p)) (assoc :media-type "image/*")))))

(defn audio-part
  "音频部件。内联数据须给 :media-type（如 \"audio/wav\" / \"audio/mpeg\"）。"
  ([source] (audio-part source nil))
  ([source opts] (file-part source opts)))

;;; ============================================================
;;; 谓词与访问器
;;; ============================================================

(defn part?
  "是否为**中立**内容部件（:type 为 keyword）。
   `:type` 为字符串的一律不是——那是厂商原生块，wire 层原样透传。"
  [x]
  (and (map? x)
       (contains? #{:text :file} (:type x))))

(defn text-part? [x] (and (part? x) (= :text (:type x))))
(defn file-part? [x] (and (part? x) (= :file (:type x))))

(defn parts?
  "content 是否为多模态部件向量（含至少一个中立部件）。
   纯字符串 content 与纯厂商原生块向量都返回 false。"
  [content]
  (boolean (and (sequential? content) (some part? content))))

(defn media-type
  [part]
  (:media-type part))

(defn top-level-media-type
  "media type 的顶层类别：\"image/png\" → \"image\"（无则 nil）"
  [part]
  (some-> (:media-type part) (str/split #"/") first str/lower-case))

(defn image?
  [part]
  (= "image" (top-level-media-type part)))

(defn audio?
  [part]
  (= "audio" (top-level-media-type part)))

(defn data-uri
  "内联部件 → \"data:<media-type>;base64,<data>\"（无 :data 返回 nil）"
  [{:keys [media-type data]}]
  (when data
    (str "data:" (or media-type "application/octet-stream") ";base64," data)))

(defn text-of
  "取 content 的纯文本（字符串原样；部件向量取其中文本部件拼接；
   厂商原生块认 :text 键）。多模态消息要做关键词匹配 / 日志时用它。"
  [content]
  (cond
    (string? content) content
    (sequential? content) (->> content
                               (keep #(cond
                                        (string? %) %
                                        (text-part? %) (:text %)
                                        (map? %) (:text %)))
                               (str/join " "))
    :else ""))
