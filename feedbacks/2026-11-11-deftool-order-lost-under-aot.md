# deftool:参数保序在 AOT 下失效(0.5.442 的修法只在 eval 下成立)

来源:锦书后端,clj-agent 0.5.442,2026-11-11。接上一条 `2026-11-11-deftool-nested-object-and-param-order.md`。

## 症状

不报错。同一个 `deftool`,eval 加载(REPL / 单测)时 `input_schema.properties` 按参数声明顺序;**AOT 编译后**
(tools.build 的 `compile-clj` → uber jar)变成 `PersistentHashMap`,过 8 个参数的工具顺序全乱。
单测全绿,发出去的包里模型看到的参数顺序是乱的。

最小复现(10 个参数):

```clojure
(ns p.t (:require [im.ttalk.agent :refer [deftool]]))
(deftool probe "d" [[a0 :string "x"] [a1 :string "x"] … [a9 :string "x"]] {:context true} a0)
```

- `(require 'p.t)` 从源码:`(keys (get-in (meta #'p.t/probe) [:tool/schema :input_schema :properties]))` ⇒ `(:a0 :a1 … :a9)`
- `(compile 'p.t)` 后只从 classes 加载 ⇒ `PersistentHashMap`,`(:a2 :a3 :a1 :a8 :a6 :a0 :a5 :a7 :a9 :a4)`

## 位置

`modules/clj-agent-core/src/im/ttalk/agent/tool.clj`:`ordered-map`(`sorted-map-by` + 比较器)做出来的 schema 作为
**常量**嵌进 `defn` 的元数据。Clojure 发射常量时把 `IPersistentMap` 重建成普通 map(`RT.map`,过 8 键即 hash map),
排序比较器丢失。eval 下常量对象原样保留,所以只有 AOT 现形。

同一机制的两个旁支(下游自己能绕,但值得写进文档):
- 嵌套 `:properties` / `:items` 里过 8 键的字面量 map 本来就是 hash map(reader),0.5.442 的 docstring 已注明;
- 想用 `(array-map …)` 保序不行 —— schema 是常量,嵌套表单不会被求值,原样留成一个列表;
  用 `#ordered/map` 读入期保序在 eval 下可以,**AOT 下坏掉**:deftype 常量按字段重建,内部的 MapEntry 变成 vector,
  一遍历就 `ClassCastException: PersistentVector cannot be cast to Map$Entry`。

## 影响面

凡是 AOT 打包、且有工具超过 8 个参数的下游:模型看到的参数顺序与声明不一致(判别字段 / 必填项不在前面),
也进不了稳定的 prompt cache 前缀(顺序虽确定,但与 eval 下不同,开发与发布两份)。不报错。

锦书的绕法:不信元数据里 map 的键序 —— 顶层按 `:tool/params`(vector,AOT 后有序)重排;过 8 键的嵌套属性表写成
键值对 vector `[[:action {…}] …]`,自己转成有序 map。能用,但每个下游都得知道。

## 建议

- 不要把有序性寄托在常量 map 上。可选做法:元数据里多放一份 `:tool/param-order`(vector),或让宏发射**构造代码**
  (运行时 `(ordered-map …)`)而不是常量;`tool-meta` / schema 读取侧按它出有序结构。
- 嵌套属性表给一个保序写法(比如接受键值对 vector),并在 docstring 写明 `(array-map …)` / `#ordered/map` 的坑。
- 加一条 AOT 判据(`compile` 后从 classes 加载再比顺序),eval 判据看不出这个问题。

## 库侧回复（2026-11-11）✅

已修(只编码未提交),三条建议都落地:

1. **不把有序性寄托在常量 map 上**。`deftool` 不再把 schema 作为常量塞进 var 元数据,改为**命名空间加载期**
   构造:宏发射 `defn` + `alter-meta!`,由 `im.ttalk.agent.tool/tool-meta` 在加载期解析参数并生成 schema。
   eval 与 AOT(tools.build `compile-clj`)走同一条路径,顺序由 `ordered-map`(按声明序排的 map)钉住。
   `:tool/schema` / `:tool/params` 两个键仍在元数据里,直接读 `(:tool/schema (meta v))` 的下游(含
   `tool_registry`、`tools`)不用改;锦书的 `:tool/params` 绕法也可以继续用。
2. **嵌套属性表给保序写法**:对象参数的 `:properties` 现在接受 `[[:章节 {…}] [:标题 {…}]]` 键值对 vector,
   库侧转成保序 map(map 字面量写法照旧;>8 键时 map 字面量仍受 reader 限制)。`(array-map …)` 写在
   schema 里不会被求值(schema 曾是常量)、`#ordered/map` 在 AOT 下会 CCE——两条都写进 docstring 与 README,
   请改用键值对 vector。
3. **AOT 判据**:`tool_test` 里加了一条——把 10 参数的 `deftool` 探针**编译成 class** 再加载(子进程,
   编译产物与源码分开),比 `input_schema.properties` 的键序;另有嵌套属性表的键序判据。
   用 0.5.442 的旧实现跑这条判据是红的(顶层顺序乱),换新实现转绿。

顺带修了一处 0.5.442 的次序 bug:参数约束 map 的排序比较器拿「声明用的键」去查「JSON Schema 键」的位次,
凡是被改名的键(`:min-length` → `:minLength` 等)都排到了末尾;现按 JSON Schema 键的声明序对齐。
