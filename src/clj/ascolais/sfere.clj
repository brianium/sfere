(ns ascolais.sfere
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.atom :as atom-store]
            [ascolais.sfere.caffeine :as caff-store]
            [ascolais.sfere.registry :as reg]))

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

(defn registry
  "Create a Sandestin registry for Sfere connection management.

   Arguments:
   - store: A connection store (from sfere/store)
   - opts: Optional map with:
     | Key       | Description                                              |
     |-----------|----------------------------------------------------------|
     | :id-fn    | (fn [ctx] scope-id) - Derives scope-id from context      |
     | :on-purge | (fn [ctx key]) - Called when connection is purged        |

   Usage:
   ```clojure
   (def dispatch
     (s/create-dispatch
       [(twk/registry)
        (sfere/registry store {:id-fn #(get-in % [:session :user-id])})]))
   ```"
  ([store] (reg/registry store))
  ([store opts] (reg/registry store opts)))

(defn wrap-connection-reuse
  "Ring middleware that enables SSE connection reuse for sfere.

   When a handler returns a response with ::sfere/key, this middleware
   looks up any existing stored connection and adds it to the response.
   This allows twk to reuse the existing SSE instead of creating a new one.

   Arguments:
   - handler: Ring handler
   - store: Connection store
   - id-fn: Function to derive scope-id from context (same as registry :id-fn)

   Usage - compose with twk middleware:
   ```clojure
   (-> handler
       (sfere/wrap-connection-reuse store id-fn)
       (twk/with-datastar ->sse-response dispatch))
   ```"
  [handler store id-fn]
  (reg/wrap-connection-reuse handler store id-fn))
