# Unified on-evict Callback

## Status

| Component | Status |
|-----------|--------|
| Design | **Complete** |
| Remove on-purge from registry | **Complete** |
| Add on-evict to atom store | **Complete** |
| Update demo | **Complete** |
| Update docs | **Complete** |

## Problem Statement

Currently there are two callbacks for connection removal:

- `:on-purge` (registry) - has dispatch context, called on `::twk/sse-closed`
- `:on-evict` (Caffeine store) - no context, called by Caffeine on eviction

This is confusing and inconsistent. Users have to understand two different callback models.

## Design

### Unified Model

Remove `:on-purge` from the registry. Both stores support `:on-evict` with the same signature:

```clojure
(fn [key conn cause]
  ;; key: connection key [scope-id [category id]]
  ;; conn: the connection being removed
  ;; cause: keyword indicating why removal happened
  )
```

Users who need dispatch must capture it themselves - no magic context passing.

### Cause Values

**Caffeine store:**
- `:expired` - TTL timeout
- `:explicit` - `purge!` called (includes registry auto-purge on SSE close)
- `:replaced` - overwritten by new value for same key
- `:size` - evicted due to size limit
- `:collected` - garbage collected (weak/soft references)

**Atom store:**
- `:explicit` - `purge!` called
- `:replaced` - overwritten by new value for same key

### Implementation

#### Atom Store

Use `add-watch` to detect changes:

```clojure
(defn store
  [{:keys [atom on-evict]}]
  (let [a (or atom (clojure.core/atom {}))]
    (when on-evict
      (add-watch a ::on-evict
        (fn [_ _ old-state new-state]
          ;; Find removed keys
          (doseq [k (keys old-state)]
            (when-not (contains? new-state k)
              (on-evict k (get old-state k) :explicit)))
          ;; Find replaced keys
          (doseq [k (keys new-state)]
            (when (and (contains? old-state k)
                       (not= (get old-state k) (get new-state k)))
              (on-evict k (get old-state k) :replaced))))))
    (->AtomConnectionStore a)))
```

#### Registry Changes

Remove `:on-purge` option. The interceptor still calls `purge!` on SSE close, which triggers the store's `on-evict`.

## Migration

Users currently using `:on-purge`:

```clojure
;; Before
(sfere/registry store {:on-purge (fn [ctx key] ...)})

;; After - capture what you need
(def *dispatch (atom nil))

(sfere/store {:type :caffeine
              :on-evict (fn [key conn cause]
                          (when (#{:explicit :expired} cause)
                            (@*dispatch {} {} [...])))})
```

## Implementation Plan

- [x] Add `:on-evict` to atom store using `add-watch`
- [x] Remove `:on-purge` from registry
- [x] Update registry interceptor (just calls purge!, no callback)
- [x] Update demo to use store-level on-evict only
- [x] Update README documentation
- [x] Update registry spec (002)
