(ns im.ttalk.agent.tools.files
  "文件工具（T-02）：read_file / list_dir / glob / grep / write_file / delete_path。

   read_file / list_dir / grep 的口径翻译自 OpenAI Codex CLI（Apache-2.0，Copyright 2025 OpenAI；
   见模块根 THIRD-PARTY-NOTICES.md），经 Keelson `ReadFileTool.cs` / `ListDirTool.cs` / `GrepTool.cs` 转写：
   - read_file：codex-rs/core/src/tools/handlers/read_file.rs（14c35a16a8^）——offset 缺省 1、limit 缺省 2000、
     单行 500 字节、TAB 宽 4、行前缀 `L{n}: `、slice / indentation 两种模式；
   - list_dir：codex-rs/core/src/tools/handlers/list_dir.rs（70807730f5^）——offset 1、limit 25、depth 2、
     条目名 500 字节、每层缩进 2 空格；
   - grep：codex-rs/core/src/tools/handlers/grep_files.rs（178c3b15b4^）——limit 100、顶 2000、按修改时间倒序。
   glob 是 Keelson 新增（Codex 没有）；write_file / delete_path 借鉴 coxswain（Apache-2.0）。

   每个工具函数体只读 `(ws/current)`——工作区由 `im.ttalk.agent.tools/workspace-tools` 绑定。"
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.freshness :as fresh]
            [im.ttalk.agent.tools.syntax :as syntax]
            [im.ttalk.agent.tools.text :as text]
            [im.ttalk.agent.tools.walk :as walk]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.io ByteArrayOutputStream File InputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption NoSuchFileException Path]
           [java.util.concurrent TimeUnit]
           [java.util.regex Pattern PatternSyntaxException]))

(set! *warn-on-reflection* true)

(defn- pos-arg!
  "数值参数：nil ⇒ 缺省；≤ 0 ⇒ fail!。"
  [v default code msg]
  (let [v (if (nil? v) default (long v))]
    (when (<= v 0) (fail! code msg))
    v))

;;; ============================================================
;;; read_file
;;; ============================================================

(def ^:const read-max-line-bytes 500)
(def ^:const tab-width 4)
(def ^:const read-default-limit 2000)

(defn- format-line ^String [^bytes raw]
  (text/take-bytes-at-char-boundary (String. raw StandardCharsets/UTF_8) read-max-line-bytes))

(defn- scan-lines
  "流式按 \\n 切行（去掉结尾 \\r），同时算整篇 tag。f: (fn [n ^bytes line]) ⇒ 真值继续交行，假值之后只算 tag。
   ⇒ {:tag :lines 总行数}"
  [^InputStream in f]
  (let [buf (byte-array 16384)
        line (ByteArrayOutputStream.)
        h (volatile! text/fnv-offset)
        n (volatile! 0)
        want (volatile! true)
        emit (fn []
               (vswap! n inc)
               (when @want
                 (let [^bytes bs (.toByteArray line)
                       len (alength bs)
                       bs (if (and (pos? len) (= (aget bs (dec len)) (byte 13)))
                            (java.util.Arrays/copyOf bs (dec len))
                            bs)]
                   (when-not (f @n bs) (vreset! want false))))
               (.reset line))]
    (loop []
      (let [r (.read in buf)]
        (when (pos? r)
          (vreset! h (text/tag-append @h buf 0 r))
          (loop [i 0 start 0]
            (if (< i r)
              (if (= (aget buf i) (byte 10))
                (do (when @want (.write line buf start (- i start)))
                    (emit)
                    (recur (inc i) (inc i)))
                (recur (inc i) start))
              (when @want (.write line buf start (- r start)))))
          (recur))))
    (when (pos? (.size line)) (emit))
    {:tag (text/tag-format @h) :lines @n}))

(defn- read-slice [in offset limit]
  (let [collected (transient [])
        {:keys [tag lines]} (scan-lines in (fn [n raw]
                                             (if (< n offset)
                                               true
                                               (do (conj! collected (str "L" n ": " (format-line raw)))
                                                   (< (count collected) limit)))))]
    {:tag tag
     :lines (persistent! collected)
     :error (when (< lines offset) "offset 超出了文件的行数。")}))

(defn- measure-indent [^String s]
  (loop [i 0 n 0]
    (if (< i (.length s))
      (case (.charAt s i) \space (recur (inc i) (inc n)) \tab (recur (inc i) (+ n tab-width)) n)
      n)))

