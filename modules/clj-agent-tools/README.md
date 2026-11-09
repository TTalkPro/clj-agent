# clj-agent-tools

编码类 agent 工具（**可选模块**，只依赖 `clj-agent-core`）：一行拿到一组绑定好工作区的 `deftool` 工具 var，
直接放进 `:tools`。移植自 Keelson `Keelson.Agent.Tools`（其中一部分口径来自 OpenAI Codex CLI，署名见
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)）。设计与取舍见 `designs/tools-module.md`。

```clojure
(require '[im.ttalk.agent.tools :as tools]
         '[im.ttalk.agent.tools.exec :as exec]
         '[im.ttalk.agent.tools.notes :as notes])

(def ws (tools/workspace {:root "/abs/path/to/repo"
                          :exec {:backend (exec/local-backend)}      ;; 开 :exec 必须显式选后端
                          :notes (notes/dir-store "/abs/path/to/notes")}))

(def coding-tools
  (tools/workspace-tools ws {:groups #{:files :edit :delete :exec :notes}}))

;; 放进 simple-agent / build-chat-client 的 :tools；敏感工具（写 / 删 / exec）自动被
;; approval-filter 与 :on-pause 拦下。审批时给人看 diff（与执行同一个 plan，不落盘）：
(tools/preview ws "edit_file" {:path "src/a.clj" :old "(inc x)" :new "(dec x)"})
;; => {:title "编辑 /abs/.../src/a.clj" :diff "@@ -3 +3 @@\n-  (inc x)\n+  (dec x)\n✓ 改完之后语法没问题\n"}

(tools/close! ws)   ;; 收尾：结束 exec 会话
```

## 工具

| 组 | 工具 | 敏感 | 要点 |
|---|---|---|---|
| `:files`（缺省） | `read_file` `list_dir` `glob` `grep` | | 行号 / 分页 / 缩进块；`.gitignore`；有 `rg` 用 `rg` |
| `:edit` | `write_file` `edit_file` `apply_patch` | ✓ | 读写新鲜度闸；精确替换；Codex 补丁文法、全部推演通过才落盘；「不更糟」语法闸 |
| `:delete` | `delete_path` | ✓ | 非空目录要 `recursive`；链接只删链接；根拒删 |
| `:exec` | `exec` `exec_command` `write_stdin` | ✓ | 固定环境 `NO_COLOR/TERM=dumb/PAGER=cat`、超时 124、头尾保留；有状态会话 + LRU |
| `:web-fetch` | `web_fetch` | ✓（可关） | JDK HttpClient；每跳重定向过 `:allow-url`；体积上限；HTML → 文本 |
| `:skill` | `skill` | | `{:skills {:dirs [..]}}` 下的 `*/SKILL.md` |
| `:notes` | `notes_list` `notes_read` `notes_write` | 写可开 | `INotesStore`：`dir-store` / `memory-store` |

## 命名空间

| ns | 干什么 |
|---|---|
| `im.ttalk.agent.tools` | 门面：`workspace` / `workspace-tools` / `preview` / `close!` / `bind-tool` |
| `im.ttalk.agent.tools.workspace` | `IPathPolicy`（`workspace-policy`：根 / 只读根 / 受保护名 / 符号链接逃逸）、`IFileLayer`（原子写）、`fail!` |
| `im.ttalk.agent.tools.files` | `read_file` `list_dir` `glob` `grep` `write_file` `delete_path` |
| `im.ttalk.agent.tools.edit` | `edit_file` |
| `im.ttalk.agent.tools.patch` | `apply_patch` + 补丁解析 / 匹配 / 内存应用引擎 |
| `im.ttalk.agent.tools.exec` | `exec` `exec_command` `write_stdin`；`IExecBackend` / `ICommandRunner`（`argv-prefix-backend` 接 bwrap）、HeadTailBuffer |
| `im.ttalk.agent.tools.web` | `web_fetch` |
| `im.ttalk.agent.tools.skill` | `skill` |
| `im.ttalk.agent.tools.notes` | `notes_*` + `INotesStore` |
| `im.ttalk.agent.tools.syntax` | `ISyntaxChecker` + JSON / EDN·Clojure / XML 检查器 + 「不更糟」判断 |
| `im.ttalk.agent.tools.freshness` | 已读表（内容 tag，FNV-1a 32） |
| `im.ttalk.agent.tools.text` | 内容 tag、按原样编解码（换行 / BOM / GBK）、统一 diff |
| `im.ttalk.agent.tools.walk` | glob、`.gitignore` 子集、目录遍历 |

## 沙箱

`exec` 只经 `exec/ICommandRunner`、会话只经 `exec/IExecBackend` 起进程。把命令包进 bwrap：

```clojure
(exec/argv-prefix-backend (exec/local-backend)
                          ["bwrap" "--ro-bind" "/" "/" "--bind" "/abs/path/to/repo" "/abs/path/to/repo" "--unshare-net"])
```

**依赖**：`clj-agent-core`；cheshire（JSON 语法检查）。HTTP / XML / 进程全走 JDK，零新依赖。
