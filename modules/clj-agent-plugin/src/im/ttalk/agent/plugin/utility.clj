(ns im.ttalk.agent.plugin.utility
  "通用工具集：calculator, current-time"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(deftool calculator
  "数学计算器，支持基本 Clojure 数学表达式求值，如 (+ 1 2)、(* 3 4)、(Math/sqrt 16)"
  [[expression :string "数学表达式，如 (+ 1 2) 或 (* 3 (+ 2 1))"]]
  (try
    (let [allowed-ns #{'clojure.core}
          result (eval (read-string expression))]
      (str result))
    (catch Exception e
      (str "计算错误: " (.getMessage e)))))

(deftool current-time
  "获取当前系统时间"
  [[format :string "时间格式字符串（可选，默认 yyyy-MM-dd HH:mm:ss）" :default "yyyy-MM-dd HH:mm:ss"]]
  (let [now (LocalDateTime/now)
        fmt (try
              (DateTimeFormatter/ofPattern (or format "yyyy-MM-dd HH:mm:ss"))
              (catch Exception _
                (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))]
    (.format now fmt)))

(def all-tools
  "通用工具集（tool vars 列表）"
  [#'calculator #'current-time])
