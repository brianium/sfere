# Demo Application Specification

## Status

| Component | Status |
|-----------|--------|
| Dependencies in deps.edn | Complete |
| Integrant system setup | Complete |
| Demo app (routes, handlers, views) | Complete |
| Manual testing | Complete |

## Implementation Summary

The demo is fully functional. Users can join a lobby, see other participants, exchange messages in real-time, and leave cleanly with proper UI updates across all connected clients.

## Key Architectural Discovery: Long-lived SSE via data-init

The original spec assumed `@sse-post()` would work for long-lived SSE connections. This was incorrect.

**The Problem:** When using `@sse-post()` (datastar's SSE POST action), the SSE connection closes immediately after receiving the first event. This is by design - `@sse-post` is intended for request/response patterns, not persistent connections.

**The Solution:** Use `data-init` with `@get()` for long-lived SSE connections:

```clojure
;; In lobby-ui, after user joins:
[:div#join-form {:data-init "@get('/sse?username=brian')"}
 ...]
```

This establishes a persistent SSE connection that stays open to receive broadcasts.

### Request Architecture

| Endpoint | Method | Purpose | SSE Behavior |
|----------|--------|---------|--------------|
| `/join` | POST | Return lobby UI with data-init | `with-open-sse? true` (closes after) |
| `/sse` | GET | Establish persistent connection | Stays open (stored in sfere) |
| `/message` | POST | Broadcast message to all | `with-open-sse? true` (closes after) |
| `/leave` | POST | Broadcast departure, cleanup | `with-open-sse? true` (closes after) |

## Issues Encountered

### 1. SSE Connections Closing Immediately

**Symptom:** Connections stored successfully, broadcasts dispatched with no errors, but only one event appeared in browser before `:client-close`.

**Root Cause:** `@sse-post()` is not designed for long-lived connections. Datastar closes the connection after receiving the response.

**Fix:** Separate the join flow into two requests:
1. POST `/join` returns HTML containing `data-init="@get('/sse')"`
2. GET `/sse` establishes the persistent SSE connection

### 2. Duplicate Participant Entries

**Symptom:** User appeared twice in participant list after joining.

**Root Cause:** `lobby-ui` included the user's name, then the broadcast also added them.

**Fix:** Exclude self from the participant list broadcast (self already has their name from `lobby-ui`), but include self in the "joined" message broadcast.

### 3. Join Messages in Wrong Location

**Symptom:** "X joined the lobby" messages appeared in the participant list instead of the messages area.

**Root Cause:** Single broadcast was appending a div containing both `<li>` and message to `#participant-list`.

**Fix:** Split into two separate broadcasts with correct selectors:
- Participant `<li>` → `#participant-list`
- Join message → `#messages`

### 4. New Users Don't See Existing Participants

**Symptom:** When Phil joins after Brian, Phil's participant list only shows Phil.

**Root Cause:** No server-side state tracking who's already in the lobby.

**Fix:** Query the sfere store for existing connections when a user connects via `/sse`, and send them the current participant list before broadcasting their join.

### 5. Participant Not Removed on Leave

**Symptom:** User remained in participant list after leaving.

**Root Cause:** The remove broadcast was patching an element but without a selector specifying which element to remove.

**Fix:** Use selector targeting the specific element: `{twk/selector "#participant-brian" twk/patch-mode twk/pm-remove}`

### 6. Connection Lifecycle is Application-Level

**Important:** Sfere is a connection storage library, not a connection monitoring library. Lifecycle management (detecting disconnects, broadcasting "user left") is an application concern.

The demo uses **explicit leave only** — "user left" is broadcast when the user clicks Leave. Browser close/crash results in silent TTL cleanup with no notification.

**Why this approach?** SSE close detection is fundamentally unreliable:
- SSE is one-way (server→client); server can't detect client disconnect
- http-kit only discovers dead connections on write failure
- Writes are buffered, so detection is inconsistent

For real-time presence, applications should implement client-initiated heartbeats (see spec 011-demo-presence.md).

## Overview

A lobby/room demo that showcases sfere's connection management and broadcast capabilities. Users join a lobby, can see who else is present, and send messages that broadcast to all participants.

## User Flow

1. User loads the page → sees join form
2. User enters a name and clicks "Join" → POST `/join` returns lobby UI with `data-init`
3. `data-init` triggers GET `/sse` → establishes persistent SSE, stores connection, broadcasts join
4. User sends a message → POST `/message` broadcasts to all stored connections
5. User clicks "Leave" → POST `/leave` broadcasts departure, removes from participant lists, closes stored SSE

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

     ;; Join form - uses regular @post, NOT @sse-post
     [:div#join-form
      [:input {:data-bind:username true :placeholder "Enter your name"}]
      [:button {:data-on:click "@post('/join')"} "Join Lobby"]]]]])

(defn lobby-ui
  "Returned by /join, includes data-init for persistent SSE."
  [username]
  [:div#join-form
   ;; data-init establishes long-lived SSE connection
   {:data-init (str "@get('/sse?username=" username "')")}

   [:div#participants
    [:h3 "In Lobby:"]
    [:ul#participant-list
     [:li {:id (str "participant-" username)} username]]]

   [:div#messages
    [:div.message.system-message (str "Welcome to the lobby, " username "!")]]

   [:div
    [:input {:data-bind:message true :placeholder "Type a message"}]
    [:button {:data-on:click "@post('/message')"} "Send"]]

   [:button {:data-on:click "@post('/leave')"} "Leave Lobby"]])
