(ns clara.explorer.artifacts.compose-persist-test
  "`flow/compose-persist!` over the two checked-in `rules-annos/` units: the
  composed result is written as a normal single-unit artifact directory, with
  the composed `:slim` block intact and a manifest recording the operation."
  (:require
   [clara.explorer.artifacts.flow :as flow]
   [clara.explorer.artifacts.store :as store]
   [clara.explorer.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts*]]
   [clara.explorer.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

(def ^:private app-approved
  "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?")

(def ^:private notice-approved
  "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app")

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
      (is (= [:clara.explorer.analyze/generated :memory] (:layers result)))
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
        (is (= 'clara.explorer.artifacts.compose/->composed-analysis
               (get-in meta [:slim :written-by])))))

    (testing "the manifest records the composition"
      (let [manifest (edn-io/read-edn-file (store/get-artifact-file :manifest opts))
            units (get-in manifest [:analysis-run :units])]
        (is (= "composed/demo" (:repo manifest)))
        (is (= :compose (get-in manifest [:analysis-run :mode])))
        (is (= ["loan-app-ruleset" "loan-disposition-ruleset"]
               (mapv :repo units)))
        (testing "each source entry carries the state that was composed"
          (is (every? #(and (:sha %) (:created %)) units))
          (is (not-any? :variant units)))
        (testing "staleness is stated in composition terms"
          (is (= "review-when-any-source-sha-drifts"
                 (get-in manifest [:staleness :policy])))
          (is (= #{"loan-app-ruleset" "loan-disposition-ruleset"}
                 (set (keys (get-in manifest [:staleness :sources])))))
          (is (= (get-in units [0 :sha])
                 (get-in manifest [:staleness :sources "loan-app-ruleset" :sha]))))))))

(deftest compose-persist-carries-variant-sources-test
  (let [opts (assoc *artifact-opts*
                    :root (registry-root)
                    :repo "composed/demo-variant-mix"
                    :units [{:repo "loan-app-ruleset"}
                            {:repo "loan-disposition-ruleset" :variant [[:ref "feature/new-tax"]]}])
        _ (flow/compose-persist! opts)
        manifest (edn-io/read-edn-file (store/get-artifact-file :manifest opts))
        units (get-in manifest [:analysis-run :units])]
    (testing "the variant source is read from _variants/, so its rules are present"
      (let [index (store/read-analysis-part :index opts)]
        (is (contains? (set (keys (:rules index))) notice-approved))))
    (testing "the manifest's :analysis-run :units carry :variant for variant sources"
      (is (= ["loan-app-ruleset" "loan-disposition-ruleset"] (mapv :repo units)))
      (is (= [[:ref "feature/new-tax"]] (get-in units [1 :variant])))
      (is (not (contains? (nth units 0) :variant))))
    (testing "per-source staleness keys the variant source by repo@variant path"
      (let [sources (get-in manifest [:staleness :sources])]
        (is (contains? sources "loan-disposition-ruleset@ref=feature%2Fnew-tax"))
        (is (= [[:ref "feature/new-tax"]]
               (get-in sources ["loan-disposition-ruleset@ref=feature%2Fnew-tax" :variant])))))))

(deftest compose-persist-manifest-fn-sees-each-source-manifest-test
  (let [opts (assoc *artifact-opts*
                    :root (registry-root)
                    :repo "composed/demo-hook"
                    :units [{:repo "loan-app-ruleset"}
                            {:repo "loan-disposition-ruleset" :variant [[:ref "feature/new-tax"]]}]
                    :manifest-fn (fn [manifest {:keys [sources]}]
                                   (update-in manifest [:analysis-run :units]
                                              (fn [units]
                                                (mapv (fn [entry {:keys [unit manifest]}]
                                                        (assoc entry
                                                               :source-unit unit
                                                               :source-repo (:repo manifest)))
                                                      units
                                                      sources)))))
        _ (flow/compose-persist! opts)
        units (-> (store/get-artifact-file :manifest opts)
                  edn-io/read-edn-file
                  (get-in [:analysis-run :units]))]
    (testing ":sources follow :analysis-run :units, each with its unit's own manifest"
      (is (= [{:repo "loan-app-ruleset"}
              {:repo "loan-disposition-ruleset" :variant [[:ref "feature/new-tax"]]}]
             (mapv :source-unit units)))
      (is (= ["loan-app-ruleset" "loan-disposition-ruleset"] (mapv :source-repo units))))
    (testing "the composition's own entries are still there to enrich"
      (is (every? #(and (:sha %) (:created %)) units)))))

(deftest compose-persist-records-coverage-test
  (testing "a fully covered selection records an empty unknown-namespaces vector"
    (let [opts (assoc *artifact-opts*
                      :root (registry-root)
                      :repo "composed/coverage-clean"
                      :units [{:repo "loan-app-ruleset"}
                              {:repo "loan-disposition-ruleset"}])
          _ (flow/compose-persist! opts)
          coverage (-> (store/get-artifact-file :manifest opts)
                       edn-io/read-edn-file
                       :coverage)]
      (is (= {:unknown-namespaces []} coverage))))

  (testing "a filter naming a namespace no unit covers records it"
    (let [opts (assoc *artifact-opts*
                      :root (registry-root)
                      :repo "composed/coverage-gap"
                      :units [{:repo "loan-app-ruleset"
                               :namespaces ["clara.explorer.test.rules.loan-app-rules"
                                            "clara.explorer.test.rules.nope"]}])
          _ (flow/compose-persist! opts)
          coverage (-> (store/get-artifact-file :manifest opts)
                       edn-io/read-edn-file
                       :coverage)]
      (is (= ["clara.explorer.test.rules.nope"] (:unknown-namespaces coverage)))))

  (testing "a :manifest-fn addition under :coverage is kept"
    (let [opts (assoc *artifact-opts*
                      :root (registry-root)
                      :repo "composed/coverage-host"
                      :units [{:repo "loan-app-ruleset"}]
                      :manifest-fn (fn [manifest _]
                                     (assoc-in manifest [:coverage :host-gap] ["my.ns"])))
          _ (flow/compose-persist! opts)
          coverage (-> (store/get-artifact-file :manifest opts)
                       edn-io/read-edn-file
                       :coverage)]
      (is (= ["my.ns"] (:host-gap coverage))))))
