# SSE Close Immediate Purge

## Status

| Component | Status |
|-----------|--------|
| Problem Analysis | **Complete** |
| Design | **Complete** |
| Implementation | **Complete** |

## Problem Statement

With sliding expiry mode, the `on-evict` callback fires but `remaining-connections` is always empty because all connections expire at the same time. This makes it impossible to broadcast "user left" messages to other users.

### Root Cause

1. **Sliding windows sync together**: When user A sends a message, it broadcasts to all connections (A and B). The broadcast calls `p/connection` for each recipient, which is a cache access that resets both sliding windows.

2. **All connections expire together**: Since all connections receive the same broadcasts, their sliding windows stay synchronized. When activity stops, they all expire within milliseconds of each other.

3. **SSE close doesn't purge immediately**: When a browser tab closes, `::twk/sse-closed` is dispatched, but `purge-connection!` requires `::sfere/key` in the context. Since the SSE close event has no key context, nothing is purged. Connections only expire via TTL.

4. **Empty remaining-connections**: When on-evict fires for user A, user B has also expired (or is about to). The broadcast has no recipients.

### Observed Behavior

```clojure
;; Brian and Phil both join the lobby
;; Brian sends messages, which broadcast to both connections
;; Both sliding windows reset together on each broadcast

;; Brian closes his browser tab
;; 30 seconds later (TTL expires)...

{:on-evict true
 :username "brian"
 :cause :expired
 :remaining-connections []}  ;; Empty! Phil also expired

{:on-evict true
 :username "phil"
 :cause :expired
 :remaining-connections []}  ;; Both gone
```

### Why Fixed Expiry Works (But Isn't Ideal)

With `:expiry-mode :fixed`, each connection expires based on creation time, not access time. If Brian joins before Phil, Brian expires first while Phil is still active. However:

- Fixed expiry isn't the typical use case for connection management
- Users expect sliding expiry semantics (inactive for X seconds)
- Fixed expiry would disconnect active users after a fixed duration

## Proposed Solution

When `::twk/sse-closed` is dispatched, sfere should find the connection by matching the SSE object and purge it immediately. This triggers on-evict with `:explicit` cause while other connections are still active.

### Design

Add a reverse lookup to find a connection's key by its SSE object:

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

Update the interceptor to use this when SSE closes:

```clojure
(defn- sfere-interceptor
  [store {:keys [id-fn]}]
  {:before-dispatch
   (fn [ctx]
     (cond
       ;; On SSE close, purge by connection object
       (sse-close-dispatch? ctx)
       (let [sse (get-in ctx [:system :sse])]
         (purge-by-connection! store sse)
         ctx)

       ;; ... existing logic ...
       ))})
```

### Expected Behavior After Fix

```clojure
;; Brian and Phil both join the lobby
;; Brian sends messages (sliding windows sync)

;; Brian closes his browser tab
;; http-kit detects TCP failure on next write attempt
;; ::twk/sse-closed is dispatched

;; Interceptor finds brian's key by matching SSE object
;; purge! triggers on-evict immediately

{:on-evict true
 :username "brian"
 :cause :explicit  ;; Not :expired!
 :remaining-connections [[:ascolais.sfere/default-scope [:lobby "phil"]]]}
 ;; Phil is still connected!

;; Broadcast succeeds, Phil sees "brian left the lobby"
```

### Performance Consideration

The reverse lookup scans all keys in the store. For typical use cases (< 10,000 connections), this is negligible. For very large deployments:

- Consider maintaining a reverse index `{conn -> key}`
- Or accept the O(n) scan since SSE close is infrequent

For now, the simple scan is sufficient.

## Implementation Plan

- [x] Add `find-key-by-connection` helper to registry
- [x] Add `purge-by-connection!` helper to registry
- [x] Update `sfere-interceptor` to use `purge-by-connection!` on SSE close
- [x] Add tests for the new behavior
- [x] Update demo to use sliding expiry (already using `:sliding`)
- [ ] Verify "user left" broadcasts work correctly (manual testing)

## Alternative Considered

**Maintain reverse index**: Store `{conn -> key}` mapping alongside the main store.

Rejected because:
- Adds complexity (must keep both maps in sync)
- SSE close is infrequent, scan cost is acceptable
- Can optimize later if needed

## Implementation Notes

The implementation uses an O(n) scan via `find-key-by-connection` to locate the connection key by matching the SSE object with `identical?`. This approach was chosen over a reverse index because:

1. The O(n) scan is fast enough for typical deployments (< 10,000 connections)
2. SSE close events are infrequent relative to other operations
3. No additional state to keep in sync with the store
4. Simpler to reason about and debug

The interceptor now calls `purge-by-connection!` on SSE close, which:
1. Scans all keys in the store
2. Finds the key whose stored connection is `identical?` to the closing SSE
3. Calls `p/purge!` on that key, triggering `on-evict` with `:explicit` cause

Tests verify:
- Purging works when no key is in the dispatch context
- Correct connection is purged when multiple exist
- `on-evict` callback is triggered with `:explicit` cause
- Gracefully handles case when connection is not in store

## Related

- [008-sse-close-purge-fix.md](./008-sse-close-purge-fix.md) - Original SSE investigation
- [009-unified-on-evict.md](./009-unified-on-evict.md) - Unified on-evict callback model
