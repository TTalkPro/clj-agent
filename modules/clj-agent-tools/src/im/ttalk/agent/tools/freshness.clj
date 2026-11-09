(ns im.ttalk.agent.tools.freshness
  "读写新鲜度闸（T-03；出处 Keelson `ReadFreshness.cs` + `FileReadTable.cs`，借鉴 coxswain `seen.ss`）。

   已读表：真实路径 ⇒ 最近一次**交给模型**的内容 tag。**宿主记，不让模型回传**——模型的上下文
   会被裁剪压缩，tag 交给它保管会丢。

   ⚠️ 登记点 = 凡把 tag 交给模型的地方（`read_file` 的 `[路径#TAG]` 头、写类工具回的「新的 TAG」），
   一处都不能漏——漏一处就是「读过却说你没读过」。写成功之后也登记（模型知道自己写成了什么）。

   表的来处：工作区的 `:reads`（缺省每个 `workspace-tools` 实例一张）；`:read-tracking? false` ⇒ 没有表，
   闸与登记全不做。"
  (:require [im.ttalk.agent.tools.workspace :refer [fail!]]))

(defn read-table
  "新的已读表（宿主要按会话隔离时自己建一张，经 `{:reads ..}` 传给 workspace-tools）。"
  []
  (atom {}))

(defn- k [path] (str path))

(defn note!
  "登记：path 此刻交给模型的是 tag 那份内容。表为 nil ⇒ 什么都不做。"
  [table path tag]
  (when table (swap! table assoc (k path) tag)))

(defn forget!
  "忘掉一条（文件被删 / 改名走了）。"
  [table path]
  (when table (swap! table dissoc (k path))))

(defn tag-of [table path]
  (when table (get @table (k path))))

(defn check
  "这次写得不得：current-tag 是文件**现在**的内容 tag。
   ⇒ :fresh | :not-read | :changed（表为 nil ⇒ :fresh，即闸关着）。
   ⚠️ 文件在不在不归这里判：新建不要求读过，由调用方跳过这道闸。"
  [table path current-tag]
  (if (nil? table)
    :fresh
    (let [seen (tag-of table path)]
      (cond (nil? seen) :not-read
            (.equalsIgnoreCase ^String seen ^String current-tag) :fresh
            :else :changed))))

(defn check!
  "过闸；不过 ⇒ fail!（tools/freshness-not-read | tools/freshness-changed）。shown 是报错里给模型看的路径。"
  [table path shown current-tag]
  (case (check table path current-tag)
    :fresh nil
    :not-read (fail! "tools/freshness-not-read"
                     (format "%s 你还没有读过 —— 先用 read_file 看一眼再改。（改一个没看过的文件，等于对着一份猜出来的内容动手。）"
                             shown))
    :changed (fail! "tools/freshness-changed"
                    (format "%s 在你读完之后变了（现在是 %s，你上次读到的是 %s）。先用 read_file 重新读一遍，再照新内容改。"
                            shown current-tag (tag-of table path)))))

(defn header
  "`read_file` 输出头：`[路径#TAG]`。"
  [shown tag]
  (str "[" shown "#" tag "]"))

(defn new-tag-note [tag] (str "新的 TAG 是 " tag "。"))
