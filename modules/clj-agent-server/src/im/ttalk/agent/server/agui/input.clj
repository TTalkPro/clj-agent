(ns im.ttalk.agent.server.agui.input
  "AG-UI 的 run 输入（C# Keelson.Agui 的 RunAgentInput.Decode）：只做**形状**校验，码与文案同 C#；解析成什么交给
   clj-agent-agui 的 `codec/parse-run-input`（历史取服务端权威、只取最后一条 user 消息 …）。

   错误是 ex-info：`:im.ttalk.agent.server/code` = C# 的资源键（`agui-input/…`），`:agui/wire-code` = 线上的种类码
   （`bad_json` / `bad_input`），路由据此回 400 `{\"error\": wire-code, \"message\": …}`。"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.server.strings :refer [msg]]
            [im.ttalk.agent.server.strings :as err]))

(set! *warn-on-reflection* true)

(defn- input-error [wire-code m]
  (err/error m {:im.ttalk.agent.server/kind :agui :agui/wire-code wire-code}))

(def ^:private bad ::bad)

(defn parse-json
  "串 ⇒ JSON 值（对象键是 keyword）；不是合法 JSON ⇒ `::bad`。"
  [^String s]
  (try (json/parse-string s true)
       (catch Exception _ bad)))

(defn bad-json? [v] (= bad v))

(defn- req-string [body k]
  (let [v (get body k)]
    (if (and (string? v) (seq v))
      v
      (throw (input-error "bad_input" (msg :agui-input/missing-string (name k)))))))

(defn- check-array [body k]
  (let [v (get body k)]
    (when-not (or (nil? v) (sequential? v))
      (throw (input-error "bad_input" (msg :agui-input/want-array (name k)))))))

(defn- last-seq [body]
  (let [v (get body :lastSeq)]
    (cond (nil? v) 0
          (and (integer? v) (not (neg? (long v)))) (long v)
          :else (throw (input-error "bad_input" (msg :agui-input/want-non-negative-long "lastSeq"))))))

(defn validate
  "已解析的请求体（map）⇒ 原样返回并补 `:lastSeq`（缺席 = 0）；形状不对抛（见 ns 文档）。"
  [body]
  (when-not (map? body) (throw (input-error "bad_input" (msg :agui-input/not-object))))
  (req-string body :threadId)
  (req-string body :runId)
  (run! #(check-array body %) [:messages :tools :context :resume])
  (assoc body :lastSeq (last-seq body)))

(defn decode
  "请求体原文 ⇒ 校验过的 RunAgentInput（keyword 键的 map）。空 / 坏 JSON ⇒ `bad_json`；形状不对 ⇒ `bad_input`。"
  [^String body]
  (when (empty? body) (throw (input-error "bad_json" (msg :agui-input/empty-body))))
  (let [v (parse-json body)]
    (when (bad-json? v) (throw (input-error "bad_json" (msg :agui-input/bad-json))))
    (validate v)))

(defn wire-code
  "输入错误的线上种类码；不是输入错误 ⇒ nil。"
  [e]
  (:agui/wire-code (ex-data e)))
