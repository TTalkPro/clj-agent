# Channel 与 Go Blocks 使用说明

项目中的 channel 使用了 **go blocks**（基于 `core.async`）。

## 使用方式总结

### 1. go-loop（主要模式）

```clojure
;; Step Worker (runtime.clj:112)
(go-loop []
  (when-let [{:keys [input-name data]} (<! input-chan)]
    ;; 处理输入...
    (recur)))

;; Router (runtime.clj:290)
(go-loop []
  (let [[v port] (async/alts! ports-with-timeout :priority true)]
    ;; 路由事件...
    (recur)))
```

### 2. 阻塞操作使用 async/thread

为了避免阻塞 go-loop 的线程池，耗时操作使用 `async/thread`：

```clojure
;; runtime.clj:130-134
;; 在独立线程执行（避免阻塞 go-loop 线程池）
(let [result-ch
      (async/thread
        (try
          (let [result (on-activate inputs state ctx)]
            {:ok result})
          (catch Exception e
            {:error (.getMessage e)})))]
  ;; 在 go-loop 中等待结果
  (let [exec-result (<! result-ch)
        ...]))
```

### 3. 阻塞读取（外部同步调用）

对外暴露的同步 API 使用 `<!!`：

```clojure
;; runtime.clj:727 - run-process
(let [result (<!! (:result-chan runtime))]
  ...)

;; runtime.clj:1322 - wait-for-completion
(<!! (:result-chan handle))
```

## 架构图

```
┌─────────────────────────────────────────────────────┐
│  外部调用 (同步)                                      │
│    <!! result-chan                                   │
└─────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────┐
│  Router (go-loop)                                    │
│    alts! [event-chan, external-chan, control-chan]  │
│    路由事件到各 Step                                  │
└─────────────────────────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│ Step Worker │   │ Step Worker │   │ Step Worker │
│  (go-loop)  │   │  (go-loop)  │   │  (go-loop)  │
│     │       │   │     │       │   │     │       │
│     ▼       │   │     ▼       │   │     ▼       │
│ async/thread│   │ async/thread│   │ async/thread│
│ (执行回调)   │   │ (执行回调)   │   │ (执行回调)   │
└─────────────┘   └─────────────┘   └─────────────┘
```

## 关键设计

`on-activate` 回调在 `async/thread` 中执行（真实线程），而不是在 go block 中，这样可以安全地进行阻塞 I/O 操作（如 HTTP 调用、数据库查询），不会耗尽 go block 的固定线程池。

## 相关文件

- `modules/clj-agent-core/src/im/ttalk/agent/core/kernel/process/runtime.clj`
