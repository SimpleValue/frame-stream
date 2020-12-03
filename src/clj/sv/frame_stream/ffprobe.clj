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
  [emit input]
  (let [input* (if (instance? java.io.File
                              input)
                 (.getAbsolutePath input)
                 input)
        args ["ffprobe"
              "-v"
              "quiet"
              "-print_format"
              "json"
              "-show_format"
              "-show_streams"
              input*]]
    (emit {:message/type :sv.frame-stream/ffprobe
           :args args})
    (let [shell-result (apply sh/sh
                              args)]
      (when (not= (:exit shell-result) 0)
        (emit {:message/type :sv.frame-stream/ffprobe-failed
               :args args
               :shell-result shell-result})
        (throw (ex-info "ffprobe failed" shell-result)))
      (let [result (json/parse-string (:out shell-result) true)]
        result))))
