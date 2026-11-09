(ns im.ttalk.agent.provider.anthropic-server-tools
  "Anthropic 服务端工具块的「看得懂」的视图（designs/provider-reliability.md §9；
   Keelson `Providers/AnthropicServerTools.cs` 的 `Describe`）。

   服务端工具块（`server_tool_use`、`web_search_tool_result`、`code_execution_tool_result` …）在
   中立层始终是不透明回放载荷（`:blocks`，原样回放、pause_turn 续跑都认它），这一点不变。
   本 ns 只给界面 / 日志一个纯数据视图（查询词、搜到的网址、执行输出），**不进历史**。

   ```clojure
   (server-tools/describe-all (:content raw-anthropic-response))
   ;=> [{:kind :web-search-call :id \"srvtoolu_1\" :queries [\"clojure\"]}
   ;    {:kind :web-search-result :tool-use-id \"srvtoolu_1\" :results [{:url .. :title ..}]}]
   ```"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private code-tools #{"code_execution" "bash_code_execution" "text_editor_code_execution"})

(defn- error-view
  "结果内容是错误对象（type 以 _error 结尾）→ {:error-code ..}；否则 nil。"
  [content]
  (when (and (map? content) (string? (:type content)) (str/ends-with? (:type content) "_error"))
    {:error-code (or (:error_code content) (:type content))}))

(defn- server-tool-use [{:keys [id name input]}]
  (cond
    (= "web_search" name)
    {:kind :web-search-call :id id :queries (if-let [q (:query input)] [q] [])}

    (contains? code-tools name)
    {:kind :code-execution-call :id id :name name
     :input (or (:code input) (:command input) (when (map? input) (pr-str input)))}

    :else
    {:kind :server-tool-call :id id :name name :input input}))

(defn- web-search-result [{:keys [tool_use_id content]}]
  (if-let [err (error-view content)]
    (merge {:kind :web-search-result :tool-use-id tool_use_id :results []} err)
    {:kind :web-search-result :tool-use-id tool_use_id
     :results (vec (for [r (when (sequential? content) content)
                         :when (string? (:url r))]
                     (cond-> {:url (:url r)}
                       (:title r)    (assoc :title (:title r))
                       (:page_age r) (assoc :page-age (:page_age r)))))}))

(defn- code-result [{:keys [tool_use_id content type]}]
  (if-let [err (error-view content)]
    (merge {:kind :code-execution-result :tool-use-id tool_use_id :block-type type} err)
    (cond-> {:kind :code-execution-result :tool-use-id tool_use_id :block-type type}
      (seq (:stdout content)) (assoc :stdout (:stdout content))
      (seq (:stderr content)) (assoc :stderr (:stderr content))
      (some? (:return_code content)) (assoc :return-code (:return_code content)))))

(defn describe
  "一个 Anthropic 内容块 → 视图 map；不认识（text / tool_use / thinking …）→ nil。视图带 `:raw` 原块。

   | 块 | 视图 |
   |---|---|
   | server_tool_use name=web_search | `{:kind :web-search-call :id :queries [q]}` |
   | server_tool_use name=*code_execution | `{:kind :code-execution-call :id :name :input \"代码或命令\"}` |
   | 其它 server_tool_use（web_fetch …） | `{:kind :server-tool-call :id :name :input {...}}` |
   | web_search_tool_result | `{:kind :web-search-result :tool-use-id :results [{:url :title :page-age}]}` |
   | *code_execution_tool_result | `{:kind :code-execution-result :tool-use-id :stdout :stderr :return-code}` |
   结果是错误对象（`*_tool_result_error`）→ 视图带 `:error-code`。"
  [block]
  (when (map? block)
    (when-let [view (case (:type block)
                      "server_tool_use" (server-tool-use block)
                      "web_search_tool_result" (web-search-result block)
                      ("code_execution_tool_result" "bash_code_execution_tool_result"
                       "text_editor_code_execution_tool_result") (code-result block)
                      nil)]
      (assoc view :raw block))))

(defn describe-all
  "一组内容块 → 认得的那些块的视图（按原次序；认不出的跳过）。"
  [blocks]
  (into [] (keep describe) blocks))
