(ns im.ttalk.agent.provider.common.request-hook
  "宿主对**最终请求体**的最后一改：config `:configure-request`。

   形状：`(fn [payload] payload)`，payload 是本库组好的完整请求体（wire 形态的
   map，键为 snake_case 关键字）——思考字段（common.thinking 生成的）、`:extra-body`、
   缓存断点（Anthropic）、流式的 `:stream true` **都已在场**，钩子看得见、删得掉、
   改写得了；返回值原样作为请求体发出。

   为什么要有（feedbacks/2026-11-10-provider-request-hook-and-two-tier-cache-ttl.md）：
   `:extra-body` 只能 merge，删不掉库生成的键。qwen 收到 `thinking` /
   `reasoning_effort` 回 400（它要 `enable_thinking`）、kimi-k2 收到 `temperature`
   回 400——宿主手里只有 config，算不出最终请求体，于是绕不过去。
   对标 Keelson.Agent 的 `ConfigureRequest`（`Action<JsonObject>`）。

   约定：
   - 同步 / 流式 / deferred 各条路径**同一处**调用，发出之前最后一步。
   - 返回非 map 视为宿主 bug，当场抛 `ex-info`（`:type :invalid-request-hook`），
     不静默发出半截请求体。
   - 钩子抛出的异常原样上抛（不包装、不吞）。")

(set! *warn-on-reflection* true)

(defn apply-hook
  "有 `:configure-request` 就把 payload 交给它，否则原样返回。"
  [config payload]
  (if-let [f (:configure-request config)]
    (let [out (f payload)]
      (if (map? out)
        out
        (throw (ex-info ":configure-request 必须返回请求体 map"
                        {:type :invalid-request-hook
                         :provider (:provider-name config)
                         :returned (some-> out class .getName)}))))
    payload))
