# clj-agent-tools：编码类 agent 工具模块

> 状态：✅ 已实施（2026-11-08，T-01～T-07）
> 出处：Keelson `src/Keelson.Agent.Tools/*`（A-40～A-43、A-51～A-56）、`src/Keelson.Agent/Tools/{FileReadTable,FileLayer,ApprovalPreview}.cs`、
> `src/Keelson.Text/{ContentTag.cs,Patch/*}`、`src/Keelson.Core/Workspace/WorkspacePathPolicy.cs`。
> 任务：[T-01](../tasks/T-01.md) … [T-07](../tasks/T-07.md)。

## 0. 一句话

给 clj-agent 一个**可选**模块：一行 `(tools/workspace-tools {:root "/repo" :groups #{:files :edit}})`
拿到一组 `deftool` 工具 var（`read_file` / `write_file` / `edit_file` / `apply_patch` / `list_dir` / `glob` /
`grep` / `delete_path` / `exec` / `exec_command` / `write_stdin` / `web_fetch` / `skill` / `notes_*`），
直接放进 `:tools`。所有碰文件的工具先过**工作区路径策略**，写类工具过**读写新鲜度闸**与**「不更糟」语法闸**，
敏感工具用 `deftool {:sensitive true}` 标注，于是现有的 `approval-filter` / `:on-pause` 原样生效。

## 1. 动机（真实场景）

clj-agent 的使用者（本仓库的 examples、Keelson 侧的 Java/Clojure 宿主、strake 等）要做的第一件事几乎总是
「让 agent 改代码」。现状下每个宿主都在手写同一套工具，而手写版本反复踩同一批坑——这些坑 Keelson 已经一条条付过学费：

| 坑 | 真实后果 | Keelson 的解 |
|---|---|---|
| 路径不设根，或只做字符串前缀判断 | `../../etc/passwd`、经工作区里的符号链接写到根外 | `WorkspacePathPolicy`：字面 + 解链接两口径 |
| 模型对着没读过 / 读完被改过的文件改 | 静默覆盖用户刚做的修改 | A-51 `ReadFreshness` + `FileReadTable` |
| 「替换第一处匹配」 | 改错地方且模型无从察觉 | `edit_file` 不唯一就拒 + 0 匹配三种诊断 |
| 多文件补丁写到一半失败 | 仓库停在半改状态 | `apply_patch` 先在内存推演整份 |
| 命令输出 1 MB 灌进上下文 / 分页器卡住 | 上下文爆掉、命令挂死 | HeadTailBuffer + 固定环境 `PAGER=cat` + 时限 |
| `curl` 整页 HTML | `<script>` 把输出预算吃光 | `web_fetch` 抽正文 |
| 改出一个括号不配平的 JSON / XML | 下一步构建才发现 | A-56「不更糟」语法闸 |

按 design-principles §1.2 四问：现在有人要用（每个编码 agent 宿主）；不建则每个宿主各写一遍且大概率漏掉上表的坑
（「代价高到不可接受」——静默覆盖用户文件）；换来的是能力（新鲜度闸、原子补丁不是「更声明式」）；触发条件写得出。

## 2. 模块边界：为什么不进 core

- **core 零依赖、零 I/O 立场**（`modules/clj-agent-core/deps.edn` 注释：「core 只含 protocol/契约 + chat-client 原语」）。
  这些工具要碰文件系统、起子进程、出网、依赖 cheshire（JSON 语法检查），放进 core 会把所有只想要 ChatClient 的使用者一并拖进来。
- **不是每个 agent 都是编码 agent**：客服 / RAG / 工作流 agent 用不上 `apply_patch`，按 §1「用不上的人不该为它付 API 面」。
- **依赖方向**：`clj-agent-tools → clj-agent-core`（只用 `deftool` 宏与 `tool/*` 元数据约定），**不依赖 client**——工具不认识
  react 循环、memory、pause；它们只是带元数据的函数。与 `clj-agent-mcp`「产出普通工具」的立场一致。
- 外部依赖只有 `cheshire`（T-07 的 JSON 检查器要 Jackson 的位置信息；cheshire 已是 client/provider/mcp 的依赖，不算新依赖）。
  HTTP 走 JDK `java.net.http`，XML 走 JDK SAX，进程走 `ProcessBuilder`——**零新依赖**。

## 3. 工具如何绑定工作区（关键形状）

### 3.1 现状

