(ns ascolais.sfere.caffeine
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.match :refer [match-key?]])
  (:import (com.github.benmanes.caffeine.cache Caffeine Cache Scheduler)
           (java.time Duration)))

(defrecord CaffeineConnectionStore [^Cache cache]
  p/ConnectionStore
  (store! [_ key conn]
    (.put cache key conn)
    nil)
  (connection [_ key]
    (.getIfPresent cache key))
  (purge! [_ key]
    (.invalidate cache key)
    nil)
  (list-keys [_]
    (keys (.asMap cache)))
  (list-keys [_ pattern]
    (filter #(match-key? pattern %) (keys (.asMap cache)))))

(defn store
  "Create a Caffeine-backed connection store.

   Options:
   | Key            | Description                                      | Default    |
   |----------------|--------------------------------------------------|------------|
   | :duration-ms   | Idle time before auto-purge                      | 600000 (10min) |
   | :maximum-size  | Max connections in store                         | 10000      |
   | :scheduler     | true for system scheduler, or Scheduler instance | nil        |
   | :cache         | Existing Cache instance (overrides other opts)   | nil        |"
  [{:keys [duration-ms maximum-size scheduler cache]
    :or {duration-ms 600000
         maximum-size 10000}}]
  (if cache
    (->CaffeineConnectionStore cache)
    (let [builder (doto (Caffeine/newBuilder)
                    (.maximumSize (long maximum-size))
                    (.expireAfterAccess (Duration/ofMillis duration-ms)))
          builder (if (true? scheduler)
                    (.scheduler builder (Scheduler/systemScheduler))
                    (if (instance? Scheduler scheduler)
                      (.scheduler builder scheduler)
                      builder))]
      (->CaffeineConnectionStore (.build builder)))))
