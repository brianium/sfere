(ns ascolais.sfere.caffeine
  (:require [ascolais.sfere.protocols :as p]
            [ascolais.sfere.match :refer [match-key?]])
  (:import (com.github.benmanes.caffeine.cache Caffeine Cache Expiry RemovalCause RemovalListener Scheduler Ticker)
           (java.time Duration Instant)
           (java.util.concurrent Executor)))

(defrecord CaffeineConnectionStore [^Cache cache expiry-mode]
  p/ConnectionStore
  (store! [_ key conn]
    (if (= expiry-mode :fixed)
      (let [existing (.getIfPresent cache key)
            created-at (:created-at existing (Instant/now))
            entry {:conn conn :created-at created-at}]
        (.put cache key entry))
      (.put cache key conn))
    nil)
  (connection [_ key]
    (if (= expiry-mode :fixed)
      (some-> (.getIfPresent cache key) :conn)
      (.getIfPresent cache key)))
  (purge! [_ key]
    (.invalidate cache key)
    nil)
  (list-keys [_]
    (keys (.asMap cache)))
  (list-keys [_ pattern]
    (filter #(match-key? pattern %) (keys (.asMap cache)))))

(defn- cause->keyword
  "Convert Caffeine RemovalCause to keyword."
  [^RemovalCause cause]
  (condp = cause
    RemovalCause/EXPIRED :expired
    RemovalCause/EXPLICIT :explicit
    RemovalCause/REPLACED :replaced
    RemovalCause/SIZE :size
    RemovalCause/COLLECTED :collected
    :unknown))

(defn- make-removal-listener
  "Create a RemovalListener that calls on-evict with (key conn cause)."
  [on-evict expiry-mode]
  (reify RemovalListener
    (onRemoval [_ key value cause]
      (let [conn (if (= expiry-mode :fixed) (:conn value) value)]
        (on-evict key conn (cause->keyword cause))))))

(defn- fixed-expiry
  "Create an Expiry that expires entries at a fixed time from creation."
  [duration-ms]
  (let [duration-ns (.toNanos (Duration/ofMillis duration-ms))]
    (reify Expiry
      (expireAfterCreate [_ _ _ _]
        duration-ns)
      (expireAfterUpdate [_ _ new-value _ _]
        (let [created-at (:created-at new-value)
              elapsed-ns (.toNanos (Duration/between created-at (Instant/now)))
              remaining-ns (max 0 (- duration-ns elapsed-ns))]
          remaining-ns))
      (expireAfterRead [_ _ _ _ current-duration]
        current-duration))))

(defn store
  "Create a Caffeine-backed connection store.

   Options:
   | Key            | Description                                      | Default    |
   |----------------|--------------------------------------------------|------------|
   | :duration-ms   | Time before auto-purge                           | 600000 (10min) |
   | :maximum-size  | Max connections in store                         | 10000      |
   | :expiry-mode   | :sliding (reset on access) or :fixed (from creation) | :sliding |
   | :scheduler     | true for system scheduler, or Scheduler instance | true if :fixed |
   | :on-evict      | Callback (fn [key conn cause]) on eviction       | nil        |
   | :ticker        | Ticker instance for time (testing)               | nil        |
   | :executor      | Executor for async operations (testing)          | nil        |
   | :cache         | Existing Cache instance (overrides other opts)   | nil        |"
  [{:keys [duration-ms maximum-size expiry-mode scheduler on-evict ticker executor cache]
    :or {duration-ms 600000
         maximum-size 10000
         expiry-mode :sliding}}]
  (if cache
    (->CaffeineConnectionStore cache expiry-mode)
    (let [fixed? (= expiry-mode :fixed)
          scheduler (if (and fixed? (nil? scheduler)) true scheduler)
          builder (doto (Caffeine/newBuilder)
                    (.maximumSize (long maximum-size)))
          builder (if fixed?
                    (.expireAfter builder (fixed-expiry duration-ms))
                    (.expireAfterAccess builder (Duration/ofMillis duration-ms)))
          builder (if (true? scheduler)
                    (.scheduler builder (Scheduler/systemScheduler))
                    (if (instance? Scheduler scheduler)
                      (.scheduler builder scheduler)
                      builder))
          builder (if on-evict
                    (.removalListener builder (make-removal-listener on-evict expiry-mode))
                    builder)
          builder (if (instance? Ticker ticker)
                    (.ticker builder ticker)
                    builder)
          builder (if (instance? Executor executor)
                    (.executor builder executor)
                    builder)]
      (->CaffeineConnectionStore (.build builder) expiry-mode))))

(defn clean-up!
  "Trigger cache maintenance. Useful for testing to force eviction checks."
  [store]
  (.cleanUp ^Cache (:cache store)))
