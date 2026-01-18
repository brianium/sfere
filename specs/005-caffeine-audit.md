# Caffeine Store Audit

## Status: Complete

| Component | Status |
|-----------|--------|
| Compare implementations | Complete |
| Determine if differences matter | Complete |
| Implement :expiry-mode option | Complete |

## Overview

Audit the sfere caffeine store implementation against the original datastar.wow.deacon implementation to verify correctness.

## Implementation Comparison

### datastar.wow.deacon (Original)

**Location:** `/Users/brian/projects/datastar.wow.deacon/src/datastar/wow/deacon/caffeine.clj`

```clojure
;; Stores wrapped entries with creation timestamp
(store! [_ k conn]
  (let [existing   (.getIfPresent cache k)
        created-at (:created-at existing (Instant/now))
        entry      {:conn conn :created-at created-at}]
    (.put cache k entry)))

;; Retrieves connection from wrapper
(connection [_ k] (some-> (.getIfPresent cache k) :conn))
```

**Expiry behavior:**
- Without scheduler: `expireAfterAccess` (sliding window)
- With scheduler: Fixed expiry from creation time via custom `Expiry` implementation

**The `fixed-expiry` reify:**
```clojure
(reify Expiry
  (expireAfterCreate [_ _ _ _] idle-ns)
  (expireAfterUpdate [_ _ new-value _ _]
    ;; Calculates remaining time from original creation
    (let [created-at   (:created-at new-value)
          elapsed-ns   (.toNanos (Duration/between created-at (Instant/now)))
          remaining-ns (max 0 (- idle-ns elapsed-ns))]
      remaining-ns))
  (expireAfterRead [_ _ _ _ old-expiry-ns] old-expiry-ns))
```

### sfere (Current)

**Location:** `src/clj/ascolais/sfere/caffeine.clj`

```clojure
;; Stores connection directly
(store! [_ key conn]
  (.put cache key conn)
  nil)

;; Retrieves connection directly
(connection [_ key]
  (.getIfPresent cache key))
```

**Expiry behavior:**
- Always uses `expireAfterAccess` (sliding window)
- Scheduler is attached but doesn't change expiry semantics

## Key Differences

| Aspect | deacon | sfere |
|--------|--------|-------|
| Entry storage | `{:conn conn :created-at timestamp}` | Connection directly |
| Expiry (no scheduler) | Sliding window | Sliding window |
| Expiry (with scheduler) | **Fixed from creation** | Sliding window |
| Complexity | Higher (custom Expiry) | Lower |

## Questions to Resolve

1. **Is fixed expiry intentional in deacon?**
   - Why would you want connections to expire at a fixed time regardless of activity?
   - Use case: Security (force re-auth), resource limits?

2. **Does sfere's simpler approach work for our use cases?**
   - SSE connections are typically long-lived with activity
   - Sliding window may be more appropriate for active connections

3. **Should sfere support both modes?**
   - Option to choose between fixed and sliding expiry
   - Or document the behavioral difference

## Audit Tasks

- [x] Review deacon git history for context on fixed-expiry decision
- [x] Test both implementations with scheduler enabled
- [x] Determine if fixed expiry is needed for sfere use cases
- [x] Either align implementations or document the intentional difference

## Findings

### Git History Analysis

The fixed-expiry feature was added to deacon in commit `2635d58` ("feat: caffeine supports fixed expiry"). The implementation **couples scheduler use with fixed expiry semantics**:

- Without scheduler: sliding window (`expireAfterAccess`)
- With scheduler: fixed expiry from creation time

There's no documentation explaining *why* these were coupled, but the effect is:
- When using a scheduler for more precise cleanup timing, you also get different expiry semantics
- This may have been intentional for session token use cases in deacon

### SSE Connection Behavior

For SSE connections (sfere's primary use case):

| Expiry Type | Behavior | SSE Implications |
|-------------|----------|------------------|
| Sliding window | Timer resets on access | Active connections stay alive indefinitely |
| Fixed | Expires at creation + duration | Connection dies even if active |

SSE connections typically:
- Have heartbeat/keepalive activity
- Should stay alive while active
- Are explicitly purged on close

**Sliding window is more appropriate** because:
1. Active connections should not be force-expired
2. Connection lifecycle is already managed (store on open, purge on close)
3. Only truly abandoned connections (no activity) should be cleaned up

### Why deacon Wraps Entries

Deacon stores `{:conn conn :created-at timestamp}` to support its fixed-expiry calculation. The `expireAfterUpdate` callback needs the original creation time to compute remaining TTL.

Sfere doesn't need this wrapper because sliding window expiry doesn't require tracking creation time.

## Decision

After review, we need **both** expiry modes:
- **Sliding window**: Active connections stay alive (good for long-lived SSE)
- **Fixed expiry**: Connection expires at exactly `creation + duration-ms` (good for guaranteed cleanup)

### Implementation: `:expiry-mode` Option

Add a new `:expiry-mode` option to `sfere.caffeine/store`:

| Value | Behavior | Use Case |
|-------|----------|----------|
| `:sliding` (default) | Timer resets on access | Long-lived SSE connections |
| `:fixed` | Expires at creation + duration | Guaranteed resource cleanup |

**Key difference from deacon**: We decouple expiry mode from scheduler. In deacon, using a scheduler implicitly enables fixed expiry. In sfere, these are independent options.

```clojure
;; Sliding window (default) - connection stays alive while accessed
(sfere/store {:type :caffeine :duration-ms 60000})

;; Fixed expiry - connection dies after exactly 60s regardless of access
;; Note: scheduler defaults to true when :expiry-mode is :fixed
(sfere/store {:type :caffeine :duration-ms 60000 :expiry-mode :fixed})
```

### Implementation Notes

Fixed expiry requires:
1. Wrap stored values as `{:conn conn :created-at (Instant/now)}`
2. Custom `Expiry` implementation that calculates remaining time from creation
3. Unwrap when retrieving via `connection`
