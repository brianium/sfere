(ns ascolais.sfere-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sfere :as sfere]
            [ascolais.sfere.match :as match]
            [ascolais.sfere.atom :as atom-store]
            [ascolais.sfere.caffeine :as caff-store]))

;; =============================================================================
;; Pattern Matching Tests
;; =============================================================================

(deftest wildcard?-test
  (testing "wildcard? identifies :* as wildcard"
    (is (true? (match/wildcard? :*)))
    (is (false? (match/wildcard? :foo)))
    (is (false? (match/wildcard? "*")))
    (is (false? (match/wildcard? nil)))))

(deftest match-key?-test
  (testing "exact match"
    (is (true? (match/match-key? [:user-1 [:room "lobby"]]
                                 [:user-1 [:room "lobby"]])))
    (is (false? (match/match-key? [:user-1 [:room "lobby"]]
                                  [:user-2 [:room "lobby"]]))))

  (testing "scope wildcard"
    (is (true? (match/match-key? [:* [:room "lobby"]]
                                 [:user-1 [:room "lobby"]])))
    (is (true? (match/match-key? [:* [:room "lobby"]]
                                 [:user-2 [:room "lobby"]])))
    (is (false? (match/match-key? [:* [:room "lobby"]]
                                  [:user-1 [:game 42]]))))

  (testing "inner wildcard"
    (is (true? (match/match-key? [:user-1 :*]
                                 [:user-1 [:room "lobby"]])))
    (is (true? (match/match-key? [:user-1 :*]
                                 [:user-1 [:game 42]])))
    (is (false? (match/match-key? [:user-1 :*]
                                  [:user-2 [:room "lobby"]]))))

  (testing "category wildcard"
    (is (true? (match/match-key? [:* [:room :*]]
                                 [:user-1 [:room "lobby"]])))
    (is (true? (match/match-key? [:* [:room :*]]
                                 [:user-1 [:room "other"]])))
    (is (false? (match/match-key? [:* [:room :*]]
                                  [:user-1 [:game 42]]))))

  (testing "full wildcard"
    (is (true? (match/match-key? [:* :*]
                                 [:user-1 [:room "lobby"]])))
    (is (true? (match/match-key? [:* :*]
                                 [:anything [:any "thing"]])))))

;; =============================================================================
;; Store Protocol Compliance Tests (shared by both implementations)
;; =============================================================================

(defn test-store-basic-operations [store-fn store-name]
  (testing (str store-name " basic operations")
    (let [s (store-fn)
          key [:user-1 [:room "lobby"]]
          conn {:type :sse :channel "ch-1"}]

      (testing "store! and connection"
        (is (nil? (sfere/connection s key)) "connection not found initially")
        (is (= conn (sfere/store! s key conn)) "store! returns connection")
        (is (= conn (sfere/connection s key)) "connection retrieved after store"))

      (testing "purge!"
        (sfere/purge! s key)
        (is (nil? (sfere/connection s key)) "connection removed after purge"))

      (testing "list-connections without pattern"
        (sfere/store! s [:user-1 [:room "lobby"]] {:id 1})
        (sfere/store! s [:user-2 [:room "lobby"]] {:id 2})
        (sfere/store! s [:user-1 [:game 42]] {:id 3})
        (is (= 3 (count (sfere/list-connections s)))))

      (testing "list-connections with pattern"
        (is (= 2 (count (sfere/list-connections s [:* [:room "lobby"]]))))
        (is (= 2 (count (sfere/list-connections s [:user-1 :*]))))
        (is (= 1 (count (sfere/list-connections s [:user-2 :*]))))
        (is (= 3 (count (sfere/list-connections s [:* :*])))))

      (testing "connection-count"
        (is (= 3 (sfere/connection-count s)))
        (is (= 2 (sfere/connection-count s [:* [:room "lobby"]])))))))

(defn test-store-overwrite [store-fn store-name]
  (testing (str store-name " overwrite behavior")
    (let [s (store-fn)
          key [:user-1 [:room "lobby"]]
          conn1 {:version 1}
          conn2 {:version 2}]
      (sfere/store! s key conn1)
      (is (= conn1 (sfere/connection s key)))
      (sfere/store! s key conn2)
      (is (= conn2 (sfere/connection s key)) "new value overwrites old"))))

