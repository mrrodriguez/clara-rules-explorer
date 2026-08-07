(ns clara.server.tools.graph.serialize-test
  (:require [clara.server.tools.graph.serialize :as s]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.rules.loan-app-rules]
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

(deftest test-serialize-match-per-ns-context
  (testing "Each end of a match pair serializes in its own production's ns context"
    (let [producer-ns 'clara.server.tools.graph.serialize-test
          consumer-ns 'clara.server.tools.graph.rules.loan-app-rules
          match (first (s/serialize-match {:raw-pairs [{:producer-type 'TestRecord
                                                        :consumer-type 'clojure.lang.IPersistentMap}]
                                           :known-set #{}
                                           :producer-ns producer-ns
                                           :consumer-ns consumer-ns}))]
      (is (= "clara.server.tools.graph.serialize_test.TestRecord"
             (get-in match [:producer-type :name]))
          "unqualified symbol resolves to the class in the producer's ns")
      (is (= "clojure.lang.IPersistentMap"
             (get-in match [:consumer-type :name])))))

  (testing "The same symbol degrades to symbol[...] under a ns where it does not resolve (per-ns divergence prevented)"
    (let [match (first (s/serialize-match {:raw-pairs [{:producer-type 'TestRecord
                                                        :consumer-type 'clojure.lang.IPersistentMap}]
                                           :known-set #{}
                                           :producer-ns 'clara.server.tools.graph.rules.loan-app-rules
                                           :consumer-ns 'clara.server.tools.graph.rules.loan-app-rules}))]
      (is (= "symbol[TestRecord]" (get-in match [:producer-type :name]))))))

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

  (testing "Symbols resolving to a var serialize as the fully-qualified var symbol"
    ;; Regression: the ns-name parameter used to shadow clojure.core/ns-name,
    ;; NPE-ing the var branch (Cannot invoke getName because "x" is null).
    (is (= "clojure.string/join"
           (s/resolve-type 'clojure.string 'join)))
    (is (= "clojure.string/join"
           (s/resolve-type 'clojure.string 'clojure.string/join)))
    (is (= "clojure.string/join"
           (s/resolve-type 'clara.server.tools.graph.rules.loan-doc-rules
                           'clojure.string/join))))

  (testing "An unresolvable ns never NPEs — degrades to symbol[...]"
    (is (= "symbol[Foo]" (s/resolve-type 'diverge.a 'Foo))))

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
      (is (not= (s/route-id long-a) (s/route-id long-b)))))

  (testing "nil returns nil — no route-id for nil (upstream should filter)"
    (is (nil? (s/route-id nil)))))

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

  (testing "nil type returns nil — upstream should filter before serialization"
    (is (nil? (s/serialize-type-ref #{} nil nil))))

  (testing "Kind-explicit name with nil ns-name"
    (is (= "symbol[my.ns/foo]" (:name (s/serialize-type-ref #{} nil 'my.ns/foo))))
    (is (= "\"foo\"" (:name (s/serialize-type-ref #{} nil "foo"))))
    (is (= "[:a 1]" (:name (s/serialize-type-ref #{} nil [:a 1]))))))

(deftest test-resolve-type-map-kind
  (testing "Map literals serialize kind-explicitly via pr-str"
    (is (= "{:a 1, :b 2}" (s/resolve-type nil {:a 1 :b 2})))
    (is (= "{:my-type :tuple}" (s/resolve-type nil {:my-type :tuple})))
    (is (= "{:a \"b\"}" (s/resolve-type nil {:a "b"}))
        "string values keep their quotes (pr-str, unlike str)"))

  (testing "Map fact types are distinct from tuples and strings"
    (is (not= (s/resolve-type nil {:a 1}) (s/resolve-type nil [:a 1])))
    (is (not= (s/resolve-type nil {:a 1}) (s/resolve-type nil "{:a 1}")))))

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

;; ---------------------------------------------------------------------------
;; serialize-lhs-form — condition-type dispatch
;; ---------------------------------------------------------------------------

(defn- lhs-form-contains?
  "True when `lhs-form-str` contains `kw` as a keyword operator (preceded by
   `:` or whitespace + `:`, not as part of a larger word)."
  [lhs-form-str kw]
  (boolean (re-find (re-pattern (str "(?:^|\\s|\\[):" (name kw) "\\b"))
                    lhs-form-str)))

(deftest test-serialize-lhs-form--leaf-fact
  (testing "Plain :fact condition renders correctly"
    (let [lhs [{:type 'my.ns/Foo
                :fact-binding '?f
                :constraints '[(= ?f 1)]}]
          form (s/serialize-lhs-form lhs)]
      (is (str/includes? form "my.ns/Foo")
          "form must contain the fact type")
      (is (str/includes? form "?f")
          "form must contain the fact binding")
      (is (str/includes? form "(= ?f 1)")
          "form must contain constraints"))))

(deftest test-serialize-lhs-form--test
  (testing ":test condition renders as a leaf"
    (let [lhs [{:constraints '[(> ?n 0)]}]
          form (s/serialize-lhs-form lhs)]
      (is (str/includes? form ">")
          "form must contain the test constraint"))))

(deftest test-serialize-lhs-form--not
  (testing ":not group preserves the operator and nested conditions"
    (let [lhs [[:not {:type 'my.ns/Order
                      :fact-binding '?o
                      :constraints '[(= ?o ?order-id)]}]]
          form (s/serialize-lhs-form lhs)]
      (is (lhs-form-contains? form :not)
          ":not operator must appear in the rendered form")
      (is (str/includes? form "my.ns/Order")
          "nested fact type must survive the group"))))

(deftest test-serialize-lhs-form--or
  (testing ":or group preserves the operator and all branches"
    (let [lhs [[:or {:type 'my.ns/WebOrder
                     :fact-binding '?o
                     :constraints '[(= ?o ?id)]}
                {:type 'my.ns/PhoneOrder
                 :fact-binding '?o
                 :constraints '[(= ?o ?id)]}]]
          form (s/serialize-lhs-form lhs)]
      (is (lhs-form-contains? form :or)
          ":or operator must appear in the rendered form")
      (is (str/includes? form "my.ns/WebOrder")
          "first branch type must survive")
      (is (str/includes? form "my.ns/PhoneOrder")
          "second branch type must survive"))))

(deftest test-serialize-lhs-form--and
  (testing ":and group preserves the operator"
    (let [lhs [[:and {:type 'my.ns/A
                      :fact-binding '?a
                      :constraints '[(= ?a ?id)]}
                {:type 'my.ns/B
                 :fact-binding '?b
                 :constraints '[(= ?b ?id)]}]]
          form (s/serialize-lhs-form lhs)]
      (is (lhs-form-contains? form :and)
          ":and operator must appear in the rendered form")
      (is (str/includes? form "my.ns/A"))
      (is (str/includes? form "my.ns/B")))))

(deftest test-serialize-lhs-form--exists
  (testing ":exists group preserves the operator"
    (let [lhs [[:exists {:type 'my.ns/Child
                         :fact-binding '?c
                         :constraints '[(= ?c ?id)]}]]
          form (s/serialize-lhs-form lhs)]
      (is (lhs-form-contains? form :exists)
          ":exists operator must appear in the rendered form")
      (is (str/includes? form "my.ns/Child")))))

(deftest test-serialize-lhs-form--single-group-lhs
  (testing "Rule whose entire LHS is one boolean group renders correctly"
    (let [lhs [[:or {:type 'my.ns/A :fact-binding '?a}
                {:type 'my.ns/B :fact-binding '?b}]]
          form (s/serialize-lhs-form lhs)]
      (is (lhs-form-contains? form :or)
          ":or operator must appear when LHS is a single group")
      (is (str/includes? form "my.ns/A"))
      (is (str/includes? form "my.ns/B"))
      (is (not (str/includes? form "[]:"))
          "form must not render as empty brackets"))))

(deftest test-serialize-lhs-form--accumulator-from-group
  (testing "Group nested inside an accumulator's :from renders recursively"
    (let [lhs [{:accumulator '(acc/all)
                :result-binding '?result
                :from [:not {:type 'my.ns/Done
                             :fact-binding '?d}]}]
          form (s/serialize-lhs-form lhs)]
      (is (str/includes? form "acc/all")
          "accumulator fn must appear")
      (is (lhs-form-contains? form :not)
          ":not inside :from must be preserved")
      (is (str/includes? form "my.ns/Done")
          "nested fact type inside :from must survive"))))

(deftest test-serialize-lhs-form--fact-type-invariant
  (testing "Every fact type in extract-lhs-fact-types appears in serialize-lhs-form output"
    (let [lhs [{:type 'my.ns/X :fact-binding '?x :constraints '[(= ?x 1)]}
               [:not {:type 'my.ns/Y :fact-binding '?y}]
               {:accumulator '(acc/min)
                :result-binding '?m
                :from {:type 'my.ns/Z :fact-binding '?z}}]
          lhs-form-str (s/serialize-lhs-form lhs)
          fact-types (core/extract-lhs-fact-types lhs)]
      (is (seq fact-types) "LHS must yield at least one fact type")
      (doseq [ft fact-types]
        (is (str/includes? lhs-form-str (str ft))
            (str "fact type " ft " must appear in serialize-lhs-form output"))))))
