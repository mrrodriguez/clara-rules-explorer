(ns clara.server.tools.graph.core-test
  (:require [clara.rules :as r]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules :as ldr]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            GivenDocument
            AllGivenDocuments
            AllRequiredDocuments]))

(use-fixtures :each (fn [f]
                      (reset! ldr/count-atom 0)
                      (f)))

(def ^:private loan-doc-annotations
  (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
          .getPath
          ann/load-sidecar))

(defn- ->test-session
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(deftest test-loan-doc-rules-behavior
  (testing "Document check logic"
    (let [session (-> (->test-session)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card}))
                      (r/fire-rules))
          results (r/query session ldr/find-document-check :?app-id "app-1")]
      (is (= 1 (count results)))
      (is (= :pass (:status (:?document-check (first results)))))))

  (testing "collect-all-missing-required-docs fires when missing-required-docs is non-empty"
    (r/fire-rules
     (reduce r/insert (->test-session)
             [(laf/map->Application {:app-id "app-2"})
              (laf/map->RequiredDocument {:app-id "app-2" :doc-type :paystub})
              (laf/map->RequiredDocument {:app-id "app-2" :doc-type :w2})
              (laf/map->GivenDocument {:app-id "app-2" :doc-type :paystub})]))
    (is (pos? @ldr/count-atom)
        "collect-all-missing-required-docs should have fired at least once")))

(deftest test-loan-app-outcome-behavior
  (testing "Application outcome: approved (approved path)"
    (let [session (-> (->test-session)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->IdentityCheck {:app-id "app-1" :status :pass})
                                (laf/map->FraudCheck {:app-id "app-1" :status :pass}))
                      (r/fire-rules))
          results (r/query session clara.server.tools.graph.rules.loan-app-rules/find-app-outcome :?app-id "app-1")]
      (is (= 1 (count results)))
      (is (= :approved (:status (:?outcome (first results)))))))

  (testing "Application outcome: denied (one check failed)"
    (let [session (-> (->test-session)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->IdentityCheck {:app-id "app-1" :status :pass})
                                (laf/map->FraudCheck {:app-id "app-1" :status :fail}))
                      (r/fire-rules))
          results (r/query session clara.server.tools.graph.rules.loan-app-rules/find-app-outcome :?app-id "app-1")]
      (is (= 1 (count results)))
      (is (= :denied (:status (:?outcome (first results)))))))

  (testing "Application outcome: pending (checks incomplete)"
    (let [session (-> (->test-session)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
                                ;; Missing IdentityCheck and FraudCheck
                                )
                      (r/fire-rules))
          results (r/query session clara.server.tools.graph.rules.loan-app-rules/find-app-outcome :?app-id "app-1")]
      (is (= 1 (count results)))
      (is (= :pending (:status (:?outcome (first results))))))))

(deftest test-lhs-type-extraction
  (testing "Extraction from various internal condition types"
    (is (= [Application GivenDocument]
           (core/extract-lhs-fact-types [{:type Application :constraints []}
                                         {:accumulator 'some-acc
                                          :from {:type GivenDocument :constraints []}}])))

    (is (= [Application AllGivenDocuments AllRequiredDocuments]
           (core/extract-lhs-fact-types [{:type Application :constraints []}
                                         {:type AllGivenDocuments :constraints []}
                                         {:type AllRequiredDocuments :constraints []}])))))

