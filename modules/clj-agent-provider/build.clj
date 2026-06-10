(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deploy-deps :as dd]))

(def lib 'im.ttalk/clj-agent-provider)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
;; 发布关键：core 在 deps.edn 中是 {:local/root ...}，write-pom 无法把本地路径写成
;; 合法 Maven 坐标 —— 生成的 pom 会缺失 core 依赖，消费方解析即断。这里在构建期把
;; core 覆盖为同版本号的 :mvn/version，使 pom 写出正确的 <dependency>。
;; （两个模块用同一 0.1.<git-count> 版本方案，逐提交对齐。）
(def core-coord {'im.ttalk/clj-agent-core {:mvn/version version}})
(def basis (b/create-basis {:project "deps.edn"
                            :override-deps core-coord}))
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
                :pom-data [[:description "clj-agent LLM - LLM Provider Abstraction Module"]
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
