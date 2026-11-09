(ns im.ttalk.agent.tools.syntax
  "「不更糟」语法闸（T-07；出处 Keelson `SyntaxGuard.cs` / `SyntaxCheckers.cs`，借鉴 coxswain `check.ss`）。

   判据是**更糟**不是「不合法」：before 看得了、且 after 的问题数比 before 多才拒（0→1、3→5 拒；
   3→1、3→3 放）。只查 after 会退化成「不合法就拒」，把修复它的那次编辑也拦掉，然后彻底卡死。

   ⚠️ 检查器 `check` 返回 nil（「看不了」）与返回 []（「看过了，没问题」）是**两件事**：审批预览对后者
   说「✓ 改完之后语法没问题」，对前者一个字都不说。拿不准的写法一律回 nil，⛔ 猜。检查器抛异常等同 nil。

   内置：JSON（Jackson，cheshire 带来的）、EDN 与 Clojure 源码（Clojure 读取器）、XML（JDK SAX，禁 DTD）。
   BracketScan.cs 不移植——理由见 designs/tools-module.md §6。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader LispReader$ReaderException]
           [com.fasterxml.jackson.core JsonFactory JsonFactoryBuilder JsonParseException JsonParser JsonToken]
           [com.fasterxml.jackson.core.json JsonReadFeature]
           [java.io StringReader]
           [java.nio.file Path Paths]
           [javax.xml.parsers SAXParserFactory]
           [org.xml.sax SAXParseException]
           [org.xml.sax.helpers DefaultHandler]))

(set! *warn-on-reflection* true)

(defprotocol ISyntaxChecker
  (handles? [checker path] "这个路径归不归它管。第一个说「管」的独家负责。")
  (check [checker path text]
    "查一遍：nil ⇒ 看不了；[] ⇒ 没问题；[{:line :column :message} ...] ⇒ 问题（行列从 1 起）。"))

(defn- file-name ^String [path]
  (str (.getFileName ^Path (Paths/get (str path) (make-array String 0)))))

(defn- ext ^String [path]
  (let [n (file-name path) i (.lastIndexOf n ".")]
    (if (pos? i) (str/lower-case (subs n i)) "")))

(defn checker
  "用谓词与函数造检查器（宿主接 tree-sitter 等用）。check-fn: (fn [path text]) ⇒ nil | [problem]"
  [handles-fn check-fn]
  (reify ISyntaxChecker
    (handles? [_ path] (boolean (handles-fn path)))
    (check [_ path text] (check-fn path text))))