(deftest test-rulebase-analysis-loan-app
  (let [session (->test-session)
        analysis (core/rulebase-analysis session loan-doc-annotations)]

    (testing "Rules summary"
      (let [rules-map (:rules analysis)]
        (is (contains? rules-map "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
        (let [summary (get rules-map "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs")]
          (is (some #{"clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"} (:insert-types summary)))
          (is (some #{"clara.server.tools.graph.rules.loan_app_facts.Application" "clara.server.tools.graph.rules.loan_app_facts.GivenDocument"}
                    (:lhs-types summary)))
          ;; Verify summary includes downstream info directly
          (is (some (fn [d] (= "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input" (:name d)))
                    (:downstream summary))))))

    (testing "Queries summary"
      (let [queries-map (:queries analysis)]
        (is (contains? queries-map "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"))
        (let [summary (get queries-map "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome")]
          (is (= #{"?app-id"} (:params summary)))
          (is (some #{"clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome"} (:lhs-types summary)))
          ;; Verify summary includes upstream info directly
          (is (some (fn [u] (= "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved" (:name u)))
                    (:upstream summary))))

        (is (contains? queries-map "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"))
        (let [summary (get queries-map "clara.server.tools.graph.rules.loan-doc-rules/find-document-check")]
          (is (= #{"?app-id"} (:params summary)))
          (is (some #{"clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"} (:lhs-types summary)))
          (is (some (fn [u] (= "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs" (:name u)))
                    (:upstream summary))))))

    (testing "Fact types summary"
      (let [fact-types (:fact-types analysis)]
        (is (contains? fact-types "clara.server.tools.graph.rules.loan_app_facts.Application"))
        (let [app-fact (get fact-types "clara.server.tools.graph.rules.loan_app_facts.Application")]
          (is (some #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"} (:used-by-rules app-fact)))
          (is (some #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"} (:used-by-rules app-fact))))

        (is (contains? fact-types "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"))
        (let [all-given (get fact-types "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments")]
          (is (some #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"} (:inserted-by-rules all-given)))
          (is (some #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"} (:used-by-rules all-given))))))

    (testing "Nodes and Rete structure"
      (let [nodes (:nodes analysis)]
        (is (seq nodes))
        (is (some (fn [[_ node]] (= :alpha (:kind node))) nodes))
        (is (some (fn [[_ node]] (= :production (:kind node))) nodes))
        (is (some (fn [[_ node]] (= :query (:kind node))) nodes))))

    (testing "Dependency graph edges"
      (let [graph (:dep-graph analysis)]
        (is (contains? graph "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
        (let [edges (get graph "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs")]
          (is (contains? (:downstream edges) "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input")))
        (is (contains? graph "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"))
        (let [edges (get graph "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input")]
          (is (contains? (:upstream edges) "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
          (is (contains? (:upstream edges) "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")))))))

(deftest test-dependency-graph-correctness
  (let [session (->test-session)
        analysis (core/rulebase-analysis session loan-doc-annotations)
        rules (:rules analysis)]
    (testing "Upstream and downstream dependencies are correctly identified"
      (let [collect-given "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
            collect-req "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
            collect-input "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"

            summary-given (get rules collect-given)
            summary-input (get rules collect-input)]

        ;; collect-app-given-docs inserts AllGivenDocuments
        ;; collect-app-doc-check-input reads AllGivenDocuments
        ;; Thus collect-app-given-docs -> collect-app-doc-check-input

        (is (some #{"clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"} (:insert-types summary-given)))
        (is (some #{"clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"} (:lhs-types summary-input)))

        ;; Note: summary upstream/downstream entries are maps: {:ns ... :name ... :type ...}
        (is (some (fn [d] (= collect-input (:name d))) (:downstream summary-given)))
        (is (some (fn [u] (= collect-given (:name u))) (:upstream summary-input)))
        (is (some (fn [u] (= collect-req (:name u))) (:upstream summary-input)))))))

(deftest test-dep-graph-full
  (let [session (->test-session)
        analysis (core/rulebase-analysis session loan-doc-annotations)
        graph (:dep-graph analysis)]
    (testing "Full expected dependency graph structure"
      (is (= {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
              {:downstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"
              {:downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"},
               :upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
              {:downstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"}},
              "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"}},
              "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
                 "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"},
               :downstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                 "clara.server.tools.graph.rules.loan-doc-rules/collect-all-missing-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/collect-all-missing-required-docs"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"}},
              "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"},
               :downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"
                 "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"
                 "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"},
               :downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"
                 "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"},
               :downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"}}}
             graph)))))

;;;;
;; Type hierarchy

(derive ::Car ::Vehicle)

(r/defrule car-producer
  {:clara-rules/insert-types [::Car]}
  [String]
  =>
  (r/insert! (with-meta {:id 1} {:type ::Car})))

(r/defrule vehicle-consumer
  [?v <- ::Vehicle]
  =>
  (r/insert! (with-meta {:vehicle ?v} {:type ::Found})))

(r/defquery get-found-vehicle []
  [::Found (= ?vehicle (:vehicle this))])

(deftest test-hierarch-rules-behavior
  (let [session (-> (r/mk-session [car-producer vehicle-consumer get-found-vehicle])
                    (r/insert "hi")
                    (r/fire-rules))
        [res :as results] (r/query session get-found-vehicle)]
    (is (= 1 (count results)))
    (is (= {:?vehicle {:id 1}} res))))

(deftest test-dep-graph-hierarchy
  (testing "Dependency graph edges with type hierarchy (ancestor-fn)"
    (let [session (r/mk-session [car-producer vehicle-consumer])
          analysis (core/rulebase-analysis session {})
          graph (:dep-graph analysis)]
      (is (contains? (get-in graph ["clara.server.tools.graph.core-test/car-producer" :downstream])
                     "clara.server.tools.graph.core-test/vehicle-consumer"))
      (is (contains? (get-in graph ["clara.server.tools.graph.core-test/vehicle-consumer" :upstream])
                     "clara.server.tools.graph.core-test/car-producer")))))

(deftest test-fact-type-summary-order
  (let [session (->test-session)
        analysis (core/rulebase-analysis session loan-doc-annotations)
        fact-types (:fact-types analysis)]
    (testing "Fact type summary maintains insertion order (rules first, then queries)"
      (is (= ["clara.server.tools.graph.rules.loan_app_facts.Application"
              "clara.server.tools.graph.rules.loan_app_facts.GivenDocument"
              "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"
              "clara.server.tools.graph.rules.loan_app_facts.RequiredDocument"
              "clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments"
              "clara.server.tools.graph.rules.loan_app_facts.DocumentCheckInput"
              "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"
              "clara.server.tools.graph.rules.loan_app_facts.IdentityCheck"
              "clara.server.tools.graph.rules.loan_app_facts.FraudCheck"
              "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome"]
             (vec (keys fact-types)))))

    (testing "Fact type summary entry structure"
      (is (= {:name "clara.server.tools.graph.rules.loan_app_facts.Application"
              :used-by-rules ["clara.server.tools.graph.rules.loan-doc-rules/collect-app-id-card-given-docs"
                              "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
                              "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
                              "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"
                              "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"
                              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"
                              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending"]
              :used-by-queries []
              :inserted-by-rules []
              :retracted-by-rules []}
             (get fact-types "clara.server.tools.graph.rules.loan_app_facts.Application"))))))

(deftest test-unlinked-rule-detection
  (let [session (->test-session)
        analysis (core/rulebase-analysis session loan-doc-annotations)
        rules (:rules analysis)
        unlinked-rule-name "clara.server.tools.graph.rules.loan-doc-rules/collect-app-id-card-given-docs"
        unlinked (get rules unlinked-rule-name)]

    (testing "Unlinked rule has :unlinked-rule metadata"
      (is (contains? unlinked :unlinked-rule))
      (let [{:keys [downstream reason]} (:unlinked-rule unlinked)]
        (is (= :unknown downstream))
        (is (string? reason))
        (is (str/includes? reason "no declared insert-types"))))

    (testing "Unlinked rule is not considered a sink rule"
      (is (false? (:sink-rule unlinked))))

    (testing "Unlinked rule is a source rule (no rule produces its consumed fact types)"
      (is (true? (:source-rule unlinked))
          "Rule consuming only external facts should be a source rule"))

    (testing "Unlinked rule's JSON-serialized view (rules-list) omits :downstream"
      (let [rule-list (core/rules-list analysis)
            unlinked-item (first (filter #(= unlinked-rule-name (:name %)) rule-list))]
        (is (contains? unlinked-item :unlinked-rule))
        (is (not (contains? unlinked-item :downstream)))))))

(deftest test-no-output-types-annotation-prevents-unlinked
  (testing "Rule with :clara-rules/no-output-types true is not flagged as unlinked"
    (let [session (->test-session)
          analysis (core/rulebase-analysis session loan-doc-annotations)
          rules (:rules analysis)
          rule (get rules "clara.server.tools.graph.rules.loan-doc-rules/collect-all-missing-required-docs")]
      (is (not (contains? rule :unlinked-rule))
          "Rule with :clara-rules/no-output-types should not have :unlinked-rule")
      (is (empty? (:insert-types rule)))
      (is (empty? (:retract-types rule)))
      (is (false? (:sink-rule rule))
          "No-output rule should not be considered a sink"))))
