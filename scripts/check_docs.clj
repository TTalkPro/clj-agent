(ns check-docs
  "README 与真实代码的一致性检查（CI 门禁）。

   动机：2026-07 的一轮排查发现，六个 README 里积了一批**幽灵 API**——功能早
   被删/改，源码里甚至留了「已移除」的注释，文档却没跟。最离谱的是
   `:build-result-msgs`：`model/service.clj` 明写它已移除，四个 README 却还在
   头部特性 bullet 里教人用它。这类腐烂靠人肉复查挡不住，所以机器化。

   四项检查：

   1. **ns 存在**：README 里点名的 `im.ttalk.agent.*` 命名空间必须真的存在
      （抓到过 `im.ttalk.agent.model.types` —— 从来不存在的幽灵）；
   2. **ns 覆盖**：源码里的每个 ns 至少被某个 README 提到
      （抓到过 `pause` / `timeline` / `dashscope` —— 整块功能在模块索引里隐身）；
   3. **符号 resolve**：代码块里的 `alias/sym`（alias 由同文件的 require 绑定）
      必须能 resolve；
   4. **墓碑**：已删除的 API 不得在文档里复活。map 键、宏选项这类东西没法靠
      resolve 检查，故显式登记——**删 API 时往 tombstones 里加一条**。

   设计取舍：**宁可漏报，不可误报**。alias 没在同文件 require 里绑定就跳过
   （Spring 类名、`my-vector-store/search` 这类占位符、`scripts/test-all.sh`
   这类路径因此天然不参检）。一个会误报的门禁很快就会被加 `|| true` 绕过。

   用法：
     clojure -M scripts/check_docs.clj        # 全部检查，有问题 exit 1"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;;; ============================================================
;;; 墓碑：已删除的 API —— 文档里再出现即为腐烂
;;; ============================================================

(def tombstones
  "{已删除的记号 说明/替代}。删 API 时在此登记，文档就再也复活不了它。"
  {":build-result-msgs" "service map 的历史键，已移除（见 model/service.clj）；现为 :chat-fn + :stream-fn"
   "model.types"        "从未存在的 ns（model 下只有 message/response/error/service）"
   ":assistant-msg"     "chat-fn 返回值的历史字段；现返回归一化 ILLMResponse"
   "create-filter :name :chat :order" "filter 的 :order/:phase 早已移除——执行顺序 = :filters 向量注册顺序"})

(def ^:private doc-files
  ["README.md" "README_EN.md" "modules/README.md"
   "modules/clj-agent-core/README.md"
   "modules/clj-agent-client/README.md"
   "modules/clj-agent-provider/README.md"])

;;; 允许不被任何 README 提及的 ns（内部实现细节）
(def ^:private coverage-allowlist
  #{})

;;; ============================================================
;;; 源码 ns 清单
;;; ============================================================

(defn- src-namespaces []
  (->> (file-seq (io/file "modules"))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".clj")
                     (str/includes? (.getPath ^java.io.File %) "/src/")))
       (keep (fn [f]
               (let [content (slurp f)]
                 (when-let [[_ n] (re-find #"\(ns\s+([\w.\-]+)" content)]
                   (symbol n)))))
       (filter #(str/starts-with? (name %) "im.ttalk.agent"))
       set))

;;; ============================================================
;;; 文本解析
;;; ============================================================

(defn- expand-braces
  "im.ttalk.agent.provider.wire.{openai,anthropic} → 两个 ns"
  [s]
  (if-let [[_ prefix inner] (re-find #"^([\w.\-]+)\.\{([\w.,\-]+)\}$" s)]
    (map #(str prefix "." (str/trim %)) (str/split inner #","))
    [s]))

;; 顺序有讲究（正则交替按序试，先匹配者胜）：
;; 1. 通配形式（`...{wire,schema,stream}.*`）整体吃掉 → 随后被 * 过滤器丢弃。
;;    否则花括号会被展开成 provider.wire 这类**包前缀**（并非真 ns）而误报。
;; 2. 花括号形式必须先于裸形式：可选组 (?:...)? 不会为了匹配而回溯，
;;    写成 `[\w.-]*(?:\.\{...\})?` 时贪婪的 [\w.-]* 会吃掉 "." 并让可选组匹配空。
(def ^:private ns-pattern
  #"im\.ttalk\.agent[\w.\-]*(?:\.\{[\w.,\-]+\})?\.\*|im\.ttalk\.agent[\w.\-]*\.\{[\w.,\-]+\}|im\.ttalk\.agent[\w.\-]*")

(defn- mentioned-namespaces
  "README 里点名的 im.ttalk.agent.* ns（剥掉 /Symbol 后缀、展开花括号、跳过通配）。"
  [text]
  (->> (re-seq ns-pattern text)
       (map #(first (str/split % #"/")))          ;; im.ttalk.agent.model/ILLMProvider → ns
       (mapcat expand-braces)
       (remove #(str/ends-with? % "."))
       (remove #(str/includes? % "*"))
       (map #(str/replace % #"\.$" ""))
       set))

(defn- code-blocks [text]
  (map second (re-seq #"(?s)```clojure\n(.*?)```" text)))

(defn- strip-noise
  "剥掉行注释与字符串字面量——两者都是散文/数据，不是 API 调用。
   不剥的话：注释里的「pause/resume」和字符串里的 URL「/anthropic/v1/messages」
   都会被当成 alias/sym 误报。"
  [block]
  (-> block
      (str/replace #"(?m);.*$" "")
      (str/replace #"\"(?:[^\"\\]|\\.)*\"" "\"\"")))

(defn- alias-map
  "同一文件所有代码块里的 (require '[im.ttalk.agent.x :as a]) → {\"a\" ns}。
   跨代码块取并集：require 常写在一个块、用法在另一个块。"
  [text]
  (into {}
        (for [[_ ns-str a] (re-seq #"\[(im\.ttalk\.agent[\w.\-]*)\s+:as\s+([\w\-]+)\]" text)]
          [a (symbol ns-str)])))

(defn- qualified-usages
  "代码块里的 alias/sym（排除 ::kw、:kw 与 java interop）。"
  [block]
  (->> (re-seq #"(?<![:\w.])([a-zA-Z][\w\-]*)/([a-zA-Z!?*<>=+][\w!?*<>=+.\-]*)" block)
       (map (fn [[_ a s]] [a s]))))

;;; ============================================================
;;; 检查
;;; ============================================================

(defn- strip-ignored
  "剥掉显式豁免区：

     <!-- check-docs:ignore-start -->  …  <!-- check-docs:ignore-end -->

   唯一的正当用途：**谈论**幽灵 API 而非使用它（比如本门禁自己的说明文档要举
   `:build-result-msgs` 当反面例子——不豁免就会自我触发）。刻意做成区间标记而
   非整文件豁免：豁免面越小，腐烂越难从这个口子爬回来。"
  [text]
  (str/replace text
               #"(?s)<!--\s*check-docs:ignore-start\s*-->.*?<!--\s*check-docs:ignore-end\s*-->"
               ""))

(defn- check-tombstones [problems]
  (doseq [f doc-files
          :let [text (strip-ignored (slurp f))]
          [ghost why] tombstones
          :when (str/includes? text ghost)]
    (swap! problems conj
           (format "%s: 出现已删除的 API「%s」\n    → %s" f ghost why))))

(defn- check-ns-exists [problems known]
  (doseq [f doc-files
          :let [text (strip-ignored (slurp f))]
          n (mentioned-namespaces text)
          :when (not (contains? known (symbol n)))]
    (swap! problems conj
           (format "%s: 点名了不存在的命名空间「%s」" f n))))

(defn- check-ns-coverage [problems known]
  (let [all-text (str/join "\n" (map slurp doc-files))
        mentioned (mentioned-namespaces all-text)
        ;; 文档常用简写：`im.ttalk.agent.memory` / `.memory.sqlite`、`.manager` / `.delegate`
        shorthand? (fn [n]
                     (let [seg (last (str/split (name n) #"\."))]
                       (or (str/includes? all-text (str "`." seg "`"))
                           (str/includes? all-text (str "/ `." seg)))))]
    (doseq [n known
            :when (and (not (contains? mentioned (name n)))
                       (not (shorthand? n))
                       (not (contains? coverage-allowlist n)))]
      (swap! problems conj
             (format "命名空间 %s 存在于源码，但六个 README 一个都没提到（模块索引不完整）" n)))))

(defn- check-symbols [problems]
  (doseq [f doc-files
          :let [text (slurp f)
                aliases (alias-map text)]
          block (map strip-noise (code-blocks text))
          [a s] (qualified-usages block)
          :let [ns-sym (get aliases a)]
          ;; alias 没绑定到 im.ttalk ns → 不是我们的 API，跳过（宁可漏报不误报）
          :when ns-sym]
    (when-not (ns-resolve ns-sym (symbol s))
      (swap! problems conj
             (format "%s: %s/%s 无法 resolve（%s 里没有 %s）" f a s ns-sym s)))))

;;; ============================================================
;;; main
;;; ============================================================

(defn -main [& _]
  (let [known (src-namespaces)
        problems (atom [])]
    (println "加载" (count known) "个命名空间…")
    (doseq [n known]
      (try (require n)
           (catch Throwable t
             (swap! problems conj (format "无法加载命名空间 %s: %s" n (.getMessage t))))))

    (check-tombstones problems)
    (check-ns-exists problems known)
    (check-ns-coverage problems known)
    (check-symbols problems)

    (println)
    (if (empty? @problems)
      (do (println "✓ 文档与代码一致：" (count doc-files) "个 README，"
                   (count known) "个命名空间，" (count tombstones) "条墓碑")
          (System/exit 0))
      (do (println "✗ 发现" (count @problems) "处文档与代码不符：\n")
          (doseq [p (sort @problems)] (println " •" p))
          (println "\n（若为有意为之：更新文档，或改 scripts/check_docs.clj 的 allowlist/tombstones）")
          (System/exit 1)))))

(-main)
