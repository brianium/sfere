(ns dev
  (:require [clj-reload.core :as reload]
            [demo.app :as app]
            [demo.config :refer [config]]
            [demo.system :as system]
            [portal.api :as p]
            [ascolais.sfere]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Portal Setup (reload-safe)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce portal (p/open))
(defonce _setup-tap (add-tap #'p/submit))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; System Lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start
  "Start the development system."
  []
  (system/start config))

(defn stop
  "Stop the development system."
  []
  (system/stop))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reloading
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reload
  "Reload changed namespaces."
  []
  (reload/reload))

(defn restart
  "Full restart: stop, reload, and start."
  []
  (stop)
  (reload)
  (start))

;; clj-reload hook
(defn before-ns-unload []
  (stop))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience accessors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn store
  "Get the connection store from the running system."
  []
  (::app/store system/*system*))

(defn dispatch
  "Get the dispatch function from the running system."
  []
  (::app/dispatch system/*system*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Start the system
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(start)
