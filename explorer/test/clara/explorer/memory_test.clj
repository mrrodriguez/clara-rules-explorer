(ns clara.explorer.memory-test
  (:require [clara.rules :as r]
            [clara.explorer.annotations.merge :as ann.merge]
            [clara.explorer.core :as core]
            [clara.explorer.memory :as memory]
            [clara.explorer.serialize :as serialize]
            [clara.explorer.test.rules.loan-app-facts :as laf]
            [clara.explorer.test.rules.loan-app-rules]
            [clara.explorer.test.rules.loan-doc-rules]
            [clara.explorer.test.rules.nil-safety-test-rules :as nil-safety]
            [clara.explorer.test.rules.equal-fact-test-rules :as equal-facts]
            [clara.explorer.test.rules.match-uniqueness-test-rules :as mu]
            [clara.explorer.test.rules.memory-relations-test-rules :as mrr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- ->test-session
  []
  (r/mk-session 'clara.explorer.test.rules.loan-doc-rules
                'clara.explorer.test.rules.loan-app-rules))

(deftest test-monotonic-fact-ids
  (testing "Facts are assigned monotonic IDs in a deterministic order"
    (let [app-1 (laf/map->Application {:app-id "app-1"})
          app-2 (laf/map->Application {:app-id "app-2"})
          session (-> (->test-session)
                      (r/insert app-1 app-2)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          facts (:facts memory-analysis)
          ids (keys facts)]

      (is (seq ids) "Memory analysis should contain facts")
      (is (= (set (range 1 (inc (count facts)))) (set ids)) "IDs should be 1 to N")

      (let [app-1-data (serialize/prune-fns app-1)
            app-2-data (serialize/prune-fns app-2)
            app-1-id (some (fn [[id f]] (when (= (:data f) app-1-data) id)) facts)
            app-2-id (some (fn [[id f]] (when (= (:data f) app-2-data) id)) facts)]
        (is (some? app-1-id))
        (is (some? app-2-id))
        (is (not= app-1-id app-2-id))))))

(deftest test-identity-based-ids
  (testing "Equal but distinct facts get different IDs"
    (let [app-a (laf/map->Application {:app-id "equal"})
          app-b (laf/map->Application {:app-id "equal"})
          _ (assert (not (identical? app-a app-b)) "Test setup: facts must be distinct instances")
          _ (assert (= app-a app-b) "Test setup: facts must be equal by value")

          session (-> (->test-session)
                      (r/insert app-a app-b)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          facts (:facts memory-analysis)
          app-data (serialize/prune-fns app-a)
          instances (filter #(= (:data (val %)) app-data) facts)]

      (is (= 2 (count instances)) "Both equal facts should be in the memory-analysis")
      (is (not= (first (keys instances)) (second (keys instances))) "They must have different IDs"))))

(deftest test-supports-insertions-of-index
  (testing "Supports-insertions-of index correctly identifies rules whose activation includes a fact"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          app-data (serialize/prune-fns app)
          fact-id (some (fn [[id f]] (when (= (:data f) app-data) id)) (:facts memory-analysis))
          supports-insertions-of (get-in memory-analysis [:facts fact-id :supports-insertions-of])]

      (is (seq supports-insertions-of) "Fact should support some rule insertions")
      (is (some #(= (:name %) "clara.explorer.test.rules.loan-doc-rules/collect-app-req-docs") supports-insertions-of)))))

(deftest test-origin-map
  (testing "Origin map correctly identifies the rule that inserted a fact"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          ;; Find a fact that was inserted by a rule, e.g., AllRequiredDocuments
          inserted-fact-entry (some (fn [[id f]]
                                      (when (= (:type f) "clara.explorer.test.rules.loan_app_facts.AllRequiredDocuments")
                                        [id f]))
                                    (:facts memory-analysis))]

      (when inserted-fact-entry
        (let [[id _] inserted-fact-entry
              origins (get-in memory-analysis [:origin id])]
          (is (seq origins) "Inserted fact should have an origin")
          (is (= "clara.explorer.test.rules.loan-doc-rules/collect-app-req-docs" (:name (first origins)))))))))

(deftest test-enriched-memory-analysis
  (testing "Memory analysis contains enriched fact-table and rule-centric groupings"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)

          ;; 1. Verify Enriched Fact Table
          app-data (serialize/prune-fns app)
          fact (some #(when (= (:data %) app-data) %) (vals (:facts memory-analysis)))]
      (is (some? fact))
      (is (vector? (:inserted-from fact)))
      (is (vector? (:supports-insertions-of fact)))
      (is (empty? (:inserted-from fact)) "Root fact should have no origins in origin-map")
      (is (seq (:supports-insertions-of fact)) "Fact should support some rule insertions")

      ;; 2. Verify Rule-Centric Index
      (let [type-info (get-in memory-analysis [:fact-types "clara.explorer.test.rules.loan_app_facts.Application"])]
        (is (seq (:inserted-from type-info)) "Type info should have rule-centric inserted-from")
        (is (= "Root Facts (External)" (:name (first (:inserted-from type-info)))))
        (is (= "root" (:type (first (:inserted-from type-info)))))
        (is (seq (:supports-insertions-of type-info)) "Type info should have rule-centric supports-insertions-of")
        (let [usage (first (:supports-insertions-of type-info))]
          (is (string? (:name usage)))
          (is (string? (:type usage)))
          (is (seq (:facts usage))))))))

(deftest test-rule-query-activity
  (testing "Memory analysis contains rule and query activity (inserted facts and matches)"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)

          ;; 1. Verify Rule Activity
          rule-name "clara.explorer.test.rules.loan-doc-rules/collect-app-req-docs"
          rule-info (get-in memory-analysis [:rule-matches rule-name])]
      (is (some? rule-info) "Rule info should exist in rule-matches")
      (is (seq (:inserted-facts rule-info)) "Rule should have inserted facts")
      (is (every? :id (:inserted-facts rule-info)) "Inserted facts should have IDs")
      (is (vector? (:matches rule-info)) "Rule should have matches vector")
      ;; Matches are FactMatch entries — {:fact SessionFact :bindings [...]}
      (when-let [match (first (:matches rule-info))]
        (is (map? (:fact match)) "Match entry should carry a :fact SessionFact")
        (is (vector? (:bindings match)) "Match entry should carry a :bindings vector")
        (let [fact (:fact match)]
          (is (int? (:id fact)) "Match fact should have integer :id")
          (is (map? (:type fact)) "Match fact :type is a TypeReference")
          (is (string? (get-in fact [:type :name])))
          (is (false? (get-in fact [:type :known]))
              "Session facts default to known: false without a known-set — the honest flag is computed against the analysis's fact-type names")
          (is (map? (:data fact)) "Match fact should have :data (the fact's own value)")
          (is (contains? fact :is-root) "Match fact should have :is-root")
          (is (vector? (:inserted-from fact)) "Match fact should have :inserted-from")
          (is (vector? (:supports-insertions-of fact)) "Match fact should have :supports-insertions-of")))

      ;; 2. Verify Query Activity
      (let [query-name "clara.explorer.test.rules.loan-doc-rules/find-document-check"
            query-info (get-in memory-analysis [:query-matches query-name])]
        (is (some? query-info) "Query info should exist in query-matches")
        (is (vector? (:matches query-info)) "Query should have matches vector")
        ;; Query matches are also FactMatch entries
        (when-let [qmatch (first (:matches query-info))]
          (is (map? (:fact qmatch)) "Query match entry should carry a :fact SessionFact")
          (is (vector? (:bindings qmatch)) "Query match entry should carry a :bindings vector")
          (let [fact (:fact qmatch)]
            (is (int? (:id fact)) "Query match fact should have integer :id")
            (is (map? (:type fact)) "Query match fact :type is a TypeReference")
            (is (string? (get-in fact [:type :name])))
            (is (map? (:data fact)) "Query match fact should have :data (the fact's own value)")))))))

(deftest test-multi-fact-match-flattening
  (testing "Multi-fact rule matches are flattened to one FactMatch entry per fact-id"
    (let [app (laf/map->Application {:app-id "app-1"})
          req-doc (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
          given-doc (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
          session (-> (->test-session)
                      (r/insert app req-doc given-doc)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)

          rule-name "clara.explorer.test.rules.loan-doc-rules/app-has-all-required-docs"
          rule-info (get-in memory-analysis [:rule-matches rule-name])
          matches (:matches rule-info)]

      (is (some? rule-info) "app-has-all-required-docs should exist in rule-matches")
      (is (= 2 (count matches))
          "Should have 2 match entries (Application + document-check-input), not 1 with fact-ids")
      (is (every? #(contains? (:fact %) :id) matches) "Every match fact should have :id")
      (is (every? #(contains? (:fact %) :type) matches) "Every match fact should have :type")
      (is (every? #(contains? (:fact %) :data) matches) "Every match fact should have :data")
      (is (every? #(contains? (:fact %) :is-root) matches) "Every match fact should have :is-root")
      (is (every? #(contains? (:fact %) :inserted-from) matches) "Every match fact should have :inserted-from")
      (is (every? #(contains? (:fact %) :supports-insertions-of) matches) "Every match fact should have :supports-insertions-of")
      ;; Verify types match the actual facts
      (let [types (set (map (comp :name :type :fact) matches))]
        (is (contains? types "clara.explorer.test.rules.loan_app_facts.Application")
            "Should include Application fact")
        (is (contains? types ":loan-doc-rules/document-check-input")
            "Should include document-check-input fact"))
      ;; Verify all match entries share the same single binding set
      (is (every? #(= 1 (count (:bindings %))) matches)
          "Every match fact should carry exactly one binding set")
      (let [bindings (map (comp first :bindings) matches)]
        (is (apply = bindings) "All match entries should share identical bindings")))))

;; ---------------------------------------------------------------------------
;; Match uniqueness — FactMatch shape (one row per fact, N binding sets)
;; ---------------------------------------------------------------------------

(defn- ->match-uniqueness-memory-analysis
  []
  (-> (r/mk-session 'clara.explorer.test.rules.match-uniqueness-test-rules)
      (r/insert (mu/->Config "c1") (mu/->Item "a") (mu/->Item "b") (mu/->Item nil))
      (r/fire-rules)
      (memory/->memory-analysis)))

(defn- mu-rule-matches
  [memory-analysis short-name]
  (get-in memory-analysis [:rule-matches
                           (str "clara.explorer.test.rules.match-uniqueness-test-rules/" short-name)
                           :matches]))

(def ^:private mu-item-type-name
  "clara.explorer.test.rules.match_uniqueness_test_rules.Item")

(def ^:private mu-config-type-name
  "clara.explorer.test.rules.match_uniqueness_test_rules.Config")

(deftest test-match-uniqueness-case-a
  (testing "One fact satisfying two conditions of one activation yields one row with one binding set"
    (let [memory-analysis (->match-uniqueness-memory-analysis)
          matches (mu-rule-matches memory-analysis "overlapping-conditions")
          item-matches (filter #(= mu-item-type-name (get-in % [:fact :type :name])) matches)]
      (is (= 3 (count item-matches)) "all three items appear, once each")
      (is (apply < (mapv (comp :id :fact) item-matches))
          "rows are sorted by fact id (strictly increasing)")
      (is (every? #(= 1 (count (:bindings %))) item-matches)
          "each item carries exactly one binding set")
      ;; The tagged items satisfied both accumulator conditions; their
      ;; duplicate (fact, bindings) pairs collapsed to one binding set.
      (let [bindings (map (comp first :bindings) item-matches)]
        (is (apply = bindings) "all items share the one activation's binding set")
        (is (= #{"a" "b"} (set (map :tag (:?tagged (first bindings)))))
            "?tagged accumulates the two tagged items")
        (is (= #{"a" "b" nil} (set (map :tag (:?all (first bindings)))))
            "?all accumulates all three items")))))

(deftest test-match-uniqueness-case-b
  (testing "One fact across N activations yields one row with N binding sets, none lost"
    (let [memory-analysis (->match-uniqueness-memory-analysis)
          matches (mu-rule-matches memory-analysis "pairwise")
          config-match (first (filter #(= mu-config-type-name (get-in % [:fact :type :name])) matches))]
      (is (some? config-match) "config appears in the pairwise matches")
      (is (= 3 (count (:bindings config-match)))
          "config appears once with three binding sets")
      (is (= #{"a" "b" nil}
             (set (map #(get-in % [:?item :tag]) (:bindings config-match))))
          "every activation's binding set is retained")
      (is (= "c1" (get-in config-match [:fact :data :name]))
          ":data is the fact's own value, not bindings"))))

(deftest test-match-uniqueness-combined
  (testing "A fact duplicated within and across activations appears once with distinct binding sets"
    (let [memory-analysis (->match-uniqueness-memory-analysis)
          matches (mu-rule-matches memory-analysis "combined")
          config-match (first (filter #(= mu-config-type-name (get-in % [:fact :type :name])) matches))]
      (is (some? config-match))
      (is (= 3 (count (:bindings config-match)))
          "within-activation duplicates collapsed; the three activation bindings remain")
      (is (= #{"a" "b" nil}
             (set (map #(get-in % [:?item :tag]) (:bindings config-match))))
          "no activation lost")
      (is (= 3 (count (distinct (:bindings config-match))))
          "binding sets are distinct"))))

(deftest test-match-uniqueness-distinct-ids
  (testing "Match fact ids are distinct over every rule and query in a fixture session"
    (let [memory-analysis (->match-uniqueness-memory-analysis)]
      (doseq [[p-name {:keys [matches]}] (:rule-matches memory-analysis)]
        (is (= (count matches) (count (distinct (map (comp :id :fact) matches))))
            (str "rule " p-name " has distinct match fact ids")))
      (doseq [[p-name {:keys [matches]}] (:query-matches memory-analysis)]
        (is (= (count matches) (count (distinct (map (comp :id :fact) matches))))
            (str "query " p-name " has distinct match fact ids")))
      ;; The query mirrors pairwise: Config appears once with three binding sets.
      (let [query-matches (get-in memory-analysis [:query-matches
                                                   "clara.explorer.test.rules.match-uniqueness-test-rules/find-pairs"
                                                   :matches])
            config-match (first (filter #(= mu-config-type-name (get-in % [:fact :type :name])) query-matches))]
        (is (some? config-match) "find-pairs query has a Config match")
        (is (= 3 (count (:bindings config-match)))
            "queries get the same group-and-collect treatment as rules")))))

(deftest test-match-uniqueness-ordering-stability
  (testing "Match rows and binding sets are ordered deterministically across identical sessions"
    (let [build (fn []
                  (-> (r/mk-session 'clara.explorer.test.rules.match-uniqueness-test-rules)
                      (r/insert (mu/->Config "c1") (mu/->Item "a") (mu/->Item "b") (mu/->Item nil))
                      (r/fire-rules)
                      (memory/->memory-analysis)))
          s1 (build)
          s2 (build)
          ;; `:inserted-facts` order for equal facts is not part of this
          ;; contract (see equal-fact fixtures); assert only the match shape.
          matches-only (fn [snap]
                         {:rule-matches (update-vals (:rule-matches snap) :matches)
                          :query-matches (:query-matches snap)})]
      (is (= (matches-only s1) (matches-only s2))
          "match rows and binding sets are byte-identical across identical sessions"))))

(deftest test-stable-deterministic-fact-ids
  (testing "Fact IDs are stable and deterministic based on sort criteria"
    (let [app-1 (laf/map->Application {:app-id "app-1"})
          app-2 (laf/map->Application {:app-id "app-2"})

          ;; Create two memory-analyses of identical sessions
          make-memory-analysis (fn []
                                 (-> (->test-session)
                                     (r/insert app-1 app-2)
                                     (r/fire-rules)
                                     (memory/->memory-analysis)))

          memory-analysis-1 (make-memory-analysis)
          memory-analysis-2 (make-memory-analysis)

          ;; Strip volatile fields (e.g. timestamps) from fact data for comparison
          strip-volatile (fn [data]
                           (if (and (map? data) (:timestamp data) (:action data))
                             (dissoc data :timestamp)
                             data))]

      (is (= (keys (:facts memory-analysis-1)) (keys (:facts memory-analysis-2))) "ID keys should be identical")
      (is (= (set (map (comp strip-volatile :data) (vals (:facts memory-analysis-1))))
             (set (map (comp strip-volatile :data) (vals (:facts memory-analysis-2))))) "Fact data set should be identical"))))

(deftest test-deterministic-fact-str--shapes
  (testing "set of maps does not throw"
    (is (string? (#'memory/deterministic-fact-str {:fact/type :t :results #{{:a 1}}} serialize/prune-fns))
        "set of maps must canonicalize without comparator error"))

  (testing "map keyed by a map does not throw"
    (is (string? (#'memory/deterministic-fact-str {:fact/type :t :by {{:a 1} 1}} serialize/prune-fns))
        "map keyed by a map must canonicalize without comparator error"))

  (testing "mixed key types does not throw"
    (is (string? (#'memory/deterministic-fact-str {:a 1 "b" 2} serialize/prune-fns))
        "mixed key types must canonicalize without class cast"))

  (testing "vector of maps is fine (regression)"
    (is (string? (#'memory/deterministic-fact-str {:fact/type :t :results [{:a 1}]} serialize/prune-fns))
        "vector of maps must canonicalize"))

  (testing "determinism: same map in different key orders → identical strings"
    (is (= (#'memory/deterministic-fact-str {:a 1 :b 2} serialize/prune-fns)
           (#'memory/deterministic-fact-str {:b 2 :a 1} serialize/prune-fns))
        "key order must not affect the canonical string"))

  (testing "determinism: same set in different element orders → identical strings"
    (is (= (#'memory/deterministic-fact-str {:s #{1 2 3}} serialize/prune-fns)
           (#'memory/deterministic-fact-str {:s #{3 1 2}} serialize/prune-fns))
        "set element order must not affect the canonical string")))

(deftest test-accumulator-fact-extraction
  (testing "Accumulator results (like vectors) are not treated as facts"
    (let [app (laf/map->Application {:app-id "app-1"})
          given-doc (laf/map->GivenDocument {:app-id "app-1" :doc-type :id})
          session (-> (->test-session)
                      (r/insert app given-doc)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          fact-types (:fact-types memory-analysis)]
      ;; The memory-analysis should NOT contain PersistentVector as a fact type
      (is (nil? (get fact-types "clojure.lang.PersistentVector")) "PersistentVector should not be in fact types")
      (is (nil? (get fact-types "java.lang.Boolean")) "Boolean should not be in fact types")
      (is (some? (get fact-types "clara.explorer.test.rules.loan_app_facts.AllGivenDocuments"))))))

(deftest test-accumulator-input-facts-retained
  (testing "A root fact read only by an accumulator condition is present, not dropped as retracted"
    (let [session (-> (->test-session)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card}))
                      (r/fire-rules))
          analysis (memory/->memory-analysis session)
          fact-type-names (set (map (comp :name :type) (vals (:facts analysis))))]
      (is (contains? fact-type-names "clara.explorer.test.rules.loan_app_facts.RequiredDocument")
          "RequiredDocument (accumulator :from input) must be retained in :facts")
      (is (contains? fact-type-names "clara.explorer.test.rules.loan_app_facts.Application")))))

(deftest test-session-id-indexes
  (testing "Memory-analyses expose id→name reverse indexes that resolve every id"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)]
      (doseq [name (keys (:rule-matches memory-analysis))]
        (is (= name (get (:rule-id-index memory-analysis)
                         (serialize/route-id (str name))))
            (str "rule-id-index resolves " (serialize/route-id (str name)) " back to " name)))
      (doseq [name (keys (:query-matches memory-analysis))]
        (is (= name (get (:query-id-index memory-analysis)
                         (serialize/route-id (str name))))
            (str "query-id-index resolves " (serialize/route-id (str name)) " back to " name)))))

  (testing "A session route-id collision throws at memory-analysis-build time"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'memory/->id-name-index ["same" "same"])))))

(deftest test-session-fact-known-parity
  (testing "Session fact-type known flags honestly reflect membership in the analysis's fact-type names"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          analysis (core/->rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/->props-layer session)]))
          known-set (set (keys (:fact-types analysis)))
          memory-analysis (memory/->memory-analysis session known-set)
          fact-types (map :type (vals (:facts memory-analysis)))]
      (is (seq fact-types) "Memory analysis should contain facts")
      (doseq [{type-name :name type-known :known} fact-types]
        (is (= (contains? known-set type-name) type-known)
            (str "known flag for " type-name " must equal analysis membership")))))

  (testing "Without a known-set every session fact type is unknown"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)]
      (is (every? (comp false? :known :type) (vals (:facts memory-analysis)))
          "Default memory-analysis marks no session fact type known"))))

(deftest test-update-memory-analysis-known-set
  (testing "update-memory-analysis-known-set re-stamps :known to match a fresh analysis-derived memory-analysis"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          analysis (core/->rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/->props-layer session)]))
          known-set (set (keys (:fact-types analysis)))
          ;; The reuse path: enrichment builds a 1-arity memory-analysis (all unknown).
          enrichment-memory-analysis (memory/->memory-analysis session)]
      (is (every? (comp false? :known :type) (vals (:facts enrichment-memory-analysis)))
          "enrichment memory-analysis starts with every fact type unknown")
      (let [re-stamped (memory/update-memory-analysis-known-set enrichment-memory-analysis known-set)
            fresh      (memory/->memory-analysis session known-set)]
        (is (= fresh re-stamped)
            "re-stamped memory-analysis must equal a freshly-built analysis-derived memory-analysis")))))

(deftest test-memory-analysis-raw-types
  (testing "Memory analysis exposes fact-id → raw type for the enrichment boundary"
    (let [app (laf/map->Application {:app-id "app-1"})
          req-doc (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
          given-doc (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
          session (-> (->test-session)
                      (r/insert app req-doc given-doc)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          raw-types (:fact-raw-types memory-analysis)]
      (is (seq raw-types))
      (is (some (comp class? val) raw-types) "record facts map to their Class object")
      (is (some (comp keyword? val) raw-types)
          "tagged facts (e.g. :loan-doc-rules/document-check-input) map to their keyword — never a string")
      ;; Every fact's raw type re-serializes to the served :type :name
      (doseq [[id fact] (:facts memory-analysis)]
        (is (= (get-in fact [:type :name])
               (serialize/serialize-fact-type nil (get raw-types id)))
            (str "raw type of fact " id " re-serializes to its served :type :name"))))))

(deftest test-memory-analysis-id-parity
  (testing "Session fact-type ids use the same route-id(name) function as the analysis side"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          analysis (core/->rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/->props-layer session)]))
          analysis-types (:fact-types analysis)]
      (doseq [{type-name :name type-id :id} (vals (:fact-types memory-analysis))]
        (is (= (serialize/route-id type-name) type-id)
            (str "session id for " type-name " is route-id(name)"))
        ;; Session facts include runtime-inserted types the analysis does not
        ;; know (dynamic inserts); ids still agree wherever both surfaces
        ;; cover the same type.
        (when-let [analysis-type (get analysis-types type-name)]
          (is (= (:id analysis-type) type-id)
              (str "session id for " type-name " matches the analysis id")))))))

(deftest test-nil-excluded-from-all-facts
  (testing "Nil facts inserted via insert-all! are excluded from :all-facts by fact-visible?"
    (let [fact (nil-safety/->NilSafetyFact "f1")
          session (-> (r/mk-session 'clara.explorer.test.rules.nil-safety-test-rules)
                      (r/insert fact)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          facts (:facts memory-analysis)]

      ;; Memory analysis builds without throwing
      (is (map? memory-analysis))
      (is (seq facts) "Memory analysis should contain the triggering fact")

      ;; Nil should not appear — it was filtered at get-wrapped-fact-groups
      (let [nil-facts (filterv (fn [[_id f]]
                                 (nil? (:data f)))
                               facts)]
        (is (empty? nil-facts)
            "Nil facts should be excluded from the memory-analysis")))))

(deftest test-nil-inserting-rule-memory-analysis
  (testing "A rule that inserts nil still appears in rule-matches with clean entries"
    (let [fact (nil-safety/->NilSafetyFact "f1")
          session (-> (r/mk-session 'clara.explorer.test.rules.nil-safety-test-rules)
                      (r/insert fact)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          entry (some (fn [[p-name m]]
                        (when (= "nil-insertion-rule" (name (symbol (str p-name)))) m))
                      (:rule-matches memory-analysis))]
      (is (some? entry) "the nil-inserting rule must still appear in the rule-match index")
      (is (every? some? (:inserted-facts entry))
          "a fact with no memory-analysis entry is absent, never present as nil")
      (is (every? some? (:matches entry))))))

(deftest test-equal-facts-attributed-to-their-own-inserting-rule
  (testing "Two rules inserting equal-but-distinct facts each claim their own"
    (let [session (-> (r/mk-session 'clara.explorer.test.rules.equal-fact-test-rules)
                      (r/insert (equal-facts/->Seed 1))
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          derived (->> (:facts memory-analysis)
                       (filter (fn [[_id f]]
                                 (str/includes? (get-in f [:type :name]) "Derived"))))
          derived-ids (set (map key derived))
          claimed (into {}
                        (map (fn [[p-name m]]
                               [(name (symbol (str p-name))) (mapv :id (:inserted-facts m))]))
                        (:rule-matches memory-analysis))
          claimed-ids (mapcat val claimed)]

      (is (= 2 (count derived-ids)) "both equal facts are distinct in the memory-analysis")
      (is (= (sort claimed-ids) (distinct (sort claimed-ids)))
          "no fact is claimed by more than one rule")
      (is (= derived-ids (set claimed-ids))
          "and no fact is orphaned")

      (testing "the same holds for :inserted-from on the facts themselves"
        (is (every? (fn [[_id f]] (= 1 (count (:inserted-from f)))) derived)
            "each fact names exactly the rule that inserted it")))))

(deftest test-unknown-fact-type-substitution
  (testing "fact-type-fn returning nil for a non-nil fact — substitutes unknown-fact-type"
    (let [fact (nil-safety/->NilSafetyFact "f1")
          ;; Custom fact-type-fn that returns nil for everything
          nil-ft-fn (constantly nil)
          session (-> (r/mk-session 'clara.explorer.test.rules.nil-safety-test-rules
                                    :fact-type-fn nil-ft-fn)
                      (r/insert fact)
                      (r/fire-rules))
          memory-analysis (memory/->memory-analysis session)
          facts (:facts memory-analysis)]

      (is (map? memory-analysis))
      (is (seq facts) "Memory analysis should contain the fact")

      ;; The fact should get the unknown-fact-type sentinel
      (is (every? (fn [[_id f]]
                    (= (get-in f [:type :name])
                       ":clara.explorer.analyze/unknown-fact-type"))
                  facts)
          "All facts should have unknown-fact-type since fact-type-fn returns nil")

      (let [[_id f] (first facts)]
        (is (false? (get-in f [:type :known]))
            "Unknown type should be known: false")
        (is (some? (get-in f [:type :id]))
            "Should have a deterministically generated route-id")))))

(deftest test-nil-insertion-analysis-no-crash
  (testing "Full pipeline: rule that inserts nil → analysis does not crash"
    (let [fact (nil-safety/->NilSafetyFact "f1")
          session (-> (r/mk-session 'clara.explorer.test.rules.nil-safety-test-rules)
                      (r/insert fact)
                      (r/fire-rules))
          analysis (core/->rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/->props-layer session)]))]
      (is (map? analysis))
      (is (contains? analysis :rules))
      (is (contains? analysis :fact-types)))))

;; ---------------------------------------------------------------------------
;; Working-memory relations — the memory-relations-test-rules fixture
;; ---------------------------------------------------------------------------

(def ^:private mrr-type-ns
  "Class-name namespace prefix for the memory-relations fixture record types
   (hyphens in the ns munge to underscores in the record class name)."
  "clara.explorer.test.rules.memory_relations_test_rules.")

(defn- ->memory-relations-session
  "Builds the memory-relations fixture session: both applications, the
   negated/blocking facts, a failed document check, and a review close that
   retracts app-1's task."
  []
  (-> (r/mk-session 'clara.explorer.test.rules.memory-relations-test-rules)
      (r/insert (mrr/->Application "app-1" 7)
                (mrr/->Application "app-2" 7)
                (mrr/->ManualHold :hold)
                (mrr/->MissingDocument "app-1" :paystub)
                (mrr/->LoanOffer "app-1" 9)
                (mrr/->LoanOffer "app-2" 5)
                (mrr/->DocumentCheck "app-1" :failed)
                (mrr/->ReviewClosed "app-1"))
      (r/fire-rules)))

(defn- find-mrr-fact-id
  "Finds a fixture fact's id by its short record-type name and a `:data` predicate."
  [memory-analysis short-type pred]
  (some (fn [[id fact]]
          (when (and (= (str mrr-type-ns short-type) (get-in fact [:type :name]))
                     (pred (:data fact)))
            id))
        (:facts memory-analysis)))

(defn- fact-relation-names
  "Short production names of a fact's `rel-key` relation, in order."
  [memory-analysis fact-id rel-key]
  (mapv (fn [dep] (last (str/split (:name dep) #"/")))
        (get-in memory-analysis [:facts fact-id rel-key])))

(defn- ->memory-relations-facts
  "Returns `{:analysis …}` plus each fixture fact's id, keyed by label."
  []
  (let [analysis (memory/->memory-analysis (->memory-relations-session))
        find-id (partial find-mrr-fact-id analysis)]
    {:analysis           analysis
     :application-1      (find-id "Application" #(= "app-1" (:app-id %)))
     :application-2      (find-id "Application" #(= "app-2" (:app-id %)))
     :manual-hold        (find-id "ManualHold" (constantly true))
     :missing-document-1 (find-id "MissingDocument" #(= "app-1" (:app-id %)))
     :loan-offer-9       (find-id "LoanOffer" #(= 9 (:apr %)))
     :loan-offer-5       (find-id "LoanOffer" #(= 5 (:apr %)))
     :document-check-1   (find-id "DocumentCheck" #(= "app-1" (:app-id %)))
     :review-closed-1    (find-id "ReviewClosed" (constantly true))
     :review-task-2      (find-id "ReviewTask" #(= "app-2" (:app-id %)))}))

(deftest test-memory-relations-matches-condition-of
  (let [{:keys [analysis application-1 application-2 document-check-1
                review-closed-1 review-task-2]}
        (->memory-relations-facts)]
    (is (some? application-1))
    (is (some? application-2))

    (testing "Application matches the condition of all seven productions that read it"
      (let [expected #{"ready-for-review" "documents-complete" "offers-within-limit"
                       "document-check-passed" "audit-application" "open-review-task"
                       "applications-without-hold"}]
        (is (= expected (set (fact-relation-names analysis application-1 :matches-condition-of))))
        (is (= (fact-relation-names analysis application-1 :matches-condition-of)
               (fact-relation-names analysis application-2 :matches-condition-of))
            "both applications match the same conditions")))

    (testing "DocumentCheck reaches document-check-passed's join (the :test fails after)"
      (is (= ["document-check-passed"]
             (fact-relation-names analysis document-check-1 :matches-condition-of))))

    (testing "ReviewClosed and the surviving ReviewTask match close-review-task"
      (is (= ["close-review-task"]
             (fact-relation-names analysis review-closed-1 :matches-condition-of)))
      (is (= ["close-review-task"]
             (fact-relation-names analysis review-task-2 :matches-condition-of))))))

(deftest test-memory-relations-blocks
  (let [{:keys [analysis manual-hold missing-document-1 loan-offer-9 loan-offer-5]}
        (->memory-relations-facts)]
    (testing "ManualHold blocks ready-for-review and the hold query"
      (is (= ["ready-for-review" "applications-without-hold"]
             (fact-relation-names analysis manual-hold :blocks-condition-of))))
    (testing "MissingDocument blocks only its own application's rule"
      (is (= ["documents-complete"]
             (fact-relation-names analysis missing-document-1 :blocks-condition-of))))
    (testing "LoanOffer over the limit blocks; under the limit is only a candidate"
      (is (= ["offers-within-limit"]
             (fact-relation-names analysis loan-offer-9 :blocks-condition-of)))
      (is (= ["offers-within-limit"]
             (fact-relation-names analysis loan-offer-5 :blocking-candidate-of))))))

(deftest test-memory-relations-supports-insertions-of
  (let [{:keys [analysis application-1 application-2]} (->memory-relations-facts)]
    (testing "app-1's only activation (open-review-task) had its insertion retracted"
      (is (= [] (fact-relation-names analysis application-1 :supports-insertions-of))))
    (testing "app-2 supports every rule that fired and left a retained insertion"
      (is (= ["documents-complete" "offers-within-limit" "open-review-task"]
             (fact-relation-names analysis application-2 :supports-insertions-of))))))

(deftest test-memory-relations-blocks-disjoint
  (let [{:keys [analysis]} (->memory-relations-facts)]
    (doseq [[_id fact] (:facts analysis)]
      (is (empty? (filter (set (:blocks-condition-of fact)) (:blocking-candidate-of fact)))
          "blocks-condition-of and blocking-candidate-of are disjoint for every fact"))))

(deftest test-memory-relations-retracted-fact-absent
  (let [{:keys [analysis]} (->memory-relations-facts)
        review-task-1 (find-mrr-fact-id analysis "ReviewTask" #(= "app-1" (:app-id %)))
        review-task-2 (find-mrr-fact-id analysis "ReviewTask" #(= "app-2" (:app-id %)))]
    (is (nil? review-task-1) "ReviewTask app-1 was retracted and is absent from :facts")
    (is (some? review-task-2) "ReviewTask app-2 is present")))

(deftest test-memory-relations-clean-session-split
  (testing "Without negated, failed or retracted facts the two keys split cleanly by production type"
    (let [session (-> (r/mk-session 'clara.explorer.test.rules.memory-relations-test-rules)
                      (r/insert (mrr/->Application "app-1" 7)
                                (mrr/->Application "app-2" 7))
                      (r/fire-rules))
          analysis (memory/->memory-analysis session)]
      (doseq [[_id fact] (:facts analysis)]
        (is (every? #(= "rule" (:type %)) (:supports-insertions-of fact))
            "supports-insertions-of carries only rules")
        (is (every? #(= "query" (:type %)) (:supports-results-of fact))
            "supports-results-of carries only queries")
        (is (empty? (:blocks-condition-of fact)) "no negation blocks a clean session")
        (is (empty? (:blocking-candidate-of fact))
            "no negation candidates in a clean session")))))
