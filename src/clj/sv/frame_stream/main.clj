(ns sv.frame-stream.main
  (:require [sv.frame-stream.http-kit :as http-kit])
  (:gen-class))

(defn -main
  [& args]
  (let [stop (http-kit/start)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                       (fn []
                         (stop)))))
  )

(comment
  (-main)
  )
