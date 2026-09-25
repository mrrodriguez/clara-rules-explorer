(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.shared.compose
  "The pure compose half of `clara.server.tools.graph.artifacts.compose`: combine a caller-named
  selection of artifact units into one slim `RulebaseAnalysis`.

  The caller asserts the units are components of ONE rulebase and gets one slim `RulebaseAnalysis`
  back. Rules/queries merge by fq name (a name in two units is an error), fact types merge per name
  with `:ancestors` unioned across units, the dep-graph is recomputed over the merged production set
  so cross-unit edges exist, and each production gains `:unit`. The result is meant to be rehydrated
  — see `clara.server.tools.graph.artifacts.rehydrate` — which rebuilds the inverses over the whole
  composition, the one thing a per-unit artifact cannot contain.

  Registry I/O arrives through the same capabilities map
  `clara.server.tools.graph.shared.selection/->selection` takes, so this namespace stays free of
  `clara.server.tools.graph.artifacts.registry`, which bb cannot load. The layer-fold half of
  `clara.server.tools.graph.artifacts.compose` stays JVM-side: it needs the live annotation merge."
  (:require [clara.server.tools.graph.artifacts.hierarchy :as hierarchy]
            [clara.server.tools.graph.shared.selection :as shared-selection]
            [clojure.set :as set]))

;; ===========================================================================
;; composed analysis
;; ===========================================================================

(defn- merge-production-map
  "Merge `kind` (`:rules` or `:queries`) across units by fq name. A name claimed
  by two units is refused — two units claiming one production means the
  selection is wrong, and guessing a winner would be a silent wrong answer."
  [analyses units kind]
  (let [acc (volatile! {:productions (sorted-map) :units {}})]
    (doseq [[analysis unit] (map vector analyses units)
            [p-name production] (get analysis kind)]
      (let [uk (shared-selection/unit-key unit)
            existing (get-in @acc [:units p-name])]
        (when existing
          (throw (ex-info (format "Cannot compose: %s is claimed by both %s and %s"
                                  p-name existing uk)
                          {:production p-name :units [existing uk]})))
        (vswap! acc (fn [a]
                      (-> a
                          (assoc-in [:productions p-name] (assoc production :unit uk))
                          (assoc-in [:units p-name] uk))))))
    (:productions @acc)))

(defn- ->fact-type-map
  "The composed `:fact-types` map from `ancestors` (the already-closed unioned
  hierarchy) and the units' raw fact-type maps: per type name, `:ns` from the
  first unit that declares it, `:ancestors` ordered deepest-first via
  `clara.server.tools.graph.artifacts.hierarchy/hierarchy-order`."
  [fact-type-maps ancestors]
  (into (sorted-map)
        (map (fn [name]
               [name {:name name
                      :ns (:ns (some #(get % name) fact-type-maps))
                      :ancestors (hierarchy/hierarchy-order
                                  ancestors
                                  (get ancestors name #{}))}]))
        (keys ancestors)))

(defn union-fact-types
  "Merge slim fact-type maps from multiple units. Per name, `:ancestors` is the
  union of every unit's ancestor edge set, re-closed transitively and ordered
  deepest-first — the same closure the federated index computes, via
  `clara.server.tools.graph.artifacts.hierarchy`. `:ns` comes from the first
  unit that has the name."
  [fact-type-maps]
  (let [fact-type-maps (into [] (remove nil?) fact-type-maps)]
    (->fact-type-map fact-type-maps
                     (hierarchy/closed-ancestors
                      (hierarchy/union-ancestors fact-type-maps)))))

;; ===========================================================================
;; dep-graph recomputation over the merged production set
;; ===========================================================================

(defn- ->type-analysis-map
  "`{p-name {:consumed-types #{t} :produced-types #{t}}}` over rules and
  queries."
  [rules queries]
  (into {}
        (comp cat
              (map (fn [[p-name production]]
                     [p-name {:consumed-types (set (:lhs-types production))
                              :produced-types (set (concat (:insert-types production)
                                                           (:retract-types production)))}])))
        [rules queries]))

(defn- ->consumers-by-type
  "`{type #{p-name}}` — the consumers of each consumed type."
  [type-analysis-map]
  (let [idx (volatile! {})]
    (doseq [[p-name {:keys [consumed-types]}] type-analysis-map
            t consumed-types]
      (vswap! idx update t (fnil conj #{}) p-name))
    @idx))

(defn- ->dep-graph
  "The composed dep-graph over the merged productions, in slim form (`:upstream`
  only, a source node's entry empty). Cross-unit edges exist because the graph
  is recomputed over the unioned fact-type hierarchy rather than unioned.
  `ancestors` is the closed, unioned ancestor map — the one hierarchy every
  merge mode shares — so the producer→consumer closure direction lives in
  `clara.server.tools.graph.artifacts.hierarchy/ancestor-closure` rather than
  inline here."
  [rules queries ancestors]
  (let [type-analysis (->type-analysis-map rules queries)
        consumers-by-type (->consumers-by-type type-analysis)
        upstreams (volatile! {})]
    (doseq [[producer-name {:keys [produced-types]}] type-analysis
            pt produced-types
            :let [consumers (reduce into #{}
                                    (keep consumers-by-type
                                          (hierarchy/ancestor-closure ancestors #{pt})))]
            consumer-name consumers
            :when (not= producer-name consumer-name)]
      (vswap! upstreams update consumer-name (fnil conj #{}) producer-name))
    ;; Every production is a node; a source node keeps no :upstream key.
    (into (sorted-map)
          (map (fn [[p-name _]]
                 [p-name (cond-> {}
                           (seq (get @upstreams p-name))
                           (assoc :upstream (get @upstreams p-name)))]))
          type-analysis)))

;; ===========================================================================
;; slim block + entry point
;; ===========================================================================

(defn- ->composed-slim
  [analyses]
  {:written-by "clara.server.tools.graph.artifacts.compose"
   :dropped (set/union #{:nodes}
                       (apply set/union (map #(get-in % [:slim :dropped]) analyses)))
   :references (str "Composed analysis: every unit's slim drop set unioned, "
                    "plus :nodes (the composition never compiled).")
   :unknown-fact-types (apply set/union (map #(get-in % [:slim :unknown-fact-types]) analyses))
   :recover (into {} (mapcat #(get-in % [:slim :recover])) analyses)})

(defn ->composed-analysis
  "One slim `RulebaseAnalysis` over the selected units, which the caller asserts
  are components of ONE rulebase. `caps` is the same capabilities map
  `clara.server.tools.graph.shared.selection/->selection` takes. Each unit is
  narrowed to its `:namespaces` filter first; rules/queries merge by fq name
  (collision refused), fact types merge per name with ancestors unioned, the
  dep-graph is recomputed over the merged set, and each production gains
  `:unit`. Rehydrate the result to rebuild the inverses over the whole
  composition."
  [caps selection]
  (let [sel (shared-selection/->selection caps selection)
        analyses (mapv #(get (:analyses sel) (shared-selection/unit-key %)) selection)
        rules (merge-production-map analyses selection :rules)
        queries (merge-production-map analyses selection :queries)
        fact-types (->fact-type-map (map :fact-types analyses) (:ancestors sel))
        dep-graph (->dep-graph rules queries (:ancestors sel))]
    {:rules rules
     :queries queries
     :fact-types fact-types
     :dep-graph dep-graph
     :unresolved (vec (mapcat :unresolved analyses))
     :slim (->composed-slim analyses)}))
