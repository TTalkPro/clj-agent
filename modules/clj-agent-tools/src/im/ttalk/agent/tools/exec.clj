(ns im.ttalk.agent.tools.exec
  "进程工具（T-05）：exec（单发）、exec_command / write_stdin（有状态会话）。

   移植自 OpenAI Codex CLI（Apache-2.0，Copyright 2025 OpenAI；见模块根 THIRD-PARTY-NOTICES.md），经 Keelson
   `Process/*` 转写：
   - HeadTailBuffer ← codex-rs/core/src/unified_exec/head_tail_buffer.rs（头尾各半、中间丢弃并计数）；
   - 单发执行 ← codex-rs/core/src/exec.rs（时限 10 s、超时退出码 124、128 + 信号、杀后排空 2 s）；
   - 固定环境 ← unified_exec/process_manager.rs 的 UNIFIED_EXEC_ENV（不带 CODEX_CI）；
   - 会话 ← unified_exec/{mod.rs, process_manager.rs}（yield 250–30000 ms、空轮询 ≥ 5000 ms、64 个会话、
     最近 8 个不驱逐、先驱逐已退出的、\\u0003 中断）；
   - 结果文本 ← core/src/tools/context.rs 与 core/src/tools/mod.rs（format_exec_output_for_model）。

   两个协议，宿主接沙箱的缝：
   - `IExecBackend`（起进程）：缺省 `local-backend`（ProcessBuilder）；`argv-prefix-backend` 一行把每条命令包进
     `bwrap …` / `firejail …` / `ssh host …`。会话只经它。
   - `ICommandRunner`（单发跑完再返回）：缺省 `backend-runner`；可在这里做「沙箱里失败 → 问人 → 重跑」的编排。
   Keelson.Agent.Sandbox 不移植。不做 PTY（会话是管道，stdin 可写）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.tools.workspace :as ws :refer [fail!]])
  (:import [java.io ByteArrayOutputStream IOException InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Semaphore TimeUnit]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; HeadTailBuffer
;;; ============================================================

(defprotocol IHeadTail
  (push! [b bytes off len] "追加一段：先填头，填满后进尾，尾超预算丢最老的。")
  (omitted [b] "因上限从中间丢掉的字节数。")
  (total [b] "见过的全部字节（含丢掉的）。")
  (to-bytes [b] [b marker-fn] "留着的输出：头 + 尾；给了 marker-fn 且有省略时头尾之间插一行 (marker-fn omitted)。"))

(deftype HeadTail [^long head-budget ^long tail-budget ^ByteArrayOutputStream head
                   ^:unsynchronized-mutable ^bytes tail
                   ^:unsynchronized-mutable ^long tail-start
                   ^:unsynchronized-mutable ^long tail-count
                   ^:unsynchronized-mutable ^long omitted-n]
  IHeadTail
  (push! [_ bs off len]
    (let [^bytes bs bs
          off (long off) len (long len)
          take-n (min (max 0 (- head-budget (.size head))) len)
          _ (.write head bs (int off) (int take-n))
          off (+ off take-n)
          len (- len take-n)
          remaining (max 0 (- tail-budget tail-count))
          excess (max 0 (- len remaining))]
      (set! omitted-n (+ omitted-n excess))
      (let [[off len] (if (< excess tail-count)
                        (do (when (pos? excess)
                              (set! tail-start (rem (+ tail-start excess) (max 1 (alength tail))))
                              (set! tail-count (- tail-count excess)))
                            [off len])
                        (let [skip (- excess tail-count)]
                          (set! tail-start 0)
                          (set! tail-count 0)
                          [(+ off skip) (- len skip)]))
            off (long off) len (long len)]
        (when (pos? len)
          (when (nil? tail) (set! tail (byte-array tail-budget)))
          (let [cap (alength tail)]
            (dotimes [k len]
              (aset tail (int (rem (+ tail-start tail-count k) cap)) (aget bs (int (+ off k)))))
            (set! tail-count (+ tail-count len)))))))
  (omitted [_] omitted-n)
  (total [_] (+ (.size head) tail-count omitted-n))
  (to-bytes [this] (to-bytes this nil))
  (to-bytes [_ marker-fn]
    (let [out (ByteArrayOutputStream.)]
      (.writeBytes out (.toByteArray head))
      (when (and marker-fn (pos? omitted-n))
        (.writeBytes out (.getBytes (str "\n" (marker-fn omitted-n) "\n") StandardCharsets/UTF_8)))
      (when (pos? tail-count)
        (let [cap (alength tail)]
          (dotimes [k tail-count]
            (.write out (int (aget tail (int (rem (+ tail-start k) cap))))))))
      (.toByteArray out))))

(defn head-tail-buffer
  "有上限的输出缓冲：头、尾各占一半预算（奇数时尾多一字节）。不是线程安全的。"
  [max-bytes]
  (let [max-bytes (max 0 (long max-bytes))
        hb (quot max-bytes 2)]
    (->HeadTail hb (- max-bytes hb) (ByteArrayOutputStream.) nil 0 0 0)))

(defn omission-marker [n] (format "... 省略了 %d 字节 ..." n))

(defn truncate-text
  "按 UTF-8 字节头尾截断文本（中间插省略标记）。"
  ^String [^String s max-bytes]
  (let [bs (.getBytes s StandardCharsets/UTF_8)]
    (if (<= (alength bs) (long max-bytes))
      s
      (let [b (head-tail-buffer max-bytes)]
        (push! b bs 0 (alength bs))
        (String. ^bytes (to-bytes b omission-marker) StandardCharsets/UTF_8)))))

;;; ============================================================
;;; 环境 / 后端 / 执行缝
;;; ============================================================

(def fixed-env
  "进程工具给子进程加的固定环境（Codex UNIFIED_EXEC_ENV）：关颜色、哑终端、UTF-8 区域、分页器换成 cat——
   模型读的是纯文本，交互式分页器会让命令挂住。"
  [["NO_COLOR" "1"] ["TERM" "dumb"] ["LANG" "C.UTF-8"] ["LC_CTYPE" "C.UTF-8"] ["LC_ALL" "C.UTF-8"]
   ["COLORTERM" ""] ["PAGER" "cat"] ["GIT_PAGER" "cat"] ["GH_PAGER" "cat"]])

(defn exec-env
  "固定环境 + 宿主追加（值 nil = 删除）。"
  [extra]
  (merge (into {} fixed-env) extra))

(defprotocol IExecBackend
  (start-process [backend req]
    "req {:argv [..] :cwd \"..\"|nil :env {k v|nil}}（env 叠加在继承环境之上，nil 值 = 删除）⇒ java.lang.Process。"))

(defprotocol ICommandRunner
  (run-command [runner req]
    "单发跑完：req {:argv :cwd :env :timeout-ms :output-bytes}
     ⇒ {:exit-code :timed-out? :duration-ms :output bytes :omitted :stdout :stderr}。"))

(defn local-backend
  "本机 ProcessBuilder 后端（**不带沙箱**：宿主须显式选它）。"
  []
  (reify IExecBackend
    (start-process [_ {:keys [argv cwd env]}]
      (let [pb (ProcessBuilder. ^java.util.List (mapv str argv))
            e (.environment pb)]
        (when cwd (.directory pb (java.io.File. (str cwd))))
        (doseq [[k v] env]
          (if (nil? v) (.remove e (str k)) (.put e (str k) (str v))))
        (.start pb)))))

(defn argv-prefix-backend
  "把每条命令包进前缀再交给 base：`(argv-prefix-backend (local-backend) [\"bwrap\" \"--ro-bind\" \"/\" \"/\" …])`。"
  [base prefix]
  (reify IExecBackend
    (start-process [_ req]
      (start-process base (update req :argv #(into (vec prefix) %))))))

(def ^:const timeout-exit-code 124)
(def ^:const default-timeout-ms 10000)
(def ^:const output-max-bytes (* 1024 1024))
(def ^:const drain-timeout-ms 2000)

(defn kill-tree!
  "杀进程树（先子孙后自己）。"
  [^Process p]
  (doseq [^java.lang.ProcessHandle h (iterator-seq (.iterator (.descendants p)))]
    (.destroyForcibly h))
  (.destroyForcibly p))

(defn- pump!
  "后台把 in 的字节抄进 (f bytes n)；结束（EOF / 出错）后返回。⇒ Thread"
  ^Thread [^InputStream in f]
  (Thread/startVirtualThread
   (fn []
     (let [buf (byte-array 8192)]
       (loop []
         (let [n (try (.read in buf) (catch IOException _ -1))]
           (when (pos? n) (f buf n) (recur))))))))

(defn run-on-backend
  "单发执行的本体：起进程、关 stdin（读 stdin 的命令立即见 EOF 而不是挂住）、收输出；
   超时 ⇒ 杀进程树、再等至多 2 s 收尾、退出码 124。"
  [backend {:keys [argv cwd env timeout-ms output-bytes]}]
  (let [cap (long (or output-bytes output-max-bytes))
        all (head-tail-buffer cap) out (head-tail-buffer cap) err (head-tail-buffer cap)
        lock (Object.)
        start (System/nanoTime)
        ^Process p (start-process backend {:argv argv :cwd cwd :env env})
        _ (try (.close (.getOutputStream p)) (catch IOException _ nil))
        t-out (pump! (.getInputStream p) (fn [b n] (locking lock (push! all b 0 n) (push! out b 0 n))))
        t-err (pump! (.getErrorStream p) (fn [b n] (locking lock (push! all b 0 n) (push! err b 0 n))))
        timeout-ms (long (or timeout-ms default-timeout-ms))
        exited? (.waitFor p (max 0 timeout-ms) TimeUnit/MILLISECONDS)]
    (when-not exited? (kill-tree! p))
    ;; 管道正常会随进程关掉；孙进程拿着不放 ⇒ 最多等 2 s 就放弃
    (.join t-out drain-timeout-ms)
    (.join t-err drain-timeout-ms)
    (when (or (.isAlive t-out) (.isAlive t-err))
      (try (.close (.getInputStream p)) (.close (.getErrorStream p)) (catch IOException _ nil)))
    (let [exit (if exited? (.exitValue p) timeout-exit-code)]
      (locking lock
        {:exit-code exit
         :timed-out? (not exited?)
         :duration-ms (quot (- (System/nanoTime) start) 1000000)
         :output (to-bytes all omission-marker)
         :omitted (omitted all)
         :stdout (String. ^bytes (to-bytes out) StandardCharsets/UTF_8)
         :stderr (String. ^bytes (to-bytes err) StandardCharsets/UTF_8)}))))

(defn backend-runner
  "直接经一个后端跑（不审批、不升级）。"
  [backend]
  (reify ICommandRunner
    (run-command [_ req] (run-on-backend backend req))))

;;; ============================================================
;;; 公共
;;; ============================================================

(defn- exec-opts [w]
  (let [o (:exec w)]
    (when-not (or (:backend o) (:runner o))
      (fail! "tools/exec-missing-backend"
             "没有配置进程后端：须在 {:exec {:backend (exec/local-backend)}} 里显式选择（或给沙箱后端 / :runner）"
             {:error-class :environment}))
    o))

(defn- backend-of [w] (or (:backend (exec-opts w))
                          (fail! "tools/exec-missing-backend" "会话工具需要 {:exec {:backend ..}}" {:error-class :environment})))

(defn- runner-of [w] (let [o (exec-opts w)] (or (:runner o) (backend-runner (:backend o)))))

(defn- workdir-of [w raw]
  (if (and (str/blank? raw) (nil? (:cwd w)))
    nil
    (str (ws/resolve! w raw "workdir" :read true))))

(def ^:private windows? (str/includes? (str/lower-case (System/getProperty "os.name")) "win"))

(defn- count-lines [^String s]
  (if (= "" s) 0 (let [n (count (filter #(= \newline %) s))] (if (str/ends-with? s "\n") n (inc n)))))

;;; ============================================================
;;; exec
;;; ============================================================

(defn exec-text
  "Codex format_exec_output_for_model：退出码 / 耗时 / 截断改变了行数时总行数 / 输出（头尾截断）。"
  [{:keys [exit-code timed-out? duration-ms output]} max-bytes]
  (let [content (str (when timed-out? (format "命令在 %d 毫秒后超时\n" duration-ms))
                     (String. ^bytes output StandardCharsets/UTF_8))
        formatted (truncate-text content max-bytes)]
    (str/join "\n" (concat [(str "退出码：" exit-code)
                            (format "耗时：%.1f 秒" (/ duration-ms 1000.0))]
                           (when (not= (count-lines content) (count-lines formatted))
                             [(str "输出总行数：" (count-lines content))])
                           ["输出：" formatted]))))

(defn exec*
  [w {:keys [command workdir timeout_ms]}]
  (let [argv (vec command)]
    (when (or (empty? argv) (str/blank? (str (first argv))))
      (fail! "tools/exec-empty-command" "命令参数为空"))
    (let [o (exec-opts w)
          req {:argv (mapv str argv)
               :cwd (workdir-of w workdir)
               :env (exec-env (:env o))
               :timeout-ms (or timeout_ms (:default-timeout-ms o) default-timeout-ms)
               :output-bytes (:output-bytes o output-max-bytes)}
          r (try (run-command (runner-of w) req)
                 (catch IOException e (fail! "tools/exec-start-failed" (str "exec 失败：" (.getMessage e)))))]
      (if (:rejected r)
        (fail! "tools/exec-rejected" (or (:reason r) "命令没有执行。"))
        (exec-text r (:max-output-bytes o 40000))))))

(deftool exec
  "运行一条命令并回它的输出。command 是 argv，原样交给 execvp()，不自动套 shell：要 shell 就写 [\"bash\", \"-lc\", \"…\"]。总是设 workdir，除非确有必要不要用 cd。缺省时限 10 秒（timeout_ms 可调），超时退出码 124；stdin 关闭。"
  [[command :array "要执行的命令（argv，字符串数组）"]
   [workdir :string "执行命令的工作目录（缺省工作区工作目录）" :default nil]
   [timeout_ms :int "命令的时限（毫秒，缺省 10000）" :default nil]]
  {:sensitive true :serial true :tags [:exec]}
  (exec* (ws/current) {:command command :workdir workdir :timeout_ms timeout_ms}))

;;; ============================================================
;;; 会话
;;; ============================================================

(def ^:const min-yield-ms 250)
(def ^:const min-empty-yield-ms 5000)
(def ^:const max-yield-ms 30000)
(def ^:const default-exec-yield-ms 10000)
(def ^:const default-write-yield-ms 250)
(def ^:const default-max-background-ms 300000)
(def interrupt "\u0003")

(defn- clamp [x lo hi] (max lo (min hi x)))

(defn clamp-exec-yield [ms min-ms] (clamp ms min-ms max-yield-ms))

(defn clamp-write-yield [ms empty? max-bg min-ms]
  (let [t (max ms min-ms)]
    (if empty? (clamp t min-empty-yield-ms (max max-bg min-empty-yield-ms)) (min t max-yield-ms))))

(defn prune-candidate
  "驱逐谁（Codex process_id_to_prune_from_meta）：按最近使用排，最近的 protected 个不动；
   其余按最久没用排，先挑已退出的，没有就挑最久没用的。meta: [{:id :last-used :exited?}]"
  [meta protected]
  (when (seq meta)
    (let [prot (set (map :id (take protected (sort-by :last-used > meta))))
          lru (remove #(prot (:id %)) (sort-by :last-used meta))]
      (:id (or (first (filter :exited? lru)) (first lru))))))

(defn- session-opts [w] (merge {:max 64 :protected 8 :output-bytes output-max-bytes
                                :max-background-ms default-max-background-ms :min-yield-ms min-yield-ms}
                               (get-in w [:exec :sessions])))

(defn- manager [w]
  (let [state (:state w)]
    (or (::sessions @state)
        (::sessions (swap! state update ::sessions #(or % {:table (atom {}) :next-id (atom 1000)}))))))

(defn- alloc-id [w {:keys [table next-id]}]
  (if (:deterministic-ids? (session-opts w))
    (dec (swap! next-id inc))
    (loop [] (let [id (+ 1000 (rand-int 99000))] (if (contains? @table id) (recur) id)))))

(defn- terminate! [s] (try (kill-tree! (:process s)) (catch Exception _ nil)))

(defn- new-session [w id ^Process p cmd]
  (let [cap (:output-bytes (session-opts w))
        lock (Object.)
        buf (volatile! (head-tail-buffer cap))
        push (fn [b n] (locking lock (push! @buf b 0 n)))
        readers [(pump! (.getInputStream p) push) (pump! (.getErrorStream p) push)]]
    {:id id :process p :cmd cmd :lock lock :buf buf :cap cap :readers readers
     :last-used (atom (System/nanoTime)) :gate (Semaphore. 1)}))

(defn- store!
  "登记会话；满了按 Codex 规则驱逐一个（正被 write_stdin 用着的不驱逐，表可暂时超过上限）。"
  [w mgr s]
  (let [{:keys [max protected]} (session-opts w)
        victim (locking mgr
                 (let [t @(:table mgr)
                       v (when (>= (count t) max)
                           (loop [meta (map (fn [[id x]] {:id id :last-used @(:last-used x)
                                                          :exited? (not (.isAlive ^Process (:process x)))})
                                            t)]
                             (when-let [id (prune-candidate meta protected)]
                               (if (.tryAcquire ^Semaphore (:gate (t id)))
                                 (t id)
                                 (recur (remove #(= id (:id %)) meta))))))]
                   (swap! (:table mgr) #(cond-> (assoc % (:id s) s) v (dissoc (:id v))))
                   v))]
    (when victim
      ;; 放开交互锁：正排队等它的 write_stdin 拿到锁后发现它已不在表里 ⇒ 回「未知的会话」
      (.release ^Semaphore (:gate victim))
      (terminate! victim))))

(defn- collect!
  "等到截止或进程退出（退出后至多再等 50 ms 收尾），取走这段输出。"
  [s deadline-ns]
  (let [^Process p (:process s)]
    (loop []
      (let [now (System/nanoTime)]
        (cond
          (not (.isAlive p)) (doseq [^Thread t (:readers s)] (.join t 50))
          (>= now deadline-ns) nil
          :else (do (Thread/sleep (long (min 20 (max 1 (quot (- deadline-ns now) 1000000))))) (recur)))))
    (locking (:lock s)
      (let [b @(:buf s)] (vreset! (:buf s) (head-tail-buffer (:cap s))) b))))

(defn- session-output [b wall-ms session-id exit-code max-tokens]
  (let [raw (String. ^bytes (to-bytes b omission-marker) StandardCharsets/UTF_8)
        tokens (quot (+ (long (total b)) 3) 4)
        budget (* 4 (long (or max-tokens 10000)))]
    (str/join "\n" (concat [(str "块 ID：" (format "%06x" (rand-int 0xffffff)))
                            (format "耗时：%.4f 秒" (/ wall-ms 1000.0))]
                           (if session-id
                             [(str "进程仍在运行，会话 ID " session-id)]
                             [(str "进程已退出，退出码 " exit-code)])
                           [(str "原始 token 数：" tokens)
                            "输出："
                            (truncate-text raw budget)]))))

(defn- refresh!
  "退出了就从表里摘掉 ⇒ [session-id exit-code]。"
  [mgr s]
  (let [^Process p (:process s)]
    (if (.isAlive p)
      [(:id s) nil]
      (do (swap! (:table mgr) dissoc (:id s)) [nil (.exitValue p)]))))

(defn- shell-argv [w cmd shell login?]
  (if windows?
    [(or shell "powershell.exe") "-NoProfile" "-Command" cmd]
    [(or shell (System/getenv "SHELL") "/bin/bash") (if login? "-lc" "-c") cmd]))

(defn exec-command*
  [w {:keys [cmd workdir yield_time_ms max_output_tokens shell login]}]
  (when (str/blank? cmd) (fail! "tools/exec-empty-command" "命令参数为空"))
  (let [o (exec-opts w)
        allow-login? (:allow-login? o true)
        _ (when (and (true? login) (not allow-login?))
            (fail! "tools/exec-login-disabled" "配置禁用了 login shell；省略 login 或设为 false。"))
        mgr (manager w)
        id (alloc-id w mgr)
        start (System/nanoTime)
        ^Process p (try (start-process (backend-of w)
                                       {:argv (shell-argv w cmd shell (if (nil? login) allow-login? login))
                                        :cwd (workdir-of w workdir)
                                        :env (exec-env (:env o))})
                        (catch IOException e (fail! "tools/exec-start-failed" (str "exec_command 失败：" (.getMessage e)))))
        s (new-session w id p cmd)
        _ (when (.isAlive p) (store! w mgr s))
        y (clamp-exec-yield (long (or yield_time_ms default-exec-yield-ms)) (:min-yield-ms (session-opts w)))
        b (collect! s (+ start (* y 1000000)))
        wall (quot (- (System/nanoTime) start) 1000000)
        [sid code] (refresh! mgr s)]
    (session-output b wall sid code max_output_tokens)))

(defn- send-interrupt! [^Process p]
  (if windows?
    (fail! "tools/exec-interrupt-unsupported" "这个平台不支持向会话发中断（\\u0003）")
    (.waitFor (.start (ProcessBuilder. ^java.util.List (vector "kill" "-INT" (str (.pid p))))))))

(defn write-stdin*
  [w {:keys [session_id chars yield_time_ms max_output_tokens]}]
  (let [mgr (manager w)
        chars (or chars "")
        id (long session_id)
        s (or (get @(:table mgr) id) (fail! "tools/exec-session-unknown" (str "未知的会话 ID " id)))
        ^Semaphore gate (:gate s)]
    (.acquire gate)
    (try
      (when-not (identical? s (get @(:table mgr) id))
        (fail! "tools/exec-session-unknown" (str "未知的会话 ID " id)))
      (reset! (:last-used s) (System/nanoTime))
      (let [^Process p (:process s)]
        (cond
          (= "" chars) nil
          (= interrupt chars) (when (.isAlive p) (send-interrupt! p))
          :else (try
                  (let [^OutputStream os (.getOutputStream p)]
                    (.write os (.getBytes ^String chars StandardCharsets/UTF_8))
                    (.flush os))
                  (Thread/sleep 100)
                  (catch IOException e
                    (fail! "tools/exec-stdin-closed" (str "写 stdin 失败：" (.getMessage e))))))
        (let [{:keys [max-background-ms min-yield-ms]} (session-opts w)
              y (clamp-write-yield (long (or yield_time_ms default-write-yield-ms)) (= "" chars) max-background-ms min-yield-ms)
              start (System/nanoTime)
              b (collect! s (+ start (* y 1000000)))
              wall (quot (- (System/nanoTime) start) 1000000)
              [sid code] (refresh! mgr s)]
          (session-output b wall sid code max_output_tokens)))
      (finally (.release gate)))))

(defn session-ids
  "当前登记的会话 id（升序）。"
  [w]
  (sort (keys @(:table (manager w)))))

(defn close-sessions!
  "结束工作区的全部会话（宿主在会话结束时调）。"
  [w]
  (let [mgr (manager w)
        all (vals @(:table mgr))]
    (reset! (:table mgr) {})
    (run! terminate! all)
    (count all)))

(deftool exec_command
  "经 shell 运行一条命令，等至多 yield_time_ms：跑完了回退出码；还在跑就回一个会话 ID，之后用 write_stdin 写输入或轮询输出。适合长时间运行或交互式命令（dev server、REPL、tail -f）。"
  [[cmd :string "要执行的 shell 命令"]
   [workdir :string "命令的工作目录；缺省为工作区工作目录" :default nil]
   [yield_time_ms :int "回输出前等多久（缺省 10000 ms；有效范围 250–30000）" :default nil]
   [max_output_tokens :int "输出的 token 预算（缺省 10000）" :default nil]
   [shell :string "要启动的 shell 程序；缺省为用户的 $SHELL" :default nil]
   [login :boolean "true 以 -l 语义运行 shell（缺省 true）" :default nil]]
  {:sensitive true :serial true :tags [:exec]}
  (exec-command* (ws/current) {:cmd cmd :workdir workdir :yield_time_ms yield_time_ms
                               :max_output_tokens max_output_tokens :shell shell :login login}))

(deftool write_stdin
  "往 exec_command 开的会话写 stdin 并收一段输出。chars 为空串 = 只轮询不写（至少等 5000 ms）；\"\\u0003\" = 发中断（Ctrl-C）。"
  [[session_id :int "正在运行的会话 ID"]
   [chars :string "要写进 stdin 的字符；缺省为空，即只轮询" :default nil]
   [yield_time_ms :int "回输出前等多久（非空写入缺省 250 ms、顶 30000；空轮询 5000–300000）" :default nil]
   [max_output_tokens :int "输出的 token 预算（缺省 10000）" :default nil]]
  {:sensitive true :serial true :tags [:exec]}
  (write-stdin* (ws/current) {:session_id session_id :chars chars :yield_time_ms yield_time_ms
                              :max_output_tokens max_output_tokens}))
