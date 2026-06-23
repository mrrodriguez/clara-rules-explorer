(ns clara.server.tools.graph.source-sink-test
  (:require [clara.rules :as r]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private loan-doc-annotations
  (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
          .getPath
          ann/load-sidecar))

(defn- ->loan-app-session
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(deftest test-source-sink-indicators-loan-app
  (let [session (->loan-app-session)
        analysis (core/rulebase-analysis session loan-doc-annotations)
        rules (:rules analysis)
        queries (:queries analysis)]

    (testing "Source rules (no upstream dependencies)"
      ;; collect-app-given-docs reads Application and GivenDocument (never inserted by rules)
      (let [rule (get rules "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs")]
        (is (true? (:source-rule rule)) "Should be a source rule"))

      ;; collect-app-req-docs reads Application and RequiredDocument (never inserted by rules)
      (let [rule (get rules "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")]
        (is (true? (:source-rule rule)) "Should be a source rule")))

    (testing "Middle rules (have both upstream and downstream rules)"
      ;; app-has-all-required-docs reads DocumentCheckInput (from collect-app-doc-check-input)
      ;; and feeds DocumentCheck (to app-outcome rules)
      (let [rule (get rules "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs")]
        (is (false? (:source-rule rule)))
        (is (false? (:sink-rule rule)))))

    (testing "Complex dependencies (not sinks due to recursive types or :not)"
      ;; In loan-app-rules, app-outcome rules insert ApplicationOutcome.
      ;; app-outcome-denied and app-outcome-pending READ ApplicationOutcome via :not.
      ;; Thus they are downstream of any rule inserting ApplicationOutcome (including themselves).
      (let [pending (get rules "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending")]
        (is (false? (:sink-rule pending)) "Should NOT be a sink because it is upstream of other rules via ApplicationOutcome"))
      
      (let [approved (get rules "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved")]
        (is (false? (:sink-rule approved)) "Should NOT be a sink because denied/pending depend on its ApplicationOutcome inserts")))

    (testing "Queries should not have source/sink indicators"
      (let [query (get queries "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome")]
        (is (nil? (:source-rule query)))
        (is (nil? (:sink-rule query))))

      (let [query (get queries "clara.server.tools.graph.rules.loan-doc-rules/find-document-check")]
        (is (nil? (:source-rule query)))
        (is (nil? (:sink-rule query)))))))
