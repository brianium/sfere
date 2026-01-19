# Demo Heartbeat for Connection Monitoring

## Status

| Component | Status |
|-----------|--------|
| Problem Analysis | **Complete** |
| Design | **Proposed** |
| Implementation | Pending |

## Context

Sfere is a connection storage library. Connection lifecycle management (detecting disconnects, broadcasting "user left") is an application concern. This spec demonstrates that sfere provides sufficient primitives for applications to implement their own lifecycle monitoring.

## Problem Statement

SSE close detection is passive — the server only knows a connection is dead when it tries to write to it. If all users go idle after one closes their tab, the dead connection isn't detected until TTL expiry, at which point all connections expire together.

The demo should show a complete, working example of application-level lifecycle management.

## Proposed Solution: Server-Side Heartbeat

Periodically write to all connections. Failed writes trigger SSE close detection, which purges the connection and fires `on-evict`.

### Design

```clojure
(ns demo.heartbeat
  (:require [ascolais.sfere :as sfere]
            [ascolais.twk :as twk]))

(defn heartbeat-once!
  "Send a heartbeat to all lobby connections.
   Failed writes will trigger SSE close detection."
  [store dispatch]
  (doseq [key (sfere/list-keys store [:* [:lobby :*]])]
    (try
      (dispatch {} {}
        [[::sfere/with-connection key
          ;; Invisible element that doesn't affect UI
          [::twk/patch-elements [:span.heartbeat {:style "display:none"}]
           {twk/selector "body" twk/patch-mode twk/pm-append}]]])
      (catch Exception _
        ;; Connection may already be gone, ignore
        nil))))

(defn start-heartbeat!
  "Start a background heartbeat thread.
   Returns a function to stop the heartbeat."
  [store dispatch interval-ms]
  (let [running (atom true)]
    (future
      (while @running
        (Thread/sleep interval-ms)
        (try
          (heartbeat-once! store dispatch)
          (catch Exception e
            (tap> {:demo/heartbeat-error (.getMessage e)})))))
    ;; Return stop function
    (fn [] (reset! running false))))
```

### Integration with Integrant

```clojure
;; In demo/app.clj

(defmethod ig/init-key ::heartbeat [_ {:keys [store dispatch interval-ms]
                                        :or {interval-ms 10000}}]
  (start-heartbeat! store dispatch interval-ms))

(defmethod ig/halt-key! ::heartbeat [_ stop-fn]
  (when stop-fn
    (stop-fn)))

;; In demo/config.clj

(def config
  {::app/store         {...}
   ::app/dispatch      {:store (ig/ref ::app/store)}
   ::app/heartbeat     {:store (ig/ref ::app/store)
                        :dispatch (ig/ref ::app/dispatch)
                        :interval-ms 10000}  ;; 10 seconds
   ...})
```

### Expected Behavior

1. Brian and Phil join the lobby
2. Heartbeat runs every 10 seconds, writing to both connections
3. Brian closes his browser tab
4. Within 10 seconds, next heartbeat tries to write to Brian
5. Write fails → `::twk/sse-closed` dispatched → `purge-by-connection!` runs
6. `on-evict` fires with `:explicit` cause
7. Phil sees "brian left the lobby"

### Heartbeat Interval Considerations

| Interval | Trade-off |
|----------|-----------|
| 5s | Fast detection, more network traffic |
| 10s | Good balance for most apps |
| 30s | Low overhead, slower detection |

The interval should be shorter than the TTL to ensure dead connections are detected before expiry.

## Alternative: Client-Initiated Keepalive

Instead of server pushing heartbeats, clients could ping the server:

```javascript
// Client-side (in lobby page)
setInterval(() => {
  fetch('/keepalive', { method: 'POST' })
}, 10000)
```

```clojure
;; Server-side
(defn keepalive [{:keys [signals]}]
  (let [username (:username signals)]
    ;; Just touch the connection - no response needed
    {::twk/with-open-sse? true
     ::twk/fx []}))
```

**Trade-offs:**
- Pro: Only active clients send traffic
- Con: Requires client-side code changes
- Con: Detects "client stopped pinging" not "connection died"

The server-side heartbeat is simpler and more reliable for detecting dead TCP connections.

## Implementation Plan

- [ ] Create `demo.heartbeat` namespace with `heartbeat-once!` and `start-heartbeat!`
- [ ] Add `::heartbeat` Integrant component to demo/app.clj
- [ ] Add heartbeat to demo/config.clj
- [ ] Test: verify "user left" appears within heartbeat interval after tab close
- [ ] Document the pattern in demo spec

## Success Criteria

With heartbeat enabled:
1. Brian joins, Phil joins
2. Brian closes tab (no explicit "Leave")
3. Within ~10 seconds, Phil sees "brian left the lobby"
4. Phil's connection remains active

This proves sfere provides sufficient primitives for application-level lifecycle management.

## Related

- [010-sse-close-immediate-purge.md](./010-sse-close-immediate-purge.md) - SSE close detection fix
- [004-demo-application.md](./004-demo-application.md) - Demo application spec
