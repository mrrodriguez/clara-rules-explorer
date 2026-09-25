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

  The pure `:compose` half lives in `clara.server.tools.graph.shared.compose`,
  which takes the registry I/O as an injected capabilities map so the babashka
  editor client can share it without loading
  `clara.server.tools.graph.artifacts.registry`. The `:layers` half stays here —
  it needs the live annotation merge (`clara.server.tools.graph.annotations.merge`)
  and the file-backed layer stack (`clara.server.tools.graph.artifacts.store`).

  The selection is explicit and ordered; nothing here discovers intent."
  (:require
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.shared.compose :as shared-compose]
   [clojure.walk :as walk]))

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

(defn- ->narrowed-layer
  "Qualify `layer`'s id with `unit` and narrow its annotations to the unit's
  `:namespaces` filter (a no-op for unfiltered units), so a scoped fold folds
  only the scope's contribution."
  [unit layer]
  (-> layer
      (assoc :id (qualified-layer-id unit (:id layer)))
      (registry/narrow-annotations unit)))

(defn- ->unit-layer-pairs
  "One unit's file-backed layers, each as `[raw narrowed]` — `raw` keeps the
  standard role id, `narrowed` is qualified + `:namespaces`-filtered. Read once
  per unit and shared by `fold-layers` (which folds the narrowed halves in
  selection order) and `->standard-role-layers` (which selects a role by the raw
  id before it is qualified away)."
  [registry unit]
  (map (fn [layer] [layer (->narrowed-layer unit layer)])
       (store/get-layer-stack (->opts registry unit))))

(defn- ->narrowed-namespaces
  "The per-unit `:namespaces` filters of `selection`, as `{unit-key [ns …]}`,
  for the units that carry one. Empty when no unit is narrowed — the persisted
  role layer's `:source` says so."
  [selection]
  (into (sorted-map)
        (keep (fn [unit]
                (when-let [nses (seq (:namespaces unit))]
                  [(registry/unit-key unit) (mapv str nses)])))
        selection))

(defn fold-layers
  "Fold the file-backed layer stack of every unit in `selection` — caller order,
  lowest precedence first — into one `MergedAnnotations`. Each layer's `:id` is
  qualified with its unit (see `qualified-layer-id`) and narrowed to the unit's
  `:namespaces` filter first; within one unit nothing else changes."
  [registry selection]
  (ann.merge/merge-layers
   (mapcat (fn [unit] (map second (->unit-layer-pairs registry unit)))
           selection)))

(defn- strip-derived-callsite-provenance
  "Remove the `:from-layer` stamps `ann.merge/merge-layers` computes.

   A layer file carries no `:from-layer` of its own — the merge computes it.
   The intermediate fold in `->standard-role-layers` runs over re-id'd layers,
   so it stamps every callsite with a qualified id that would otherwise leak
   into the flattened role layer written back to disk."
  [annotations]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (some #{:callsites} (keys x)))
       (update x :callsites (fn [callsites]
                              (mapv #(dissoc % :from-layer) callsites)))
       x))
   annotations))

(defn ->standard-role-layers
  "Flatten `selection`'s file-backed layers into at most one layer per standard
   artifact role (`:auto`, `:memory`, `:agent`).

   Each unit's layer for a role is re-id'd with `qualified-layer-id` so the
   per-role fold can attribute origins correctly, and narrowed to the unit's
   `:namespaces` filter first; the resulting layer then carries the standard
   role `:id` so it can be written as a normal single-unit layer file.
   Unit-level provenance is deliberately left out of the returned layers — it
   belongs in the manifest / federated sidecar — so the on-disk shape is
   indistinguishable from any other unit's. A narrowed fold records the
   per-unit filter under `:source :namespaces`, so a reader of the persisted
   layer can tell a scoped set from a whole one.

   Returns an ordered `{role Layer}` map, omitting roles no selected unit
   contributed."
  [registry selection]
  (let [narrowed (->narrowed-namespaces selection)]
    (into (array-map)
          (keep (fn [[role role-id]]
                  (let [layers (mapcat (fn [unit]
                                         (keep (fn [[raw layer]]
                                                 (when (= role-id (:id raw)) layer))
                                               (->unit-layer-pairs registry unit)))
                                       selection)]
                    (when (seq layers)
                      (let [folded (ann.merge/merge-layers layers)]
                        [role (ann.merge/->layer
                               {:id role-id
                                :annotations (strip-derived-callsite-provenance
                                              (ann.merge/annotations folded))
                                :source (cond-> {:composed-of (mapv registry/unit-key selection)
                                                 :role role}
                                          (seq narrowed) (assoc :namespaces narrowed))})]))))
                store/layer-artifacts))))

;; ===========================================================================
;; composed analysis (delegates to the shared, bb-loadable implementation)
;; ===========================================================================

(defn union-fact-types
  "Merge slim fact-type maps from multiple units — delegates to
  `clara.server.tools.graph.shared.compose/union-fact-types`. Per name,
  `:ancestors` is the union of every unit's ancestor edge set, re-closed
  transitively and ordered deepest-first. `:ns` comes from the first unit that
  has the name."
  [fact-type-maps]
  (shared-compose/union-fact-types fact-type-maps))

(defn ->composed-analysis
  "One slim `RulebaseAnalysis` over the selected units, which the caller asserts
  are components of ONE rulebase — delegates to
  `clara.server.tools.graph.shared.compose/->composed-analysis` with the JVM
  registry's capabilities. Each unit is narrowed to its `:namespaces` filter
  first; rules/queries merge by fq name (collision refused), fact types merge
  per name with ancestors unioned, the dep-graph is recomputed over the merged
  set, and each production gains `:unit`. Rehydrate the result to rebuild the
  inverses over the whole composition."
  [registry selection]
  (shared-compose/->composed-analysis
   {:read-analysis #(registry/read-analysis registry %)
    :assert-compatible! #(registry/assert-compatible! registry %)}
   selection))
