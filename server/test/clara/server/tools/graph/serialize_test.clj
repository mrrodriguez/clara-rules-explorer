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

(deftest test-resolve-type-kind-explicit
  (testing "Classes serialize as .getName (unchanged)"
    (is (= "clara.server.tools.graph.serialize_test.TestRecord"
           (s/resolve-type nil TestRecord))))

  (testing "Keywords keep their colon"
    (is (= ":my.ns/child" (s/resolve-type nil :my.ns/child)))
    (is (= ":extract-doc-meta" (s/resolve-type nil :extract-doc-meta))))

  (testing "Strings are quoted (pr-str)"
    (is (= "\"foo\"" (s/resolve-type nil "foo"))))

  (testing "Unresolved symbols are wrapped in symbol[...]"
    (is (= "symbol[my.ns/foo]" (s/resolve-type nil 'my.ns/foo)))
    (is (= "symbol[extract-doc-meta]" (s/resolve-type nil 'extract-doc-meta))))

  (testing "Symbols resolving to a class via the ns-name serialize as the class name"
    (is (= "clara.server.tools.graph.rules.loan_app_facts.Application"
           (s/resolve-type 'clara.server.tools.graph.rules.loan-doc-rules
                           'clara.server.tools.graph.rules.loan_app_facts.Application))))

  (testing "Vectors / tuples serialize via pr-str (kind-explicit elements)"
    (is (= "[:a 1]" (s/resolve-type nil [:a 1])))
    (is (= "[:loan/status \"verified\"]" (s/resolve-type nil [:loan/status "verified"]))))

  (testing "Distinct kinds never collide"
    (let [kw (s/resolve-type nil :foo)
          s1 (s/resolve-type nil "foo")
          sym (s/resolve-type nil 'foo)]
      (is (not= kw s1))
      (is (not= kw sym))
      (is (not= s1 sym)))))

(deftest test-route-id
  (testing "Deterministic per name"
    (is (= (s/route-id "my.ns.MarkerRecord") (s/route-id "my.ns.MarkerRecord")))
    (is (= (s/route-id ":my.ns/child") (s/route-id ":my.ns/child"))))

  (testing "Uniform slug + 8-char base36 hash suffix for every kind"
    (doseq [name ["my.ns.MarkerRecord"
                  ":my.ns/child"
                  "\"foo\""
                  "[:loan/status \"verified\"]"
                  "my.ns/verify-docs?"]]
      (is (re-matches #"[A-Za-z0-9.-]+-[a-z0-9]{8}" (s/route-id name))
          (str "route-id of " name " must be slug + 8 base36 chars"))))

  (testing "Special characters are slugged away"
    (is (str/starts-with? (s/route-id "my.ns/verify-docs?") "my.ns-verify-docs-"))
    (is (str/starts-with? (s/route-id "[:loan/status \"verified\"]") "loan-status-verified-")))

  (testing "60-char slug truncation still distinguishes via the hash"
    (let [long-a (apply str (repeat 80 \a))
          long-b (str long-a "b")]
      (is (= 69 (count (s/route-id long-a))))
      (is (not= (s/route-id long-a) (s/route-id long-b))))))

(deftest test-serialize-type-ref
  (testing "Known flag reflects membership in the known set"
    (let [known #{":my.ns/child"}
          ref (s/serialize-type-ref known nil :my.ns/child)
          ghost (s/serialize-type-ref known nil :my.ns/other)]
      (is (= {:name ":my.ns/child"
              :id (s/route-id ":my.ns/child")
              :known true}
             ref))
      (is (false? (:known ghost)))))

  (testing "Kind-explicit name with nil ns-name"
    (is (= "symbol[my.ns/foo]" (:name (s/serialize-type-ref #{} nil 'my.ns/foo))))
    (is (= "\"foo\"" (:name (s/serialize-type-ref #{} nil "foo"))))
    (is (= "[:a 1]" (:name (s/serialize-type-ref #{} nil [:a 1]))))))

(deftest test-serialize-condition
  (testing "Basic condition serialization: :type becomes a TypeReference"
    (let [condition {:type :some-type
                     :constraints '[(= ?a 1)]}
          serialized (s/serialize-condition condition nil #{})]
      (is (= ":some-type" (get-in serialized [:type :name])))
      (is (string? (get-in serialized [:type :id])))
      (is (false? (get-in serialized [:type :known])))
      (is (string? (:constraints serialized)))
      (is (str/includes? (:constraints serialized) "(= ?a 1)"))))

  (testing "Nested condition serialization (OR/AND)"
    (let [condition [:or
                     {:type :type-a :constraints '[(= ?a 1)]}
                     {:type :type-b :constraints '[(= ?b 2)]}]
          serialized (s/serialize-condition condition nil #{})]
      (is (= :or (first serialized)))
      (is (= ":type-a" (get-in (second serialized) [:type :name])))
      (is (string? (:constraints (second serialized))))
      (is (= ":type-b" (get-in (nth serialized 2) [:type :name])))
      (is (string? (:constraints (nth serialized 2))))))

  (testing "Accumulator condition serialization"
    (let [condition {:accumulator '(acc/all)
                     :from {:type :some-type :constraints '[(= ?a 1)]}}
          serialized (s/serialize-condition condition nil #{})]
      (is (= '(acc/all) (:accumulator serialized)))
      (is (= ":some-type" (get-in serialized [:from :type :name])))
      (is (string? (get-in serialized [:from :constraints]))))))

(deftest test-serialize-lhs
  (testing "Serializing a full LHS vector"
    (let [lhs [{:type :type-a :constraints '[(= ?a 1)]}
               {:type :type-b :constraints '[(= ?b 2)]}]
          serialized (s/serialize-lhs lhs nil #{})]
      (is (= 2 (count serialized)))
      (is (= ":type-a" (get-in (first serialized) [:type :name])))
      (is (string? (:constraints (first serialized))))
      (is (= ":type-b" (get-in (second serialized) [:type :name])))
      (is (string? (:constraints (second serialized)))))))
