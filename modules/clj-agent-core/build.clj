(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'im.ttalk/clj-agent-core)
(def version (format "0.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def src-dirs ["src"])
(def scm-url "https://github.com/TTalkPro/clj-agent")

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs src-dirs
                :scm {:url scm-url
                       :connection "scm:git:git://github.com/TTalkPro/clj-agent.git"
                       :developerConnection "scm:git:ssh://git@github.com/TTalkPro/clj-agent.git"
                       :tag (str "v" version)}
                :pom-data [[:description "clj-agent Core - Semantic Kernel 风格 AI Agent 核心模块"]
                           [:url scm-url]
                           [:licenses
                            [:license
                             [:name "MIT"]
                             [:url "https://opensource.org/licenses/MIT"]]]
                           [:developers
                            [:developer
                             [:id "clj-agent"]
                             [:name "clj-agent team"]]]]})
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  ;; 注：b/install 必填 :class-dir（曾误传 :src-dirs → assert-required 报错，install 从未跑通）
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [opts]
  (dd/deploy (merge {:installer :remote
                      :artifact jar-file
                      :pom-file (b/pom-path {:class-dir class-dir
                                              :lib lib
                                              :version version})}
                     opts)))

(defn ci [_]
  (clean nil)
  (jar nil))
