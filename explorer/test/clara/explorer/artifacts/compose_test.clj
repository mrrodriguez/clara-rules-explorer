(ns clara.explorer.artifacts.compose-test
  "Composition over the two checked-in `rules-annos/` units — the end-to-end
  demonstration that separate persisted rulesets merge into one cohesive
  rulebase. `loan-app-ruleset` produces `ApplicationOutcome`, and
  `loan-disposition-ruleset` consumes its ancestor `:loan-app/application-outcome`
  — the same wiring `clara.explorer.server.integration-test` proves live. The merge
  is where the cross-unit edge materializes: each unit alone cannot contain it."
  (:require
   [clara.rules :as r]
   [clara.explorer.annotations.merge :as ann.merge]
   [clara.explorer.artifacts.compose :as compose]
   [clara.explorer.artifacts.cross-unit :as cross-unit]
   [clara.explorer.artifacts.registry :as registry]
   [clara.explorer.artifacts.rehydrate :as rehydrate]
   [clara.explorer.artifacts.shared.compose :as shared-compose]
   [clara.explorer.artifacts.slim :as slim]
   [clara.explorer.core :as core]
   [clara.explorer.test.rules.loan-hierarchy-rules :as lhr]
   [clara.explorer.test.rules.split-hierarchy-a]
   [clara.explorer.test.rules.split-hierarchy-b]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def ^:private app-approved
  "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?")

(def ^:private app-denied
  "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?")

(def ^:private notice-approved
  "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app")

(def ^:private notice-denied
  "clara.explorer.test.rules.loan-outcome-notices/notice-denied-app")

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
        (is (contains? ids "loan-app-ruleset/:clara.explorer.analyze/generated"))
        (is (contains? ids "loan-app-ruleset/:memory"))
        (is (contains? ids "loan-disposition-ruleset/:clara.explorer.analyze/generated"))))
    (testing "the merged annotations cover rules from both units"
      (let [names (set (keys (:annotations folded)))]
        (is (contains? names app-approved))
        (is (contains? names notice-approved))))))

(deftest fold-layers-qualifies-variant-layer-ids-test
  (let [reg (->registry)
        folded (compose/fold-layers reg [{:repo "loan-disposition-ruleset"}
                                         {:repo "loan-disposition-ruleset"
                                          :variant [[:ref "feature/new-tax"]]}])]
    (testing "a variant source is read from _variants/ and its layer id encodes the variant"
      (let [ids (set (map :id (:layers folded)))]
        (is (contains? ids "loan-disposition-ruleset/:clara.explorer.analyze/generated"))
        (is (contains? ids
                       "loan-disposition-ruleset@ref=feature%2Fnew-tax/:clara.explorer.analyze/generated"))))))

