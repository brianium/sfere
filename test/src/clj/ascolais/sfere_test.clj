(ns ascolais.sfere-test
  (:require [clojure.test :refer [deftest is testing]]
            [ascolais.sfere :as sfere]))

(deftest greet-test
  (testing "greet returns a greeting message"
    (is (= "Hello, World!" (sfere/greet "World")))
    (is (= "Hello, Clojure!" (sfere/greet "Clojure")))))
