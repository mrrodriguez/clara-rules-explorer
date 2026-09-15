(ns clara.server.tools.graph.artifacts.selection
  "Materialize a caller-named selection of artifact units for merging: read each
  unit's slim analysis (refusing absent analysis and shape skew), narrow each to
  its `:namespaces` filter, and compute the unioned, re-closed hierarchy and the
  namespace coverage — once, for every merge mode.

  `clara.server.tools.graph.artifacts.compose` and
  `clara.server.tools.graph.artifacts.federate` both consume this: it is the
  selection preamble the two modes used to each perform for themselves. Both
  need the same per-unit narrowed analyses, the same
  `registry/assert-compatible!` refusal of shape skew, and the same unioned
  hierarchy. `federate` additionally reads the hierarchy conflicts and the
  coverage report; `compose` reads the closed ancestors for its fact-type merge.

  Nothing here decides which units belong together — the selection arrives named
  and ordered, and this namespace returns a value describing it."
  (:require
   [clara.server.tools.graph.artifacts.hierarchy :as hierarchy]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clojure.set :as set]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; reading the selection
;; ===========================================================================

(defn- read-analysis!
  "The unit's slim analysis, throwing when absent. `assert-compatible!` already
  refuses a selection with an analysis-less unit, so this is the defensive
  second check that turns a would-be NPE into a named error."
  [registry unit]
  (or (registry/read-analysis registry unit)
      (throw (ex-info (format "Unit %s has no merged-rulebase-analysis to merge"
                              (registry/unit-key unit))
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
               (let [uk (registry/unit-key unit)]
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
  "A mergeable selection of `units` under `registry`, as one value:

    :analyses            `{unit-key slim-analysis}`, each narrowed to its
                         unit's `:namespaces` filter
    :ancestors          the unioned, transitively re-closed ancestor map
    :descendants        its transpose
    :hierarchy-conflicts  `{ft {unit-key #{ft}}}` where units disagree
    :coverage           `{:units [unit-key] :namespaces {unit-key [ns]}
                          :unknown-namespaces [ns]}`

  Throws (via `registry/assert-compatible!`) when the units do not share one
  slim shape or a unit has no analysis to merge. The selection is read in
  caller order; nothing here reorders it."
  [registry selection]
  (registry/assert-compatible! registry selection)
  (let [un-narrowed (into {}
                          (map (fn [unit]
                                 [(registry/unit-key unit) (read-analysis! registry unit)]))
                          selection)
        covered-by-unit (->covered-namespaces-by-unit un-narrowed)
        analyses (into {}
                       (map (fn [unit]
                              [(registry/unit-key unit)
                               (registry/narrow-analysis
                                (get un-narrowed (registry/unit-key unit)) unit)]))
                       selection)
        ancestors (->> (vals analyses)
                       (map :fact-types)
                       hierarchy/union-ancestors
                       hierarchy/closed-ancestors)]
    {:analyses analyses
     :ancestors ancestors
     :descendants (hierarchy/->descendants ancestors)
     :hierarchy-conflicts (->ancestor-conflicts analyses)
     :coverage {:units (mapv registry/unit-key selection)
                :namespaces (->namespaces covered-by-unit selection)
                :unknown-namespaces (->unknown-namespaces covered-by-unit selection)}}))
