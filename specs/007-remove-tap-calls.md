# Remove tap> from Public APIs

## Status

**Complete**

| Component | Status |
|-----------|--------|
| Remove taps from registry.clj | Done |
| Fix tests that rely on tap> | Done |
| Verify demo still works | N/A (demo keeps its tap> calls) |

## Overview

Remove all `tap>` calls from public-facing source code. Tap debugging is acceptable in the demo application but not in the library itself.

## Current tap> Usage

### Public API (MUST REMOVE)

**`src/clj/ascolais/sfere/registry.clj`** — 9 tap> calls:

| Location | Event | Purpose |
|----------|-------|---------|
| Line 58 | `:wrap-connection-reuse` | Debug connection reuse middleware |
| Line 96 | `:broadcast` | Log broadcast pattern matching |
| Line 105 | `:broadcast-send` | Log each send during broadcast |
| Line 109 | `:broadcast-dispatch-result` | Log dispatch results |
| Line 111 | `:broadcast-error` | Log errors during broadcast |
| Line 126 | `:store-check` | Debug store conditions |
| Line 136 | `:stored!` | Log successful stores |
| Line 156 | `:inject-connection` | Debug connection injection |
| Line 177 | `:sse-close-detected` | Log SSE close events |

### Tests (MUST FIX)

**`test/src/clj/ascolais/sfere/registry_test.clj`** — Line 152:
```clojure
(with-redefs [tap> (fn [x] (swap! tapped conj x))]
  ;; Test relies on capturing tap> for broadcast-error
```

The test verifies that broadcast continues after errors and logs them. Need to refactor to not rely on tap>.

### Demo (ACCEPTABLE)

**`dev/src/clj/demo/app.clj`**:
- Line 217: `on-purge` callback logs purge events
- Line 255: SSE close status logging

These are fine — demo is for development/debugging.

## Implementation Plan

### 1. Remove taps from registry.clj

Simply delete all `tap>` calls. The library should be silent by default.

For error handling in broadcast, the current behavior is correct (continue on error), but we should NOT log via tap>. The error is already caught and handled.

### 2. Fix broadcast error test

The test currently verifies:
1. Broadcast continues after first dispatch throws
2. Error was logged via tap>

Refactor to:
1. Keep assertion that broadcast continues (count dispatched = 1)
2. Remove assertion about tap> logging (implementation detail)

Alternative: Add an `:on-error` callback option to broadcast. But this adds complexity — simpler to just remove the tap assertion.

### 3. Verify demo works

Run the demo and confirm:
- Lobby join/leave works
- Messages broadcast correctly
- Demo's own tap> calls still appear in Portal

## Tasks

- [ ] Remove all tap> calls from `src/clj/ascolais/sfere/registry.clj`
- [ ] Update test to not rely on tap> capture
- [ ] Run full test suite
- [ ] Manual smoke test of demo
- [ ] Commit changes

## Design Decision

**No logging callbacks in v1.** Libraries that need observability can wrap the registry or use their own interceptors. This keeps the core simple.

If observability is needed later, consider:
- Optional `:on-broadcast-error` callback in registry options
- Metrics integration via separate module
