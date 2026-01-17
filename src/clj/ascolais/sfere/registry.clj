(ns ascolais.sfere.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.sfere.protocols :as p]
            [ascolais.twk :as twk]))

;; Effect and key keywords (in ascolais.sfere namespace for public API)
(def with-connection-key :ascolais.sfere/with-connection)
(def broadcast-key :ascolais.sfere/broadcast)
(def connection-key :ascolais.sfere/key)

(defn- scoped-key
  "Derive the full connection key from context.
   Returns [scope-id inner-key] or nil if no key in response."
  [ctx id-fn]
  (when-some [k (get-in ctx [:dispatch-data ::twk/response connection-key])]
    [(id-fn ctx) k]))

(defn- with-connection-effect
  "Effect handler for ::with-connection.
   Dispatches nested effects to a specific stored connection."
  [store]
  (fn [{:keys [dispatch]} [_ key nested-fx]]
    (when-some [conn (p/connection store key)]
      (dispatch {:sse conn}
                {::twk/connection conn}
                [nested-fx]))))

(defn- broadcast-effect
  "Effect handler for ::broadcast.
   Dispatches nested effects to all connections matching pattern."
  [store]
  (fn [{:keys [dispatch]} [_ {:keys [pattern exclude]} nested-fx]]
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
                      [nested-fx]))
          (catch Exception e
            (tap> {:sfere/event :broadcast-error :key k :error e})))))))

(defn- store-connection!
  "Store connection if conditions are met:
   - ::key is present in response
   - SSE connection exists in system
   - No connection already set in dispatch-data
   - Not a with-open-sse? dispatch"
  [store id-fn ctx]
  (when-some [full-key (scoped-key ctx id-fn)]
    (let [sse (get-in ctx [:system :sse])
          existing-conn (get-in ctx [:dispatch-data ::twk/connection])
          with-open? (get-in ctx [:dispatch-data ::twk/with-open-sse?])]
      (when (and sse (not existing-conn) (not with-open?))
        (p/store! store full-key sse)))))

(defn- purge-connection!
  "Purge connection and call on-purge callback if provided."
  [store id-fn on-purge ctx]
  (when-some [full-key (scoped-key ctx id-fn)]
    (when on-purge
      (on-purge ctx full-key))
    (p/purge! store full-key)))

(defn- sfere-interceptor
  "Create interceptor for auto-store/purge of connections."
  [store {:keys [id-fn on-purge]}]
  {:before-dispatch
   (fn [ctx]
     (let [fx-key (get-in ctx [:dispatch-data ::twk/response ::twk/fx-key])]
       (cond
         ;; On SSE close, purge the connection
         (= ::twk/sse-closed fx-key)
         (purge-connection! store id-fn on-purge ctx)

         ;; Otherwise, try to store connection if applicable
         :else
         (store-connection! store id-fn ctx)))
     ctx)})

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
  ([store] (registry store {}))
  ([store {:keys [id-fn on-purge]
           :or {id-fn (constantly :ascolais.sfere/default-scope)}}]
   {::s/effects
    {with-connection-key
     {::s/description "Dispatch nested effects to a specific stored connection"
      ::s/schema [:tuple
                  [:= with-connection-key]
                  [:tuple :any [:tuple :any :any]]
                  :any]
      ::s/handler (with-connection-effect store)}

     broadcast-key
     {::s/description "Dispatch nested effects to all connections matching pattern"
      ::s/schema [:tuple
                  [:= broadcast-key]
                  [:map
                   [:pattern [:tuple
                              [:or [:= :*] :any]
                              [:or [:= :*] [:tuple [:or [:= :*] :any] [:or [:= :*] :any]]]]]
                   [:exclude {:optional true} :any]]
                  :any]
      ::s/handler (broadcast-effect store)}}

    ::s/interceptors
    [(sfere-interceptor store {:id-fn id-fn :on-purge on-purge})]}))
