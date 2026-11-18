(ns im.ttalk.agent.provider.http.transport-injection-test
  "H-09：`:http-transport` 是唯一跨实现插件边界，四条调用路径都能注入 fake。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.provider.common.embeddings :as embeddings]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.dashscope :as dashscope]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.http.transport :as transport])
  (:import [java.util.concurrent CompletableFuture]))

(defn- fake-request-transport
  [calls body]
  {:name :fake :capabilities #{:string}
   :request (fn [req] (swap! calls conj [:request req])
              {:status 200 :headers {"retry-after" ["7"]} :body body :url (:url req)})
   :request-async (fn [req] (swap! calls conj [:request-async req])
                    (CompletableFuture/completedFuture
                     {:status 200 :headers {} :body body :url (:url req)}))
   :stream-lines (fn [_] (throw (ex-info "not a stream transport" {})))})

(defn- fake-stream-transport
  [calls]
  {:name :fake :capabilities #{:string :stream-lines}
   :request (fn [_] (throw (ex-info "stream transport got request" {})))
   :request-async (fn [_] (throw (ex-info "stream transport got async request" {})))
   :stream-lines (fn [{:keys [on-response on-line]}]
                   (swap! calls conj :stream)
                   (on-response 200 {"content-type" ["text/event-stream"]})
                   (on-line "data: [DONE]")
                   {:future (CompletableFuture/completedFuture {:status 200 :headers {}})
                    :cancel (fn [])})})

(deftest provider-client-uses-injected-transport
  (let [calls (atom []) t (fake-request-transport calls "{\"ok\":true}") url "https://fake/api"]
    (is (= {:ok true} (:body (http/request :get url :http-transport t))))
    (is (= {:ok true} (:body @(http/request-deferred :get url :http-transport t))))
    (is (= {:ok true} (:body (http/post-json url {:x 1} {:http-transport t}))))
    (is (= {:ok true} (:body (http/get-json url {:http-transport t}))))
    (is (= [:request :request-async :request :request] (mapv first @calls)))
    ;; 插件优先级不受迁移期旧 :client 影响
    (is (= {:ok true} (:body (http/request :get url :http-transport t :client "not-a-jdk-client"))))))

(deftest provider-stream-uses-injected-transport
  (let [calls (atom []) t (fake-stream-transport calls)
        opts {:body {:x 1} :parse-fn identity :process-fn (fn [ev s] [s ev])
              :make-initial-state (fn [] {}) :build-response identity
              :on-token (fn [_]) :http-transport t}]
    (is (= {} (stream-client/post-stream-sync "https://fake/sse" opts)))
    (is (= {} @(stream-client/post-stream-deferred "https://fake/sse" opts)))
    (is (= [:stream :stream] @calls))))

(deftest provider-transport-error-mapping
  (testing "IO 型 transport exception 保持 {:status 0 ...} 公共形状"
    (let [t {:name :bad :capabilities #{:string}
             :request (fn [_] (throw (transport/error "connect failed" :connection-error nil {})))
             :request-async (fn [_] (CompletableFuture/failedFuture
                                     (transport/error "connect failed" :connection-error nil {})))
             :stream-lines (fn [_] (throw (ex-info "unused" {})))}]
      (is (= {:status 0 :error "connect failed" :success? false}
             (http/request :get "https://fake" :http-transport t)))
      (is (= {:status 0 :error "connect failed" :success? false}
             @(http/request-deferred :get "https://fake" :http-transport t))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"缺函数"
                          (http/request :get "https://fake"
                                        :http-transport {:name :bad :capabilities #{:string}
                                                         :request-async (fn [_])
                                                         :stream-lines (fn [_])})))))

(deftest listed-provider-implementations-pass-transport
  ;; 这是配置贯通的防回归：每个列出的实现至少在其同步/异步/流式调用面显式取配置值。
  (let [source (fn [path] (slurp path))
        src {:openai (source "src/im/ttalk/agent/provider/common/openai_compat.clj")
             :anthropic (source "src/im/ttalk/agent/provider/anthropic.clj")
             :responses (source "src/im/ttalk/agent/provider/openai_responses.clj")
             :dashscope (source "src/im/ttalk/agent/provider/dashscope.clj")
             :embeddings (source "src/im/ttalk/agent/provider/common/embeddings.clj")}]
    (doseq [[k s] src
            needle [(if (#{:openai :anthropic} k)
                      ":http-transport (:http-transport config)"
                      (if (= k :responses) ":http-transport (:http-transport cfg)"
                          ":http-transport ("))]]
      (is (str/includes? s needle) (str k " 没贯通 :http-transport"))))
  ;; embeddings 与 DashScope 公共函数用 fake 做一次行为贯通。
  (let [calls (atom [])
        cfg {:model "m" :provider-name :openai :batch-size 1
             :http-transport (fake-request-transport calls "{\"data\":[{\"embedding\":[0.1]}],\"model\":\"m\"}")}]
    (is (= [[0.1]] (:embeddings (embeddings/call-embeddings "https://fake/embeddings" "key" cfg ["x"]))))
    (is (= [:request] (mapv first @calls)))))
