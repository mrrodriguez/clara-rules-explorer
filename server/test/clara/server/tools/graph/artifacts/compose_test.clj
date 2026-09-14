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

(deftest standard-role-layers-flatten-and-strip-test
  (let [reg (->registry)
        layers (compose/->standard-role-layers reg [(unit "loan-app-ruleset")
                                                    (unit "loan-disposition-ruleset")])]
    (testing "one standard layer per role, in fold order, absent roles omitted"
      (is (= [:auto :memory] (vec (keys layers))))
      (is (= :clara.tools.graph.analyze/generated (get-in layers [:auto :id])))
      (is (= :memory (get-in layers [:memory :id]))))
    (testing "the auto layer folds both units' generated layers"
      (let [names (set (keys (get-in layers [:auto :annotations])))]
        (is (contains? names app-approved))
        (is (contains? names notice-approved))))
    (testing "the memory layer keeps only the unit that contributed one"
      (let [names (set (keys (get-in layers [:memory :annotations])))]
        (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-audit-trail"))
        (is (not (contains? names notice-approved)))))
    (testing "flattened layer files carry no derived :from-layer stamps"
      (let [cs (get-in layers [:auto :annotations app-approved
                               :clara-rules/dynamic-insert-types-detected :callsites])]
        (is (seq cs))
        (is (every? #(not (contains? % :from-layer)) cs))))))

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

(deftest union-fact-types-recloses-and-orders-ancestors-test
  (testing "a hierarchy split across units is re-closed transitively"
    (let [merged (compose/union-fact-types
                  [{"D" {:name "D" :ns "x" :ancestors ["C"]}
                    "C" {:name "C" :ns "x" :ancestors []}}
                   {"C" {:name "C" :ns "x" :ancestors ["B"]}
                    "B" {:name "B" :ns "x" :ancestors ["A"]}
                    "A" {:name "A" :ns "x" :ancestors []}}])]
      (is (= ["C" "B" "A"] (get-in merged ["D" :ancestors])))
      (is (= ["B" "A"] (get-in merged ["C" :ancestors])))))

  (testing "ancestors are ordered deepest-first, not shallowest-first"
    (let [merged (compose/union-fact-types
                  [{"A" {:name "A" :ns "x" :ancestors []}
                    "B" {:name "B" :ns "x" :ancestors ["A"]}
                    "C" {:name "C" :ns "x" :ancestors ["A" "B"]}}])]
      (is (= ["B" "A"] (get-in merged ["C" :ancestors])))
      (is (= [] (get-in merged ["A" :ancestors]))))))
