# clj-agent-process

Process Framework —— 事件驱动的步骤编排引擎（对标 Semantic Kernel Process / beamai process）。

**V1：纯函数式同步 runtime**（零外部依赖、确定性执行、易测试）。
V2（core.async 并行化：fan-out 真并行、外部事件、ProcessHandle）见
[并行化设计](../../docs/process-parallel-design.md)，尚未实施。

## 模型

- **Step**：`{:id :init :can-activate? :on-activate :on-resume :on-terminate :required-inputs :config}`
- **事件驱动**：step 产出事件 → bindings 路由 → 投递到目标 step 的输入槽 → required-inputs 收齐（+ 守卫通过）才激活
- **两层状态**：step 私有 `state`（`:init` 创建、返回 `:state` 推进）+ 全 process 共享 `context`（core 的 ToolContext）
- **支持模式**：线性 / Fan-out / Fan-in / 循环（自环事件 + `:terminate` 退出）/ Human-in-the-loop（pause/resume）/ error-handler / 快照恢复

## 用法

```clojure
(require '[im.ttalk.agent.process.builder :as pb]
         '[im.ttalk.agent.process.runtime :as rt]
         '[im.ttalk.agent.context :as ctx])

(def spec
  (-> (pb/builder :doc-generation)
      (pb/add-step {:id :gather
                    :on-activate (fn [inputs state ctx]
                                   {:events [{:name :info-gathered :data (fetch (:input inputs))}]})})
      (pb/add-step {:id :generate
                    :on-activate (fn [inputs state ctx]
                                   {:context (ctx/set-var ctx :doc (render (:input inputs)))
                                    :events []})})
      (pb/on-event :start :gather)
      (pb/on-event :info-gathered :generate)
      (pb/set-initial-event :start "GlowBrew")
      (pb/build)))

(rt/run-process spec {:context (ctx/create {:kernel my-kernel})   ;; step 内可取 kernel 调工具
                      :on-quiescent (fn [snapshot] (save! snapshot))})
;; => {:status :completed :context ... :steps-state ...}
```

### Pause / Resume

```clojure
(let [paused (rt/run-process approval-spec)]
  (when (rt/paused? paused)
    (rt/resume paused "approved")))    ;; 交给暂停 step 的 :on-resume
```

### 快照恢复

`:on-quiescent` 在安全时机（一批 step 执行完 / 暂停）给出快照；恢复时把
`:step-states` + `:context` 传回 `run-process`，用 `:initial-events` 驱动后续步骤。

## Timeline / Snapshot（存档 + 时间旅行）

`on-quiescent` 的快照可交给 Timeline 管理——版本链 / 时间旅行 / 分支实验 / 跨重启续跑：

```clojure
(require '[im.ttalk.agent.timeline :as tl]
         '[im.ttalk.agent.timeline.sqlite :as tls]
         '[im.ttalk.agent.process.snapshot :as snap])

(def mgr (tl/manager (tls/sqlite-store "timeline.db")))   ;; 或 (tl/in-memory-store)

;; 自动存档：每个静止点/暂停点落一版
(rt/run-process spec {:on-quiescent (snap/checkpointer mgr "session-1")})

;; 断点续跑（跨进程重启；context 里的 :kernel 存档时自动剥离，恢复时注回）
(let [cp (snap/latest-checkpoint mgr "session-1")]
  (when (snap/paused-checkpoint? cp)
    (snap/resume-checkpoint spec cp "approved" {:context-extras {:kernel k}})))

;; 时间旅行 / 分支实验
(tl/go-back! mgr "session-1" 2)
(snap/branch! mgr "session-1" (:id cp) "exp")
(rt/run-process spec (merge (snap/restore-opts cp)
                            {:initial-events [{:name :add :data 100}]
                             :on-quiescent (snap/checkpointer mgr "session-1")}))
(tl/switch-branch! mgr "session-1" "main")   ;; main 时间线不受实验污染
```

已知约定：快照不含事件队列（暂停点续跑完全由 `on-resume` 产出的事件驱动）；
SQLite store 走 EDN，context 须可序列化（`:kernel` 由 checkpointer 缺省剥离）。

## 命名空间

| 命名空间 | 职责 |
|---------|------|
| `im.ttalk.agent.process.builder` | Builder API + build 时校验 |
| `im.ttalk.agent.process.runtime` | 同步事件循环引擎（run-process / resume / resume-from-snapshot） |
| `im.ttalk.agent.process.event` | 事件创建与路由 |
| `im.ttalk.agent.process.step` | step 状态与激活判定 |
| `im.ttalk.agent.process.snapshot` | Process × Timeline 适配（checkpointer / 恢复 / 分支） |
| `im.ttalk.agent.timeline` | 通用版本链 / 时间旅行 / 分支管理 |
| `im.ttalk.agent.timeline.sqlite` | Timeline 的 SQLite store（按需加载） |

依赖：`clj-agent-core`（ToolContext / kernel 原语）；next.jdbc + sqlite-jdbc（timeline.sqlite，按需）。

## 测试

```bash
clojure -M:test
```
