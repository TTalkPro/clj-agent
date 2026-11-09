# Third-party notices — clj-agent-tools

## OpenAI Codex CLI

- 上游：<https://github.com/openai/codex>
- 版本：提交 `4bad6d78e9`（已删工具取自其历史：`14c35a16a8^`、`178c3b15b4^`、`70807730f5^`、`e783341b70^`、`83decfa300^`）
- 许可证：Apache-2.0，原文见同目录 [`LICENSE-codex.txt`](LICENSE-codex.txt)。上游 NOTICE：

  > OpenAI Codex
  > Copyright 2025 OpenAI

- 转写路径：Codex（Rust）→ Keelson `src/Keelson.Agent.Tools`、`src/Keelson.Text/Patch`（C#，其 THIRD-PARTY-NOTICES.md 同样署名）→ 本模块（Clojure）。
- 范围（逐文件在源码 ns 文档里注明出处）：
  - `im/ttalk/agent/tools/files.clj`：`read_file`（`codex-rs/core/src/tools/handlers/read_file.rs`：slice / indentation 两种模式、常量、错误句）、
    `list_dir`（`handlers/list_dir.rs`）、`grep`（`handlers/grep_files.rs` 的参数、常量与输出口径）；
  - `im/ttalk/agent/tools/patch.clj`：补丁解析器（`codex-rs/apply-patch/src/parser.rs`、`streaming_parser.rs`）、
    上下文匹配（`seek_sequence.rs`）、内存应用（`text_file.rs`、`file_update.rs`、`lib.rs`）、工具流程（`core/src/tools/handlers/apply_patch.rs`）
    与工具说明里的补丁文法（`core/src/tools/handlers/apply_patch_spec.rs @ e783341b70^`）；
  - `im/ttalk/agent/tools/exec.clj`：`HeadTailBuffer`（`core/src/unified_exec/head_tail_buffer.rs`）、单发执行常量
    （`core/src/exec.rs`：时限 10 s、超时 124、杀后排空 2 s、1 MiB）、固定环境（`unified_exec/process_manager.rs` 的 `UNIFIED_EXEC_ENV`）、
    会话管理（`unified_exec/{mod.rs, process_manager.rs}`：yield 口径、LRU 驱逐、中断）、结果文本（`core/src/tools/context.rs`、`core/src/tools/mod.rs`）；
  - `im/ttalk/agent/tools/workspace.clj`：受保护名（`codex-rs/protocol/src/permissions.rs` 的 `PROTECTED_METADATA_PATH_NAMES`）。

### 本仓库所做的修改

C# 翻译为 Clojure；文件只经 `IFileLayer` 读写、每个路径先过 `IPathPolicy`；相对路径按宿主给的工作目录解析（上游只收绝对路径）；
面向模型的文案改为中文；`grep_files` 改名 `grep`，有 `rg` 时起 `rg` 子进程（结果再过路径策略），否则用 `java.util.regex`（带匹配超时）
+ 自带的 `.gitignore` 子集；新增 `glob`（上游没有）；`read_file` 的 indentation 参数摊平为顶层参数。
进程工具只经 `IExecBackend` / `ICommandRunner` 起进程、不做 PTY（会话是管道，stdin 可写）；单发 `exec` 取已删 `shell` 工具的 argv 形状、
输出改为头尾保留；固定环境不带 `CODEX_CI`；会话不做网络审批 / stdin 复核 / 暂停；用户 shell 取 `SHELL` 环境变量。
补丁引擎不碰 IO：先在内存推演整份补丁，任一文件对不上则一个也不写。

## coxswain

- 仓主自己的仓库，Apache-2.0。经 Keelson 转写借鉴：读写新鲜度与内容 tag（`seen.ss`、`tag.ss`）、`edit_file` / `write_file`（`edit.ss`、`file.ss`）、
  `delete_path`（`delete.ss`）、`web_fetch`（`fetch.ss`）、`skill`（`skill.ss`）、笔记（`memory.ss`）、「不更糟」语法闸（`check.ss`）。
