(ns im.ttalk.agent.tools.walk
  "目录遍历（glob / grep 共用；出处 Keelson `WorkspaceWalker.cs`）。

   - `path-glob`：JDK glob（`*` 不跨目录、`**` 跨任意级、`{a,b}` 任选、`?`、`[...]`），并补上
     「`**/` 也匹配零级目录」的口径（`**/*.clj` 匹配根下的 `a.clj`；JDK 原生不认）。
   - `ignore-rules`：`.gitignore` / `.ignore` 的常见子集——空行与 `#` 跳过、`!` 取反、结尾 `/` 只配目录、
     模式里有 `/`（不算结尾的）⇒ 相对忽略文件所在目录锚定，否则按名字在任意深度匹配；后写的胜出；
     被忽略的目录整棵剪掉（git 同样不能在其下再取反）。
   - `walk`：⛔ 跟随符号链接（rg 缺省同）、总是跳过 `.git`、可选跳过隐藏条目、认忽略规则（根的祖先里的
     忽略文件也读，直到含 `.git` 的那层）、路径策略不许读的不进。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tools.workspace :as ws])
  (:import [java.nio.file FileSystems Files LinkOption Path PathMatcher Paths]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; glob
;;; ============================================================

(defn- zero-dir-variants
  "`**/x` 另加 `x`；`a/**/x` 另加 `a/x`（求闭包：每处 `**/` 都可以是零级）。"
  [^String p]
  (loop [todo [p] seen #{p}]
    (if-let [v (first todo)]
      (let [more (cond-> []
                   (str/starts-with? v "**/") (conj (subs v 3))
                   (str/includes? v "/**/") (conj (str/replace-first v "/**/" "/")))
            fresh (remove seen more)]
        (recur (into (subvec todo 1) fresh) (into seen fresh)))
      seen)))

(defn path-glob
  "编译 glob；返回 (fn [rel-path-string]) ⇒ bool（rel 用 / 分隔）。
   basename-anywhere?：模式里没有 / 时在任意深度按名字匹配（rg --glob 口径，grep 的 include 用）。"
  [^String pattern basename-anywhere?]
  (let [q (str/replace (str/replace pattern "\\" "/") #"^/+" "")
        q (if (and basename-anywhere? (not (str/includes? q "/"))) (str "**/" q) q)
        fs (FileSystems/getDefault)
        matchers (mapv #(.getPathMatcher fs (str "glob:" %)) (zero-dir-variants q))]
    (fn [^String rel]
      (let [p (Paths/get rel (make-array String 0))]
        (boolean (some #(.matches ^PathMatcher % p) matchers))))))

;;; ============================================================
;;; 忽略规则
;;; ============================================================

(def ignore-file-names [".gitignore" ".ignore"])

(defn- trim-trailing-spaces [^String s]
  (loop [end (.length s)]
    (if (and (pos? end) (= (.charAt s (dec end)) \space)
             (not (and (> end 1) (= (.charAt s (- end 2)) \\))))
      (recur (dec end))
      (subs s 0 end))))

(defn parse-ignore
  "一份忽略文件 ⇒ 规则向量。base：忽略文件所在目录（Path）。"
  [^Path base ^String content]
  (into []
        (keep (fn [raw]
                (let [line (str/replace raw #"\r$" "")]
                  (when-not (or (str/blank? line) (str/starts-with? line "#"))
                    (let [line (trim-trailing-spaces line)
                          [negate line] (cond (str/starts-with? line "!") [true (subs line 1)]
                                              (or (str/starts-with? line "\\!") (str/starts-with? line "\\#")) [false (subs line 1)]
                                              :else [false line])
                          dir-only (str/ends-with? line "/")
                          line (str/replace line #"/+$" "")]
                      (when-not (str/blank? line)
                        (try
                          (if (str/includes? line "/")
                            {:base base :negate negate :dir-only dir-only
                             :anchored (path-glob (str/replace line #"^/+" "") false)}
                            {:base base :negate negate :dir-only dir-only
                             :name (.getPathMatcher (FileSystems/getDefault) (str "glob:" line))})
                          (catch Exception _ nil)))))))) ;; 写错的规则跳过（git 也静默忽略）
        (str/split content #"\n")))

(defn ignored?
  "被忽略？rules 按出现顺序，后写的胜出。"
  [rules ^Path full dir?]
  (let [name-p (.getFileName full)]
    (true? (reduce (fn [acc {:keys [^Path base negate dir-only name anchored]}]
                     (if (or (and dir-only (not dir?))
                             (not (and (not= full base) (.startsWith full base))))
                       acc
                       (let [hit (if name
                                   (.matches ^PathMatcher name name-p)
                                   (anchored (str/replace (str (.relativize base full)) "\\" "/")))]
                         (if hit (not negate) acc))))
                   nil rules))))

;;; ============================================================
;;; 遍历
;;; ============================================================

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- readable? [w ^Path p] (nil? (ws/deny w p :read)))

(defn- load-ignores [w ^Path dir]
  (into []
        (mapcat (fn [n]
                  (let [p (.resolve dir ^String n)]
                    (if (and (readable? w p)
                             (Files/isRegularFile p (make-array LinkOption 0))
                             (<= (Files/size p) (* 1024 1024)))
                      (try (parse-ignore dir (slurp (str p) :encoding "UTF-8"))
                           (catch Exception _ []))
                      []))))
        ignore-file-names))

(defn- ancestor-ignores
  "根本身就是仓库根 ⇒ 不读祖先；否则自外向内读到仓库根（含 .git 的那层）或策略不许读的边界。"
  [w ^Path root]
  (if (Files/exists (.resolve root ".git") (make-array LinkOption 0))
    []
    (let [chain (loop [d (.getParent root) acc []]
                  (if (or (nil? d) (not (readable? w d)))
                    acc
                    (let [acc (conj acc d)]
                      (if (Files/exists (.resolve ^Path d ".git") (make-array LinkOption 0))
                        acc
                        (recur (.getParent ^Path d) acc)))))]
      (into [] (mapcat #(load-ignores w %)) (reverse chain)))))

(defn walk
  "遍历 root 下的文件 ⇒ [{:path Path :rel \"a/b.clj\" :size n :mtime ms} ...]（按名字顺序深度优先）。
   opts：:skip-hidden?、:limit（找够就停，nil 不限）、:pred（(fn [rel]) 只收满足的）。"
  [w ^Path root {:keys [skip-hidden? pred]}]
  (let [out (transient [])]
    (loop [stack (list [root (ancestor-ignores w root)])]
      (when-let [[^Path dir rules] (first stack)]
        (let [rules (into rules (load-ignores w dir))
              children (try (with-open [ds (Files/newDirectoryStream dir)]
                              (sort-by #(str (.getFileName ^Path %)) (vec ds)))
                            (catch Exception _ []))
              subdirs (transient [])]
          (doseq [^Path c children
                  :let [n (str (.getFileName c))]
                  :when (and (not= n ".git")
                             (not (and skip-hidden? (str/starts-with? n ".")))
                             (not (Files/isSymbolicLink c)))]
            (let [dir? (Files/isDirectory c ^"[Ljava.nio.file.LinkOption;" no-follow)
                  file? (Files/isRegularFile c ^"[Ljava.nio.file.LinkOption;" no-follow)]
              (when (and (or dir? file?)
                         (not (ignored? rules c dir?))
                         (readable? w c))
                (if dir?
                  (conj! subdirs [c rules])
                  (let [rel (str/replace (str (.relativize root c)) "\\" "/")]
                    (when (or (nil? pred) (pred rel))
                      (conj! out {:path c :rel rel :size (Files/size c)
                                  :mtime (.toMillis (Files/getLastModifiedTime c ^"[Ljava.nio.file.LinkOption;" no-follow))})))))))
          (recur (concat (persistent! subdirs) (rest stack))))))
    (persistent! out)))

(defn newest-first
  "修改时间新的在前，同时刻按路径。"
  [entries]
  (sort (fn [a b]
          (let [c (compare (:mtime b) (:mtime a))]
            (if (zero? c) (compare (str (:path a)) (str (:path b))) c)))
        entries))
