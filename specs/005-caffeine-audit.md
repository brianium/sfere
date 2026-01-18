# Caffeine Store Audit

## Status

| Component | Status |
|-----------|--------|
| Compare implementations | Not started |
| Determine if differences matter | Not started |
| Fix or document differences | Not started |

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

- [ ] Review deacon git history for context on fixed-expiry decision
- [ ] Test both implementations with scheduler enabled
- [ ] Determine if fixed expiry is needed for sfere use cases
- [ ] Either align implementations or document the intentional difference

## Recommendation (Preliminary)

The sfere implementation appears simpler and potentially more appropriate for SSE connections:
- Active connections should stay alive (sliding window is correct)
- Fixed expiry from creation seems suited for session tokens, not SSE

However, this should be verified before concluding the audit.
