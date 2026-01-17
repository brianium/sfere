(ns ascolais.sfere.protocols)

(defprotocol ConnectionStore
  (store! [_ key conn] "Store a connection identified by key. Returns nil.")
  (connection [_ key] "Retrieve connection by exact key. Returns connection or nil.")
  (purge! [_ key] "Remove connection by exact key. Returns nil.")
  (list-keys [_] [_ pattern] "Return sequence of all keys, or keys matching pattern."))
