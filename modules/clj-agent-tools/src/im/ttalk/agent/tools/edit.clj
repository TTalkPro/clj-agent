(ns im.ttalk.agent.tools.edit
  "edit_file（T-03；出处 Keelson `EditFileTool.cs`，借鉴 coxswain `edit.ss`）：把文件里一段**一字不差**的
   原文换成另一段。

   为什么是精确原文、不是行号也不是 diff：行号会漂、改错行不报错；模型生成的 diff 常常是坏的、模糊匹配会打歪。
   精确原文要么原样对上，要么什么都不发生。⚠️ 不唯一就拒（默默改第一处，模型无从察觉）；要全改显式传 all。

   0 次时按顺序给三种诊断（⛔ 拿诊断去做模糊匹配）：① old 每行都带 read_file 的 `L数字:` 前缀；
   ② 空白归一化后能对上（附行号）；③ 都不是 ⇒「先 read_file 看实际内容」。
   新鲜度闸在匹配**之前**（建立在过期内容上的编辑可能照样对得上）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.files :as files]
            [im.ttalk.agent.tools.freshness :as fresh]
            [im.ttalk.agent.tools.syntax :as syntax]
            [im.ttalk.agent.tools.text :as text]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.nio.file NoSuchFileException]))

(set! *warn-on-reflection* true)

(def ^:const max-listed-lines 20)

(defn occurrences
  "不重叠地找出全部出现位置（\"aa\" 在 \"aaa\" 里算一次：与「全部替换」的实际次数一致）。"
  [^String hay ^String needle]
  (if (zero? (.length needle))
    []
    (loop [i (.indexOf hay needle) acc []]
      (if (neg? i) acc (recur (.indexOf hay needle (+ i (.length needle))) (conj acc i))))))

(defn line-of
  "下标所在的行号（1 起）。"
  [^String s ^long index]
  (loop [i 0 line 1]
    (if (< i index) (recur (inc i) (if (= (.charAt s i) \newline) (inc line) line)) line)))

(defn- replace-at [^String s at ^long len ^String replacement]
  (let [sb (StringBuilder.)]
    (loop [[i & more] at start 0]
      (if i
        (do (.append sb s (int start) (int i)) (.append sb replacement) (recur more (+ (long i) len)))
        (do (.append sb s (int start) (.length s)) (str sb))))))

