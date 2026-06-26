(ns clara.server.tools.graph.analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [clara.rules :as r]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.rules.loan-doc-rules :as ldr]
            [clara.server.tools.graph.rules.analyze-test-rules :as atr])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            AllGivenDocuments
            AllRequiredDocuments
            DocumentCheckInput
            DocumentCheck]
           [clara.server.tools.graph.rules.loan_doc_rules
            AllIdCardGivenDocuments]
           [clara.server.tools.graph.rules.analyze_test_rules
            LocalDummyRecord]))


(deftest test-static-analysis-rules
  (testing "Traces RHS function calls and constructors programmatically"
    (let [annotations (analyze/analyze-rules-from-paths {:paths ["test/clara/server/tools/graph/rules/loan_doc_rules.clj"]})]
      ;; Rule 1: collect-app-id-card-given-docs
      (let [ann-1 (get annotations `ldr/collect-app-id-card-given-docs)]
        (is (some? ann-1) "Should find collect-app-id-card-given-docs")
        (is (= [`AllIdCardGivenDocuments]
               (:clara-rules/insert-types ann-1))
            "Should identify the locally defined AllIdCardGivenDocuments record type"))

      ;; Rule 2: collect-app-given-docs
      (let [ann-2 (get annotations `ldr/collect-app-given-docs)]
        (is (some? ann-2))
        (is (= [`AllGivenDocuments]
               (:clara-rules/insert-types ann-2))
            "Should identify AllGivenDocuments record type from foreign facts ns"))

      ;; Rule 3: collect-app-req-docs
      (let [ann-3 (get annotations `ldr/collect-app-req-docs)]
        (is (some? ann-3))
        (is (= [`AllRequiredDocuments]
               (:clara-rules/insert-types ann-3))
            "Should identify AllRequiredDocuments record type"))

      ;; Rule 4: collect-app-doc-check-input
      (let [ann-4 (get annotations `ldr/collect-app-doc-check-input)]
        (is (some? ann-4))
        (is (= [`DocumentCheckInput]
               (:clara-rules/insert-types ann-4))
            "Should identify DocumentCheckInput record type"))

      ;; Rule 5: app-has-all-required-docs
      (let [ann-5 (get annotations `ldr/app-has-all-required-docs)]
        (is (some? ann-5))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-5))
            "Should identify DocumentCheck record type"))

      ;; Rule 6: collect-all-missing-required-docs
      (let [ann-6 (get annotations `ldr/collect-all-missing-required-docs)]
        (is (nil? ann-6) "Should not list rules that do not insert/retract facts")))))

