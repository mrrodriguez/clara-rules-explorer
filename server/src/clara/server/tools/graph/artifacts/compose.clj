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
  (str (registry/unit-key unit) "/" layer-id))

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
      (throw (ex-info (str "Unit " (registry/unit-key unit)
                           " has no merged-rulebase-analysis to compose")
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
          (throw (ex-info (str "Cannot compose: " p-name
                               " is claimed by both " existing " and " uk)
                          {:production p-name :units [existing uk]})))
        (vswap! acc (fn [a]
                      (-> a
                          (assoc-in [:productions p-name] (assoc production :unit uk))
                          (assoc-in [:units p-name] uk))))))
    (:productions @acc)))

(defn- hierarchy-order
  "Order a set of type names descendant-first (deepest first, ties by name), the
  same rule `clara.server.tools.graph.fact-types/hierarchy-order` applies. A
  pathological cycle falls back to the lexicographically smallest remaining
  name."
  [ancestor-sets raw]
  (loop [remaining (set raw)
         ordered []]
    (if (empty? remaining)
      ordered
      (let [pick (or (->> remaining
                          (filter (fn [x]
                                    (not-any? (fn [d]
                                                (contains? (get ancestor-sets d #{}) x))
                                              remaining)))
                          (sort)
                          first)
                     (first (sort remaining)))]
        (recur (disj remaining pick) (conj ordered pick))))))

(defn union-fact-types
  "Merge slim fact-type maps from multiple units. Per name, `:ancestors` is the
  union of every unit's ancestor edge set, re-ordered descendant-first — the
  same closure the federated index computes. `:ns` comes from the first unit
  that has the name."
  [fact-type-maps]
  (let [fact-type-maps (into [] (remove nil?) fact-type-maps)
        names (into (sorted-set) (mapcat keys) fact-type-maps)
        ;; ancestors-of: name -> set of ancestors, over the unioned edge set.
        ancestors-of (into {}
                           (map (fn [name]
                                  [name (into #{} (mapcat #(get-in % [name :ancestors]))
                                              fact-type-maps)]))
                           names)
        ;; ancestors-of is extended with each ancestor's own ancestors, so the
        ;; order walk can decide which remaining node is deepest.
        ancestor-sets (reduce (fn [m [name as]]
                                (reduce (fn [m a] (update m a (fnil conj #{}) name)) m as))
                              ancestors-of
                              (mapcat (fn [name] (map (fn [a] [a (get ancestors-of a #{})]) (get ancestors-of name))) names))]
    (into (sorted-map)
          (map (fn [name]
                 (let [entry (some #(get % name) fact-type-maps)]
                   [name {:name name
                          :ns (:ns entry)
                          :ancestors (vec (hierarchy-order ancestor-sets
                                                           (get ancestors-of name #{})))}]))
               names))))

;; ===========================================================================
;; dep-graph recomputation over the merged production set
;; ===========================================================================

(defn- ->type-analysis-map
  [rules queries]
  (into {}
        (map (fn [[p-name production]]
               [p-name {:consumed-types (set (:lhs-types production))
                        :produced-types (set (concat (:insert-types production)
                                                     (:retract-types production)))}]))
        (concat rules queries)))

(defn- ->consumers-by-type
  [type-analysis-map]
  (reduce-kv (fn [idx p-name {:keys [consumed-types]}]
               (reduce #(update %1 %2 (fnil conj #{}) p-name) idx consumed-types))
             {} type-analysis-map))

(defn- ->dep-graph
  "The composed dep-graph over the merged productions, in slim form (`:upstream`
  only, a source node's entry empty). Cross-unit edges exist because the graph
  is recomputed over the unioned fact-type hierarchy rather than unioned."
  [rules queries fact-types]
  (let [type-analysis (->type-analysis-map rules queries)
        ancestors-set-fn (fn [t] (set (get-in fact-types [t :ancestors] [])))
        consumers-by-type (->consumers-by-type type-analysis)
        graph (volatile! {})]
    (doseq [[producer-name {:keys [produced-types]}] type-analysis
            :when (seq produced-types)
            pt produced-types
            :let [consumers (reduce into #{} (keep consumers-by-type
                                                   (cons pt (ancestors-set-fn pt))))]
            consumer-name consumers
            :when (not= producer-name consumer-name)]
      (vswap! graph (fn [g]
                      (-> g
                          (update-in [producer-name :downstream] (fnil conj #{}) consumer-name)
                          (update-in [consumer-name :upstream] (fnil conj #{}) producer-name)))))
    (into (sorted-map)
          (map (fn [[node {:keys [upstream]}]]
                 [node (cond-> {} (seq upstream) (assoc :upstream (set upstream)))])
               @graph))))

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
