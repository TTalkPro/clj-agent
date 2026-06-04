(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deploy-deps :as dd]))

(def lib 'im.ttalk/clj-agent-provider)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def src-dirs ["src"])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs src-dirs
                :scm {:url "https://github.com/yourusername/clojure-in-actions"
                       :connection "scm:git:git://github.com/yourusername/clojure-in-actions.git"
                       :developerConnection "scm:git:ssh://git@github.com/yourusername/clojure-in-actions.git"
                       :tag (str "v" version)}
                :pom-data [[:description "clj-agent LLM - LLM Provider Abstraction Module"]
                           [:url "https://github.com/yourusername/clojure-in-actions"]
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
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :src-dirs src-dirs}))

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
