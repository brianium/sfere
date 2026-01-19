# Demo Presence Detection

## Status

| Component | Status |
|-----------|--------|
| Problem Analysis | **Complete** |
| Design | **Complete** |
| Implementation | **Complete** (Option B) |

## Context

Sfere is a connection storage library for targeting SSE writes. It is **not** a presence detection system. These are separate concerns:

| Concern | Question | Sfere's Role |
|---------|----------|--------------|
| Connection storage | "Where do I send data to user X?" | ✓ Core functionality |
| Presence detection | "Is user X still connected?" | ✗ Application concern |

SSE is a one-way channel (server→client). The server cannot reliably detect when a client disconnects — it only discovers this when a write fails, and TCP buffering makes even that unreliable.

## Problem Statement

The demo attempts to broadcast "user left" when connections are evicted. But:

1. Browser close doesn't trigger immediate SSE close detection
2. http-kit only detects dead connections on write failure
3. Writes are buffered, so failures are detected asynchronously (if at all)
4. With sliding expiry, all idle connections expire together

**Result**: "User left" notifications are unreliable or don't appear at all.

## Key Insight

Presence detection should be based on **positive life signals**, not **connection death detection**.

Instead of:
> "Detect when connection dies" → "User left"

Use:
> "User hasn't sent a signal in N seconds" → "User left"

This is more reliable because:
- Doesn't depend on TCP-level close detection
- Works regardless of buffering/async behavior
- Is explicit and predictable

## Proposed Solution: Client Heartbeat

### Overview

1. Client sends a ping to server every N seconds
2. Server tracks "last seen" timestamp per user
3. Background job checks for stale users and broadcasts departures

### Client-Side

Add a periodic ping using datastar's `@get`:

```html
<!-- In lobby-ui, establish presence heartbeat -->
<div data-on-load="@setInterval(@get('/ping'), 10000)">
  ...
</div>
```

Or with vanilla JS:
```javascript
setInterval(() => fetch('/ping', { method: 'POST' }), 10000);
```

### Server-Side: Presence Tracking

```clojure
;; Simple atom tracking last-seen times
(def presence (atom {}))  ;; {username -> timestamp}

(defn ping-handler [{:keys [signals]}]
  (let [username (:username signals)]
    (swap! presence assoc username (System/currentTimeMillis))
    {:status 204}))  ;; No content needed
```

### Server-Side: Stale User Detection

```clojure
(defn check-stale-users! [presence store dispatch max-age-ms]
  (let [now (System/currentTimeMillis)
        stale-threshold (- now max-age-ms)]
    (doseq [[username last-seen] @presence]
      (when (< last-seen stale-threshold)
        ;; User is stale - broadcast departure and clean up
        (swap! presence dissoc username)
        (let [key [::sfere/default-scope [:lobby username]]]
          (dispatch {} {}
            [[::sfere/broadcast {:pattern [:* [:lobby :*]]}
              [::twk/patch-elements (participant-left username)
               {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
             [::sfere/broadcast {:pattern [:* [:lobby :*]]}
              [::twk/patch-elements ""
               {twk/selector (str "#participant-" username)
                twk/patch-mode twk/pm-remove}]]])
          (sfere/purge! store key))))))

;; Run every 10 seconds
(defn start-presence-checker! [presence store dispatch]
  (let [running (atom true)]
    (future
      (while @running
        (Thread/sleep 10000)
        (check-stale-users! presence store dispatch 30000)))
    (fn [] (reset! running false))))
```

### How It Uses Sfere

| Sfere Primitive | Use in Presence Pattern |
|-----------------|------------------------|
| `list-connections` | Not needed — presence atom is source of truth |
| `broadcast` | Send "user left" to remaining users |
| `purge!` | Clean up connection when user is stale |
| `with-connection` | Could ping specific user, but not needed |

The presence atom replaces the need to query sfere for "who's here". Sfere is just used for the final broadcast and cleanup.

## Alternative: Simplified Demo Without Real-Time Presence

If real-time "user left" isn't critical for the demo, simplify:

1. **Explicit leave**: Broadcast "user left" only when user clicks Leave
2. **Silent TTL cleanup**: Let Caffeine expire idle connections without broadcast
3. **Document the limitation**: Note that real-time presence requires client heartbeats

This keeps the demo focused on sfere's core value (connection targeting, broadcast) without the complexity of presence detection.

## Decision: Option B (Simplified)

**Chosen approach**: Keep the demo focused on sfere's core functionality.

- "User left" is only broadcast on explicit Leave button click
- Browser close/crash → silent TTL cleanup, no notification
- Documents that real-time presence requires client heartbeats

**Rationale**: Option A adds significant complexity that distracts from sfere's purpose (connection storage and targeting). The demo should showcase what sfere does well, not work around SSE limitations.

## Implementation (Complete)

- [x] Remove `on-evict` callback from demo config
- [x] Keep explicit `/leave` broadcast as-is
- [x] Update demo spec noting presence is application-level
- [x] Document client heartbeat as pattern for apps that need it (Option A preserved in spec)

## Related

- [010-sse-close-immediate-purge.md](./010-sse-close-immediate-purge.md) - SSE close detection limitations
- [004-demo-application.md](./004-demo-application.md) - Demo application spec
