(ns demo.server
  (:require [org.httpkit.server :as hk-server]))

(defn httpkit-server
  "Start an HTTP Kit server. Returns a stop function."
  [{:keys [handler port]
    :or {port 3000}}]
  (println (str "Starting server on http://localhost:" port))
  (hk-server/run-server handler {:port port}))
