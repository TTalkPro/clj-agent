(ns im.ttalk.agent.mcp.transport
  "客户端传输：Streamable HTTP 与 stdio。

   一个协议、两个实现，外加「传输就是一个函数」这条后门：

   ```clojure
   (send! t msg)   ;; 请求 → 响应 map；通知 → nil
   (close! t)      ;; 释放（stdio 杀子进程；HTTP 无状态，无操作）
   ```

   **`(fn [msg] resp)` 也是合法传输**（`send!` 对 `IFn` 有实现）——测试因此不用起
   服务、不用起进程，一个纯函数就能把整条客户端逻辑跑完。这条不是给测试开的
   特例，它就是这层的最小契约。

   ## Streamable HTTP（2026-07-28）

   POST 一条 JSON-RPC，`Accept: application/json, text/event-stream`，响应可能是
   两种之一。三个头是**必填**且**不能与信封不一致**：

   | 头 | 值 | 不对会怎样 |
   |---|---|---|
   | `MCP-Protocol-Version` | 与 `_meta` 里的版本**同一个值** | `-32020 HeaderMismatch` |
   | `Mcp-Method` | 与 body 的 `method` 同一个值 | 同上 |
   | `Mcp-Name` | 只对 `tools/call` / `prompts/get`（取 `name`）与 `resources/read`（取 `uri`） | 同上 |

   版本是从**信封里反读**的（`protocol/protocol-version-of`），不是传输自己记一份
   ——两处各记一份就是 `-32020` 的来源。

   **没有会话**：modern 不再有 `Mcp-Session-Id`。但双时代客户端要连老 server，
   所以这里仍然会**收下并回传** server 给的 session id；modern 路径上 server 不会
   给，这段代码就是死的。

   ## stdio

   一行一条 JSON（消息内不得有裸换行）。读端是一条**后台线程**：响应按 id 交给
   等待中的 promise，其余（通知、日志）交给 `:on-notification`。

   为什么不能「写一行、读一行」了事：协议允许 server 在响应之前先推通知
   （进度、日志、订阅事件），同步读会把它们当成响应。而且 2026-07-28 明说
   「一条 stdio 连接不是一个会话」，客户端可以在同一个进程上交错发无关请求。"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [im.ttalk.http :as http]
            [im.ttalk.http.sse :as sse]
            [im.ttalk.agent.mcp.protocol :as p]
            [taoensso.timbre :as log])
  (:import [java.io BufferedWriter]
           [java.net.http HttpClient]
           [java.util.concurrent Callable Executors ExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(defprotocol Transport
  (send! [t msg] "发一条消息。请求返回响应 map；通知返回 nil。")
  (close! [t] "释放资源。"))

(extend-protocol Transport
  clojure.lang.IFn
  (send! [f msg] (f msg))
  (close! [_] nil))

;;; ============================================================
;;; 传输层错误
;;; ============================================================

(defn transport-error
  "传输层失败。**带上足够让客户端判时代的证据**：HTTP 状态码与响应体里可能有的
   JSON-RPC 错误——双时代回退正是看这两样（见 `mcp.client`）。"
  [msg data]
  (ex-info msg (assoc data ::transport-error true)))

(defn transport-error?
  [t] (boolean (some-> t ex-data ::transport-error)))

;;; ============================================================
;;; Streamable HTTP
;;; ============================================================

(defn- parse-sse
  "SSE 响应体 → 第一个 `:data` 能解析成 JSON 的事件。分帧走 `im.ttalk.http.sse`（WHATWG）：
   多行 `data:` 按规范以换行拼接（此前只取第一行，一条 JSON 拆成多行 data 就解析失败）、
   `\\r\\n` / `\\r` 行尾都认。末尾补一个空行：服务端写完最后一个事件就关连接、没写收尾空行时也不丢它。

   只取第一条够用的理由：本传输只做**请求/响应**。真正的长流（`subscriptions/listen`）是
   另一件事，等有需求再开——现在把它做出来就是替想象中的用户写代码。"
  [body]
  (some (fn [{:keys [data]}]
          (try (json/parse-string data)
               (catch Exception _ nil)))
        (sse/parse-all (str body "\n\n"))))

(defn- json-rpc-error-of
  "从响应体里挖 JSON-RPC 错误信封（挖不出就 nil）。

   **非 2xx 的响应体也要挖**：modern server 用 `400 + {-32022}` 告诉客户端
   「版本不行，换一个」，而老 server 的 400 body 是空的或一段 HTML——
   这正是双时代判据的分水岭。"
  [body]
  (when-not (str/blank? (str body))
    (let [m (try (json/parse-string (str body)) (catch Exception _ nil))]
      (when (and (map? m) (get m "error")) m))))

(defrecord HttpTransport [^HttpClient http url headers timeout-ms session]
  Transport
  (send! [_ msg]
    (let [pv (p/protocol-version-of msg)
          method (get msg "method")
          rname (p/routing-name msg)
          hs (cond-> (merge {"Content-Type" "application/json"
                             "Accept" "application/json, text/event-stream"}
                            headers)
               ;; 三个必填头。**都从信封反读**，不另记一份；调用方的同名头不得盖掉它们
               pv (http/assoc-header "MCP-Protocol-Version" (str pv))
               method (http/assoc-header "Mcp-Method" (str method))
               rname (http/assoc-header "Mcp-Name" (str rname))
               ;; legacy 兼容：老 server 给了 session id 就一路带着
               @session (http/assoc-header "Mcp-Session-Id" @session))
          resp (http/request {:method :post :url url :headers hs :body (p/encode msg)
                              :timeout-ms timeout-ms :client http})
          status (:status resp)
          ctype (str (http/header resp "content-type"))
          body (:body resp)]
      (when-let [s (http/header resp "mcp-session-id")]
        (reset! session s))
      (cond
        (>= status 400)
        (throw (transport-error (str "MCP HTTP " status)
                                {:status status
                                 :url url
                                 :jsonrpc-error (json-rpc-error-of body)
                                 :body (subs (str body) 0 (min 500 (count (str body))))}))

        (str/blank? (str body)) nil
        (str/includes? ctype "text/event-stream") (parse-sse body)
        :else (p/parse body))))
  (close! [_] nil))

(defn http-transport
  "Streamable HTTP 传输（传输层是 `im.ttalk.http`）。

   - `:url`        必填
   - `:headers`    额外头（鉴权走这里；MCP 的 auth 框架只管 HTTP）
   - `:timeout-ms` 单次请求上限（缺省 30s）
   - `:client`     自带 `java.net.http.HttpClient`；缺省共享的 `im.ttalk.http/default-client`
                   （HTTP/1.1、虚拟线程、connect 30s——designs/http-module.md §6）
   - `:proxy`      没给 `:client` 时用：`:env`（读 https_proxy / no_proxy …）、代理地址串，或 ProxySelector"
  [{:keys [url headers timeout-ms client proxy] :or {timeout-ms 30000}}]
  (when (str/blank? (str url))
    (throw (ex-info "http-transport 需要 :url" {})))
  (->HttpTransport (or client
                       (when proxy (http/client {:proxy proxy}))
                       (http/default-client))
                   url headers timeout-ms (atom nil)))

;;; ============================================================
;;; stdio
;;; ============================================================

(defn- reader-loop!
  "后台读线程：一行一条消息。

   有 id 且**等在那儿**的 → 交给对应 promise；其余一律走 `on-notification`。
   「有 id 但没人等」也走通知——那多半是 server 回了个我们已经超时放弃的请求，
   丢掉比塞进下一个人的信箱好。"
  [out pending on-notification stop?]
  (try
    (loop []
      (when-not @stop?
        (when-let [line (.readLine ^java.io.BufferedReader out)]
          (when-not (str/blank? line)
            (if-let [msg (p/parse line)]
              (let [id (get msg "id")
                    pr (when (some? id) (get @pending id))]
                (if pr
                  (do (swap! pending dissoc id) (deliver pr msg))
                  (when on-notification
                    (try (on-notification msg) (catch Throwable _ nil)))))
              (log/warn "MCP stdio: 读到一行不是 JSON，已跳过")))
          (recur))))
    (catch Throwable t
      (when-not @stop?
        (log/warn "MCP stdio 读线程结束:" (.getMessage t))))))

(defrecord StdioTransport [^Process proc ^BufferedWriter in pending on-notification
                           timeout-ms stop? ^ExecutorService pool]
  Transport
  (send! [_ msg]
    (let [id (get msg "id")
          pr (when (some? id) (promise))]
      (when pr (swap! pending assoc id pr))
      ;; 写与刷必须成对且互斥：两条消息交错写进同一行，对面只会看到一坨垃圾
      (locking in
        (.write in ^String (p/encode msg))
        (.newLine in)
        (.flush in))
      (when pr
        (let [r (deref pr timeout-ms ::timeout)]
          (if (= ::timeout r)
            (do (swap! pending dissoc id)
                (throw (transport-error "MCP stdio 请求超时"
                                        {:timeout-ms timeout-ms :method (get msg "method")})))
            r)))))
  (close! [_]
    (reset! stop? true)
    (try (.close in) (catch Throwable _ nil))
    (try (.destroy proc) (catch Throwable _ nil))
    (try (when-not (.waitFor proc 2 TimeUnit/SECONDS) (.destroyForcibly proc))
         (catch Throwable _ nil))
    (.shutdownNow pool)
    nil))

(defn stdio-transport
  "起一个子进程当 MCP server，用它的 stdin/stdout 说话。

   - `:command`  `[\"npx\" \"-y\" \"@modelcontextprotocol/server-everything\"]`（必填）
   - `:env`      追加的环境变量 map
   - `:dir`      工作目录
   - `:timeout-ms` 单次请求上限（缺省 30s）
   - `:on-notification` `(fn [msg])`——通知与「没人等的响应」都走这里

   **stderr 直接继承父进程**：server 的诊断信息本来就该看得见，吞掉它等于把
   「为什么连不上」变成猜谜。"
  [{:keys [command env dir timeout-ms on-notification] :or {timeout-ms 30000}}]
  (when-not (seq command)
    (throw (ex-info "stdio-transport 需要 :command" {})))
  (let [pb (ProcessBuilder. ^java.util.List (mapv str command))]
    (when dir (.directory pb (io/file dir)))
    (doseq [[k v] env] (.put (.environment pb) (name k) (str v)))
    (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
    (let [proc (.start pb)
          in (io/writer (.getOutputStream proc))
          out (io/reader (.getInputStream proc))
          pending (atom {})
          stop? (atom false)
          pool (Executors/newVirtualThreadPerTaskExecutor)]
      (.submit pool ^Callable (fn [] (reader-loop! out pending on-notification stop?)))
      (->StdioTransport proc in pending on-notification timeout-ms stop? pool))))
