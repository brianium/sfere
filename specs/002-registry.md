# Sfere Registry Specification

## Status

| Component | Status |
|-----------|--------|
| `registry` function | Not started |
| `::sfere/with-connection` effect | Not started (blocked on Sandestin change) |
| `::sfere/broadcast` effect | Not started (blocked on Sandestin change) |
| Interceptor (auto-store/purge) | Not started |
| Integration with Twk | Not started |
| Tests | Not started |

## Dependencies

- **[Sandestin Dispatch System Override](./003-sandestin-dispatch-system-override.md)** — The `::with-connection` and `::broadcast` effects require the 3-arity dispatch function to override system.

## Overview

Sfere exports a single Sandestin registry that provides SSE connection management for the Twk ecosystem. The registry enables storing connections for later retrieval (REPL usage, out-of-band dispatch) and broadcasting to multiple connections.

## Registry Function

```clojure
(require '[ascolais.sfere :as sfere])

(sfere/registry)                     ;; defaults: atom store
(sfere/registry store)               ;; custom store
(sfere/registry store opts)          ;; custom store + options
```

### Options

| Key | Description |
|-----|-------------|
| `:id-fn` | `(fn [ctx] scope-id)` — Derives scope-id from context. Defaults to `(constantly ::sfere/default-scope)` |
| `:on-purge` | `(fn [ctx key])` — Called when a connection is purged (e.g., SSE closed). Defaults to no-op |

### Usage with Sandestin/Twk

```clojure
(require '[ascolais.sandestin :as s])
(require '[ascolais.twk :as twk])
(require '[ascolais.sfere :as sfere])

(def store (sfere/store {:type :atom}))

(def dispatch
  (s/create-dispatch
    [(twk/registry)
     (sfere/registry store {:id-fn #(get-in % [:session :user-id])})]))
```

## Connection Key Structure

All keys follow the structure: `[:scope-id [:category :id]]`

- **scope-id** — Derived from context via `:id-fn` (e.g., user-id, session-id)
- **category** — Keyword classifying the connection (e.g., `:room`, `:game`, `:channel`)
- **id** — Identifier within the category (e.g., `"lobby"`, `42`, `:main`)

Examples:
```clojure
[:user-123 [:room "lobby"]]
[:session-abc [:game 42]]
[:user-456 [:channel :notifications]]
```

### Wildcard Matching

Use `:*` as a wildcard when listing or broadcasting:

| Pattern | Matches |
|---------|---------|
| `[:user-123 [:room "lobby"]]` | Exact match only |
| `[:* [:room "lobby"]]` | All scope-ids in room "lobby" |
| `[:user-123 :*]` | All connections for user-123 |
| `[:* [:room :*]]` | All connections in any room |
| `[:* :*]` | All connections |

## Effects

### `::sfere/with-connection`

Dispatch nested effects to a specific stored connection.

```clojure
[::sfere/with-connection [:user-123 [:room "lobby"]]
 [::twk/patch-signals {:message "Hello!"}]]
```

**Schema:**
```clojure
[:tuple
 [:enum ::sfere/with-connection]
 ::sfere/connection-key
 :ascolais.sandestin/EffectVector]
```

**Behavior:**
1. Looks up connection by full key (note: full key, not inner key)
2. If not found, silent no-op
3. If found, uses `dispatch` from effect context to dispatch nested effects with:
   - `:sse` in system set to the retrieved connection
   - `::twk/connection` in dispatch-data set to the retrieved connection

**Implementation sketch:**
```clojure
;; Requires: Sandestin dispatch system override (3-arity dispatch)
(defn with-connection-effect
  [{:keys [dispatch system]} store [_ key nested-fx]]
  (when-some [conn (p/connection store key)]
    (dispatch (assoc system :sse conn)       ;; new-system
              {::twk/connection conn}         ;; extra-dispatch-data
              [nested-fx])))
```

### `::sfere/broadcast`

Dispatch nested effects to all connections matching a pattern.

```clojure
[::sfere/broadcast {:pattern [:* [:room "lobby"]]}
 [::twk/patch-signals {:message "Player joined!"}]]
```

**Schema:**
```clojure
[:tuple
 [:enum ::sfere/broadcast]
 [:map [:pattern ::sfere/connection-pattern]]
 :ascolais.sandestin/EffectVector]
```