- `deftool`（`modules/clj-agent-core/src/im/ttalk/agent/tool.clj:172`）生成**固定函数体**的 var，元数据里放 schema 与
  `:tool/sensitive` / `:tool/serial` 等声明。
- **`:sensitive` 只对 var 工具生效**：`tool_registry.clj:76-88` 的 `build-func-def` 对内联工具（map + `:handler`）恒给
  `:sensitive false`。于是审批 filter（`filter.clj:565` `approval-filter`）与 `simple_agent.clj:229` 的 `:on-pause` 都看不到内联工具的敏感标注。

结论：工具**必须是 var**（否则敏感标注失效），但工具又要**按实例**绑定工作区根、已读表、会话表——固定函数体的 deftool 做不到闭包。

### 3.2 方案：deftool 定义 + 每实例造匿名 var

```clojure
(deftool read_file "…" [[file_path :string "…"] …]
  (read-file* (ws/current) …))          ;; 函数体只读动态变量 ws/*workspace*

;; workspace-tools 为每个工具造一个新的匿名 var：
;;   root = (fn [args] (binding [ws/*workspace* ws] (f args)))
;;   meta = deftool 的元数据（可按实例覆盖 :tool/sensitive、:tool/schema 的 description）
```

- 绑定发生在**工具函数体内部**，与执行它的线程 / 引擎无关 → 满足 design-principles §3「边界内一致」：同一个工具无论被
  哪个 TCM、批内并行还是串行执行，看到的都是同一个工作区；也没有任何环境状态从外面「流进」工具（§3「边界外不流通」）。
- 直接调用未绑定的 deftool var（`(read_file {...})`）会得到明确错误「工具未绑定工作区」，不会静默用 `nil` 根。
- 匿名 var 的元数据带 `:tools/workspace`，宿主拿它做审批预览（§7）。
- `web_fetch` 的 `:sensitive` 可按实例关（`{:web-fetch {:sensitive false}}`）、`notes_write` 可按实例开；`skill` 的
  description 按实例扫描技能目录生成——都靠覆盖匿名 var 的元数据实现，deftool 本身不变。

### 3.3 不做什么

- 不改 `deftool` 宏、不给 core 加「工具工厂」概念（§1：一个模块内部 30 行的 `bind-tool` 就够，用不着永久 API 面）。
- 不让内联工具支持 `:sensitive`——那是 core 的事，且本模块用 var 已绕开（若协调者认为 core 应补，见完成报告）。

## 4. 路径策略 / 工作区根（T-01）

出处：`Keelson.Core/Workspace/WorkspacePathPolicy.cs`、`WorkspaceTools.cs` 的 `WorkspaceAccess.TryResolve`。

- `IPathPolicy` 协议：`(check-path policy ^Path full access)` → `nil`（放行）| `{:code .. :message ..}`（拒绝）。
  `access` ∈ `:read` / `:write`。宿主可自带实现；确实不限时显式给 `(ws/unrestricted-policy)`。
- 缺省 `workspace-policy`：可写根 `:root`/`:roots`，只读根 `:read-only-roots`，受保护名 `.git .agents .codex .aws`
  （可写根下这些**顶层**目录只读，照 Codex `PROTECTED_METADATA_PATH_NAMES`）。判定在两个口径上都做：
  1. **字面**：`..` 折叠后必须落在某个根下（最深的根说了算，只读子树盖过外层可写根）；
  2. **解链接后**：逐段解开已存在的符号链接（含悬空链接，按其目标继续解；40 跳判环）——经链接逃到根外 → `workspace/symlink-escape`；
     经链接写进受保护目录 / 只读根同样拒。
- 相对路径按工作目录 `:cwd`（缺省第一个可写根）解析；`:cwd` 为 `nil` 时只收绝对路径。
- 写入：**原子写**——同目录临时文件 + `ATOMIC_MOVE`（不支持时退回 `REPLACE_EXISTING`），保留原文件 POSIX 权限，
  父目录不存在就建；写的是**解链接后的真实路径**（链接指向根内文件时更新目标而不是把链接换成普通文件）。
  写前策略再问一次（防验证后被换成链接，照 Codex `no_follow`）。
- `IFileLayer` 协议（出处 `FileLayer.cs`）：读写类工具只经它碰文件（`entry` / `open-in` / `write-bytes!` / `delete!`），
  缺省 `local-file-layer`。暂存层（`FileStaging.cs`）**不移植**：它要求「整轮结束一次审阅后提交」的回合钩子与 UI，
  属于宿主 / runtime 的事；协议留了缝，宿主可以自己装饰一层。

