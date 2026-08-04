(ns clara.server.graph.smoke-hierarchy-test
  "HTTP integration tests for the fact-type hierarchy features (ancestors,
   type-bridge `:match`, honest session `known` flags) over the loan-hierarchy
   fixture session — a different session from the loan-doc/loan-app demo, so
   these features are covered end-to-end without touching the demo data.
   Reuses the smoke-test helpers (get-*, run-smoke-test, run-hierarchy-rules);
   the server runs on its own port with its own :once fixture, keeping the
   main smoke tests' server untouched."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clara.server.graph.server :as server]
            [clara.server.graph.smoke-test :as st]))

(defn- with-hierarchy-server
  [f]
  (binding [st/*port* 19002]
    (st/run-smoke-test {:session-fn st/run-hierarchy-rules
                        :layers []})
    (try
      (f)
      (finally
        (server/stop!)))))

(use-fixtures :once with-hierarchy-server)

(def ^:private income-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document")

(def ^:private supporting-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document")

(def ^:private loan-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/loan-document")

(def ^:private base-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/base-document")

(deftest test-hierarchy-fact-type-ancestors
  (testing "Fact-type detail serves the ancestor chain with honest known flags"
    (let [ft (st/get-fact-type income-document)
          ancestors (get ft "ancestors")]
      (is (some? ft))
      (is (= [supporting-document loan-document base-document]
             (mapv #(get % "name") ancestors))
          "Descendant-first hierarchy order (supporting <: loan <: base)")
      (is (= [true true false]
             (mapv #(get % "known") ancestors))
          "supporting/loan are on an LHS (known); base-document is a ghost (known: false)"))))

(deftest test-hierarchy-tuple-fact-types
  (testing "Vector-tuple fact types are kind-explicit across the API"
    (let [names (set (map #(get % "name") (get (st/get-fact-types) "fact-types")))]
      (is (contains? names "[:loan/status \"verified\"]"))
      (is (contains? names "[:document/flag \"income-mismatch\"]")))))

(deftest test-hierarchy-type-bridge-match
  (testing "The type-bridge :match links insert-income-document → review-supporting-document"
    (let [rule (st/get-rule (str "clara.server.tools.graph.rules.loan-hierarchy-rules/"
                                 "insert-income-document"))
          downstream (get rule "downstream")
          bridge (first (filter #(= "clara.server.tools.graph.rules.loan-hierarchy-rules/review-supporting-document"
                                    (get % "name"))
                                downstream))]
      (is (some? bridge) "Downstream edge to review-supporting-document exists")
      (is (= 1 (count (get bridge "match"))))
      (let [match (first (get bridge "match"))]
        (is (= income-document (get-in match ["producer-type" "name"])))
        (is (= supporting-document (get-in match ["consumer-type" "name"]))
            "Hierarchy bridge: producer keyword ≠ consumer keyword, linked via the derive chain")
        (is (true? (get-in match ["producer-type" "known"])))))))

(deftest test-hierarchy-session-known-flags
  (testing "Session fact-type known flags agree with the analysis end-to-end"
    (let [snapshot (st/get-session-snapshot)
          income-entry (first (filter (fn [[_id fact]]
                                        (= income-document (get-in fact ["type" "name"])))
                                      (get snapshot "facts")))]
      (is (some? income-entry) "income-document fact is in working memory")
      (is (true? (get-in income-entry [1 "type" "known"]))
          "income-document is an analysis insert-type → session known: true (linkable)")
      (is (nil? (get snapshot "fact-raw-types"))
          "The internal fact-id → raw-type index is stripped from the served snapshot (raw objects do not serialize to JSON)"))))
