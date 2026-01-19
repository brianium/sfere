# SSE Close Purge Fix

## Status

| Component | Status |
|-----------|--------|
| Investigation | **Complete** |
| Root cause | SSE protocol limitation - server can't detect passive client disconnect |
| Design | **Complete** |
| Implementation | **Complete** |

## Problem Statement

Connections are not being purged from the sfere store when a browser tab closes.

## Investigation Results

### Root Cause

This is an **SSE protocol limitation**, not a sfere or http-kit bug.

SSE (Server-Sent Events) is a one-way protocol (server → client). The server has no mechanism to detect when a client passively disconnects. The server only learns about disconnection when:

1. **Write fails** - Server tries to send data, TCP reports failure
2. **TCP keepalive timeout** - OS-level probes fail (takes minutes)
3. **Client notification** - Client explicitly sends a close signal (requires JS)

### http-kit `open?` Does NOT Work

We tested accessing the underlying http-kit channel:

```clojure
;; Browser connected
(hk/open? channel)  ;; => true

;; Browser tab closed (no write attempted)
(hk/open? channel)  ;; => true (STILL TRUE!)

;; After attempting a write
(hk/send! channel "data: test\n\n")
;; ... http-kit detects TCP failure, calls on-close
(sfere/connection-count store)  ;; => 0 (purged!)
```

**Conclusion:** `open?` only reflects server-side close state, not actual TCP state. You MUST write to detect dead connections - there is no passive check available.

## Design

### Simplified Approach

Rather than complicate the atom store with eviction semantics, we accept that **Caffeine is the right tool for connection lifecycle management**.

| Callback | Where | When | Has dispatch context? |
|----------|-------|------|----------------------|
| `on-purge` | Registry | `::twk/sse-closed` dispatched | Yes |
| `on-evict` | Caffeine store | TTL expiration | No |

### API

```clojure
;; Caffeine store with eviction callback
(def store
  (sfere/store {:type :caffeine
                :ttl-seconds 30
                :expiry-mode :sliding
                :on-evict (fn [key conn cause]
                            ;; Called when TTL expires
                            ;; No dispatch context - user must capture dispatch if needed
                            )}))

;; Registry handles dispatch-triggered purges
(sfere/registry store {:on-purge (fn [ctx key] ...)})
```

### Callback Signatures

```clojure
;; on-purge - called during dispatch, has full context
(fn [ctx key]
  ;; ctx contains :dispatch-data, :system, etc.
  ;; Can dispatch effects via captured dispatch reference
  )

;; on-evict - called by Caffeine, no dispatch context
(fn [key conn cause]
  ;; key: the connection key [scope-id [category id]]
  ;; conn: the SSE connection being evicted
  ;; cause: :expired, :explicit, :replaced, :size, :collected
  )
```

### Broadcasting from on-evict

Since `on-evict` has no dispatch context, users who need to dispatch effects must capture the dispatch function:

```clojure
;; Demo pattern - capture dispatch for use in on-evict
(defn make-on-evict [dispatch-atom]
  (fn [key _conn cause]
    (when (= cause :expired)
      (let [[_scope [_category username]] key]
        ;; Use captured dispatch to broadcast
        (@dispatch-atom {} {}
          [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
            [::twk/patch-elements (user-left-message username)]]])))))

(def store
  (sfere/store {:type :caffeine
                :ttl-seconds 30
                :on-evict (make-on-evict *dispatch)}))
```

## Demo Requirements

The demo should:
1. Use Caffeine store (not atom)
2. Set a reasonable TTL (e.g., 30 seconds for demo purposes)
3. Broadcast "user left" when Caffeine evicts a connection
4. Accept that there's a delay between browser close and "user left" message

### Demo Flow

1. User joins lobby → connection stored in Caffeine with TTL
2. User is active → each dispatch resets TTL (sliding expiry)
3. User closes browser tab → no immediate detection
4. TTL expires (30s of inactivity) → Caffeine evicts
5. `on-evict` fires → broadcasts:
   - "X left the lobby" message to `#messages`
   - Remove participant from `#participant-list`
6. Other users see the departure message and updated participant list

## Implementation Plan

### Phase 1: Add on-evict to Caffeine Store
- [x] Add `:on-evict` option to `make-caffeine-store`
- [x] Wire to Caffeine's `RemovalListener`
- [x] Pass removal cause to callback
- [x] Add tests (using fake ticker and direct executor for deterministic testing)

### Phase 2: Update Demo
- [x] Switch demo from atom to Caffeine store
- [x] Add TTL configuration (30s)
- [x] Implement `on-evict` that broadcasts "user left"
- [x] Test the full flow

### Phase 3: Documentation
- [x] Document SSE limitation in README
- [x] Add "Connection Lifecycle" section
- [x] Document Caffeine vs atom store trade-offs
- [x] Update registry spec (002) with clarification

## Implementation Notes

### Caffeine RemovalListener

```clojure
(import '[com.github.benmanes.caffeine.cache RemovalListener RemovalCause])

(defn- cause->keyword [^RemovalCause cause]
  (condp = cause
    RemovalCause/EXPIRED   :expired
    RemovalCause/EXPLICIT  :explicit
    RemovalCause/REPLACED  :replaced
    RemovalCause/SIZE      :size
    RemovalCause/COLLECTED :collected
    :unknown))

(defn- make-removal-listener [on-evict]
  (reify RemovalListener
    (onRemoval [_ key value cause]
      (on-evict key value (cause->keyword cause)))))
```

### Sliding vs Fixed Expiry with on-evict

- **Sliding** (`:expiry-mode :sliding`): TTL resets on each access. Good for "inactive for X seconds".
- **Fixed** (`:expiry-mode :fixed`): TTL from creation. Good for "max session duration".

For browser disconnect detection, **sliding** is preferred - we want to detect inactivity.

## Related

- 002-registry.md - Clarify on-purge is dispatch-triggered only
- 005-caffeine-audit.md - Already has expiry-mode support
