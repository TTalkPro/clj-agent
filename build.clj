(ns build
  "clj-agent 多模块构建/发布（tools.build）。

   历史：三个模块各有一份 build.clj，内容 95% 相同——同一套 pom-data、同一个
   版本方案、同一段 override-deps 注释，连「b/install 必填 :class-dir」这条踩坑
   记录都抄了三遍。改一处要改三处，于是合并到根：模块表 + 一套函数。

   核心机制是 `b/set-project-root!`——tools.build 的所有路径（:project \"deps.edn\"、
   target/、class-dir）都相对 *project-root* 解析，把它指向模块目录即可复用同一
   套代码。注意 deps-deploy **不吃** *project-root*，故 deploy 的路径须显式
   `b/resolve-path`。

   用法（-T:build，模块顺序 core → client → provider 已内建）：

     clojure -T:build clean            # 清理全部模块的 target/
     clojure -T:build jar              # 打包全部模块
     clojure -T:build install          # 装到本地 ~/.m2
     clojure -T:build release          # 每模块 clean → jar → install（发布前的完整流程）
     clojure -T:build deploy           # 推 Clojars（需 CLOJARS_USERNAME/PASSWORD）

     clojure -T:build jar :module core # 只做单个模块（core/client/provider/agui/tools）

   或经 babashka：bb jar / bb install / bb release / bb deploy（见 bb.edn）。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; 版本在项目根算一次：git-count-revs 跟着 *project-root* 走，但全部模块同属一个
;; 仓库、共用 0.5.<git-count> 逐提交对齐的版本方案，故不允许按模块各算各的。
(def version (format "0.5.%s" (b/git-count-revs nil)))

(def ^:private scm-url "https://github.com/TTalkPro/clj-agent")

(def ^:private scm
  {:url scm-url
   :connection "scm:git:git://github.com/TTalkPro/clj-agent.git"
   :developerConnection "scm:git:ssh://git@github.com/TTalkPro/clj-agent.git"
   :tag (str "v" version)})

(def modules
  "按依赖顺序：core 必须最先 install，否则 client/provider 的 :release
   override（把 core 从 :local/root 换成同版本 :mvn/version）解析不到坐标。

   :override-libs —— 发布关键：模块间依赖在各自 deps.edn 里是 {:local/root \"..\"}，
   write-pom 无法把本地路径写成合法 Maven 坐标，生成的 pom 会**缺失该依赖**，
   消费方解析即断。故构建期用 alias 把它们覆盖成同版本 :mvn/version。
   （create-basis 没有顶层 :override-deps 参数——曾直接传 → 被静默忽略，pom 仍缺
   依赖。必须放进 alias 再经 :aliases 启用。）

   **它是列表而不是布尔**（2026-09-03 随 agui 模块泛化）：agui 同时依赖 core 与
   client，原来的 `:override-core?` 只覆盖 core，pom 会**只缺 client 那一条**——
   同一个坑换个依赖再踩一遍。加模块时把它依赖的全部内部模块列进来。"
  [{:key :http
    :lib 'im.ttalk/clj-http
    :dir "modules/clj-http"
    :description "im.ttalk clj-http - general-purpose zero-dependency HTTP client on java.net.http (request / async / line streaming / SSE / proxy)"}
   {:key :core
    :lib 'im.ttalk/clj-agent-core
    :dir "modules/clj-agent-core"
    :description "clj-agent Core - ChatClient / ChatModel 原语与协议（对齐 Spring AI 命名）"}
   {:key :client
    :lib 'im.ttalk/clj-agent-client
    :dir "modules/clj-agent-client"
    :override-libs ['im.ttalk/clj-agent-core]
    :description "clj-agent Client - Agent runtime (client / ReAct loop / memory / subagent)"}
   {:key :provider
    :lib 'im.ttalk/clj-agent-provider
    :dir "modules/clj-agent-provider"
    :override-libs ['im.ttalk/clj-agent-core 'im.ttalk/clj-http]
    :description "clj-agent LLM - LLM Provider Abstraction Module"}
   ;; mcp 曾不在本表里——而 agui 依赖它，agui 的 pom 因此缺 mcp 这条依赖（2026-11-09 补上）
   {:key :mcp
    :lib 'im.ttalk/clj-agent-mcp
    :dir "modules/clj-agent-mcp"
    :override-libs ['im.ttalk/clj-http]
    :description "clj-agent MCP - Model Context Protocol client + server (Streamable HTTP / stdio)"}
   {:key :agui
    :lib 'im.ttalk/clj-agent-agui
    :dir "modules/clj-agent-agui"
    :override-libs ['im.ttalk/clj-agent-core 'im.ttalk/clj-agent-client 'im.ttalk/clj-agent-mcp 'im.ttalk/clj-http]
    :description "clj-agent AGUI - Agent runtime backend (run registry / event stream / AG-UI codec)"}
   {:key :tools
    :lib 'im.ttalk/clj-agent-tools
    :dir "modules/clj-agent-tools"
    :override-libs ['im.ttalk/clj-agent-core 'im.ttalk/clj-http]
    ;; 第三方署名随 jar 发（Apache-2.0 §4：再分发须附许可证原文与 NOTICE）
    :extra-files ["LICENSE-codex.txt" "THIRD-PARTY-NOTICES.md"]
    :description "clj-agent Tools - workspace coding tools for agents (files / edit / apply_patch / exec / web_fetch)"}
   {:key :server
    :lib 'im.ttalk/clj-agent-server
    :dir "modules/clj-agent-server"
    :override-libs ['im.ttalk/clj-http 'im.ttalk/clj-agent-core 'im.ttalk/clj-agent-client 'im.ttalk/clj-agent-mcp 'im.ttalk/clj-agent-agui]
    :description "clj-agent Server - AG-UI and MCP HTTP endpoints as Ring handlers (routing / guard / SSE / adapter shims)"}
   {:key :server-jetty
    :lib 'im.ttalk/clj-agent-server-jetty
    :dir "modules/clj-agent-server-jetty"
    :override-libs ['im.ttalk/clj-agent-server 'im.ttalk/clj-http 'im.ttalk/clj-agent-core 'im.ttalk/clj-agent-client 'im.ttalk/clj-agent-mcp 'im.ttalk/clj-agent-agui]
    :description "clj-agent Server Jetty - default Jetty 12 adapter/host for clj-agent-server"}])

(def ^:private class-dir "target/classes")
(def ^:private src-dirs ["src"])

(defn- jar-file [{:keys [lib]}]
  (format "target/%s-%s.jar" (name lib) version))

(defn- basis [{:keys [override-libs]}]
  (if (seq override-libs)
    (b/create-basis {:project "deps.edn"
                     :extra {:aliases {:release {:override-deps
                                                 (into {} (map (fn [lib] [lib {:mvn/version version}]))
                                                       override-libs)}}}
                     :aliases [:release]})
    (b/create-basis {:project "deps.edn"})))

(defn- select
  ":module core|client|provider|agui（也吃 clj-agent-core 这种全名）→ 单模块；缺省 = 全部。"
  [{:keys [module]}]
  (if-not module
    modules
    (let [k (-> module name (str/replace #"^clj-(agent-)?" "") keyword)]
      (or (seq (filter #(= k (:key %)) modules))
          (throw (ex-info (format "未知模块 %s（可选：%s）"
                                  module (str/join " / " (map (comp name :key) modules)))
                          {:module module}))))))

(defn- run-modules!
  "在每个模块目录下执行 f。tools.build 的路径全部相对 *project-root*，
   故切根即切模块；finally 复位，避免半路抛异常后污染后续调用。"
  [opts label f]
  (doseq [m (select opts)]
    (println (format "[%s] %s %s" label (:lib m) version))
    (try
      (b/set-project-root! (:dir m))
      (f m)
      (finally (b/set-project-root! ".")))))

(defn- clean* [_] (b/delete {:path "target"}))

(defn- install*
  ;; 注：b/install 必填 :class-dir（曾误传 :src-dirs → assert-required 报错，install 从未跑通）
  [{:keys [lib] :as m}]
  (b/install {:basis (basis m)
              :lib lib
              :version version
              :jar-file (jar-file m)
              :class-dir class-dir}))

(defn- installed?
  "下游模块的 basis 把内部依赖覆盖为 :mvn/version，故它们**打包前**就要求本地仓库
   里已有同版本的那些 jar——否则 create-basis 直接解析失败。"
  [{:keys [lib]}]
  (.exists (io/file (System/getProperty "user.home") ".m2" "repository"
                    (str/replace (namespace lib) "." "/") (name lib) version
                    (format "%s-%s.jar" (name lib) version))))

;;; ============================================================
;;; 任务
;;; ============================================================

(defn clean
  "删除模块 target/。"
  [opts]
  (run-modules! opts "clean" clean*)
  opts)

(declare jar)

(defn- ensure-deps-installed!
  "只打某个下游模块（例如 `jar :module agui`）时自动补齐它 override 掉的内部依赖，
   免得撞上「必须先 install core」这条隐式前提——旧的 build-all.sh 就栽在这。
   按 `modules` 的声明序补（core 在前）：补 client 本身又要求 core 已在库里。"
  [opts]
  (let [needed (into #{} (mapcat :override-libs) (select opts))]
    (doseq [m modules
            :when (and (contains? needed (:lib m)) (not (installed? m)))]
      (println (format "[prep] 本地 Maven 缺 %s %s，先补一次" (:lib m) version))
      (jar {:module (:key m)})
      (run-modules! {:module (:key m)} "install" install*))))

(defn jar
  "写 pom + 打 jar 到 modules/<m>/target/。"
  [opts]
  (ensure-deps-installed! opts)
  (run-modules!
   opts "jar"
   (fn [{:keys [lib description] :as m}]
     (b/write-pom {:class-dir class-dir
                   :lib lib
                   :version version
                   :basis (basis m)
                   :src-dirs src-dirs
                   :scm scm
                   :pom-data [[:description description]
                              [:url scm-url]
                              [:licenses
                               [:license
                                [:name "MIT"]
                                [:url "https://opensource.org/licenses/MIT"]]]
                              [:developers
                               [:developer
                                [:id "clj-agent"]
                                [:name "clj-agent team"]]]]})
     (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
     (doseq [f (:extra-files m)]
       (b/copy-file {:src f :target (str class-dir "/META-INF/" (name lib) "/" f)}))
     (b/jar {:class-dir class-dir :jar-file (jar-file m)})))
  opts)

(defn install
  "装到本地 ~/.m2/repository。core 先行——client/provider 的 pom 依赖它。

   先 jar 再 install：曾有人 clean 后直接 `bb install`，install 拿不到
   modules/<m>/target/*.jar，Windows 上只丢一个裸 NoSuchFileException。
   jar 内部会按需补装 override 掉的内部依赖（ensure-deps-installed!）。"
  [opts]
  (jar opts)
  (run-modules! opts "install" install*)
  opts)

(defn deploy
  "推 Clojars。deps-deploy 不认 *project-root*，故路径显式 resolve 成含模块前缀的形式。

   单个模块失败不阻断其余模块，跑完再汇总：
   - Clojars 403「redeploying non-snapshots is not allowed」说明该模块当前版本
     已在远端，视为跳过而非失败（重跑 resume 场景下这是正常路径）；
   - 其余异常记为失败，最后整体抛错，让退出码如实反映本次 deploy 未全绿。"
  [opts]
  (let [targets (select opts)
        ok (atom [])
        skipped (atom [])
        failed (atom [])]
    (doseq [m targets]
      (println (format "[deploy] %s %s" (:lib m) version))
      (try
        (b/set-project-root! (:dir m))
        (dd/deploy (merge {:installer :remote
                           :artifact (str (b/resolve-path (jar-file m)))
                           :pom-file (str (b/pom-path {:class-dir class-dir
                                                       :lib (:lib m)
                                                       :version version}))}
                          (dissoc opts :module)))
        (swap! ok conj (:lib m))
        (catch Exception e
          (if (str/includes? (or (ex-message e) "")
                             "redeploying non-snapshots is not allowed")
            (do (println (format "↷ %s %s 已在远端，跳过（redeploy 非快照被 Clojars 拒绝）"
                                 (:lib m) version))
                (swap! skipped conj (:lib m)))
            (do (println (format "✗ %s deploy failed：%s" (:lib m) (ex-message e)))
                (swap! failed conj [(:lib m) e]))))
        (finally (b/set-project-root! "."))))
    (when (seq @failed)
      (println)
      (println (format "✓ 已推送：%s"
                       (str/join " " @ok)))
      (when (seq @skipped)
        (println (format "↷ 已在远端跳过：%s" (str/join " " @skipped))))
      (println (format "✗ 失败：%s"
                       (str/join " " (map first @failed))))
      (throw (ex-info (format "%d/%d 个模块 deploy 失败"
                              (count @failed) (count targets))
                      {:failed (map first @failed) :deployed @ok :skipped @skipped})))
    (println (format "✓ 已推送 %d 个模块%s" (count @ok)
                     (if (seq @skipped)
                       (format "，另有 %d 个已在远端跳过：%s"
                               (count @skipped) (str/join " " @skipped))
                       "")))
    opts))

(defn release
  "发布前的完整本地流程。

   是**逐模块** clean → jar → install，而非全量各来一遍：下游模块打包时
   basis 已把 core 换成 :mvn/version，故 core 必须在**它们打包之前**就落进 ~/.m2。
   先 jar 全部再 install 全部会在 fresh clone 上当场解析失败。"
  [opts]
  (doseq [m (select opts)]
    (let [one {:module (:key m)}]
      (clean one)
      (jar one)
      ;; 不走公开 install——那会再 jar 一遍，release 已是逐模块 clean→jar→install。
      (run-modules! one "install" install*)))
  (println (format "✓ %s 已构建并安装到本地 Maven" version))
  opts)
