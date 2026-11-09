(ns im.ttalk.agent.tools.skill
  "skill 工具（T-06；出处 Keelson `SkillTool.cs`，借鉴 coxswain `skill.ss`）。

   解决的是「几百字一条、只在特定活上用得着的操作手册全塞进系统提示是每轮白烧，全不给则每次现摸」：
   工具说明里只列目录（名字 + 一句描述），模型对上了再调 skill 把整份装进对话。

   目录由宿主给（`{:skills {:dirs [\"/repo/.skills\" \"~/skills\"]}}`，⛔ 库内写死 .claude/skills 一类位置），
   按给的顺序同名先到的赢；每个目录下一层子目录各放一份 `SKILL.md`（frontmatter `name` / `description`；
   没有 name 用目录名）。说明在 `workspace-tools` 时扫描一次（说明稳定 ⇒ 不打碎提示缓存前缀）；调用时重扫。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.io File]
           [java.nio.file Files LinkOption Path]))

(set! *warn-on-reflection* true)

(def defaults {:max-body-chars 64000 :max-side-files 10 :max-listed-description-chars 200})

(defn opts-of [w] (merge defaults (:skills w)))

(defn valid-name?
  "1–64 个字母 / 数字 / `- _ .`（免得带空格的目录名在说明里被读成两个词）。"
  [s]
  (boolean (and (string? s) (re-matches #"[\p{L}\p{N}._-]{1,64}" s))))

(defn- unquote* [^String s]
  (if (and (>= (count s) 2)
           (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
               (and (str/starts-with? s "'") (str/ends-with? s "'"))))
    (subs s 1 (dec (count s)))
    s))

(defn parse-frontmatter
  "只认最常见的写法（⛔ 不是 YAML 解析器）：首行 --- 到下一个 ---；key: value，值去两头空白与成对引号；
   以空白开头的行接到上一个值后面；> / | 块标记当空串；没闭合 ⇒ 当没有 frontmatter。⇒ [front-map body]"
  [^String text]
  (let [ls (str/split (str/replace text "\r\n" "\n") #"\n" -1)]
    (if-not (= "---" (str/trim (first ls)))
      [{} text]
      (loop [i 1 front []]
        (if (>= i (count ls))
          [{} text]
          (let [line (ls i)]
            (cond
              (= "---" (str/trim line))
              [(reduce (fn [m [k v]] (if (contains? m k) m (assoc m k (unquote* v)))) {} front)
               (str/join "\n" (subvec ls (inc i)))]

              (and (seq front) (re-find #"^[ \t]" line))
              (let [more (str/trim line) [k v] (peek front)]
                (recur (inc i) (if (= "" more) front (conj (pop front) [k (if (= "" v) more (str v " " more))]))))

              :else
              (let [c (.indexOf ^String line ":")]
                (if (pos? c)
                  (let [k (str/trim (subs line 0 c)) v (str/trim (subs line (inc c)))]
                    (recur (inc i) (if (= "" k) front (conj front [k (if (#{">" "|" ">-" "|-"} v) "" v)]))))
                  (recur (inc i) front))))))))))

(defn- read-text [^File f]
  (try
    (when (.isFile f)
      (let [bs (Files/readAllBytes (.toPath f))]
        (when-not (some zero? bs)
          (let [s (String. bs java.nio.charset.StandardCharsets/UTF_8)]
            (if (str/starts-with? s (str (char 0xFEFF))) (subs s 1) s)))))
    (catch Exception _ nil)))

(defn scan
  "扫描技能目录：同名先到的赢；找不到的目录、读不动 / 名字不合规的 SKILL.md 跳过。
   ⇒ [{:name :description :path :dir}]"
  [dirs]
  (loop [[d & more] dirs seen #{} acc []]
    (if-not d
      acc
      (let [subs* (try (->> (.listFiles (File. (str d))) (filter #(.isDirectory ^File %)) (sort-by #(.getName ^File %)))
                       (catch Exception _ nil))
            [seen acc] (reduce (fn [[seen acc] ^File sub]
                                 (let [md (File. sub "SKILL.md")]
                                   (if-let [t (read-text md)]
                                     (let [[front _] (parse-frontmatter t)
                                           n (let [x (get front "name")] (if (str/blank? x) (.getName sub) x))]
                                       (if (or (not (valid-name? n)) (seen n))
                                         [seen acc]
                                         [(conj seen n) (conj acc {:name n :description (not-empty (get front "description"))
                                                                   :path (str md) :dir (str sub)})]))
                                     [seen acc])))
                               [seen acc] subs*)]
        (recur more seen acc)))))

(defn description
  "工具说明：列出全部技能（只取描述第一行，截到上限）。"
  [w]
  (let [{:keys [dirs max-listed-description-chars]} (opts-of w)
        skills (scan dirs)]
    (if (empty? skills)
      "装载一份 skill（宿主准备好的操作手册）。当前没有可用的 skill。"
      (str "装载一份 skill（宿主准备好的操作手册）。手上的活对上下面某一条时先装它再动手：装进来的是整份步骤、基目录与旁边的文件表。name 要与列表里的一字不差。可用的 skill："
           (str/join (for [{:keys [name description]} skills
                           :let [first-line (str/trim (first (str/split-lines (or description "（没有描述）"))))
                                 one (if (> (count first-line) max-listed-description-chars)
                                       (str (subs first-line 0 max-listed-description-chars) "…")
                                       first-line)]]
                       (str "\n- " name ": " one)))))))

(defn- side-files
  "技能目录下除顶层 SKILL.md 外的文件，深度 ≤ 3，至多 max 个；⛔ 跟符号链接。"
  [dir max]
  (let [acc (transient [])]
    (letfn [(walk [^File d depth]
              (when (and (< (count acc) max) (< depth 3))
                (doseq [^File e (sort-by #(.getName ^File %) (or (seq (.listFiles d)) []))
                        :while (< (count acc) max)
                        :when (not (Files/isSymbolicLink (.toPath e)))]
                  (if (.isDirectory e)
                    (walk e (inc depth))
                    (when-not (and (zero? depth) (= "SKILL.md" (.getName e)))
                      (conj! acc (str e)))))))]
      (walk (File. (str dir)) 0))
    (persistent! acc)))

(defn skill*
  [w {:keys [name]}]
  (let [{:keys [dirs max-body-chars max-side-files]} (opts-of w)
        skills (scan dirs)
        s (or (first (filter #(= name (:name %)) skills))
              (fail! "tools/skill-unknown"
                     (if (empty? skills)
                       (format "没有叫 %s 的 skill：当前一份 skill 都没有。" name)
                       (format "没有叫 %s 的 skill。有的是：%s" name (str/join ", " (map :name skills))))))
        t (or (read-text (File. ^String (:path s)))
              (fail! "tools/skill-read-failed" (format "skill %s 读不出来（%s）。" (:name s) (:path s))))
        body (str/trim (second (parse-frontmatter t)))
        body (if (> (count body) max-body-chars)
               (str (subs body 0 max-body-chars) "\n\n"
                    (format "[SKILL.md 共 %d 个字符，这里是前 %d 个；其余在 %s]" (count body) max-body-chars (:path s)))
               body)
        files (side-files (:dir s) max-side-files)]
    (str "已装载 skill " (:name s) "\n\n" body "\n\n---\n"
         "基目录：" (:dir s) "（正文里的相对路径相对于这个目录）"
         (when (seq files)
           (str "\n旁边的文件（最多列 " max-side-files " 个）：" (str/join (map #(str "\n- " %) files)))))))

(deftool skill
  "装载一份 skill（宿主准备好的操作手册）。（可用清单在绑定工作区时生成）"
  [[name :string "skill 的名字，与工具说明里列的一字不差"]]
  {:tags [:read-only :skill]}
  (skill* (ws/current) {:name name}))
