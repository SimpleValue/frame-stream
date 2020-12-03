(ns sv.frame-stream.http-kit
  (:require [org.httpkit.server :as httpkit]
            [sv.frame-stream.core :as frame-stream]))

(defn not-found-handler
  [request]
  (-> {:status 404
       :body "404 not found (frame-stream)"}
      (frame-stream/add-cors-header)))

(defn ring-handler
  [request]
  (or (frame-stream/ring-handler request)
      (not-found-handler request)))

(defn start
  []
  (httpkit/run-server #'ring-handler
                      {:port (Long/valueOf
                              (or (System/getenv "PORT")
                                  "8090"))}))
(comment
  (def stop
    (start))

  (stop)
  )
