# Demo Application Specification

## Overview

A lobby/room demo that showcases sfere's connection management and broadcast capabilities. Users join a lobby, can see who else is present, and send messages that broadcast to all participants.

## User Flow

1. User loads the page → sees lobby with list of current participants
2. User enters a name and joins → stored connection, broadcast "X joined" to others
3. User sends a message → broadcast to all in lobby
4. User leaves (closes tab or clicks leave) → purge connection, broadcast "X left"

## Page Structure

```clojure
(defn lobby-page [{:keys [participants]}]
  [c/doctype-html5
   [:html
    [:head
     [:title "Sfere Demo - Lobby"]
     [:script {:src twk/CDN-url :type "module"}]]
    [:body {:data-signals (json/write-json-str {:username "" :message ""})}

     ;; Join form (shown when not connected)
     [:div#join-form
      [:input {:data-model "username" :placeholder "Enter your name"}]
      [:button {:data-on:click (twk/sse-post "/join")} "Join Lobby"]]

     ;; Lobby view (shown after joining)
     [:div#lobby {:style "display:none"}
      ;; Participant list
      [:div#participants
       [:h3 "In Lobby:"]
       [:ul
        (for [p participants]
          [:li p])]]

      ;; Messages area
      [:div#messages]

      ;; Message input
      [:div
       [:input {:data-model "message" :placeholder "Type a message"}]
       [:button {:data-on:click (twk/sse-post "/message")} "Send"]]

      [:button {:data-on:click (twk/sse-post "/leave")} "Leave Lobby"]]]]])
```

## Routes & Handlers

### GET /
Render the lobby page with current participant list.

```clojure
(defn index [_]
  {:body (lobby-page {:participants (get-participant-names)})})
```

### POST /join
Join the lobby. Store connection, broadcast join notification.

```clojure
(defn join [{:keys [signals]}]
  (let [username (:username signals)]
    {::sfere/key [:lobby :participant]  ;; inner key, scope-id from session
     ::twk/fx
     [;; Show lobby UI to joiner
      [::twk/patch-elements (lobby-view username)]
      ;; Broadcast to others in lobby
      [::sfere/broadcast {:pattern [:* [:lobby :participant]]}
       [::twk/patch-elements (participant-joined username)]]]}))
```

### POST /message
Send a message to everyone in the lobby.

```clojure
(defn send-message [{:keys [signals session]}]
  (let [username (:username session)
        message  (:message signals)]
    {::sfere/key [:lobby :participant]
     ::twk/fx
     [;; Broadcast message to all (including sender)
      [::sfere/broadcast {:pattern [:* [:lobby :participant]]}
       [::twk/patch-elements (message-bubble username message)]]
      ;; Clear sender's input
      [::twk/patch-signals {:message ""}]]}))
```

### POST /leave
Leave the lobby. Broadcast departure, close connection.

```clojure
(defn leave [{:keys [session]}]
  (let [username (:username session)]
    {::sfere/key [:lobby :participant]
     ::twk/fx
     [;; Broadcast departure to others
      [::sfere/broadcast {:pattern [:* [:lobby :participant]]
                          :exclude #{[(session-id) [:lobby :participant]]}}
       [::twk/patch-elements (participant-left username)]]
      ;; Close this connection
      [::twk/close-sse]]}))
```

### SSE Close Handler
Automatic cleanup when connection drops (tab close, network loss).

Handled via `:on-purge` in registry options. See **System Setup** section for the full pattern — requires capturing the dispatch reference in an atom since interceptors don't have direct access to dispatch.

## System Setup

```clojure
(require '[ascolais.sandestin :as s])
(require '[ascolais.twk :as twk])
(require '[ascolais.sfere :as sfere])

(def store (sfere/store {:type :atom}))

;; Atom to hold dispatch reference for on-purge callback
;; (interceptors don't receive dispatch, only effect handlers do)
(def *dispatch (atom nil))

(defn on-purge
  "Broadcast departure when connection is purged.
   Uses captured dispatch reference since interceptors don't have access to dispatch."
  [ctx key]
  (when-some [dispatch @*dispatch]
    (let [username (get-in ctx [:dispatch-data ::username])]
      (dispatch {} {}
        [[::sfere/broadcast {:pattern [:* [:lobby :participant]]
                             :exclude #{key}}
          [::twk/patch-elements (participant-left username)]]]))))

(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store
       {:id-fn   #(get-in % [:request :session :id])
        :on-purge on-purge})]))

;; Capture dispatch reference for on-purge
(reset! *dispatch dispatch)

(def app
  (-> routes
      (twk/with-datastar ->sse-response dispatch)))
```

## Key Patterns Demonstrated

1. **Connection storage** — `::sfere/key` in response triggers auto-store
2. **Broadcast to room** — `::sfere/broadcast` with pattern `[:* [:lobby :participant]]`
3. **Exclude self** — `:exclude` in broadcast props
4. **Auto-cleanup with broadcast** — `:on-purge` + captured dispatch atom pattern
5. **Mixed effects** — Combine direct effects (to requester) with broadcasts (to others)

## REPL Discoverability

```clojure
;; See who's connected
(sfere/list-connections store)
;; => [[:session-1 [:lobby :participant]]
;;     [:session-2 [:lobby :participant]]]

;; Count participants
(sfere/connection-count store [:* [:lobby :participant]])
;; => 2

;; Send announcement from REPL
(dispatch system {}
  [[::sfere/broadcast {:pattern [:* [:lobby :participant]]}
    [::twk/patch-elements [:div.announcement "Server restarting in 5 minutes"]]]])
```

## Dependencies

```clojure
;; deps.edn :dev alias
{:extra-deps
 {org.httpkit/http-kit {:mvn/version "2.8.0"}
  metosin/reitit {:mvn/version "0.7.2"}
  integrant/integrant {:mvn/version "0.13.1"}
  dev.onionpancakes/chassis {:mvn/version "1.0.467"}
  io.github.starfederation/datastar.clojure {:git/tag "v1.0.0-beta.11" :git/sha "..."}}}
```

## Notes

**Dispatch capture pattern:** The `on-purge` callback requires capturing the dispatch function in an atom because Sandestin interceptors don't receive dispatch in their context (only effect handlers do). This is a known limitation — if a cleaner pattern emerges or Sandestin adds dispatch to interceptor context, this can be simplified.