```

## Routes & Handlers

### GET /
Render the lobby page.

```clojure
(defn index [_]
  {:body (lobby-page)})
```

### POST /join
Return lobby UI with `data-init` that will establish SSE. Closes after sending.

```clojure
(defn join [{:keys [signals]}]
  (let [username (:username signals)]
    {::twk/with-open-sse? true  ;; Close after sending HTML
     ::twk/fx
     [[::twk/patch-elements (lobby-ui username)]]}))
```

### GET /sse
Establish persistent SSE connection. Stores connection, sends existing participants, broadcasts join.

```clojure
(defn sse-connect [{:keys [query-params]}]
  (let [username (get query-params "username")
        user-key [::sfere/default-scope [:lobby username]]
        ;; Query store for existing participants
        store (-> demo.system/*system* ::store)
        existing-keys (sfere/list-connections store [:* [:lobby :*]])
        existing-users (->> existing-keys
                            (map (fn [[_scope [_category uname]]] uname))
                            (remove #{username}))]
    {::sfere/key [:lobby username]  ;; Store this connection
     ::twk/fx
     (concat
       ;; Send existing participants to the new user
       (when (seq existing-users)
         [[::twk/patch-elements
           (into [:div] (map participant-item existing-users))
           {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]])
       ;; Broadcast to others (exclude self - already has their name)
       [[::sfere/broadcast {:pattern [:* [:lobby :*]] :exclude #{user-key}}
         [::twk/patch-elements (participant-item username)
          {twk/selector "#participant-list" twk/patch-mode twk/pm-append}]]
        ;; Broadcast join message to all (including self)
        [::sfere/broadcast {:pattern [:* [:lobby :*]]}
         [::twk/patch-elements [:div.message.system-message (str username " joined")]
          {twk/selector "#messages" twk/patch-mode twk/pm-append}]]])}))
```

### POST /message
Broadcast message to all. Closes after dispatch.

```clojure
(defn send-message [{:keys [signals]}]
  (let [username (:username signals)
        message (:message signals)]
    {::twk/with-open-sse? true
     ::twk/fx
     [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
       [::twk/patch-elements (message-bubble username message)
        {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
      ;; Clear sender's input via their stored connection
      [::sfere/with-connection [::sfere/default-scope [:lobby username]]
       [::twk/patch-signals {:message ""}]]]}))
```

### POST /leave
Broadcast departure, remove from participant lists, close stored SSE.

```clojure
(defn leave [{:keys [signals]}]
  (let [username (:username signals)
        user-key [::sfere/default-scope [:lobby username]]]
    {::twk/with-open-sse? true
     ::twk/fx
     [[::sfere/broadcast {:pattern [:* [:lobby :*]] :exclude #{user-key}}
       [::twk/patch-elements (participant-left username)
        {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
      [::sfere/broadcast {:pattern [:* [:lobby :*]] :exclude #{user-key}}
       [::twk/patch-elements ""
        {twk/selector (str "#participant-" username) twk/patch-mode twk/pm-remove}]]
      [::sfere/with-connection user-key
       [::twk/close-sse]]]}))
```

## System Setup

```clojure
(require '[ascolais.sandestin :as s])
(require '[ascolais.twk :as twk])
(require '[ascolais.sfere :as sfere])
(require '[starfederation.datastar.clojure.adapter.http-kit :as hk])

;; Store with TTL for cleanup (no on-evict - using explicit leave only)
(def store (sfere/store {:type :caffeine
                         :duration-ms 30000
                         :expiry-mode :sliding}))

(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store)]))

;; Optional: capture dispatch for REPL usage
(def *dispatch (atom dispatch))

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

1. **Long-lived SSE via data-init** — Use `@get()` with `data-init` for persistent connections, not `@sse-post()`
2. **with-open-sse? for request/response** — Set `true` for requests that should close after dispatch
3. **Username in key** — `::sfere/key [:lobby username]` embeds username for identification
4. **Broadcast to room** — `::sfere/broadcast` with pattern `[:* [:lobby :*]]`
5. **Exclude self** — `:exclude #{user-key}` to avoid duplicate updates
6. **Store as application state** — Query `sfere/list-connections` to find existing users
7. **Targeted effects** — `::sfere/with-connection` to send to a specific stored connection
8. **Element removal** — Use selector + `twk/pm-remove` to delete specific elements

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

**No sessions needed:** By embedding username in the key and using `data-bind` for client persistence, we avoid server-side session management entirely.

**Explicit leave for "user left":** The demo only broadcasts "user left" when the user clicks the Leave button. Browser close/crash results in silent TTL cleanup — no departure message is broadcast. This is intentional.

**Why not detect browser close?** SSE is one-way (server→client). The server cannot reliably detect when a client disconnects. http-kit only discovers dead connections when a write fails, and writes are buffered, making detection unreliable. For real-time presence, applications should implement client-initiated heartbeats (see spec 011).

**TTL is for cleanup, not presence:** The Caffeine store's TTL ensures abandoned connections don't leak. It's a safety net, not a presence mechanism.

**data-init vs @sse-post:** Datastar's `@sse-post()` is designed for request/response patterns where the SSE closes after the response. For persistent connections that receive multiple events over time, use `data-init` with `@post()` instead.
