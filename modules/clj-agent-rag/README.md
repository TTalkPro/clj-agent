# clj-agent-rag

RAG 检索增强生成模块

[English](#english) | 中文

## 概述

`clj-agent-rag` 提供检索增强生成（Retrieval-Augmented Generation）功能：

- **RAG Pipeline**：文档索引、检索、问答的完整流水线
- **文本切分**：多种切分策略（按长度、按段落、按语义）
- **向量存储**：Embedding 生成和向量数据库集成
- **Kernel Plugin**：将 RAG 操作暴露为 LLM 可调用的工具

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-rag {:local/root "../clj-agent-rag"}}}
```

内部依赖：`clj-agent-core`

外部依赖：
- cheshire/cheshire 5.12.0
- com.taoensso/timbre 6.3.0

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.rag.plugin` | RAG 工具集（Kernel Plugin） |
| `im.ttalk.agent.rag.pipeline` | RAG 执行管道 |
| `im.ttalk.agent.rag.embeddings` | Embedding 操作 |
| `im.ttalk.agent.rag.vector_store` | 向量数据库接口 |
| `im.ttalk.agent.rag.splitter` | 文本切分策略 |
| `im.ttalk.agent.rag.utils` | 工具函数 |

## 使用方式

### 作为 Kernel Plugin

将 RAG 工具注册到 Kernel，LLM 可自动调用：

```clojure
(require '[im.ttalk.agent.rag.plugin :refer [rag-tools]])

;; 注册到 Kernel
(-> (kernel/create-kernel-builder)
    (kernel/add-plugin rag-tools)
    ...)

;; 或用于 SimpleAgent
(ka/create-agent {:tools [rag-tools] ...})
```

### 直接使用 Pipeline

```clojure
(require '[im.ttalk.agent.rag.pipeline :as pipeline])

;; 初始化 Pipeline
(def p (pipeline/create-rag-pipeline
         {:embeddings-model embedder
          :vector-store vs
          :llm-fn llm-fn}))      ;; 可选：用于生成回答

;; 设为默认（供 Plugin 使用）
(pipeline/set-default-rag-pipeline! p)

;; 索引文档
(pipeline/index-document p "文档内容..." :metadata {:source "doc-1"})
(pipeline/index-file p "/path/to/file.txt")

;; 检索
(pipeline/retrieve p "查询" :top-k 5 :min-score 0.5)
;; => {:ok true :documents [{:content "..." :score 0.85} ...]}

;; 问答（检索 + LLM 生成）
(pipeline/query p "问题" :top-k 5)
;; => {:ok true :answer "..." :documents [...]}

;; 统计
(pipeline/pipeline-stats p)
;; => {:embeddings-model "..." :document-count 42 :has-llm true}
```

## RAG Plugin 工具列表

| 工具 | 说明 | Sensitive |
|------|------|-----------|
| `rag-index-text` | 索引文本到知识库 | No |
| `rag-index-file` | 索引文件到知识库 | Yes |
| `rag-retrieve` | 检索相关文档片段 | No |
| `rag-query` | RAG 问答（检索+生成） | No |
| `rag-search` | 带分数过滤的向量搜索 | No |
| `rag-stats` | 获取知识库统计信息 | No |

## 文本切分

```clojure
(require '[im.ttalk.agent.rag.splitter :as splitter])

;; 按字符长度切分
(splitter/split-by-length text {:chunk-size 500 :overlap 50})

;; 按段落切分
(splitter/split-by-paragraph text)

;; 自定义切分
(splitter/split-text text {:strategy :length :chunk-size 1000})
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-rag` provides Retrieval-Augmented Generation capabilities:

- **RAG Pipeline**: Complete pipeline for document indexing, retrieval, and Q&A
- **Text Splitting**: Multiple chunking strategies (length, paragraph, semantic)
- **Vector Storage**: Embedding generation and vector DB integration
- **Kernel Plugin**: Exposes RAG operations as LLM-callable tools

### Key APIs

- `pipeline/create-rag-pipeline` - Initialize RAG pipeline
- `pipeline/index-document`, `pipeline/index-file` - Index content
- `pipeline/retrieve` - Vector similarity search
- `pipeline/query` - RAG Q&A (retrieve + generate)
- `rag-tools` - Pre-built Plugin for Kernel registration

### Tools (as Plugin)

`rag-index-text`, `rag-index-file`(sensitive), `rag-retrieve`, `rag-query`, `rag-search`, `rag-stats`
