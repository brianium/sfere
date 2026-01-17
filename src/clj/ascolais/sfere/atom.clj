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

(defn store
  "Create an atom-backed connection store.

   Options:
   | Key    | Description                          |
   |--------|--------------------------------------|
   | :atom  | Existing atom to use (optional)      |"
  ([] (store {}))
  ([{:keys [atom]}]
   (->AtomConnectionStore (or atom (clojure.core/atom {})))))
