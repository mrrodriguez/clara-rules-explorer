(ns clara.server.tools.graph.analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [clara.server.tools.graph.analyze :as analyze]))

(deftest test-static-analysis-rules
  (testing "Traces RHS function calls and constructors programmatically"
    (let [annotations (analyze/analyze-rules ["test/clara/server/tools/graph/rules/loan_doc_rules.clj"])]
      ;; Rule 1: collect-app-id-card-given-docs
      (let [ann-1 (get annotations 'clara.server.tools.graph.rules.loan-doc-rules/collect-app-id-card-given-docs)]
        (is (some? ann-1) "Should find collect-app-id-card-given-docs")
        (is (= ['clara.server.tools.graph.rules.loan_doc_rules.AllIdCardGivenDocuments]
               (:clara-rules/insert-types ann-1))
            "Should identify the locally defined AllIdCardGivenDocuments record type"))

      ;; Rule 2: collect-app-given-docs
      (let [ann-2 (get annotations 'clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs)]
        (is (some? ann-2))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments]
               (:clara-rules/insert-types ann-2))
            "Should identify AllGivenDocuments record type from foreign facts ns"))

      ;; Rule 3: collect-app-req-docs
      (let [ann-3 (get annotations 'clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs)]
        (is (some? ann-3))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments]
               (:clara-rules/insert-types ann-3))
            "Should identify AllRequiredDocuments record type"))

      ;; Rule 4: collect-app-doc-check-input
      (let [ann-4 (get annotations 'clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input)]
        (is (some? ann-4))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheckInput]
               (:clara-rules/insert-types ann-4))
            "Should identify DocumentCheckInput record type"))

      ;; Rule 5: app-has-all-required-docs
      (let [ann-5 (get annotations 'clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs)]
        (is (some? ann-5))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-5))
            "Should identify DocumentCheck record type"))

      ;; Rule 6: collect-all-missing-required-docs
      (let [ann-6 (get annotations 'clara.server.tools.graph.rules.loan-doc-rules/collect-all-missing-required-docs)]
        (is (nil? ann-6) "Should not list rules that do not insert/retract facts")))))

(deftest test-static-analysis-edge-cases
  (testing "Traces different Clojure and Java constructor patterns and helpers"
    (let [annotations (analyze/analyze-rules ["test/clara/server/tools/graph/rules/analyze_test_rules.clj"])]
      ;; Rule A: standard Clojure record constructor
      (let [ann-a (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-record-constructor)]
        (is (some? ann-a))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord]
               (:clara-rules/insert-types ann-a))
            "Should identify the locally defined LocalDummyRecord class"))

      ;; Rule B: Java constructor style (Class. ...)
      (let [ann-b (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-java-constructor-dot)]
        (is (some? ann-b))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-b))
            "Should identify DocumentCheck from Class. constructor usage"))

      ;; Rule C: Java constructor style (new Class ...)
      (let [ann-c (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-java-constructor-new)]
        (is (some? ann-c))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-c))
            "Should identify DocumentCheck from new Class constructor usage"))

      ;; Rule D: Tracing through helper function
      (let [ann-d (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-nested-helper-call)]
        (is (some? ann-d))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-d))
            "Should trace constructors called by transitively invoked functions"))

      ;; Rule E: Map facts with metadata (highly dynamic)
      (let [ann-e (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-metadata-map-fact)]
        (is (some? ann-e))
        (is (empty? (:clara-rules/insert-types ann-e))
            "Should be an empty insert-types vector because map-with-metadata is too dynamic to resolve statically"))

      ;; Rule B2: Fully-qualified Class. constructor
      (let [ann-b2 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-java-constructor-fq-dot)]
        (is (some? ann-b2))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-b2))
            "Should identify DocumentCheck from fq Class. constructor usage"))

      ;; Rule C2: Short name new Class constructor
      (let [ann-c2 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-java-constructor-short-new)]
        (is (some? ann-c2))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-c2))
            "Should identify DocumentCheck from new short-Class constructor usage"))

      ;; Rule F1: Modern constructor syntax (Class/new) via short name
      (let [ann-f1 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-java-constructor-short-modern)]
        (is (some? ann-f1))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-f1))
            "Should identify DocumentCheck from short Class/new constructor usage"))

      ;; Rule F2: Modern constructor syntax (Class/new) via fully-qualified name
      (let [ann-f2 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-java-constructor-fq-modern)]
        (is (some? ann-f2))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-f2))
            "Should identify DocumentCheck from fq Class/new constructor usage"))

      ;; Rule G: Tracing helper function calling Java constructor
      (let [ann-g (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-nested-java-helper-call)]
        (is (some? ann-g))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-g))
            "Should trace constructors called by helper functions that instantiate Java classes"))

      ;; Rule H1: insert-all! with collection of records
      (let [ann-h1 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-insert-all-collection)]
        (is (some? ann-h1))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord]
               (:clara-rules/insert-types ann-h1))
            "Should identify LocalDummyRecord from insert-all! usage"))

      ;; Rule H2: insert! with varargs
      (let [ann-h2 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-insert-varargs)]
        (is (some? ann-h2))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord]
               (:clara-rules/insert-types ann-h2))
            "Should identify LocalDummyRecord from insert! varargs usage"))

      ;; Rule H3: retract! with varargs
      (let [ann-h3 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-retract-varargs)]
        (is (some? ann-h3))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord]
               (:clara-rules/retract-types ann-h3))
            "Should identify LocalDummyRecord from retract! varargs usage"))

      ;; Rule H4: RHS with complex nested doseq loop
      (let [ann-h4 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-complex-rhs-nested)]
        (is (some? ann-h4))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord]
               (:clara-rules/insert-types ann-h4))
            "Should trace through complex nested RHS doseq constructs"))

      ;; Rule H5: RHS calls helper function which does the insert
      (let [ann-h5 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-helper-does-insert)]
        (is (some? ann-h5))
        (is (= ['clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-h5))
            "Should trace constructors and insertion side-effects when rule calls an inserting helper"))

      ;; Rule H6: Side-effect only rule
      (let [ann-h6 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-side-effect-only)]
        (is (nil? ann-h6) "Should not list rules that do not perform inserts or retracts"))

      ;; Rule H7: insert-all! with collection constructed by helper
      (let [ann-h7 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-insert-all-helper)]
        (is (some? ann-h7))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord]
               (:clara-rules/insert-types ann-h7))
            "Should trace constructors called by collection-building helpers passed to insert-all!"))

      ;; Rule H8: insert-all! with heterogeneous collection constructed by helper
      (let [ann-h8 (get annotations 'clara.server.tools.graph.rules.analyze-test-rules/rule-insert-all-heterogeneous)]
        (is (some? ann-h8))
        (is (= ['clara.server.tools.graph.rules.analyze_test_rules.LocalDummyRecord
                'clara.server.tools.graph.rules.loan_app_facts.DocumentCheck]
               (:clara-rules/insert-types ann-h8))
            "Should identify multiple different fact types constructed and inserted via a collection helper")))))

(deftest test-merge-annotations
  (testing "Merging existing annotations with newly generated annotations"
    (let [existing '{clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs
                     {:clara-rules/insert-types [AllGivenDocuments]}}
          generated '{clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs
                      {:clara-rules/insert-types [clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments]}
                      clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs
                      {:clara-rules/insert-types [clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments]}}
          merged (analyze/merge-annotations existing generated)]
      (is (= '[AllGivenDocuments]
             (get-in merged ['clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs :clara-rules/insert-types]))
          "Existing symbol annotations should be preserved over generated ones")
      (is (= '[clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments]
             (get-in merged ['clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs :clara-rules/insert-types]))
          "New rules should be correctly added to the annotations map")

      (let [existing-str {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
                          {:clara-rules/insert-types '[AllGivenDocuments]}}
            merged-str (analyze/merge-annotations existing-str generated)]
        (is (= '[AllGivenDocuments]
               (get-in merged-str ["clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs" :clara-rules/insert-types]))
            "Existing string annotations should be preserved over generated ones")))))
