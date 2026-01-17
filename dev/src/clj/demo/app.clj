(ns demo.app
  (:require [ascolais.sandestin :as s]
            [ascolais.sfere :as sfere]
            [ascolais.twk :as twk]
            [demo.server :as demo.server]
            [dev.onionpancakes.chassis.core :as c]
            [integrant.core :as ig]
            [reitit.ring :as rr]
            [reitit.ring.middleware.parameters :as rmp]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dispatch capture for on-purge
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private *dispatch
  "Atom to hold dispatch reference for on-purge callback.
   Interceptors don't receive dispatch, only effect handlers do."
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
      [:button {:data-on:click (twk/sse-post "/join")} "Join Lobby"]]

     ;; Lobby view (hidden initially, shown after joining)
     [:div#lobby {:style "display:none"}
      [:div#participants
       [:h3 "In Lobby:"]
       [:ul#participant-list]]

      [:div#messages]

      [:div
       [:input {:data-bind:message true :placeholder "Type a message"}]
       [:button {:data-on:click (twk/sse-post "/message")} "Send"]]

      [:button {:data-on:click (twk/sse-post "/leave") :style "margin-top: 1rem;"} "Leave Lobby"]]]]])

(defn lobby-view
  "The lobby UI shown after joining. Replaces #join-form."
  [username]
  [:div#join-form {:style "display:none"}])

(defn lobby-visible
  "Make the lobby div visible."
  []
  [:div#lobby {:style "display:block"}])

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
  "POST /join - Join the lobby."
  [{:keys [signals]}]
  (let [username (:username signals)]
    (if (or (nil? username) (empty? username))
      {:status 400 :body "Username required"}
      {::sfere/key [:lobby username]
       ::twk/fx
       [;; Hide join form, show lobby
        [::twk/patch-elements (lobby-view username)]
        [::twk/patch-elements (lobby-visible)]
        ;; Add self to participant list
        [::twk/patch-elements (participant-item username) {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]
        ;; Broadcast join to others
        [::sfere/broadcast {:pattern [:* [:lobby :*]]}
         [::twk/patch-elements (participant-joined username) {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]]]})))

(defn send-message
  "POST /message - Send a message to everyone in lobby."
  [{:keys [signals]}]
  (let [username (:username signals)
        message (:message signals)]
    (if (or (nil? message) (empty? message))
      {:status 400 :body "Message required"}
      {::sfere/key [:lobby username]
       ::twk/fx
       [;; Broadcast message to all (including sender)
        [::sfere/broadcast {:pattern [:* [:lobby :*]]}
         [::twk/patch-elements (message-bubble username message) {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
        ;; Clear sender's input
        [::twk/patch-signals {:message ""}]]})))

(defn leave
  "POST /leave - Leave the lobby."
  [{:keys [signals]}]
  (let [username (:username signals)]
    {::sfere/key [:lobby username]
     ::twk/fx
     [;; Broadcast departure to others (exclude self)
      [::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{[::sfere/default-scope [:lobby username]]}}
       [::twk/patch-elements (participant-left username) {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
      ;; Remove from participant list for others
      [::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{[::sfere/default-scope [:lobby username]]}}
       [::twk/patch-elements [:li {:id (str "participant-" username)} ""] {twk/patch-mode twk/pm-remove}]]
      ;; Close this connection
      [::twk/close-sse]]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; on-purge callback
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-purge
  "Broadcast departure when connection is purged (tab close, network loss).
   Extracts username from key structure: [scope [:lobby username]]"
  [_ctx [_scope [_category username] :as key]]
  (when-some [dispatch @*dispatch]
    (tap> {:sfere/event :on-purge :key key :username username})
    (dispatch {} {}
              [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                                   :exclude #{key}}
                [::twk/patch-elements (participant-left username) {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
               [::sfere/broadcast {:pattern [:* [:lobby :*]]
                                   :exclude #{key}}
                [::twk/patch-elements [:li {:id (str "participant-" username)} ""] {twk/patch-mode twk/pm-remove}]]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def routes
  ["/"
   ["" {:name ::index :get index}]
   ["join" {:name ::join :post join}]
   ["message" {:name ::message :post send-message}]
   ["leave" {:name ::leave :post leave}]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integrant components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::store [_ {:keys [type] :or {type :atom}}]
  (sfere/store {:type type}))

(defmethod ig/init-key ::dispatch [_ {:keys [store]}]
  (let [dispatch (s/create-dispatch
                  [(twk/registry)
                   (sfere/registry store {:on-purge on-purge})])]
    (reset! *dispatch dispatch)
    dispatch))

(defmethod ig/init-key ::with-datastar [_ {:keys [dispatch]}]
  (twk/with-datastar hk/->sse-response dispatch))

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
