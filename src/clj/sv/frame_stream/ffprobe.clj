(ns sv.frame-stream.ffprobe
  (:require [clojure.java.shell :as sh]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Concept:
;;
;; Wraps the ffprobe command line tool of the ffmpeg package that
;; provides a lot of information about video and image files.

(defn ffprobe
  "Invokes ffprobe on the given `input` (File or URL string) and returns
   the ffprobe output as a clojure map."
  [input]
  (let [input* (if (instance? java.io.File
                              input)
                 (.getAbsolutePath input)
                 input)]
    (let [shell-result (sh/sh
                         "ffprobe"
                         "-v"
                         "quiet"
                         "-print_format"
                         "json"
                         "-show_format"
                         "-show_streams"
                         input*)]
      (when (not= (:exit shell-result) 0)
        (throw (ex-info "ffprobe failed" shell-result)))
      (let [result (json/parse-string (:out shell-result) true)]
        result))))

(defn acceptable-mp4?
  [ffprobe-result]
  (let [video-stream (some (fn [stream]
                             (when (= (:codec_type stream)
                                      "video")
                               stream))
                           (:streams ffprobe-result))]
    (and
      (str/includes? (get-in ffprobe-result
                             [:format :format_name])
                     "mp4")
      (= (:codec_name video-stream)
         "h264")
      (= (:pix_fmt video-stream)
         "yuv420p"))))

(comment
  (ffprobe "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")

  (acceptable-mp4? (ffprobe "a.mp4"))
  )
