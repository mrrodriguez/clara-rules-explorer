(ns clara.server.tools.graph.analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [clara.server.tools.graph.analyze :as analyze]))

(deftest test-static-analysis-rules
  (testing "Traces RHS function calls and constructors programmatically"
    (let [annotations (analyze/analyze-rules ["test/clara/server/tools/graph/rules/"])]
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
