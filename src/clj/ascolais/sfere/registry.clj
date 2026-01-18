(ns ascolais.sfere.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.sfere.protocols :as p]
            [ascolais.twk :as twk]))

;; Effect and key keywords (in ascolais.sfere namespace for public API)
(def with-connection-key :ascolais.sfere/with-connection)
(def broadcast-key :ascolais.sfere/broadcast)
(def connection-key :ascolais.sfere/key)

(defn wrap-connection-reuse
  "Ring middleware that looks up stored connections and adds them to the response.
   This allows twk to reuse existing SSE connections instead of creating new ones.

   Must be applied AFTER twk/with-datastar in the middleware vector (so it wraps
   the handler before with-datastar does)."
  [handler store id-fn]
  (fn [request]
    (let [response (handler request)
          ;; Check if this is a sfere response with a key
          inner-key (get response connection-key)]
      (if inner-key
        ;; Look up existing connection
        (let [scope-id (id-fn {:request request})
              full-key [scope-id inner-key]
              existing (p/connection store full-key)]
          (tap> {:sfere/event :wrap-connection-reuse
                 :key full-key
                 :found? (some? existing)})
          (if existing
            ;; Add existing connection to response so twk reuses it
            (assoc response ::twk/connection existing)
            response))
        response))))

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
  (fn [{:keys [dispatch]} _system key nested-fx]
    (when-some [conn (p/connection store key)]
      (dispatch {:sse conn}
                {::twk/connection conn}
                [nested-fx]))))

(defn- broadcast-effect
  "Effect handler for ::broadcast.
   Dispatches nested effects to all connections matching pattern."
  [store]
  (fn [{:keys [dispatch]} _system {:keys [pattern exclude]} nested-fx]
    (let [all-keys     (p/list-keys store [:* :*])
          matching     (p/list-keys store pattern)
          excluded     (cond
                         (set? exclude) exclude
                         (vector? exclude) (set (p/list-keys store exclude))
                         :else #{})
          keys-to-send (remove excluded matching)]
      (tap> {:sfere/event :broadcast
             :pattern pattern
             :all-stored-keys (vec all-keys)
             :matching matching
             :excluded excluded
             :keys-to-send (vec keys-to-send)})
      (doseq [k keys-to-send]
        (try
          (when-some [conn (p/connection store k)]
            (tap> {:sfere/event :broadcast-send :key k :conn-type (type conn) :nested-fx nested-fx})
            (let [result (dispatch {:sse conn}
                                   {::twk/connection conn}
                                   [nested-fx])]
              (tap> {:sfere/event :broadcast-dispatch-result :key k :errors (:errors result)})))
          (catch Exception e
            (tap> {:sfere/event :broadcast-error :key k :error e})))))))

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
          with-open? (get-in ctx [:dispatch-data ::twk/with-open-sse?])
          request-uri (get-in ctx [:system :request :uri])
          actions (:actions ctx)]
      (tap> {:sfere/event :store-check
             :key full-key
             :has-sse? (some? sse)
             :existing-conn? (some? existing-conn)
             :with-open? with-open?
             :will-store? (and sse (not existing-conn) (not with-open?))
             :request-uri request-uri
             :actions-count (count actions)})
      (when (and sse (not existing-conn) (not with-open?))
        (p/store! store full-key sse)
        (tap> {:sfere/event :stored! :key full-key})))))

(defn- purge-connection!
  "Purge connection and call on-purge callback if provided."
  [store id-fn on-purge ctx]
  (when-some [full-key (scoped-key ctx id-fn)]
    (when on-purge
      (on-purge ctx full-key))
    (p/purge! store full-key)))

(defn- inject-existing-connection
  "If there's a stored connection for this key, inject it into dispatch-data
   so twk will reuse it instead of creating a new SSE.

   twk's ::twk/connection effect handler reads from dispatch-data[::twk/connection],
   so we inject there (not under ::twk/response)."
  [store id-fn ctx]
  (if-some [full-key (scoped-key ctx id-fn)]
    (if-some [existing (p/connection store full-key)]
      (do
        (tap> {:sfere/event :inject-connection :key full-key})
        (assoc-in ctx [:dispatch-data ::twk/connection] existing))
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
  [store {:keys [id-fn on-purge]}]
  {:before-dispatch
   (fn [ctx]
     (cond
       ;; On SSE close, purge the connection
       (sse-close-dispatch? ctx)
       (do (tap> {:sfere/event :sse-close-detected
                  :key (scoped-key ctx id-fn)})
           (purge-connection! store id-fn on-purge ctx)
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
