(ns clara.server.tools.graph.annotations-test
  "Tests for rule-name normalization, `production-annotation`, and
   `props-layer`.  Merge semantics, callsite identity, reporting, validation,
   and derivation are covered in annotations_merge_test.clj."
  (:require [clara.rules :as r]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clojure.test :refer [deftest is testing]]))

(deftest test-normalize-rule-name
  (testing "symbol → string"
    (is (= "my.ns/rule-a" (ann/normalize-rule-name 'my.ns/rule-a))))
  (testing "string is identity"
    (is (= "my.ns/rule-a" (ann/normalize-rule-name "my.ns/rule-a"))))
  (testing "keyword → string"
    (is (= "my.ns/rule-a" (ann/normalize-rule-name :my.ns/rule-a)))))

(deftest test-get-annotation
  (let [annotations {"my.ns/rule-a" {:clara-rules/insert-types [:TypeA]}}]
    (testing "string key lookup"
      (is (= {:clara-rules/insert-types [:TypeA]}
             (ann/get-annotation annotations "my.ns/rule-a"))))
    (testing "symbol key lookup is normalized"
      (is (= {:clara-rules/insert-types [:TypeA]}
             (ann/get-annotation annotations 'my.ns/rule-a))))
    (testing "missing key returns nil"
      (is (nil? (ann/get-annotation annotations "nonexistent"))))))

(deftest test-production-annotation
  (let [production {:ns-name 'user :name "user/my-rule" :props {}}]
    (testing "unqualified keys out of a bare annotations map"
      (is (= {:insert-types [:TypeA] :notes "n"}
             (ann/production-annotation
              {"user/my-rule" #:clara-rules{:insert-types [:TypeA]
                                            :notes "n"
                                            :merge-props {:insert-types :replace}
                                            :internal-key :not-read}}
              production))
          "merge-props is a consumed directive; unknown keys are not read"))
    (testing "non-class types (keywords/symbols) pass through"
      (is (= [:KeywordA 'SymbolB]
             (:insert-types (ann/production-annotation
                             {"user/my-rule" #:clara-rules{:insert-types [:KeywordA 'SymbolB]}}
                             production)))))
    (testing "symbol types resolve against the production's namespace"
      (is (= [String]
             (:insert-types (ann/production-annotation
                             {"user/my-rule" #:clara-rules{:insert-types [`String]}}
                             production)))))
    (testing "absent type keys stay absent (no empty vectors)"
      (is (= {}
             (ann/production-annotation {"user/my-rule" {}} production))))
    (testing "rule-name lookup is normalized"
      (is (= [:TypeA]
             (:insert-types (ann/production-annotation
                             {'user/my-rule #:clara-rules{:insert-types [:TypeA]}}
                             production)))))))

(deftest test-props-layer
  (let [session (r/mk-session 'clara.server.tools.graph.rules.loan-app-rules)
        layer (ann/props-layer session)
        outcome-class (Class/forName "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome")]
    (testing "layer identity"
      (is (= :props (:id layer)))
      (is (= :rulebase (:source layer))))
    (testing "rule :props are read off the compiled productions"
      (is (= [outcome-class]
             (get-in layer
                     [:annotations
                      "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                      :clara-rules/insert-types]))))
    (testing "composes at any position in the fold, carrying props through (§5.5)"
      (let [merged (ann/merge-layers [layer])
            rule (get (ann/annotations merged)
                      "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")]
        (is (= [outcome-class]
               (:clara-rules/insert-types rule)))))))
