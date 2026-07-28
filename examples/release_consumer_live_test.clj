(ns release-consumer-live-test
  "**发布产物**的消费方视角验证（`bb release` 之后跑）。

   单测证明不了、只有它能证的事：单测跑在源码 classpath 上，`modules/*/src` 全在
   `:paths` 里——**它对 jar 和 pom 一无所知**。而消费方拿到的是 jar + pom：

   1. **传递依赖真的成立**。client/provider 的 `deps.edn` 里 core 是
      `{:local/root \"..\"}`，`write-pom` 写不出本地路径的合法 Maven 坐标；
      build.clj 用 alias 的 `:override-deps` 把它换成同版本 `:mvn/version`，
      pom 才有那条 `<dependency>`。这套机制一旦回归，**单测与 check_docs 全都照不到**
      ——症状只在别人的项目里出现：`Could not find artifact im.ttalk:clj-agent-core`。
      故本脚本的 deps **故意只声明 client + provider，不声明 core**：core 能进
      classpath，只可能是 pom 传递进来的。
   2. **装出来的 jar 真能跑**。源码能跑不等于 jar 能跑（漏 copy 的资源、写错的
      `:paths`、AOT 残留都只在 jar 里现形），故末尾接一次真实 provider 调用。

   怎么做到的：脚本自己 fork 一个子进程，cwd 指向临时目录（**避开仓库的
   deps.edn**，否则又走回源码 classpath，白验），deps 用 `-Sdeps` 只给
   client + provider 的 `:mvn/version`。父进程只负责校验 ~/.m2 里有没有产物、
   拼命令行；所有断言都在子进程里跑。

   运行（需先 `bb release`，需 MINIMAX_API_KEY）：
     clojure -M -e '(load-file \"examples/release_consumer_live_test.clj\")'

   环境变量：
     MINIMAX_API_KEY - MiniMax API Key（必需；兼容旧名 MINIMAX_AUTH_TOKEN）"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;;; ============================================================
;;; 公共
;;; ============================================================

(def ^:private child? (some? (System/getenv "CLJ_AGENT_CONSUMER_CHILD")))

(def failures (atom 0))
(def passes (atom 0))

(defn check [desc ok?]
  (if ok?
    (do (swap! passes inc) (println "  ✓" desc))
    (do (swap! failures inc) (println "  ✗ FAIL:" desc))))

;;; ============================================================
;;; 父进程：校验产物 + fork 子进程
;;; ============================================================

(def ^:private this-file (.getAbsolutePath (io/file *file*)))

(defn- release-version
  "与 build.clj 同一套：0.3.<git-count-revs>。"
  []
  (let [{:keys [exit out]} (shell/sh "git" "rev-list" "HEAD" "--count")]
    (when-not (zero? exit)
      (println "拿不到 git rev count——本脚本要在仓库里跑")
      (System/exit 1))
    (str "0.3." (str/trim out))))

(defn- m2-jar [artifact version]
  (io/file (System/getProperty "user.home") ".m2" "repository" "im" "ttalk"
           artifact version (format "%s-%s.jar" artifact version)))

(defn- run-parent! []
  (let [version (release-version)
        artifacts ["clj-agent-core" "clj-agent-client" "clj-agent-provider"]
        missing (remove #(.exists (m2-jar % version)) artifacts)]
    (println "=== 发布产物消费方验证 ===")
    (println "版本：" version)
    (when (seq missing)
      (println "\n本地 Maven 缺这些产物：" (str/join " " missing))
      (println "先跑：bb release")
      (System/exit 1))
    ;; 子进程：cwd = 临时目录（不能是仓库根，否则 deps.edn 把源码塞进 classpath，
    ;; 这次验证就退化成「源码能跑」——恰恰是单测已经覆盖、而这里要排除的那件事）
    (let [tmp (.toFile (java.nio.file.Files/createTempDirectory
                         "clj-agent-consumer" (into-array java.nio.file.attribute.FileAttribute [])))
          deps (format "{:deps {im.ttalk/clj-agent-client {:mvn/version \"%s\"} im.ttalk/clj-agent-provider {:mvn/version \"%s\"}}}"
                       version version)
          pb (doto (ProcessBuilder. ["clojure" "-Sdeps" deps "-M" "-e"
                                     (format "(load-file \"%s\")" this-file)])
               (.directory tmp)
               (.inheritIO))]
      (.put (.environment pb) "CLJ_AGENT_CONSUMER_CHILD" version)
      (println "子进程 deps：" deps)
      (println "子进程 cwd： " (.getPath tmp) "（无 deps.edn，故源码不在 classpath 上）")
      (let [code (.waitFor (.start pb))]
        ;; 只删自己造的临时目录（子进程会在里面留下 .cpcache）
        (doseq [f (reverse (file-seq tmp))] (io/delete-file f true))
        (System/exit code)))))

;; 父进程在此终止：下面的 require 与 deftool 只在子进程里被读到。
;; （load-file 逐个 form 读-求值，System/exit 之后的 form 根本不会被编译，
;;   所以父进程压根不需要 im.ttalk.* 在 classpath 上。）
(when-not child?
  (run-parent!))

;;; ============================================================
;;; 子进程：断言全在这里
;;; ============================================================

(require '[im.ttalk.agent.client :as agent]
         '[im.ttalk.agent.tool :refer [deftool]]
         '[im.ttalk.agent.provider.minimax :as minimax])

(def version (System/getenv "CLJ_AGENT_CONSUMER_CHILD"))

(defn- resource-of
  "某个 ns 的源文件是从哪来的——jar 还是目录。"
  [path]
  (str (.getResource (clojure.lang.RT/baseLoader) path)))

(defn- from-jar? [path artifact]
  (str/includes? (resource-of path) (format "%s-%s.jar" artifact version)))

;;; ---- 1. pom 的传递依赖 --------------------------------------

(println "\n=== 1. core 经 pom 传递而来（消费方 deps 里没有它）===")

(check "im.ttalk.agent.model/call-llm 可 resolve（core 的协议）"
       (some? (requiring-resolve 'im.ttalk.agent.model/call-llm)))
(check "im.ttalk.agent.kernel/invoke-tool 可 resolve（core 的 kernel 原语）"
       (some? (requiring-resolve 'im.ttalk.agent.kernel/invoke-tool)))

(println "     core 来源：" (resource-of "im/ttalk/agent/kernel.clj"))
(check "core 来自 jar 而非源码目录（排除「其实走了 :local/root」）"
       (from-jar? "im/ttalk/agent/kernel.clj" "clj-agent-core"))
(check "client 来自 jar" (from-jar? "im/ttalk/agent/client.clj" "clj-agent-client"))
(check "provider 来自 jar" (from-jar? "im/ttalk/agent/provider/minimax.clj" "clj-agent-provider"))
(check "第三方依赖也经 pom 传递（cheshire / timbre）"
       (and (some? (requiring-resolve 'cheshire.core/generate-string))
            (some? (requiring-resolve 'taoensso.timbre/info))))

;;; ---- 2. 装出来的 jar 真能跑 ---------------------------------

(println "\n=== 2. 真实 provider 端到端（一轮工具调用）===")

(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(when-not auth-token
  (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
  (System/exit 1))

(deftool get-weather
  "查询指定城市当前天气"
  [[city :string "城市名"]]
  (str city ": 晴, 25°C"))

(def log (atom []))

(def a (agent/create-agent
         {:provider (minimax/create-provider {:api-key auth-token})
          :model minimax/default-model
          :max-tokens 1024
          :tools [#'get-weather]
          :callbacks {:on-tool-call (fn [n args] (swap! log conj [:call n args]) nil)
                      :on-tool-result (fn [& args] (swap! log conj (into [:result] args)))}}))

(def r (agent/chat a "北京今天天气怎么样？请用 get-weather 工具查询。"))

;; 模型措辞只打印不断言（见 README「写 live 断言的一条规矩」）
(println "     模型回复：" (pr-str (:text r)))

(let [cnt (fn [kw] (count (filter #(= kw (first %)) @log)))]
  (check "chat 返回 :completed" (= :completed (:status r)))
  (check "回复文本非空" (seq (:text r)))
  (check "on-tool-call 触发 1 次（jar 里的工具链路活着）" (= 1 (cnt :call)))
  (check "on-tool-result 触发 1 次" (= 1 (cnt :result))))

(println (format "\n%s  %d 通过 / %d 失败（版本 %s）"
                 (if (zero? @failures) "✓ 发布产物消费方验证全绿" "✗ 有失败")
                 @passes @failures version))

(shutdown-agents)
(System/exit (if (zero? @failures) 0 1))