(deftest standard-role-layers-flatten-and-strip-test
  (let [reg (->registry)
        layers (compose/->standard-role-layers reg [(unit "loan-app-ruleset")
                                                    (unit "loan-disposition-ruleset")])]
    (testing "one standard layer per role, in fold order, absent roles omitted"
      (is (= [:auto :memory] (vec (keys layers))))
      (is (= :clara.explorer.analyze/generated (get-in layers [:auto :id])))
      (is (= :memory (get-in layers [:memory :id]))))
    (testing "the auto layer folds both units' generated layers"
      (let [names (set (keys (get-in layers [:auto :annotations])))]
        (is (contains? names app-approved))
        (is (contains? names notice-approved))))
    (testing "the memory layer keeps only the unit that contributed one"
      (let [names (set (keys (get-in layers [:memory :annotations])))]
        (is (contains? names "clara.explorer.test.rules.loan-doc-rules/dynamic-insert-audit-trail"))
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

(deftest composed-analysis-narrows-to-unit-namespace-filter-test
  (let [reg (->registry)
        composed (compose/->composed-analysis
                  reg [{:repo "loan-app-ruleset"
                        :namespaces ["clara.explorer.test.rules.loan-doc-rules"]}
                       {:repo "loan-disposition-ruleset"}])]
    (testing "only the in-scope namespace's productions compose"
      (is (= 17 (count (:rules composed))))
      (is (= 3 (count (:queries composed))))
      (is (= #{"clara.explorer.test.rules.loan-doc-rules"
               "clara.explorer.test.rules.loan-outcome-notices"}
             (into #{} (map (comp :ns val))
                   (concat (:rules composed) (:queries composed))))))
    (testing "a production from the filtered-out namespace is absent"
      (is (not (contains? (:rules composed) app-approved))))
    (testing "the unfiltered unit still contributes its productions"
      (is (contains? (:rules composed) notice-approved)))))

(deftest composed-analysis-refuses-a-name-collision-test
  (let [reg (->registry)]
    ;; The two units do not collide; composing the same unit twice must.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"claimed by both"
                          (compose/->composed-analysis reg [(unit "loan-app-ruleset")
                                                            (unit "loan-app-ruleset")])))))

(deftest fold-layers-narrow-annotations-to-the-namespace-filter-test
  (let [reg (->registry)
        selection [{:repo "loan-app-ruleset"
                    :namespaces ["clara.explorer.test.rules.loan-app-rules"]}
                   {:repo "loan-disposition-ruleset"
                    :namespaces ["clara.explorer.test.rules.loan-app-rules"]}]
        composed (compose/->composed-analysis reg selection)
        folded (compose/fold-layers reg selection)]
    (testing "the narrowed fold has exactly the composed rules"
      (is (= (set (keys (:rules composed)))
             (set (keys (ann.merge/annotations folded))))))
    (testing "an unnarrowed fold still carries every unit's annotations"
      (is (> (count (ann.merge/annotations (compose/fold-layers reg (registry/source-units reg))))
             (count (ann.merge/annotations folded)))))))

(deftest standard-role-layers-narrow-and-record-the-filter-test
  (let [reg (->registry)
        selection [{:repo "loan-app-ruleset"
                    :namespaces ["clara.explorer.test.rules.loan-app-rules"]}
                   {:repo "loan-disposition-ruleset"}]
        layers (compose/->standard-role-layers reg selection)]
    (testing "the auto layer folds only the narrowed unit's in-scope rules"
      (let [names (set (keys (get-in layers [:auto :annotations])))]
        (is (contains? names app-approved))
        (is (not (contains? names "clara.explorer.test.rules.loan-doc-rules/extract-doc-meta-rule")))))
    (testing "the persisted layer source records the per-unit filter"
      (is (= {"loan-app-ruleset" ["clara.explorer.test.rules.loan-app-rules"]}
             (get-in layers [:auto :source :namespaces]))))
    (testing "an unnarrowed selection records no filter"
      (is (not (contains? (get-in (compose/->standard-role-layers reg [(unit "loan-app-ruleset")
                                                                       (unit "loan-disposition-ruleset")])
                                  [:auto :source])
                          :namespaces))))))

(deftest union-fact-types-recloses-and-orders-ancestors-test
  (testing "a hierarchy split across units is re-closed transitively"
    (let [merged (shared-compose/union-fact-types
                  [{"D" {:name "D" :ns "x" :ancestors ["C"]}
                    "C" {:name "C" :ns "x" :ancestors []}}
                   {"C" {:name "C" :ns "x" :ancestors ["B"]}
                    "B" {:name "B" :ns "x" :ancestors ["A"]}
                    "A" {:name "A" :ns "x" :ancestors []}}])]
      (is (= ["C" "B" "A"] (get-in merged ["D" :ancestors])))
      (is (= ["B" "A"] (get-in merged ["C" :ancestors])))))

  (testing "ancestors are ordered deepest-first, not shallowest-first"
    (let [merged (shared-compose/union-fact-types
                  [{"A" {:name "A" :ns "x" :ancestors []}
                    "B" {:name "B" :ns "x" :ancestors ["A"]}
                    "C" {:name "C" :ns "x" :ancestors ["A" "B"]}}])]
      (is (= ["B" "A"] (get-in merged ["C" :ancestors])))
      (is (= [] (get-in merged ["A" :ancestors]))))))

(def ^:private split-consume-c-name
  "clara.explorer.test.rules.split-hierarchy-a/consume-c")

(def ^:private split-produce-a-name
  "clara.explorer.test.rules.split-hierarchy-b/produce-a")

(def ^:private split-a-hierarchy
  (-> (make-hierarchy)
      (derive :split-hierarchy/b :split-hierarchy/c)))

(def ^:private split-b-hierarchy
  (-> (make-hierarchy)
      (derive :split-hierarchy/a :split-hierarchy/b)))

(defn- ->split-session
  "Session over `ns-sym` with keyword facts resolved by `lhr/fact-type-fn`
  and hierarchy answers from `hierarchy`, standing in for the unit's own
  classpath."
  [ns-sym hierarchy]
  (r/mk-session ns-sym
                :fact-type-fn lhr/fact-type-fn
                :ancestors-fn (fn [t] (ancestors hierarchy t))))

(defn- ->split-analysis
  "Full `core/->rulebase-analysis` over `session`, recording the declared
  tags of `hierarchy` (the `derive` children) even when no production
  mentions them."
  [session hierarchy]
  (core/->rulebase-analysis
   session
   (ann.merge/merge-layers [(ann.merge/->props-layer session)])
   {:declared-tags-fn (fn [] (keys (:parents hierarchy)))}))

(defn- ->composed-from-slims
  "In-memory composition of the two slim analyses, without touching disk.
  `shared-compose/->composed-analysis` takes the same capabilities map
  `clara.explorer.artifacts.shared.selection/->selection` takes."
  [slim-a slim-b]
  (let [by-unit {"unit-a" slim-a "unit-b" slim-b}]
    (shared-compose/->composed-analysis
     {:read-analysis (fn [unit] (get by-unit (:repo unit)))
      :assert-compatible! (constantly nil)}
     [{:repo "unit-a"} {:repo "unit-b"}])))

(defn- ->direct-type-names
  "Serialized names a full-analysis production directly references via
  `:lhs-types`, `:insert-types`, and `:retract-types`."
  [production]
  (set (concat (map :name (:lhs-types production))
               (map :name (:insert-types production))
               (map :name (:retract-types production)))))

(deftest split-hierarchy-declared-edge-composes-test
  (testing "a derive declared in one unit, on a type only another unit uses"
    (let [session-a (->split-session 'clara.explorer.test.rules.split-hierarchy-a
                                     split-a-hierarchy)
          session-b (->split-session 'clara.explorer.test.rules.split-hierarchy-b
                                     split-b-hierarchy)
          analysis-a (->split-analysis session-a split-a-hierarchy)
          analysis-b (->split-analysis session-b split-b-hierarchy)
          slim-a (slim/slim-rulebase-analysis analysis-a)
          slim-b (slim/slim-rulebase-analysis analysis-b)
          composed (->composed-from-slims slim-a slim-b)]
      (testing "the fixture models separate classpaths"
        (is (not (contains? (:fact-types analysis-b) ":split-hierarchy/c"))
            "B never saw A's derive, so its own analysis has no entry for :c"))
      (testing "the declaring unit records its unused child"
        (is (= [":split-hierarchy/c"]
               (mapv :name
                     (get-in analysis-a [:fact-types ":split-hierarchy/b" :ancestors]))))
        (let [direct (into #{}
                           (map ->direct-type-names)
                           (concat (vals (:rules analysis-a))
                                   (vals (:queries analysis-a))))]
          (is (not (contains? direct ":split-hierarchy/b"))
              "no production mentions :b, so it is not in the known set")))
      (testing "the composition closes the chain"
        (is (= [":split-hierarchy/b" ":split-hierarchy/c"]
               (get-in composed [:fact-types ":split-hierarchy/a" :ancestors]))))
      (testing "the cross-unit edge exists"
        (is (contains? (get-in composed [:dep-graph split-consume-c-name :upstream])
                       split-produce-a-name)))
      (testing "no false entry point"
        (is (not (contains? (cross-unit/all-entry-points composed (:fact-types composed))
                            ":split-hierarchy/c"))))
      (testing "without the declared edge the composition misses the link (old behavior)"
        (let [analysis-a-old (core/->rulebase-analysis
                              session-a
                              (ann.merge/merge-layers [(ann.merge/->props-layer session-a)])
                              {:declared-tags-fn (constantly [])})
              composed-old (->composed-from-slims
                            (slim/slim-rulebase-analysis analysis-a-old)
                            slim-b)]
          (is (= [":split-hierarchy/b"]
                 (get-in composed-old [:fact-types ":split-hierarchy/a" :ancestors]))
              "the chain stops where the unrecorded edge was")
          (is (not (contains? (get-in composed-old
                                      [:dep-graph split-consume-c-name :upstream])
                              split-produce-a-name)))
          (is (contains? (cross-unit/all-entry-points composed-old
                                                      (:fact-types composed-old))
                         ":split-hierarchy/c")
              ":c reads as an entry point with no producer"))))))
