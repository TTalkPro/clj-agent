(ns im.ttalk.agent.filter.instructions
  "分层指令文件（designs/runtime-borrow.md §10；借 Keelson
   `Instructions/HierarchicalFileInstructionSource.cs` 与 `Advisors/InstructionsAdvisor.cs`）。

   项目里放一份 `AGENTS.md`（子目录可以再放一份更具体的），agent 每次问模型时把
   「根 → … → cwd」路径上每层**第一个存在的候选文件**拼起来，接在 system prompt 后面。

   - 指令**不进消息历史**：只改本次请求的 `:system-prompt` 选项，ChatMemory 一个字都不留；
   - 候选文件名由调用方给（库内不写死；缺省 `[\"AGENTS.md\"]` 只是 opts 缺省值）；
   - 字节预算截断，不劈 UTF-8 多字节字符；读不到的层跳过，不打断回合；
   - 每次 LLM 调用现读（文件小，改了立刻生效）。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [im.ttalk.agent.filter :as flt])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(set! *warn-on-reflection* true)

(defn- canonical-path ^Path [p]
  (.normalize (.toAbsolutePath (.toPath (io/file p)))))

(defn- path-from-root
  "[root … cwd] 目录序列；cwd 不在 root 之内抛。"
  [^Path root ^Path cwd]
  (when-not (.startsWith cwd root)
    (throw (ex-info "cwd 必须位于 root 之内" {:root (str root) :cwd (str cwd)})))
  (if (= root cwd)
    [root]
    (reductions (fn [^Path acc part] (.resolve acc (str part)))
                root
                (iterator-seq (.iterator (.relativize root cwd))))))

(defn- limit-utf8
  "按字节预算截断，不切掉多字节字符的中间。"
  ^bytes [^bytes bs allowance]
  (if (<= (alength bs) allowance)
    bs
    (let [len (loop [len (long allowance)]
                (if (and (pos? len) (= 0x80 (bit-and (aget bs (dec len)) 0xC0)))
                  (recur (dec len))
                  len))
          len (if (and (pos? len) (>= (bit-and (aget bs (dec len)) 0xFF) 0xC0)) (dec len) len)]
      (java.util.Arrays/copyOf bs (int len)))))

(defn- strip-bom ^bytes [^bytes bs]
  (if (and (>= (alength bs) 3)
           (= (bit-and (aget bs 0) 0xFF) 0xEF)
           (= (bit-and (aget bs 1) 0xFF) 0xBB)
           (= (bit-and (aget bs 2) 0xFF) 0xBF))
    (java.util.Arrays/copyOfRange bs 3 (alength bs))
    bs))

(def ^:private sep "\n\n")

(defn hierarchical-file-source
  "分层文件指令源。返回 `(fn [] -> {:text :origins :truncated?} | nil)`。

   opts:
   - :root       根目录（必需）
   - :cwd        当前目录（缺省 = root；相对路径按 root 解析；必须在 root 之内）
   - :names      同一目录里的候选文件名，按顺序取第一个存在的（缺省 [\"AGENTS.md\"]）
   - :max-bytes  总字节预算（缺省 32768）"
  [{:keys [root cwd names max-bytes] :or {names ["AGENTS.md"] max-bytes 32768}}]
  (when (str/blank? (str root)) (throw (ex-info ":root 必需" {})))
  (when (empty? names) (throw (ex-info ":names 至少一个候选文件名" {})))
  (doseq [n names]
    (when (or (str/blank? n) (not= n (.getName (io/file n))))
      (throw (ex-info "候选名必须是纯文件名（不含目录）" {:name n}))))
  (let [root-p (canonical-path root)
        cwd-p (cond
                (str/blank? (str cwd)) root-p
                (.isAbsolute (io/file cwd)) (canonical-path cwd)
                :else (.normalize (.resolve root-p (str cwd))))
        dirs (vec (path-from-root root-p cwd-p))
        sep-bytes (count (.getBytes ^String sep StandardCharsets/UTF_8))]
    (fn []
      (when (pos? max-bytes)
        (loop [[^Path d & more] dirs, chunks [], origins [], used 0, truncated? false]
          (if (nil? d)
            (when (seq chunks)
              {:text (str/join sep (map #(String. ^bytes % StandardCharsets/UTF_8) chunks))
               :origins origins
               :truncated? truncated?})
            (let [^File f (some #(let [f (.toFile (.resolve d ^String %))] (when (.isFile f) f)) names)
                  bs (when f (try (strip-bom (Files/readAllBytes (.toPath f))) (catch Exception _ nil)))]
              (if (nil? bs)
                (recur more chunks origins used truncated?)
                (let [extra (if (seq chunks) sep-bytes 0)
                      allowance (- max-bytes used extra)]
                  (if (<= allowance 0)
                    (recur nil chunks origins used true)
                    (let [lim (limit-utf8 bs allowance)
                          cut? (< (alength lim) (alength ^bytes bs))]
                      (cond
                        (zero? (alength lim)) (recur more chunks origins used true)
                        cut? (recur nil (conj chunks lim) (conj origins (str f))
                                    (long (+ used extra (alength lim))) true)
                        :else (recur more (conj chunks lim) (conj origins (str f))
                                     (long (+ used extra (alength lim))) truncated?)))))))))))))

(defn instructions-filter
  "指令源 `:chat` filter：每次 LLM 调用前取正文，接到 `:system-prompt` 选项后面
   （没有就作为 system prompt）。正文为空 / 源抛异常 → 原样放行。

   source: `(fn [] -> {:text ..} | 字符串 | nil)`，如 `hierarchical-file-source` 的返回值。
   opts:   :separator 与已有 system prompt 之间的分隔（缺省两个换行）"
  [source & {:keys [separator] :or {separator "\n\n"}}]
  (flt/create-filter :instructions
   :chat (fn [req chain]
           (let [loaded (try (source) (catch Throwable _ nil))
                 text (if (string? loaded) loaded (:text loaded))]
             (if (str/blank? text)
               (chain req)
               (let [sp (flt/req-option req :system-prompt)]
                 (chain (flt/with-option req :system-prompt
                          (if (str/blank? sp) text (str sp separator text))))))))))
