(ns ascolais.sfere.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.sfere.protocols :as p]
            [ascolais.twk :as twk]))

;; Effect and key keywords (in ascolais.sfere namespace for public API)
(def with-connection-key :ascolais.sfere/with-connection)
(def broadcast-key :ascolais.sfere/broadcast)
(def connection-key :ascolais.sfere/key)

;; -----------------------------------------------------------------------------
;; Schemas for discoverability
;; -----------------------------------------------------------------------------

(def ^:private scope-id-schema
  "Schema for scope identifier (e.g., :ascolais.sfere/default-scope or user-id)"
  [:or :keyword :string :uuid :int])

(def ^:private inner-key-schema
  "Schema for inner connection key (e.g., [:lobby \"brian\"])"
  [:tuple :keyword [:or :string :keyword :int :uuid]])

(def ^:private connection-key-schema
  "Schema for full connection key [scope-id inner-key]
   Example: [:ascolais.sfere/default-scope [:lobby \"brian\"]]"
  [:tuple scope-id-schema inner-key-schema])

(def ^:private wildcard-schema
  "Schema for wildcard or specific value in patterns"
  [:or [:= :*] :keyword :string :int :uuid])

(def ^:private pattern-schema
  "Schema for broadcast patterns. Supports wildcards at any level.
   Examples:
     [:* [:lobby :*]]        - all users in any lobby
     [:* [:lobby \"general\"]] - all users in 'general' lobby
     [:user-123 [:lobby :*]] - specific user's lobby connections"
  [:tuple
   wildcard-schema
   [:tuple wildcard-schema wildcard-schema]])

(defn- scoped-key
  "Derive the full connection key from context.
   Returns [scope-id inner-key] or nil if no key in response."
  [ctx id-fn]
  (when-some [k (get-in ctx [:dispatch-data ::twk/response connection-key])]
    [(id-fn ctx) k]))

(defn- with-connection-effect
  "Effect handler for ::with-connection.
   Dispatches nested effects to a specific stored connection.
   Accepts one or more effect vectors (variadic)."
  [store]
  (fn [{:keys [dispatch]} _system key & nested-fxs]
    (when (seq nested-fxs)
      (when-some [conn (p/connection store key)]
        (dispatch {:sse conn}
                  {::twk/connection conn}
                  (vec nested-fxs))))))

(defn- broadcast-effect
  "Effect handler for ::broadcast.
   Dispatches nested effects to all connections matching pattern.
   Accepts one or more effect vectors (variadic)."
  [store]
  (fn [{:keys [dispatch]} _system {:keys [pattern exclude]} & nested-fxs]
    (when (seq nested-fxs)
      (let [matching     (p/list-keys store pattern)
            excluded     (cond
                           (set? exclude) exclude
                           (vector? exclude) (set (p/list-keys store exclude))
                           :else #{})
            keys-to-send (remove excluded matching)]
        (doseq [k keys-to-send]
          (try
            (when-some [conn (p/connection store k)]
              (dispatch {:sse conn}
                        {::twk/connection conn}
                        (vec nested-fxs)))
            (catch Exception _
              ;; Continue broadcasting to remaining connections on error
              nil)))))))

(defn- store-connection!
  "Store connection if conditions are met:
   - ::key is present in response
   - SSE connection exists in system
   - No connection already set in dispatch-data (i.e., not injected)
   - Not a with-open-sse? dispatch"
  [store id-fn ctx]
  (when-some [full-key (scoped-key ctx id-fn)]
    (let [sse (get-in ctx [:system :sse])
          existing-conn (get-in ctx [:dispatch-data ::twk/connection])
          with-open? (get-in ctx [:dispatch-data ::twk/with-open-sse?])]
      (when (and sse (not existing-conn) (not with-open?))
        (p/store! store full-key sse)))))

(defn- purge-connection!
  "Purge connection from store."
  [store id-fn ctx]
  (when-some [full-key (scoped-key ctx id-fn)]
    (p/purge! store full-key)))

(defn- find-key-by-connection
  "Scan store to find the key for a given connection object.
   Returns the first key whose stored connection is identical to conn."
  [store conn]
  (when conn
    (->> (p/list-keys store)
         (filter #(identical? conn (p/connection store %)))
         first)))

(defn- purge-by-connection!
  "Find and purge a connection from store by matching the SSE object.
   Used when SSE closes but we don't have the key in context."
  [store conn]
  (when-some [key (find-key-by-connection store conn)]
    (p/purge! store key)))

(defn- inject-existing-connection
  "If there's a stored connection for this key, inject it into dispatch-data
   so twk will reuse it instead of creating a new SSE.

   twk's ::twk/connection effect handler reads from dispatch-data[::twk/connection],
   so we inject there (not under ::twk/response)."
  [store id-fn ctx]
  (if-some [full-key (scoped-key ctx id-fn)]
    (if-some [existing (p/connection store full-key)]
      (assoc-in ctx [:dispatch-data ::twk/connection] existing)
      ctx)
    ctx))

(defn- sse-close-dispatch?
  "Check if this dispatch is for the SSE close event.
   twk dispatches [[:ascolais.twk/sse-closed]] when the SSE connection closes."
  [ctx]
  (let [actions (:actions ctx)]
    (and (= 1 (count actions))
         (= [::twk/sse-closed] (first actions)))))

(defn- sfere-interceptor
  "Create interceptor for auto-store/purge of connections."
  [store {:keys [id-fn]}]
  {:before-dispatch
   (fn [ctx]
     (cond
       ;; On SSE close, purge by connection object (enables immediate eviction
       ;; even when ::sfere/key isn't in context - fixes sliding expiry sync issue)
       (sse-close-dispatch? ctx)
       (let [sse (get-in ctx [:system :sse])]
         (purge-by-connection! store sse)
         ctx)

       ;; Otherwise, inject existing connection and/or store new one
       :else
       (let [ctx (inject-existing-connection store id-fn ctx)]
         (store-connection! store id-fn ctx)
         ctx)))})

(defn registry
  "Create a Sandestin registry for Sfere connection management.

   Arguments:
   - store: A connection store (from sfere/store)
   - opts: Optional map with:
     | Key       | Description                                              |
     |-----------|----------------------------------------------------------|
     | :id-fn    | (fn [ctx] scope-id) - Derives scope-id from context      |

   Usage:
   ```clojure
   (def dispatch
     (s/create-dispatch
       [(twk/registry)
        (sfere/registry store {:id-fn #(get-in % [:session :user-id])})]))
   ```"
  ([store] (registry store {}))
  ([store {:keys [id-fn]
           :or {id-fn (constantly :ascolais.sfere/default-scope)}}]
   {::s/effects
    {with-connection-key
     {::s/description "Dispatch nested effects to a specific stored connection.

   Arguments:
     key        - Full connection key [scope-id [:category identifier]]
     nested-fxs - One or more effect vectors to dispatch to the connection

   Example (single effect):
     [::sfere/with-connection [:ascolais.sfere/default-scope [:lobby \"brian\"]]
      [::twk/patch-signals {:message \"\"}]]

   Example (multiple effects):
     [::sfere/with-connection [:ascolais.sfere/default-scope [:lobby \"brian\"]]
      [::twk/patch-signals {:typing false}]
      [::twk/patch-elements [:div \"Done\"]]]"
      ::s/schema [:cat
                  [:= with-connection-key]
                  connection-key-schema
                  [:+ s/EffectVector]]
      ::s/handler (with-connection-effect store)}

     broadcast-key
     {::s/description "Dispatch nested effects to all connections matching a pattern.

   Arguments:
     opts       - Map with :pattern (required) and :exclude (optional)
     nested-fxs - One or more effect vectors to dispatch to each matching connection

   Pattern examples:
     [:* [:lobby :*]]          - all lobby connections
     [:* [:lobby \"general\"]]   - all connections in 'general' lobby
     [:user-123 [:lobby :*]]   - specific user's lobby connections

   Example (single effect):
     [::sfere/broadcast {:pattern [:* [:lobby :*]]}
      [::twk/patch-elements [:div \"Hello\"]]]

   Example (multiple effects):
     [::sfere/broadcast {:pattern [:* [:lobby :*]]}
      [::twk/patch-signals {:typing false}]
      [::twk/patch-elements [:div \"Hello\"]]]"
      ::s/schema [:cat
                  [:= broadcast-key]
                  [:map
                   [:pattern pattern-schema]
                   [:exclude {:optional true}
                    [:or
                     [:set connection-key-schema]
                     pattern-schema]]]
                  [:+ s/EffectVector]]
      ::s/handler (broadcast-effect store)}}

    ::s/interceptors
    [(sfere-interceptor store {:id-fn id-fn})]}))
