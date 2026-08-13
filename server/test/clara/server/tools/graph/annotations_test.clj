(ns clara.server.tools.graph.annotations-test
  "Tests for rule-name normalization, `production-annotation`,
   `annotations-delta`, and `props-layer`.  Merge semantics, callsite
   identity, reporting, validation, and derivation are covered in
   annotations_merge_test.clj."
  (:require [clara.rules :as r]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.serialize :as serialize]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

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
             (ann/production-annotation {"user/my-rule" {}} production))))))

(deftest test-annotations-delta
  (testing "new type added over base"
    (is (= {"rule-a" {:clara-rules/insert-types ["TypeC"]}}
           (ann/annotations-delta
            {"rule-a" {:clara-rules/insert-types ["TypeA" "TypeB"]}}
            {"rule-a" {:clara-rules/insert-types ["TypeA" "TypeB" "TypeC"]}}))
        "TypeC newly added — present in delta")
    (is (= {"rule-a" {:clara-rules/insert-types ["TypeB"]}}
           (ann/annotations-delta
            {"rule-a" {:clara-rules/insert-types ["TypeA"]}}
            {"rule-a" {:clara-rules/insert-types ["TypeA" "TypeB"]}}))
        "only TypeB is new — TypeA already in base"))

  (testing "no change returns nil"
    (is (nil? (ann/annotations-delta
               {"rule-a" {:clara-rules/insert-types ["TypeA"]}}
               {"rule-a" {:clara-rules/insert-types ["TypeA"]}}))
        "identical maps produce nil")
    (is (nil? (ann/annotations-delta {} {}))
        "empty base and empty extra produces nil"))

  (testing "new rule in extra"
    (is (= {"rule-b" {:clara-rules/insert-types ["TypeB"]}}
           (ann/annotations-delta
            {}
            {"rule-b" {:clara-rules/insert-types ["TypeB"]}}))
        "rule absent from base, present in extra — entire entry is the delta"))

  (testing "type normalization — Class vs symbol resolving to same Class"
    ;; A symbol that resolves to a Class converges with the Class itself
    ;; under resolve-type (both → .getName).
    (let [class-type java.lang.String
          sym-type  `String]  ;; resolves to String in production ns
      (is (= (serialize/resolve-type 'clojure.core sym-type)
             (serialize/resolve-type 'clojure.core class-type))
          "symbol resolving to Class → same .getName as the Class")))

  (testing "no-output-types tombstone"
    (is (= {"rule-a" {:clara-rules/insert-types ["TypeA"]
                      :clara-rules/no-output-types nil}}
           (ann/annotations-delta
            {"rule-a" {:clara-rules/no-output-types true}}
            {"rule-a" {:clara-rules/insert-types ["TypeA"]}}))
        "insert added over a no-output-types rule — tombstone nil erases the claim")
    (is (nil? (ann/annotations-delta
               {"rule-a" {:clara-rules/no-output-types true}}
               {"rule-a" {:clara-rules/no-output-types true}}))
        "no-output-types unchanged — nothing to delta"))

  (testing "retract types"
    (is (= {"rule-a" {:clara-rules/retract-types ["TypeB"]}}
           (ann/annotations-delta
            {"rule-a" {:clara-rules/retract-types ["TypeA"]}}
            {"rule-a" {:clara-rules/retract-types ["TypeA" "TypeB"]}}))
        "retract dimension works same as insert"))

  (testing "detection key with fact-instance-derived-types"
    (let [base {"rule-a" {:clara-rules/insert-types ["TypeA"]
                          :clara-rules/dynamic-insert-types-detected
                          {:callsites [{:callsite-id :cs1
                                        :resolved-types ["TypeA"]}]}}}
          extra {"rule-a" {:clara-rules/insert-types ["TypeA" "TypeB"]
                           :clara-rules/dynamic-insert-types-detected
                           {:fact-instance-derived-types ["TypeB"]}}}]
      (is (= {"rule-a" {:clara-rules/insert-types ["TypeB"]
                        :clara-rules/dynamic-insert-types-detected
                        {:fact-instance-derived-types ["TypeB"]}}}
             (ann/annotations-delta base extra))
          "new insert type + derived detection key carried through")))

  (testing "detection key with no fact-instance-derived-types is not emitted"
    (let [base {"rule-a" {:clara-rules/insert-types ["TypeA"]}}
          extra {"rule-a" {:clara-rules/insert-types ["TypeA" "TypeB"]
                           :clara-rules/dynamic-insert-types-detected {}}}]
      (is (= {"rule-a" {:clara-rules/insert-types ["TypeB"]}}
             (ann/annotations-delta base extra))
          "empty detection map — no derived types → detection key omitted")))

  (testing "mixed dimensions — insert and retract"
    (is (= {"rule-a" {:clara-rules/insert-types ["TypeNew"]
                      :clara-rules/retract-types ["RetractNew"]}}
           (ann/annotations-delta
            {"rule-a" {:clara-rules/insert-types ["TypeOld"]
                       :clara-rules/retract-types ["RetractOld"]}}
            {"rule-a" {:clara-rules/insert-types ["TypeOld" "TypeNew"]
                       :clara-rules/retract-types ["RetractOld" "RetractNew"]}}))
        "both dimensions carry only new types"))

  (testing "normalized key input (symbol keys)"
    (is (= {"my.ns/rule-a" {:clara-rules/insert-types ["TypeB"]}}
           (ann/annotations-delta
            {'my.ns/rule-a {:clara-rules/insert-types ["TypeA"]}}
            {:my.ns/rule-a {:clara-rules/insert-types ["TypeA" "TypeB"]}}))
        "symbol and keyword keys are normalized to strings"))

  (testing "multiple rules — only changed rules in delta"
    (is (= {"rule-b" {:clara-rules/insert-types ["TypeB"]}}
           (ann/annotations-delta
            {"rule-a" {:clara-rules/insert-types ["TypeA"]}
             "rule-b" {:clara-rules/insert-types ["TypeOld"]}}
            {"rule-a" {:clara-rules/insert-types ["TypeA"]}
             "rule-b" {:clara-rules/insert-types ["TypeOld" "TypeB"]}}))
        "only rule-b changed — rule-a (no change) absent from delta"))

  (testing "extra with no enrichable dimensions returns nil"
    (is (nil? (ann/annotations-delta
               {"rule-a" {:clara-rules/insert-types ["TypeA"]}}
               {"rule-a" {:clara-rules/notes "some note"}}))
        "notes is not an enrichable dimension — nothing to delta")))

(deftest test-props-layer
  (let [session (r/mk-session 'clara.server.tools.graph.rules.loan-app-rules)
        layer (ann.merge/props-layer session)
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
      (let [merged (ann.merge/merge-layers [layer])
            rule (get (ann.merge/annotations merged)
                      "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")]
        (is (= [outcome-class]
               (:clara-rules/insert-types rule)))))))