## 5. 工具清单（T-02～T-06）

| 工具 | 组 | 敏感 | 串行 | Keelson 出处 | 要点 |
|---|---|---|---|---|---|
| `read_file` | `:files` | | | `ReadFileTool.cs`（Codex read_file） | `L{n}: ` 前缀、offset/limit 分页（缺省 2000 行）、单行 500 字节截、indentation 模式；输出头 `[路径#TAG]` 并登记已读表 |
| `list_dir` | `:files` | | | `ListDirTool.cs`（Codex list_dir） | BFS 至多 depth 层、排序、分页、`/` `@` `?` 标记 |
| `glob` | `:files` | | | `GlobTool.cs` | JDK glob（`*` 不跨目录、`**`、`{a,b}`），认 `.gitignore`/`.ignore`，新改的在前 |
| `grep` | `:files` | | | `GrepTool.cs`（Codex grep_files） | 有 `rg` 用 `rg --files-with-matches`（结果再过路径策略），否则 JVM 实现（正则带超时、跳二进制、认忽略规则） |
| `write_file` | `:edit` | ✓ | ✓ | `WriteFileTool.cs` | 新建直接写；覆盖要过新鲜度闸，按原换行/编码/BOM 写回 |
| `edit_file` | `:edit` | ✓ | ✓ | `EditFileTool.cs` `EditableText.cs` | 精确原文；不唯一拒（列行号）；0 匹配三种诊断；`all=true` 全换 |
| `apply_patch` | `:edit` | ✓ | ✓ | `ApplyPatchTool.cs` + `Keelson.Text/Patch/*`（Codex apply-patch） | Codex 补丁文法；解析 → 全部路径过写策略 → 新鲜度 → 内存推演 → 语法闸 → 才落盘 |
| `delete_path` | `:delete` | ✓ | ✓ | `DeletePathTool.cs` | 非空目录要 `recursive`；链接只删链接；根及其祖先拒删；不存在算成功 |
| `exec` | `:exec` | ✓ | ✓ | `Process/CommandRunner.cs` `ExecOutputFormat.cs` | argv、缺省 10 s、超时码 124、stdin 关闭、输出头尾保留 |
| `exec_command` / `write_stdin` | `:exec` | ✓ | ✓ | `Process/ExecSessionManager.cs` `ProcessTools.cs` | 有状态会话：yield 收集、轮询、`\u0003` 中断、LRU 驱逐（64 个、最近 8 个保护、先驱逐已退出的） |
| `web_fetch` | `:web-fetch` | ✓（可关） | | `WebFetchTool.cs` | 手动跟随重定向且每跳过 `:allow-url`、体积上限按实读字节、HTML→文本 |
| `skill` | `:skill` | | | `SkillTool.cs` | 技能目录下 `*/SKILL.md`，frontmatter `name`/`description`，description 里列目录 |
| `notes_list` / `notes_read` / `notes_write` | `:notes` | 写可开 | 写 ✓ | `NotesTools.cs` | `INotesStore` 协议 + 目录 / 内存两个实现；名字不能拿来拼路径逃出去 |

缺省组 `#{:files}`（只读）：写、删、跑进程、出网都要显式开——与 Keelson `WorkspaceToolGroups` 缺省一致。
开 `:exec` 必须给 `{:exec {:backend ..}}` 或 `{:exec {:runner ..}}`（⛔ 缺省后端：宿主显式选 `(exec/local-backend)` 或沙箱后端），否则装配期抛。

**错误约定**：业务失败一律 `throw (ex-info msg {:error-class :semantic :code "tools/…"})`。`tool/invoke` 把它变成
`{:success false :error msg}`，模型看到「错误: …」并能改参数重试；`:code` 留在 ex-data 里给宿主按码判别（不比对文案）。

**文案**：面向模型的说明与报错用中文（与 Keelson 中性语言资源一致；clj-agent 自身报错也是中文）。

## 6. 读写新鲜度（T-03）与语法闸（T-07）