(defn line-numbered?
  "每一行（末尾换行之后的空串不算）都以 `L数字:` 开头吗（允许行首空白；至少一行）。"
  [^String s]
  (let [lines (str/split s #"\n" -1)
        lines (if (and (> (count lines) 1) (= "" (peek lines))) (pop lines) lines)]
    (and (seq lines)
         (every? #(re-find #"^[ \t]*L\d+:" (str/replace % #"\r$" "")) lines))))

(defn- squeeze
  "每段连续空白压成一个空格；map[k] 是压缩后第 k 个字符在原文里的下标。"
  [^String s]
  (let [sb (StringBuilder.) m (transient [])]
    (loop [i (num 0)]
      (when (< i (.length s))
        (conj! m i)
        (if (Character/isWhitespace (.charAt s i))
          (do (.append sb \space)
              (recur (loop [j i] (if (and (< j (.length s)) (Character/isWhitespace (.charAt s j))) (recur (inc j)) j))))
          (do (.append sb (.charAt s i)) (recur (inc i))))))
    [(str sb) (persistent! m)]))

(defn diagnose
  "0 次时的诊断（只诊断，⛔ 拿它去匹配）。⇒ [code message]"
  [^String text ^String old shown]
  (if (line-numbered? old)
    ["tools/edit-file-line-numbers"
     (format "在 %s 里没找到那段原文；看起来你把 read_file 输出里的行号前缀（像「L12: 」）也抄进 old 了 —— old 要的是文件里的原文，不含行号。" shown)]
    (let [[sq m] (squeeze text)
          needle (str/trim (first (squeeze old)))
          at (if (= "" needle) -1 (.indexOf ^String sq ^String needle))]
      (if (>= at 0)
        ["tools/edit-file-whitespace"
         (format "在 %s 里没找到那段原文；但第 %d 行有一处只在空白上不同 —— 请照文件里的原样抄（缩进、tab、换行）。"
                 shown (line-of text (m at)))]
        ["tools/edit-file-no-match" (format "在 %s 里没找到那段原文。先用 read_file 看一眼实际内容再改。" shown)]))))

(defn plan-edit
  "路径策略 ⇒ 存在 / 目录 ⇒ 读 ⇒ 解码 ⇒ 新鲜度（在匹配之前）⇒ 匹配 ⇒ 编码 ⇒ 语法闸。无副作用。"
  [w {:keys [path old new all]}]
  (let [old (str old) new (str new)]
    (when (= "" old) (fail! "tools/edit-file-old-empty" "old 不能为空。要插入内容，请把插入点附近的原文一起带上。"))
    (when (= old new) (fail! "tools/edit-file-no-change" "old 与 new 一样，这次编辑没有任何效果。"))
    (let [full (ws/resolve! w path "path" :write)
          shown path
          e (ws/target-entry w full)
          _ (cond
              (nil? e) (fail! "tools/edit-file-not-found" (format "%s 不存在。新建文件用 write_file。" shown))
              (= :dir (:kind e)) (fail! "tools/text-file-is-directory"
                                        (format "%s 是一个目录，不能当文件写。" shown)))
          bs (try (ws/read-all-bytes (:files w) full)
                  (catch NoSuchFileException _ (fail! "tools/edit-file-not-found" (format "%s 不存在。新建文件用 write_file。" shown)))
                  (catch java.io.IOException ex (fail! "tools/text-file-read-failed" (format "读 %s 失败：%s" shown (.getMessage ex)))))
          file (text/decode-editable bs shown)
          _ (fresh/check! (:reads w) (ws/real full) shown (text/content-tag bs))
          t (:text file)
          old (text/normalize-input file old)
          replacement (text/normalize-input file new)
          hits (occurrences t old)]
      (when (empty? hits) (let [[c m] (diagnose t old shown)] (fail! c m)))
      (when (and (> (count hits) 1) (not all))
        (fail! "tools/edit-file-multiple"
               (format "那段原文在 %s 里出现了 %d 次（第 %s 行），不知道该改哪一处。请多带几行上下文让它唯一，或者传 all=true 全部替换。"
                       shown (count hits)
                       (str (str/join ", " (map #(line-of t %) (take max-listed-lines hits)))
                            (when (> (count hits) max-listed-lines) ", …")))))
      (let [at (if all hits [(first hits)])
            updated (replace-at t at (count old) replacement)]
        {:full full :shown shown :before t :after updated
         :output (text/encode-editable file updated shown)
         :count (count at) :first-line (line-of t (first hits))
         :verdict (syntax/judge (syntax/guard-of w) full t updated)}))))

(defn edit-file*
  [w args]
  (let [{:keys [shown first-line] :as plan} (plan-edit w args)
        tag-note (files/commit-plan! w plan)
        done (format "已改 %s：替换 %d 处，首处在第 %d 行。" shown (:count plan) first-line)]
    (cond-> done tag-note (str " " tag-note))))

(defn preview-edit
  [w args]
  (let [{:keys [full before after verdict]} (plan-edit w args)]
    {:title (str "编辑 " full)
     :diff (str (text/unified-diff before after 3)
                (when verdict (str (syntax/preview-note verdict) "\n")))}))

(deftool edit_file
  "把文件里的一段原文替换成另一段。old 必须与文件内容一字不差（含缩进与换行；不要带 read_file 输出里的「L行号: 」前缀），而且在文件里只出现一次 —— 出现多次时多带几行上下文让它唯一，或者传 all=true 全部替换。改一两处用它，不要用 write_file 重写整个文件。改之前先用 read_file 看一眼 —— 没读过的文件改不了。文件原来的换行、编码与 BOM 原样保留。"
  [[path :string "要改的文件路径（相对路径按工作目录）"]
   [old :string "要被替换掉的原文，必须与文件内容完全一致（不含 read_file 的行号前缀）"]
   [new :string "替换成什么；空串表示删掉这段"]
   [all :boolean "把所有出现的地方都替换掉（缺省 false：old 必须只出现一次）" :default false]]
  {:sensitive true :serial true :tags [:files :write]}
  (edit-file* (ws/current) {:path path :old old :new new :all all}))
