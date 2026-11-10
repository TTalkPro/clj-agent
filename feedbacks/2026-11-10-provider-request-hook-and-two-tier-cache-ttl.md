# provider:缺两样 Keelson.Agent 有的通用件 —— ① 请求体最后一改的钩子;② 同一请求两档缓存 TTL(前缀 1h + 尾部 5m);外加 ③ 单次请求的选项递不到 provider

> 报方:锦书(jinshu)§K-19 后端改写 JVM,K-25 Llm 层。C# 版靠 Keelson.Agent 的这两样,JVM 版接 clj-agent-provider 0.5.435 时没有对应物。
> 两样都**不是锦书特有**:任何「接一家 OpenAI 兼容但私有字段不同的服务商」「在 Anthropic 兼容端点上省缓存钱」的宿主都会撞。

## 症状

**一个字都不报**,两处都是:

```
① qwen:请求体里出现 thinking / reasoning_effort ⇒ 百炼回 400(它要的是 enable_thinking,且非流式必须是 false)
   kimi-k2.x:请求体里出现 temperature ⇒ 400(K2 固定温度);kimi-k2.7-code 出现 thinking ⇒ 400
   ⇒ 宿主想「组完请求体之后删几个键」,`:extra-body` 只能 merge,删不掉

② 想要:tools+system 那段前缀 1h(作者读一段、想一会儿、改两句再回来,间隔 > 5 分钟是常态),
   transcript 尾巴 5m(尾巴每轮被取代,1h 的 2× 写入费纯亏)
   现状:`:cache-strategy` + 一个 `:cache-ttl` ⇒ 两个断点只能同一档 ⇒ 要么尾巴白付 2×,要么前缀 5 分钟后整段 miss
   ⇒ 不报错,只有账单会说话
```

## 位置

| 文件 | 现状 |
|---|---|
| `clj-agent-provider/src/im/ttalk/agent/provider/common/openai_compat.clj:57` `build-params` | 最后一步是 `(map? extra-body) (merge extra-body)`;之后直接 `http/post`(`:164` / `:206` / deferred 两条)—— 没有宿主能改最终请求体的位置 |
| `…/provider/common/thinking.clj` `openai-fields` | 思考字段在这里生成;宿主钩子要**看得见并删得掉**这些字段(qwen 要把 `thinking{type}` 改写成 `enable_thinking` + Max/Plus 系流式时加 `preserve_thinking`) |
| `…/provider/common/cache.clj:196` `apply-anthropic-cache` | `(let [cc (ephemeral ttl)] …)` 一个 cc 打所有断点 |
| `…/provider/anthropic.clj:358` `build-params` | 只读 `:cache-strategy :cache-ttl` |

## 怎么撞上的

锦书的 C# 版(Keelson.Agent):

```csharp
new OpenAICompatibleChatClient(new OpenAICompatibleOptions {
    ThinkingStyle = ThinkingStyle.TopLevel,
    ConfigureRequest = ProviderHooks.Of(hook),      // Action<JsonObject>:请求体组完(含思考字段)之后最后改一次
});
new AnthropicChatClient(new AnthropicOptions {
    Auth = AnthropicAuth.Bearer,
    CachePrefix = AnthropicCacheTtl.OneHour,        // system(及其前的 tools)断点 1h
    CacheTail = AnthropicCacheTtl.FiveMinutes,      // 最后一条消息最后一块 5m(不带 ttl 字段)
    CacheTailCount = 1,
    Betas = ["extended-cache-ttl-2025-04-11"],      // 兼容端点(MiniMax /anthropic)少了它静默退回 5m
});
```

锦书的判据(要逐条移植到 JVM)在真请求体上钉:

* qwen + 档位 high ⇒ 线上请求体**没有** `thinking` / `reasoning_effort`,**有** `enable_thinking`;
* Anthropic + promptCache ⇒ `system` 变块数组,唯一块 `cache_control.ttl = "1h"`;最后一条消息最后一块
  `cache_control = {"type":"ephemeral"}`(**不带** ttl);整份 payload 恰好 2 个 `cache_control`;
  `anthropic-beta` 含 `extended-cache-ttl-2025-04-11`(这一格 `:beta` 已经能给 ✅)。

## 影响面

🟡 两样都**不报错**:

* ① 绕不过去:宿主手里只有 config,算不出最终请求体(思考字段是库里生成的),也删不掉 `:extra-body` 之外的键。
  硬绕的办法是宿主自己算思考字段、显式传 `:thinking` 等 —— 等于把 `common.thinking` 抄一份到宿主,且钩子
  「看得见库生成的字段」这条语义就没了(锦书 C# 版 2026-09 曾因字段排序 / 漏删真出过线上 400)。
* ② 能绕但亏钱:只能二选一,长对话里每轮实付差 ≈ 2.25× vs 0.1×(Anthropic 计价:写 5m 1.25× / 写 1h 2× / 读 0.1×)。

## 建议(下游不替仓主拍板)

1. ⭐ OpenAI 兼容线加 `:configure-request`(`(fn [payload] payload')`,payload 是 `build-params` 的最终产物,
   **在思考字段与 `:extra-body` 之后**、发出之前调;同步 / 流式 / deferred 四条路径同一处)。
   Anthropic 线同样给一个更好(对称),但锦书眼下只用在 OpenAI 兼容线。
   ⚠️ 流式路径的 `:stream true` 若是在 `build-params` 之后才 assoc 的,请让钩子看得见它(qwen 按 `stream` 决定 `enable_thinking`)。
2. ⭐ `:cache-ttl` 允许按断点给档,例如 `:cache-strategy :system-and-conversation` +
   `:cache-ttl {:system "1h" :conversation nil}`(字符串照旧 = 全部同一档,不破坏现有调用)。
   Keelson 在构造时有一道闸:**长 TTL 必须排在短 TTL 之前**(服务端规则),建议同样校验、违者抛。
3. 可选:任一断点为 `"1h"` 时自动并入 beta `extended-cache-ttl-2025-04-11`(Keelson 是这么做的);不做也行,宿主自己传 `:beta`。

锦书这边先移植 K-25 其余部分(配置 / 加密 / 目录 / 网关 / 本地模型与下载),client 装配与上面两组判据等这条落地后再接。

---

## 处置（clj-agent，2026-11-10）

三条建议全部采纳，只编码未提交。判据逐条钉在
`modules/clj-agent-provider/test/im/ttalk/agent/provider/request_hook_test.clj`——拦截
`http/post` / `post-deferred` / `post-stream-sync` / `post-stream-deferred`，断言的是**真正发出去的请求体与请求头**。

1. **`:configure-request`**（新 ns `provider.common.request-hook`）
   * `(fn [payload] payload')`；payload = `build-params` 产物 + 流式的 `:stream true`，即**思考字段、`:extra-body`、
     `:stream` 都已在场**，钩子返回值原样发出。
   * OpenAI 兼容线四条路径统一经 `openai-compat/request-body` 取请求体（deepseek / zhipu 等走 `call-api*` 的一并覆盖）；
     顺带对称地接上了 **Anthropic**（缓存断点之后）与 **OpenAI Responses**。
   * 返回非 map ⇒ 抛 `ex-info`（`:type :invalid-request-hook`），不发半截请求体；钩子自己抛的异常原样上抛。
   * 判据：qwen + 档位 high ⇒ 线上无 `thinking` / `reasoning_effort`、有 `enable_thinking`（非流式 false、流式 true，
     钩子按 `:stream` 判）；kimi 删 `temperature` ✅。
   * 备注：OpenAI 兼容线上接百炼，直接 `:thinking-style :qwen` 就生成 `enable_thinking`（开 true / 关 false；2026-11-10 起非流式也放开，
     依据是 LiteGate 上 qwen3.8 非流式开思考不 400），
     不需要钩子；钩子留给 Max/Plus 的 `preserve_thinking` 这类库不认识的私有规则。
     （本节初稿写过「`dashscope` provider 缺省就是 `:qwen`」——**不对**：`:dashscope` 走原生 API，当时根本不读思考键，
     `:thinking-effort` 被静默丢掉。已一并修好：原生请求写 `parameters.enable_thinking`，流式 `reasoning_content`
     单独发 `:reasoning-token`；`thinking_budget` 等走新增的 `:extra-parameters`。）

2. **`:cache-ttl` 按断点给档**（`common.cache`）
   * 标量照旧 = 所有断点同档；map 键 `:tools` / `:system` / `:conversation` / `:tool-results`，缺键或 nil = 5m（不带 ttl 字段）。
   * 闸：按渲染顺序**长 TTL 必须排在短 TTL 之前**，违者抛 `:invalid-cache-ttl`；map 里拼错的键同样抛（否则静默落成 5m）。
   * 判据：`:system-and-conversation` + `{:system "1h" :conversation nil}` ⇒ `system` 变块数组、唯一块 `ttl "1h"`；
     末条消息末块 `{"type":"ephemeral"}`；整份恰好 2 个 `cache_control` ✅。

3. **自动 beta**：策略**实际打到的**断点里有 `"1h"` ⇒ `anthropic-beta` 并入 `extended-cache-ttl-2025-04-11`
   （与 `:beta` 合并去重）；全 5m、或 1h 只写在策略不打的断点上 ⇒ 不加。


## ③ 追加(同日):`DefaultChatModel` 只把请求选项里的三格递给 provider,其余**静默丢掉**

### 症状

```clojure
(cm/call (cm/create-chat-model prov {:model "m"})
         (req/chat-request msgs {:thinking-effort "max" :temperature 0.7 :max-tokens 1}))
;; 线上请求体:没有 thinking / reasoning_effort / temperature / max_tokens —— 一个字不报
;; 同样的键放进 create-chat-model 的 config ⇒ 全都在
```

### 位置

| 文件 | 现状 |
|---|---|
| `clj-agent-core/src/im/ttalk/agent/chat_model.clj:52` `build-call-config` | 从 opts 里只取 `:tools` / `:tool-choice` / `:system-prompt`,其余丢掉 |
| 同上 `:270` / `:283` | `call` 与 `stream-call` 都经它 |
| `…/model/request.clj:100` `wire-options` | 文档写的是「**只做去 nil,不筛白名单** —— provider 私有参数必须能原样穿过去」(引 `docs/provider-variant-design.md` §1.2「递不到底」),而下一步就被 `build-call-config` 筛掉了 |

### 影响面

🔴 **不报错,值按调用变的那一类旋钮全部失效**:每轮的思考开 / 关 / 档位(锦书主对话按用户的开关逐轮变)、
一次性小任务的 `:max-tokens`、选区改写的 `:temperature`。宿主只能每次调用现造一个 ChatModel 把它们塞进 config
(锦书眼下就这么绕:`jinshu.llm.client/create-client` 的 `:options`),那等于每轮丢掉 ChatModel 上挂的一切(准入池的键、filter 状态)。

### 建议

`build-call-config` 改成「config ⊕ wire-options(去掉 ChatModel 自己的装配键 :admission / :retry 等)」,三格的特殊处理保留在合并之后;
`:retry` 若允许单次覆盖,`retry/resolve-opts` 已经有三级合并,照现在的口径走。