- 已读表（`FileReadTable.cs`）：每个 `workspace-tools` 实例一张 `atom {真实路径 → tag}`；tag = FNV-1a 32 位、8 位大写十六进制
  （`ContentTag.cs`，测试向量 `"hello"` → `4F9F2CAB`）。**登记点 = 凡把 tag 交给模型的地方**：`read_file` 头、写类工具回的「新的 TAG」。
  写已有文件前：没读过 → 拒；读后变了 → 拒（附两个 tag）；新建不要求读过。`{:read-tracking? false}` 关闭。
  宿主要按会话隔离时传 `{:reads (freshness/read-table)}`。
- 语法闸（`SyntaxGuard.cs`）：`ISyntaxChecker` 协议 `handles?` / `check`；`check` 返回 `nil`（看不了）与 `[]`（看过没问题）是两件事。
  判据是**更糟**：before 看得了且 after 问题数更多才拒（0→1、1→2 拒；1→1、3→1 放），只查 after 会把修复它的编辑也拦掉。
  内置：JSON（Jackson/cheshire，`.jsonc`/`tsconfig*.json`/`.vscode/*.json` 允许注释与尾逗号）、EDN 与 Clojure 源码（Clojure 读取器，
  `EOF while reading, starting at line N` 正好给出未闭合位置；`::alias/kw` 之类读取器判不了的写法回 `nil`）、XML（JDK SAX，禁 DTD；带 `<!DOCTYPE` 回 `nil`）。
  宿主 `{:syntax {:checkers [...]}}` 追加的排在内置前面。检查器抛异常 = 看不了 = 放行。
- **BracketScan.cs 不移植**：它是 1262 行、按 C/C#/Java/JS/TS/Go/Rust/Kotlin/Lisp/Python 逐语言手写的词法跳过器，正确性靠每种
  语言的边角（C# 原始插值字符串、JS 正则与除号歧义、Rust 生命周期 vs 字符……）堆出来；而 clj-agent 的主要宿主写 Clojure，
  Clojure 读取器已经给出更准的结果（还带起始行号）。按 §1 四问：其余语言的需求今天写不出具体调用方，宿主要时用协议自己接 tree-sitter 等。

## 7. 与审批的关系（ApprovalPreview 的适合部分）

- 敏感工具都声明 `{:sensitive true}` → `approval-filter`（`filter.clj:565`）与 `simple-agent` 的 `:on-pause`（`simple_agent.clj:229`）
  无需任何改动即可拦截。写类工具同时 `{:serial true}`，批内并行时整批退化为按序执行（避免两次写同一文件交错）。
- 审批**预览**（`ApprovalPreview.cs` / 各工具 `PreviewAsync`）：`(tools/preview ws "edit_file" args)` → `{:title .. :diff ..}` 或
  `{:title .. :will-fail msg}`。与执行**调同一个 plan 函数**（无副作用），所以「预览说能改、批准后被拒」不会因两份判据漂移而发生；
  diff 尾巴带语法闸的同一次判断（✓ / ⚠️）。宿主在 `approval-filter` 的 `approve-fn` 或 `:on-pause` 回调里调用它。
- 会话级「本会话都批准」缓存（`ApprovalCache.cs`）、审批键 **不移植**：那是审批机制（core/client）的事，不是工具的事。

## 8. exec 与沙箱（T-05）

- 两个协议（出处 `CommandRunner.cs` 的 `ICommandRunner` / Keelson.Workspace 的 `IExecBackend`）：
  - `IExecBackend`：`(start-process backend {:argv :cwd :env})` → 进程句柄（`java.lang.Process` 或同形）。缺省 `local-backend`（`ProcessBuilder`）。
    `(exec/argv-prefix-backend base ["bwrap" "--ro-bind" "/" "/" …])` 把每条命令包进前缀——宿主接 bwrap / firejail / ssh 就这一行。
  - `ICommandRunner`：`(run-command runner req)` 单发跑完再返回，可在这里做「沙箱里失败 → 问人 → 不带沙箱重跑」之类的编排。
    缺省 `backend-runner`。
- 固定环境 `NO_COLOR=1 TERM=dumb LANG/LC_*=C.UTF-8 COLORTERM= PAGER=cat GIT_PAGER=cat GH_PAGER=cat`（Codex `UNIFIED_EXEC_ENV`），
  宿主 `:env` 再叠加（值 `nil` = 删除）。不带 Codex 的 `CODEX_CI`。
- 超时：杀**进程树**（`ProcessHandle.descendants`），等管道至多 2 s 收尾（孙进程占着管道也不挂死），退出码 124。
- `Keelson.Agent.Sandbox` 不移植（任务约定）。

