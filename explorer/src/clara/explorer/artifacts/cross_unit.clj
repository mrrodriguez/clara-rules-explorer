(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.cross-unit
  "Cross-unit readings over one (composed) analysis: entry points and unit
  edges. Pure — every fn takes the already-read parts, so the bb report and
  `clara.explorer.artifacts.diff` share one definition rather than restating it.

  Entry points are types a production matches that no rule produces (a rule
  produces what it inserts, and each of those types' ancestors; retraction does
  not produce). Unit edges are cross-unit producer→consumer edges read off the
  recomputed dep-graph and `:unit` attribution."
  (:require [clara.explorer.artifacts.hierarchy :as hierarchy]
            [clojure.set :as set]))

(defn ->ancestor-map
  "`fact-types` as a closed `{type-name #{ancestor-name}}` map — the `:ancestors`
  vectors are already transitively closed on disk."
  [fact-types]
  (into {} (map (fn [[n {:keys [ancestors]}]] [n (set ancestors)])) fact-types))

(defn produced-types
  "The fact types the rules produce: each insert type plus its ancestors (a fact
  of a descendant type IS-A each ancestor). Retraction does not produce."
  [rules ancestors]
  (into #{}
        (mapcat (fn [[_ {:keys [insert-types]}]] (hierarchy/ancestor-closure ancestors (set insert-types))))
        rules))

(defn all-entry-points
  "The flat set of entry-point types across `index` (`{:rules … :queries …}`):
  types some production matches that no rule in the unit produces."
  [index fact-types]
  (let [rules (:rules index)
        queries (:queries index)
        ancestors (->ancestor-map fact-types)
        produced (produced-types rules ancestors)
        consumed (into #{} (comp cat (mapcat :lhs-types)) [(vals rules) (vals queries)])]
    (set/difference consumed produced)))

(defn entry-points
  "`{unit-key {ft consume-count}}` — entry-point types grouped by consuming
  unit, each with how many productions consume it. Productions without a
  `:unit` (a ruleset unit) group under nil."
  [index fact-types]
  (let [rules (:rules index)
        queries (:queries index)
        ancestors (->ancestor-map fact-types)
        produced (produced-types rules ancestors)]
    (into (sorted-map)
          (keep (fn [[unit prods]]
                  (let [consumed (frequencies
                                  (mapcat (fn [[_ p]] (distinct (:lhs-types p))) prods))
                        entry (into (sorted-map)
                                    (filter (fn [[ft _]] (not (contains? produced ft))))
                                    consumed)]
                    (when (seq entry) [unit entry]))))
          (group-by (fn [[_ p]] (:unit p)) (concat rules queries)))))

(defn unit-edges
  "`{[producer-unit consumer-unit] {:via #{ft} :rules n}}` for a composed unit:
  there is an edge when a rule in `producer-unit` is upstream (dep-graph) of a
  production in `consumer-unit`. `:via` is the upstream rules' inserted and
  retracted types, each with its ancestors, intersected with the downstream
  production's `:lhs-types`. `:rules` counts the distinct upstream rules.
  Edges within one unit are not listed."
  [index dep-graph fact-types]
  (let [rules (:rules index)
        queries (:queries index)
        productions (merge queries rules)
        ancestors (->ancestor-map fact-types)
        coupled (fn [rule]
                  (into (set (:insert-types rule)) (:retract-types rule)))
        edges (volatile! {})]
    (doseq [[down-name {:keys [upstream]}] dep-graph
            :let [down (get productions down-name)
                  consumer (:unit down)]
            :when (and consumer upstream)]
      (doseq [up-name upstream
              :let [up (get rules up-name)
                    producer (:unit up)]
              :when (and producer (not= producer consumer))
              :let [via (set/intersection (set (:lhs-types down))
                                          (hierarchy/ancestor-closure ancestors (coupled up)))]
              :when (seq via)]
        (vswap! edges update [producer consumer]
                (fn [acc]
                  (-> (or acc {:via #{} :rules #{}})
                      (update :via set/union via)
                      (update :rules conj up-name))))))
    (into (sorted-map)
          (map (fn [[k {:keys [via rules]}]]
                 [k {:via via :rules (count rules)}]))
          @edges)))
