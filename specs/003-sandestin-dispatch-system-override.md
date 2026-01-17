# Sandestin: Dispatch with System Override

## Problem

Sandestin effects receive a `:dispatch` function in their handler context for continuation-based dispatch:

```clojure
;; From sandestin/dispatch.clj, execute-effects function
(let [dispatch-fn (fn dispatch-continuation
                    ([fx]
                     (dispatch-continuation {} fx))
                    ([extra-dispatch-data fx]
                     (dispatch registry system
                               (merge dispatch-data extra-dispatch-data)
                               fx)))
      handler-ctx {:dispatch dispatch-fn
                   :dispatch-data dispatch-data
                   :system system}]
```

The current `dispatch-fn` supports:
- `([fx])` — dispatch with current system and dispatch-data
- `([extra-dispatch-data fx])` — dispatch with merged dispatch-data, **same system**

There is no way to dispatch with a **modified system**.

## Why This Matters

Effects that orchestrate other effects sometimes need to dispatch with a different system context. Examples:

1. **Connection routing** — An effect looks up a connection from a store and dispatches nested effects using that connection instead of the current one

2. **Resource substitution** — An effect swaps a database connection, HTTP client, or other system resource for nested effects

3. **Context switching** — An effect dispatches work that should run against a different system context (e.g., different tenant, different credentials)

Without system override, these patterns require workarounds like post-creation binding or passing raw dispatch functions through non-standard channels.

## Current Limitation

The `:dispatch` function in handler context closes over the original `system` and provides no way to override it:

```clojure
([extra-dispatch-data fx]
 (dispatch registry system  ;; <-- always the original system
           (merge dispatch-data extra-dispatch-data)
           fx))
```

### Why not interceptors?

We considered using a `before-effect` interceptor to modify system. However, `execute-effect-with-interceptors` uses the **original** system when calling the effect handler, not the context returned by interceptors:

```clojure
(let [before-ctx (interceptors/run-before interceptors :effect effect-ctx)]
  (if (interceptors/halted? before-ctx)
    ...
    ;; Uses original `system`, not before-ctx's :system
    (let [result (execute-single-effect registry handler-ctx system effect)
```

Changing this behavior would be more invasive and implicit than extending dispatch-fn.

## Proposed Solution

Extend `dispatch-fn` to support a third arity for system override:

```clojure
(let [dispatch-fn (fn dispatch-continuation
                    ([fx]
                     (dispatch-continuation system {} fx))
                    ([extra-dispatch-data fx]
                     (dispatch-continuation system extra-dispatch-data fx))
                    ([new-system extra-dispatch-data fx]
                     (dispatch registry new-system
                               (merge dispatch-data extra-dispatch-data)
                               fx)))
      handler-ctx {:dispatch dispatch-fn
                   :dispatch-data dispatch-data
                   :system system}]
```

New signature:
- `([fx])` — unchanged
- `([extra-dispatch-data fx])` — unchanged
- `([new-system extra-dispatch-data fx])` — dispatch with provided system and merged dispatch-data

## Usage Example

An effect that dispatches nested effects with a different resource:

```clojure
(defn with-alternate-db-effect
  [{:keys [dispatch system]} db-pool [_ db-key nested-fx]]
  (when-some [alt-db (get-connection db-pool db-key)]
    (dispatch (assoc system :db alt-db)  ;; override :db in system
              {}                          ;; no extra dispatch-data
              [nested-fx])))
```

## Open Question

**Replace vs merge semantics for the new-system parameter?**

**Option A: Replace** — `new-system` is used directly
```clojure
(dispatch {:db alt-db} {} [fx])
;; system becomes {:db alt-db}
```

**Option B: Merge** — `new-system` is merged into current system
```clojure
(dispatch {:db alt-db} {} [fx])
;; system becomes (merge current-system {:db alt-db})
```

Considerations:
- **Replace** is explicit — caller controls exactly what system contains
- **Merge** is ergonomic — override one key without restating others
- With replace, caller can still merge manually: `(dispatch (assoc system :db alt-db) {} [fx])`
- With merge, caller cannot easily remove keys from system

**Recommendation:** Merge semantics, matching how `extra-dispatch-data` already works (merged, not replaced). This provides consistency and ergonomics for the common case of overriding specific keys.
