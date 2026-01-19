(ns demo.app
  (:require [ascolais.sandestin :as s]
            [ascolais.sfere :as sfere]
            [ascolais.twk :as twk]
            [demo.server :as demo.server]
            [demo.system :as demo.system]
            [dev.onionpancakes.chassis.core :as c]
            [integrant.core :as ig]
            [reitit.ring :as rr]
            [reitit.ring.middleware.parameters :as rmp]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dispatch capture for on-purge
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def *dispatch
  "Atom to hold dispatch reference for on-evict callback.
   Needed because on-evict is called by Caffeine, not during dispatch."
  (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn lobby-page
  "Main page with join form and lobby view."
  []
  [c/doctype-html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "UTF-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
     [:title "Sfere Demo - Lobby"]
     [:script {:src twk/CDN-url :type "module"}]
     [:style "
       body { font-family: system-ui, sans-serif; max-width: 600px; margin: 2rem auto; padding: 0 1rem; }
       input { padding: 0.5rem; margin-right: 0.5rem; }
       button { padding: 0.5rem 1rem; cursor: pointer; }
       #lobby { margin-top: 1rem; }
       #messages { border: 1px solid #ccc; padding: 1rem; min-height: 200px; margin: 1rem 0; }
       .message { margin: 0.5rem 0; }
       .system-message { color: #666; font-style: italic; }
       #participant-list { list-style: none; padding: 0; }
       #participant-list li { padding: 0.25rem 0; }
     "]]
    [:body {:data-signals:username ""
            :data-signals:message ""}

     [:h1 "Sfere Demo - Lobby"]

     ;; Join form (shown when not connected)
     [:div#join-form
      [:p "Enter your name to join the lobby:"]
      [:input {:data-bind:username true :placeholder "Your name"}]
      [:button {:data-on:click "@post('/join')"} "Join Lobby"]]

     ;; Lobby view (hidden initially, shown after joining)
     [:div#lobby {:style "display:none"}
      [:div#participants
       [:h3 "In Lobby:"]
       [:ul#participant-list]]

      [:div#messages]

      [:div
       [:input {:data-bind:message true :placeholder "Type a message"}]
       [:button {:data-on:click "@post('/message')"} "Send"]]

      [:button {:data-on:click "@post('/leave')" :style "margin-top: 1rem;"} "Leave Lobby"]]]]])

(defn lobby-ui
  "The full lobby UI shown after joining. Replaces the join-form area.
   Uses data-init to establish a long-lived SSE connection for receiving broadcasts."
  [username]
  [:div#join-form
   ;; Establish long-lived SSE connection via data-init
   {:data-init "@post('/sse')"}

   [:div#participants
    [:h3 "In Lobby:"]
    [:ul#participant-list
     [:li {:id (str "participant-" username)} username]]]

   [:div#messages
    [:div.message.system-message (str "Welcome to the lobby, " username "!")]]

   [:div
    [:input {:data-bind:message true :placeholder "Type a message"}]
    [:button {:data-on:click "@post('/message')"} "Send"]]

   [:button {:data-on:click "@post('/leave')" :style "margin-top: 1rem;"} "Leave Lobby"]])

(defn participant-item
  "A single participant list item."
  [username]
  [:li {:id (str "participant-" username)} username])

(defn participant-joined
  "Message and list item for when someone joins."
  [username]
  [:div
   [:li {:id (str "participant-" username)} username]
   [:div.message.system-message (str username " joined the lobby")]])

(defn participant-left
  "Message for when someone leaves."
  [username]
  [:div
   [:div.message.system-message (str username " left the lobby")]])

(defn message-bubble
  "A chat message."
  [username message]
  [:div.message [:strong username] ": " message])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index
  "GET / - Render the lobby page."
  [_]
  {:body (lobby-page)})

(defn join
  "POST /join - Join the lobby.
   Returns HTML with data-init that establishes SSE connection."
  [{:keys [signals]}]
  (let [username (:username signals)]
    (if (or (nil? username) (empty? username))
      {:status 400 :body "Username required"}
      ;; Return lobby UI which includes data-init for SSE
      {::twk/with-open-sse? true
       ::twk/fx
       [[::twk/patch-elements (lobby-ui username)]]})))

(defn sse-connect
  "POST /sse - Establish long-lived SSE connection for a user.
   This is triggered by data-init in the lobby-ui."
  [{:keys [signals]}]
  (let [username (:username signals)]
    (if (or (nil? username) (empty? username))
      {:status 400 :body "Username required"}
      (let [user-key [::sfere/default-scope [:lobby username]]
            ;; Get existing lobby members from store
            store (-> demo.system/*system* ::store)
            existing-keys (sfere/list-connections store [:* [:lobby :*]])
            existing-users (->> existing-keys
                                (map (fn [[_scope [_category uname]]] uname))
                                (remove #{username}))]
        {::sfere/key [:lobby username]
         ::twk/fx
         (concat
           ;; Send existing participants to the new user
          (when (seq existing-users)
            [[::twk/patch-elements
              (into [:div] (map participant-item existing-users))
              {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]])
           ;; Add to participant list for others (self already has it from lobby-ui)
          [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                               :exclude #{user-key}}
            [::twk/patch-elements (participant-item username)
             {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]]
            ;; Broadcast join message to all (including self)
           [::sfere/broadcast {:pattern [:* [:lobby :*]]}
            [::twk/patch-elements [:div.message.system-message (str username " joined the lobby")]
             {twk/selector "#messages" twk/patch-mode twk/pm-append}]]])}))))

(defn send-message
  "POST /message - Send a message to everyone in lobby."
  [{:keys [signals]}]
  (let [username (:username signals)
        message (:message signals)]
    (if (or (nil? message) (empty? message))
      {:status 400 :body "Message required"}
      {::twk/with-open-sse? true
       ::twk/fx
       [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
         [::twk/patch-elements (message-bubble username message)
          {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
        [::sfere/with-connection [::sfere/default-scope [:lobby username]]
         [::twk/patch-signals {:message ""}]]]})))

(defn leave
  "POST /leave - Leave the lobby."
  [{:keys [signals]}]
  (let [username (:username signals)
        user-key [::sfere/default-scope [:lobby username]]]
    {::twk/with-open-sse? true
     ::twk/fx
     [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{user-key}}
       [::twk/patch-elements (participant-left username)
        {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
      [::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{user-key}}
       [::twk/patch-elements ""
        {twk/selector (str "#participant-" username)
         twk/patch-mode twk/pm-remove}]]
      [::sfere/with-connection user-key
       [::twk/close-sse]]]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; on-purge callback (dispatch-triggered)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-purge
  "Called when connection is purged via ::twk/sse-closed dispatch.

   NOTE: We intentionally do NOT broadcast 'user left' here because this
   only fires when the server tries to write to a closed connection. SSE
   connections close frequently due to normal browser behavior (tab
   switching, idle timeout, etc.) - the user hasn't necessarily left.

   'User left' notifications are handled by on-evict (TTL expiration)."
  [_ctx [_scope [_category username] :as key]]
  (tap> {:sfere/event :on-purge :key key :username username})
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; on-evict callback (TTL-triggered)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-on-evict
  "Create an on-evict callback that broadcasts 'user left' on eviction.

   Since on-evict is called by Caffeine (not during dispatch), we need to
   capture the dispatch function to broadcast departure messages.

   Broadcasts on:
   - :expired - TTL timeout (user inactive)
   - :explicit - Registry purged connection (SSE close detected)"
  [dispatch-atom]
  (fn [[_scope [_category username] :as key] _conn cause]
    (tap> {:sfere/event :on-evict :key key :username username :cause cause})
    (when (#{:expired :explicit} cause)
      ;; Use captured dispatch to broadcast departure
      (when-let [dispatch @dispatch-atom]
        (dispatch {} {}
                  [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
                    [::twk/patch-elements (participant-left username)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
                   [::sfere/broadcast {:pattern [:* [:lobby :*]]}
                    [::twk/patch-elements ""
                     {twk/selector (str "#participant-" username)
                      twk/patch-mode twk/pm-remove}]]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def routes
  ["/"
   ["" {:name ::index :get index}]
   ["join" {:name ::join :post join}]
   ["sse" {:name ::sse :post sse-connect}]
   ["message" {:name ::message :post send-message}]
   ["leave" {:name ::leave :post leave}]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integrant components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::store [_ {:keys [type ttl-seconds on-evict]
                                   :or {type :caffeine
                                        ttl-seconds 30}}]
  (if (= type :caffeine)
    (sfere/store {:type :caffeine
                  :duration-ms (* ttl-seconds 1000)
                  :expiry-mode :sliding
                  :scheduler true
                  :on-evict on-evict})
    (sfere/store {:type type})))

(defmethod ig/init-key ::dispatch [_ {:keys [store]}]
  (let [dispatch (s/create-dispatch
                  [(twk/registry)
                   (sfere/registry store {:on-purge on-purge})])]
    (reset! *dispatch dispatch)
    dispatch))

(defn ->sse-response-with-logging
  "Wraps http-kit ->sse-response to log close status codes."
  [request opts]
  (let [original-on-close (:d*.sse/on-close opts)]
    (hk/->sse-response
     request
     (assoc opts :d*.sse/on-close
            (fn [sse-gen status]
              (tap> {:sfere/event :sse-close-status
                     :status status
                     :request-uri (:uri request)})
              (when original-on-close
                (original-on-close sse-gen status)))))))

(defmethod ig/init-key ::with-datastar [_ {:keys [dispatch]}]
  (twk/with-datastar ->sse-response-with-logging dispatch))

(defmethod ig/init-key ::router [_ {:keys [routes middleware]
                                    :or {middleware []}}]
  (let [middleware (into [rmp/parameters-middleware] middleware)]
    (rr/router routes {:data {:middleware middleware}})))

(defmethod ig/init-key ::handler [_ {:keys [router middleware]
                                     :or {middleware []}}]
  (rr/ring-handler
   router
   (rr/routes
    (rr/create-default-handler))
   {:middleware middleware}))

(defmethod ig/init-key ::server [_ deps]
  (demo.server/httpkit-server deps))

(defmethod ig/halt-key! ::server [_ stop-fn]
  (when stop-fn
    (stop-fn)))
