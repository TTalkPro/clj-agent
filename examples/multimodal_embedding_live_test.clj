(ns multimodal-embedding-live-test
  "多模态输入 × Embedding 的真机验证（对应 P12）。

   运行（按需设其一/其二）：
     ZHIPU_API_KEY=... clojure -M examples/multimodal_embedding_live_test.clj
     SILICONFLOW_API_KEY=... OPENROUTER_API_KEY=... clojure -M examples/...

   **只验单测证明不了的三件事**（wire 形状、切片、错误分类单测已覆盖，不重复）：
   1. 图片**真的到达模型**——用一张程序生成的图（内容只有本进程知道：几个色块），
      问模型「这张图主色是什么」，答得出才说明 base64 没白发；
   2. **URL 与内联两条路都通**（两家 wire 的 source 形状不同，只有真端点能判）；
   3. embedding **向量真的有语义**——同义句相似度显著高于无关句。
      这条是判据本身：随便返回一堆数也能过形状断言，过不了语义排序。

   断言钉机制与可判定的量（关键词命中、相似度序关系、维度、条数），
   **不钉模型措辞**——后者会波动，拿它当断言等于给 CI 埋雷。

   缺哪个 key 就跳过哪一段，不算失败（这是给人跑的验证脚本，不是 CI）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.embeddings :as emb]
            [im.ttalk.agent.provider.openai-compat-provider :as oc]
            [im.ttalk.agent.provider.zhipu :as zhipu])
  (:import [java.awt Color Graphics2D]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.util Base64]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(def failures (atom 0))
(def skipped (atom 0))

(defn check [description ok?]
  (if ok?
    (println "  PASS" description)
    (do (swap! failures inc)
        (println "  FAIL" description))))

(defn skip [description why]
  (swap! skipped inc)
  (println "  SKIP" description "——" why))

;;; ============================================================
;;; 测试图片：纯红色方块 + 白底（内容只有本进程知道）
;;; ============================================================

(defn red-square-png-base64
  "生成一张 200x200、中央大红方块的 PNG，返回 base64。
   用生成图而不是网图，是为了让「答对」只可能来自真看见了这张图。"
  []
  (let [img (BufferedImage. 200 200 BufferedImage/TYPE_INT_RGB)
        ^Graphics2D g (.createGraphics img)]
    (.setColor g Color/WHITE)
    (.fillRect g 0 0 200 200)
    (.setColor g Color/RED)
    (.fillRect g 40 40 120 120)
    (.dispose g)
    (let [baos (ByteArrayOutputStream.)]
      (ImageIO/write img "png" baos)
      (.encodeToString (Base64/getEncoder) (.toByteArray baos)))))

;;; ============================================================
;;; 1. 多模态：内联图片（智谱 GLM-4V 系列，OpenAI 兼容端点）
;;; ============================================================

