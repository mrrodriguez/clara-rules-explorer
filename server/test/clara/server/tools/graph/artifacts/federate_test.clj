(ns clara.server.tools.graph.artifacts.federate-test
  "The federated index over the two checked-in `rules-annos/` units. They share
  the fact-type vocabulary around `:loan-app/application-outcome` without being
  one rulebase — `loan-app-ruleset` produces `ApplicationOutcome` (which derives
  the keyword), `loan-disposition-ruleset` matches the keyword. The index is
  where the cross-unit contract becomes queryable."
  (:require
   [clara.rules :as r]
   [clara.server.tools.graph.annotations.merge :as am]
   [clara.server.tools.graph.artifacts.federate :as federate]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.core :as core]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clara.server.tools.graph.rules.loan-app-rules]
   [clara.server.tools.graph.rules.loan-doc-queries]
   [clara.server.tools.graph.rules.loan-doc-rules]
   [clara.server.tools.graph.rules.loan-outcome-notices]
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

(defn- ->reference-analysis
  "The monolithic truth: the loan-doc/app session with the downstream notices
  ruleset wired on, the same session `integration-test/run-loan-outcome-notices`
  builds."
  []
  (let [session (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                              'clara.server.tools.graph.rules.loan-app-rules
                              'clara.server.tools.graph.rules.loan-doc-queries
                              'clara.server.tools.graph.rules.loan-outcome-notices)]
    (core/->rulebase-analysis session (am/annotations (am/merge-layers [(am/->props-layer session)])))))

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

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clara-federate-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

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

(deftest grade-against-a-composed-reference-test
  (let [index (->index)
        graded (federate/grade index (->reference-analysis))]
    (testing "the reference confirms the cross-unit edge"
      (is (contains? (set (:confirmed-unit-edges graded))
                     ["loan-app-ruleset" "loan-disposition-ruleset"])))
    (testing "no index edge is contradicted"
      (is (= [] (:contradicted-unit-edges graded))))
    (testing "every reference namespace is covered by a unit"
      (is (= [] (:uncovered-namespaces graded))))
    (testing "the union had no entry point the reference actually produces"
      (is (empty? (:reference-produced-entry-points graded))))))

(deftest digest-and-persist-round-trip-test
  (let [index (->index)
        digest (federate/->digest index)]
    (testing "->digest reduces the index to counts + the work lists"
      (is (= 2 (get-in digest [:summary :unit-count])))
      (is (= 1 (get-in digest [:summary :unit-edge-count])))
      (is (= #{keyword-outcome}
             (:via (get (:unit-edges digest) ["loan-app-ruleset" "loan-disposition-ruleset"]))))
      (is (string? (:more digest))))

    (testing "persist! writes both files and they read back equal"
      (let [dir (temp-dir)]
        (try
          (let [{written-index :index written-digest :digest} (federate/persist! index {:dir dir})]
            (is (= (->index) (edn-io/read-edn-file (io/file written-index))))
            (is (= (federate/->digest (->index)) (edn-io/read-edn-file (io/file written-digest)))))
          (finally (delete-tree dir)))))))
