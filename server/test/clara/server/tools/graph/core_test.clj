(ns clara.server.tools.graph.core-test
  (:require [clara.rules :as r]
            [clara.rules.engine :as eng]
            [clara.server.tools.graph.annotation-fixtures :as fixtures]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.fact-types :as ft]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules :as ldr]
            [clara.server.tools.graph.rules.loan-hierarchy-rules :as lhr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [schema.test :as st])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            GivenDocument
            AllGivenDocuments
            AllRequiredDocuments]))

(use-fixtures :once st/validate-schemas)

(use-fixtures :each (fn [f]
                      (reset! ldr/count-atom 0)
                      (f)))

(defn- type-ref-names [refs]
  (set (map :name refs)))

(defn- prod-dep-names [deps]
  (set (map :name deps)))

(defn- loan-doc-annotations
  [session]
  (fixtures/loan-doc-merged-annotations session))

(defn- ->test-session
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

;; ---------------------------------------------------------------------------
;; Loan-hierarchy fixture helpers (see loan_hierarchy_rules.clj)
;; ---------------------------------------------------------------------------

(defn- ->hierarchy-session
  "Session over the loan-hierarchy fixture rules.  `opts` may provide an
   :ancestors-fn override; mk-session options are keyword args (a trailing
   options map would be parsed as a rule source and silently dropped)."
  [& [opts]]
  (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                :fact-type-fn lhr/fact-type-fn
                :ancestors-fn (:ancestors-fn opts)))

(defn- hierarchy-annotations
  "Props-layer annotations for the loan-hierarchy fixture (its rules declare
   :clara-rules/insert-types in props)."
  [session]
  (ann.merge/merge-layers [(ann.merge/props-layer session)]))

(defn- fact-type-by-name
  "Fact-type entry by its exact serialized name."
  [analysis type-name]
  (get-in analysis [:fact-types type-name]))

;; ---------------------------------------------------------------------------
;; Intransitive / cyclic hierarchy fixtures (topological-sort edge cases)
;; ---------------------------------------------------------------------------

;; A ≺ B by hierarchy, but strings order B < C < A — a fused comparator would
;; assert B<C and C<A yet A<B (intransitive; TimSort would throw).
(derive ::intra-a ::intra-b)
(derive ::intra-c ::intra-a)

(r/defrule intra-producer
  {:clara-rules/insert-types [::intra-c]}
  [String]
  =>
  (r/insert! (with-meta {:x 1} {:type ::intra-c})))

(r/defrule intra-consumer
  [?x <- ::intra-b]
  =>
  (r/insert! (with-meta {:y ?x} {:type ::intra-done})))

;; Mutually-ancestral custom ancestors-fn — must terminate via the cycle guard.
(defn- cycle-ancestors [t]
  (cond
    (= t ::cyc-a) #{::cyc-b}
    (= t ::cyc-b) #{::cyc-a}
    :else (try (clojure.core/ancestors t) (catch Throwable _ nil))))

(r/defrule cyc-producer
  {:clara-rules/insert-types [::cyc-a]}
  [Long]
  =>
  (r/insert! (with-meta {:x 1} {:type ::cyc-a})))

(r/defrule cyc-consumer
  [?x <- ::cyc-b]
  =>
  (r/insert! (with-meta {:y ?x} {:type ::cyc-done})))

(defn- intra-analysis
  []
  (let [session (r/mk-session [intra-producer intra-consumer])
        anns (ann.merge/merge-layers [(ann.merge/props-layer session)])]
    (core/->rulebase-analysis session anns)))

