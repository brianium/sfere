(ns ascolais.sfere.atom
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.match :refer [match-key?]]))

(defrecord AtomConnectionStore [*atom]
  p/ConnectionStore
  (store! [_ key conn]
    (swap! *atom assoc key conn)
    nil)
  (connection [_ key]
    (get @*atom key))
  (purge! [_ key]
    (swap! *atom dissoc key)
    nil)
  (list-keys [_]
    (keys @*atom))
  (list-keys [_ pattern]
    (filter #(match-key? pattern %) (keys @*atom))))

(defn- install-watch!
  "Install a watch on the atom to call on-evict when entries are removed or replaced."
  [a on-evict]
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

(defn store
  "Create an atom-backed connection store.

   Options:
   | Key       | Description                                    |
   |-----------|------------------------------------------------|
   | :atom     | Existing atom to use (optional)                |
   | :on-evict | Callback (fn [key conn cause]) on removal      |"
  ([] (store {}))
  ([{:keys [atom on-evict]}]
   (let [a (or atom (clojure.core/atom {}))]
     (when on-evict
       (install-watch! a on-evict))
     (->AtomConnectionStore a))))
