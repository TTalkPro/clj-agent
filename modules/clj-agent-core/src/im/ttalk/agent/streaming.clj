(ns im.ttalk.agent.streaming
  "流式取消令牌（cancel token）。

   用途：让应用层在需要时（如 web handler 客户端断连）中止正在进行的 chat-stream——
   取消上游 HTTP（停止烧 token）并让 ReAct 循环停在当前回合。

   用法（应用层）：
     (require '[im.ttalk.agent.streaming :as st]
              '[im.ttalk.agent.client :as agent])
     (def token (st/make-cancel-token))
     (future (agent/chat-stream a \"消息\" on-token {:cancel-token token}))
     ;; 客户端断连时：
     (st/request-cancel! token)

   设计：令牌穿过 chat-stream → react 循环（回合间据它停止）；react 在调 provider 流式前
   把动态 var *register-cancel* 绑成「登记当前令牌的在途 cancel-fn」。provider 拿到
   stream_client 的 cancel 后登记之——**无需改协议 / config 签名**。整条同步调用链同线程，
   动态 var 可见；request-cancel! 直接操作令牌（可跨线程）。")

(set! *warn-on-reflection* true)

(defn make-cancel-token
  "创建取消令牌。"
  []
  (atom {:cancelled? false :cancel-fn nil}))

(defn cancel-token?
  [x]
  (and (instance? clojure.lang.Atom x) (map? @x) (contains? @x :cancelled?)))

(defn cancelled?
  "令牌是否已被请求取消（nil 令牌返回 false）。"
  [token]
  (boolean (and token (:cancelled? @token))))

(defn request-cancel!
  "请求取消：标记令牌 + 调用当前在途流的 cancel-fn（取消上游 HTTP）。幂等、可跨线程。"
  [token]
  (when token
    (let [{:keys [cancel-fn]} (swap! token assoc :cancelled? true)]
      (when cancel-fn (cancel-fn)))))

;;; ============================================================
;;; provider ↔ token 的桥：动态 var（同步调用链内可见）
;;; ============================================================

(def ^:dynamic *register-cancel*
  "react 在调 provider 流式前绑定为「登记当前令牌 cancel-fn」的函数；缺省 nil。"
  nil)

(defn register-cancel!
  "provider 调用：把 stream_client 的 cancel-fn 登记给当前令牌
   （仅当 react 绑定了 *register-cancel* 且 cancel-fn 非 nil）。"
  [cancel-fn]
  (when (and *register-cancel* cancel-fn)
    (*register-cancel* cancel-fn)))

(defn binding-register
  "react 用：返回登记函数，绑到 *register-cancel*。登记时若令牌已取消则立即调 cancel-fn
   （消除「检查-登记」竞态：取消请求若发生在登记前，登记时补调）。"
  [token]
  (fn [cancel-fn]
    (swap! token assoc :cancel-fn cancel-fn)
    (when (cancelled? token) (cancel-fn))))
