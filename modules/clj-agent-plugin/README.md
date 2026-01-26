# clj-agent-plugin

预置插件库 - 文件操作、HTTP 请求、Shell 命令

[English](#english) | 中文

## 概述

`clj-agent-plugin` 提供常用的预置工具插件，可直接注册到 Kernel 使用：

- **file-tools**：文件读写、目录操作、复制/移动/删除
- **http-tools**：HTTP GET/POST/PUT/DELETE 请求
- **shell-tools**：Shell 命令执行（安全/非安全模式）

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-plugin {:local/root "../clj-agent-plugin"}}}
```

内部依赖：`clj-agent-core`

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.plugin.file` | 文件操作工具集 |
| `im.ttalk.agent.plugin.http` | HTTP 请求工具集 |
| `im.ttalk.agent.plugin.shell` | Shell 命令工具集 |
| `im.ttalk.agent.plugin.security` | 安全工具 |
| `im.ttalk.agent.plugin.resilience` | 重试/超时装饰器 |
| `im.ttalk.agent.plugin.utility` | 通用工具 |
| `im.ttalk.agent.plugin.helpers` | 内部辅助函数 |
| `im.ttalk.agent.plugin.all` | 所有插件合集 |

## 使用方式

```clojure
(require '[im.ttalk.agent.plugin.file :as file])
(require '[im.ttalk.agent.plugin.http :as http])
(require '[im.ttalk.agent.plugin.shell :as shell])

;; 注册到 Kernel
(-> (kernel/create-kernel-builder)
    (kernel/add-tools file/all-tools)
    (kernel/add-tools http/all-tools)
    (kernel/add-tools shell/all-tools)
    ...)

;; 或用于 SimpleAgent
(ka/create-agent {:tools (concat file/all-tools http/all-tools) ...})
```

## 工具列表

### file-tools（文件操作）

| 工具 | 说明 | Sensitive |
|------|------|-----------|
| `read-file` | 读取文件内容 | No |
| `write-file` | 写入文件（覆盖） | Yes |
| `append-file` | 追加内容到文件 | Yes |
| `list-directory` | 列出目录内容 | No |
| `file-info` | 获取文件元信息 | No |
| `file-exists` | 检查文件是否存在 | No |
| `create-directory` | 创建目录 | Yes |
| `delete-file` | 删除文件 | Yes |
| `copy-file` | 复制文件 | Yes |
| `move-file` | 移动/重命名文件 | Yes |

### http-tools（HTTP 请求）

| 工具 | 说明 | 参数 |
|------|------|------|
| `http-get` | GET 请求 | `url`, `timeout`(默认10000) |
| `http-post` | POST 请求 | `url`, `body`, `content-type`, `timeout` |
| `http-put` | PUT 请求 | `url`, `body`, `content-type`, `timeout` |
| `http-delete` | DELETE 请求 | `url`, `timeout` |

### shell-tools（Shell 命令）

| 工具 | 说明 | Sensitive |
|------|------|-----------|
| `execute-command` | 执行命令（无安全检查） | Yes |
| `execute-command-safe` | 安全执行（带危险命令拦截） | No |

## Sensitive 标记说明

标记为 `sensitive` 的工具在 ProcessAgent 中会触发暂停审批流程。在 KernelAgent 中则直接执行。

写入、删除、移动等文件操作和未检查的 Shell 命令都标记为 sensitive，防止 LLM 未经确认执行危险操作。

---

<a name="english"></a>

## English

### Overview

Pre-built plugin library providing common tools for file operations, HTTP requests, and shell commands.

### Plugins

- **file-tools**: read, write, append, list, info, exists, mkdir, delete, copy, move
- **http-tools**: GET, POST, PUT, DELETE with configurable timeout
- **shell-tools**: Command execution (safe/unsafe modes)

### Usage

```clojure
(require '[im.ttalk.agent.plugin.file :as file])
(kernel/add-tools builder file/all-tools)
```

Tools marked as `:sensitive true` will trigger approval in ProcessAgent.
