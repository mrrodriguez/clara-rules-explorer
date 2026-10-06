(ns clara.explorer.memory.inspect-test
  "Tests for the working-memory inspection primitives in
   `clara.explorer.memory.inspect`."
  (:require [clara.rules :as r]
            [clara.rules.platform :as platform]
            [clara.explorer.memory.inspect :as inspect]
            [clara.explorer.test.rules.accumulator-relations-test-rules :as accr]
            [clara.explorer.test.rules.loan-app-facts :as laf]
            [clara.explorer.test.rules.loan-doc-rules]
            [clara.explorer.test.rules.memory-relations-test-rules :as mrr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st])
  (:import [clara.explorer.test.rules.accumulator_relations_test_rules
            Start
            AccumSource]
           [clara.explorer.test.rules.memory_relations_test_rules
            Application
            ManualHold
            LoanOffer
            ReviewTask
            Marker
            AuditEntry]
           [clara.explorer.test.rules.loan_app_facts
            RequiredDocument]))

(use-fixtures :once st/validate-schemas)

;; --- helpers ---------------------------------------------------------------

(defn- ->relations-session
  "The memory-relations fixture session: both applications, blocking facts, a
   failed document check, and a review close that retracts app-1's task."
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

(defn- ->clean-session
  "Just the two applications — no negation, failed or retracted facts."
  []
  (-> (r/mk-session 'clara.explorer.test.rules.memory-relations-test-rules)
      (r/insert (mrr/->Application "app-1" 7)
                (mrr/->Application "app-2" 7))
      (r/fire-rules)))

(defn- ->accumulator-session
  "The accumulator-relations fixture session: one Start that seeds an inserted
   accumulator-input fact read only by accumulator `:from` conditions."
  []
  (-> (r/mk-session 'clara.explorer.test.rules.accumulator-relations-test-rules)
      (r/insert (accr/->Start :g1 0))
      (r/fire-rules)))

(defn- short-name
  "Short production name (after the last `/`) of a rule/query record."
  [p]
  (last (str/split (str (:name p)) #"/")))

(defn- entry->fact
  "Unwraps the `:fact` of a support/relation entry."
  [entry]
  (platform/fact-id-unwrap (:fact entry)))

(defn- fact-entry-names
  "Short production names of `rel` entries whose fact satisfies `pred`."
  [relations rel pred]
  (->> (get relations rel)
       (filter #(pred (entry->fact %)))
       (map (comp short-name :production))
       set))

;; --- condition-match->facts ----------------------------------------------------------

(deftest test-condition-match->facts
  (testing "a non-accumulator condition contributes its own fact"
    (is (= [:f] (inspect/condition-match->facts {:fact :f :condition {:type :T}}))))
  (testing "an accumulator condition contributes the inputs, not the result"
    (is (= [:a :b] (inspect/condition-match->facts {:fact :result
                                                    :condition {:accumulator :all}
                                                    :facts-accumulated [:a :b]})))))

;; --- get-all-facts / get-root-facts ----------------------------------------

(deftest test-get-all-facts
  (let [facts (inspect/get-all-facts (->relations-session))]
    (testing "a retracted fact is not in working memory"
      (is (not-any? #(and (instance? ReviewTask %) (= "app-1" (:app-id %))) facts)))
    (testing "a surviving inserted fact is present"
      (is (some #(and (instance? ReviewTask %) (= "app-2" (:app-id %))) facts)))
    (testing "root facts are present"
      (is (some #(and (instance? Application %) (= "app-1" (:app-id %))) facts))
      (is (some #(instance? ManualHold %) facts)))))

(deftest test-get-all-facts-retains-accumulator-input
  (testing "a root fact read only by an accumulator is in working memory"
    (let [session (-> (r/mk-session 'clara.explorer.test.rules.loan-doc-rules)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card}))
                      (r/fire-rules))
          facts (inspect/get-all-facts session)]
      (is (some #(and (instance? RequiredDocument %) (= "app-1" (:app-id %))) facts)
          "RequiredDocument lives in accum-memory, not element memory"))))

(deftest test-get-root-facts
  (let [roots (inspect/get-root-facts (->relations-session))]
    (testing "only externally-inserted facts are roots"
      (is (= 8 (count roots)))
      (is (not-any? #(or (instance? ReviewTask %)
                         (instance? Marker %)
                         (instance? AuditEntry %))
                    roots)))
    (testing "the externally-inserted facts are present"
      (is (some #(and (instance? Application %) (= "app-1" (:app-id %))) roots))
      (is (some #(instance? ManualHold %) roots)))))

;; --- get-insertions / get-rule-matches / get-query-matches -----------------

(deftest test-get-insertions
  (let [insertions (inspect/get-insertions (->relations-session))
        open-review (first (filter #(= "open-review-task" (short-name %)) (keys insertions)))]
    (is (some? open-review) "open-review-task is a key")
    (let [entries (get insertions open-review)]
      (testing "both ReviewTasks recorded, even the retracted one"
        (is (= #{"app-1" "app-2"} (set (map (comp :app-id :fact) entries)))))
      (testing "each entry carries an explanation"
        (is (every? #(contains? % :explanation) entries))))))

(deftest test-get-rule-matches
  (let [rule-matches (inspect/get-rule-matches (->relations-session))
        open-review (first (filter #(= "open-review-task" (short-name %)) (keys rule-matches)))]
    (is (some? open-review) "open-review-task is a key")
    (is (= 2 (count (get rule-matches open-review)))
        "one activation per application")
    (is (every? #(and (vector? (:matches %)) (map? (:bindings %)))
                (get rule-matches open-review)))))

(deftest test-get-query-matches
  (let [query-matches (inspect/get-query-matches (->clean-session))
        query (first (filter #(= "applications-without-hold" (short-name %)) (keys query-matches)))]
    (is (some? query) "applications-without-hold is a key")
    (is (= 2 (count (get query-matches query)))
        "both applications pass the negation when no hold is present")))

;; --- supports ---------------------------------------------------------

(deftest test-rule-insertion-supports
  (let [entries (inspect/->rule-insertion-supports (->relations-session))
        supports-open-review? (fn [entry]
                                (and (instance? Application (entry->fact entry))
                                     (= "open-review-task" (short-name (:production entry)))))]
    (testing "a retracted insertion no longer supports its activation"
      (is (not-any? #(and (= "app-1" (:app-id (entry->fact %)))
                          (supports-open-review? %))
                    entries)))
    (testing "a retained insertion supports its activation"
      (is (some #(and (= "app-2" (:app-id (entry->fact %)))
                      (supports-open-review? %))
                entries)))
    (testing "every entry is a rule"
      (is (every? #(= "rule" (:type %)) entries)))))

(deftest test-query-result-supports
  (let [entries (inspect/->query-result-supports (inspect/get-query-matches (->clean-session)))]
    (testing "each application supports the query result"
      (is (= #{"app-1" "app-2"}
             (->> entries
                  (map entry->fact)
                  (filter #(instance? Application %))
                  (map :app-id)
                  set))))
    (testing "every entry is a query"
      (is (every? #(= "query" (:type %)) entries)))))

(deftest test-accumulator-relations
  (let [session (->accumulator-session)
        facts (inspect/get-all-facts session)
        relations (inspect/->beta-node-relations session)
        accum-source? #(instance? AccumSource %)
        start? #(instance? Start %)]
    (testing "an inserted accumulator-input fact is retained in working memory"
      (is (some #(and (instance? AccumSource %) (= :g1 (:group %))) facts)))
    (testing "accumulator :from inputs are matches-condition-of for both node types"
      (is (= #{"accumulate-sources" "accumulate-over-threshold"}
             (fact-entry-names relations :matches-condition-of accum-source?))))
    (testing "the accumulator rule's first condition is also matched"
      (is (= #{"insert-accum-source" "accumulate-sources" "accumulate-over-threshold"}
             (fact-entry-names relations :matches-condition-of start?))))))

(deftest test-rule-insertion-supports-include-accumulator-inputs
  (let [entries (inspect/->rule-insertion-supports (->accumulator-session))]
    (testing "accumulator :from inputs support the rules that accumulated them"
      (is (= #{"accumulate-sources" "accumulate-over-threshold"}
             (->> entries
                  (filter #(instance? AccumSource (entry->fact %)))
                  (map (comp short-name :production))
                  set))))))

;; --- beta-node relations --------------------------------------------------------

(deftest test-beta-node-relations
  (let [relations (inspect/->beta-node-relations (->relations-session))
        manual-hold? #(instance? ManualHold %)
        loan-9? #(and (instance? LoanOffer %) (= 9 (:apr %)))
        loan-5? #(and (instance? LoanOffer %) (= 5 (:apr %)))
        application-1? #(and (instance? Application %) (= "app-1" (:app-id %)))]
    (testing "a no-join negation blocks both the rule and the query"
      (is (= #{"ready-for-review" "applications-without-hold"}
             (fact-entry-names relations :blocks-condition-of manual-hold?))))
    (testing "an over-limit offer blocks; an under-limit one is only a candidate"
      (is (= #{"offers-within-limit"}
             (fact-entry-names relations :blocks-condition-of loan-9?)))
      (is (= #{"offers-within-limit"}
             (fact-entry-names relations :blocking-candidate-of loan-5?))))
    (testing "Application matches the condition of all seven productions that read it"
      (is (= #{"ready-for-review" "documents-complete" "offers-within-limit"
               "document-check-passed" "audit-application" "open-review-task"
               "applications-without-hold"}
             (fact-entry-names relations :matches-condition-of application-1?))))
    (testing "every entry carries a fact, a production, and a kind"
      (doseq [rel [:matches-condition-of :blocks-condition-of :blocking-candidate-of]
              entry (get relations rel)]
        (is (contains? entry :fact))
        (is (contains? entry :production))
        (is (#{"rule" "query"} (:type entry)))))))
