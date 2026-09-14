(ns clara.server.tools.graph.artifacts.compose
  "Combine a caller-named selection of artifact units into one queryable
  structure.

  Two modes, by what they return:

    `fold-layers` (mode `:layers`)
        generalize `store/get-layer-stack` across units: a selection of units
        folded in caller order, lowest precedence first, into one
        `MergedAnnotations`. Layer ids are qualified `<repo>[@<branch>]/<layer-id>`
        because a `LayerId` is a per-set identity.

    `->composed-analysis` (mode `:compose`)
        the caller asserts the units are components of ONE rulebase and gets one
        slim `RulebaseAnalysis` back. Rules/queries merge by fq name (a name in
        two units is an error), fact types merge per name with `:ancestors`
        unioned across units, the dep-graph is recomputed over the merged
        production set so cross-unit edges exist, and each production gains
        `:unit`. The result is meant to be rehydrated — see
        `clara.server.tools.graph.artifacts.rehydrate` — which rebuilds the
        inverses over the whole composition, the one thing a per-unit artifact
        cannot contain.

  The selection is explicit and ordered; nothing here discovers intent."
  (:require
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.artifacts.hierarchy :as hierarchy]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.artifacts.store :as store]
   [clojure.set :as set]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; layer fold across units
;; ===========================================================================

(defn qualified-layer-id
  "A layer id qualified with its unit, so `:provenance` names whose layer a fold
  credited. `<repo>[@<branch>]/<layer-id>`."
  [unit layer-id]
  (format "%s/%s" (registry/unit-key unit) layer-id))

(defn- ->opts
  [registry unit]
  (cond-> {:root (:root registry) :repo (:repo unit)}
    (some? (:branch unit)) (assoc :branch (:branch unit))))

(defn fold-layers
  "Fold the file-backed layer stack of every unit in `selection` — caller order,
  lowest precedence first — into one `MergedAnnotations`. Each layer's `:id` is
  qualified with its unit (see `qualified-layer-id`); within one unit nothing
  changes."
  [registry selection]
  (ann.merge/merge-layers
   (mapcat (fn [unit]
             (map (fn [layer]
                    (assoc layer :id (qualified-layer-id unit (:id layer))))
                  (store/get-layer-stack (->opts registry unit))))
           selection)))

;; ===========================================================================
;; composed analysis
;; ===========================================================================

(defn- read-analysis-or-throw
  [registry unit]
  (or (registry/read-analysis registry unit)
      (throw (ex-info (format "Unit %s has no merged-rulebase-analysis to compose"
                              (registry/unit-key unit))
                      {:unit unit}))))

(defn- merge-production-map
  "Merge `kind` (`:rules` or `:queries`) across units by fq name. A name claimed
  by two units is refused — two units claiming one production means the
  selection is wrong, and guessing a winner would be a silent wrong answer."
  [analyses units kind]
  (let [acc (volatile! {:productions (sorted-map) :units {}})]
    (doseq [[analysis unit] (map vector analyses units)
            [p-name production] (get analysis kind)]
      (let [uk (registry/unit-key unit)
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

(defn union-fact-types
  "Merge slim fact-type maps from multiple units. Per name, `:ancestors` is the
  union of every unit's ancestor edge set, re-closed transitively and ordered
  deepest-first — the same closure the federated index computes, via
  `clara.server.tools.graph.artifacts.hierarchy`. `:ns` comes from the first
  unit that has the name."
  [fact-type-maps]
  (let [fact-type-maps (into [] (remove nil?) fact-type-maps)
        ancestors (hierarchy/closed-ancestors
                   (hierarchy/union-ancestors fact-type-maps))]
    (into (sorted-map)
          (map (fn [name]
                 [name {:name name
                        :ns (:ns (some #(get % name) fact-type-maps))
                        :ancestors (hierarchy/hierarchy-order
                                    ancestors
                                    (get ancestors name #{}))}]))
          (keys ancestors))))

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
  is recomputed over the unioned fact-type hierarchy rather than unioned."
  [rules queries fact-types]
  (let [type-analysis (->type-analysis-map rules queries)
        ancestors-of (fn [t] (get-in fact-types [t :ancestors] []))
        consumers-by-type (->consumers-by-type type-analysis)
        upstreams (volatile! {})]
    (doseq [[producer-name {:keys [produced-types]}] type-analysis
            pt produced-types
            :let [consumers (reduce into #{} (keep consumers-by-type
                                                   (cons pt (ancestors-of pt))))]
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
  are components of ONE rulebase. Rules/queries merge by fq name (collision
  refused), fact types merge per name with ancestors unioned, the dep-graph is
  recomputed over the merged set, and each production gains `:unit`. Rehydrate
  the result to rebuild the inverses over the whole composition."
  [registry selection]
  (registry/assert-compatible! registry selection)
  (let [analyses (mapv #(read-analysis-or-throw registry %) selection)
        rules (merge-production-map analyses selection :rules)
        queries (merge-production-map analyses selection :queries)
        fact-types (union-fact-types (map :fact-types analyses))
        dep-graph (->dep-graph rules queries fact-types)]
    {:rules rules
     :queries queries
     :fact-types fact-types
     :dep-graph dep-graph
     :unresolved (vec (mapcat :unresolved analyses))
     :slim (->composed-slim analyses)}))
