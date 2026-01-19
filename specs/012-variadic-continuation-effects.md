# Variadic Continuation Effects

## Status

| Component | Status |
|-----------|--------|
| Design | Complete |
| `::sfere/with-connection` variadic | Complete |
| `::sfere/broadcast` variadic | Complete |
| Tests | Complete |
| Documentation | Complete |

## Overview

Enable `::sfere/with-connection` and `::sfere/broadcast` effects to accept a variadic number of continuation effect vectors, rather than exactly one. This allows dispatching multiple effects to a connection in a single invocation while maintaining backwards compatibility with existing single-effect usage.

## Motivation

Currently, to send multiple effects to a connection, users must either:

1. Wrap effects in multiple calls:
```clojure
[[::sfere/broadcast {:pattern [:* [:room "lobby"]]}
  [::twk/patch-signals {:typing false}]]
 [::sfere/broadcast {:pattern [:* [:room "lobby"]]}
  [::twk/patch-elements [:div "Message received"]]]]
```

2. Use a custom action that expands to multiple effects

Neither is ideal. The first duplicates the pattern matching work. The second requires action infrastructure.

With variadic continuations:
```clojure
[[::sfere/broadcast {:pattern [:* [:room "lobby"]]}
  [::twk/patch-signals {:typing false}]
  [::twk/patch-elements [:div "Message received"]]]]
```

## Current Implementation

### Effect Signatures

```clojure
;; with-connection-effect
(fn [{:keys [dispatch]} _system key nested-fx]
  (when-some [conn (p/connection store key)]
    (dispatch {:sse conn}
              {::twk/connection conn}
              [nested-fx])))  ; wraps single fx in vector

;; broadcast-effect
(fn [{:keys [dispatch]} _system {:keys [pattern exclude]} nested-fx]
  ;; ... matching logic ...
  (doseq [k keys-to-send]
    (dispatch {:sse conn}
              {::twk/connection conn}
              [nested-fx])))  ; wraps single fx in vector
```

### Current Schemas

```clojure
;; with-connection
[:tuple
 [:= with-connection-key]
 connection-key-schema
 s/EffectVector]  ; exactly one

;; broadcast
[:tuple
 [:= broadcast-key]
 [:map
  [:pattern pattern-schema]
  [:exclude {:optional true} ...]]
 s/EffectVector]  ; exactly one
```

## Proposed Design

### Updated Effect Signatures

Use Clojure rest args to accept zero or more continuation effects:

```clojure
;; with-connection-effect
(fn [{:keys [dispatch]} _system key & nested-fxs]
  (when (and (seq nested-fxs) (p/connection store key))
    (when-some [conn (p/connection store key)]
      (dispatch {:sse conn}
                {::twk/connection conn}
                (vec nested-fxs)))))  ; pass all fxs as EffectsVector

;; broadcast-effect
(fn [{:keys [dispatch]} _system {:keys [pattern exclude]} & nested-fxs]
  (when (seq nested-fxs)
    ;; ... matching logic ...
    (doseq [k keys-to-send]
      (dispatch {:sse conn}
                {::twk/connection conn}
                (vec nested-fxs)))))  ; pass all fxs as EffectsVector
```

### Updated Schemas

Use Malli's `[:+ ...]` (one or more) or `[:* ...]` (zero or more) for the continuation:

```clojure
;; with-connection - require at least one effect
[:cat
 [:= with-connection-key]
 connection-key-schema
 [:+ s/EffectVector]]

;; broadcast - require at least one effect
[:cat
 [:= broadcast-key]
 [:map
  [:pattern pattern-schema]
  [:exclude {:optional true} ...]]
 [:+ s/EffectVector]]
```

**Note:** Using `:cat` instead of `:tuple` because `:tuple` has fixed arity while `:cat` supports regex-like patterns including `[:+]` and `[:*]`.

### Backwards Compatibility

This change is fully backwards compatible:

| Usage | Before | After |
|-------|--------|-------|
| Single effect | `[::sfere/broadcast opts fx1]` | `[::sfere/broadcast opts fx1]` ✓ |
| Multiple effects | N/A | `[::sfere/broadcast opts fx1 fx2 fx3]` ✓ |

Existing code continues to work because a single effect is just the degenerate case of "one or more effects."

## Sandestin Schema Reference

From `ascolais.sandestin`:

```clojure
(def EffectVector
  "Schema for an effect vector that will be dispatched.
   Use in schemas where an effect accepts continuation effects."
  [:vector [:cat :qualified-keyword [:* :any]]])

(def EffectsVector
  "Schema for a vector of effect vectors (what dispatch receives)."
  [:vector EffectVector])
```

The dispatch function expects `EffectsVector` as its final argument. Our handlers currently wrap the single `nested-fx` in a vector to create an `EffectsVector`. With variadic args, `(vec nested-fxs)` naturally produces an `EffectsVector`.

## Implementation Plan

### 1. Update effect handlers

**File:** `src/clj/ascolais/sfere/registry.clj`

```clojure
(defn- with-connection-effect
  "Effect handler for ::with-connection.
   Dispatches nested effects to a specific stored connection."
  [store]
  (fn [{:keys [dispatch]} _system key & nested-fxs]
    (when (seq nested-fxs)
      (when-some [conn (p/connection store key)]
        (dispatch {:sse conn}
                  {::twk/connection conn}
                  (vec nested-fxs))))))

(defn- broadcast-effect
  "Effect handler for ::broadcast.
   Dispatches nested effects to all connections matching pattern."
  [store]
  (fn [{:keys [dispatch]} _system {:keys [pattern exclude]} & nested-fxs]
    (when (seq nested-fxs)
      (let [matching     (p/list-keys store pattern)
            excluded     (cond
                           (set? exclude) exclude
                           (vector? exclude) (set (p/list-keys store exclude))
                           :else #{})
            keys-to-send (remove excluded matching)]
        (doseq [k keys-to-send]
          (try
            (when-some [conn (p/connection store k)]
              (dispatch {:sse conn}
                        {::twk/connection conn}
                        (vec nested-fxs)))
            (catch Exception _
              nil)))))))
```

