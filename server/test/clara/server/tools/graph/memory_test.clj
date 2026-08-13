(ns clara.server.tools.graph.memory-test
  (:require [clara.rules :as r]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clara.server.tools.graph.rules.nil-safety-test-rules :as nil-safety]
            [clara.server.tools.graph.rules.equal-fact-test-rules :as equal-facts]
            [clara.server.tools.graph.rules.match-uniqueness-test-rules :as mu]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- ->test-session
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(deftest test-monotonic-fact-ids
  (testing "Facts are assigned monotonic IDs in a deterministic order"
    (let [app-1 (laf/map->Application {:app-id "app-1"})
          app-2 (laf/map->Application {:app-id "app-2"})
          session (-> (->test-session)
                      (r/insert app-1 app-2)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          facts (:facts snapshot)
          ids (keys facts)]

      (is (seq ids) "Snapshot should contain facts")
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
          snapshot (memory/session-snapshot session)
          facts (:facts snapshot)
          app-data (serialize/prune-fns app-a)
          instances (filter #(= (:data (val %)) app-data) facts)]

      (is (= 2 (count instances)) "Both equal facts should be in the snapshot")
      (is (not= (first (keys instances)) (second (keys instances))) "They must have different IDs"))))

(deftest test-used-by-index
  (testing "Used-by index correctly identifies rules using a fact"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          app-data (serialize/prune-fns app)
          fact-id (some (fn [[id f]] (when (= (:data f) app-data) id)) (:facts snapshot))
          used-by (get-in snapshot [:used-by fact-id])]

      (is (seq used-by) "Fact should be used by some rules/queries")
      (is (some #(= (:name %) "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs") used-by)))))

(deftest test-origin-map
  (testing "Origin map correctly identifies the rule that inserted a fact"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          ;; Find a fact that was inserted by a rule, e.g., AllRequiredDocuments
          inserted-fact-entry (some (fn [[id f]]
                                      (when (= (:type f) "clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments")
                                        [id f]))
                                    (:facts snapshot))]

      (when inserted-fact-entry
        (let [[id _] inserted-fact-entry
              origins (get-in snapshot [:origin id])]
          (is (seq origins) "Inserted fact should have an origin")
          (is (= "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs" (:name (first origins)))))))))

(deftest test-enriched-snapshot
  (testing "Snapshot contains enriched fact-table and rule-centric groupings"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)

          ;; 1. Verify Enriched Fact Table
          app-data (serialize/prune-fns app)
          fact (some #(when (= (:data %) app-data) %) (vals (:facts snapshot)))]
      (is (some? fact))
      (is (vector? (:inserted-from fact)))
      (is (vector? (:used-by fact)))
      (is (empty? (:inserted-from fact)) "Root fact should have no origins in origin-map")
      (is (seq (:used-by fact)) "Fact should be used by rules/queries")

      ;; 2. Verify Rule-Centric Index
      (let [type-info (get-in snapshot [:fact-types "clara.server.tools.graph.rules.loan_app_facts.Application"])]
        (is (seq (:inserted-from type-info)) "Type info should have rule-centric inserted-from")
        (is (= "Root Facts (External)" (:name (first (:inserted-from type-info)))))
        (is (= "root" (:type (first (:inserted-from type-info)))))
        (is (seq (:used-by type-info)) "Type info should have rule-centric used-by")
        (let [usage (first (:used-by type-info))]
          (is (string? (:name usage)))
          (is (string? (:type usage)))
          (is (seq (:facts usage))))))))

(deftest test-rule-query-activity
  (testing "Snapshot contains rule and query activity (inserted facts and matches)"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)

          ;; 1. Verify Rule Activity
          rule-name "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
          rule-info (get-in snapshot [:rule-matches rule-name])]
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
          (is (vector? (:used-by fact)) "Match fact should have :used-by")))

      ;; 2. Verify Query Activity
      (let [query-name "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
            query-info (get-in snapshot [:query-matches query-name])]
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
  (testing "Multi-fact rule matches are flattened to one SessionFact entry per fact-id"
    (let [app (laf/map->Application {:app-id "app-1"})
          req-doc (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
          given-doc (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
          session (-> (->test-session)
                      (r/insert app req-doc given-doc)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)

          rule-name "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"
          rule-info (get-in snapshot [:rule-matches rule-name])
          matches (:matches rule-info)]

      (is (some? rule-info) "app-has-all-required-docs should exist in rule-matches")
      (is (= 2 (count matches))
          "Should have 2 match entries (Application + document-check-input), not 1 with fact-ids")
      (is (every? #(contains? (:fact %) :id) matches) "Every match fact should have :id")
      (is (every? #(contains? (:fact %) :type) matches) "Every match fact should have :type")
      (is (every? #(contains? (:fact %) :data) matches) "Every match fact should have :data")
      (is (every? #(contains? (:fact %) :is-root) matches) "Every match fact should have :is-root")
      (is (every? #(contains? (:fact %) :inserted-from) matches) "Every match fact should have :inserted-from")
      (is (every? #(contains? (:fact %) :used-by) matches) "Every match fact should have :used-by")
      ;; Verify types match the actual facts
      (let [types (set (map (comp :name :type :fact) matches))]
        (is (contains? types "clara.server.tools.graph.rules.loan_app_facts.Application")
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

(defn- ->match-uniqueness-snapshot
  []
  (-> (r/mk-session 'clara.server.tools.graph.rules.match-uniqueness-test-rules)
      (r/insert (mu/->Config "c1") (mu/->Item "a") (mu/->Item "b") (mu/->Item nil))
      (r/fire-rules)
      (memory/session-snapshot)))

(defn- mu-rule-matches
  [snapshot short-name]
  (get-in snapshot [:rule-matches
                    (str "clara.server.tools.graph.rules.match-uniqueness-test-rules/" short-name)
                    :matches]))

(def ^:private mu-item-type-name
  "clara.server.tools.graph.rules.match_uniqueness_test_rules.Item")

(def ^:private mu-config-type-name
  "clara.server.tools.graph.rules.match_uniqueness_test_rules.Config")

(deftest test-match-uniqueness-case-a
  (testing "One fact satisfying two conditions of one activation yields one row with one binding set"
    (let [snapshot (->match-uniqueness-snapshot)
          matches (mu-rule-matches snapshot "overlapping-conditions")
          item-matches (filter #(= mu-item-type-name (get-in % [:fact :type :name])) matches)]
      (is (= 3 (count item-matches)) "all three items appear, once each")
      (is (= [1 2 3] (mapv (comp :id :fact) item-matches)) "rows are sorted by fact id")
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
    (let [snapshot (->match-uniqueness-snapshot)
          matches (mu-rule-matches snapshot "pairwise")
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
    (let [snapshot (->match-uniqueness-snapshot)
          matches (mu-rule-matches snapshot "combined")
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
    (let [snapshot (->match-uniqueness-snapshot)]
      (doseq [[p-name {:keys [matches]}] (:rule-matches snapshot)]
        (is (= (count matches) (count (distinct (map (comp :id :fact) matches))))
            (str "rule " p-name " has distinct match fact ids")))
      (doseq [[p-name {:keys [matches]}] (:query-matches snapshot)]
        (is (= (count matches) (count (distinct (map (comp :id :fact) matches))))
            (str "query " p-name " has distinct match fact ids")))
      ;; The query mirrors pairwise: Config appears once with three binding sets.
      (let [query-matches (get-in snapshot [:query-matches
                                            "clara.server.tools.graph.rules.match-uniqueness-test-rules/find-pairs"
                                            :matches])
            config-match (first (filter #(= mu-config-type-name (get-in % [:fact :type :name])) query-matches))]
        (is (some? config-match) "find-pairs query has a Config match")
        (is (= 3 (count (:bindings config-match)))
            "queries get the same group-and-collect treatment as rules")))))

(deftest test-match-uniqueness-ordering-stability
  (testing "Match rows and binding sets are ordered deterministically across identical sessions"
    (let [build (fn []
                  (-> (r/mk-session 'clara.server.tools.graph.rules.match-uniqueness-test-rules)
                      (r/insert (mu/->Config "c1") (mu/->Item "a") (mu/->Item "b") (mu/->Item nil))
                      (r/fire-rules)
                      (memory/session-snapshot)))
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

          ;; Create two snapshots of identical sessions
          make-snapshot (fn []
                          (-> (->test-session)
                              (r/insert app-1 app-2)
                              (r/fire-rules)
                              (memory/session-snapshot)))

          snapshot-1 (make-snapshot)
          snapshot-2 (make-snapshot)

          ;; Strip volatile fields (e.g. timestamps) from fact data for comparison
          strip-volatile (fn [data]
                           (if (and (map? data) (:timestamp data) (:action data))
                             (dissoc data :timestamp)
                             data))]

      (is (= (keys (:facts snapshot-1)) (keys (:facts snapshot-2))) "ID keys should be identical")
      (is (= (set (map (comp strip-volatile :data) (vals (:facts snapshot-1))))
             (set (map (comp strip-volatile :data) (vals (:facts snapshot-2))))) "Fact data set should be identical"))))

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
          snapshot (memory/session-snapshot session)
          fact-types (:fact-types snapshot)]
      ;; The snapshot should NOT contain PersistentVector as a fact type
      (is (nil? (get fact-types "clojure.lang.PersistentVector")) "PersistentVector should not be in fact types")
      (is (nil? (get fact-types "java.lang.Boolean")) "Boolean should not be in fact types")
      (is (some? (get fact-types "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"))))))

(deftest test-session-id-indexes
  (testing "Session snapshots expose id→name reverse indexes that resolve every id"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)]
      (doseq [name (keys (:rule-matches snapshot))]
        (is (= name (get (:rule-id-index snapshot)
                         (serialize/route-id (str name))))
            (str "rule-id-index resolves " (serialize/route-id (str name)) " back to " name)))
      (doseq [name (keys (:query-matches snapshot))]
        (is (= name (get (:query-id-index snapshot)
                         (serialize/route-id (str name))))
            (str "query-id-index resolves " (serialize/route-id (str name)) " back to " name)))))

  (testing "A session route-id collision throws at snapshot-build time"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'memory/build-id-name-index ["same" "same"])))))

(deftest test-session-fact-known-parity
  (testing "Session fact-type known flags honestly reflect membership in the analysis's fact-type names"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          analysis (core/rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/props-layer session)]))
          known-set (set (keys (:fact-types analysis)))
          snapshot (memory/session-snapshot session known-set)
          fact-types (map :type (vals (:facts snapshot)))]
      (is (seq fact-types) "Snapshot should contain facts")
      (doseq [{type-name :name type-known :known} fact-types]
        (is (= (contains? known-set type-name) type-known)
            (str "known flag for " type-name " must equal analysis membership")))))

  (testing "Without a known-set every session fact type is unknown"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)]
      (is (every? (comp false? :known :type) (vals (:facts snapshot)))
          "Default snapshot marks no session fact type known"))))

(deftest test-update-snapshot-known-set
  (testing "update-snapshot-known-set re-stamps :known to match a fresh analysis-derived snapshot"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          analysis (core/rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/props-layer session)]))
          known-set (set (keys (:fact-types analysis)))
          ;; The reuse path: enrichment builds a 1-arity snapshot (all unknown).
          enrichment-snapshot (memory/session-snapshot session)]
      (is (every? (comp false? :known :type) (vals (:facts enrichment-snapshot)))
          "enrichment snapshot starts with every fact type unknown")
      (let [re-stamped (memory/update-snapshot-known-set enrichment-snapshot known-set)
            fresh      (memory/session-snapshot-from-analysis session analysis)]
        (is (= fresh re-stamped)
            "re-stamped snapshot must equal a freshly-built analysis-derived snapshot")))))

(deftest test-snapshot-raw-types
  (testing "Snapshot exposes fact-id → raw type for the enrichment boundary"
    (let [app (laf/map->Application {:app-id "app-1"})
          req-doc (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
          given-doc (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
          session (-> (->test-session)
                      (r/insert app req-doc given-doc)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          raw-types (:fact-raw-types snapshot)]
      (is (seq raw-types))
      (is (some (comp class? val) raw-types) "record facts map to their Class object")
      (is (some (comp keyword? val) raw-types)
          "tagged facts (e.g. :loan-doc-rules/document-check-input) map to their keyword — never a string")
      ;; Every fact's raw type re-serializes to the served :type :name
      (doseq [[id fact] (:facts snapshot)]
        (is (= (get-in fact [:type :name])
               (serialize/serialize-fact-type nil (get raw-types id)))
            (str "raw type of fact " id " re-serializes to its served :type :name"))))))

(deftest test-session-analysis-id-parity
  (testing "Session fact-type ids use the same route-id(name) function as the analysis side"
    (let [app (laf/map->Application {:app-id "app-1"})
          session (-> (->test-session)
                      (r/insert app)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          analysis (core/rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/props-layer session)]))
          analysis-types (:fact-types analysis)]
      (doseq [{type-name :name type-id :id} (vals (:fact-types snapshot))]
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
          session (-> (r/mk-session 'clara.server.tools.graph.rules.nil-safety-test-rules)
                      (r/insert fact)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          facts (:facts snapshot)]

      ;; Snapshot builds without throwing
      (is (map? snapshot))
      (is (seq facts) "Snapshot should contain the triggering fact")

      ;; Nil should not appear — it was filtered at get-wrapped-fact-groups
      (let [nil-facts (filterv (fn [[_id f]]
                                 (nil? (:data f)))
                               facts)]
        (is (empty? nil-facts)
            "Nil facts should be excluded from the snapshot")))))

(deftest test-nil-inserting-rule-snapshot
  (testing "A rule that inserts nil still appears in rule-matches with clean entries"
    (let [fact (nil-safety/->NilSafetyFact "f1")
          session (-> (r/mk-session 'clara.server.tools.graph.rules.nil-safety-test-rules)
                      (r/insert fact)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          entry (some (fn [[p-name m]]
                        (when (= "nil-insertion-rule" (name (symbol (str p-name)))) m))
                      (:rule-matches snapshot))]
      (is (some? entry) "the nil-inserting rule must still appear in the rule-match index")
      (is (every? some? (:inserted-facts entry))
          "a fact with no snapshot entry is absent, never present as nil")
      (is (every? some? (:matches entry))))))

(deftest test-equal-facts-attributed-to-their-own-inserting-rule
  (testing "Two rules inserting equal-but-distinct facts each claim their own"
    (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.equal-fact-test-rules)
                      (r/insert (equal-facts/->Seed 1))
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          derived (->> (:facts snapshot)
                       (filter (fn [[_id f]]
                                 (str/includes? (get-in f [:type :name]) "Derived"))))
          derived-ids (set (map key derived))
          claimed (into {}
                        (map (fn [[p-name m]]
                               [(name (symbol (str p-name))) (mapv :id (:inserted-facts m))]))
                        (:rule-matches snapshot))
          claimed-ids (mapcat val claimed)]

      (is (= 2 (count derived-ids)) "both equal facts are distinct in the snapshot")
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
          session (-> (r/mk-session 'clara.server.tools.graph.rules.nil-safety-test-rules
                                    :fact-type-fn nil-ft-fn)
                      (r/insert fact)
                      (r/fire-rules))
          snapshot (memory/session-snapshot session)
          facts (:facts snapshot)]

      (is (map? snapshot))
      (is (seq facts) "Snapshot should contain the fact")

      ;; The fact should get the unknown-fact-type sentinel
      (is (every? (fn [[_id f]]
                    (= (get-in f [:type :name])
                       ":clara.tools.graph.analyze/unknown-fact-type"))
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
          session (-> (r/mk-session 'clara.server.tools.graph.rules.nil-safety-test-rules)
                      (r/insert fact)
                      (r/fire-rules))
          analysis (core/rulebase-analysis
                    session
                    (ann.merge/merge-layers [(ann.merge/props-layer session)]))]
      (is (map? analysis))
      (is (contains? analysis :rules))
      (is (contains? analysis :fact-types)))))
