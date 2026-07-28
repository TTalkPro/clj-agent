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

     clojure -T:build clean            # 清理三个模块的 target/
     clojure -T:build jar              # 打包三个模块
     clojure -T:build install          # 装到本地 ~/.m2
     clojure -T:build release          # 每模块 clean → jar → install（发布前的完整流程）
     clojure -T:build deploy           # 推 Clojars（需 CLOJARS_USERNAME/PASSWORD）

     clojure -T:build jar :module core # 只做单个模块（core/client/provider）

   或经 babashka：bb jar / bb install / bb release / bb deploy（见 bb.edn）。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; 版本在项目根算一次：git-count-revs 跟着 *project-root* 走，但三个模块同属一个
;; 仓库、共用 0.3.<git-count> 逐提交对齐的版本方案，故不允许按模块各算各的。
(def version (format "0.3.%s" (b/git-count-revs nil)))

(def ^:private scm-url "https://github.com/TTalkPro/clj-agent")

(def ^:private scm
  {:url scm-url
   :connection "scm:git:git://github.com/TTalkPro/clj-agent.git"
   :developerConnection "scm:git:ssh://git@github.com/TTalkPro/clj-agent.git"
   :tag (str "v" version)})

(def modules
  "按依赖顺序：core 必须最先 install，否则 client/provider 的 :release
   override（把 core 从 :local/root 换成同版本 :mvn/version）解析不到坐标。

   :override-core? —— 发布关键：core 在模块 deps.edn 里是 {:local/root \"..\"}，
   write-pom 无法把本地路径写成合法 Maven 坐标，生成的 pom 会**缺失 core 依赖**，
   消费方解析即断。故构建期用 alias 把它覆盖成同版本 :mvn/version。
   （create-basis 没有顶层 :override-deps 参数——曾直接传 → 被静默忽略，pom 仍缺
   core 依赖。必须放进 alias 再经 :aliases 启用。）"
  [{:key :core
    :lib 'im.ttalk/clj-agent-core
    :dir "modules/clj-agent-core"
    :description "clj-agent Core - Semantic Kernel 风格 AI Agent 核心模块"}
   {:key :client
    :lib 'im.ttalk/clj-agent-client
    :dir "modules/clj-agent-client"
    :override-core? true
    :description "clj-agent Client - Agent runtime (client / ReAct loop / memory / subagent)"}
   {:key :provider
    :lib 'im.ttalk/clj-agent-provider
    :dir "modules/clj-agent-provider"
    :override-core? true
    :description "clj-agent LLM - LLM Provider Abstraction Module"}])

(def ^:private class-dir "target/classes")
(def ^:private src-dirs ["src"])

(defn- jar-file [{:keys [lib]}]
  (format "target/%s-%s.jar" (name lib) version))

(defn- basis [{:keys [override-core?]}]
  (if override-core?
    (b/create-basis {:project "deps.edn"
                     :extra {:aliases {:release {:override-deps {'im.ttalk/clj-agent-core
                                                                 {:mvn/version version}}}}}
                     :aliases [:release]})
    (b/create-basis {:project "deps.edn"})))

(defn- select
  ":module core|client|provider（也吃 clj-agent-core 这种全名）→ 单模块；缺省 = 全部。"
  [{:keys [module]}]
  (if-not module
    modules
    (let [k (-> module name (str/replace #"^clj-agent-" "") keyword)]
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

(def ^:private core-module (first (filter #(= :core (:key %)) modules)))

(defn- core-installed?
  "client/provider 的 basis 把 core 覆盖为 :mvn/version，故它们**打包前**就要求
   本地仓库里已有同版本 core——否则 create-basis 直接解析失败。"
  []
  (let [{:keys [lib]} core-module]
    (.exists (io/file (System/getProperty "user.home") ".m2" "repository"
                      (str/replace (namespace lib) "." "/") (name lib) version
                      (format "%s-%s.jar" (name lib) version)))))

;;; ============================================================
;;; 任务
;;; ============================================================

(defn clean
  "删除模块 target/。"
  [opts]
  (run-modules! opts "clean" clean*)
  opts)

(declare jar)

(defn- ensure-core-installed!
  "只打 client/provider（例如 `jar :module client`）时自动补齐 core，
   免得撞上「必须先 install core」这条隐式前提——旧的 build-all.sh 就栽在这。"
  [opts]
  (when (and (some :override-core? (select opts))
             (not (core-installed?)))
    (println (format "[prep] 本地 Maven 缺 %s %s，先补一次" (:lib core-module) version))
    (jar {:module :core})
    (run-modules! {:module :core} "install" install*)))

(defn jar
  "写 pom + 打 jar 到 modules/<m>/target/。"
  [opts]
  (ensure-core-installed! opts)
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
     (b/jar {:class-dir class-dir :jar-file (jar-file m)})))
  opts)

(defn install
  "装到本地 ~/.m2/repository。core 先行——client/provider 的 pom 依赖它。"
  [opts]
  (run-modules! opts "install" install*)
  opts)

(defn deploy
  "推 Clojars。deps-deploy 不认 *project-root*，故路径显式 resolve 成含模块前缀的形式。"
  [opts]
  (run-modules!
   opts "deploy"
   (fn [{:keys [lib] :as m}]
     (dd/deploy (merge {:installer :remote
                        :artifact (str (b/resolve-path (jar-file m)))
                        :pom-file (str (b/pom-path {:class-dir class-dir
                                                    :lib lib
                                                    :version version}))}
                       (dissoc opts :module)))))
  opts)

(defn release
  "发布前的完整本地流程。

   是**逐模块** clean → jar → install，而非三遍全量：client/provider 打包时
   basis 已把 core 换成 :mvn/version，故 core 必须在**它们打包之前**就落进 ~/.m2。
   先 jar 全部再 install 全部会在 fresh clone 上当场解析失败。"
  [opts]
  (doseq [m (select opts)]
    (let [one {:module (:key m)}]
      (clean one)
      (jar one)
      (install one)))
  (println (format "✓ %s 已构建并安装到本地 Maven" version))
  opts)