### 2. Update schemas in registry

```clojure
with-connection-key
{::s/description "Dispatch nested effects to a specific stored connection.

Arguments:
  key         - Full connection key [scope-id [:category identifier]]
  nested-fxs  - One or more effect vectors to dispatch to the connection

Example (single effect):
  [::sfere/with-connection [:default-scope [:lobby \"brian\"]]
   [::twk/patch-signals {:message \"\"}]]

Example (multiple effects):
  [::sfere/with-connection [:default-scope [:lobby \"brian\"]]
   [::twk/patch-signals {:typing false}]
   [::twk/patch-elements [:div \"Done\"]]]"
 ::s/schema [:cat
             [:= with-connection-key]
             connection-key-schema
             [:+ s/EffectVector]]
 ::s/handler (with-connection-effect store)}

broadcast-key
{::s/description "Dispatch nested effects to all connections matching a pattern.

Arguments:
  opts        - Map with :pattern (required) and :exclude (optional)
  nested-fxs  - One or more effect vectors to dispatch to each matching connection

Pattern examples:
  [:* [:lobby :*]]          - all lobby connections
  [:* [:lobby \"general\"]]   - all connections in 'general' lobby

Example (single effect):
  [::sfere/broadcast {:pattern [:* [:lobby :*]]}
   [::twk/patch-elements [:div \"Hello\"]]]

Example (multiple effects):
  [::sfere/broadcast {:pattern [:* [:lobby :*]]}
   [::twk/patch-signals {:typing false}]
   [::twk/patch-elements [:div \"Hello\"]]]"
 ::s/schema [:cat
             [:= broadcast-key]
             [:map
              [:pattern pattern-schema]
              [:exclude {:optional true}
               [:or
                [:set connection-key-schema]
                pattern-schema]]]
             [:+ s/EffectVector]]
 ::s/handler (broadcast-effect store)}
```

### 3. Add tests for variadic behavior

**File:** `test/src/clj/ascolais/sfere/registry_test.clj`

```clojure
(deftest with-connection-variadic-test
  (testing "with-connection dispatches multiple effects"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse (mock-sse "conn-1")
          key [:user-1 [:room "lobby"]]]
      (sfere/store! store key sse)

      (let [handler (get-in (sfere/registry store)
                            [::s/effects ::sfere/with-connection ::s/handler])
            ctx {:dispatch (fn [sys-override extra-data fx]
                             (swap! dispatched conj {:fx fx}))}]
        (handler ctx {} key
                 [::test-effect-1 "a"]
                 [::test-effect-2 "b"]
                 [::test-effect-3 "c"])

        (is (= 1 (count @dispatched)))
        (is (= [[::test-effect-1 "a"]
                [::test-effect-2 "b"]
                [::test-effect-3 "c"]]
               (:fx (first @dispatched))))))))

(deftest broadcast-variadic-test
  (testing "broadcast dispatches multiple effects to each connection"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse1 (mock-sse "conn-1")
          sse2 (mock-sse "conn-2")]
      (sfere/store! store [:user-1 [:room "lobby"]] sse1)
      (sfere/store! store [:user-2 [:room "lobby"]] sse2)

      (let [handler (get-in (sfere/registry store)
                            [::s/effects ::sfere/broadcast ::s/handler])
            ctx {:dispatch (fn [sys-override extra-data fx]
                             (swap! dispatched conj {:sse (:sse sys-override) :fx fx}))}]
        (handler ctx {} {:pattern [:* [:room "lobby"]]}
                 [::test-effect-1 "a"]
                 [::test-effect-2 "b"])

        (is (= 2 (count @dispatched)))
        (doseq [d @dispatched]
          (is (= [[::test-effect-1 "a"]
                  [::test-effect-2 "b"]]
                 (:fx d))))))))
```

### 4. Update spec 002-registry.md

Update the registry spec to document variadic continuations.

### 5. Update README if needed

Add examples showing multiple continuation effects.

## Testing Checklist

- [x] Single effect still works (backwards compat)
- [x] Multiple effects dispatch together
- [x] Zero effects is a no-op (no dispatch called)
- [x] `with-connection` variadic works
- [x] `broadcast` variadic works
- [x] Error handling still works (continues on failure)
- [x] Schema validates correctly

## Design Decisions

| Question | Decision |
|----------|----------|
| Zero effects allowed? | **No** - at least one effect is required. Schema uses `[:+ s/EffectVector]`. Dispatching zero effects is a no-op and should be caught at schema validation time. |
| Dispatch atomicity | All effects dispatch together in a single `dispatch` call per connection. For broadcast, all effects go to connection A, then all effects to connection B, etc. This provides atomicity per-connection. |

## Files to Modify

| File | Changes |
|------|---------|
| `src/clj/ascolais/sfere/registry.clj` | Update effect handlers and schemas |
| `test/src/clj/ascolais/sfere/registry_test.clj` | Add variadic tests |
| `specs/002-registry.md` | Update documentation |
| `README.md` | Add usage examples (if warranted) |
