(ns clara.server.tools.graph.serialize-test
  (:require [clara.server.tools.graph.serialize :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(defrecord TestRecord [a b])

(deftest test-prune-fns
  (testing "Primitive types remain unchanged"
    (is (= 1 (s/prune-fns 1)))
    (is (= "foo" (s/prune-fns "foo")))
    (is (= nil (s/prune-fns nil)))
    (is (= true (s/prune-fns true))))

  (testing "Recursive map pruning"
    (let [f (fn [] 1)
          pruned (s/prune-fns {:a 1 :b f})]
      (is (map? pruned))
      (is (= 1 (:a pruned)))
      (is (string? (:b pruned)))
      (is (str/starts-with? (:b pruned) "clara.server.tools.graph.serialize_test$fn"))))

  (testing "Recursive vector pruning"
    (let [f (fn [] 1)
          pruned (s/prune-fns [1 f])]
      (is (vector? pruned))
      (is (= 1 (first pruned)))
      (is (string? (second pruned)))))

  (testing "Recursive set pruning"
    (let [f (fn [] 1)
          pruned (s/prune-fns #{1 f})]
      (is (set? pruned))
      (is (contains? pruned 1))
      (is (some string? pruned))))

  (testing "Keywords and symbols"
    ;; Keywords are IFn, but we usually want to keep them as keywords for JSON libs to handle
    ;; or at least not stringify the whole map because it contains keywords.
    (is (= :foo (s/prune-fns :foo)))
    (is (= 'bar (s/prune-fns 'bar))))

  (testing "Nested structures"
    (let [f (fn [] 1)
          data {:a [1 {:b f}]}
          pruned (s/prune-fns data)]
      (is (= 1 (get-in pruned [:a 0])))
      (is (string? (get-in pruned [:a 1 :b])))))

  (testing "Preserves sorted collection types"
    (let [data (sorted-map :b (fn [] 2) :a 1)
          pruned (s/prune-fns data)]
      (is (instance? clojure.lang.PersistentTreeMap pruned))
      (is (= [:a :b] (keys pruned))))
    (let [data (sorted-set 2 1)
          pruned (s/prune-fns data)]
      (is (instance? clojure.lang.PersistentTreeSet pruned))
      (is (= [1 2] (seq pruned)))))

  (testing "Handles Records by converting to maps"
    (let [f (fn [] 1)
          data (->TestRecord 1 f)
          pruned (s/prune-fns data)]
      (is (map? pruned))
      (is (not (record? pruned)))
      (is (= 1 (:a pruned)))
      (is (string? (:b pruned))))))

(deftest test-serialize-condition
  (testing "Basic condition serialization"
    (let [condition {:type :some-type
                     :constraints '[(= ?a 1)]}
          serialized (s/serialize-condition condition)]
      (is (= :some-type (:type serialized)))
      (is (string? (:constraints serialized)))
      (is (str/includes? (:constraints serialized) "(= ?a 1)"))))

  (testing "Nested condition serialization (OR/AND)"
    (let [condition [:or 
                     {:type :type-a :constraints '[(= ?a 1)]}
                     {:type :type-b :constraints '[(= ?b 2)]}]
          serialized (s/serialize-condition condition)]
      (is (= :or (first serialized)))
      (is (string? (:constraints (second serialized))))
      (is (string? (:constraints (nth serialized 2))))))

  (testing "Accumulator condition serialization"
    (let [condition {:accumulator '(acc/all)
                     :from {:type :some-type :constraints '[(= ?a 1)]}}
          serialized (s/serialize-condition condition)]
      (is (= '(acc/all) (:accumulator serialized)))
      (is (string? (get-in serialized [:from :constraints]))))))

(deftest test-serialize-lhs
  (testing "Serializing a full LHS vector"
    (let [lhs [{:type :type-a :constraints '[(= ?a 1)]}
               {:type :type-b :constraints '[(= ?b 2)]}]
          serialized (s/serialize-lhs lhs)]
      (is (= 2 (count serialized)))
      (is (string? (:constraints (first serialized))))
      (is (string? (:constraints (second serialized)))))))
