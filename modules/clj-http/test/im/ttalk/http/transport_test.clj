(ns im.ttalk.http.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http.transport :as transport]))

(defn- t [] {:name :fake :capabilities #{:string}
             :request (fn [req] req)
             :request-async (fn [req] (java.util.concurrent.CompletableFuture/completedFuture req))
             :stream-lines (fn [req] {:future (java.util.concurrent.CompletableFuture/completedFuture req)
                                      :cancel (fn [])})})

(deftest contract-validation
  (is (not (transport/transport? {})))
  (doseq [t [(dissoc (t) :request)
             (assoc (t) :capabilities [:string])
             (assoc (t) :close 1)]]
    (is (not (transport/transport? t)))
    (is (thrown? clojure.lang.ExceptionInfo (transport/assert-transport! t))))
  (is (transport/transport? (assoc (t) :close (fn [])))))

(deftest facade-missing-functions
  (doseq [f [transport/request transport/request-async transport/stream-lines]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"缺函数"
                          (f {:capabilities #{}} (constantly nil))))))

(deftest normalize
  (let [req (transport/request (t) {:method "post" :url "https://x"
                                    :headers {"Authorization" "a" :Content-Type ["b"]
                                              "x-multi" (java.util.List/of "1" "2")}
                                    :body "x"})]
    (is (= {"authorization" ["a"] "content-type" ["b"] "x-multi" ["1" "2"]}
           (select-keys (:headers req) ["authorization" "content-type" "x-multi"])))
    (let [req (transport/request (t) {:method :post :url "https://x" :body "x"})]
      (is (= ["application/json"] (get (:headers req) "content-type")))))
  (doseq [body [1 {:a 1} (java.io.ByteArrayInputStream. (.getBytes "x"))]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"body"
                          (transport/request (t) {:url "https://x" :body body})))))

(deftest response-and-errors
  (is (= {"x-a" ["1" "2"]} (:headers (transport/normalize-response {:status 200 :headers {"X-A" ["1" "2"]}}))))
  (let [c (ex-info "cause" {})]
    (is (transport/error? (transport/error "boom" :connection-error c {:x 1}) :connection-error))
    (is (= c (::transport/cause (ex-data (transport/error "boom" :timeout-error c nil)))))
    (is (transport/io-error? (transport/error "boom" :timeout-error c nil)))
    (is (not (transport/io-error? (transport/error "bad" :invalid-request c nil))))))
