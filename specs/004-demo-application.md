# Demo Application Specification

## Status

| Component | Status |
|-----------|--------|
| Dependencies in deps.edn | Not started |
| Integrant system setup | Not started |
| Demo app (routes, handlers, views) | Not started |
| Manual testing | Not started |

## Overview

A lobby/room demo that showcases sfere's connection management and broadcast capabilities. Users join a lobby, can see who else is present, and send messages that broadcast to all participants.

## User Flow

1. User loads the page → sees join form
2. User enters a name and joins → stored connection, broadcast "X joined" to others
3. User sends a message → broadcast to all in lobby
4. User leaves (closes tab or clicks leave) → purge connection, broadcast "X left"

## Key Design Decisions

### Username in Connection Key

Instead of using sessions, we embed the username directly in the connection key:

```clojure
;; Key structure: [scope-id [:lobby username]]
[::sfere/default-scope [:lobby "brian"]]
[::sfere/default-scope [:lobby "alice"]]
```

This allows:
- Extracting username from the key in `on-purge` (for "user left" broadcasts)
- No server-side session management needed
- Username persists in client signals via `data-bind`

### Broadcast Pattern

To broadcast to all lobby participants:
```clojure
[:* [:lobby :*]]  ;; matches all usernames in lobby
```

### Datastar Signal Binding

Datastar uses `data-bind` for two-way binding between inputs and signals:

```html
<!-- Initialize signals on a container -->
<body data-signals:username="" data-signals:message="">

<!-- Bind input to signal -->
<input data-bind:username placeholder="Enter your name">
```

When the form is submitted via `sse-post`, signals are sent to the server and available via `(:signals request)`.

## Page Structure

```clojure
(defn lobby-page []
  [c/doctype-html5
   [:html
    [:head
     [:title "Sfere Demo - Lobby"]
     [:script {:src twk/CDN-url :type "module"}]]
    [:body {:data-signals:username ""
            :data-signals:message ""}

     ;; Join form (shown when not connected)
     [:div#join-form
      [:input {:data-bind:username true :placeholder "Enter your name"}]
      [:button {:data-on:click (twk/sse-post "/join")} "Join Lobby"]]

     ;; Lobby view (hidden initially, shown after joining)
     [:div#lobby {:style "display:none"}
      ;; Participant list
      [:div#participants
       [:h3 "In Lobby:"]
       [:ul#participant-list]]

      ;; Messages area
      [:div#messages]

      ;; Message input
      [:div
       [:input {:data-bind:message true :placeholder "Type a message"}]
       [:button {:data-on:click (twk/sse-post "/message")} "Send"]]

      [:button {:data-on:click (twk/sse-post "/leave")} "Leave Lobby"]]]]])
```

## Routes & Handlers

### GET /
Render the lobby page.

```clojure
(defn index [_]
  {:body (lobby-page)})
```

### POST /join
Join the lobby. Store connection with username in key, broadcast join notification.

```clojure
(defn join [{:keys [signals]}]
  (let [username (:username signals)]
    {::sfere/key [:lobby username]  ;; username embedded in key!
     ::twk/fx
     [;; Show lobby UI to joiner
      [::twk/patch-elements (lobby-view username)]
      ;; Broadcast to others in lobby
      [::sfere/broadcast {:pattern [:* [:lobby :*]]}
       [::twk/patch-elements (participant-joined username)]]]}))
```

### POST /message
Send a message to everyone in the lobby.

```clojure
(defn send-message [{:keys [signals]}]
  (let [username (:username signals)
        message  (:message signals)]
    {::sfere/key [:lobby username]
     ::twk/fx
     [;; Broadcast message to all (including sender)
      [::sfere/broadcast {:pattern [:* [:lobby :*]]}
       [::twk/patch-elements (message-bubble username message)]]
      ;; Clear sender's input
      [::twk/patch-signals {:message ""}]]}))
```

### POST /leave
Leave the lobby. Broadcast departure, close connection.

