(ns clara.server.tools.graph.artifacts.compose-test
  "Composition over the two checked-in `rules-annos/` units — the end-to-end
  demonstration that separate persisted rulesets merge into one cohesive
  rulebase. `loan-app-ruleset` produces `ApplicationOutcome`, and
  `loan-disposition-ruleset` consumes its ancestor `:loan-app/application-outcome`
  — the same wiring `clara.server.graph.integration-test` proves live. The merge
  is where the cross-unit edge materializes: each unit alone cannot contain it."
  (:require
   [clara.server.tools.graph.artifacts.compose :as compose]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.artifacts.rehydrate :as rehydrate]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def ^:private app-approved
  "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")

(def ^:private app-denied
  "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?")

(def ^:private notice-approved
  "clara.server.tools.graph.rules.loan-outcome-notices/notice-approved-app")

(def ^:private notice-denied
  "clara.server.tools.graph.rules.loan-outcome-notices/notice-denied-app")

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(defn- ->registry []
  (registry/discover {:root (registry-root)}))

(defn- unit [repo] {:repo repo})

(deftest fold-layers-qualifies-layer-ids-across-units-test
  (let [reg (->registry)
        folded (compose/fold-layers reg [(unit "loan-app-ruleset")
                                         (unit "loan-disposition-ruleset")])]
    (testing "the fold spans both units' file layers, ids qualified per unit"
      (let [ids (set (map :id (:layers folded)))]
        (is (contains? ids "loan-app-ruleset/:clara.tools.graph.analyze/generated"))
        (is (contains? ids "loan-app-ruleset/:memory"))
        (is (contains? ids "loan-disposition-ruleset/:clara.tools.graph.analyze/generated"))))
    (testing "the merged annotations cover rules from both units"
      (let [names (set (keys (:annotations folded)))]
        (is (contains? names app-approved))
        (is (contains? names notice-approved))))))

(deftest composed-analysis-joins-the-two-rulesets-test
  (let [reg (->registry)
        composed (-> reg
                     (compose/->composed-analysis [(unit "loan-app-ruleset")
                                                   (unit "loan-disposition-ruleset")])
                     rehydrate/rehydrate-analysis)]
    (testing "rules and queries from both units are present, each tagged :unit"
      (is (contains? (set (keys (:rules composed))) app-approved))
      (is (contains? (set (keys (:rules composed))) notice-approved))
      (is (= "loan-app-ruleset" (get-in composed [:rules app-approved :unit])))
      (is (= "loan-disposition-ruleset" (get-in composed [:rules notice-approved :unit]))))

    (testing "the shared fact type shows the cross-unit contract"
      (let [outcome (get-in composed [:fact-types ":loan-app/application-outcome"])
            inserted-by (set (map :name (:inserted-by-rules outcome)))
            used-by (set (map :name (:used-by-rules outcome)))]
        (is (contains? inserted-by app-approved)
            "the upstream unit's producer inserts the type")
        (is (contains? used-by notice-approved)
            "the downstream unit's consumer matches the type")
        (is (contains? used-by notice-denied))))

    (testing "the dep-graph carries the cross-unit edge a per-unit artifact cannot"
      (is (contains? (get-in composed [:dep-graph notice-approved :upstream])
                     app-approved)
          "notice-approved-app is downstream of the app-outcome producer")
      (is (contains? (get-in composed [:dep-graph notice-denied :upstream])
                     app-denied)))

    (testing "the composition rehydrates without the still-absent live-only keys"
      (is (nil? (:nodes composed)))
      (is (contains? (get-in composed [:slim :dropped]) :nodes)))))

(deftest composed-analysis-refuses-a-name-collision-test
  (let [reg (->registry)]
    ;; The two units do not collide; composing the same unit twice must.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"claimed by both"
                          (compose/->composed-analysis reg [(unit "loan-app-ruleset")
                                                            (unit "loan-app-ruleset")])))))