## 9. 平台差异

| 点 | Linux / macOS | Windows |
|---|---|---|
| 路径比较 | `Path.startsWith`（macOS 的 APFS 默认不分大小写，但 JDK `UnixPath` 区分——大小写不同的写法会被当成根外而**拒**，失败方向安全） | `WindowsPath` 本身不分大小写 |
| 原子写 | `ATOMIC_MOVE` | 目标被占用时可能失败 → 报错不写（不会半写） |
| 权限保留 | POSIX 权限复制 | 无 POSIX 视图，跳过 |
| `exec_command` shell | `$SHELL`（缺省 `/bin/bash`）`-lc` / `-c` | `powershell.exe -NoProfile -Command` |
| `\u0003` 中断 | `kill -INT <pid>` | 不支持 → 报错 |
| `grep` | 有 `rg` 优先 | 同左；`rg` 用 Rust regex 语法，JVM 回落用 Java regex——两者在环视 / 反向引用上不同（`rg` 不支持环视），说明里写明 |

## 10. 不做什么（否决记录）

- **FileStaging 暂存层**：需要回合级提交钩子与审阅 UI（§4）。重启条件：runtime 有「turn 结束」钩子且有宿主要做整轮审阅。
- **BracketScan**（§6）。重启条件：出现非 Clojure 语言的宿主且明确要括号闸。
- **审批键 / 会话审批缓存**（§7）。
- **PTY**（Keelson 立法 46 同样不做）：会话是管道，`tty` 参数不提供。
- **view_image / update_plan / request_user_input / current_time**（`SmallTools.cs`）：依赖多模态输入 / 事件流 / 问用户通道，
  分别是 provider 与 runtime 的事；`current_time` 宿主一行 deftool 即可。
- **GBK 之外的遗留编码**：只认 UTF-8（含 BOM）、带 BOM 的 UTF-16/32、严格 GBK（编回去逐字节相同才认），其余拒改（⛔ 猜一个写回去）。

## 11. 与 design-principles 的关系

- §1：每项都过了四问（§1 动机表）；否决项写了重启条件（§10）。`bind-tool` 留在模块内，不给 core 加 API 面。
- §2：零 web 依赖；`web_fetch` 只用 JDK HttpClient。
- §3：工作区绑定在工具函数体内部建立，与引擎 / 线程无关；子 agent 想要工具就自己 `workspace-tools`（已读表随实例，不从父 agent 流入）。

## 12. 测试方案

全部单元测试、临时目录（`Files/createTempDirectory`），不依赖 API key / 网络：

- 路径策略：越界、`..`、符号链接逃逸（含悬空链接）、受保护名、只读根、链接环。
- 每个工具至少一组正反例；`apply_patch` 搬 Codex 的典型用例（Add/Delete/Update/Move、`@@` 上下文、EOF、heredoc 宽松、任一对不上则零写入）。
- 新鲜度：没读过拒、读后外部修改拒、写后新 tag 可连续编辑。
- exec：超时 124、HeadTailBuffer 头尾与省略计数、固定环境变量可见、会话轮询 / stdin / 驱逐、argv 前缀后端。
- web_fetch：JDK `com.sun.net.httpserver.HttpServer` 起本地服务，覆盖重定向 + 允许钩子、体积上限、HTML→文本、非 2xx。
- 语法闸：JSON/EDN/XML 的 0→1 拒、1→1 放、`nil` 放行、预览尾巴。
- 端到端：`workspace-tools` 产出的 var 放进 `build-chat-client`，经 `chat-client/invoke-tool` 调用，`:sensitive` 进入 ToolRequest。

## 13. 署名

`read_file` / `list_dir` / `grep` 的口径、`apply_patch` 解析器与应用器、`HeadTailBuffer`、exec 常量与固定环境、会话管理器口径
翻译自 **OpenAI Codex CLI**（Apache-2.0，Copyright 2025 OpenAI），经 Keelson 的 C# 翻译再转 Clojure。按 Apache-2.0 §4，
模块根放 `LICENSE-codex.txt`（许可证原文）与 `THIRD-PARTY-NOTICES.md`（上游、范围、修改说明），相关源文件头注明出处。
其余（新鲜度、edit_file、write_file、delete_path、web_fetch、skill、notes、语法闸）借鉴 coxswain（仓主自己的仓库，Apache-2.0）经 Keelson 转写。