(defn for-extensions
  "按扩展名（带点、不分大小写）挑的检查器。"
  [check-fn & extensions]
  (let [s (set (map str/lower-case extensions))]
    (checker #(contains? s (ext %)) check-fn)))

(defn without-bom ^String [^String s]
  (if (and (pos? (.length s)) (= (int (.charAt s 0)) 0xFEFF)) (subs s 1) s))

;;; ============================================================
;;; JSON
;;; ============================================================

(defn- lenient-json? [path]
  (let [n (str/lower-case (file-name path))
        parent (some-> ^Path (.getParent ^Path (Paths/get (str path) (make-array String 0))) .getFileName str)]
    (or (= (ext path) ".jsonc")
        (str/starts-with? n "tsconfig") (str/starts-with? n "jsconfig")
        (#{"devcontainer.json" ".devcontainer.json"} n)
        (= parent ".vscode"))))

(defn- json-factory ^JsonFactory [lenient?]
  (let [^JsonFactoryBuilder b (JsonFactoryBuilder.)]
    (when lenient?
      (.enable b JsonReadFeature/ALLOW_JAVA_COMMENTS)
      (.enable b JsonReadFeature/ALLOW_TRAILING_COMMA))
    (.build b)))

(def json-checker
  "`.json` 严格；`.jsonc`、`tsconfig*.json` / `jsconfig*.json`、`devcontainer.json`、`.vscode/` 下的允许注释与尾逗号。
   解析器在第一处错误就停 ⇒ 问题数 0 / 1。"
  (reify ISyntaxChecker
    (handles? [_ path] (contains? #{".json" ".jsonc"} (ext path)))
    (check [_ path text]
      (let [text (without-bom text)
            ^JsonParser p (.createParser (json-factory (lenient-json? path)) ^String text)]
        (try
          (loop [depth (num 0) roots (num 0)]
            (let [t (.nextToken p)]
              (cond
                (nil? t) (if (zero? roots)
                           [{:line 1 :column 1 :message "JSON 解析不了：内容为空"}]
                           [])
                (and (zero? depth) (pos? roots))
                (let [loc (.getTokenLocation p)]
                  [{:line (.getLineNr loc) :column (.getColumnNr loc)
                    :message "JSON 解析不了：顶层值之后还有多余内容"}])
                :else
                (let [d (cond (#{JsonToken/START_OBJECT JsonToken/START_ARRAY} t) (inc depth)
                              (#{JsonToken/END_OBJECT JsonToken/END_ARRAY} t) (dec depth)
                              :else depth)]
                  (recur d (if (zero? d) (inc roots) roots))))))
          (catch JsonParseException e
            (let [loc (.getLocation e)
                  msg (first (str/split-lines (or (.getOriginalMessage e) "")))]
              [{:line (max 1 (.getLineNr loc)) :column (max 1 (.getColumnNr loc))
                :message (str "JSON 解析不了：" msg)}]))
          (finally (.close p)))))))

;;; ============================================================
;;; EDN / Clojure
;;; ============================================================

(def ^:private code-exts #{".clj" ".cljs" ".cljc" ".cljd" ".bb"})

(def ^:private undecidable
  "读取器判不了、却不代表语法坏了的报错：自动解析关键字的别名、读取期求值、未知 tag 的 record 等。"
  #"(?i)invalid token: ::|no namespace|read-eval|no reader function|record|class|not loaded|Invalid keyword: ::")

(defn- reader-problem [^Throwable e]
  (let [[line col] (when (instance? LispReader$ReaderException e)
                     [(.-line ^LispReader$ReaderException e) (.-column ^LispReader$ReaderException e)])
        cause (or (.getCause e) e)
        msg (or (.getMessage ^Throwable cause) (str (class cause)))]
    (when-not (re-find undecidable msg)
      (let [start (some->> (re-find #"starting at line (\d+)" msg) second parse-long)]
        [{:line (or start (when (and line (pos? (long line))) line) 1)
          :column (if start 1 (if (and col (pos? (long col))) col 1))
          :message (str "读取不了：" msg)}]))))

(def edn-checker
  "`.edn` 用 clojure.edn（任何 tag 都收）；Clojure 源码（.clj .cljs .cljc .bb）用 LispReader
   （`*read-eval*` 关、读取器条件允许、任何 tag 都收）。未闭合时报「EOF while reading, starting at line N」。
   读取器判不了的写法（`::alias/kw`、`#=` …）⇒ nil。"
  (reify ISyntaxChecker
    (handles? [_ path] (or (= (ext path) ".edn") (contains? code-exts (ext path))))
    (check [_ path text]
      (let [text (without-bom text)
            rdr (LineNumberingPushbackReader. (StringReader. text))
            eof (Object.)]
        (try
          (if (= (ext path) ".edn")
            (loop [] (if (identical? eof (edn/read {:eof eof :default (fn [_ v] v)} rdr)) [] (recur)))
            (binding [*read-eval* false
                      *default-data-reader-fn* (fn [_ v] v)
                      *ns* (create-ns 'im.ttalk.agent.tools.syntax.scratch)]
              (loop [] (if (identical? eof (read {:eof eof :read-cond :allow} rdr)) [] (recur)))))
          (catch Exception e (reader-problem e)))))))

;;; ============================================================
;;; XML
;;; ============================================================

(def ^:private xml-exts
  #{".xml" ".csproj" ".fsproj" ".vbproj" ".props" ".targets" ".resx" ".axaml" ".xaml" ".svg"
    ".xsd" ".xsl" ".xslt" ".plist" ".pom" ".nuspec" ".config"})

(def xml-checker
  "XML 家族：JDK SAX，禁 DTD、禁外部实体；带 `<!DOCTYPE` 的回 nil（不处理 DTD 就判不了里面的实体引用）。
   第一处错误就停 ⇒ 0 / 1。"
  (reify ISyntaxChecker
    (handles? [_ path] (contains? xml-exts (ext path)))
    (check [_ _ text]
      (let [text (without-bom text)]
        (if (str/includes? text "<!DOCTYPE")
          nil
          (let [f (doto (SAXParserFactory/newInstance)
                    (.setNamespaceAware true)
                    (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
                    (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                    (.setFeature "http://xml.org/sax/features/external-parameter-entities" false))]
            (try
              (.parse (.newSAXParser f) (org.xml.sax.InputSource. (StringReader. text)) (DefaultHandler.))
              []
              (catch SAXParseException e
                [{:line (max 1 (.getLineNumber e)) :column (max 1 (.getColumnNumber e))
                  :message (str "XML 解析不了：" (.getMessage e))}]))))))))

(def built-in
  "内置检查器（各管各的扩展名，互不重叠）。"
  [json-checker edn-checker xml-checker])

;;; ============================================================
;;; 判断
;;; ============================================================

(defn problems
  "挑检查器并查；不归任何一个管、检查器回 nil 或抛异常 ⇒ nil。
   guard: {:enabled? true :checkers [...]}——宿主给的 :checkers 排在内置前面。"
  [guard path text]
  (try
    (let [cs (concat (:checkers guard) built-in)]
      (when-let [c (first (filter #(handles? % (str path)) cs))]
        (some-> (check c (str path) (without-bom text)) vec)))
    (catch Exception _ nil)))

(defn judge
  "判一次：before / after **都**过一遍。闸关着或 after 看不了 ⇒ nil。
   before 为 nil ⇒ 新文件：不判，但预览照常对 after 下结论。
   ⇒ {:before [..]|nil :after [..] :worse? bool}"
  [guard path before after]
  (when (:enabled? guard true)
    (when-let [a (problems guard path after)]
      (let [b (when (some? before) (problems guard path before))]
        {:before b :after a :worse? (boolean (and b (> (count a) (count b))))}))))

(def ^:const max-listed 20)

(defn- headline [{:keys [before after]}]
  (if (zero? (count before))
    "这次改动让文件解析不了了。"
    (format "这次改动让文件的语法问题从 %d 处变成了 %d 处。" (count before) (count after))))

(defn- problem-lines [{:keys [after]}]
  (str (str/join (map (fn [{:keys [line column message]}] (format "  第 %d 行第 %d 列：%s\n" line column message))
                      (take max-listed after)))
       (when (> (count after) max-listed) (format "  ……另有 %d 处\n" (- (count after) max-listed)))))

(defn rejection
  "edit_file / write_file 的拒绝文案。"
  [verdict shown]
  (str shown "：" (headline verdict) "没有写入。\n" (problem-lines verdict)
       "下一步：先 read_file 看一眼现在的样子，然后一次改完整（这次改动的中间状态不合法的话，把它合并成一次编辑，或者用 write_file 整篇写一遍）。"))

(defn patch-file-lines [verdict shown]
  (str shown "：" (headline verdict) "\n" (problem-lines verdict)))

(defn patch-rejection [files]
  (str files "整份补丁一个文件也没写。\n"
       "下一步：先 read_file 看一眼现在的样子，然后一次改完整（这次改动的中间状态不合法的话，把它合并成一份补丁，或者用 write_file 整篇写一遍）。"))

(defn preview-note
  "审批预览 diff 的尾巴：与执行时拒不拒是同一次判断。"
  [{:keys [before after worse?] :as v}]
  (cond
    worse? (str "⚠️ 批准之后这次调用会被拒：" (headline v))
    (empty? after) "✓ 改完之后语法没问题"
    (nil? before) (format "⚠️ 改完之后这个文件有 %d 处语法问题（改动之前的内容没法检查，没有可比的，照常执行）" (count after))
    :else (format "⚠️ 改完之后这个文件仍然有 %d 处语法问题（不是这次改动造成的，照常执行）" (count after))))

(defn guard-of
  "工作区的语法闸配置。"
  [ws]
  (merge {:enabled? true} (:syntax ws)))
