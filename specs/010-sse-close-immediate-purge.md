# SSE Close Immediate Purge

## Status

| Component | Status |
|-----------|--------|
| Problem Analysis | **Complete** |
| Design | **Complete** |
| Implementation | **Complete** |

## Context: Sfere's Scope

Sfere is a **connection storage library**, not a connection monitoring library. Its responsibilities:

1. **Store** connections with meaningful keys
2. **Retrieve** connections for targeted dispatch (`with-connection`)
3. **Broadcast** to patterns of connections
4. **TTL expiry** as a safety net for cleanup (not real-time lifecycle management)

Connection lifecycle management (detecting disconnects, broadcasting "user left" messages, heartbeats) is an **application-level concern**. Sfere provides primitives that applications can use to implement their own lifecycle logic.

## Problem Statement

When `::twk/sse-closed` is dispatched, the interceptor couldn't purge the connection because `purge-connection!` required `::sfere/key` in the dispatch context. Since the SSE close event has no key context, connections were only cleaned up via TTL expiry.

This meant sfere wasn't doing its basic job of cleaning up connections when it knew they were closed.

## Solution

When `::twk/sse-closed` is dispatched, find the connection by matching the SSE object and purge it immediately:

```clojure
(defn- find-key-by-connection
  "Scan store to find the key for a given connection object."
  [store conn]
  (when conn
    (->> (p/list-keys store)
         (filter #(identical? conn (p/connection store %)))
         first)))

(defn- purge-by-connection!
  "Find and purge a connection from store by matching the SSE object."
  [store conn]
  (when-some [key (find-key-by-connection store conn)]
    (p/purge! store key)))
```

The interceptor now uses this on SSE close:

```clojure
(sse-close-dispatch? ctx)
(let [sse (get-in ctx [:system :sse])]
  (purge-by-connection! store sse)
  ctx)
```

## Important Limitation: SSE Close Detection

SSE close detection is **passive**. When a browser tab closes:

1. The TCP connection is terminated on the client side
2. The server doesn't know until it tries to **write** to that connection
3. Only on a failed write does `::twk/sse-closed` dispatch

This means:
- **Active sessions**: If there's any broadcast activity, dead connections are detected quickly
- **Idle sessions**: If everyone goes idle, connections expire via TTL together

This is a fundamental limitation of TCP/HTTP, not something sfere should try to solve. Applications that need real-time disconnect detection should implement heartbeats.

## Application-Level Lifecycle Management

Sfere provides primitives for applications to build their own lifecycle logic:

| Primitive | Use Case |
|-----------|----------|
| `on-evict` callback | React to connection removal (any cause) |
| `list-keys` | Query current connections |
| `purge!` | Explicitly remove a connection |
| `connection-count` | Check occupancy |

### Example: Application Heartbeat

```clojure
;; Application-level heartbeat (not sfere's responsibility)
(defn heartbeat! [store dispatch]
  (doseq [key (sfere/list-keys store [:* [:lobby :*]])]
    (dispatch {} {}
      [[::sfere/with-connection key
        [::twk/patch-elements [:span.heartbeat]]]])))

;; Run periodically - failed writes flush dead connections
;; which triggers on-evict where the app can broadcast "user left"
```

### Example: on-evict Handler

```clojure
;; Application decides what to do when connections are evicted
(defn my-on-evict [key conn cause]
  (let [[_scope [_category username]] key]
    (case cause
      :explicit (log/info "Connection closed" username)
      :expired  (log/info "Connection timed out" username)
      :replaced (log/info "Connection replaced" username))

    ;; Application chooses whether to broadcast
    (when (#{:explicit :expired} cause)
      (broadcast-user-left! username))))
```

## Implementation Notes

Uses O(n) scan via `find-key-by-connection` with `identical?` matching. This is acceptable because:

1. SSE close events are infrequent
2. Typical deployments have < 10,000 connections
3. No additional state to keep in sync

## Implementation Plan

- [x] Add `find-key-by-connection` helper to registry
- [x] Add `purge-by-connection!` helper to registry
- [x] Update `sfere-interceptor` to use `purge-by-connection!` on SSE close
- [x] Add tests for the new behavior

## Related

- [008-sse-close-purge-fix.md](./008-sse-close-purge-fix.md) - Original SSE investigation
- [009-unified-on-evict.md](./009-unified-on-evict.md) - Unified on-evict callback model
