(ns im.ttalk.agent.tools.workspace
  "工作区：路径策略 + 文件层 + 每实例状态（设计：designs/tools-module.md §3 §4）。

   - `IPathPolicy`：每个路径碰之前都问它。缺省 `workspace-policy`——可写根、只读根、
     受保护名（`.git .agents .codex .aws`，照 Codex `PROTECTED_METADATA_PATH_NAMES`），
     判定在**字面**（`..` 已折叠）与**解链接后**两个口径上都做（出处 Keelson
     `WorkspacePathPolicy.cs`）。
   - `IFileLayer`：读写类工具只经它碰文件（出处 Keelson `FileLayer.cs`），缺省
     `local-file-layer`——原子写（同目录临时文件 + ATOMIC_MOVE）、写解链接后的真实路径。
   - `workspace`：把选项收成一个 ws map；`*workspace*` 由 `im.ttalk.agent.tools/workspace-tools`
     造的匿名 var 在**工具函数体内部**绑定（与引擎 / 线程无关，见 design-principles §3）。

   业务失败一律 `fail!`：抛 `ex-info`，ex-data 带 `:error-class :semantic` 与 `:code`，
   `tool/invoke` 把它变成给模型的「错误: …」。"
  (:require [clojure.string :as str])
  (:import [java.io InputStream]
           [java.nio.file AtomicMoveNotSupportedException CopyOption DirectoryNotEmptyException
            Files InvalidPathException LinkOption NoSuchFileException OpenOption Path Paths
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 失败
;;; ============================================================

(defn fail!
  "业务失败（模型改参数即可）：抛 ex-info，`:code` 给宿主按码判别（⛔ 比对文案）。"
  ([code msg] (fail! code msg nil))
  ([code msg data]
   (throw (ex-info msg (merge {:error-class :semantic :code code} data)))))

(defn failure-code
  "从 ex-info 取错误码；不是本模块的失败 ⇒ nil。"
  [e]
  (:code (ex-data e)))

;;; ============================================================
;;; 当前工作区（工具函数体内部绑定）
;;; ============================================================

(def ^:dynamic *workspace*
  "当前工具调用的工作区。只由 `workspace-tools` 造的匿名 var 在函数体内部 binding。"
  nil)

(defn current
  "当前工作区；没绑定 ⇒ 抛（直接调用 deftool var 而没经 workspace-tools）。"
  []
  (or *workspace*
      (throw (ex-info "工具未绑定工作区：请经 im.ttalk.agent.tools/workspace-tools 取得工具"
                      {:error-class :environment :code "tools/unbound"}))))

;;; ============================================================
;;; 路径工具
;;; ============================================================

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private follow (make-array LinkOption 0))

(defn ->path ^Path [x]
  (if (instance? Path x) x (Paths/get (str x) (make-array String 0))))

(defn link? [^Path p] (Files/isSymbolicLink p))
(defn exists-no-follow? [^Path p] (Files/exists p ^"[Ljava.nio.file.LinkOption;" no-follow))
(defn directory? [^Path p] (Files/isDirectory p ^"[Ljava.nio.file.LinkOption;" follow))

(defn- under?
  "path 在 root 下（含相等）。Path.startsWith 按段比较，不会把 /a/bc 当成 /a/b 下。"
  [^Path path ^Path root]
  (.startsWith path root))

(def ^:private max-link-hops 40)

(defn canonical
  "逐段解开**已存在**的符号链接（含悬空链接：按其目标继续解），回解完的绝对路径；
   链接成环（超过 40 跳）⇒ nil。不存在的尾段原样拼上。p 须是绝对、已 normalize。"
  ^Path [^Path p]
  (loop [done (.getRoot p)
         segs (map str (seq p))
         hops 0]
    (if (empty? segs)
      done
      (let [nxt (.resolve ^Path done ^String (first segs))]
        (if (link? nxt)
          (if (>= hops max-link-hops)
            nil
            (let [target (Files/readSymbolicLink nxt)
                  t (.normalize (.resolve ^Path done target))]
              (recur (.getRoot t) (concat (map str (seq t)) (rest segs)) (inc hops))))
          (recur nxt (rest segs) hops))))))

;;; ============================================================
;;; 路径策略
;;; ============================================================

(defprotocol IPathPolicy
  (check-path [policy full-path access]
    "full-path：绝对、已 normalize 的 Path；access ∈ :read / :write。
     放行 ⇒ nil；拒绝 ⇒ {:code \"workspace/…\" :message \"…\"}。"))

(def default-protected-names
  "可写根下这些**顶层**目录只读（Codex permissions.rs PROTECTED_METADATA_PATH_NAMES）。"
  [".git" ".agents" ".codex" ".aws"])

(defn- check-root ^Path [r]
  (let [p (->path r)]
    (when-not (.isAbsolute p)
      (throw (ex-info (str "工作区根必须是绝对路径：" r) {:root r})))
    (.normalize p)))

(defn- deepest [roots ^Path path]
  (reduce (fn [best {:keys [^Path path-of] :as r}]
            (if (and (under? path path-of)
                     (or (nil? best) (> (.getNameCount path-of) (.getNameCount ^Path (:path-of best)))))
              r
              best))
          nil roots))

(defn- protected-name [protected ^Path path ^Path root]
  (when (and (not= path root) (under? path root))
    (let [first-seg (str (.getName (.relativize root path) 0))]
      (some #(when (= % first-seg) %) protected))))

(defrecord WorkspacePolicy [lexical canonical-roots protected]
  IPathPolicy
  (check-path [_ full access]
    (let [^Path lex (.normalize ^Path full)
          lex-root (deepest lexical lex)]
      (if-not lex-root
        {:code "workspace/outside-roots"
         :message (format "%s 在工作区之外（允许的根：%s）" lex
                          (str/join ", " (map (comp str :path-of) lexical)))}
        (let [can (canonical lex)]
          (if-not can
            {:code "workspace/link-loop" :message (format "%s 的符号链接成环" lex)}
            (let [can-root (deepest canonical-roots can)]
              (cond
                (nil? can-root)
                {:code "workspace/symlink-escape"
                 :message (format "%s 经符号链接指向工作区之外（%s）" lex can)}

                (= access :read) nil

                (or (not (:writable lex-root)) (not (:writable can-root)))
                {:code "workspace/read-only" :message (format "%s 只读，不能写" lex)}

                :else
                (when-let [n (or (protected-name protected lex (:path-of lex-root))
                                 (protected-name protected can (:path-of can-root)))]
                  {:code "workspace/protected"
                   :message (format "%s 在受保护目录 %s 下，不能写" lex n)})))))))))

(defn workspace-policy
  "工作区路径策略。roots：可读可写的根（绝对路径，至少一个）。
   opts：:read-only-roots（只读根）、:protected-names（缺省 `default-protected-names`）。"
  ([roots] (workspace-policy roots nil))
  ([roots {:keys [read-only-roots protected-names]
           :or {protected-names default-protected-names}}]
   (let [rw (mapv (fn [r] {:path-of (check-root r) :writable true}) roots)
         _ (when (empty? rw) (throw (ex-info "工作区路径策略至少要一个根" {})))
         ro (mapv (fn [r] {:path-of (check-root r) :writable false}) read-only-roots)
         lexical (into rw ro)
         canonical-roots (mapv (fn [r] (update r :path-of #(or (canonical %) %))) lexical)]
     (->WorkspacePolicy lexical canonical-roots (vec protected-names)))))

(defn unrestricted-policy
  "不设限的策略——确实不限时**显式**给它（⛔ 静默缺省）。"
  []
  (reify IPathPolicy (check-path [_ _ _] nil)))

;;; ============================================================
;;; 文件层
;;; ============================================================

(defprotocol IFileLayer
  (entry [layer path]
    "一项的现状；不存在 ⇒ nil。符号链接本身是 :link（⛔ 跟随）。
     {:kind :file|:dir|:link|:other :size n :mtime ms :link-target str}")
  (open-in [layer path] "读打开（跟随链接）。不存在 ⇒ NoSuchFileException。")
  (write-bytes! [layer path bytes] "整份写入：原子，父目录不存在就建。")
  (delete! [layer path recursive?] "删文件或目录（recursive? 才删非空目录；链接只删链接本身）；不存在不算错。"))

(defn entry-of
  "NIO 取一项（不跟随链接）。"
  [^Path p]
  (when (exists-no-follow? p)
    (cond
      (link? p) {:kind :link :size 0
                 :mtime (.toMillis (Files/getLastModifiedTime p ^"[Ljava.nio.file.LinkOption;" no-follow))
                 :link-target (str (Files/readSymbolicLink p))}
      (Files/isDirectory p ^"[Ljava.nio.file.LinkOption;" no-follow)
      {:kind :dir :size 0 :mtime (.toMillis (Files/getLastModifiedTime p ^"[Ljava.nio.file.LinkOption;" no-follow))}
      (Files/isRegularFile p ^"[Ljava.nio.file.LinkOption;" no-follow)
      {:kind :file :size (Files/size p)
       :mtime (.toMillis (Files/getLastModifiedTime p ^"[Ljava.nio.file.LinkOption;" no-follow))}
      :else {:kind :other :size 0 :mtime 0})))

(defn- copy-posix-perms! [^Path from ^Path to]
  (try
    (Files/setPosixFilePermissions to (Files/getPosixFilePermissions from ^"[Ljava.nio.file.LinkOption;" follow))
    (catch UnsupportedOperationException _ nil)
    (catch java.io.IOException _ nil)))

(defn atomic-write!
  "原子写：同目录临时文件 → ATOMIC_MOVE（不支持时退回 REPLACE_EXISTING）。
   写的是解链接后的真实路径（链接指向的文件被更新，链接本身保留）；保留原文件 POSIX 权限。"
  [^Path p ^bytes data]
  (let [^Path target (or (canonical (.normalize (.toAbsolutePath p))) p)
        dir (.getParent target)
        _ (Files/createDirectories dir (make-array FileAttribute 0))
        tmp (Files/createTempFile dir (str "." (.getFileName target) ".") ".tmp" (make-array FileAttribute 0))]
    (try
      (Files/write tmp data ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
      (when (Files/isRegularFile target ^"[Ljava.nio.file.LinkOption;" follow)
        (copy-posix-perms! target tmp))
      (try
        (Files/move tmp target ^"[Ljava.nio.file.CopyOption;"
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move tmp target ^"[Ljava.nio.file.CopyOption;"
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists tmp)))))

(defn- delete-tree!
  "删目录树：链接只删链接本身（⛔ 跟进去）。"
  [^Path p]
  (if (and (not (link? p)) (Files/isDirectory p ^"[Ljava.nio.file.LinkOption;" no-follow))
    (do (with-open [ds (Files/newDirectoryStream p)]
          (doseq [c (vec ds)] (delete-tree! c)))
        (Files/deleteIfExists p))
    (Files/deleteIfExists p)))

(defn local-file-layer
  "缺省文件层：直接经本机文件系统。"
  []
  (reify IFileLayer
    (entry [_ path] (entry-of (->path path)))
    (open-in [_ path] (Files/newInputStream (->path path) (make-array OpenOption 0)))
    (write-bytes! [_ path bytes] (atomic-write! (->path path) bytes))
    (delete! [_ path recursive?]
      (let [p (->path path)]
        (cond
          (not (exists-no-follow? p)) nil
          (link? p) (Files/deleteIfExists p)
          (and recursive? (Files/isDirectory p ^"[Ljava.nio.file.LinkOption;" no-follow)) (delete-tree! p)
          :else (Files/deleteIfExists p))))))

(defn read-all-bytes
  "经文件层读全部字节。"
  ^bytes [layer path]
  (with-open [^InputStream in (open-in layer path)]
    (.readAllBytes in)))

;;; ============================================================
;;; 工作区
;;; ============================================================

(defn workspace
  "把选项收成工作区 map（`workspace-tools` 调它；宿主也可先建好再传，便于审批预览共用）。

   选项：
   - :root / :roots          可写根（绝对路径）。给了 :policy 时可省
   - :read-only-roots        只读根
   - :protected-names        受保护名（缺省 .git .agents .codex .aws）
   - :policy                 自带 IPathPolicy（给了就不用 :root 造缺省策略）
   - :cwd                    相对路径的基目录；缺省第一个可写根；显式 nil ⇒ 只收绝对路径
   - :file-layer             IFileLayer（缺省 local-file-layer）
   - :read-tracking?         读写新鲜度闸（缺省 true）
   - :reads                  已读表（freshness/read-table）；缺省每个工作区一张
   - :syntax                 语法闸 {:enabled? true :checkers [...]}（追加在内置前面）
   - :grep                   {:rg :auto|false|\"/path/to/rg\" :max-file-bytes n :regex-timeout-ms n}
   - :exec / :web-fetch / :skills / :notes   各工具组的选项（见对应 ns）"
  [opts]
  (if (::workspace? opts)
    opts
    (let [roots (or (:roots opts) (when (:root opts) [(:root opts)]))
          policy (or (:policy opts)
                     (when (seq roots)
                       (workspace-policy roots (select-keys opts [:read-only-roots :protected-names])))
                     (throw (ex-info "workspace 要么给 :root / :roots，要么显式给 :policy（不限就给 (unrestricted-policy)）"
                                     {:opts (keys opts)})))
          cwd (if (contains? opts :cwd)
                (some-> (:cwd opts) check-root)
                (some-> (first roots) check-root))]
      (merge opts
             {::workspace? true
              :policy policy
              :roots (mapv check-root roots)
              :read-only-roots (mapv check-root (:read-only-roots opts))
              :cwd cwd
              :files (or (:file-layer opts) (local-file-layer))
              :reads (when (:read-tracking? opts true)
                       (or (:reads opts) (atom {})))
              :state (atom {})}))))

(defn deny
  "问策略；拒绝 ⇒ 理由 map，放行 ⇒ nil。"
  [ws ^Path full access]
  (check-path (:policy ws) full access))

(defn check!
  "问策略；拒绝 ⇒ fail!（码 = 策略的码）。"
  [ws ^Path full access]
  (when-let [{:keys [code message]} (deny ws full access)]
    (fail! code message {:path (str full)})))

(defn resolve-path
  "解析模型给的路径：相对 ⇒ 按 :cwd；空 ⇒ :cwd 本身（`allow-empty?`）。返回绝对、已 normalize 的 Path
   （**不**问策略；问策略用 `resolve!`）。解析不了 ⇒ fail!。"
  (^Path [ws path arg-name] (resolve-path ws path arg-name false))
  (^Path [ws path arg-name allow-empty?]
   (let [^Path cwd (:cwd ws)]
     (if (str/blank? path)
       (if (and allow-empty? cwd)
         cwd
         (fail! "tools/relative-path"
                (if cwd
                  (format "%s 不能为空" arg-name)
                  (format "%s 必须是绝对路径（这个工作区没有工作目录，相对路径无从解析）" arg-name))))
       (let [^Path p (try (->path path)
                    (catch InvalidPathException e
                      (fail! "tools/invalid-path" (format "%s 不是合法路径：%s" arg-name (.getMessage e)))))]
         (cond
           (.isAbsolute p) (.normalize p)
           cwd (.normalize (.resolve cwd p))
           :else (fail! "tools/relative-path"
                        (format "%s 必须是绝对路径（这个工作区没有工作目录，相对路径无从解析）" arg-name))))))))

(defn resolve!
  "解析 + 问策略；失败 ⇒ fail!。"
  (^Path [ws path arg-name access] (resolve! ws path arg-name access false))
  (^Path [ws path arg-name access allow-empty?]
   (let [full (resolve-path ws path arg-name allow-empty?)]
     (check! ws full access)
     full)))

(defn root-like?
  "文件系统根、工作目录、策略的根（可写 / 只读）以及它们的祖先——删了就是把工作区整个端掉。"
  [ws ^Path full]
  (or (= full (.getRoot full))
      (some (fn [^Path r] (under? r full))
            (cond-> (into (vec (:roots ws)) (:read-only-roots ws))
              (:cwd ws) (conj (:cwd ws))))))

(defn shown
  "报错里给模型看的路径：它自己传的那个（空则完整路径）。"
  [raw ^Path full]
  (if (str/blank? raw) (str full) raw))

(defn real
  "解链接后的路径（已读表的键：同一个文件经不同写法 / 链接访问是同一条）；成环 ⇒ 原样。"
  ^Path [^Path full]
  (or (canonical full) full))

(defn target-entry
  "一项的现状，链接则看它指向的那一项（悬空链接 ⇒ nil）。"
  [ws ^Path full]
  (let [e (entry (:files ws) full)]
    (if (= :link (:kind e))
      (entry (:files ws) (real full))
      e)))
