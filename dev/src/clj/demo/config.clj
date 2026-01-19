(ns demo.config
  (:require [demo.app :as app]
            [integrant.core :as ig]))

(defmethod ig/halt-key! ::app/server [_ stop-fn]
  (when stop-fn
    (stop-fn)))

(def config
  {::app/store         {:type :caffeine
                        :ttl-seconds 30}
   ;; NOTE: No on-evict callback. "User left" is only broadcast on explicit
   ;; Leave action. TTL silently cleans up abandoned connections.
   ;; Real-time presence detection requires client heartbeats (see spec 011).
   ::app/dispatch      {:store (ig/ref ::app/store)}
   ::app/with-datastar {:dispatch (ig/ref ::app/dispatch)}
   ::app/router        {:routes     app/routes
                        :middleware [(ig/ref ::app/with-datastar)]}
   ::app/handler       {:router (ig/ref ::app/router)}
   ::app/server        {:handler (ig/ref ::app/handler)
                        :port 3000}})
