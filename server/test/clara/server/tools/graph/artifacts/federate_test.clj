(ns clara.server.tools.graph.artifacts.federate-test
  "The federated index over the two checked-in `rules-annos/` units. They share
  the fact-type vocabulary around `:loan-app/application-outcome` without being
  one rulebase — `loan-app-ruleset` produces `ApplicationOutcome` (which derives
  the keyword), `loan-disposition-ruleset` matches the keyword. The index is
  where the cross-unit contract becomes queryable."
  (:require
   [clara.server.tools.graph.artifacts.federate :as federate]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def app-outcome "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome")
(def keyword-outcome ":loan-app/application-outcome")
(def notice-approved "clara.server.tools.graph.rules.loan-outcome-notices/notice-approved-app")
(def notice-denied "clara.server.tools.graph.rules.loan-outcome-notices/notice-denied-app")

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(defn- ->index []
  (federate/->index (registry/discover {:root (registry-root)})
                    [{:repo "loan-app-ruleset"}
                     {:repo "loan-disposition-ruleset"}]))

(deftest index-records-the-cross-unit-contract-test
  (let [index (->index)]
    (testing "the hierarchy carries the derive from the producing unit"
      (is (contains? (get-in index [:hierarchy :ancestors app-outcome])
                     keyword-outcome))
      (is (contains? (get-in index [:hierarchy :descendants keyword-outcome])
                     app-outcome)))

    (testing "the shared type names its producers and consumers by unit"
      (let [entry (get-in index [:fact-types keyword-outcome])]
        (is (= #{"loan-app-ruleset"} (:producers entry))
            "the keyword is produced by the unit inserting a derived type")
        (is (= #{"loan-disposition-ruleset"} (:consumers entry)))
        (is (= {"loan-disposition-ruleset" #{notice-approved notice-denied}}
               (:matched-by entry)))))

    (testing "the unit edge is the fact a per-unit artifact cannot contain"
      (is (= {:via #{keyword-outcome} :rules 2}
             (get (:unit-edges index) ["loan-app-ruleset" "loan-disposition-ruleset"]))))))

(deftest query-fns-answer-over-the-index-test
  (let [index (->index)]
    (testing "impact-of names the downstream rules that break"
      (let [impact (federate/impact-of index keyword-outcome)]
        (is (= {"loan-disposition-ruleset" #{notice-approved notice-denied}}
               (:matched-by impact)))))

    (testing "producers-of names the inserting rules (direct insert, so empty here)"
      (is (= {} (federate/producers-of index keyword-outcome))))

    (testing "dependents-of and paths-between expose the unit dependency"
      (is (= {"loan-disposition-ruleset" {:via #{keyword-outcome} :rules 2}}
             (federate/dependents-of index "loan-app-ruleset")))
      (is (= ["loan-app-ruleset" "loan-disposition-ruleset"]
             (federate/paths-between index "loan-app-ruleset" "loan-disposition-ruleset"))))

    (testing "coverage reports no shape skew between the two units"
      (is (= [] (:shape-mismatch (federate/coverage-report index)))))))
