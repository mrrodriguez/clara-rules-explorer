(ns clara.server.tools.graph.artifacts.compose-persist-test
  "`flow/compose-persist!` over the two checked-in `rules-annos/` units: the
  composed result is written as a normal single-unit artifact directory, with
  the composed `:slim` block intact and a manifest recording the operation."
  (:require
   [clara.server.tools.graph.artifacts.flow :as flow]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts*]]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

(def ^:private app-approved
  "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")

(def ^:private notice-approved
  "clara.server.tools.graph.rules.loan-outcome-notices/notice-approved-app")

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(deftest compose-persist-writes-a-single-unit-dir-test
  (let [opts (assoc *artifact-opts*
                    :root (registry-root)
                    :repo "composed/demo"
                    :units [{:repo "loan-app-ruleset"}
                            {:repo "loan-disposition-ruleset"}])
        result (flow/compose-persist! opts)]
    (testing "the result reports the unit-shaped dir and the layers that folded"
      (is (= (store/get-out-dir opts) (:dir result)))
      (is (= [:clara.tools.graph.analyze/generated :memory] (:layers result)))
      (is (pos? (:rule-count result))))

    (testing "the three derived artifacts exist as a normal unit"
      (is (.isFile (store/get-artifact-file :auto opts)))
      (is (.isFile (store/get-artifact-file :memory opts)))
      (is (.isFile (store/get-artifact-file :merged opts)))
      (is (.isDirectory (store/get-artifact-file :rulebase-analysis opts)))
      (is (.isFile (store/get-artifact-file :rulebase-analysis-digest opts)))
      (is (.isFile (store/get-artifact-file :manifest opts))))

    (testing "merged annotations span both source units"
      (let [merged (store/read-merged-annotations opts)
            names (set (keys (:annotations merged)))]
        (is (contains? names app-approved))
        (is (contains? names notice-approved))))

    (testing "the composed analysis parts carry :unit and the composed :slim block"
      (let [index (store/read-analysis-part :index opts)
            meta (store/read-analysis-part :meta opts)]
        (is (contains? (set (keys (:rules index))) app-approved))
        (is (contains? (set (keys (:rules index))) notice-approved))
        (is (= "loan-app-ruleset" (get-in index [:rules app-approved :unit])))
        (is (= "loan-disposition-ruleset" (get-in index [:rules notice-approved :unit])))
        (is (contains? (get-in meta [:slim :dropped]) :nodes)
            "the composition still declares its absent Rete network")
        (is (str/includes? (get-in meta [:slim :references]) "Composed analysis"))))

    (testing "the manifest records the composition"
      (let [manifest (edn-io/read-edn-file (store/get-artifact-file :manifest opts))
            units (get-in manifest [:analysis-run :units])]
        (is (= "composed/demo" (:repo manifest)))
        (is (= :compose (get-in manifest [:analysis-run :mode])))
        (is (= ["loan-app-ruleset" "loan-disposition-ruleset"]
               (mapv :repo units)))
        (testing "each source entry carries the state that was composed"
          (is (every? #(and (:sha %) (:created %)) units))
          (is (not-any? :branch units)))
        (testing "staleness is stated in composition terms"
          (is (= "review-when-any-source-sha-drifts"
                 (get-in manifest [:staleness :policy])))
          (is (= #{"loan-app-ruleset" "loan-disposition-ruleset"}
                 (set (keys (get-in manifest [:staleness :sources])))))
          (is (= (get-in units [0 :sha])
                 (get-in manifest [:staleness :sources "loan-app-ruleset" :sha]))))))))
