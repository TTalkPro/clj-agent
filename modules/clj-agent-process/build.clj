(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'im.ttalk/clj-agent-process)
(def version (format "0.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
;; 发布关键：core 在 deps.edn 中是 {:local/root ...}，write-pom 无法把本地路径写成
;; 合法 Maven 坐标 —— 生成的 pom 会缺失 core 依赖，消费方解析即断。这里在构建期把
;; core 覆盖为同版本号的 :mvn/version，使 pom 写出正确的 <dependency>。
;; （各模块用同一 0.2.<git-count> 版本方案，逐提交对齐。）
(def core-coord {'im.ttalk/clj-agent-core {:mvn/version version}})
;; 注：create-basis 没有顶层 :override-deps 参数（曾直接传 → 被静默忽略，
;; pom 仍缺 core 依赖）。override-deps 必须放进 alias 并经 :aliases 启用。
;; 前提：构建本模块前须先 install core（见 scripts/install-all.sh 的顺序）。
(def basis (b/create-basis {:project "deps.edn"
                            :extra {:aliases {:release {:override-deps core-coord}}}
                            :aliases [:release]}))
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
                :pom-data [[:description "clj-agent Process - Process framework (event-driven step orchestration)"]
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
