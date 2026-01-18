# Sfere

SSE connection management for Sandestin/Twk applications.

## What is Sfere?

Sfere provides connection storage and retrieval for [Sandestin](https://github.com/brianium/sandestin) and [Twk](https://github.com/brianium/twk) applications, enabling:

- **Multiplayer/broadcast scenarios** — Send updates to multiple connected clients
- **Out-of-band dispatch** — Push updates from REPL, background jobs, or external events
- **Connection introspection** — Query active connections with pattern matching

## Installation

```clojure
;; deps.edn
{:deps {io.github.brianium/sfere {:git/tag "v0.3.0" :git/sha "586bc38"}}}
```

Sfere depends on:
- [sandestin](https://github.com/brianium/sandestin) v0.4.0+
- [twk](https://github.com/brianium/twk) v0.2.1+
- [caffeine](https://github.com/ben-manes/caffeine) (included, for caffeine store)

## Quick Start

```clojure
(require '[ascolais.sandestin :as s])
(require '[ascolais.twk :as twk])
(require '[ascolais.sfere :as sfere])

;; 1. Create a store
(def store (sfere/store {:type :atom}))

;; 2. Create dispatch with registries
(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store)]))

;; 3. Handler that stores connection
(defn join [{:keys [signals]}]
  {::sfere/key [:lobby (:username signals)]  ;; Store with this key
   ::twk/fx [[::twk/patch-elements [:div "Joined!"]]]})

;; 4. Handler that broadcasts
(defn send-message [{:keys [signals]}]
  {::twk/with-open-sse? true
   ::twk/fx
   [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
     [::twk/patch-elements [:div (:message signals)]]]]})
```

## Key Concepts

### Connection Keys

Keys follow a vector structure: `[scope-id [:category identifier]]`

```clojure
;; Examples
[::sfere/default-scope [:lobby "brian"]]  ;; Default scope
[:user-123 [:room "general"]]             ;; Custom scope from :id-fn
```

- **scope-id** — Derived from context via `:id-fn` option (defaults to `::sfere/default-scope`)
- **[:category identifier]** — User-provided identifier from `::sfere/key`

### Patterns & Wildcards

Use `:*` as a wildcard for pattern matching:

```clojure
[:* [:lobby :*]]          ;; All lobby connections
[:* [:lobby "general"]]   ;; All in "general" lobby
[:user-123 [:room :*]]    ;; All rooms for user-123
[:* :*]                   ;; All connections
```

### Effects

| Effect | Purpose |
|--------|---------|
| `::sfere/broadcast` | Send effects to all connections matching a pattern |
| `::sfere/with-connection` | Send effects to a specific connection by key |

#### Broadcast Options

```clojure
{::twk/fx
 [[::sfere/broadcast {:pattern [:* [:lobby :*]]
                      :exclude #{[::sfere/default-scope [:lobby "admin"]]}}
   [::twk/patch-elements [:div "Message"]]]]}
```

| Option | Description |
|--------|-------------|
| `:pattern` | Key pattern with optional `:*` wildcards |
| `:exclude` | Set of exact keys to exclude from broadcast |

## Long-lived SSE Connections

For persistent connections (chat, live updates), use `data-init`:

```clojure
;; HTML (via twk/hiccup or chassis)
[:div {:data-init "@post('/sse')"}]

;; POST /sse handler - establishes persistent connection
(defn sse-connect [{:keys [signals]}]
  (let [username (:username signals)]
    {::sfere/key [:lobby username]
     ::twk/fx [[::twk/patch-elements [:div "Connected!"]]]}))
     ;; No ::twk/with-open-sse? - connection stays open!

;; Other handlers close after dispatch
(defn send-message [{:keys [signals]}]
  {::twk/with-open-sse? true  ;; Closes after effects dispatch
   ::twk/fx [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
              [::twk/patch-elements [:div (:message signals)]]]]})
```

**Important:** The HTTP method (GET, POST, etc.) doesn't affect SSE persistence. Connections stay open unless you set `::twk/with-open-sse? true`, which closes the connection after effects dispatch.

## Store Options

### Atom Store (Development)

```clojure
(sfere/store {:type :atom})
```

Simple in-memory store. No expiration.

### Caffeine Store (Production)

```clojure
(sfere/store {:type :caffeine
              :duration-ms 600000    ;; 10 min timeout
              :maximum-size 10000})  ;; Max connections
```

| Option | Description | Default |
|--------|-------------|---------|
| `:duration-ms` | Time before connection is evicted | 600000 (10 min) |
| `:maximum-size` | Maximum number of stored connections | 10000 |
| `:expiry-mode` | `:sliding` (reset on access) or `:fixed` (from creation) | `:sliding` |
| `:scheduler` | `true` for system scheduler, or `Scheduler` instance | `true` if `:fixed` |

#### Expiry Modes

**Sliding (default):** Timer resets on each access. Connections stay alive while active.

```clojure
(sfere/store {:type :caffeine :duration-ms 60000})
```

**Fixed:** Connection expires at exactly `creation + duration-ms` regardless of access. Useful for guaranteed resource cleanup.

```clojure
(sfere/store {:type :caffeine :duration-ms 60000 :expiry-mode :fixed})
```

## REPL Discoverability

```clojure
;; List all connection keys
(sfere/list-connections store)
;; => [[::sfere/default-scope [:lobby "alice"]]
;;     [::sfere/default-scope [:lobby "bob"]]]

;; List matching pattern
(sfere/list-connections store [:* [:lobby :*]])

;; Count connections
(sfere/connection-count store [:* [:lobby :*]])
;; => 2

;; Send updates from REPL
(dispatch {} {}
  [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
    [::twk/patch-elements [:div "Server announcement!"]]]])
```

## Integration with Sandestin

### Registry Options

```clojure
(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store {:id-fn    #(get-in % [:session :user-id])
                            :on-purge (fn [ctx key] (log/info "Purged" key))})]))
```

| Option | Description |
|--------|-------------|
| `:id-fn` | `(fn [ctx] scope-id)` — Derives scope-id from handler context |
| `:on-purge` | `(fn [ctx key])` — Called when connection is removed from store |

## Full Example

See [`dev/src/clj/demo/app.clj`](dev/src/clj/demo/app.clj) for a complete lobby chat example demonstrating:

- Long-lived SSE via `data-init`
- Broadcasting messages to all lobby participants
- Participant join/leave notifications
- Connection exclusion patterns
- Integration with Integrant for system lifecycle

## Development

### REPL

```bash
clj -M:dev
```

```clojure
(dev)
(reload)  ;; After changes
```

### Tests

```bash
clj -X:test
```

## License

Copyright 2026

Distributed under the Eclipse Public License version 1.0.
