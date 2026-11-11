# deftool:对象参数写不了嵌套 schema,参数多于 8 个时声明顺序丢

来源:锦书后端(JVM 版,C# 树已于 2026-11-11 删除)要把 39 件工具从「C# 导出的 JSON 契约 + 按名字查 handler」
改成 `deftool` 就地声明。依赖 clj-agent-core 0.5.441。

## 症状

两处都**不报错**:

1. `[because :object "…" …]` 这种对象参数,生成的 schema 只有 `{:type "object" :description "…"}` ——
   写在参数向量里的 `:properties` / `:required` 被静默丢掉(不在 `constraint-keys` 里)。模型看到的是一个
   「随便什么对象」,嵌套字段只能靠描述猜。
2. 参数多于 8 个时,`input_schema.properties` 的顺序与声明顺序不一致(`reduce assoc {}` 过了 8 个键变成
   `PersistentHashMap`)。参数顺序是工具说明的一部分(必填、常用的放前面),也进 prompt cache 的稳定前缀。

## 位置

`modules/clj-agent-core/src/im/ttalk/agent/tool.clj`:

- `constraint-keys`(第 108 行):只认 `:enum :pattern :minimum :maximum :exclusive-* :min/max-length :items :min/max-items`,
  没有 `:properties` / `:required` / `:additional-properties`;
- `params->json-schema` 第 159 行:`(reduce (fn [acc …] (assoc acc …)) {} parsed-params)` —— 普通 map,>8 键丢序。

## 怎么撞上的

锦书 39 件工具里:

- 3 个**对象**参数带嵌套属性与必填:`put_codex_section.because`、`merge_codex_sections.because`、
  `record_codex_event.event`(`event` 有 `required`);
- 10 个「对象数组」参数走 `:items {…}` 没问题(嵌套 map 原样进 schema);
- `edit` 有 10 个参数 ⇒ 顺序乱。

```clojure
(deftool record-codex-event "…"
  [[event :object "事件本身"
    :properties {:chapter {:type "integer" :description "…"} :title {:type "string" :description "…"}}
    :required ["chapter" "title"]]
   [lands :array "落点" :items {…}]]
  …)
;; ⇒ :input_schema {:properties {:event {:type "object" :description "事件本身"} …}}   ← :properties/:required 没了
```

## 影响面

凡是用 `deftool` 声明「对象参数」的下游:嵌套字段说明对模型不可见,`tool-args` 的调用前校验也校不到嵌套必填。
参数顺序那条只在 >8 个参数时出现,不报错,只是工具说明悄悄变样。

## 建议

- `constraint-keys` 补 `:properties`、`:required`、`:additional-properties`(→ `additionalProperties`),
  语义同 `:items`:原样进 schema;
- `properties` 用保序的 map 组(如 `array-map` / ordered-map),按参数声明顺序输出;
  `:items` / `:properties` 里用户写的嵌套字面量 >8 键时同样会丢序,可在文档里提醒或一并保序。

锦书在等这两条落地后一次性把 39 件工具换成 `deftool`(之前不做半套)。

## 库侧回复（2026-11-11）✅

已修（只编码未提交）：`:properties` / `:required` / `:additional-properties` → `additionalProperties`
原样进对象参数 schema；嵌套 required 经 `tool-args` 参与调用前校验。`input_schema.properties`
改用按声明序排序的 map 生成，10 个参数也保序；`deftool` 元数据里的 schema 改为 quoted 值传入，
避免 `defn` attr-map 求值时把 >8 键的有序 map 重排。
判据钉在 `tool_test`：嵌套 schema / 嵌套缺参校验、`p01..p10` 声明序。嵌套 map 字面量本身超过
8 个键时受 Clojure reader 的 map 类型限制，库无法恢复源码文本序；已按建议在文档提醒。
