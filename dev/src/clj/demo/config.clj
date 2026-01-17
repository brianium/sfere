(ns demo.config
  (:require [demo.app :as app]
            [integrant.core :as ig]))

(defmethod ig/halt-key! ::app/server [_ stop-fn]
  (when stop-fn
    (stop-fn)))

(def config
  {::app/store         {:type :atom}
   ::app/dispatch      {:store (ig/ref ::app/store)}
   ::app/with-datastar {:dispatch (ig/ref ::app/dispatch)}
   ::app/router        {:routes     app/routes
                        :middleware [(ig/ref ::app/with-datastar)]}
   ::app/handler       {:router (ig/ref ::app/router)}
   ::app/server        {:handler (ig/ref ::app/handler)
                        :port 3000}})
