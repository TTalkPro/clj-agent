(ns im.ttalk.agent.tools
  "clj-agent-tools 门面：一行拿到绑定好工作区的编码类工具（设计：designs/tools-module.md）。

   (require '[im.ttalk.agent.tools :as tools])
   (def ws (tools/workspace {:root \"/abs/repo\"}))
   (tools/workspace-tools ws {:groups #{:files :edit}})   ;; ⇒ [#'read_file …] 放进 :tools

   工具是 deftool 定义的 var；`workspace-tools` 为每个工具造一个**匿名 var**，根值是
   `(fn [args] (binding [ws/*workspace* ws] (f args)))`，元数据沿用 deftool 的（可按实例覆盖
   `:tool/sensitive` 与 description）。绑定发生在工具函数体内部——与哪个引擎 / 线程执行它无关
   （design-principles §3）。用 var 而不用内联工具 map，是因为 `:sensitive` 只对 var 生效
   （tool_registry/build-func-def），审批 filter 与 :on-pause 才看得见。

   组（缺省只开只读的 :files；写、删、跑进程、出网都要显式开）：
   - :files     read_file list_dir glob grep
   - :edit      write_file edit_file apply_patch（敏感、串行）
   - :delete    delete_path（敏感、串行）
   - :exec      exec exec_command write_stdin（敏感、串行；必须给 {:exec {:backend ..}}）
   - :web-fetch web_fetch（缺省敏感，{:web-fetch {:sensitive false}} 可关）
   - :skill     skill（{:skills {:dirs [..]}}）
   - :notes     notes_list notes_read notes_write（必须给 {:notes store}；{:notes-write-sensitive? true} 让写走审批）"
  (:require [im.ttalk.agent.tools.edit :as edit]
            [im.ttalk.agent.tools.exec :as exec]
            [im.ttalk.agent.tools.files :as files]
            [im.ttalk.agent.tools.notes :as notes]
            [im.ttalk.agent.tools.patch :as patch]
            [im.ttalk.agent.tools.skill :as skill]
            [im.ttalk.agent.tools.web :as web]
            [im.ttalk.agent.tools.workspace :as ws]))

(def groups
  "组 ⇒ 工具 var（deftool 原件，未绑定）。"
  {:files     [#'files/read_file #'files/list_dir #'files/glob #'files/grep]
   :edit      [#'files/write_file #'edit/edit_file #'patch/apply_patch]
   :delete    [#'files/delete_path]
   :exec      [#'exec/exec #'exec/exec_command #'exec/write_stdin]
   :web-fetch [#'web/web_fetch]
   :skill     [#'skill/skill]
   :notes     [#'notes/notes_list #'notes/notes_read #'notes/notes_write]})

(def all-groups (set (keys groups)))

(defn workspace
  "建工作区（选项见 `im.ttalk.agent.tools.workspace/workspace`）。已经是工作区 ⇒ 原样返回。"
  [opts]
  (ws/workspace opts))

(defn bind-tool
  "把一个 deftool var 绑到工作区：⇒ 新的匿名 var（元数据 = 原元数据 + overrides + :tools/workspace）。"
  ([v w] (bind-tool v w nil))
  ([v w overrides]
   (let [f @v
         nv (clojure.lang.Var/create (fn bound-tool [args] (binding [ws/*workspace* w] (f args))))]
     (alter-meta! nv merge (meta v) overrides {:tools/workspace w})
     nv)))

(defn- overrides-for [w v]
  (case (:name (:tool/schema (meta v)))
    "web_fetch" {:tool/sensitive (boolean (:sensitive (web/opts-of w)))}
    "notes_write" {:tool/sensitive (boolean (:notes-write-sensitive? w))}
    "skill" {:tool/schema (assoc (:tool/schema (meta v)) :description (skill/description w))}
    nil))

(defn workspace-tools
  "按组造工具 ⇒ 绑定好工作区的 tool var 向量，直接放进 build-chat-client / simple-agent 的 :tools。

   w-or-opts：工作区（`workspace` 的结果）或其选项 map（:root 等）。
   opts（可省，也可直接并在 w-or-opts 里）：
   - :groups  组集合（缺省 #{:files}；:all = 全部可用的组）
   - :only    只要这些工具名（字符串集合）
   - :exclude 不要这些工具名

   校验：开了 :exec 却没给 {:exec {:backend ..}} / {:exec {:runner ..}} ⇒ 抛；开了 :notes 却没给 :notes 存储 ⇒ 抛。"
  ([w-or-opts] (workspace-tools w-or-opts nil))
  ([w-or-opts opts]
   (let [w (ws/workspace (merge w-or-opts (dissoc opts :groups :only :exclude)))
         gs (let [g (or (:groups opts) (:groups w-or-opts) #{:files})]
              (if (= g :all)
                (cond-> (disj all-groups :exec :notes)
                  (or (get-in w [:exec :backend]) (get-in w [:exec :runner])) (conj :exec)
                  (:notes w) (conj :notes))
                (set g)))
         only (some-> (or (:only opts) (:only w-or-opts)) set)
         exclude (set (or (:exclude opts) (:exclude w-or-opts)))]
     (when-let [unknown (seq (remove all-groups gs))]
       (throw (ex-info (str "未知的工具组：" (vec unknown) "（可选：" (vec (sort all-groups)) "）") {:groups gs})))
     (when (and (gs :exec) (not (or (get-in w [:exec :backend]) (get-in w [:exec :runner]))))
       (throw (ex-info "开了 :exec 组却没给进程后端：须显式选 {:exec {:backend (exec/local-backend)}} 或沙箱后端 / :runner"
                       {:groups gs})))
     (when (and (gs :notes) (nil? (:notes w)))
       (throw (ex-info "开了 :notes 组却没给笔记存储：{:notes (notes/dir-store \"…\")}" {:groups gs})))
     (let [tools (for [g [:files :edit :delete :exec :web-fetch :skill :notes]
                       :when (gs g)
                       v (groups g)
                       :let [n (:name (:tool/schema (meta v)))]
                       :when (and (or (nil? only) (only n)) (not (exclude n)))]
                   (bind-tool v w (overrides-for w v)))]
       (when (empty? tools)
         (throw (ex-info "workspace-tools 一个工具都没造出来（检查 :groups / :only / :exclude）" {:groups gs})))
       (vec tools)))))

(defn workspace-of
  "从 workspace-tools 产出的 var 取回它绑定的工作区。"
  [tool-var]
  (:tools/workspace (meta tool-var)))

(defn preview
  "审批预览（出处 Keelson ApprovalPreview / 各工具 PreviewAsync）：与执行**同一个 plan 函数**（无副作用）。
   w：工作区（或 workspace-tools 产出的任一 var）；tool-name：字符串或关键字；args：工具参数 map。
   ⇒ {:title .. :diff ..}（会失败 ⇒ {:title .. :will-fail 原因}）；不是写类工具 ⇒ nil。

   典型用法：在 approval-filter 的 approve-fn 或 simple-agent 的 :on-pause 回调里调用，把 diff 给人看。"
  [w tool-name args]
  (let [w (if (var? w) (workspace-of w) (ws/workspace w))
        f (case (name tool-name)
            "write_file" files/preview-write
            "edit_file" edit/preview-edit
            "apply_patch" patch/preview-apply
            "delete_path" files/preview-delete
            nil)]
    (when f
      (try
        (f w (update-keys (or args {}) keyword))
        (catch clojure.lang.ExceptionInfo e
          (if (ws/failure-code e)
            {:title (str (name tool-name) " 会失败") :will-fail (ex-message e)}
            (throw e)))))))

(defn close!
  "收尾：结束工作区里的全部 exec 会话。⇒ 结束了几个。"
  [w]
  (exec/close-sessions! (if (var? w) (workspace-of w) w)))
