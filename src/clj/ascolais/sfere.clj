(ns ascolais.sfere
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.atom :as atom-store]
            [ascolais.sfere.caffeine :as caff-store]))

(defmulti store
  "Create a connection store. Dispatch on :type key.

   Supported types:
   - :atom     — Simple atom-backed store
   - :caffeine — Caffeine cache with expiration"
  :type)

(defmethod store :atom [opts]
  (atom-store/store opts))

(defmethod store :caffeine [opts]
  (caff-store/store opts))

(defmethod store :default [{:keys [type]}]
  (throw (ex-info (str "Unknown store type: " type) {:type type})))

(defn store!
  "Store connection at key. Returns connection."
  [s key conn]
  (p/store! s key conn)
  conn)

(defn connection
  "Get connection by exact key. Returns nil if not found."
  [s key]
  (p/connection s key))

(defn purge!
  "Remove connection by key."
  [s key]
  (p/purge! s key))

(defn list-connections
  "List connection keys, optionally filtered by pattern."
  ([s]
   (p/list-keys s))
  ([s pattern]
   (p/list-keys s pattern)))

(defn connection-count
  "Count connections, optionally filtered by pattern."
  ([s]
   (count (p/list-keys s)))
  ([s pattern]
   (count (p/list-keys s pattern))))

(defn store?
  "Returns true if x implements ConnectionStore."
  [x]
  (satisfies? p/ConnectionStore x))
