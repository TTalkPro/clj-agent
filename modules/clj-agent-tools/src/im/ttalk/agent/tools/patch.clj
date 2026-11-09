(ns im.ttalk.agent.tools.patch
  "apply_patch（T-04）：Codex 补丁文法的解析器 + 内存应用器 + 工具壳。

   翻译自 OpenAI Codex CLI（Apache-2.0，Copyright 2025 OpenAI；见模块根 THIRD-PARTY-NOTICES.md 与
   LICENSE-codex.txt），经 Keelson `Keelson.Text/Patch/*` 与 `ApplyPatchTool.cs` 转写：
   - 解析：codex-rs/apply-patch/src/parser.rs（边界检查、宽松模式剥 heredoc）与 streaming_parser.rs（逐行状态机）@ 4bad6d78e9；
   - 匹配：codex-rs/apply-patch/src/seek_sequence.rs（精确 → 忽略行尾空白 → 忽略两端空白 → Unicode 标点归 ASCII）；
   - 应用：codex-rs/apply-patch/src/{text_file.rs, file_update.rs, lib.rs}（逐行保行尾、compute_replacements、成功摘要）；
   - 工具壳：codex-rs/core/src/tools/handlers/apply_patch.rs（先验证整份补丁、写前逐路径过权限）。

   与上游的差别：引擎不碰 IO——读由调用方给，产出「先全部算好、再由调用方落盘」的 plan：
   **任一文件对不上 ⇒ 一个也不写**。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.freshness :as fresh]
            [im.ttalk.agent.tools.syntax :as syntax]
            [im.ttalk.agent.tools.text :as text]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file NoSuchFileException Path]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 解析
;;; ============================================================

(def begin-patch "*** Begin Patch")
(def end-patch "*** End Patch")
(def add-file "*** Add File: ")
(def delete-file "*** Delete File: ")
(def update-file "*** Update File: ")
(def move-to "*** Move to: ")
(def end-of-file "*** End of File")
(def change-context "@@ ")
(def empty-change-context "@@")
(def environment-id "*** Environment ID:")

(defn- parse-error
  ([reason] (parse-error reason nil))
  ([reason line]
   (throw (ex-info (if line
                     (format "第 %d 行的补丁块无效：%s" line reason)
                     (str "无效补丁：" reason))
                   {::parse-error true :line line :error-class :semantic :code "patch/invalid"}))))

(def ^:private msg-first-line "补丁的第一行必须是 '*** Begin Patch'")
(def ^:private msg-last-line "补丁的最后一行必须是 '*** End Patch'")

