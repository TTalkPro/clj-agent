(ns im.ttalk.http.jdk-transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http.jdk-transport :as jdk]
            [im.ttalk.http.test-server :as ts]
            [im.ttalk.http.transport :as transport]))

(deftest jdk-contract-basics
  (let [t (jdk/transport)]
    (is (transport/transport? t))
    (is (= #{:string :bytes :discard :input-stream :stream-lines} (:capabilities t)))
    (ts/with-server [url (fn [ex]
                           (.add (.getResponseHeaders ex) "X-Multi" "1")
                           (.add (.getResponseHeaders ex) "x-multi" "2")
                           (let [b (.readAllBytes (.getRequestBody ex))]
                             (.sendResponseHeaders ex 200 (alength b))
                             (when (pos? (alength b)) (.write (.getResponseBody ex) b))))]
      (let [r (transport/request t {:method :post :url url :body "abc"})]
        (is (= 200 (:status r)))
        (is (= ["1" "2"] (get-in r [:headers "x-multi"])))
        (is (= "abc" (:body r))))
      (let [r (.get (transport/request-async t {:url url :as :bytes}) 5 java.util.concurrent.TimeUnit/SECONDS)]
        (is (bytes? (:body r)))))))