(deftest test-static-analysis-edge-cases
  (testing "Traces different Clojure and Java constructor patterns and helpers"
    (let [annotations (analyze/analyze-rules-from-paths {:paths ["test/clara/server/tools/graph/rules/analyze_test_rules.clj"]})]
      ;; Rule A: standard Clojure record constructor
      (let [ann-a (get annotations `atr/rule-record-constructor)]
        (is (some? ann-a))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-a))
            "Should identify the locally defined LocalDummyRecord class")
        (is (nil? (:clara-rules/dynamic-insert-types-detected ann-a))
            "Should not set dynamic-insert-types-detected when types are statically resolved"))

      ;; Rule B: Java constructor style (Class. ...)
      (let [ann-b (get annotations `atr/rule-java-constructor-dot)]
        (is (some? ann-b))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-b))
            "Should identify DocumentCheck from Class. constructor usage"))

      ;; Rule C: Java constructor style (new Class ...)
      (let [ann-c (get annotations `atr/rule-java-constructor-new)]
        (is (some? ann-c))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-c))
            "Should identify DocumentCheck from new Class constructor usage"))

      ;; Rule D: Tracing through helper function
      (let [ann-d (get annotations `atr/rule-nested-helper-call)]
        (is (some? ann-d))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-d))
            "Should trace constructors called by transitively invoked functions"))

      ;; Rule E: Map facts with metadata (highly dynamic)
      (let [ann-e (get annotations `atr/rule-metadata-map-fact)]
        (is (some? ann-e) "Should recognize rule-metadata-map-fact as an inserter")
        (is (= [] (:clara-rules/insert-types ann-e))
            "Should infer an empty insert-types vector because metadata map facts are too dynamic to resolve statically")
        (is (true? (:clara-rules/dynamic-insert-types-detected ann-e))
            "Should tag the rule with dynamic-insert-types-detected as true since we have no statically inferred types"))

      ;; Rule B2: Fully-qualified Class. constructor
      (let [ann-b2 (get annotations `atr/rule-java-constructor-fq-dot)]
        (is (some? ann-b2))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-b2))
            "Should identify DocumentCheck from fq Class. constructor usage"))

      ;; Rule C2: Short name new Class constructor
      (let [ann-c2 (get annotations `atr/rule-java-constructor-short-new)]
        (is (some? ann-c2))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-c2))
            "Should identify DocumentCheck from new short-Class constructor usage"))

      ;; Rule F1: Modern constructor syntax (Class/new) via short name
      (let [ann-f1 (get annotations `atr/rule-java-constructor-short-modern)]
        (is (some? ann-f1))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-f1))
            "Should identify DocumentCheck from short Class/new constructor usage"))

      ;; Rule F2: Modern constructor syntax (Class/new) via fully-qualified name
      (let [ann-f2 (get annotations `atr/rule-java-constructor-fq-modern)]
        (is (some? ann-f2))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-f2))
            "Should identify DocumentCheck from fq Class/new constructor usage"))

      ;; Rule G: Tracing helper function calling Java constructor
      (let [ann-g (get annotations `atr/rule-nested-java-helper-call)]
        (is (some? ann-g))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-g))
            "Should trace constructors called by helper functions that instantiate Java classes"))

      ;; Rule H1: insert-all! with collection of records
      (let [ann-h1 (get annotations `atr/rule-insert-all-collection)]
        (is (some? ann-h1))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-h1))
            "Should identify LocalDummyRecord from insert-all! usage"))

      ;; Rule H2: insert! with varargs
      (let [ann-h2 (get annotations `atr/rule-insert-varargs)]
        (is (some? ann-h2))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-h2))
            "Should identify LocalDummyRecord from insert! varargs usage"))

      ;; Rule H3: retract! with varargs
      (let [ann-h3 (get annotations `atr/rule-retract-varargs)]
        (is (some? ann-h3))
        (is (= [`LocalDummyRecord]
               (:clara-rules/retract-types ann-h3))
            "Should identify LocalDummyRecord from retract! varargs usage"))

      ;; Rule H4: RHS with complex nested doseq loop
      (let [ann-h4 (get annotations `atr/rule-complex-rhs-nested)]
        (is (some? ann-h4))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-h4))
            "Should trace through complex nested RHS doseq constructs"))

      ;; Rule H5: RHS calls helper function which does the insert
      (let [ann-h5 (get annotations `atr/rule-helper-does-insert)]
        (is (some? ann-h5))
        (is (= [`DocumentCheck]
               (:clara-rules/insert-types ann-h5))
            "Should trace constructors and insertion side-effects when rule calls an inserting helper"))

      ;; Rule H6: Side-effect only rule
      (let [ann-h6 (get annotations `atr/rule-side-effect-only)]
        (is (nil? ann-h6) "Should not list rules that do not perform inserts or retracts"))

      ;; Rule H7: insert-all! with collection constructed by helper
      (let [ann-h7 (get annotations `atr/rule-insert-all-helper)]
        (is (some? ann-h7))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-h7))
            "Should trace constructors called by collection-building helpers passed to insert-all!"))

      ;; Rule H8: insert-all! with heterogeneous collection constructed by helper
      (let [ann-h8 (get annotations `atr/rule-insert-all-heterogeneous)]
        (is (some? ann-h8))
        (is (= [`LocalDummyRecord
                `DocumentCheck]
               (:clara-rules/insert-types ann-h8))
            "Should identify multiple different fact types constructed and inserted via a collection helper"))

      ;; Rule H9: insert-unconditional! usage
      (let [ann-h9 (get annotations `atr/rule-insert-unconditional)]
        (is (some? ann-h9))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-h9))
            "Should identify LocalDummyRecord from insert-unconditional! usage"))

      ;; Rule H10: insert-all-unconditional! usage
      (let [ann-h10 (get annotations `atr/rule-insert-all-unconditional)]
        (is (some? ann-h10))
        (is (= [`LocalDummyRecord]
               (:clara-rules/insert-types ann-h10))
            "Should identify LocalDummyRecord from insert-all-unconditional! usage"))
      
      ;; Test filter and no-output-types generation
      (let [annotations-filtered (analyze/analyze-rules-from-paths {:paths ["test/clara/server/tools/graph/rules/analyze_test_rules.clj"]
                                                                     :rules-filter [`atr/rule-side-effect-only]})
            ann-h6 (get annotations-filtered `atr/rule-side-effect-only)]
        (is (some? ann-h6) "Should keep rule-side-effect-only when it is in rules-filter")
        (is (true? (:clara-rules/no-output-types ann-h6))
            "Should mark rules that don't insert/retract at all as no-output-types true")))))

(deftest test-merge-annotations
  (testing "Merging existing annotations with newly generated annotations"
    (let [existing {`ldr/collect-app-given-docs
                    {:clara-rules/insert-types '[AllGivenDocuments]}}
          generated {`ldr/collect-app-given-docs
                     {:clara-rules/insert-types [`AllGivenDocuments]}
                     `ldr/collect-app-req-docs
                     {:clara-rules/insert-types [`AllRequiredDocuments]}}
          merged (analyze/merge-annotations existing generated)]
      (is (= '[AllGivenDocuments]
             (get-in merged [`ldr/collect-app-given-docs :clara-rules/insert-types]))
          "Existing symbol annotations should be preserved over generated ones")
      (is (= [`AllRequiredDocuments]
             (get-in merged [`ldr/collect-app-req-docs :clara-rules/insert-types]))
          "New rules should be correctly added to the annotations map")

      (let [existing-str {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
                          {:clara-rules/insert-types '[AllGivenDocuments]}}
            merged-str (analyze/merge-annotations existing-str generated)]
        (is (= '[AllGivenDocuments]
               (get-in merged-str ["clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs" :clara-rules/insert-types]))
            "Existing string annotations should be preserved over generated ones")))))

(deftest test-classpath-resolution-and-caching
  (testing "Namespace path conversion"
    (is (= "clara/server/tools/graph/rules/analyze_test_rules"
           (analyze/ns->resource-base 'clara.server.tools.graph.rules.analyze-test-rules))))

  (testing "Resource finding"
    (is (some? (analyze/find-ns-resource 'clara.server.tools.graph.rules.analyze-test-rules)))
    (is (nil? (analyze/find-ns-resource 'non-existent-ns.fake))))

  (testing "Build analysis from namespaces with custom cache"
    (let [cache (atom {})
          project-prefix "clara.server.tools.graph.rules"
          merged (analyze/build-analysis-from-namespaces {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
                                                           :include-ns-prefixes [project-prefix]
                                                           :cache-atom cache})]
      ;; Verify that the starting namespace was analyzed and stored in cache
      (is (contains? @cache 'clara.server.tools.graph.rules.analyze-test-rules))
      ;; Verify that dependencies (e.g. loan-app-facts) were transitively analyzed and cached
      (is (contains? @cache 'clara.server.tools.graph.rules.loan-app-facts))
      ;; Verify that the merged analysis contains expected var definitions
      (let [var-defs (set (map :name (:var-definitions merged)))]
        (is (contains? var-defs 'make-document-check))
        (is (contains? var-defs 'rule-record-constructor)))))

  (testing "Build analysis from namespaces with global cache"
    (analyze/clear-global-analysis-cache!)
    (let [project-prefix "clara.server.tools.graph.rules"
          _ (analyze/build-analysis-from-namespaces {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
                                                      :include-ns-prefixes [project-prefix]})]
      ;; Verify the global cache is populated
      (is (not-empty @@#'analyze/global-analysis-cache))
      
      (analyze/clear-global-analysis-cache!)
      ;; Verify the global cache is cleared
      (is (empty? @@#'analyze/global-analysis-cache))))

  (testing "Resolve, ingest, and analyze rules together (production style)"
    (let [project-prefix "clara.server.tools.graph.rules"
          merged-analysis (analyze/build-analysis-from-namespaces {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
                                                                    :include-ns-prefixes [project-prefix]})
          annotations (analyze/analyze-rules {:analysis merged-analysis})]
      (is (some? (get annotations `atr/rule-record-constructor)))
      (is (= [`LocalDummyRecord]
             (get-in annotations [`atr/rule-record-constructor :clara-rules/insert-types])))))

  (testing "Analyze session rules directly (high-level API)"
    (let [session (r/mk-session 'clara.server.tools.graph.rules.analyze-test-rules)
          project-prefix "clara.server.tools.graph.rules"
          annotations (analyze/analyze-session-rules {:session-or-rulebase session
                                                       :include-ns-prefixes [project-prefix]})]
      (is (some? (get annotations `atr/rule-record-constructor)))
      (is (= [`LocalDummyRecord]
             (get-in annotations [`atr/rule-record-constructor :clara-rules/insert-types])))
      (is (some? (get annotations `atr/rule-insert-varargs)))
      (is (= [`LocalDummyRecord]
             (get-in annotations [`atr/rule-insert-varargs :clara-rules/insert-types]))))))


