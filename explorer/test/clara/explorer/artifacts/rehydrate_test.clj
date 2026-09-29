(ns clara.explorer.artifacts.rehydrate-test
  "Round-trip property for `rehydrate`: a real rulebase analysis, slimmed, comes
  back through `rehydrate-analysis` equal to the original modulo the keys only a
  live rulebase can answer.

  The one deviation rehydrate cannot reproduce exactly is load order: the
  persisted parts are sorted maps, so a fact type's `:used-by-*` /
  `:inserted-by-rules` / `:retracted-by-rules` vectors come back name-sorted
  where the in-memory analysis emits load order. Both sides are normalized to
  name order for the comparison; the *sets* are what this pins."
  (:require
   [clara.rules :as r]
   [clara.explorer.annotations.merge :as am]
   [clara.explorer.artifacts.rehydrate :as rehydrate]
   [clara.explorer.artifacts.slim :as slim]
   [clara.explorer.conditions :as conditions]
   [clara.explorer.core :as core]
   [clara.explorer.test.rules.loan-outcome-notices]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

(def ^:private session
  "A real, unfired single-ns session — enough rulebase for every dropped
  direction to actually occur (producers, consumers, a hierarchy)."
  (delay (r/mk-session 'clara.explorer.test.rules.loan-outcome-notices)))

(defn- ->analysis
  "The analysis over the session's own rule `:props` layer, so the declared
  insert-types produce real dep-graph edges and the round-trip exercises the
  source/sink asymmetry of `:upstream` / `:downstream`."
  []
  (let [s @session]
    (core/->rulebase-analysis s (am/annotations (am/merge-layers [(am/->props-layer s)])))))

(defn- strip-matches
  [deps]
  (when deps (mapv #(dissoc % :match) deps)))

(defn- strip-production
  "Remove what rehydrate cannot put back from one production: the raw-LHS form,
  the serialized dynamic-detection maps, the condition-internal keys, and the
  `:match` attachments on upstream/downstream deps."
  [production]
  (cond-> production
    true (dissoc :lhs-form :dynamic-insert-types-detected :dynamic-retract-types-detected)
    (:lhs production) (update :lhs conditions/strip-internal-keys)
    (:upstream production) (update :upstream strip-matches)
    (:downstream production) (update :downstream strip-matches)))

(defn- sort-usage
  [entry]
  (reduce (fn [m k] (update m k #(vec (sort-by :name %))))
          entry
          [:used-by-rules :used-by-queries :inserted-by-rules :retracted-by-rules]))

(defn- strip-absent
  "The original analysis with every key/field rehydrate leaves absent removed,
  and the usage vectors normalized to the name order rehydrate emits."
  [analysis]
  (-> analysis
      (dissoc :nodes :ns-deps :merged-annotations)
      (update :rules #(update-vals % strip-production))
      (update :queries #(update-vals % strip-production))
      (update :fact-types #(update-vals % sort-usage))))

(deftest rehydrate-round-trips-a-real-analysis-test
  (let [analysis (->analysis)]
    (testing "slimming then rehydrating reproduces the analysis, modulo the
              keys only a live rulebase answers"
      (let [rehydrated (-> analysis
                           slim/slim-rulebase-analysis
                           rehydrate/rehydrate-analysis)]
        (is (= (strip-absent analysis)
               (dissoc rehydrated :slim)))))

    (testing "the narrowed :slim block names exactly what is still absent"
      (let [dropped (get-in (-> analysis
                                slim/slim-rulebase-analysis
                                rehydrate/rehydrate-analysis)
                            [:slim :dropped])
            still-absent #{:nodes :lhs-form :raw-condition :clara-rules-explorer/normalized :ns-deps}]
        (is (= still-absent (set/intersection dropped still-absent)))
        (is (not (contains? dropped :descendants)))
        (is (not (contains? dropped :used-by-rules)))
        (is (not (contains? dropped :downstream)))
        (is (not (contains? dropped :fact-type-id-index)))))))

(deftest rehydrate-restores-annotations-when-supplied-test
  (let [analysis (->analysis)
        slimmed (slim/slim-rulebase-analysis analysis)
        rehydrated (rehydrate/rehydrate-analysis slimmed {:annotations {}})]
    (testing ":merged-annotations comes back from the supplied annotations"
      (is (contains? rehydrated :merged-annotations)))
    (testing "the narrowed dropped set no longer claims it"
      (is (not (contains? (get-in rehydrated [:slim :dropped]) :merged-annotations))))))
