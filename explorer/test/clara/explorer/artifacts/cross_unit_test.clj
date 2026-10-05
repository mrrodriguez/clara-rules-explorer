(ns clara.explorer.artifacts.cross-unit-test
  "Pins the shared cross-unit readings: entry points (a descendant insertion
  supplies its ancestors; retraction does not produce) and unit edges."
  (:require [clara.explorer.artifacts.cross-unit :as cross-unit]
            [clojure.test :refer [deftest is testing]]))

(def ^:private index
  {:rules
   {"p/consumer" {:name "p/consumer" :ns "p"
                  :lhs-types [":x/parent" ":x/retracted"]
                  :insert-types [] :retract-types [] :unit "a"}
    "p/descendant-producer" {:name "p/descendant-producer" :ns "p"
                             :lhs-types []
                             :insert-types [":x/child"] :retract-types [] :unit "b"}
    "p/retract-only" {:name "p/retract-only" :ns "p"
                      :lhs-types []
                      :insert-types [] :retract-types [":x/retracted"] :unit "b"}}
   :queries {}})

(def ^:private fact-types
  {":x/parent" {:name ":x/parent" :ns nil :ancestors []}
   ":x/child" {:name ":x/child" :ns nil :ancestors [":x/parent"]}
   ":x/retracted" {:name ":x/retracted" :ns nil :ancestors []}})

(def ^:private dep-graph
  {"p/consumer" {:upstream #{"p/descendant-producer"}}})

(deftest entry-points-descendant-vs-retract-test
  (testing "a consumed type supplied by an inserted descendant is not an entry point"
    (is (not (contains? (cross-unit/all-entry-points index fact-types) ":x/parent"))))
  (testing "a consumed type only retracted is an entry point"
    (is (contains? (cross-unit/all-entry-points index fact-types) ":x/retracted")))
  (testing "entry points group by consuming unit with a consumer count"
    (is (= {"a" {":x/retracted" 1}}
           (cross-unit/entry-points index fact-types)))))

(deftest unit-edges-cross-unit-test
  (testing "a cross-unit producer->consumer edge carries :via and :rules"
    (is (= {["b" "a"] {:via #{":x/parent"} :rules 1}}
           (cross-unit/unit-edges index dep-graph fact-types)))))
