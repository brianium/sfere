(ns ascolais.sfere.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sandestin :as s]
            [ascolais.sfere :as sfere]
            [ascolais.sfere.registry :as reg]
            [ascolais.twk :as twk]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-test-dispatch
  "Create a dispatch with sfere registry for testing."
  [store & [opts]]
  (s/create-dispatch
   [(sfere/registry store (or opts {}))]))

(defn mock-sse
  "Create a mock SSE connection."
  [id]
  {:id id :type :mock-sse})

;; =============================================================================
;; Registry Creation Tests
;; =============================================================================

(deftest registry-creation-test
  (testing "registry returns a valid Sandestin registry map"
    (let [store (sfere/store {:type :atom})
          reg (sfere/registry store)]
      (is (map? reg))
      (is (contains? reg ::s/effects))
      (is (contains? reg ::s/interceptors))))

  (testing "registry has with-connection effect"
    (let [store (sfere/store {:type :atom})
          reg (sfere/registry store)]
      (is (contains? (::s/effects reg) :ascolais.sfere/with-connection))))

  (testing "registry has broadcast effect"
    (let [store (sfere/store {:type :atom})
          reg (sfere/registry store)]
      (is (contains? (::s/effects reg) :ascolais.sfere/broadcast)))))

;; =============================================================================
;; with-connection Effect Tests
;; =============================================================================

(deftest with-connection-effect-test
  (testing "with-connection dispatches to stored connection"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse (mock-sse "conn-1")
          key [:user-1 [:room "lobby"]]]
      ;; Store the connection
      (sfere/store! store key sse)

      ;; Create effect handler context
      ;; Sandestin calls handlers as (handler ctx system & args)
      (let [handler (get-in (sfere/registry store) [::s/effects :ascolais.sfere/with-connection ::s/handler])
            ctx {:dispatch (fn [sys-override extra-data fx]
                             (swap! dispatched conj {:sys sys-override :data extra-data :fx fx}))}]
        (handler ctx {} key [::test-effect "hello"])

        (is (= 1 (count @dispatched)))
        (is (= sse (get-in @dispatched [0 :sys :sse])))
        (is (= sse (get-in @dispatched [0 :data ::twk/connection]))))))

  (testing "with-connection is no-op when connection not found"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          key [:user-1 [:room "lobby"]]]
      (let [handler (get-in (sfere/registry store) [::s/effects :ascolais.sfere/with-connection ::s/handler])
            ctx {:dispatch (fn [& args] (swap! dispatched conj args))}]
        (handler ctx {} key [::test-effect])

        (is (empty? @dispatched))))))

;; =============================================================================
;; broadcast Effect Tests
;; =============================================================================

(deftest broadcast-effect-test
  (testing "broadcast dispatches to all matching connections"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse1 (mock-sse "conn-1")
          sse2 (mock-sse "conn-2")
          sse3 (mock-sse "conn-3")]
      ;; Store connections
      (sfere/store! store [:user-1 [:room "lobby"]] sse1)
      (sfere/store! store [:user-2 [:room "lobby"]] sse2)
      (sfere/store! store [:user-3 [:game 42]] sse3)

      ;; Sandestin calls handlers as (handler ctx system & args)
      (let [handler (get-in (sfere/registry store) [::s/effects :ascolais.sfere/broadcast ::s/handler])
            ctx {:dispatch (fn [sys-override extra-data fx]
                             (swap! dispatched conj {:sse (:sse sys-override)}))}]
        (handler ctx {} {:pattern [:* [:room "lobby"]]} [::test-effect])

        (is (= 2 (count @dispatched)))
        (is (= #{sse1 sse2} (set (map :sse @dispatched)))))))

  (testing "broadcast respects exclude set"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse1 (mock-sse "conn-1")
          sse2 (mock-sse "conn-2")]
      (sfere/store! store [:user-1 [:room "lobby"]] sse1)
      (sfere/store! store [:user-2 [:room "lobby"]] sse2)

      (let [handler (get-in (sfere/registry store) [::s/effects :ascolais.sfere/broadcast ::s/handler])
            ctx {:dispatch (fn [sys-override _ _]
                             (swap! dispatched conj {:sse (:sse sys-override)}))}]
        (handler ctx {} {:pattern [:* [:room "lobby"]]
                         :exclude #{[:user-1 [:room "lobby"]]}}
                 [::test-effect])

        (is (= 1 (count @dispatched)))
        (is (= sse2 (:sse (first @dispatched)))))))

  (testing "broadcast respects exclude pattern"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse1 (mock-sse "conn-1")
          sse2 (mock-sse "conn-2")
          sse3 (mock-sse "conn-3")]
      (sfere/store! store [:user-1 [:room "lobby"]] sse1)
      (sfere/store! store [:user-1 [:room "other"]] sse2)
      (sfere/store! store [:user-2 [:room "lobby"]] sse3)

      (let [handler (get-in (sfere/registry store) [::s/effects :ascolais.sfere/broadcast ::s/handler])
            ctx {:dispatch (fn [sys-override _ _]
                             (swap! dispatched conj {:sse (:sse sys-override)}))}]
        ;; Broadcast to all rooms, exclude user-1's connections
        (handler ctx {} {:pattern [:* [:room :*]]
                         :exclude [:user-1 :*]}
                 [::test-effect])

        (is (= 1 (count @dispatched)))
        (is (= sse3 (:sse (first @dispatched)))))))

  (testing "broadcast continues on error"
    (let [store (sfere/store {:type :atom})
          dispatched (atom [])
          sse1 (mock-sse "conn-1")
          sse2 (mock-sse "conn-2")]
      (sfere/store! store [:user-1 [:room "lobby"]] sse1)
      (sfere/store! store [:user-2 [:room "lobby"]] sse2)

      (let [call-count (atom 0)
            handler (get-in (sfere/registry store) [::s/effects :ascolais.sfere/broadcast ::s/handler])
            ctx {:dispatch (fn [sys-override _ _]
                             (swap! call-count inc)
                             (when (= 1 @call-count)
                               (throw (ex-info "Test error" {})))
                             (swap! dispatched conj {:sse (:sse sys-override)}))}]
        (handler ctx {} {:pattern [:* [:room "lobby"]]} [::test-effect])

        ;; Should have attempted both, one succeeded (error was caught and ignored)
        (is (= 1 (count @dispatched)))))))

