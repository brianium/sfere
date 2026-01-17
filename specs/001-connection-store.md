# Connection Store Specification

## Status

| Component | Status |
|-----------|--------|
| `ascolais.sfere.protocols` | Complete |
| `ascolais.sfere.match` | Complete |
| `ascolais.sfere.atom` | Complete |
| `ascolais.sfere.caffeine` | Complete |
| Public API in `ascolais.sfere` | Complete |
| Tests | Complete |

## Overview

The connection store provides the underlying storage mechanism for SSE connections. It defines a protocol that can be implemented by different backends (atom, caffeine) and includes pattern matching support for the key structure.

## Protocol

```clojure
(ns ascolais.sfere.protocols)

(defprotocol ConnectionStore
  (store! [_ key conn] "Store a connection identified by key. Returns nil.")
  (connection [_ key] "Retrieve connection by exact key. Returns connection or nil.")
  (purge! [_ key] "Remove connection by exact key. Returns nil.")
  (list-keys [_] "Return sequence of all keys in store.")
  (list-keys [_ pattern] "Return sequence of keys matching pattern."))
```

## Key Structure

All keys must conform to: `[:scope-id [:category :id]]`

```clojure
;; Valid keys
[:user-123 [:room "lobby"]]
[:session-abc [:game 42]]
["scope" [:channel :main]]

;; Schema
(def ConnectionKey
  [:tuple :any [:tuple :any :any]])

(def ConnectionPattern
  [:tuple
   [:or [:= :*] :any]
   [:or [:= :*] [:tuple [:or [:= :*] :any] [:or [:= :*] :any]]]])
```

## Pattern Matching

The `list-keys` function with a pattern argument returns keys matching the pattern. Use `:*` as wildcard.

### Match Rules

| Pattern | Key | Match? |
|---------|-----|--------|
| `[:user-1 [:room "lobby"]]` | `[:user-1 [:room "lobby"]]` | Yes |
| `[:user-1 [:room "lobby"]]` | `[:user-2 [:room "lobby"]]` | No |
| `[:* [:room "lobby"]]` | `[:user-1 [:room "lobby"]]` | Yes |
| `[:* [:room "lobby"]]` | `[:user-2 [:room "lobby"]]` | Yes |
| `[:* [:room "lobby"]]` | `[:user-1 [:game 42]]` | No |
| `[:user-1 :*]` | `[:user-1 [:room "lobby"]]` | Yes |
| `[:user-1 :*]` | `[:user-1 [:game 42]]` | Yes |
| `[:user-1 :*]` | `[:user-2 [:room "lobby"]]` | No |
| `[:* [:room :*]]` | `[:user-1 [:room "lobby"]]` | Yes |
| `[:* [:room :*]]` | `[:user-1 [:room "other"]]` | Yes |
| `[:* [:room :*]]` | `[:user-1 [:game 42]]` | No |
| `[:* :*]` | `[:user-1 [:room "lobby"]]` | Yes |
| `[:* :*]` | `[:anything [:any "thing"]]` | Yes |

### Match Function

```clojure
(defn wildcard? [x] (= :* x))

(defn match-key?
  "Returns true if key matches pattern."
  [[p-scope p-inner] [k-scope k-inner]]
  (and (or (wildcard? p-scope) (= p-scope k-scope))
       (or (wildcard? p-inner)
           (let [[p-cat p-id] p-inner
                 [k-cat k-id] k-inner]
             (and (or (wildcard? p-cat) (= p-cat k-cat))
                  (or (wildcard? p-id) (= p-id k-id)))))))
```

## Implementations

### Atom Store

Simple in-memory store backed by an atom. Suitable for development and single-instance deployments.

```clojure
(ns ascolais.sfere.atom
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.match :refer [match-key?]]))

(defrecord AtomConnectionStore [*atom]
  p/ConnectionStore
  (store! [_ key conn]
    (swap! *atom assoc key conn)
    nil)
  (connection [_ key]
    (get @*atom key))
  (purge! [_ key]
    (swap! *atom dissoc key)
    nil)
  (list-keys [_]
    (keys @*atom))
  (list-keys [_ pattern]
    (filter #(match-key? pattern %) (keys @*atom))))

(defn store
  "Create an atom-backed connection store.

   Options:
   | Key    | Description                          |
   |--------|--------------------------------------|
   | :atom  | Existing atom to use (optional)      |"
  ([] (store {}))
  ([{:keys [atom]}]
   (->AtomConnectionStore (or atom (clojure.core/atom {})))))
```