(defn- cyc-analysis
  []
  (let [session (r/mk-session [cyc-producer cyc-consumer] :ancestors-fn cycle-ancestors)
        anns (ann.merge/merge-layers [(ann.merge/props-layer session)])]
    (core/->rulebase-analysis session anns)))

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
        analysis (core/->rulebase-analysis session (loan-doc-annotations session))]

    (testing "Rules summary"
      (let [rules-map (:rules analysis)]
        (is (contains? rules-map "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
        (let [summary (get rules-map "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs")]
          (is (contains? (type-ref-names (:insert-types summary))
                         "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"))
          (is (contains? (type-ref-names (:lhs-types summary))
                         "clara.server.tools.graph.rules.loan_app_facts.Application"))
          (is (contains? (type-ref-names (:lhs-types summary))
                         "clara.server.tools.graph.rules.loan_app_facts.GivenDocument"))
          ;; Verify summary includes downstream info directly
          (is (some (fn [d] (= "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input" (:name d)))
                    (:downstream summary))))))

    (testing "Queries summary"
      (let [queries-map (:queries analysis)]
        (is (contains? queries-map "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"))
        (let [summary (get queries-map "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome")]
          (is (= #{"?app-id"} (:params summary)))
          (is (contains? (type-ref-names (:lhs-types summary))
                         "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome"))
          ;; Verify summary includes upstream info directly
          (is (some (fn [u] (= "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?" (:name u)))
                    (:upstream summary))))

        (is (contains? queries-map "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"))
        (let [summary (get queries-map "clara.server.tools.graph.rules.loan-doc-rules/find-document-check")]
          (is (= #{"?app-id"} (:params summary)))
          (is (contains? (type-ref-names (:lhs-types summary))
                         "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"))
          (is (some (fn [u] (= "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs" (:name u)))
                    (:upstream summary))))))

    (testing "Fact types summary"
      (let [fact-types (:fact-types analysis)]
        (is (contains? fact-types "clara.server.tools.graph.rules.loan_app_facts.Application"))
        (let [app-fact (get fact-types "clara.server.tools.graph.rules.loan_app_facts.Application")]
          (is (contains? (prod-dep-names (:used-by-rules app-fact))
                         "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
          (is (contains? (prod-dep-names (:used-by-rules app-fact))
                         "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")))

        (is (contains? fact-types "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"))
        (let [all-given (get fact-types "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments")]
          (is (contains? (prod-dep-names (:inserted-by-rules all-given))
                         "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
          (is (contains? (prod-dep-names (:used-by-rules all-given))
                         "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input")))))

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
        analysis (core/->rulebase-analysis session (loan-doc-annotations session))
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

        (is (contains? (type-ref-names (:insert-types summary-given))
                       "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"))
        (is (contains? (type-ref-names (:lhs-types summary-input))
                       "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"))

        ;; Note: summary upstream/downstream entries are maps: {:ns ... :name ... :type ...}
        (is (some (fn [d] (= collect-input (:name d))) (:downstream summary-given)))
        (is (some (fn [u] (= collect-given (:name u))) (:upstream summary-input)))
        (is (some (fn [u] (= collect-req (:name u))) (:upstream summary-input)))))))

(deftest test-dep-graph-full
  (let [session (->test-session)
        analysis (core/->rulebase-analysis session (loan-doc-annotations session))
        graph (:dep-graph analysis)]
    (testing "Full expected dependency graph structure"
      (is (= {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
              {:downstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
              {:downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved-args-demo"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"},
               :upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved-args-demo"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"}},
              "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
              {:downstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"}},
              "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"}},
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
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                 "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-review"
                 "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-metadata"
                 "clara.server.tools.graph.rules.loan-doc-rules/dynamic-retract-stale-notice"
                 "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-audit-trail"
                 "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                 "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"},
               :downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved-args-demo"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"}},
              "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                 "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"},
               :downstream
               #{"clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved-args-demo"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"}},
              "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-review"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-metadata"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/dynamic-retract-stale-notice"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}},
              "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-audit-trail"
              {:upstream
               #{"clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"}}}
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
          analysis (core/->rulebase-analysis session (ann.merge/annotations (ann.merge/merge-layers [(ann.merge/props-layer session)])))
          graph (:dep-graph analysis)]
      (is (contains? (get-in graph ["clara.server.tools.graph.core-test/car-producer" :downstream])
                     "clara.server.tools.graph.core-test/vehicle-consumer"))
      (is (contains? (get-in graph ["clara.server.tools.graph.core-test/vehicle-consumer" :upstream])
                     "clara.server.tools.graph.core-test/car-producer")))))

(def ^:private loan-app-fact-type-order
  "Directly-referenced fact types in canonical load order (rules first, then
   queries).  Hierarchy-only ancestor types (e.g. clojure.lang.IPersistentMap,
   java.lang.Object) appear after these, sorted alphabetically."
  ["clara.server.tools.graph.rules.loan_app_facts.Application"
   "clara.server.tools.graph.rules.loan_app_facts.GivenDocument"
   ":extract-doc-meta"
   "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocumentsMeta"
   "clara.server.tools.graph.rules.loan_doc_rules.AllIdCardGivenDocuments"
   "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"
   "clara.server.tools.graph.rules.loan_app_facts.RequiredDocument"
   "clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments"
   ":loan-doc-rules/document-check-input"
   "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"
   "clara.server.tools.graph.rules.loan_doc_rules.StaleDocumentNotice"
   "clara.server.tools.graph.rules.loan_app_facts.IdentityCheck"
   "clara.server.tools.graph.rules.loan_app_facts.FraudCheck"
   "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome"])

(deftest test-fact-type-summary-order
  (let [session (->test-session)
        analysis (core/->rulebase-analysis session (loan-doc-annotations session))
        fact-types (:fact-types analysis)]
    (testing "Directly-referenced fact types maintain insertion order as a prefix"
      (is (= loan-app-fact-type-order
             (take (count loan-app-fact-type-order) (keys fact-types)))))

    (testing "Hierarchy-only ancestor types appear at the end, sorted"
      (let [all-keys (vec (keys fact-types))
            hierarchy-keys (drop (count loan-app-fact-type-order) all-keys)]
        (is (seq hierarchy-keys)
            "Record types have Java interface ancestors (IPersistentMap, Object, etc.)")
        (is (every? (fn [k] (str/starts-with? k "clojure.lang."))
                    (take 3 hierarchy-keys)))
        (is (= (sort hierarchy-keys) hierarchy-keys)
            "Hierarchy-only types are sorted alphabetically")))

    (testing "Fact type summary entry structure"
      (let [entry (get fact-types "clara.server.tools.graph.rules.loan_app_facts.Application")]
        (is (= #{"clara.server.tools.graph.rules.loan-doc-rules/collect-doc-meta"
                 "clara.server.tools.graph.rules.loan-doc-rules/collect-app-id-card-given-docs"
                 "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
                 "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
                 "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"
                 "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"
                 "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"}
               (prod-dep-names (:used-by-rules entry)))
            "used-by-rules is a [ProductionDep] list")
        (is (every? #(= (:type %) "rule") (:used-by-rules entry)))
        (is (empty? (:used-by-queries entry)))
        (is (empty? (:inserted-by-rules entry)))
        (is (empty? (:retracted-by-rules entry)))
        (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (:name entry)))
        (is (= "clara.server.tools.graph.rules.loan_app_facts" (:ns entry))
            "Fact-type :ns is the package name for a class type")
        (is (every? #(and (string? (:name %))
                          (string? (:id %))
                          (false? (:known %)))
                    (:ancestors entry))
            "Record ancestors are all ghost types (known: false)")
        (is (= ["clojure.lang.IHashEq"
                "clojure.lang.IKeywordLookup"
                "clojure.lang.IObj"
                "clojure.lang.IMeta"
                "clojure.lang.IPersistentMap"
                "clojure.lang.Associative"
                "clojure.lang.Counted"
                "clojure.lang.ILookup"
                "clojure.lang.IPersistentCollection"
                "clojure.lang.IRecord"
                "clojure.lang.Seqable"
                "java.io.Serializable"
                "java.lang.Iterable"
                "java.lang.Object"
                "java.util.Map"]
               (mapv :name (:ancestors entry)))
            "Ancestors are deterministically hierarchy-ordered (descendant-first, lexicographic tie-break)")))))

(deftest test-unlinked-rule-detection
  (let [session (->test-session)
        analysis (core/->rulebase-analysis session (loan-doc-annotations session))
        rules (:rules analysis)
        unlinked-rule-name "clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta-rule"
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
      (let [rule-list (core/get-rules-list analysis)
            unlinked-item (first (filter #(= unlinked-rule-name (:name %)) rule-list))]
        (is (contains? unlinked-item :unlinked-rule))
        (is (not (contains? unlinked-item :downstream)))))))

(deftest test-no-output-types-annotation-prevents-unlinked
  (testing "Rule with :clara-rules/no-output-types true is not flagged as unlinked"
    (let [session (->test-session)
          analysis (core/->rulebase-analysis session (loan-doc-annotations session))
          rules (:rules analysis)
          rule (get rules "clara.server.tools.graph.rules.loan-doc-rules/collect-all-missing-required-docs")]
      (is (not (contains? rule :unlinked-rule))
          "Rule with :clara-rules/no-output-types should not have :unlinked-rule")
      (is (empty? (:insert-types rule)))
      (is (empty? (:retract-types rule)))
      (is (false? (:sink-rule rule))
          "No-output rule should not be considered a sink"))))

(deftest test-dynamic-detection-in-rules-list
  (let [session (->test-session)
        analysis (core/->rulebase-analysis session (loan-doc-annotations session))
        rule-list (core/get-rules-list analysis)
        rule-by-name #(first (filter (fn [r] (= (:name r) %)) rule-list))]

    (testing "Unresolved dynamic-insert rule via helper call"
      (let [rule (rule-by-name "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-review")]
        (is (contains? rule :unlinked-rule))
        (is (empty? (:insert-types rule)))
        (let [dyn (:dynamic-insert-types-detected rule)]
          (is (= :none (:resolution dyn)))
          (is (= 1 (count (:callsites dyn))))
          (is (match? [{:source-str "(build-compliance-review ?app-id)"
                        :ns "clara.server.tools.graph.rules.loan-doc-rules"
                        :filename "clara/server/tools/graph/rules/loan_doc_rules.clj"
                        :status :none}]
                      (:callsites dyn))))))

    (testing "Unresolved dynamic-insert rule via metadata helper"
      (let [rule (rule-by-name "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-metadata")]
        (is (contains? rule :unlinked-rule))
        (is (empty? (:insert-types rule)))
        (let [dyn (:dynamic-insert-types-detected rule)]
          (is (= :none (:resolution dyn)))
          (is (= 1 (count (:callsites dyn))))
          (is (match? [{:source-str "(build-compliance-via-metadata ?app-id)"
                        :ns "clara.server.tools.graph.rules.loan-doc-rules"
                        :filename "clara/server/tools/graph/rules/loan_doc_rules.clj"
                        :status :none}]
                      (:callsites dyn))))))

    (testing "Resolved dynamic-retract rule"
      (let [rule (rule-by-name "clara.server.tools.graph.rules.loan-doc-rules/dynamic-retract-stale-notice")]
        (is (= #{"clara.server.tools.graph.rules.loan_doc_rules.StaleDocumentNotice"}
               (type-ref-names (:retract-types rule)))
            "retract-types are TypeReferences whose :name is the class name")
        (let [dyn (:dynamic-retract-types-detected rule)]
          (is (= :full (:resolution dyn)))
          (is (= 1 (count (:callsites dyn))))
          (let [callsite (first (:callsites dyn))]
            (is (= "(StaleDocumentNotice. ?app-id :paystub \"no-longer-needed\")" (:source-str callsite)))
            (is (= "clara.server.tools.graph.rules.loan-doc-rules" (:ns callsite)))
            (is (= :full (:status callsite)))
            (is (= [{:name "clara.server.tools.graph.rules.loan_doc_rules.StaleDocumentNotice"
                     :known true}]
                   (mapv #(select-keys % [:name :known]) (:resolved-types callsite))))
            (is (= {:name "clara.server.tools.graph.rules.loan_doc_rules.StaleDocumentNotice"
                    :known true}
                   (select-keys (first (:resolved-types callsite)) [:name :known])))))))

    (testing "Unresolved dynamic-insert rule has callsite info but no insert-types"
      (let [rule (rule-by-name "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-audit-trail")]
        (is (contains? rule :unlinked-rule))
        (is (empty? (:insert-types rule)))
        (let [dyn (:dynamic-insert-types-detected rule)]
          (is (= :none (:resolution dyn)))
          (is (= 1 (count (:callsites dyn))))
          (is (match? [{:source-str "(build-audit-trail-entry ?app-id :doc-check-passed)"
                        :ns "clara.server.tools.graph.rules.loan-doc-rules"
                        :filename "clara/server/tools/graph/rules/loan_doc_rules.clj"
                        :status :none}]
                      (:callsites dyn))))))))

;; ---------------------------------------------------------------------------
;; Fact-type hierarchy: :ancestors, :known, ordering, :ns
;; ---------------------------------------------------------------------------

(deftest test-loan-hierarchy-behavior
  (testing "The fixture session fires and matches through the keyword hierarchy and tuples"
    (let [session (-> (->hierarchy-session)
                      (r/insert (lhr/map->LoanApplication {:app-id "app-1" :status :new}))
                      (r/fire-rules))]
      (is (= 1 (count (r/query session lhr/find-loan-documents)))
          "::income-document satisfies ::loan-document via the derive chain")
      (is (pos? (count (r/query session lhr/find-map-facts)))
          "LoanApplication (a record) satisfies clojure.lang.IPersistentMap"))))

(deftest test-loan-hierarchy-ancestors
  (let [session (->hierarchy-session)
        analysis (core/->rulebase-analysis session (hierarchy-annotations session))]

    (testing "Keyword derive hierarchy is transitive; known reflects usage"
      (let [income (fact-type-by-name analysis
                                      ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document")
            ancestors (:ancestors income)]
        (is (some? income))
        (is (= [":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document"
                ":clara.server.tools.graph.rules.loan-hierarchy-rules/loan-document"
                ":clara.server.tools.graph.rules.loan-hierarchy-rules/base-document"]
               (mapv :name ancestors))
            "Descendant-first hierarchy order (supporting <: loan <: base)")
        (is (= [true true false]
               (mapv :known ancestors))
            "supporting/loan are on an LHS (known), base-document is a ghost")))

    (testing "Record type: interface ancestor known, JDK ghosts not"
      (let [app (fact-type-by-name analysis
                                   "clara.server.tools.graph.rules.loan_hierarchy_rules.LoanApplication")
            ancestors (:ancestors app)]
        (is (some? app))
        (is (contains? (set (map :name ancestors)) "clojure.lang.IPersistentMap"))
        (is (true? (:known (first (filter #(= "clojure.lang.IPersistentMap" (:name %)) ancestors))))
            "IPersistentMap is on the find-map-facts query LHS → known")
        (is (false? (:known (first (filter #(= "java.lang.Object" (:name %)) ancestors)))))
        (is (false? (:known (first (filter #(= "java.io.Serializable" (:name %)) ancestors))))))

      (testing "Underived keyword and tuple types have empty ancestors"
        (is (empty? (:ancestors (fact-type-by-name analysis
                                                   ":clara.server.tools.graph.rules.loan-hierarchy-rules/document-reviewed"))))
        (is (empty? (:ancestors (fact-type-by-name analysis "[:loan/status \"verified\"]")))))

      (testing "Tuple types are kind-explicit in the analysis"
        (let [verified (fact-type-by-name analysis "[:loan/status \"verified\"]")
              mismatch (fact-type-by-name analysis "[:document/flag \"income-mismatch\"]")]
          (is (some? verified))
          (is (some? mismatch))
          (is (seq (:inserted-by-rules verified)))
          (is (seq (:inserted-by-rules mismatch)))
          (is (seq (:used-by-rules verified))))))))

(deftest test-ancestors-missing-meta-fallback
  (testing "A rulebase whose get-alphas-fn meta lacks :ancestors-fn falls back to clojure.core/ancestors"
    (let [session (->hierarchy-session)
          rulebase (-> session eng/components :rulebase)
          gaf (:get-alphas-fn rulebase)
          rulebase' (assoc rulebase :get-alphas-fn (with-meta gaf (dissoc (meta gaf) :ancestors-fn)))
          analysis (core/->rulebase-analysis rulebase' (hierarchy-annotations session))
          income (fact-type-by-name analysis
                                    ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document")]
      (is (seq (:ancestors income))
          "clojure.core/ancestors fallback still yields the derive hierarchy"))))

(deftest test-ancestors-nil-returning-fn
  (testing "A user ancestors-fn returning nil yields empty :ancestors — no NPE"
    (let [session (->hierarchy-session {:ancestors-fn (fn [_] nil)})
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))]
      (is (seq (:fact-types analysis)))
      (is (every? (comp empty? :ancestors) (vals (:fact-types analysis)))))))

(deftest test-ancestors-memoization
  (testing "ancestors-fn is invoked exactly once per distinct raw type across the whole analysis"
    (let [calls (atom 0)
          counting-fn (fn [t]
                        (swap! calls inc)
                        (try (clojure.core/ancestors t) (catch Throwable _ nil)))
          session (->hierarchy-session {:ancestors-fn counting-fn})
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))
          fact-types (:fact-types analysis)
          expected (count (into #{}
                                (concat (keys fact-types)
                                        (mapcat (fn [e] (map :name (:ancestors e)))
                                                (vals fact-types)))))]
      (is (= expected @calls)
          "once per distinct raw type in consumed∪produced ∪ their ancestors"))))

(deftest test-ancestors-mixed-kind
  (testing "Custom ancestors-fn with mixed kinds serializes kind-explicitly and orders on strings"
    (let [custom (fn [t]
                   (if (= t :clara.server.tools.graph.rules.loan-hierarchy-rules/income-document)
                     #{:clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document
                       "string-parent"}
                     (try (clojure.core/ancestors t) (catch Throwable _ nil))))
          session (->hierarchy-session {:ancestors-fn custom})
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))
          income (fact-type-by-name analysis
                                    ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document")]
      (is (= ["\"string-parent\"" ":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document"]
             (mapv :name (:ancestors income)))
          "string ancestor is quoted, keyword keeps its colon; lexicographic tie-break orders them")
      (is (= [false true] (mapv :known (:ancestors income)))))))

(deftest test-divergent-serialization-degrades
  (testing "A raw type serializing differently across production ns contexts degrades (keeps the first load-order serialization, logs a warning) instead of throwing"
    (let [tam (array-map
               'prod-a {:consumed-types ['join] :produced-types [] :retract-types #{} :ns-name 'clojure.string}
               'prod-b {:consumed-types ['join] :produced-types [] :retract-types #{} :ns-name 'clara.server.tools.graph.rules.loan-app-rules})
          idx (ft/->ancestors-index tam
                                    (fn [_] #{})
                                    [{:name 'prod-a} {:name 'prod-b}])]
      (is (= {"clojure.string/join" {:ancestors [] :ns nil}}
             idx)
          "The first (load-order) production's serialization is canonical; the divergent symbol[...] one is dropped")
      (is (seq idx) "Degradation still yields an ancestors index — no build-wide failure")))

  (testing "A full ->rulebase-analysis over a divergent annotation set still builds (no throw)"
    (let [session (->test-session)
          anns (ann.merge/merge-layers
                [(ann.merge/props-layer session)
                 (ann.merge/layer
                  {:id :divergent
                   :annotations
                   {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
                    {:clara-rules/insert-types ['AllRequiredDocuments]}
                    "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"
                    {:clara-rules/insert-types ['AllRequiredDocuments]}}})])
          ;; 'AllRequiredDocuments resolves in loan-doc-rules (imported there)
          ;; but not in loan-app-rules → the same raw symbol serializes to
          ;; both "...AllRequiredDocuments" and "symbol[AllRequiredDocuments]".
          analysis (core/->rulebase-analysis session anns)
          canonical (fact-type-by-name analysis
                                       "clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments")
          divergent (fact-type-by-name analysis "symbol[AllRequiredDocuments]")]
      (is (seq (:fact-types analysis)) "Analysis builds despite the divergent type")
      (is (some? canonical) "The load-order (loan-doc) serialization is the canonical fact-type entry")
      (is (some? divergent) "The other serialization still surfaces as a fact type (per-production serialization)")
      (is (empty? (:ancestors divergent)) "A divergent alias has no ancestors entry — localized degradation, not a crash")
      (is (get-in analysis [:rules "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"])
          "The rule summaries are still produced"))))

(deftest test-ancestors-intransitive-and-cyclic
  (testing "Intransitive hierarchy/string ordering terminates without a comparator error"
    (let [analysis (intra-analysis)
          c-entry (fact-type-by-name analysis ":clara.server.tools.graph.core-test/intra-c")]
      (is (= [":clara.server.tools.graph.core-test/intra-a"
              ":clara.server.tools.graph.core-test/intra-b"]
             (mapv :name (:ancestors c-entry)))
          "Hierarchy wins over string order (intra-a before intra-b even though intra-b < intra-a lexicographically)")))

  (testing "Mutually-ancestral custom ancestors-fn terminates via the cycle guard"
    (let [analysis (cyc-analysis)
          a-entry (fact-type-by-name analysis ":clara.server.tools.graph.core-test/cyc-a")]
      (is (some? a-entry))
      (is (= [":clara.server.tools.graph.core-test/cyc-b"]
             (mapv :name (:ancestors a-entry)))))))

(deftest test-condition-type-matches-lhs-types
  (testing "Every LHS condition :type :name string-equals a :lhs-types entry :name (all kinds)"
    (let [session (->hierarchy-session)
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))]
      (doseq [[p-name summary] (:rules analysis)]
        (doseq [cond (:lhs summary)]
          (when-let [type-ref (:type cond)]
            (is (some #(= (:name type-ref) (:name %)) (:lhs-types summary))
                (str "condition :type " (:name type-ref) " of " p-name
                     " must match an :lhs-types entry"))))))))

(deftest test-ancestors-symbol-ns-resolution
  (testing "A fact type from an unresolved symbol insert-type still appears with :ancestors"
    (let [session (->hierarchy-session)
          rule-name "clara.server.tools.graph.rules.loan-hierarchy-rules/insert-income-document"
          analysis (core/->rulebase-analysis
                    session
                    {rule-name {:clara-rules/insert-types ['my.ns/unresolved-thing]}})
          ft (fact-type-by-name analysis "symbol[my.ns/unresolved-thing]")]
      (is (some? ft))
      (is (contains? ft :ancestors))
      (is (empty? (:ancestors ft))))))

(deftest test-fact-type-ns
  (testing "Fact-type :ns is best-effort namespace/package per raw kind"
    (let [session (->hierarchy-session)
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))]
      (is (= "clara.server.tools.graph.rules.loan_hierarchy_rules"
             (:ns (fact-type-by-name analysis
                                     "clara.server.tools.graph.rules.loan_hierarchy_rules.LoanApplication")))
          "class → package name")
      (is (= "clara.server.tools.graph.rules.loan-hierarchy-rules"
             (:ns (fact-type-by-name analysis
                                     ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document")))
          "keyword → its namespace")
      (is (nil? (:ns (fact-type-by-name analysis "[:loan/status \"verified\"]")))
          "tuple → nil"))))

(deftest test-fact-type-id-index
  (testing "The reverse index resolves every fact-type id back to its name"
    (let [session (->hierarchy-session)
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))
          index (ft/->fact-type-id-index analysis)]
      (doseq [{type-name :name type-id :id} (vals (:fact-types analysis))]
        (is (= type-name (get index type-id))
            (str "index resolves " type-id " back to " type-name)))))

  (testing "A route-id collision throws at index build time"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ft/->fact-type-id-index
                  {:fact-types {"a" {:id "same-id" :name "a"}
                                "b" {:id "same-id" :name "b"}}})))))

(deftest test-production-id-index
  (testing "The reverse index resolves every rule and query id back to its name"
    (let [session (->test-session)
          analysis (core/->rulebase-analysis session (loan-doc-annotations session))
          index (core/->production-id-index analysis)]
      (doseq [{prod-name :name prod-id :id} (concat (vals (:rules analysis))
                                                    (vals (:queries analysis)))]
        (is (= prod-name (get index prod-id))
            (str "index resolves " prod-id " back to " prod-name)))))

  (testing "A production route-id collision throws at index build time"
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/->production-id-index
                  {:rules {"a" {:id "same-id" :name "a"}}
                   :queries {"b" {:id "same-id" :name "b"}}})))))

;; ---------------------------------------------------------------------------
;; Type-bridge info (:match) on dependency edges
;; ---------------------------------------------------------------------------

(derive ::match-child-a ::match-parent-a)
(derive ::match-child-b ::match-parent-b)

;; Multi-type bridge: producer inserts both children, consumer reads both parents.
(r/defrule match-multi-producer
  {:clara-rules/insert-types [::match-child-a ::match-child-b]}
  [String]
  =>
  (r/insert! (with-meta {:a 1} {:type ::match-child-a}))
  (r/insert! (with-meta {:b 1} {:type ::match-child-b})))

(r/defrule match-multi-consumer
  [?a <- ::match-parent-a]
  [?b <- ::match-parent-b]
  =>
  (r/insert! (with-meta {:done true} {:type ::match-done})))

;; Direct AND hierarchy matches in one pair.
(r/defrule match-direct-producer
  {:clara-rules/insert-types [::match-foo ::match-child-a]}
  [Long]
  =>
  (r/insert! (with-meta {:foo 1} {:type ::match-foo}))
  (r/insert! (with-meta {:a 1} {:type ::match-child-a})))

(r/defrule match-direct-consumer
  [?f <- ::match-foo]
  [?a <- ::match-parent-a]
  =>
  (r/insert! (with-meta {:done true} {:type ::match-done-2})))

;; Duplicate declarations on both ends collapse to one match entry.
(r/defrule match-dedup-producer
  {:clara-rules/insert-types [::match-child-a ::match-child-a]}
  [Boolean]
  =>
  (r/insert! (with-meta {:a 1} {:type ::match-child-a}))
  (r/insert! (with-meta {:a 2} {:type ::match-child-a})))

(r/defrule match-dedup-consumer
  [?x <- ::match-parent-a]
  [?y <- ::match-parent-a]
  =>
  (r/insert! (with-meta {:done true} {:type ::match-done-3})))

(defn- match-rulebase-analysis
  [rules]
  (let [session (r/mk-session rules)]
    (core/->rulebase-analysis session
                              (ann.merge/merge-layers [(ann.merge/props-layer session)]))))

(defn- dep-by-name [deps name]
  (first (filter #(= name (:name %)) deps)))

(defn- match-pairs
  "[[producer-name consumer-name] ...] for a dep's :match array."
  [dep]
  (mapv (fn [m] [(:name (:producer-type m)) (:name (:consumer-type m))])
        (:match dep)))

(defn- production-summary-by-name [analysis name]
  (or (get-in analysis [:rules name])
      (get-in analysis [:queries name])))

(deftest test-match-direct
  (testing "Direct match (no hierarchy) on both directions"
    (let [session (->test-session)
          analysis (core/->rulebase-analysis session (loan-doc-annotations session))
          producer (get-in analysis [:rules "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"])
          consumer (get-in analysis [:rules "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"])
          down (dep-by-name (:downstream producer) (:name consumer))
          up (dep-by-name (:upstream consumer) (:name producer))]
      (is (= [["clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"
               "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"]]
             (match-pairs down)))
      (is (= (match-pairs down) (match-pairs up))
          "match is symmetric across both directions"))))

(deftest test-match-hierarchy-jump
  (testing "Single hierarchy jump (produced descendant satisfies consumed ancestor)"
    (let [session (->hierarchy-session)
          analysis (core/->rulebase-analysis session (hierarchy-annotations session))
          producer (get-in analysis [:rules "clara.server.tools.graph.rules.loan-hierarchy-rules/insert-income-document"])
          consumer (get-in analysis [:rules "clara.server.tools.graph.rules.loan-hierarchy-rules/review-supporting-document"])
          down (dep-by-name (:downstream producer) (:name consumer))
          up (dep-by-name (:upstream consumer) (:name producer))]
      (is (= [[":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document"
               ":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document"]]
             (match-pairs down)))
      (is (= (match-pairs down) (match-pairs up)) "symmetric"))))

(deftest test-match-multi-type
  (testing "A single production pair linked by multiple type pairs"
    (let [analysis (match-rulebase-analysis [match-multi-producer match-multi-consumer])
          producer (get-in analysis [:rules "clara.server.tools.graph.core-test/match-multi-producer"])]
      (is (= [[":clara.server.tools.graph.core-test/match-child-a"
               ":clara.server.tools.graph.core-test/match-parent-a"]
              [":clara.server.tools.graph.core-test/match-child-b"
               ":clara.server.tools.graph.core-test/match-parent-b"]]
             (match-pairs (dep-by-name (:downstream producer)
                                       "clara.server.tools.graph.core-test/match-multi-consumer")))
          "two matches, sorted by producer then consumer :name"))))

(deftest test-match-direct-and-hierarchy
  (testing "Direct and hierarchy matches coexist in one pair"
    (let [analysis (match-rulebase-analysis [match-direct-producer match-direct-consumer])
          producer (get-in analysis [:rules "clara.server.tools.graph.core-test/match-direct-producer"])]
      (is (= [[":clara.server.tools.graph.core-test/match-child-a"
               ":clara.server.tools.graph.core-test/match-parent-a"]
              [":clara.server.tools.graph.core-test/match-foo"
               ":clara.server.tools.graph.core-test/match-foo"]]
             (match-pairs (dep-by-name (:downstream producer)
                                       "clara.server.tools.graph.core-test/match-direct-consumer")))))))

(deftest test-match-dedup
  (testing "Duplicate insert declarations and LHS conditions collapse to one match entry"
    (let [analysis (match-rulebase-analysis [match-dedup-producer match-dedup-consumer])
          producer (get-in analysis [:rules "clara.server.tools.graph.core-test/match-dedup-producer"])]
      (is (= [[":clara.server.tools.graph.core-test/match-child-a"
               ":clara.server.tools.graph.core-test/match-parent-a"]]
             (match-pairs (dep-by-name (:downstream producer)
                                       "clara.server.tools.graph.core-test/match-dedup-consumer")))))))

(deftest test-match-cross-field-consistency
  (testing "producer-type :name matches the producer's own insert/retract types; consumer-type :name the consumer's lhs-types"
    (let [sessions [[(->test-session) loan-doc-annotations]
                    [(->hierarchy-session) hierarchy-annotations]
                    [(r/mk-session [match-multi-producer match-multi-consumer
                                    match-direct-producer match-direct-consumer
                                    match-dedup-producer match-dedup-consumer])
                     (fn [s] (ann.merge/merge-layers [(ann.merge/props-layer s)]))]]]
      (doseq [[session annotations-fn] sessions
              :let [analysis (core/->rulebase-analysis session (annotations-fn session))]]
        (doseq [[p-name summary] (concat (:rules analysis) (:queries analysis))]
          (doseq [dir [:upstream :downstream]]
            (doseq [dep (get summary dir)]
              (doseq [m (:match dep)]
                (let [[producer-name consumer-name]
                      (if (= dir :upstream)
                        [(:name dep) p-name]
                        [p-name (:name dep)])
                      producer (production-summary-by-name analysis producer-name)
                      consumer (production-summary-by-name analysis consumer-name)]
                  (is (contains? (type-ref-names (concat (:insert-types producer)
                                                         (:retract-types producer)))
                                 (get-in m [:producer-type :name]))
                      (str producer-name " :match producer-type must be in its own insert/retract types"))
                  (is (contains? (type-ref-names (:lhs-types consumer))
                                 (get-in m [:consumer-type :name]))
                      (str consumer-name " :match consumer-type must be in its own lhs-types")))))))))))

(deftest test-match-sidecar-symbol-foreign-ns
  (testing "A :match producer-type from a sidecar annotation with a foreign-ns symbol stays consistent with the producer's own insert-types"
    (let [session (->hierarchy-session)
          producer-name "clara.server.tools.graph.rules.loan-hierarchy-rules/insert-income-document"
          consumer-name "clara.server.tools.graph.rules.loan-hierarchy-rules/review-supporting-document"
          ;; Bare sidecar annotation (string rule-name keys): a foreign-ns
          ;; unresolved symbol plus the rule's real keyword insert-type.
          analysis (core/->rulebase-analysis
                    session
                    {producer-name {:clara-rules/insert-types ['my.ns/foreign-symbol
                                                               ::lhr/income-document]}})
          producer (get-in analysis [:rules producer-name])
          consumer (get-in analysis [:rules consumer-name])
          down (dep-by-name (:downstream producer) consumer-name)
          up (dep-by-name (:upstream consumer) producer-name)]
      (is (contains? (type-ref-names (:insert-types producer)) "symbol[my.ns/foreign-symbol]")
          "the foreign symbol serializes kind-explicitly in the producer's insert-types")
      (is (= [[":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document"
               ":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document"]]
             (match-pairs down))
          "the edge still forms via the keyword type")
      (is (= (match-pairs down) (match-pairs up)) "match is symmetric")
      (doseq [m (:match down)]
        (is (contains? (type-ref-names (concat (:insert-types producer)
                                               (:retract-types producer)))
                       (get-in m [:producer-type :name]))
            "cross-field invariant holds with the sidecar layer present")
        (is (contains? (type-ref-names (:lhs-types consumer))
                       (get-in m [:consumer-type :name])))))))

;; Retraction-coupled bridge: the producer retracts ::retract-target, the
;; consumer reads it — the match is flagged :via :retract.
(r/defrule via-retract-producer
  {:clara-rules/retract-types [::retract-target]}
  [String]
  =>
  (r/retract! (with-meta {:x 1} {:type ::retract-target})))

(r/defrule via-retract-consumer
  [?t <- ::retract-target]
  =>
  (r/insert! (with-meta {:done true} {:type ::via-retract-done})))

;; Mixed bridge: one producer inserts ::insert-target and retracts
;; ::retract-target; the consumer reads both — two matches, flagged distinctly.
(r/defrule via-mixed-producer
  {:clara-rules/insert-types  [::insert-target]
   :clara-rules/retract-types [::retract-target]}
  [Long]
  =>
  (r/insert! (with-meta {:i 1} {:type ::insert-target}))
  (r/retract! (with-meta {:r 1} {:type ::retract-target})))

(r/defrule via-mixed-consumer
  [?i <- ::insert-target]
  [?r <- ::retract-target]
  =>
  (r/insert! (with-meta {:done true} {:type ::via-mixed-done})))

(deftest test-match-via-retract
  (testing "A retract-only bridge is flagged :via :retract on both directions"
    (let [analysis (match-rulebase-analysis [via-retract-producer via-retract-consumer])
          producer (get-in analysis [:rules "clara.server.tools.graph.core-test/via-retract-producer"])
          consumer (get-in analysis [:rules "clara.server.tools.graph.core-test/via-retract-consumer"])
          down (dep-by-name (:downstream producer) (:name consumer))
          up (dep-by-name (:upstream consumer) (:name producer))]
      (is (= [[":clara.server.tools.graph.core-test/retract-target"
               ":clara.server.tools.graph.core-test/retract-target"]]
             (match-pairs down)))
      (is (= [:retract] (mapv :via (:match down))))
      (is (= (mapv :via (:match down)) (mapv :via (:match up)))
          "via flag is symmetric across both directions"))))

(deftest test-match-via-insert-and-retract
  (testing "Insert and retract bridges in one pair are flagged distinctly"
    (let [analysis (match-rulebase-analysis [via-mixed-producer via-mixed-consumer])
          producer (get-in analysis [:rules "clara.server.tools.graph.core-test/via-mixed-producer"])
          down (dep-by-name (:downstream producer) "clara.server.tools.graph.core-test/via-mixed-consumer")]
      (is (= [[":clara.server.tools.graph.core-test/insert-target"
               ":clara.server.tools.graph.core-test/insert-target"]
              [":clara.server.tools.graph.core-test/retract-target"
               ":clara.server.tools.graph.core-test/retract-target"]]
             (match-pairs down)))
      (is (= [nil :retract] (mapv :via (:match down)))
          "insert match unflagged, retract match flagged"))))