```clojure
(defn leave [{:keys [signals]}]
  (let [username (:username signals)]
    {::sfere/key [:lobby username]
     ::twk/fx
     [;; Broadcast departure to others (exclude self)
      [::sfere/broadcast {:pattern [:* [:lobby :*]]
                          :exclude #{[::sfere/default-scope [:lobby username]]}}
       [::twk/patch-elements (participant-left username)]]
      ;; Close this connection
      [::twk/close-sse]]}))
```

### SSE Close Handler (on-purge)
Automatic cleanup when connection drops (tab close, network loss).

Username is extracted from the key structure:

```clojure
(defn on-purge
  "Broadcast departure when connection is purged.
   Extracts username from the key: [scope [:lobby username]]"
  [ctx [_scope [_category username]]]
  (when-some [dispatch @*dispatch]
    (dispatch {} {}
      [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                           :exclude #{[_scope [_category username]]}}
        [::twk/patch-elements (participant-left username)]]])))
```

## System Setup

```clojure
(require '[ascolais.sandestin :as s])
(require '[ascolais.twk :as twk])
(require '[ascolais.sfere :as sfere])
(require '[starfederation.datastar.clojure.adapter.http-kit :as hk])

(def store (sfere/store {:type :atom}))

;; Atom to hold dispatch reference for on-purge callback
(def *dispatch (atom nil))

(defn on-purge
  "Broadcast departure when connection is purged."
  [ctx [scope [category username] :as key]]
  (when-some [dispatch @*dispatch]
    (dispatch {} {}
      [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                           :exclude #{key}}
        [::twk/patch-elements (participant-left username)]]])))

(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store {:on-purge on-purge})]))  ;; default id-fn is fine

;; Capture dispatch reference for on-purge
(reset! *dispatch dispatch)

(def app
  (twk/with-datastar hk/->sse-response dispatch))
```

## Integrant Configuration

```clojure
;; demo/config.clj
(def config
  {::app/store          {:type :atom}
   ::app/dispatch       {:store (ig/ref ::app/store)}
   ::app/with-datastar  {:dispatch (ig/ref ::app/dispatch)}
   ::app/router         {:routes     app/routes
                         :middleware [(ig/ref ::app/with-datastar)]}
   ::app/handler        {:router (ig/ref ::app/router)}
   ::app/server         {:handler (ig/ref ::app/handler)}})
```

## Key Patterns Demonstrated

1. **Username in key** — `::sfere/key [:lobby username]` embeds username for on-purge access
2. **Broadcast to room** — `::sfere/broadcast` with pattern `[:* [:lobby :*]]`
3. **Exclude self** — `:exclude` with the full key
4. **Auto-cleanup with broadcast** — `:on-purge` extracts username from key structure
5. **Client-side state** — `data-bind` keeps username in signals across requests

## REPL Discoverability

```clojure
;; See who's connected
(sfere/list-connections store)
;; => [[::sfere/default-scope [:lobby "brian"]]
;;     [::sfere/default-scope [:lobby "alice"]]]

;; Count participants
(sfere/connection-count store [:* [:lobby :*]])
;; => 2

;; Send announcement from REPL
(dispatch {} {}
  [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
    [::twk/patch-elements [:div.announcement "Server restarting in 5 minutes"]]]])
```

## Dependencies

```clojure
;; deps.edn :dev alias additions
{:extra-deps
 {metosin/reitit {:mvn/version "0.7.2"}
  integrant/integrant {:mvn/version "0.13.1"}
  dev.onionpancakes/chassis {:mvn/version "1.0.467"}}}
```

Note: `dev.data-star.clojure/http-kit` is already in :dev deps.

## Notes

**Dispatch capture pattern:** The `on-purge` callback requires capturing the dispatch function in an atom because Sandestin interceptors don't receive dispatch in their context (only effect handlers do). This is a known limitation.

**No sessions needed:** By embedding username in the key and using `data-bind` for client persistence, we avoid server-side session management entirely.