**Behavior:**
1. Find all keys matching pattern
2. Apply `:exclude` filter if provided
3. For each matching key, look up connection and dispatch nested effects
4. If a dispatch fails, log via `tap>` and continue with remaining connections
5. No connection validation before dispatch — let failures occur naturally

**Implementation sketch:**
```clojure
;; Requires: Sandestin dispatch system override (3-arity dispatch)
(defn broadcast-effect
  [{:keys [dispatch system]} store [_ {:keys [pattern exclude]} nested-fx]]
  (let [matching     (p/list-keys store pattern)
        excluded     (cond
                       (set? exclude) exclude
                       (vector? exclude) (set (p/list-keys store exclude))
                       :else #{})
        keys-to-send (remove excluded matching)]
    (doseq [k keys-to-send]
      (try
        (when-some [conn (p/connection store k)]
          (dispatch (assoc system :sse conn)       ;; new-system
                    {::twk/connection conn}         ;; extra-dispatch-data
                    [nested-fx]))
        (catch Exception e
          (tap> {:sfere/event :broadcast-error :key k :error e}))))))
```

**Props map keys:**

| Key | Description |
|-----|-------------|
| `:pattern` | Required. Connection pattern with optional `:*` wildcards |
| `:exclude` | Optional. Pattern (with wildcards) OR set of explicit keys to exclude |

**Exclude examples:**
```clojure
;; Exclude by pattern
[::sfere/broadcast {:pattern [:* [:room "lobby"]]
                    :exclude [:user-123 :*]}
 [::twk/patch-signals {:msg "Hello"}]]

;; Exclude explicit keys
[::sfere/broadcast {:pattern [:* [:room "lobby"]]
                    :exclude #{[:user-123 [:room "lobby"]]
                               [:user-456 [:room "lobby"]]}}
 [::twk/patch-signals {:msg "Hello"}]]
```

## Interceptor Behavior

The registry installs a `:before-dispatch` interceptor that handles automatic connection storage and cleanup.

### Key Derivation

The full connection key is always derived by combining:
1. **scope-id** from `(id-fn ctx)` — called fresh each time
2. **inner-key** from `::sfere/key` in the response (via dispatch-data)

```clojure
(defn- scoped-key
  [ctx id-fn]
  (let [id (id-fn ctx)]
    (when-some [k (get-in ctx [:dispatch-data ::twk/response ::sfere/key])]
      [id k])))
```

This means `::sfere/key` in the handler response is just the inner part `[:category :id]`, not the full key.

### Auto-store on SSE establishment

When a Twk response includes `::sfere/key`, the interceptor stores the connection:

```clojure
;; Ring handler response
{::twk/fx [[::twk/patch-signals {:connected true}]]
 ::sfere/key [:room "lobby"]}  ;; inner key only
```

The full key becomes `[(id-fn ctx) [:room "lobby"]]`.

Storage happens when:
- `::sfere/key` is present in response
- SSE connection exists in system
- No connection already set in dispatch-data
- `::twk/with-open-sse?` is not true

### Auto-purge on SSE close

When `::twk/sse-closed` is dispatched, the interceptor:
1. Re-derives the full key using `id-fn` + `::sfere/key` from response
2. Calls `:on-purge` callback if provided
3. Removes connection from store

Note: Twk keeps the response in dispatch-data throughout the connection lifecycle, so `::sfere/key` is available on close.

## Public API Functions

These functions are available for REPL usage and LLM discoverability:

### `sfere/list-connections`

```clojure
(sfere/list-connections store)
;; => [[:user-1 [:room "lobby"]] [:user-2 [:room "lobby"]]]

(sfere/list-connections store [:* [:room "lobby"]])
;; => [[:user-1 [:room "lobby"]] [:user-2 [:room "lobby"]]]

(sfere/list-connections store [:user-1 :*])
;; => [[:user-1 [:room "lobby"]]]
```

### `sfere/connection`

```clojure
(sfere/connection store [:user-1 [:room "lobby"]])
;; => <sse-generator> or nil
```

### `sfere/connection-count`

```clojure
(sfere/connection-count store)
;; => 5

(sfere/connection-count store [:* [:room "lobby"]])
;; => 2
```

## Design Decisions

| Question | Decision |
|----------|----------|
| Missing connection in `::with-connection` | Silent no-op |
| Broadcast failure handling | Continue with remaining, log failures via `tap>` |
| `:exclude` syntax | Both patterns and explicit key sets |
| Connection validation | Let it fail naturally, no pre-validation |