(defn- comment-line? [^String s]
  (let [t (str/trim s)] (some #(str/starts-with? t %) ["#" "//" "--"])))

(defn- read-block
  "Codex indentation::read_block（逐行照搬，含它「先放进来再撤掉」的写法）。"
  [in offset limit {:keys [anchor_line max_levels include_siblings include_header max_lines]}]
  (let [anchor (long (or anchor_line offset))
        guard (long (or max_lines limit))
        recs (transient [])
        {:keys [tag]} (scan-lines in (fn [n ^bytes raw]
                                       (let [t (String. raw StandardCharsets/UTF_8)]
                                         (conj! recs {:n n :raw t :display (format-line raw) :indent (measure-indent t)})
                                         true)))
        recs (persistent! recs)
        fmt (fn [r] (str "L" (:n r) ": " (:display r)))]
    (cond
      (<= anchor 0) {:tag tag :error "anchor_line 必须是从 1 起的行号。"}
      (<= guard 0) {:tag tag :error "max_lines 必须大于零。"}
      (or (empty? recs) (> anchor (count recs))) {:tag tag :error "anchor_line 超出了文件的行数。"}
      :else
      (let [effective (loop [k 0 prev (num 0) acc (transient [])]
                        (if (< k (count recs))
                          (let [r (recs k) prev (if (str/blank? (:raw r)) prev (:indent r))]
                            (recur (inc k) prev (conj! acc prev)))
                          (persistent! acc)))
            ai (dec anchor)
            max-levels (max 0 (long (or max_levels 0)))
            siblings? (boolean include_siblings)
            header? (if (nil? include_header) true (boolean include_header))
            anchor-indent (effective ai)
            min-indent (if (zero? max-levels) 0 (max 0 (- anchor-indent (* max-levels tab-width))))
            final-limit (min limit guard (count recs))]
        (if (= final-limit 1)
          {:tag tag :lines [(fmt (recs ai))]}
          (let [out (loop [i (num (dec ai)) j (num (inc ai)) ic (num 0) jc (num 0)
                           out (java.util.LinkedList. [(recs ai)])]
                      (if (>= (.size out) final-limit)
                        out
                        (let [;; 往上
                              [i ic up] (if (and (>= i 0) (>= (effective i) min-indent))
                                          (do (.addFirst out (recs i))
                                              (if (and (= (effective i) min-indent) (not siblings?))
                                                (if (or (and header? (comment-line? (:raw (recs i)))) (zero? ic))
                                                  [(dec i) (inc ic) 1]
                                                  (do (.removeFirst out) [-1 ic 0]))
                                                [(dec i) ic 1]))
                                          [-1 ic 0])]
                          (if (>= (.size out) final-limit)
                            out
                            (let [[j jc down] (if (and (< j (count recs)) (>= (effective j) min-indent))
                                                (do (.addLast out (recs j))
                                                    (if (and (= (effective j) min-indent) (not siblings?))
                                                      (if (pos? jc)
                                                        (do (.removeLast out) [(count recs) (inc jc) 0])
                                                        [(inc j) (inc jc) 1])
                                                      [(inc j) jc 1]))
                                                [(count recs) jc 0])]
                              (if (zero? (+ up down))
                                out
                                (recur i j ic jc out)))))))
                out (vec out)
                out (drop-while #(str/blank? (:raw %)) out)
                out (reverse (drop-while #(str/blank? (:raw %)) (reverse out)))]
            {:tag tag :lines (mapv fmt out)}))))))

(defn read-file*
  [w {:keys [file_path offset limit mode] :as args}]
  (let [offset (pos-arg! offset 1 "tools/read-file-offset" "offset 必须是从 1 起的行号。")
        limit (pos-arg! limit read-default-limit "tools/limit-zero" "limit 必须大于零。")
        full (ws/resolve! w file_path "file_path" :read)
        e (ws/target-entry w full)]
    (cond
      (nil? e) (fail! "tools/not-found" "读文件失败：文件或目录不存在")
      (= :dir (:kind e)) (fail! "tools/is-directory" "读文件失败：这是目录"))
    (let [{:keys [tag lines error]}
          (try
            (with-open [^InputStream in (ws/open-in (:files w) full)]
              (if (= mode "indentation")
                (read-block in offset limit args)
                (read-slice in offset limit)))
            (catch NoSuchFileException _ (fail! "tools/not-found" "读文件失败：文件或目录不存在"))
            (catch java.io.IOException e (fail! "tools/read-failed" (str "读文件失败：" (.getMessage e)))))
          reads (:reads w)
          header (when reads
                   ;; tag 按整篇算（局部读也算读过），印进头里就登记；报错也带头、也登记
                   (fresh/note! reads (ws/real full) tag)
                   (fresh/header file_path tag))]
      (if error
        (fail! "tools/read-file-window" (if header (str header "\n" error) error))
        (str/join "\n" (cond->> lines header (cons header)))))))

(deftool read_file
  "读本地文件，每行带从 1 起的行号（「L行号: 」），支持按行切片（slice，缺省）与按缩进取块（indentation）两种模式。大文件用 offset/limit 分页。输出第一行是 [路径#TAG]：之后改这个文件要求读过、且读完没变。"
  [[file_path :string "文件路径（相对路径按工作目录）"]
   [offset :int "从第几行开始读，至少为 1（缺省 1）" :default nil]
   [limit :int "最多回多少行（缺省 2000）" :default nil]
   [mode :string "\"slice\" 读一段连续的行（缺省），\"indentation\" 以锚定行为中心按缩进展开" :default nil]
   [anchor_line :int "indentation 模式：围绕的锚定行（缺省为 offset）" :default nil]
   [max_levels :int "indentation 模式：往外再包含几层父级缩进" :default nil]
   [include_siblings :boolean "indentation 模式：把同一缩进的兄弟块也带上" :default nil]
   [include_header :boolean "indentation 模式：带上紧贴块上方的注释（缺省 true）" :default nil]
   [max_lines :int "indentation 模式：回的行数硬上限" :default nil]]
  {:tags [:read-only :files]}
  (read-file* (ws/current) {:file_path file_path :offset offset :limit limit :mode mode
                             :anchor_line anchor_line :max_levels max_levels
                             :include_siblings include_siblings :include_header include_header
                             :max_lines max_lines}))

;;; ============================================================
;;; list_dir
;;; ============================================================

(def ^:const list-max-entry-bytes 500)
(def ^:const list-indent 2)

(defn- list-kind [^Path p]
  (cond (Files/isSymbolicLink p) :link
        (Files/isDirectory p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])) :dir
        (Files/isRegularFile p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])) :file
        :else :other))

(defn list-dir*
  [w {:keys [dir_path offset limit depth]}]
  (let [offset (pos-arg! offset 1 "tools/list-dir-offset" "offset 必须是从 1 起的条目序号。")
        limit (pos-arg! limit 25 "tools/limit-zero" "limit 必须大于零。")
        depth (pos-arg! depth 2 "tools/list-dir-depth" "depth 必须大于零。")
        full (ws/resolve! w dir_path "dir_path" :read true)
        e (ws/target-entry w full)]
    (cond
      (nil? e) (fail! "tools/not-found" "读目录失败：文件或目录不存在")
      (not= :dir (:kind e)) (fail! "tools/not-directory" "读目录失败：不是目录"))
    ;; Codex list_dir_slice_with_policy：BFS 收集 → 按相对路径排序 → 分页
    (let [entries (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [full "" depth]) acc []]
                    (if-let [[^Path dir prefix remaining] (peek queue)]
                      (let [kids (try (with-open [ds (Files/newDirectoryStream dir)] (vec ds))
                                      (catch Exception ex
                                        (fail! "tools/list-dir-failed" (str "读目录失败：" (.getMessage ^Exception ex)))))
                            kids (->> kids
                                      (filter #(nil? (ws/deny w % :read)))
                                      (map (fn [^Path c]
                                             (let [n (str (.getFileName c))
                                                   rel (if (= "" prefix) n (str prefix "/" n))]
                                               {:path c
                                                :rel rel
                                                :name (text/take-bytes-at-char-boundary rel list-max-entry-bytes)
                                                :display (text/take-bytes-at-char-boundary n list-max-entry-bytes)
                                                :depth (if (= "" prefix) 0 (inc (count (re-seq #"/" prefix))))
                                                :kind (list-kind c)})))
                                      (sort-by :name))]
                        (recur (into (pop queue)
                                     (for [k kids :when (and (= :dir (:kind k)) (> remaining 1))]
                                       [(:path k) (:rel k) (dec remaining)]))
                               (into acc kids)))
                      acc))
          entries (vec (sort-by :name entries))
          start (dec offset)]
      (when (and (seq entries) (>= start (count entries)))
        (fail! "tools/list-dir-offset" "offset 超出了目录的条目数。"))
      (let [end (min (count entries) (+ start limit))
            lines (for [{:keys [depth display kind]} (subvec entries (min start (count entries)) end)]
                    (str (apply str (repeat (* depth list-indent) " "))
                         display
                         (case kind :dir "/" :link "@" :other "?" "")))]
        (str/join "\n" (concat [(str "绝对路径：" full)]
                               lines
                               (when (< end (count entries)) [(str "超过 " (- end start) " 条")])))))))

(deftool list_dir
  "列本地目录的条目（广度优先、按名字排序、子项缩进），带简单的类型标记（目录 /、链接 @、其它 ?）。"
  [[dir_path :string "要列的目录（相对路径按工作目录；空串 = 工作目录）"]
   [offset :int "从第几条开始列，至少为 1（缺省 1）" :default nil]
   [limit :int "最多回多少条（缺省 25）" :default nil]
   [depth :int "最多往下走几层目录，至少为 1（缺省 2）" :default nil]]
  {:tags [:read-only :files]}
  (list-dir* (ws/current) {:dir_path dir_path :offset offset :limit limit :depth depth}))

;;; ============================================================
;;; glob
;;; ============================================================

(def ^:const search-default-limit 100)
(def ^:const search-max-limit 2000)

(defn glob*
  [w {:keys [pattern path limit]}]
  (let [pattern (str/trim (str pattern))
        _ (when (= "" pattern) (fail! "tools/empty-pattern" "pattern 不能为空。"))
        limit (min search-max-limit (pos-arg! limit search-default-limit "tools/limit-zero" "limit 必须大于零。"))
        root (ws/resolve! w path "path" :read true)
        e (ws/target-entry w root)]
    (cond
      (nil? e) (fail! "tools/not-found" (format "无法访问 `%s`：文件或目录不存在" root))
      (not= :dir (:kind e)) (fail! "tools/not-directory" (format "%s 不是目录：path 要给目录。" root)))
    (let [m (walk/path-glob pattern false)
          hits (walk/walk w root {:skip-hidden? false :pred m})]
      (if (empty? hits)
        "没有找到文件。"
        (str/join "\n" (map (comp str :path) (take limit (walk/newest-first hits))))))))

(deftool glob
  "按 glob 模式找文件（* 不跨目录，** 跨任意层目录，{a,b} 任选其一），按修改时间（新的在前）列出路径。认 .gitignore / .ignore，跳过 .git。"
  [[pattern :string "glob 模式，相对 path（如 \"**/*.clj\"、\"src/*.{ts,tsx}\"）"]
   [path :string "从哪个目录找；缺省为工作目录" :default nil]
   [limit :int "最多回多少个路径（缺省 100，最多 2000）" :default nil]]
  {:tags [:read-only :files]}
  (glob* (ws/current) {:pattern pattern :path path :limit limit}))

;;; ============================================================
;;; grep
;;; ============================================================

(def ^:const binary-probe-bytes 8192)

(defn- find-rg
  "rg 可执行文件：:grep {:rg :auto}（缺省，PATH 里找）| false（不用）| \"路径\"。结果按工作区缓存。"
  [w]
  (let [opt (get-in w [:grep :rg] :auto)]
    (cond
      (false? opt) nil
      (string? opt) opt
      :else
      (let [cache (:state w)]
        (if (contains? @cache ::rg)
          (::rg @cache)
          (let [exe (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win") "rg.exe" "rg")
                found (some (fn [d] (let [f (File. ^String d ^String exe)] (when (.canExecute f) (str f))))
                            (str/split (or (System/getenv "PATH") "") (re-pattern (Pattern/quote File/pathSeparator))))]
            (swap! cache assoc ::rg found)
            found))))))

(defn- grep-rg
  "rg --files-with-matches；⇒ {:paths [...]} | {:error msg}（正则写错）| nil（rg 自己坏了，回落 JVM）。"
  [w rg pattern include ^Path search]
  (let [max-bytes (get-in w [:grep :max-file-bytes] (* 16 1024 1024))
        argv (cond-> [rg "--files-with-matches" "--sortr=modified" "--no-messages" "--color=never"
                      "--max-filesize" (str max-bytes) "--regexp" pattern]
               include (into ["--glob" include])
               true (into ["--" (str search)]))
        pb (doto (ProcessBuilder. ^java.util.List argv) (.redirectErrorStream false))
        p (.start pb)
        _ (.close (.getOutputStream p))
        out (future (slurp (.getInputStream p) :encoding "UTF-8"))
        err (future (slurp (.getErrorStream p) :encoding "UTF-8"))]
    (if-not (.waitFor p 30 TimeUnit/SECONDS)
      (do (.destroyForcibly p) nil)
      (case (.exitValue p)
        0 {:paths (vec (remove str/blank? (str/split-lines @out)))}
        1 {:paths []}
        (let [e @err]
          (if (re-find #"(?i)regex parse error|error parsing regex" e)
            {:error (str/trim e)}
            nil))))))

(defn- deadline-seq ^CharSequence [^CharSequence s ^long deadline]
  (reify CharSequence
    (charAt [_ i]
      (when (> (System/nanoTime) deadline) (throw (ex-info "regex timeout" {::timeout true})))
      (.charAt s i))
    (length [_] (.length s))
    (subSequence [_ a b] (deadline-seq (.subSequence s a b) deadline))
    (toString [_] (str s))))

(defn- file-matches? [w ^Pattern re ^Path p size]
  (let [max-bytes (get-in w [:grep :max-file-bytes] (* 16 1024 1024))
        timeout-ns (* 1000000 (long (get-in w [:grep :regex-timeout-ms] 1000)))]
    (when (<= (long size) (long max-bytes))
      (when-let [^bytes bs (try (Files/readAllBytes p) (catch Exception _ nil))]
        (let [probe (min (alength bs) binary-probe-bytes)]
          (when-not (some #(zero? (aget bs (int %))) (range probe))
            (let [s (String. bs StandardCharsets/UTF_8)]
              (try
                (boolean (some (fn [^String line]
                                 (.find (.matcher re (deadline-seq line (+ (System/nanoTime) timeout-ns)))))
                               (str/split s #"\n")))
                (catch clojure.lang.ExceptionInfo e
                  (if (::timeout (ex-data e))
                    (fail! "tools/grep-timeout" (format "在 %s 里匹配超时：把正则改简单些（避免嵌套的重复）。" p))
                    (throw e)))))))))))

(defn grep*
  [w {:keys [pattern include path limit]}]
  (let [pattern (str/trim (str pattern))
        _ (when (= "" pattern) (fail! "tools/empty-pattern" "pattern 不能为空。"))
        limit (min search-max-limit (pos-arg! limit search-default-limit "tools/limit-zero" "limit 必须大于零。"))
        search (ws/resolve! w path "path" :read true)
        top (ws/target-entry w search)
        include (some-> include str/trim not-empty)
        _ (when-not top (fail! "tools/not-found" (format "无法访问 `%s`：文件或目录不存在" search)))
        via-rg (when-let [rg (find-rg w)]
                 (try (grep-rg w rg pattern include search) (catch java.io.IOException _ nil)))
        hits (cond
               (:error via-rg) (fail! "tools/grep-invalid-pattern" (str "正则写错了：" (:error via-rg)))
               via-rg (->> (:paths via-rg)
                           (map #(ws/->path %))
                           ;; rg 不认识路径策略：结果再过一遍
                           (filter #(nil? (ws/deny w (.normalize ^Path %) :read)))
                           (map str))
               :else
               (let [re (try (Pattern/compile pattern)
                             (catch PatternSyntaxException e
                               (fail! "tools/grep-invalid-pattern" (str "正则写错了：" (.getMessage e)))))]
                 (if (= :file (:kind top))
                   ;; 直接点名的文件：不看 include / 隐藏 / 忽略规则（同 rg）
                   (when (file-matches? w re search (:size top)) [(str search)])
                   (let [m (when include (walk/path-glob include true))]
                     (->> (walk/walk w search {:skip-hidden? true :pred m})
                          (filter #(file-matches? w re (:path %) (:size %)))
                          walk/newest-first
                          (map (comp str :path)))))))]
    (if (empty? hits)
      "没有匹配。"
      (str/join "\n" (take limit hits)))))

(deftool grep
  "找内容匹配正则的文件，按修改时间（新的在前）列出路径（不回匹配行）。认 .gitignore / .ignore，跳过隐藏文件与二进制文件。装了 ripgrep 时用 rg（Rust regex 语法，不支持环视 / 反向引用），否则用 Java regex，逐行匹配。"
  [[pattern :string "要找的正则表达式（逐行匹配）"]
   [include :string "可选的 glob，只在匹配它的文件里找（如 \"*.clj\" 或 \"*.{ts,tsx}\"）" :default nil]
   [path :string "要找的目录或文件；缺省为工作目录" :default nil]
   [limit :int "最多回多少个文件路径（缺省 100）" :default nil]]
  {:tags [:read-only :files]}
  (grep* (ws/current) {:pattern pattern :include include :path path :limit limit}))

;;; ============================================================
;;; write_file
;;; ============================================================

(defn- read-existing
  "读现有文件字节；不存在（含悬空链接）⇒ nil。"
  [w ^Path full shown]
  (try
    (ws/read-all-bytes (:files w) full)
    (catch NoSuchFileException _ nil)
    (catch java.io.IOException e
      (fail! "tools/text-file-read-failed" (format "读 %s 失败：%s" shown (.getMessage e))))))

(defn plan-write
  "从参数到「要写下去的字节」的全部判断（无副作用：预览与执行共用）。"
  [w {:keys [path content]}]
  (when (str/blank? path) (fail! "tools/relative-path" "path 不能为空"))
  (let [content (str content)
        full (ws/resolve! w path "path" :write)
        shown path
        e (ws/target-entry w full)
        _ (when (= :dir (:kind e))
            (fail! "tools/text-file-is-directory"
                   (format "%s 是一个目录，不能当文件写。要写它下面的文件，路径里带上文件名。" shown)))
        existing (when e (read-existing w full shown))
        guard (syntax/guard-of w)]
    (if (nil? existing)
      ;; 新建：不要求读过，内容原样（UTF-8 无 BOM）
      {:full full :shown shown :before nil :after content
       :output (.getBytes content StandardCharsets/UTF_8)
       :verdict (syntax/judge guard full nil content)}
      (let [file (text/decode-editable existing shown)
            _ (fresh/check! (:reads w) (ws/real full) shown (text/content-tag existing))
            after (text/normalize-input file content)]
        {:full full :shown shown :before (:text file) :after after
         :output (text/encode-editable file after shown)
         :verdict (syntax/judge guard full (:text file) after)}))))

(defn commit-plan!
  "落盘一个 plan（edit_file / write_file 共用）：语法闸 ⇒ 写前再问策略 ⇒ 原子写 ⇒ 登记新 tag。⇒ 新 tag 提示（或 nil）"
  [w {:keys [full shown output verdict]}]
  (when (:worse? verdict) (fail! "tools/syntax-worse" (syntax/rejection verdict shown)))
  (ws/check! w full :write)
  (try
    (ws/write-bytes! (:files w) full output)
    (catch java.io.IOException e
      (fail! "tools/text-file-write-failed" (format "写 %s 失败：%s。文件没有改动。" shown (.getMessage e)))))
  (when-let [reads (:reads w)]
    (let [tag (text/content-tag output)]
      (fresh/note! reads (ws/real full) tag)
      (fresh/new-tag-note tag))))

(defn write-file*
  [w args]
  (let [{:keys [shown before after] :as plan} (plan-write w args)
        tag-note (commit-plan! w plan)
        done (format (if before "已覆盖 %s（%d 个字符）。" "已新建 %s（%d 个字符）。") shown (count after))]
    (cond-> done tag-note (str " " tag-note))))

(defn preview-write
  [w args]
  (let [{:keys [full before after verdict]} (plan-write w args)]
    {:title (str (if before "覆盖 " "新建 ") full)
     :diff (str (text/unified-diff (or before "") after 3)
                (when verdict (str (syntax/preview-note verdict) "\n")))}))

(deftool write_file
  "把内容整篇写进一个文件（覆盖）。中间不存在的目录会自动创建。覆盖已经存在的文件之前先用 read_file 看一眼 —— 没读过的文件写不进去；新建文件不用先读。改一两处不要用它，用 edit_file：这个工具只用来新建文件或整篇重写。覆盖时文件原来的换行、编码与 BOM 原样保留。"
  [[path :string "要写的文件路径（相对路径按工作目录），要带上文件名"]
   [content :string "完整的文件内容"]]
  {:sensitive true :serial true :tags [:files :write]}
  (write-file* (ws/current) {:path path :content content}))

;;; ============================================================
;;; delete_path
;;; ============================================================

(defn- escaping-link
  "被拒的路径是不是「链接本身逃出了根」而链接所在目录可写：策略理由是 workspace/symlink-escape
   （其余理由一律照旧拒）⇒ 所在目录过写策略 ⇒ 链接名不是受保护名 ⇒ 现状确实是符号链接。"
  [w ^Path full denial]
  (when (= "workspace/symlink-escape" (:code denial))
    (let [parent (.getParent full)
          protected (set (or (:protected-names w) ws/default-protected-names))]
      (when (and parent
                 (nil? (ws/deny w parent :write))
                 (not (and (some #(= parent %) (concat (:roots w) (:read-only-roots w)))
                           (contains? protected (str (.getFileName full)))))
                 (Files/isSymbolicLink full))
        (ws/entry (:files w) full)))))

(defn- has-children? [^Path dir]
  (with-open [ds (Files/newDirectoryStream dir)]
    (boolean (seq (iterator-seq (.iterator ds))))))

(defn plan-delete
  "路径策略（逃出根的链接按所在目录判）⇒ 根拒删 ⇒ 现状 ⇒ 非空目录要 recursive。无副作用。"
  [w {:keys [path recursive]}]
  (let [full (ws/resolve-path w path "path")
        denial (ws/deny w full :write)
        link (when denial (escaping-link w full denial))]
    (when (and denial (not link)) (fail! (:code denial) (:message denial) {:path (str full)}))
    (when (ws/root-like? w full)
      (fail! "tools/delete-root-refused"
             (format "没有删 %s：它是工作区根目录（或它的上级 / 文件系统根）。只删里面具体的文件或子目录。" full)))
    (let [e (or link (ws/entry (:files w) full))]
      (if (and (= :dir (:kind e)) (has-children? full))
        (do (when-not recursive
              (fail! "tools/delete-not-empty"
                     (format "%s 是非空目录，没有删。要连同里面的内容整棵删掉就再调一次并加 recursive=true。" full)))
            {:full full :entry e :recursive? true})
        {:full full :entry e :recursive? false}))))

(defn delete-path*
  [w args]
  (let [{:keys [^Path full entry recursive?]} (plan-delete w args)]
    (try
      (case (:kind entry)
        nil (format "%s 本来就不存在，无需删除。" full)
        :link (do (ws/delete! (:files w) full false)
                  (fresh/forget! (:reads w) full)
                  (format "只删了符号链接本身 %s（它指向的 %s 未动）" full (:link-target entry)))
        :dir (do (ws/delete! (:files w) full recursive?)
                 (format "已删除目录 %s%s" full (if recursive? "（连同里面的全部内容）" "")))
        (do (ws/delete! (:files w) full false)
            (fresh/forget! (:reads w) (ws/real full))
            (format "已删除 %s" full)))
      (catch java.io.IOException e
        (fail! "tools/delete-failed" (format "删除 %s 失败：%s" full (.getMessage e)))))))

(defn preview-delete
  [w args]
  (let [{:keys [^Path full entry recursive?]} (plan-delete w args)]
    (case (:kind entry)
      nil {:title (str "删除 " full) :diff (str full " 不存在，什么都不会发生")}
      :link {:title (str "删除符号链接 " full) :diff (str "只删链接本身，不动它指向的 " (:link-target entry))}
      :dir {:title (str "删除目录 " full) :diff (if recursive? "整棵目录连同其中全部内容" "空目录")}
      (let [bs (ws/read-all-bytes (:files w) full)
            s (when-not (some zero? bs) (text/utf8-strict bs))]
        {:title (str "删除文件 " full)
         :diff (if s (text/unified-diff s "" 3) (format "二进制文件（%d 字节）" (alength ^bytes bs)))}))))

(deftool delete_path
  "删除一个文件、目录或符号链接。删东西优先用这件工具，不要用 exec 跑 rm / rm -rf。路径相对于工作目录；非空目录要 recursive=true 才会整棵删掉，不加会报错；不存在的路径直接算成功。符号链接只删链接本身、不跟过去；工作根目录本身不能删；越出工作区的路径会被拒绝。"
  [[path :string "要删除的文件、目录或符号链接（相对于工作目录，或绝对路径）"]
   [recursive :boolean "非空目录要连同内容整棵删掉时传 true" :default false]]
  {:sensitive true :serial true :tags [:files :write]}
  (delete-path* (ws/current) {:path path :recursive recursive}))
