(ns im.ttalk.agent.provider.multimodal-test
  "多模态内容部件 → 各家 wire 的翻译。

   钉三件事：
   1. 同一份中立消息在 OpenAI / Anthropic 两条 wire 上各自成型（可移植性本身）；
   2. 不支持的组合**抛规范错误**而不是静默丢内容（丢了内容模型只会答非所问）；
   3. 厂商原生块与中立部件混用时，原生块原样透传（既有逃生通道不被新机制吃掉）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.wire.anthropic :as wa]
            [im.ttalk.agent.provider.wire.openai :as wo]))

(defn- user-content
  [wire-msgs]
  (-> wire-msgs first :content))

;;; ============================================================
;;; OpenAI wire
;;; ============================================================

(deftest openai-image-parts-test
  (testing "URL 图片 → image_url；内联图片 → data URI"
    (let [{:keys [messages]}
          (wo/neutral->wire [(msg/user [(content/text-part "这是什么？")
                                        (content/image-part "https://x/a.png")
                                        (content/image-part "QUJD" {:media-type "image/png"})])])
          c (user-content messages)]
      (is (= {:type "text" :text "这是什么？"} (nth c 0)))
      (is (= {:type "image_url" :image_url {:url "https://x/a.png"}} (nth c 1)))
      (is (= {:type "image_url" :image_url {:url "data:image/png;base64,QUJD"}} (nth c 2)))))

  (testing ":detail 透传（OpenAI 的图片精度档位）"
    (let [part (assoc (content/image-part "https://x/a.png") :detail "low")
          {:keys [messages]} (wo/neutral->wire [(msg/user [part])])]
      (is (= "low" (get-in (first (user-content messages)) [:image_url :detail]))))))

(deftest openai-audio-and-pdf-test
  (testing "wav/mp3 内联音频 → input_audio"
    (let [{:keys [messages]}
          (wo/neutral->wire [(msg/user [(content/audio-part "QUJD" {:media-type "audio/wav"})])])]
      (is (= {:type "input_audio" :input_audio {:data "QUJD" :format "wav"}}
             (first (user-content messages)))))
    (let [{:keys [messages]}
          (wo/neutral->wire [(msg/user [(content/audio-part "QUJD" {:media-type "audio/mpeg"})])])]
      (is (= "mp3" (get-in (first (user-content messages)) [:input_audio :format])))))

  (testing "PDF 内联 → file.file_data，缺文件名时按序号兜底"
    (let [{:keys [messages]}
          (wo/neutral->wire [(msg/user [(content/text-part "读一下")
                                        (content/file-part "QUJD" {:media-type "application/pdf"})])])
          part (second (user-content messages))]
      (is (= "part-1.pdf" (get-in part [:file :filename])))
      (is (= "data:application/pdf;base64,QUJD" (get-in part [:file :file_data]))))))

(deftest openai-unsupported-combinations-test
  (testing "URL 音频 / URL PDF / 未知类型 —— 一律抛不可重试的规范错误，不静默丢"
    (doseq [[label part] [["URL 音频" (content/audio-part "https://x/a.wav")]
                          ["URL PDF" (content/file-part "https://x/a.pdf")]
                          ["未知类型" (content/file-part "QUJD" {:media-type "application/zip"})]
                          ["非 wav/mp3 音频" (content/audio-part "QUJD" {:media-type "audio/flac"})]]]
      (testing label
        (let [e (try (wo/neutral->wire [(msg/user [part])])
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str label " 应抛错"))
          (is (= :validation-error (:type (ex-data e))))
          (is (false? (:retryable? (ex-data e))))
          (is (= :openai (:provider (ex-data e)))))))))

(deftest openai-passthrough-test
  (testing "纯字符串 content 一字不动（既有行为不变）"
    (let [{:keys [messages]} (wo/neutral->wire [(msg/user "你好")])]
      (is (= "你好" (user-content messages)))))

  (testing "厂商原生块（:type 字符串）原样透传，可与中立部件混用"
    (let [raw {:type "image_url" :image_url {:url "https://raw" :detail "high"}}
          {:keys [messages]} (wo/neutral->wire [(msg/user [raw (content/text-part "混用")])])]
      (is (= raw (first (user-content messages))))
      (is (= {:type "text" :text "混用"} (second (user-content messages)))))))