(defn run-inline-image! []
  (println "\n[1] 内联图片 → 视觉模型（智谱 glm-4v-flash）")
  (if-not (System/getenv "ZHIPU_API_KEY")
    (skip "内联图片" "缺 ZHIPU_API_KEY")
    (let [provider (zhipu/create-provider {})
          model (or (System/getenv "VISION_MODEL") "glm-4v-flash")
          resp (proto/call-llm
                 provider
                 {:model model :max-tokens 256}
                 [(msg/user [(content/text-part "这张图的中央是什么颜色的方块？只回答颜色。")
                             (content/image-part (red-square-png-base64)
                                                 {:media-type "image/png"})])]
                 [])
          text (proto/extract-text provider resp)]
      (println "  模型答：" (str/trim (str text)))
      (check "答案命中「红」——base64 图片真的到达了模型"
             (boolean (re-find #"红|red|Red" (str text)))))))

;;; ============================================================
;;; 2. 多模态：URL 图片（OpenRouter，任选一个视觉模型）
;;; ============================================================

(defn run-url-image! []
  (println "\n[2] URL 图片 → 视觉模型（OpenRouter）")
  (let [key (System/getenv "OPENROUTER_API_KEY")
        url (or (System/getenv "TEST_IMAGE_URL")
                "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/280px-PNG_transparency_demonstration_1.png")]
    (if-not key
      (skip "URL 图片" "缺 OPENROUTER_API_KEY")
      (let [provider (oc/create-provider {:base-url "https://openrouter.ai/api/v1" :api-key key})
            model (or (System/getenv "OPENROUTER_VISION_MODEL") "openai/gpt-4o-mini")
            resp (proto/call-llm
                   provider
                   {:model model :max-tokens 256}
                   [(msg/user [(content/text-part "用一句话描述这张图里的主要物体。")
                               (content/image-part url)])]
                   [])
            text (proto/extract-text provider resp)]
        (println "  模型答：" (str/trim (str text)))
        (check "URL 图片路径通（模型给出了非空描述）"
               (and (string? text) (>= (count (str/trim text)) 5)))))))

;;; ============================================================
;;; 3. Embedding：语义排序（同义 > 无关）
;;; ============================================================

(defn- cosine [a b]
  (let [dot (reduce + 0.0 (map * a b))
        na (Math/sqrt (reduce + 0.0 (map #(* % %) a)))
        nb (Math/sqrt (reduce + 0.0 (map #(* % %) b)))]
    (if (or (zero? na) (zero? nb)) 0.0 (/ dot (* na nb)))))

(defn run-embeddings! [provider-key label]
  (println (str "\n[3] Embedding 语义排序（" label "）"))
  (let [e (try (emb/create-provider provider-key)
               (catch clojure.lang.ExceptionInfo ex ex))]
    (if (instance? Exception e)
      (skip (str "embedding " label) (ex-message ^Exception e))
      (let [texts ["今天北京天气怎么样？"      ;; 0 基准
                   "北京今日天气如何？"        ;; 1 同义
                   "红烧肉的做法是先焯水"]     ;; 2 无关
            {:keys [embeddings usage model]} (emb/embed e texts)
            [base same other] embeddings
            sim-same (cosine base same)
            sim-other (cosine base other)]
        (println (format "  model=%s dims=%d usage=%s" model (count base) (pr-str usage)))
        (println (format "  cos(同义)=%.4f  cos(无关)=%.4f" sim-same sim-other))
        (check "返回条数与入参同序等长" (= 3 (count embeddings)))
        (check "维度一致且非空" (and (pos? (count base))
                                     (apply = (map count embeddings))))
        (check "同义句相似度 > 无关句（向量真的有语义）" (> sim-same sim-other))
        (check "usage 已归一化为中立字段" (or (nil? usage) (contains? usage :total-tokens)))))))

(defn run-batching! [provider-key label]
  (println (str "\n[4] Embedding 批次切片（" label "，30 条 > 单次上限）"))
  (let [e (try (emb/create-provider provider-key {:batch-size 8})
               (catch clojure.lang.ExceptionInfo ex ex))]
    (if (instance? Exception e)
      (skip (str "batching " label) (ex-message ^Exception e))
      (let [texts (mapv #(str "第 " % " 条待向量化的文本") (range 30))
            {:keys [embeddings]} (emb/embed e texts)]
        (check "30 条经 4 次请求拼回，条数不丢不重" (= 30 (count embeddings)))
        (check "同一批内不同文本向量不同" (not= (first embeddings) (second embeddings)))))))

;;; ============================================================
;;; main
;;; ============================================================

(defn -main [& _]
  (println "=== 多模态 × Embedding 真机验证 ===")
  (run-inline-image!)
  (run-url-image!)
  (cond
    (System/getenv "SILICONFLOW_API_KEY") (do (run-embeddings! :siliconflow "SiliconFlow bge-m3")
                                              (run-batching! :siliconflow "SiliconFlow bge-m3"))
    (System/getenv "ZHIPU_API_KEY")       (do (run-embeddings! :zhipu "智谱 embedding-3")
                                              (run-batching! :zhipu "智谱 embedding-3"))
    (System/getenv "OPENAI_API_KEY")      (do (run-embeddings! :openai "OpenAI text-embedding-3-small")
                                              (run-batching! :openai "OpenAI text-embedding-3-small"))
    :else (skip "embedding" "缺 SILICONFLOW_API_KEY / ZHIPU_API_KEY / OPENAI_API_KEY"))

  (println (format "\n=== 结束：%d 失败，%d 跳过 ===" @failures @skipped))
  (System/exit (if (pos? @failures) 1 0)))

(-main)