### Caffeine Store

Production-ready store backed by Caffeine cache. Supports automatic expiration.

```clojure
(ns ascolais.sfere.caffeine
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.match :refer [match-key?]])
  (:import (com.github.benmanes.caffeine.cache Caffeine Cache Scheduler)
           (java.time Duration)))

(defrecord CaffeineConnectionStore [^Cache cache]
  p/ConnectionStore
  (store! [_ key conn]
    (.put cache key conn)
    nil)
  (connection [_ key]
    (.getIfPresent cache key))
  (purge! [_ key]
    (.invalidate cache key)
    nil)
  (list-keys [_]
    (keys (.asMap cache)))
  (list-keys [_ pattern]
    (filter #(match-key? pattern %) (keys (.asMap cache)))))

(defn store
  "Create a Caffeine-backed connection store.

   Options:
   | Key            | Description                                      | Default    |
   |----------------|--------------------------------------------------|------------|
   | :duration-ms   | Idle time before auto-purge                      | 600000 (10min) |
   | :maximum-size  | Max connections in store                         | 10000      |
   | :scheduler     | true for system scheduler, or Scheduler instance | nil        |
   | :cache         | Existing Cache instance (overrides other opts)   | nil        |"
  [{:keys [duration-ms maximum-size scheduler cache]
    :or {duration-ms 600000
         maximum-size 10000}}]
  (if cache
    (->CaffeineConnectionStore cache)
    (let [builder (doto (Caffeine/newBuilder)
                    (.maximumSize (long maximum-size))
                    (.expireAfterAccess (Duration/ofMillis duration-ms)))
          builder (if (true? scheduler)
                    (.scheduler builder (Scheduler/systemScheduler))
                    (if (instance? Scheduler scheduler)
                      (.scheduler builder scheduler)
                      builder))]
      (->CaffeineConnectionStore (.build builder)))))
```

## Public API

The main `ascolais.sfere` namespace wraps the protocol for convenience:

```clojure
(ns ascolais.sfere
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.atom :as atom-store]
            [ascolais.sfere.caffeine :as caff-store]))

(defmulti store
  "Create a connection store. Dispatch on :type key.

   Supported types:
   - :atom     — Simple atom-backed store
   - :caffeine — Caffeine cache with expiration"
  :type)

(defmethod store :atom [opts]
  (atom-store/store opts))

(defmethod store :caffeine [opts]
  (caff-store/store opts))

(defmethod store :default [{:keys [type]}]
  (throw (ex-info (str "Unknown store type: " type) {:type type})))

(defn store!
  "Store connection at key. Returns connection."
  [s key conn]
  (p/store! s key conn)
  conn)

(defn connection
  "Get connection by exact key. Returns nil if not found."
  [s key]
  (p/connection s key))

(defn purge!
  "Remove connection by key."
  [s key]
  (p/purge! s key))

(defn list-connections
  "List connection keys, optionally filtered by pattern."
  ([s]
   (p/list-keys s))
  ([s pattern]
   (p/list-keys s pattern)))

(defn connection-count
  "Count connections, optionally filtered by pattern."
  ([s]
   (count (p/list-keys s)))
  ([s pattern]
   (count (p/list-keys s pattern))))

(defn store?
  "Returns true if x implements ConnectionStore."
  [x]
  (satisfies? p/ConnectionStore x))
```

## Design Decisions

| Question | Decision |
|----------|----------|
| Key validation on store | No validation — accept any key structure |
| Thread safety | Atom: inherent. Caffeine: inherent. No additional locking. |
| Return values | `store!` returns connection (for chaining). Others return nil. |
| Empty pattern | `(list-keys store)` without pattern returns all keys |

## Testing Requirements

1. **Protocol compliance** — Both implementations pass same test suite
2. **Pattern matching** — All combinations from match rules table
3. **Concurrency** — Concurrent store/purge operations don't corrupt state
4. **Expiration (caffeine)** — Connections expire after duration-ms

## Dependencies

```clojure
;; deps.edn
{:deps
 {com.github.ben-manes.caffeine/caffeine {:mvn/version "3.2.2"}}}
```
