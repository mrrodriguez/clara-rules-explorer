(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.shared.selection
  "The merge preamble shared by the JVM artifact merge and the babashka editor client: read a
  caller-named selection of artifact units, narrow each to its `:namespaces` filter, and compute
  the unioned, re-closed hierarchy and the namespace coverage — once, for every merge mode.

  Registry I/O is injected through the capabilities map so this namespace stays free of
  `clara.server.tools.graph.artifacts.registry`, which bb cannot load (it transitively pulls
  `clara.server.tools.graph.serialize` → `clara.rules.schema`). `:read-analysis` reads one unit's
  slim analysis, `:assert-compatible!` refuses shape skew, and the pure per-unit helpers
  (`unit-key`, `narrow-analysis`) live here — `clara.server.tools.graph.artifacts.registry`
  delegates to them so the JVM and bb share one definition.

  Nothing here decides which units belong together — the selection arrives named and ordered, and
  `->selection` returns a value describing it."
  (:require [clara.server.tools.graph.artifacts.hierarchy :as hierarchy]
            [clojure.set :as set]))

;; ===========================================================================
;; per-unit helpers (pure — shared with `artifacts.registry`)
;; ===========================================================================

(defn unit-key
  "The string handle for a unit ref: `<repo>[@<branch>]`. A `UnitRef` map is not
  a comparable map key under the library's own `sorted-map` convention, so maps
  keyed by unit use this."
  [{:keys [repo branch]}]
  (str repo (when (seq branch) (str "@" branch))))