(defn- lines
  "Rust str::lines：按 \\n 切，每行去掉一个结尾 \\r，末尾的换行不产生空行。"
  [^String s]
  (let [v (str/split s #"\n" -1)
        v (if (and (seq v) (= "" (peek v))) (pop v) v)]
    (mapv #(if (str/ends-with? % "\r") (subs % 0 (dec (count %))) %) v)))

(defn- check-boundaries [ls]
  (let [first-l (some-> (first ls) str/trim)
        last-l (some-> (peek ls) str/trim)]
    (cond
      (and (= first-l begin-patch) (= last-l end-patch)) ls
      (and first-l (not= first-l begin-patch)) (parse-error msg-first-line)
      :else (parse-error msg-last-line))))

(defn- check-boundaries-lenient
  "宽松：严格失败时，首行 <<EOF / <<'EOF' / <<\"EOF\"、末行以 EOF 结尾且至少 4 行 ⇒ 剥 heredoc 再严格一次。"
  [ls]
  (try
    (check-boundaries ls)
    (catch clojure.lang.ExceptionInfo e
      (if (and (>= (count ls) 4)
               (#{"<<EOF" "<<'EOF'" "<<\"EOF\""} (first ls))
               (str/ends-with? (peek ls) "EOF"))
        (check-boundaries (subvec ls 1 (dec (count ls))))
        (throw e)))))

(defn- empty-chunk? [c] (and (empty? (:old c)) (empty? (:new c))))

(defn- last-hunk [st] (peek (:hunks st)))
(defn- update-last-hunk [st f & args] (update st :hunks #(conj (pop %) (apply f (peek %) args))))
(defn- update-last-chunk [st f & args]
  (update-last-hunk st (fn [h] (update h :chunks #(conj (pop %) (apply f (peek %) args))))))

(defn- ensure-update-not-empty [st line]
  (let [h (last-hunk st)]
    (if (and h (= :update (:type h)))
      (let [cs (:chunks h)]
        (cond
          (and (empty? cs) (= :update (:mode st)))
          (parse-error (format "路径 '%s' 的 Update 块是空的" (:path h)) (:update-line st))
          (and (seq cs) (empty-chunk? (peek cs)))
          (if (= line end-patch)
            (parse-error "Update 块里没有任何行" (:line st))
            (parse-error (format "Update 块里出现了意外的行：'%s'。每一行都应以 ' '（上下文）、'+'（新增）或 '-'（删除）开头" line) (:line st)))
          :else st))
      st)))

(defn- handle-headers
  "块头与 End Patch；处理了 ⇒ 新状态，否则 nil。"
  [st ^String trimmed]
  (cond
    (and (= :started (:mode st)) (str/starts-with? trimmed environment-id))
    (let [id (str/trim (subs trimmed (count environment-id)))]
      (cond (:env-id st) (parse-error "apply_patch 的 environment_id 不能给两次")
            (= "" id) (parse-error "apply_patch 的 environment_id 不能为空")
            :else (assoc st :env-id id)))

    (= trimmed end-patch)
    (-> (ensure-update-not-empty st trimmed) (assoc :mode :ended))

    (str/starts-with? trimmed add-file)
    (-> (ensure-update-not-empty st trimmed)
        (update :hunks conj {:type :add :path (subs trimmed (count add-file)) :contents ""})
        (assoc :mode :add))

    (str/starts-with? trimmed delete-file)
    (-> (ensure-update-not-empty st trimmed)
        (update :hunks conj {:type :delete :path (subs trimmed (count delete-file))})
        (assoc :mode :delete))

    (str/starts-with? trimmed update-file)
    (-> (ensure-update-not-empty st trimmed)
        (update :hunks conj {:type :update :path (subs trimmed (count update-file)) :move-path nil :chunks []})
        (assoc :mode :update :update-line (:line st)))

    :else nil))

(defn- new-chunk
  ([] (new-chunk nil))
  ([ctx] {:change-context ctx :old [] :new [] :ctx-idx [] :eof? false}))

(defn- current-chunk [st]
  (if (empty? (:chunks (last-hunk st)))
    (update-last-hunk st update :chunks conj (new-chunk))
    st))

(defn- push-context [c line]
  (-> c
      (update :ctx-idx conj [(count (:old c)) (count (:new c))])
      (update :old conj line)
      (update :new conj line)))

(defn- process-update-line [st ^String line]
  (let [update-line (str/trimr line)]
    (or (handle-headers st update-line)
        (let [h (last-hunk st)
              cs (:chunks h)
              last-c (peek cs)
              ctx-marker? (or (= update-line empty-change-context) (str/starts-with? update-line change-context))]
          (when (and last-c (:eof? last-c) (not= "" update-line) (not ctx-marker?))
            (parse-error (format "Update 块应以 @@ 上下文标记开始，实为：'%s'" line) (:line st)))
          (cond
            (and last-c (:eof? last-c) (= "" update-line)) st

            (and (empty? cs) (nil? (:move-path h)) (str/starts-with? update-line move-to))
            (update-last-hunk st assoc :move-path (subs update-line (count move-to)))

            (and ctx-marker? last-c (empty-chunk? last-c))
            (parse-error (format "Update 块里出现了意外的行：'%s'。每一行都应以 ' '（上下文）、'+'（新增）或 '-'（删除）开头" line) (:line st))

            (= update-line empty-change-context)
            (update-last-hunk st update :chunks conj (new-chunk))

            (str/starts-with? update-line change-context)
            (update-last-hunk st update :chunks conj (new-chunk (subs update-line (count change-context))))

            (= update-line end-of-file)
            (cond (and last-c (empty-chunk? last-c)) (parse-error "Update 块里没有任何行" (:line st))
                  last-c (update-last-chunk st assoc :eof? true)
                  :else st)

            (= "" line) (-> (current-chunk st) (update-last-chunk push-context ""))

            :else
            (case (.charAt line 0)
              \space (-> (current-chunk st) (update-last-chunk push-context (subs line 1)))
              \+ (-> (current-chunk st) (update-last-chunk update :new conj (subs line 1)))
              \- (-> (current-chunk st) (update-last-chunk update :old conj (subs line 1)))
              (if (and last-c (not (empty-chunk? last-c)))
                (parse-error (format "Update 块应以 @@ 上下文标记开始，实为：'%s'" line) (:line st))
                (parse-error (format "Update 块里出现了意外的行：'%s'。每一行都应以 ' '（上下文）、'+'（新增）或 '-'（删除）开头" line) (:line st)))))))))

(defn- bad-header [trimmed line-no]
  (parse-error (format "'%s' 不是合法的块头。合法的块头：'*** Add File: {path}'、'*** Delete File: {path}'、'*** Update File: {path}'" trimmed) line-no))

(defn- process-line [st ^String line]
  (let [st (update st :line inc)
        trimmed (str/trim line)]
    (case (:mode st)
      :not-started (if (= trimmed begin-patch) (assoc st :mode :started) (parse-error msg-first-line))
      (:started :delete) (or (handle-headers st trimmed) (bad-header trimmed (:line st)))
      :add (or (handle-headers st trimmed)
               (if (str/starts-with? line "+")
                 (update-last-hunk st update :contents str (subs line 1) "\n")
                 (bad-header trimmed (:line st))))
      :update (process-update-line st line)
      :ended (if (= "" trimmed) st (parse-error msg-last-line)))))

(defn parse-patch
  "解析补丁文本 ⇒ {:hunks [...] :patch 剥过 heredoc 的原文 :env-id}。错 ⇒ ex-info（ex-data ::parse-error）。
   hunk：{:type :add :path :contents} | {:type :delete :path} | {:type :update :path :move-path :chunks [chunk]}；
   chunk：{:change-context :old [行] :new [行] :ctx-idx [[old-i new-i]] :eof?}。"
  ([patch] (parse-patch patch true))
  ([patch lenient?]
   (let [ls (lines (str/trim (str patch)))
         ls ((if lenient? check-boundaries-lenient check-boundaries) ls)
         st (reduce process-line {:mode :not-started :hunks [] :line 0} ls)]
     (when (not= :ended (:mode st)) (parse-error msg-last-line))
     {:hunks (:hunks st) :patch (str/join "\n" ls) :env-id (:env-id st)})))

;;; ============================================================
;;; 匹配（seek_sequence）
;;; ============================================================

(def ^:private punct-map
  "Codex 列的那几类 Unicode 标点 ⇒ ASCII（各种横线、弯引号、不换行空格等）。"
  (merge (zipmap [0x2010 0x2011 0x2012 0x2013 0x2014 0x2015 0x2212] (repeat \-))
         (zipmap [0x2018 0x2019 0x201A 0x201B] (repeat \'))
         (zipmap [0x201C 0x201D 0x201E 0x201F] (repeat \"))
         (zipmap [0x00A0 0x2002 0x2003 0x2004 0x2005 0x2006 0x2007 0x2008 0x2009 0x200A 0x202F 0x205F 0x3000]
                 (repeat \space))))

(defn- normalise ^String [^String s]
  (let [t (str/trim s) sb (StringBuilder.)]
    (doseq [c t]
      (.append sb (get punct-map (int c) c)))
    (str sb)))

(defn seek
  "在 lines 里从 start 起找 pattern 这一串行；四级放宽。eof? ⇒ 先从文件末尾那个位置找。找不到 ⇒ nil。"
  [lines pattern start eof?]
  (let [lines (vec lines) pattern (vec pattern) n (count lines) m (count pattern)]
    (cond
      (zero? m) start
      (> m n) nil
      :else
      (let [search-start (if eof? (max (- n m) start) start)
            last-i (- n m)
            try-eq (fn [eq]
                     (some (fn [i] (when (every? (fn [k] (eq (lines (+ i k)) (pattern k))) (range m)) i))
                           (range search-start (inc last-i))))]
        (or (try-eq =)
            (try-eq #(= (str/trimr %1) (str/trimr %2)))
            (try-eq #(= (str/trim %1) (str/trim %2)))
            (try-eq #(= (normalise %1) (normalise %2))))))))

;;; ============================================================
;;; 应用（内存）
;;; ============================================================

(defn- apply-error [msg path]
  (throw (ex-info msg {::apply-error true :path path :error-class :semantic :code "patch/apply"})))

(defn- source-lines
  "逐行保行尾：\\r\\n / \\r / \\n 都算行结束；第一种出现的行尾是首选（没有 ⇒ \\n）。
   ⇒ {:lines [[text ending-or-nil] ...] :preferred}"
  [^String s]
  (let [n (.length s)]
    (loop [cursor 0 start 0 acc [] preferred nil]
      (if (>= cursor n)
        {:lines (if (< start n) (conj acc [(subs s start) nil]) acc) :preferred (or preferred "\n")}
        (let [c (.charAt s cursor)
              ending (cond (and (= c \return) (< (inc cursor) n) (= (.charAt s (inc cursor)) \newline)) "\r\n"
                           (= c \return) "\r"
                           (= c \newline) "\n")]
          (if ending
            (recur (+ cursor (count ending)) (+ cursor (count ending))
                   (conj acc [(subs s start cursor) ending]) (or preferred ending))
            (recur (inc cursor) start acc preferred)))))))

(defn- compute-replacements [orig-lines path chunks]
  (loop [[chunk & more] chunks line-index (num 0) acc []]
    (if-not chunk
      (vec (sort-by first acc))                      ;; sort-by 稳定
      (let [line-index (if-let [ctx (:change-context chunk)]
                         (if-let [idx (seek orig-lines [ctx] line-index false)]
                           (inc idx)
                           (apply-error (format "在 %s 里找不到上下文 '%s'" path ctx) path))
                         line-index)]
        (if (empty? (:old chunk))
          (recur more line-index (conj acc [(count orig-lines) 0 (:new chunk)]))
          (let [found (seek orig-lines (:old chunk) line-index (:eof? chunk))
                [pattern new-slice found]
                (if (and (nil? found) (= "" (peek (:old chunk))))
                  (let [p (pop (:old chunk))
                        ns (if (= "" (peek (:new chunk))) (pop (:new chunk)) (:new chunk))]
                    [p ns (seek orig-lines p line-index (:eof? chunk))])
                  [(:old chunk) (:new chunk) found])]
            (when (nil? found)
              (apply-error (format "在 %s 里找不到预期的行：\n%s" path (str/join "\n" (:old chunk))) path))
            ;; 上下文行两边都有：原文那几行留在原处（内容与行尾都不动）
            (let [[acc os ns]
                  (reduce (fn [[acc os ns :as st] [oc nc]]
                            (if (or (>= oc (count pattern)) (>= nc (count new-slice)))
                              (reduced st)
                              [(if (or (not= os oc) (not= ns nc))
                                 (conj acc [(+ found os) (- oc os) (subvec new-slice ns nc)])
                                 acc)
                               (inc oc) (inc nc)]))
                          [acc 0 0] (:ctx-idx chunk))
                  acc (if (or (not= os (count pattern)) (not= ns (count new-slice)))
                        (conj acc [(+ found os) (- (count pattern) os) (subvec new-slice ns)])
                        acc)]
              (recur more (+ found (count pattern)) acc))))))))

(defn derive-new-contents
  "按块改 original：保留每行原有行尾，插入的行用首选行尾，结果每一行都有行尾。"
  [^String original path chunks]
  (let [{:keys [lines preferred]} (source-lines original)
        reps (compute-replacements (mapv first lines) path chunks)
        result (loop [[[start old-len new-lines] & more] reps consumed (num 0) acc []]
                 (if-not start
                   (into acc (subvec lines (min consumed (count lines))))
                   (let [keep (subvec lines (min consumed (count lines)) (min (max consumed start) (count lines)))
                         consumed (min (count lines) (+ (max consumed start) old-len))]
                     (recur more consumed (into (into acc keep) (map (fn [t] [t preferred]) new-lines))))))]
    (apply str (mapcat (fn [[t e]] [t (or e preferred)]) result))))

(defn plan-patch
  "推演整份补丁。resolve：补丁里的路径 ⇒ 完整路径（Path）；read：完整路径 ⇒
   {:kind :missing|:file|:dir|:unreadable :text :reason}。任一块失败 ⇒ 抛，一步都不产出。
   ⇒ {:ops [[:write path text] [:delete path] ...] :changes [...] :added :modified :deleted}"
  [{:keys [hunks]} resolve read]
  (when (empty? hunks) (apply-error "没有改动任何文件。" nil))
  (loop [[h & more] hunks overlay {} ops [] changes [] added [] modified [] deleted []]
    (if-not h
      {:ops ops :changes changes :added added :modified modified :deleted deleted}
      (let [get-in* (fn [p] (get overlay (str p) (read p)))
            path (resolve (:path h))]
        (case (:type h)
          :add
          (let [before (get-in* path)]
            (recur more (assoc overlay (str path) {:kind :file :text (:contents h)})
                   (conj ops [:write path (:contents h)])
                   (conj changes {:kind :add :path path :old (when (= :file (:kind before)) (:text before)) :new (:contents h)})
                   (conj added (:path h)) modified deleted))

          :delete
          (let [before (get-in* path)]
            (when (#{:missing :dir} (:kind before)) (apply-error (format "删除文件 %s 失败" path) path))
            (recur more (assoc overlay (str path) {:kind :missing})
                   (conj ops [:delete path])
                   (conj changes {:kind :delete :path path :old (:text before) :new nil})
                   added modified (conj deleted (:path h))))

          :update
          (let [before (get-in* path)
                t (case (:kind before)
                    :file (or (:text before) "")
                    :dir (apply-error (format "读要更新的文件 %s 失败：这是目录" path) path)
                    :unreadable (apply-error (format "读要更新的文件 %s 失败：%s" path (:reason before)) path)
                    (apply-error (format "读要更新的文件 %s 失败：文件或目录不存在" path) path))
                new-text (derive-new-contents t path (:chunks h))
                dest (some-> (:move-path h) resolve)
                move? (and dest (not= (str dest) (str path)))]
            (recur more
                   (if move?
                     (assoc overlay (str dest) {:kind :file :text new-text} (str path) {:kind :missing})
                     (assoc overlay (str path) {:kind :file :text new-text}))
                   (if move? (conj ops [:write dest new-text] [:delete path]) (conj ops [:write path new-text]))
                   (conj changes {:kind :update :path path :move-path (when move? dest) :old t :new new-text})
                   added (conj modified (or (:move-path h) (:path h))) deleted)))))))

(defn summary
  "Codex print_summary 的口径：标题 + 每行 A / M / D + 补丁里写的路径。"
  [{:keys [added modified deleted]}]
  (str "成功。改动了这些文件：\n"
       (str/join (concat (map #(str "A " % "\n") added)
                         (map #(str "M " % "\n") modified)
                         (map #(str "D " % "\n") deleted)))))

;;; ============================================================
;;; 工具
;;; ============================================================

(defn- verification-failed [msg code]
  (fail! code (str "apply_patch 验证失败：" msg)))

(defn- read-current
  "读现状（只读一次）⇒ [{:kind ..} tag-or-nil]。"
  [w ^Path p]
  (let [e (ws/entry (:files w) p)]
    (cond
      (nil? e) [{:kind :missing} nil]
      (= :dir (:kind e)) [{:kind :dir} nil]
      :else
      (try
        (let [bs (ws/read-all-bytes (:files w) p)
              tag (text/content-tag bs)]
          (if-let [s (text/utf8-strict bs)]
            [{:kind :file :text s} tag]
            [{:kind :unreadable :reason "内容不是合法的 UTF-8"} tag]))
        (catch NoSuchFileException _ [{:kind :missing} nil])
        (catch java.io.IOException ex
          ;; 指向目录的链接打不开 ⇒ 当目录（删 / 改都会被拒）
          (if (= :link (:kind e))
            [{:kind :dir} nil]
            [{:kind :unreadable :reason (.getMessage ex)} nil]))))))

(defn plan-apply
  "解析 ⇒ 路径策略（全部过了才往下）⇒ 同一路径多处操作拒 ⇒ 读现状 ⇒ 新鲜度 ⇒ 内存推演 ⇒ 语法闸。无副作用。"
  [w {:keys [input]}]
  (let [parsed (try (parse-patch input)
                    (catch clojure.lang.ExceptionInfo e
                      (if (::parse-error (ex-data e)) (verification-failed (ex-message e) "patch/invalid") (throw e))))
        resolve (fn [p] (ws/resolve-path w p "path"))
        targets (for [h (:hunks parsed) raw (cond-> [(:path h)] (:move-path h) (conj (:move-path h)))]
                  [raw (ws/resolve! w raw "path" :write)])
        shown (reduce (fn [m [raw full]] (if (contains? m (str full)) m (assoc m (str full) raw))) {} targets)
        _ (reduce (fn [seen h]
                    (let [p (str (resolve (:path h)))]
                      (when (contains? seen p) (verification-failed (format "多个操作指向 %s" p) "patch/duplicate-target"))
                      (conj seen p)))
                  #{} (:hunks parsed))
        snapshot (into {} (for [[_ full] targets :let [k (str full)]]
                            [k (read-current w full)]))
        reads (:reads w)]
    ;; 新鲜度：已存在的目标都要读过、读完没变（新建不要求）。在推演之前判
    (when reads
      (doseq [[k raw] shown
              :let [[{:keys [kind]} tag] (snapshot k)]
              :when (and tag (not (#{:missing :dir} kind)))]
        (try (fresh/check! reads (ws/real (ws/->path k)) raw tag)
             (catch clojure.lang.ExceptionInfo e
               (verification-failed (ex-message e) (:code (ex-data e)))))))
    (let [plan (try (plan-patch parsed resolve (fn [p] (first (get snapshot (str p) [{:kind :missing}]))))
                    (catch clojure.lang.ExceptionInfo e
                      (if (::apply-error (ex-data e)) (verification-failed (ex-message e) "patch/apply") (throw e))))
          guard (syntax/guard-of w)
          verdicts (mapv (fn [{:keys [kind path move-path old new]}]
                           (when (and (not= kind :delete) new)
                             (syntax/judge guard (or move-path path) old new)))
                         (:changes plan))]
      (assoc plan :verdicts verdicts))))

(defn apply-patch*
  [w args]
  (let [{:keys [ops changes verdicts] :as plan} (plan-apply w args)
        worse (keep-indexed (fn [i v] (when (:worse? v)
                                        (let [c (changes i)] (syntax/patch-file-lines v (str (or (:move-path c) (:path c)))))))
                            verdicts)
        reads (:reads w)]
    ;; 任一文件改得更糟 ⇒ 整份补丁一个也不写（与补丁的原子性一致）
    (when (seq worse) (verification-failed (syntax/patch-rejection (str/join worse)) "tools/syntax-worse"))
    (loop [[[op ^Path p t] & more] ops done []]
      (if-not op
        (summary plan)
        (do
          ;; 每一步前再问一次策略（验证之后路径被换成链接 ⇒ 拒）
          (when-let [d (ws/deny w p :write)]
            (fail! (:code d) (if (empty? done)
                               (:message d)
                               (str (:message d) "（已经写了：" (str/join ", " done) "）"))))
          (try
            (if (= op :write)
              (let [bs (.getBytes ^String t StandardCharsets/UTF_8)]
                (ws/write-bytes! (:files w) p bs)
                (fresh/note! reads (ws/real p) (text/content-tag bs)))
              (do (ws/delete! (:files w) p false)
                  (fresh/forget! reads (ws/real p))))
            (catch java.io.IOException e
              (fail! "patch/write-failed"
                     (format "写 %s 失败：%s（已经写了：%s）" p (.getMessage e)
                             (if (empty? done) "什么都没写" (str/join ", " done))))))
          (recur more (conj done (str p))))))))

(defn preview-apply
  [w args]
  (let [{:keys [changes verdicts]} (plan-apply w args)]
    {:title (format "应用补丁（%d 个文件）" (count changes))
     :diff (str/join
            (map-indexed
             (fn [i {:keys [kind path move-path old new]}]
               (let [head (case kind
                            :add (if old (str "覆盖 " path) (str "新建 " path))
                            :delete (str "删除 " path)
                            (if move-path (str "改名 " path " → " move-path) (str "修改 " path)))
                     d (text/unified-diff (or old "") (or new "") 3)
                     v (verdicts i)]
                 (str head "\n" (if (= "" d) "（内容没有变化）\n" d)
                      (when v (str (syntax/preview-note v) "\n")))))
             changes))}))

(def ^:private description
  "用 apply_patch 改文件。补丁语言是一种精简的、以文件为单位的 diff 格式，便于解析、应用起来安全：

*** Begin Patch
[ 一个或多个文件段 ]
*** End Patch

每个文件操作以一个头开始：
*** Add File: <path> —— 新建文件，后面每一行都是 + 行（初始内容）。
*** Delete File: <path> —— 删掉已有文件，后面什么也不跟。
*** Update File: <path> —— 原地修改已有文件（要改名就紧跟一行 *** Move to: <新路径>）。
Update 之后是一个或多个块，每块以 @@ 开始（后面可跟类名 / 函数名帮助定位）；块里的行以 ' '（上下文）、'-'（删除）、'+'（新增）开头。
缺省在每处改动上下各给 3 行原文作上下文；不足以唯一定位时用 @@ 指明所属的类或函数，可以多个 @@ 逐级定位。

文法：
Patch := Begin { FileOp } End
FileOp := AddFile | DeleteFile | UpdateFile
AddFile := \"*** Add File: \" path NEWLINE { \"+\" line NEWLINE }
DeleteFile := \"*** Delete File: \" path NEWLINE
UpdateFile := \"*** Update File: \" path NEWLINE [ MoveTo ] { Hunk }
Hunk := \"@@\" [ header ] NEWLINE { HunkLine } [ \"*** End of File\" NEWLINE ]
HunkLine := (\" \" | \"-\" | \"+\") text NEWLINE

先在内存里推演整份补丁：任一文件对不上，一个文件也不会写。改已有文件之前要先 read_file 读过。文件路径只写相对路径。")

(deftool apply_patch
  "（说明见 im.ttalk.agent.tools.patch/description，绑定时替换）"
  [[input :string "整份补丁文本（*** Begin Patch … *** End Patch）"]]
  {:sensitive true :serial true :tags [:files :write]}
  (apply-patch* (ws/current) {:input input}))

(alter-meta! #'apply_patch assoc-in [:tool/schema :description] description)
