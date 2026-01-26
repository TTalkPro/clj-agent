(ns im.ttalk.agent.core.pregel.barrier
  "Pregel Barrier - BSP 同步屏障

   实现 Bulk Synchronous Parallel (BSP) 模型的同步屏障。
   确保所有 Worker 完成当前超步后才能进入下一超步。

   使用 channel 实现屏障同步:
   1. Master 创建屏障
   2. 每个 Worker 完成后调用 arrive
   3. Master 调用 await-all 等待所有 Worker

   使用示例:

   (let [barrier (create-barrier 3)]
     ;; 启动 3 个 worker
     (dotimes [i 3]
       (go
         ;; ... 执行计算 ...
         (arrive barrier {:worker-id i :result ...})))
     ;; 等待所有 worker 完成
     (let [results (await-all barrier)]
       ;; 处理结果
       ...))"
  (:require [clojure.core.async :as async :refer [chan put! <!! close! go]]))

;;; ============================================================
;;; Barrier 创建
;;; ============================================================

(defn create-barrier
  "创建同步屏障

   参数:
   - n: 需要同步的参与者数量
   - opts: 可选参数
     :timeout-ms - 超时时间（毫秒）

   返回: barrier map"
  [n & {:keys [timeout-ms] :or {timeout-ms 60000}}]
  {:n n
   :channel (chan n)
   :timeout-ms timeout-ms})

;;; ============================================================
;;; Barrier 操作
;;; ============================================================

(defn arrive
  "到达屏障（Worker 调用）

   参数:
   - barrier: 屏障
   - result: 携带的结果数据（可选）

   返回: true（成功放入 channel）"
  ([barrier]
   (arrive barrier nil))
  ([barrier result]
   (put! (:channel barrier) result)
   true))

(defn await-all
  "等待所有参与者到达（Master 调用）

   参数:
   - barrier: 屏障

   返回: 所有参与者的结果列表
   异常: 超时时抛出异常"
  [barrier]
  (let [n (:n barrier)
        ch (:channel barrier)
        timeout-ms (:timeout-ms barrier)
        timeout-ch (async/timeout timeout-ms)]
    (loop [collected []
           remaining n]
      (if (zero? remaining)
        collected
        (let [[v port] (async/alts!! [ch timeout-ch])]
          (cond
            (= port timeout-ch)
            (throw (ex-info "Barrier timeout"
                            {:collected (count collected)
                             :expected n
                             :timeout-ms timeout-ms}))

            (nil? v)
            (throw (ex-info "Barrier channel closed unexpectedly"
                            {:collected (count collected)
                             :expected n}))

            :else
            (recur (conj collected v) (dec remaining))))))))

(defn await-all-async
  "异步等待所有参与者到达

   参数:
   - barrier: 屏障

   返回: channel，完成后会放入结果列表或异常"
  [barrier]
  (let [result-ch (chan 1)
        n (:n barrier)
        ch (:channel barrier)
        timeout-ms (:timeout-ms barrier)]
    (go
      (let [timeout-ch (async/timeout timeout-ms)]
        (loop [collected []
               remaining n]
          (if (zero? remaining)
            (put! result-ch {:ok collected})
            (let [[v port] (async/alts! [ch timeout-ch])]
              (cond
                (= port timeout-ch)
                (put! result-ch {:error {:type :timeout
                                         :collected (count collected)
                                         :expected n}})

                (nil? v)
                (put! result-ch {:error {:type :channel-closed
                                         :collected (count collected)
                                         :expected n}})

                :else
                (recur (conj collected v) (dec remaining))))))))
    result-ch))

(defn close-barrier
  "关闭屏障

   参数:
   - barrier: 屏障

   返回: nil"
  [barrier]
  (close! (:channel barrier))
  nil)

;;; ============================================================
;;; 可重用屏障
;;; ============================================================

(defn create-reusable-barrier
  "创建可重用的同步屏障

   与普通屏障不同，可重用屏障可以在多个超步中使用。

   参数:
   - n: 参与者数量
   - opts: 可选参数

   返回: reusable-barrier map"
  [n & {:keys [timeout-ms] :or {timeout-ms 60000}}]
  {:n n
   :channel-atom (atom (chan n))
   :timeout-ms timeout-ms})

(defn reset-barrier
  "重置可重用屏障（为下一超步准备）

   参数:
   - barrier: 可重用屏障

   返回: barrier"
  [barrier]
  (let [old-ch @(:channel-atom barrier)]
    (close! old-ch)
    (reset! (:channel-atom barrier) (chan (:n barrier))))
  barrier)

(defn arrive-reusable
  "到达可重用屏障

   参数:
   - barrier: 可重用屏障
   - result: 结果数据

   返回: true"
  ([barrier]
   (arrive-reusable barrier nil))
  ([barrier result]
   (put! @(:channel-atom barrier) result)
   true))

(defn await-all-reusable
  "等待所有参与者到达可重用屏障

   参数:
   - barrier: 可重用屏障

   返回: 结果列表"
  [barrier]
  (let [n (:n barrier)
        ch @(:channel-atom barrier)
        timeout-ms (:timeout-ms barrier)
        timeout-ch (async/timeout timeout-ms)]
    (loop [collected []
           remaining n]
      (if (zero? remaining)
        collected
        (let [[v port] (async/alts!! [ch timeout-ch])]
          (cond
            (= port timeout-ch)
            (throw (ex-info "Reusable barrier timeout"
                            {:collected (count collected)
                             :expected n}))

            (nil? v)
            (throw (ex-info "Reusable barrier channel closed"
                            {:collected (count collected)
                             :expected n}))

            :else
            (recur (conj collected v) (dec remaining))))))))
