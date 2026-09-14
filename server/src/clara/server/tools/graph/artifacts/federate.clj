(ns clara.server.tools.graph.artifacts.federate
  "A federated index over a selection of artifact units that share a fact-type
  vocabulary but are NOT claimed to compose. The question the index answers is
  *how do these rulebases relate*: who produces a type, who consumes it (exactly
  or through the hierarchy), which units depend on each other through what, and
  what the union cannot answer.

  The hierarchy is per-unit and that is a defect the index has to repair: two
  units can hold different ancestor sets for the same type name, and both are
  locally correct. `->index` unions every unit's edge set, re-closes
  transitively, and records the disagreements under `:hierarchy :conflicts`
  rather than picking a winner.

  Two couplings are named separately. A type is *produced* by a rule that
  inserts it (or inserts a descendant of it); retracting a type does not
  produce it — it removes it. That supply reading (insert-only) is behind
  `:entry-points`, `:orphans`, and `:producers`. A rule *couples* to consumers
  through insert and retract alike, since both change the fact set the
  consumers match; that coupling reading is behind `:unit-edges`, matching the
  live dep-graph in `clara.server.tools.graph.core`.

  The index is a value — nothing is persisted on the path to an answer. Read the
  units, build the index, ask it."
  (:require
   [clara.server.tools.graph.artifacts.hierarchy :as hierarchy]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- unit-key [unit] (registry/unit-key unit))

;; ===========================================================================
;; reading the selection
;; ===========================================================================

(defn- read-analysis-or-throw
  [registry unit]
  (or (registry/read-analysis registry unit)
      (throw (ex-info (format "Unit %s has no merged-rulebase-analysis" (unit-key unit))
                      {:unit unit}))))

(defn- ->analyses-by-unit
  [registry selection]
  (into {}
        (map (fn [unit] [(unit-key unit) (read-analysis-or-throw registry unit)]))
        selection))

;; ===========================================================================
;; aggregate overlap refusal
;;
;; An aggregate unit (a composition, or a host's own captured whole-rulebase
;; unit) describes the same productions as the units it overlaps. Federating it
;; beside those units would silently double-count every per-unit answer, so
;; `->index` refuses the selection unless the caller opts in. See
;; `clara.server.tools.graph.artifacts.registry/aggregate-unit?`.
;; ===========================================================================

(defn- ->aggregate-units
  "The selected units recorded with an aggregate `:mode`, as
  `{:key unit-key :composed-from [unit-key …]}`."
  [registry selection]
  (into []
        (keep (fn [unit]
                (when (registry/aggregate-unit? registry unit)
                  {:key (unit-key unit)
                   :composed-from (mapv registry/unit-key
                                        (:composed-from (registry/unit-info registry unit)))})))
        selection))

(defn- assert-no-aggregate-overlap!
  "Refuse a selection that would silently double-count, before any analysis is
  read.

  An aggregate whose `:composed-from` names another selected unit is refused
  outright — that selection double-counts by construction, the same way
  `clara.server.tools.graph.artifacts.compose/->composed-analysis` refuses a
  production name claimed by two units. Any other mix of aggregate and source
  units is refused too: a captured whole-rulebase unit (an aggregate naming no
  `:composed-from`) overlaps every unit and the index cannot see which, so those
  two are never one question."
  [registry selection]
  (let [selected-keys (into #{} (map unit-key) selection)
        aggregates (->aggregate-units registry selection)]
    (doseq [{:keys [key composed-from]} aggregates
            :let [overlap (vec (sort (set/intersection selected-keys (set composed-from))))]
            :when (seq overlap)]
      (throw (ex-info (format "Cannot index: aggregate unit %s is composed from selected unit(s) %s — its productions already contain theirs, so selecting both would double-count"
                              key (pr-str overlap))
                      {:unit key
                       :composed-from composed-from
                       :selected-overlap overlap})))
    (when (and (seq aggregates)
               (< (count aggregates) (count selection)))
      (throw (ex-info (format "Cannot index: selection mixes %d aggregate unit(s) with %d source unit(s) — an aggregate describes the same rules as the units it overlaps"
                              (count aggregates)
                              (- (count selection) (count aggregates)))
                      {:aggregate-units (mapv :key aggregates)
                       :source-units (->> selection
                                          (remove #(registry/aggregate-unit? registry %))
                                          (mapv unit-key))})))))

(defn- narrow-analyses-by-unit
  "The per-unit analyses narrowed to each unit's `:namespaces` filter (a no-op
  for unfiltered units). Runs after coverage and unknown-namespace reporting,
  which read the *un*-narrowed analyses."
  [analyses-by-unit selection]
  (into {}
        (map (fn [unit]
               (let [uk (unit-key unit)]
                 [uk (registry/narrow-analysis (get analyses-by-unit uk) unit)])))
        selection))

;; ===========================================================================
;; hierarchy union + re-closure
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
;; polarity over the persisted LHS
;;
;; `:lhs` survives slimming with each node's kind intact: a group is a MAP with
;; `:condition-type` + `:children`, an accumulator is a map whose fact type is
;; under `:from`. See `clara.server.tools.graph.artifacts.slim` for what is kept.
;; ===========================================================================

(defn- ->type-polarities
  "`{fact-type #{polarity}}` for one production's `:lhs`: which polarity kind
  couples the rule to each fact type. Positive, negated (`:not`), `:exists`, and
  `:accumulated` are recorded separately rather than flattened away."
  [lhs]
  (let [acc (volatile! {})]
    (letfn [(add! [t ctx]
              (when t (vswap! acc update t (fnil conj #{}) ctx)))
            (walk [node ctx]
              (cond
                (and (map? node) (contains? node :accumulator))
                (walk (:from node) :accumulated)

                (and (map? node) (:condition-type node))
                (doseq [child (:children node)]
                  (walk child (case (:condition-type node)
                                :not :negated
                                :exists :exists
                                ctx)))

                (and (map? node) (contains? node :type))
                (add! (:type node) ctx)

                :else nil))]
      (doseq [c lhs] (walk c :positive)))
    @acc))

;; ===========================================================================
;; one pass over productions
;; ===========================================================================

(defn- ->production-maps
  "`{:matched-by {uk {ft #{rule}}} :matched-via-ancestor {uk {ft #{rule}}}
    :inserted-by {uk {ft #{rule}}} :retracted-by {uk {ft #{rule}}}
    :rule-polarities {rule {ft #{polarity}}}}`.

  `:matched-via-ancestor` is the descendant expansion of each rule's `:lhs-types`:
  a rule matching `T` is reached by any descendant of `T`, so every descendant
  records the rule (the exact match itself is `:matched-by`'s)."
  [analyses-by-unit descendants]
  (let [acc (volatile! {:matched-by {}
                        :matched-via-ancestor {}
                        :inserted-by {}
                        :retracted-by {}
                        :rule-polarities {}})
        add! (fn [m t p-name]
               (vswap! acc update-in (conj m t) (fnil conj #{}) p-name))]
    (doseq [[uk analysis] analyses-by-unit
            [p-name production rule?] (concat (map #(conj % true) (:rules analysis))
                                              (map #(conj % false) (:queries analysis)))]
      (vswap! acc assoc-in [:rule-polarities p-name] (->type-polarities (:lhs production)))
      (doseq [t (:lhs-types production)]
        (add! [:matched-by uk] t p-name)
        (doseq [d (get descendants t #{})]
          (when (not= d t)
            (add! [:matched-via-ancestor uk] d p-name))))
      (when rule?
        (doseq [t (:insert-types production)]
          (add! [:inserted-by uk] t p-name))
        (doseq [t (:retract-types production)]
          (add! [:retracted-by uk] t p-name))))
    @acc))

;; ===========================================================================
;; supply vs coupling: two named readings
;; ===========================================================================

(defn- production-produced-types
  "The fact types a production *produces* (supplies): its `:insert-types` only.
  Retracting a type does not produce it — it removes it. The supply reading
  behind `:entry-points`, `:orphans`, and `:producers`."
  [{:keys [insert-types]}]
  (set insert-types))

(defn- ->satisfies
  "`base-types` plus their closure under `hierarchy` — ancestors when
  `hierarchy` is the ancestor map, descendants when it is the descendant map.
  A consumer matching any of these is satisfied by the holder of `base-types`."
  [hierarchy base-types]
  (reduce (fn [acc t] (into acc (cons t (get hierarchy t #{})))) #{} base-types))

;; ===========================================================================
;; fact-type entries
;; ===========================================================================

(defn- per-ft
  "`{uk #{rule}}` for one fact type, out of a `{uk {ft #{rule}}}` map."
  [m ft]
  (into {} (keep (fn [[uk mm]] (when-let [rules (get mm ft)] [uk rules]))) m))

(defn- rule-polarity-for
  "The polarity kinds coupling `rule` to `ft`: from every type the rule matches
  that `ft` is (or derives from)."
  [rule-polarities descendants ft rule]
  (reduce-kv (fn [acc matched-type kinds]
               (if (or (= ft matched-type)
                       (contains? (get descendants matched-type #{}) ft))
                 (into acc kinds)
                 acc))
             #{}
             (get rule-polarities rule {})))

(defn- producer-units
  "Units that produce (supply) `ft` — a rule inserts a type that IS `ft` or
  derives from it, so the fact it makes is one of each ancestor. Uses the
  supply reading of `production-produced-types`, so it agrees with
  `:entry-points` and `:orphans`."
  [analyses-by-unit ancestors ft]
  (let [produces? (fn [[_ rule]]
                    (contains? (->satisfies ancestors (production-produced-types rule)) ft))]
    (into #{}
          (keep (fn [[uk analysis]]
                  (when (some produces? (:rules analysis)) uk)))
          analyses-by-unit)))

(defn- ->fact-type-entry
  [ft analyses-by-unit ancestors descendants maps]
  (let [{:keys [inserted-by retracted-by matched-by matched-via-ancestor rule-polarities]} maps
        matched (per-ft matched-by ft)
        via-ancestor (per-ft matched-via-ancestor ft)
        consuming (merge-with set/union matched via-ancestor)
        consuming-rules (into #{} (mapcat val) consuming)]
    {:declared-in (into #{} (keep (fn [[uk analysis]]
                                    (when (contains? (:fact-types analysis) ft) uk)))
                        analyses-by-unit)
     :inserted-by (per-ft inserted-by ft)
     :retracted-by (per-ft retracted-by ft)
     :matched-by matched
     :matched-via-ancestor via-ancestor
     :polarity (into {}
                     (map (fn [rule] [rule (rule-polarity-for rule-polarities descendants ft rule)]))
                     consuming-rules)
     :producers (producer-units analyses-by-unit ancestors ft)
     :consumers (into #{} (keys consuming))}))

(defn- ->fact-types-index
  [analyses-by-unit ancestors descendants maps]
  (into (sorted-map)
        (map (fn [ft] [ft (->fact-type-entry ft analyses-by-unit ancestors descendants maps)]))
        (keys ancestors)))

;; ===========================================================================
;; unit edges, entry points, orphans
;; ===========================================================================

(defn- ->consumed-types
  "Every fact type a unit's rules and queries match — each production's
  `:lhs-types` vector flattened, so the result is a set of fact-type names, not
  a set of vectors."
  [{:keys [rules queries]}]
  (into #{} (comp cat (mapcat :lhs-types)) [(vals rules) (vals queries)]))

(defn- ->produced-types
  "Every fact type `analysis`'s rules produce (supply) —
  `production-produced-types` unioned across the rules. Queries do not produce."
  [{:keys [rules]}]
  (into #{} (mapcat (fn [[_ rule]] (production-produced-types rule))) rules))

(defn- ->coupled-types
  "Every fact type `analysis`'s rules couple to their consumers — insert and
  retract alike, since both change the fact set the consumers match. The
  coupling reading behind `:unit-edges`, matching the live dep-graph in
  `clara.server.tools.graph.core`."
  [{:keys [rules]}]
  (into #{} (mapcat (fn [[_ {:keys [insert-types retract-types]}]]
                      (into (set insert-types) retract-types)))
        rules))

(defn- ->unit-edges
  "Cross-unit rule data-flow edges: `{[producer consumer] {:via #{ft} :rules n}}`,
  where `:via` is the coupled types — insert and retract alike, both of which
  change the consumer's fact set. Only rules participate — a query consumes but
  does not drive the producer→consumer dependency an edge records."
  [analyses-by-unit ancestors]
  (let [coupled (into {}
                      (map (fn [[uk analysis]] [uk (->coupled-types analysis)]))
                      analyses-by-unit)
        consumed (into {}
                       (map (fn [[uk {:keys [rules]}]]
                              [uk (into #{} (mapcat :lhs-types) (vals rules))]))
                       analyses-by-unit)
        satisfies (fn [uk] (->satisfies ancestors (get coupled uk #{})))
        edges (volatile! (sorted-map))]
    (doseq [[p-uk _] analyses-by-unit
            [c-uk _] analyses-by-unit
            :when (not= p-uk c-uk)
            :let [via (set/intersection (get consumed c-uk #{}) (satisfies p-uk))]
            :when (seq via)]
      (vswap! edges assoc [p-uk c-uk]
              {:via via
               :rules (->> (get-in analyses-by-unit [c-uk :rules])
                           (filter (fn [[_ r]]
                                     (seq (set/intersection (set (:lhs-types r)) via))))
                           count)}))
    @edges))

(defn- ->entry-points
  "`{unit-key #{ft}}` — types consumed in scope that no selected unit produces
  (or produces an ancestor of)."
  [analyses-by-unit ancestors]
  (let [produced (into #{} (mapcat ->produced-types) (vals analyses-by-unit))
        all-satisfies (->satisfies ancestors produced)]
    (into {}
          (keep (fn [[uk analysis]]
                  (let [entry (set/difference (->consumed-types analysis) all-satisfies)]
                    (when (seq entry) [uk entry]))))
          analyses-by-unit)))

(defn- ->orphans
  "`{unit-key #{ft}}` — types a unit produces (inserts) that nothing in scope
  consumes, nor consumes a descendant of."
  [analyses-by-unit descendants]
  (let [consumed (->> (vals analyses-by-unit)
                      (into #{} (mapcat ->consumed-types))
                      (->satisfies descendants))]
    (into {}
          (keep (fn [[uk analysis]]
                  (let [orphan (set/difference (->produced-types analysis) consumed)]
                    (when (seq orphan) [uk orphan]))))
          analyses-by-unit)))

;; ===========================================================================
;; the index
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
  selected unit covers. Computed from the *full* coverage — before any
  narrowing — so a namespace one unit covers but another's filter excludes is
  not misreported as unknown."
  [covered-by-unit selection]
  (let [requested (into #{} (mapcat #(map str (:namespaces %))) selection)
        covered (into #{} (mapcat val) covered-by-unit)]
    (->> (set/difference requested covered)
         sort
         vec)))

(defn- ->provenance
  [registry selection]
  (into {}
        (map (fn [unit]
               (let [info (registry/unit-info registry unit)]
                 [(unit-key unit)
                  (cond-> {:artifacts (set (:artifacts info))
                           :created (get-in info [:manifest-head :created])
                           :sha (get-in info [:manifest-head :sha])}
                    (:branch unit) (assoc :branch (:branch unit)))])))
        selection))

(defn ->index
  "The federated index over `selection` (an ordered vector of `UnitRef`s, each
  optionally narrowed by a `:namespaces` filter), a value. Units are read as
  slim, then narrowed to their filter; the hierarchy is unioned and re-closed;
  shape skew is refused via `registry/assert-compatible!`.

  A selection that mixes aggregate and source units is refused — an aggregate
  unit describes the same productions as the units it overlaps, so federating
  both silently double-counts. An aggregate whose `:composed-from` names
  another selected unit is refused outright."
  [registry selection]
  (assert-no-aggregate-overlap! registry selection)
  (registry/assert-compatible! registry selection)
  (let [analyses-by-unit (->analyses-by-unit registry selection)
        covered-by-unit (->covered-namespaces-by-unit analyses-by-unit)
        unknown-namespaces (->unknown-namespaces covered-by-unit selection)
        analyses-by-unit (narrow-analyses-by-unit analyses-by-unit selection)
        ancestors (->> (vals analyses-by-unit)
                       (map :fact-types)
                       hierarchy/union-ancestors
                       hierarchy/closed-ancestors)
        descendants (hierarchy/->descendants ancestors)
        maps (->production-maps analyses-by-unit descendants)
        namespaces (->namespaces covered-by-unit selection)]
    {:scope {:units (mapv #(select-keys % [:repo :branch :namespaces]) selection)
             :namespaces namespaces}
     :provenance (->provenance registry selection)
     :coverage {:units (mapv unit-key selection)
                :unknown-namespaces unknown-namespaces}
     :hierarchy {:ancestors ancestors
                 :descendants descendants
                 :conflicts (->ancestor-conflicts analyses-by-unit)}
     :fact-types (->fact-types-index analyses-by-unit ancestors descendants maps)
     :unit-edges (->unit-edges analyses-by-unit ancestors)
     :entry-points (->entry-points analyses-by-unit ancestors)
     :orphans (->orphans analyses-by-unit descendants)}))

;; ===========================================================================
;; query fns over the value
;; ===========================================================================

(defn impact-of
  "Consumers that break when `ft` changes: exact matches, and matches on any
  ancestor of `ft` (a change to the type reaches those too). With
  `{:descendants? true}`, also the consumers matching any descendant — the blast
  radius of changing a parent rather than the type named."
  ([index ft] (impact-of index ft {}))
  ([index ft {:keys [descendants?]}]
   {:fact-type ft
    :matched-by (get-in index [:fact-types ft :matched-by])
    :matched-via-ancestor (get-in index [:fact-types ft :matched-via-ancestor])
    :matched-via-descendant
    (when descendants?
      (reduce (fn [acc d]
                (merge-with set/union acc (get-in index [:fact-types d :matched-by])))
              {}
              (get-in index [:hierarchy :descendants ft] #{})))}))

(defn producers-of
  "The units (and rules) that insert `ft` — where it comes from."
  [index ft]
  (get-in index [:fact-types ft :inserted-by]))

(defn dependents-of
  "The units that depend on `unit` (consume something it produces), through what."
  [index unit]
  (let [k (if (map? unit) (unit-key unit) unit)]
    (into {}
          (keep (fn [[[producer consumer] edge]]
                  (when (= producer k) [consumer edge])))
          (:unit-edges index))))

(defn unit-dependency-graph
  "The unit-level dependency graph: `{[producer consumer] {:via #{ft} :rules n}}`."
  [index]
  (:unit-edges index))

(defn- ->unit-adjacency
  "`index`'s unit edges as an adjacency map `{unit-key #{unit-key …}}`."
  [index]
  (reduce (fn [m [[p c] _]] (update m p (fnil conj #{}) c))
          {}
          (:unit-edges index)))

(defn- ->successor-paths
  "The BFS paths one step beyond `frontier`: each path's terminal node's
  successors that `seen` has not visited, appended to the path."
  [adj seen frontier]
  (mapcat (fn [path]
            (let [node (peek path)]
              (map #(conj path %) (remove seen (get adj node #{})))))
          frontier))

(defn paths-between
  "Every shortest unit-dependency path from `a` to `b` (bounded BFS), as a set
  of vectors, or nil when there is no path. A reviewer asking how a fact gets
  from `a` to `b` usually wants to know whether there is more than one route."
  [index a b]
  (let [a (if (map? a) (unit-key a) a)
        b (if (map? b) (unit-key b) b)
        adj (->unit-adjacency index)]
    (if (= a b)
      #{[a]}
      (loop [frontier (list [a])
             seen #{a}]
        (when (seq frontier)
          (let [next-paths (->successor-paths adj seen frontier)
                found (into #{} (filter #(= (peek %) b)) next-paths)]
            (if (seq found)
              found
              (recur next-paths (into seen (map peek) next-paths)))))))))

(defn coverage-report
  "What this index cannot answer: its units and the requested namespaces no
  selected unit covers. Shape skew is not reported here — it is refused before
  the index is built, by `registry/assert-compatible!`."
  [index]
  (:coverage index))

;; ===========================================================================
;; grading against a composed reference
;; ===========================================================================

(defn- ->ns->units
  "Namespace → the units whose `:scope :namespaces` cover it."
  [index]
  (let [m (volatile! {})]
    (doseq [[uk nses] (get-in index [:scope :namespaces])
            ns nses]
      (vswap! m update ns (fnil conj #{}) uk))
    @m))

(defn- ->reference-ns
  "Production name → its namespace, off the reference's own production maps."
  [reference]
  (into {}
        (comp cat (map (fn [[name production]] [name (:ns production)])))
        [(get reference :rules {}) (get reference :queries {})]))

(defn- type-name
  "A fact-type token as a name — a persisted reference has bare names, a live
  one has serialized `TypeReference` maps."
  [t]
  (if (map? t) (:name t) t))

(defn- reference-produces?
  "Whether the reference produces `ft`: a rule inserts a type that IS `ft` or
  derives from it (the ancestor closure)."
  [reference ft]
  (let [fact-types (get reference :fact-types {})
        ancestors-of (fn [t] (into #{} (map type-name) (get-in fact-types [t :ancestors] [])))
        produces? (fn [[_ rule]]
                    (->> (:insert-types rule)
                         (map type-name)
                         (some (fn [t] (or (= ft t) (contains? (ancestors-of t) ft))))))]
    (boolean (some produces? (get reference :rules {})))))

(defn- reference-covers-upstream?
  "Whether `covers?` accepts any `upstream` production name for `producer`."
  [covers? producer upstream]
  (boolean (some #(covers? producer %) upstream)))

(defn- reference-confirms-edge?
  "Whether the reference dep-graph confirms the index's `[producer consumer]`
  unit edge: some reference production in the consumer's namespace has an
  upstream production in the producer's namespace."
  [ref-dep-graph covers? [producer consumer]]
  (boolean
   (some (fn [[consumer-name {:keys [upstream]}]]
           (and (covers? consumer consumer-name)
                (reference-covers-upstream? covers? producer upstream)))
         ref-dep-graph)))

(defn- ->confirmed-and-contradicted-edges
  "Split the index's unit edges into the ones the reference dep-graph confirms
  (a production edge from the producer unit to the consumer unit exists) and
  the ones it does not."
  [index reference]
  (let [ns->units (->ns->units index)
        ref-ns (->reference-ns reference)
        ref-dep-graph (get reference :dep-graph {})
        covers? (fn [unit p-name]
                  (contains? (get ns->units (get ref-ns p-name) #{}) unit))
        confirmed? (partial reference-confirms-edge? ref-dep-graph covers?)]
    (->> (keys (get index :unit-edges))
         (reduce (fn [[confirmed contradicted] edge]
                   (if (confirmed? edge)
                     [(conj confirmed edge) contradicted]
                     [confirmed (conj contradicted edge)]))
                 [[] []]))))

(defn- ->reference-produced-entry-points
  [index reference]
  (let [entry-points (into #{} (mapcat val) (get index :entry-points))]
    (into #{} (filter #(reference-produces? reference %)) entry-points)))

(defn- ->uncovered-namespaces
  [index reference]
  (let [covered (into #{} (mapcat val) (get-in index [:scope :namespaces]))
        reference-nses (into #{} (comp cat (keep :ns))
                             [(vals (get reference :rules {}))
                              (vals (get reference :queries {}))])]
    (->> (set/difference reference-nses covered)
         sort
         vec)))

(defn grade
  "Grade the union (`index`) against a composed reference (`reference-analysis`)
  — a captured session or monolithic run that really did compose. Reports:

    :reference-produced-entry-points — union entry points the reference has a
        producer for, so the union was missing a component rather than the
        rulebase lacking one
    :confirmed-unit-edges / :contradicted-unit-edges — the index's cross-unit
        edges the reference dep-graph confirms and contradicts
    :uncovered-namespaces — reference namespaces no unit in the index covers

  Grades the union; it does not replace it."
  [index reference]
  (let [[confirmed contradicted] (->confirmed-and-contradicted-edges index reference)]
    {:reference-produced-entry-points (->reference-produced-entry-points index reference)
     :confirmed-unit-edges confirmed
     :contradicted-unit-edges contradicted
     :uncovered-namespaces (->uncovered-namespaces index reference)}))

;; ===========================================================================
;; digest + persist!
;; ===========================================================================

(defn ->digest
  "An agent-readable reduction of the index: counts, the unit edge list, entry
  points, orphans, hierarchy conflicts, coverage gaps, and per-unit provenance.
  A function on the index, so an answer never depends on the persistence step.
  `:more` names what it omits, for a reader without the classpath."
  [index]
  (let [{:keys [fact-types unit-edges entry-points orphans hierarchy coverage provenance]} index]
    {:summary {:unit-count (count (:units coverage))
               :fact-type-count (count fact-types)
               :unit-edge-count (count unit-edges)
               :entry-point-count (count entry-points)
               :orphan-count (count orphans)
               :hierarchy-conflict-count (count (:conflicts hierarchy))}
     :unit-edges unit-edges
     :entry-points entry-points
     :orphans orphans
     :hierarchy-conflicts (:conflicts hierarchy)
     :coverage coverage
     :provenance provenance
     :more (str "The full registry index is registry-index.edn, beside this file. "
                "Ask it with clara.server.tools.graph.artifacts.federate/impact-of, "
                "producers-of, dependents-of, paths-between, unit-dependency-graph, "
                "and coverage-report.")}))

(defn persist!
  "Write `registry-index.edn` and `registry-digest.edn` to an explicit `:dir`.
  Takes no root and derives no directory name: how a host names the answer to
  one cross-unit question is the host's, and the same registry answers many.
  Returns `{:index path :digest path}`."
  [index {:keys [dir]}]
  (when (str/blank? dir)
    (throw (ex-info "federate/persist! requires an explicit :dir" {})))
  (let [index-file (store/get-artifact-file :registry-index {:dir dir})
        digest-file (store/get-artifact-file :registry-digest {:dir dir})]
    (io/make-parents index-file)
    (edn-io/write-edn-file! index-file index)
    (edn-io/write-edn-file! digest-file (->digest index))
    {:index (str index-file)
     :digest (str digest-file)}))
