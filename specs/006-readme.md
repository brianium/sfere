# README Documentation

## Status

**Complete**

| Component | Status |
|-----------|--------|
| Project overview | Done |
| Installation | Done |
| Quick start | Done |
| Key concepts | Done |
| Long-lived SSE | Done |
| Store options | Done |
| REPL discoverability | Done |
| Integration guide | Done |
| Full example link | Done |

## Overview

Create a comprehensive README.md for the sfere repository that demonstrates usage with Sandestin/Twk, using patterns from the demo application.

## Target Audience

- Developers familiar with Clojure
- Users of Sandestin and Twk
- Those building real-time applications with Datastar

## README Structure

### 1. Header & Badges
- Project name and one-line description
- Clojars/deps.edn badge (if published)
- CI status badge (once CI is set up)

### 2. What is Sfere?
Brief explanation:
- SSE connection management for Sandestin/Twk
- Enables multiplayer/broadcast scenarios
- REPL-friendly connection introspection

### 3. Installation

```clojure
;; deps.edn
{:deps {io.github.brianium/sfere {:git/tag "vX.Y.Z" :git/sha "..."}}}
```

Note dependencies:
- sandestin
- twk
- caffeine (optional, for caffeine store)

### 4. Quick Start

Minimal example showing:
1. Create a store
2. Create dispatch with sfere registry
3. Store a connection via `::sfere/key`
4. Broadcast to connections

```clojure
(require '[ascolais.sandestin :as s])
(require '[ascolais.twk :as twk])
(require '[ascolais.sfere :as sfere])

;; 1. Create store
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

### 5. Key Concepts

#### Connection Keys
```clojure
;; Structure: [scope-id [:category identifier]]
[:ascolais.sfere/default-scope [:lobby "brian"]]
[:user-123 [:room "general"]]
```

#### Patterns & Wildcards
```clojure
[:* [:lobby :*]]          ;; All lobby connections
[:* [:lobby "general"]]   ;; All in "general" lobby
[:user-123 [:room :*]]    ;; All rooms for user-123
```

#### Effects

| Effect | Purpose |
|--------|---------|
| `::sfere/broadcast` | Send to all matching connections |
| `::sfere/with-connection` | Send to a specific connection |

### 6. Long-lived SSE Connections

Critical pattern from demo:
- Use `data-init` with `@get()` for persistent SSE
- NOT `@sse-post()` which closes after response

```clojure
;; In your HTML (via twk/hiccup)
[:div {:data-init "@get('/sse?user=brian')"}]

;; GET /sse handler - establishes persistent connection
(defn sse-connect [{:keys [query-params]}]
  {::sfere/key [:lobby (get query-params "user")]
   ::twk/fx [...]})  ;; No with-open-sse? - stays open!

;; POST handlers use with-open-sse? true
(defn send-message [...]
  {::twk/with-open-sse? true  ;; Closes after dispatch
   ::twk/fx [[::sfere/broadcast ...]]})
```

### 7. Store Options

```clojure
;; Atom store (development)
(sfere/store {:type :atom})

;; Caffeine store (production)
(sfere/store {:type :caffeine
              :duration-ms 600000    ;; 10 min idle timeout
              :maximum-size 10000})
```

### 8. REPL Discoverability

```clojure
;; List all connections
(sfere/list-connections store)

;; List matching pattern
(sfere/list-connections store [:* [:lobby :*]])

;; Count connections
(sfere/connection-count store [:* [:lobby :*]])

;; Send from REPL
(dispatch {} {}
  [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
    [::twk/patch-elements [:div "Server message!"]]]])
```

### 9. Integration with Sandestin

Show the registry pattern:
```clojure
(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store {:id-fn    #(get-in % [:session :user-id])
                            :on-purge (fn [ctx key] (log/info "Purged" key))})]))
```

Registry options table:
| Option | Description |
|--------|-------------|
| `:id-fn` | Derives scope-id from context |
| `:on-purge` | Called when connection is purged |

### 10. Full Example

Link to demo application:
```
See dev/src/clj/demo/app.clj for a complete lobby chat example.
```

Or inline a simplified but complete example.

## Writing Guidelines

- Keep examples minimal but complete
- Show real patterns from the working demo
- Emphasize the `data-init` + `@get()` pattern for long-lived SSE
- Include REPL examples for discoverability
- Link to Sandestin and Twk documentation

## References

- Demo application: `dev/src/clj/demo/app.clj`
- Spec 004: `specs/004-demo-application.md` (especially "Issues Encountered")
- Sandestin: https://github.com/brianium/sandestin
- Twk: https://github.com/brianium/twk
