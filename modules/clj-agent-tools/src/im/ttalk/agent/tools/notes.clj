(ns im.ttalk.agent.tools.notes
  "笔记三件（T-06；出处 Keelson `NotesTools.cs`，借鉴 coxswain `memory.ss`）：notes_list / notes_read / notes_write。

   机制（存哪、按什么分）归宿主的 `INotesStore` 实现；这一层只把它包成模型能调的三件事，并把「什么时候该记」
   写进说明——只说「写一篇笔记」的说明会得到两种极端：一条都不记，或把每轮对话都记成日志。

   自带两个实现：`dir-store`（一个目录下一篇一个文件）、`memory-store`（进程内，测试 / 临时用）。
   名字校验在工具层统一做（⛔ 拿名字拼路径逃出去），存储实现可以信任它。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path]))

(set! *warn-on-reflection* true)

(defprotocol INotesStore
  (list-notes [store] "全部笔记名。")
  (read-note [store name] "正文；没有 ⇒ nil。")
  (write-note! [store name content] "整篇写入（覆盖同名）。"))

(defn valid-name?
  "非空、≤ 128 字符、两头无空白、不含路径分隔符与控制字符、不是 . / ..。"
  [s]
  (boolean (and (string? s) (<= 1 (count s) 128) (= s (str/trim s)) (not (#{"." ".."} s))
                (not-any? #(let [c (int %)] (or (#{\/ \\} %) (< c 32) (<= 127 c 159))) s))))

(defn memory-store
  "进程内笔记（atom）。"
  ([] (memory-store {}))
  ([init]
   (let [a (atom init)]
     (reify INotesStore
       (list-notes [_] (keys @a))
       (read-note [_ n] (get @a n))
       (write-note! [_ n c] (swap! a assoc n c) nil)))))

(defn dir-store
  "一个目录下一篇一个文件（UTF-8，原子写）。"
  [dir]
  (let [^Path root (.normalize (.toAbsolutePath (ws/->path dir)))]
    (reify INotesStore
      (list-notes [_]
        (if (Files/isDirectory root (make-array LinkOption 0))
          (with-open [ds (Files/newDirectoryStream root)]
            (->> (vec ds)
                 (filter #(Files/isRegularFile ^Path % (make-array LinkOption 0)))
                 (map #(str (.getFileName ^Path %)))
                 (remove #(str/starts-with? % "."))
                 vec))
          []))
      (read-note [_ n]
        (let [p (.resolve root ^String n)]
          (when (Files/isRegularFile p (make-array LinkOption 0))
            (String. (Files/readAllBytes p) StandardCharsets/UTF_8))))
      (write-note! [_ n c]
        (ws/atomic-write! (.resolve root ^String n) (.getBytes (str c) StandardCharsets/UTF_8))
        nil))))

(def ^:const read-max-chars 16000)

(defn- store-of [w]
  (or (:notes w) (fail! "tools/notes-missing-store" "没有配置笔记存储（{:notes (notes/dir-store \"…\")}）"
                        {:error-class :environment})))

(defn- check-name! [n]
  (when-not (valid-name? n)
    (fail! "tools/notes-bad-name"
           (format "笔记名不成立：%s（1–128 个字符，不能含路径分隔符或控制字符，两头不能有空白）。" n))))

(defn notes-list* [w _]
  (let [ns* (sort (list-notes (store-of w)))]
    (if (empty? ns*) "还没有笔记。" (str/join "\n" ns*))))

(defn notes-read* [w {:keys [name]}]
  (check-name! name)
  (let [t (or (read-note (store-of w) name)
              (fail! "tools/notes-not-found" (format "没有这篇笔记：%s。用 notes_list 看有哪些。" name)))]
    (if (> (count t) read-max-chars)
      (str (subs t 0 read-max-chars) "\n\n" (format "[笔记共 %d 个字符，这里是前 %d 个]" (count t) read-max-chars))
      t)))

(defn notes-write* [w {:keys [name content]}]
  (check-name! name)
  (write-note! (store-of w) name (str content))
  (str "记下了：" name))

(deftool notes_list
  "列出你记过的笔记（名字）。开始干活之前先看一眼：以前记下的结论能省掉一整轮摸索。"
  []
  {:tags [:read-only :notes]}
  (notes-list* (ws/current) nil))

(deftool notes_read
  "读一篇笔记。名字来自 notes_list。"
  [[name :string "笔记名，例如 build.md"]]
  {:tags [:read-only :notes]}
  (notes-read* (ws/current) {:name name}))

(deftool notes_write
  "记一篇笔记（整篇覆盖同名）。只记跨会话还成立的结论：怎么构建 / 跑测试、代码约定、踩过的坑、用户明确说过的偏好。不要记这一轮的过程、临时状态或读一遍文件就知道的东西。更新一篇就先 notes_read 再整篇写回。"
  [[name :string "笔记名，例如 build.md"]
   [content :string "整篇内容"]]
  {:serial true :tags [:notes]}
  (notes-write* (ws/current) {:name name :content content}))
