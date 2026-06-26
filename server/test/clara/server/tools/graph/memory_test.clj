(ns clara.server.tools.graph.memory-test
  (:require [clara.rules :as r]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.test :refer [deftest is testing]]))

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
      ;; Matches are flattened SessionFact entries (one per fact-id)
      (when-let [match (first (:matches rule-info))]
        (is (int? (:id match)) "Match entry should have integer :id")
        (is (string? (:type match)) "Match entry should have :type")
        (is (map? (:data match)) "Match entry should have :data (bindings)")
        (is (contains? match :is-root) "Match entry should have :is-root")
        (is (vector? (:inserted-from match)) "Match entry should have :inserted-from")
        (is (vector? (:used-by match)) "Match entry should have :used-by"))

      ;; 2. Verify Query Activity
      (let [query-name "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
            query-info (get-in snapshot [:query-matches query-name])]
        (is (some? query-info) "Query info should exist in query-matches")
        (is (vector? (:matches query-info)) "Query should have matches vector")
        ;; Query matches are also SessionFact entries
        (when-let [qmatch (first (:matches query-info))]
          (is (int? (:id qmatch)) "Query match entry should have integer :id")
          (is (string? (:type qmatch)) "Query match entry should have :type")
          (is (map? (:data qmatch)) "Query match entry should have :data (bindings)"))))))

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
          "Should have 2 match entries (Application + DocumentCheckInput), not 1 with fact-ids")
      (is (every? #(contains? % :id) matches) "Every match entry should have :id")
      (is (every? #(contains? % :type) matches) "Every match entry should have :type")
      (is (every? #(contains? % :data) matches) "Every match entry should have :data")
      (is (every? #(contains? % :is-root) matches) "Every match entry should have :is-root")
      (is (every? #(contains? % :inserted-from) matches) "Every match entry should have :inserted-from")
      (is (every? #(contains? % :used-by) matches) "Every match entry should have :used-by")
      ;; Verify types match the actual facts
      (let [types (set (map :type matches))]
        (is (contains? types "clara.server.tools.graph.rules.loan_app_facts.Application")
            "Should include Application fact")
        (is (contains? types "clara.server.tools.graph.rules.loan_app_facts.DocumentCheckInput")
            "Should include DocumentCheckInput fact"))
      ;; Verify all match entries share the same bindings data
      (let [bindings (map :data matches)]
        (is (apply = bindings) "All match entries should share identical bindings")))))
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
        snapshot-2 (make-snapshot)]

    (is (= (keys (:facts snapshot-1)) (keys (:facts snapshot-2))) "ID keys should be identical")
    (is (= (map :data (vals (:facts snapshot-1)))
           (map :data (vals (:facts snapshot-2)))) "Fact data order should be identical")))

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
