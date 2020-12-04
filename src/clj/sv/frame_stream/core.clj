(ns sv.frame-stream.core
  (:require [babashka.process :as p]
            [sv.frame-stream.ffprobe :as ffprobe]
            [ring.middleware.params :as m]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn extract-video-metadata
  [ffprobe-result]
  (some
    (fn [stream]
      (when-let [width (:width stream)]
        {:width width
         :height (:height stream)}))
    (:streams ffprobe-result)))

(comment
  (def url
    "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")

  (def ffprobe-result
    (ffprobe/ffprobe url))

  (extract-video-metadata ffprobe-result)
  )

(def default-duration
  20)

(defn ffmpeg-path
  "See: https://github.com/bytedeco/javacpp-presets/blob/master/ffmpeg/src/main/java/org/bytedeco/ffmpeg/ffmpeg.java"
  []
  (org.bytedeco.javacpp.Loader/load
   org.bytedeco.ffmpeg.ffmpeg))

(defn ffmpeg-command
  [{:keys [url start-time duration]}]
  [(ffmpeg-path)
   "-t" (or duration
            default-duration)
   "-ss" (or start-time 0)
   "-i" url
   "-r" "30" "-f" "rawvideo" "-pix_fmt" "abgr" "-"])

(def bytes-per-pixel
  ;; rgba
  4)

(defn calc-frame-size
  [{:keys [width height]}]
  (* width height bytes-per-pixel))

(def buffer-size
  ;; buffers
  10
  ;; frames.
  )

(defn ffmpeg!
  [{:keys [url] :as params}]
  (let [frame-size (calc-frame-size params)
        in ^java.io.InputStream (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)
        queue (java.util.concurrent.ArrayBlockingQueue. buffer-size)
        process (p/process (ffmpeg-command params)
                           {:out out
                            ;; :err :inherit
                            })]
    (future
      (loop []
        (if-not (.isAlive ^Process (:proc process))
          (.put queue
                :closed)
          (let [frame-bytes (byte-array frame-size)]
            (com.google.common.io.ByteStreams/readFully
             in
             frame-bytes)
            (let [buffered-image (com.pngencoder.PngEncoderBufferedImageConverter/createFrom4ByteAbgr
                                  frame-bytes
                                  (:width params)
                                  (:height params))
                  png-bytes (-> (com.pngencoder.PngEncoder.)
                                (.withBufferedImage buffered-image)
                                (.toBytes))]
              (.put queue
                    png-bytes))
            (recur)))))
    {:process process
     :queue queue}))

(comment
  (ffmpeg-command url)

  (def params
    {:url url
     :width 1280
     :height 720})

  (def ffmpeg-process
    (ffmpeg! params))
  )

(defonce state
  (atom nil))

(defn add-cors-header
  [response]
  (update-in response
             [:headers]
             assoc
             "Access-Control-Allow-Origin"
             "*"
             "Access-Control-Allow-Headers"
             "content-type"))

(defn emit!
  [message]
  (prn message))

(defn start-handler
  [request]
  (when (and (= (:request-method request)
                :post)
             (= (:uri request)
                "/frame-stream/start"))
    (let [params (json/parse-string (slurp (:body request))
                                    true)
          ffprobe-result (ffprobe/ffprobe emit!
                                          (:url params))
          uuid (java.util.UUID/randomUUID)
          video-metadata (extract-video-metadata ffprobe-result)]
      (swap! state
             assoc-in
             [:ffmpeg-processes
              uuid]
             (merge
              video-metadata
              (ffmpeg! (merge params
                              video-metadata))))
      (-> {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string (assoc (extract-video-metadata ffprobe-result)
                                              :uuid (str uuid)))}
          (add-cors-header)))))

(defn next-frame-handler
  [request]
  (when (and (= (:request-method request)
                :get)
             (= (:uri request)
                "/frame-stream/next-frame"))
    (let [request* (m/assoc-query-params request
                                         "UTF-8")
          uuid (java.util.UUID/fromString
                (get-in request*
                        [:query-params
                         "uuid"]))]
      (when-let [ffmpeg-process (get-in @state
                                        [:ffmpeg-processes
                                         uuid])]
        (let [{:keys [queue]} ffmpeg-process
              frame-bytes (.take ^java.util.concurrent.BlockingQueue queue)]
          (if (= frame-bytes
                 :closed)
            (do
              (swap! state update :ffmpeg-processes dissoc uuid)
              nil)
            (-> {:status 200
                 :headers {"Content-Type" "image/png"}
                 :body frame-bytes}
                (add-cors-header))))))))

(defn cors-preflight-handler
  [request]
  (when (and (= (:request-method request)
                :options)
             (str/starts-with? (:uri request)
                               "/frame-stream/"))
    (-> {:status 200}
        (add-cors-header))))

(defn health-handler
  [request]
  (when (and (= (:request-method request)
                :get)
             (= (:uri request)
                "/frame-stream/health"))
    (-> {:status 200
         :headers {"Content-Type" "text/plain"}
         :body "ok (frame-stream-server)"}
        (add-cors-header))))

(defn ring-handler
  [request]
  (some
   (fn [handler]
     (handler request))
   [cors-preflight-handler
    start-handler
    next-frame-handler
    health-handler]))

(comment
  (println "curl -d '" (pr-str {:url url}) "' " "http://localhost:8080/frame-stream/start")
  (require '[clj-http.client :as http])

  (def start-response
    (http/request {:request-method :post
                   :url "http://localhost:8080/frame-stream/start"
                   :body (pr-str {:url url
                                  :start-time 60.5})
                   :as :json
                   }))

  (time
    (dotimes [n 30]
      (def next-frame-response
        (http/request {:request-method :get
                       :url "http://localhost:8080/frame-stream/next-frame"
                       :query-params {:uuid (get-in start-response
                                                    [:body
                                                     :uuid])}
                       :as :stream
                       }))

      (io/copy (:body next-frame-response)
               (io/file "tmp/frame.raw"))))

  (let [{:keys [width height]} (:body start-response)]
    (println "ffplay" "-f" "rawvideo" "-pixel_format" "rgba" "-video_size" (str width "x" height) "tmp/frame.raw"))

  )