;; =============================================================================
;; Interceptor Tests
;; =============================================================================

(deftest interceptor-store-test
  (testing "interceptor stores connection when conditions are met"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          reg (sfere/registry store {:id-fn (constantly :test-scope)})
          interceptor (first (::s/interceptors reg))
          before-fn (:before-dispatch interceptor)
          ctx {:system {:sse sse}
               :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}}}]
      (before-fn ctx)

      (is (= sse (sfere/connection store [:test-scope [:room "lobby"]])))))

  (testing "interceptor does not store when no key in response"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          reg (sfere/registry store {:id-fn (constantly :test-scope)})
          interceptor (first (::s/interceptors reg))
          before-fn (:before-dispatch interceptor)
          ctx {:system {:sse sse}
               :dispatch-data {::twk/response {}}}]
      (before-fn ctx)

      (is (= 0 (sfere/connection-count store)))))

  (testing "interceptor does not store when connection already exists"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          existing-sse (mock-sse "existing")
          reg (sfere/registry store {:id-fn (constantly :test-scope)})
          interceptor (first (::s/interceptors reg))
          before-fn (:before-dispatch interceptor)
          ctx {:system {:sse sse}
               :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}
                               ::twk/connection existing-sse}}]
      (before-fn ctx)

      (is (= 0 (sfere/connection-count store)))))

  (testing "interceptor does not store when with-open-sse?"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          reg (sfere/registry store {:id-fn (constantly :test-scope)})
          interceptor (first (::s/interceptors reg))
          before-fn (:before-dispatch interceptor)
          ctx {:system {:sse sse}
               :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}
                               ::twk/with-open-sse? true}}]
      (before-fn ctx)

      (is (= 0 (sfere/connection-count store))))))

(deftest interceptor-purge-test
  (testing "interceptor purges connection on sse-closed"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          reg (sfere/registry store {:id-fn (constantly :test-scope)})
          interceptor (first (::s/interceptors reg))]
      ;; First store a connection
      (sfere/store! store [:test-scope [:room "lobby"]] sse)
      (is (= 1 (sfere/connection-count store)))

      ;; Simulate sse-closed dispatch
      ;; The interceptor checks :actions for [[::twk/sse-closed]]
      (let [before-fn (:before-dispatch interceptor)
            ctx {:system {:sse sse}
                 :actions [[::twk/sse-closed]]
                 :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}}}]
        (before-fn ctx))

      (is (= 0 (sfere/connection-count store)))))

  (testing "interceptor calls on-purge callback"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          purged (atom nil)
          reg (sfere/registry store {:id-fn (constantly :test-scope)
                                     :on-purge (fn [ctx key] (reset! purged {:ctx ctx :key key}))})
          interceptor (first (::s/interceptors reg))]
      ;; Store a connection
      (sfere/store! store [:test-scope [:room "lobby"]] sse)

      ;; Simulate sse-closed dispatch
      (let [before-fn (:before-dispatch interceptor)
            ctx {:system {:sse sse}
                 :actions [[::twk/sse-closed]]
                 :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}}}]
        (before-fn ctx))

      (is (some? @purged))
      (is (= [:test-scope [:room "lobby"]] (:key @purged))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest id-fn-test
  (testing "id-fn derives scope from context"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          reg (sfere/registry store {:id-fn #(get-in % [:request :session :user-id])})
          interceptor (first (::s/interceptors reg))
          before-fn (:before-dispatch interceptor)
          ctx {:system {:sse sse}
               :request {:session {:user-id "user-42"}}
               :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}}}]
      (before-fn ctx)

      (is (= sse (sfere/connection store ["user-42" [:room "lobby"]])))))

  (testing "default id-fn uses ::sfere/default-scope"
    (let [store (sfere/store {:type :atom})
          sse (mock-sse "conn-1")
          reg (sfere/registry store)
          interceptor (first (::s/interceptors reg))
          before-fn (:before-dispatch interceptor)
          ctx {:system {:sse sse}
               :dispatch-data {::twk/response {::sfere/key [:room "lobby"]}}}]
      (before-fn ctx)

      (is (= sse (sfere/connection store [::sfere/default-scope [:room "lobby"]]))))))