;;; ============================================================
;;; Anthropic wire
;;; ============================================================

(deftest anthropic-image-and-document-test
  (testing "内联图片 → base64 source；URL 图片 → url source"
    (let [{:keys [messages]}
          (wa/neutral->wire [(msg/user [(content/text-part "这是什么？")
                                        (content/image-part "QUJD" {:media-type "image/png"})
                                        (content/image-part "https://x/a.png")])])
          c (user-content messages)]
      (is (= {:type "text" :text "这是什么？"} (nth c 0)))
      (is (= {:type "image" :source {:type "base64" :media_type "image/png" :data "QUJD"}}
             (nth c 1)))
      (is (= {:type "image" :source {:type "url" :url "https://x/a.png"}} (nth c 2)))))

  (testing "PDF → document 块，文件名进 :title"
    (let [{:keys [messages]}
          (wa/neutral->wire [(msg/user [(content/file-part "QUJD" {:media-type "application/pdf"
                                                                   :filename "报告.pdf"})])])
          part (first (user-content messages))]
      (is (= "document" (:type part)))
      (is (= "报告.pdf" (:title part)))
      (is (= {:type "base64" :media_type "application/pdf" :data "QUJD"} (:source part))))))

(deftest anthropic-unsupported-test
  (testing "Anthropic 不收音频 → 规范错误"
    (let [e (try (wa/neutral->wire [(msg/user [(content/audio-part "QUJD" {:media-type "audio/wav"})])])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :validation-error (:type (ex-data e))))
      (is (= :anthropic (:provider (ex-data e))))))

  (testing "内联图片的通配 media-type 在客户端就拦下（服务端只会回一个看不懂的 400）"
    (let [e (try (wa/neutral->wire
                   [(msg/user [(assoc (content/image-part "QUJD" {:media-type "image/png"})
                                      :media-type "image/*")])])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :validation-error (:type (ex-data e))))))

  (testing "但 URL 图片带通配类型是允许的——url source 本就不带 media_type"
    (is (= {:type "url" :url "https://x/a"}
           (:source (first (user-content (:messages (wa/neutral->wire
                                                      [(msg/user [(content/image-part "https://x/a")])])))))))))

(deftest anthropic-passthrough-test
  (testing "原生块向量（citations 的 document 等）原样透传"
    (let [raw [{:type "document" :source {:type "text" :media_type "text/plain" :data "文档"}}
               {:type "text" :text "问题"}]
          {:keys [messages]} (wa/neutral->wire [(msg/user raw)])]
      (is (= raw (user-content messages)))))

  (testing "多模态 system 消息取其文本（Anthropic 的 system 是顶层字符串参数）"
    (let [{:keys [system]} (wa/neutral->wire [(msg/system [(content/text-part "你是助手")])
                                              (msg/user "hi")])]
      (is (= "你是助手" system))))

  (testing "无 system 消息时仍为 nil（既有行为）"
    (is (nil? (:system (wa/neutral->wire [(msg/user "hi")]))))))

;;; ============================================================
;;; 跨 provider：同一份历史两边都能发
;;; ============================================================

(deftest portable-across-providers-test
  (let [history [(msg/system "你是视觉助手")
                 (msg/user [(content/text-part "图里有几只猫？")
                            (content/image-part "https://x/cats.png")])
                 (msg/assistant "两只")]
        openai (wo/neutral->wire history)
        anthropic (wa/neutral->wire history)]
    (testing "OpenAI：system 内联在 messages 里"
      (is (= 3 (count (:messages openai))))
      (is (= "system" (:role (first (:messages openai))))))
    (testing "Anthropic：system 提到顶层，messages 只剩两条"
      (is (= "你是视觉助手" (:system anthropic)))
      (is (= 2 (count (:messages anthropic)))))
    (testing "图片在两边各自成型，中立历史一份不改"
      (is (= "image_url" (:type (second (user-content (drop 1 (:messages openai)))))))
      (is (= "image" (:type (second (user-content (:messages anthropic)))))))))
