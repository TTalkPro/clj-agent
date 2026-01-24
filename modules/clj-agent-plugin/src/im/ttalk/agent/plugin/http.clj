(ns im.ttalk.agent.plugin.http
  "HTTP 请求工具集"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.plugin :refer [defplugin]]
            [im.ttalk.agent.core.http.client :as http]
            [im.ttalk.agent.plugin.helpers :as helpers]))

(deftool http-get
  "发送 HTTP GET 请求获取网页内容"
  [[url :string "完整的 URL 地址"]
   [timeout :int "超时时间（毫秒），默认 10000" :default 10000]]
  (try
    (let [response (http/get url :timeout (or timeout 10000))]
      (if (:error response)
        (str "请求失败: " (:error response))
        (let [truncated (helpers/truncate-http-response response 10000)]
          (str (:body truncated)))))
    (catch Exception e
      (str "请求失败: " (.getMessage e)))))

(deftool http-post
  "发送 HTTP POST 请求"
  [[url :string "完整的 URL 地址"]
   [body :string "请求体内容" :default ""]
   [content-type :string "内容类型，默认 application/json" :default "application/json"]
   [timeout :int "超时时间（毫秒），默认 10000" :default 10000]]
  (try
    (let [response (http/post url
                              :body body
                              :headers {"Content-Type" (or content-type "application/json")}
                              :timeout (or timeout 10000))]
      (if (:error response)
        (str "请求失败: " (:error response))
        (let [truncated (helpers/truncate-http-response response 10000)]
          (str "状态: " (:status truncated) "\n"
               "响应: " (:body truncated)))))
    (catch Exception e
      (str "请求失败: " (.getMessage e)))))

(deftool http-put
  "发送 HTTP PUT 请求"
  [[url :string "完整的 URL 地址"]
   [body :string "请求体内容" :default ""]
   [content-type :string "内容类型，默认 application/json" :default "application/json"]
   [timeout :int "超时时间（毫秒），默认 10000" :default 10000]]
  (try
    (let [response (http/put url
                             :body body
                             :headers {"Content-Type" (or content-type "application/json")}
                             :timeout (or timeout 10000))]
      (if (:error response)
        (str "请求失败: " (:error response))
        (let [truncated (helpers/truncate-http-response response 10000)]
          (str "状态: " (:status truncated) "\n"
               "响应: " (:body truncated)))))
    (catch Exception e
      (str "请求失败: " (.getMessage e)))))

(deftool http-delete
  "发送 HTTP DELETE 请求"
  [[url :string "完整的 URL 地址"]
   [timeout :int "超时时间（毫秒），默认 10000" :default 10000]]
  (try
    (let [response (http/delete url :timeout (or timeout 10000))]
      (if (:error response)
        (str "请求失败: " (:error response))
        (let [truncated (helpers/truncate-http-response response 10000)]
          (str "状态: " (:status truncated) "\n"
               "响应: " (:body truncated)))))
    (catch Exception e
      (str "请求失败: " (.getMessage e)))))

(defplugin http-tools "HTTP 请求工具集" http-get http-post http-put http-delete)