(defn narrow-analysis
  "Narrow `analysis` to `unit`'s `:namespaces` filter, when present: `:rules`
  and `:queries` keep only the productions whose `:ns` is in the filter (as
  strings). `:fact-types` stays whole — keyed by type, not namespace, and the
  hierarchy benefits from staying global — and `:dep-graph` is left alone,
  because the compose merge recomputes it over the merged productions.
  `:unresolved` and `:slim` pass through.

  Without a filter the analysis is returned unchanged. Both
  `clara.server.tools.graph.artifacts.federate/->index` and the compose merge
  apply this when they read a unit for a merge, so a `UnitRef` narrowed to a
  subset of a unit's namespaces excludes the productions outside that subset."
  [analysis unit]
  (if-let [nses (:namespaces unit)]
    (let [nses (into #{} (map str) nses)
          keep? (fn [[_ {:keys [ns]}]] (contains? nses (str ns)))
          narrow (fn [productions]
                   (into (sorted-map) (filter keep?) productions))]
      (cond-> analysis
        (contains? analysis :rules) (update :rules narrow)
        (contains? analysis :queries) (update :queries narrow)))
    analysis))

;; ===========================================================================
;; reading the selection
;; ===========================================================================

(defn- read-analysis!
  "The unit's slim analysis, throwing when absent. `assert-compatible!` already
  refuses a selection with an analysis-less unit, so this is the defensive
  second check that turns a would-be NPE into a named error."
  [read-analysis unit]
  (or (read-analysis unit)
      (throw (ex-info (format "Unit %s has no merged-rulebase-analysis to merge"
                              (unit-key unit))
                      {:unit unit}))))

;; ===========================================================================
;; namespace coverage
;;
;; Coverage is computed from the *un*-narrowed analyses — a namespace one unit
;; covers but another unit's filter excludes must not be misreported as unknown.
;; ===========================================================================

(defn- ->covered-namespaces
  "The namespaces a unit's rules and queries live in."
  [{:keys [rules queries]}]
  (into #{} (comp cat (keep :ns)) [(vals rules) (vals queries)]))

(defn- ->covered-namespaces-by-unit
  "`{unit-key #{ns}}` — the full namespace coverage of each unit, before any
  per-unit `:namespaces` filter narrows it."
  [analyses-by-unit]
  (into {}
        (map (fn [[uk analysis]] [uk (->covered-namespaces analysis)]))
        analyses-by-unit))

(defn- ->requested-namespaces
  "A unit's `:namespaces` filter as a set of strings, or nil when unfiltered."
  [unit]
  (when-let [nses (:namespaces unit)]
    (into #{} (map str) nses)))

(defn- ->scoped-namespaces
  "`covered` narrowed to `requested` — or `covered` when the unit is unfiltered —
  as a sorted vector."
  [covered requested]
  (vec (sort (if requested (set/intersection covered requested) covered))))

(defn- ->namespaces
  "`{unit-key [ns …]}` — the namespaces each unit covers, narrowed to the
  caller's per-unit `:namespaces` filter when one is given. A namespace the
  caller names but no unit covers is reported under `:coverage
  :unknown-namespaces`, not here."
  [covered-by-unit selection]
  (into {}
        (map (fn [unit]
               (let [uk (unit-key unit)]
                 [uk (->scoped-namespaces
                      (get covered-by-unit uk #{})
                      (->requested-namespaces unit))])))
        selection))

(defn- ->unknown-namespaces
  "Requested namespaces (the union of per-unit `:namespaces` filters) that no
  selected unit covers."
  [covered-by-unit selection]
  (let [requested (into #{} (mapcat #(map str (:namespaces %))) selection)
        covered (into #{} (mapcat val) covered-by-unit)]
    (->> (set/difference requested covered)
         sort
         vec)))

;; ===========================================================================
;; the unioned hierarchy
;; ===========================================================================

(defn- ->ancestor-conflicts
  "The fact types whose ancestor sets differ between units: `{ft {unit #{ft}}}`.
  A single set (or units that agree) is no conflict and is not recorded."
  [analyses-by-unit]
  (let [per-ft (reduce (fn [acc [uk analysis]]
                         (reduce-kv (fn [acc ft {:keys [ancestors]}]
                                      (assoc-in acc [ft uk] (set ancestors)))
                                    acc
                                    (:fact-types analysis)))
                       {}
                       analyses-by-unit)]
    (into (sorted-map)
          (keep (fn [[ft unit-sets]]
                  (when (> (count (into #{} (vals unit-sets))) 1)
                    [ft unit-sets])))
          per-ft)))

;; ===========================================================================
;; the selection
;; ===========================================================================

(defn ->selection
  "A mergeable selection of `units` under the capabilities map `caps`
  (`{:read-analysis f :assert-compatible! f}`), as one value:

    :analyses            `{unit-key slim-analysis}`, each narrowed to its
                         unit's `:namespaces` filter
    :ancestors          the unioned, transitively re-closed ancestor map
    :descendants        its transpose
    :hierarchy-conflicts  `{ft {unit-key #{ft}}}` where units disagree
    :coverage           `{:units [unit-key] :namespaces {unit-key [ns]}
                          :unknown-namespaces [ns]}`

  Throws (via `assert-compatible!`) when the units do not share one slim shape
  or a unit has no analysis to merge. The selection is read in caller order;
  nothing here reorders it."
  [caps selection]
  (let [{:keys [read-analysis assert-compatible!]} caps]
    (assert-compatible! selection)
    (let [un-narrowed (into {}
                            (map (fn [unit]
                                   [(unit-key unit) (read-analysis! read-analysis unit)]))
                            selection)
          covered-by-unit (->covered-namespaces-by-unit un-narrowed)
          analyses (into {}
                         (map (fn [unit]
                                [(unit-key unit)
                                 (narrow-analysis
                                  (get un-narrowed (unit-key unit)) unit)]))
                         selection)
          ancestors (->> (vals analyses)
                         (map :fact-types)
                         hierarchy/union-ancestors
                         hierarchy/closed-ancestors)]
      {:analyses analyses
       :ancestors ancestors
       :descendants (hierarchy/->descendants ancestors)
       :hierarchy-conflicts (->ancestor-conflicts analyses)
       :coverage {:units (mapv unit-key selection)
                  :namespaces (->namespaces covered-by-unit selection)
                  :unknown-namespaces (->unknown-namespaces covered-by-unit selection)}})))