(deftest atom-store-protocol-compliance
  (test-store-basic-operations #(atom-store/store) "atom-store")
  (test-store-overwrite #(atom-store/store) "atom-store"))

(deftest caffeine-store-protocol-compliance
  (test-store-basic-operations #(caff-store/store {}) "caffeine-store")
  (test-store-overwrite #(caff-store/store {}) "caffeine-store"))

;; =============================================================================
;; Public API Tests
;; =============================================================================

(deftest store-multimethod-test
  (testing "store multimethod dispatches correctly"
    (let [atom-s (sfere/store {:type :atom})
          caff-s (sfere/store {:type :caffeine})]
      (is (sfere/store? atom-s))
      (is (sfere/store? caff-s))))

  (testing "store multimethod throws on unknown type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown store type"
                          (sfere/store {:type :unknown})))))

(deftest store?-test
  (testing "store? returns true for stores"
    (is (sfere/store? (sfere/store {:type :atom})))
    (is (sfere/store? (sfere/store {:type :caffeine}))))

  (testing "store? returns false for non-stores"
    (is (false? (sfere/store? {})))
    (is (false? (sfere/store? nil)))
    (is (false? (sfere/store? "string")))))

;; =============================================================================
;; Atom Store Specific Tests
;; =============================================================================

(deftest atom-store-with-existing-atom
  (testing "atom store can use existing atom"
    (let [existing (atom {[:user-1 [:room "lobby"]] {:pre-existing true}})
          s (atom-store/store {:atom existing})]
      (is (= {:pre-existing true} (sfere/connection s [:user-1 [:room "lobby"]]))))))

;; =============================================================================
;; Caffeine Store Specific Tests
;; =============================================================================

(deftest caffeine-store-expiration
  (testing "sliding expiry (default) - access resets timer"
    (let [s (caff-store/store {:duration-ms 80 :scheduler true})
          key [:user-1 [:room "lobby"]]
          conn {:id 1}]
      (sfere/store! s key conn)
      ;; Access before expiry, timer resets
      (Thread/sleep 50)
      (is (= conn (sfere/connection s key)) "connection present after partial wait")
      ;; Access again before expiry
      (Thread/sleep 50)
      (is (= conn (sfere/connection s key)) "connection still present - timer was reset")
      ;; Now wait past expiry with no access
      (Thread/sleep 100)
      (is (nil? (sfere/connection s key)) "connection expired after idle")))

  (testing "fixed expiry - access does NOT reset timer"
    (let [s (caff-store/store {:duration-ms 80 :expiry-mode :fixed})
          key [:user-1 [:room "lobby"]]
          conn {:id 1}]
      (sfere/store! s key conn)
      ;; Access before expiry - but timer should NOT reset
      (Thread/sleep 50)
      (is (= conn (sfere/connection s key)) "connection present after partial wait")
      ;; Wait past original expiry time (50 + 50 = 100ms > 80ms)
      (Thread/sleep 50)
      (is (nil? (sfere/connection s key)) "connection expired at fixed time despite access"))))

;; =============================================================================
;; Concurrency Tests
;; =============================================================================

(defn test-concurrent-operations [store-fn store-name]
  (testing (str store-name " concurrent operations")
    (let [s (store-fn)
          n 100
          futures (doall
                   (for [i (range n)]
                     (future
                       (let [key [:user i [:room "lobby"]]]
                         (sfere/store! s key {:id i})
                         (sfere/connection s key)
                         (when (even? i)
                           (sfere/purge! s key))))))]
      (doseq [f futures] @f)
      (is (= 50 (sfere/connection-count s)) "half the connections remain (odd ids)"))))

(deftest atom-store-concurrency
  (test-concurrent-operations #(atom-store/store) "atom-store"))

(deftest caffeine-store-concurrency
  (test-concurrent-operations #(caff-store/store {}) "caffeine-store"))
