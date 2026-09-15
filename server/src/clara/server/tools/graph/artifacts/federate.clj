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
   [clara.server.tools.graph.artifacts.selection :as selection]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- unit-key [unit] (registry/unit-key unit))

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
                    (contains? (hierarchy/ancestor-closure ancestors (production-produced-types rule)) ft))]
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
        satisfies (fn [uk] (hierarchy/ancestor-closure ancestors (get coupled uk #{})))
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
        all-satisfies (hierarchy/ancestor-closure ancestors produced)]
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
                      (hierarchy/descendant-closure descendants))]
    (into {}
          (keep (fn [[uk analysis]]
                  (let [orphan (set/difference (->produced-types analysis) consumed)]
                    (when (seq orphan) [uk orphan]))))
          analyses-by-unit)))

;; ===========================================================================
;; the index
;; ===========================================================================

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
  another selected unit is refused outright.

  `opts` may carry `:label` (or `:question`), recorded into the index's
  `:scope` so a persisted index names which cross-unit question it answers
  without depending on its directory name."
  ([registry selection] (->index registry selection {}))
  ([registry selection {:keys [label]}]
   (assert-no-aggregate-overlap! registry selection)
   (let [{:keys [analyses ancestors descendants hierarchy-conflicts coverage]}
         (selection/->selection registry selection)
         maps (->production-maps analyses descendants)]
     {:scope (cond-> {:units (mapv #(select-keys % [:repo :branch :namespaces]) selection)
                      :namespaces (:namespaces coverage)}
               (some? label) (assoc :label label))
      :provenance (->provenance registry selection)
      :coverage {:units (:units coverage)
                 :unknown-namespaces (:unknown-namespaces coverage)}
      :hierarchy {:ancestors ancestors
                  :descendants descendants
                  :conflicts hierarchy-conflicts}
      :fact-types (->fact-types-index analyses ancestors descendants maps)
      :unit-edges (->unit-edges analyses ancestors)
      :entry-points (->entry-points analyses ancestors)
      :orphans (->orphans analyses descendants)})))

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
;; diff — branch-vs-mainline over two indexes
;; ===========================================================================

(defn- diff-set
  "The elements of `after` not in `before`, as a sorted vector."
  [before after]
  (vec (sort (set/difference (set after) (set before)))))

(defn- ->rebased-units
  "The units present in both indexes under the same `:repo` but a different
  `:branch` (or set of branch labels), as `{:repo … :from [unit-key …] :to
  [unit-key …]}`. A repo selected once on each side is the normal case; the
  vectors keep the shape total when a selection names more than one branch."
  [before-units after-units]
  (let [by-repo (fn [units]
                  (into (sorted-map)
                        (map (fn [[repo us]] [repo (sort (map unit-key us))]))
                        (group-by :repo units)))
        before (by-repo before-units)
        after (by-repo after-units)]
    (into []
          (keep (fn [repo]
                  (when (and (contains? before repo) (contains? after repo)
                             (not= (get before repo) (get after repo)))
                    {:repo repo
                     :from (get before repo)
                     :to (get after repo)})))
          (sort (set/union (set (keys before)) (set (keys after)))))))

(defn- ->units-diff
  [before after]
  (let [before-units (get-in before [:scope :units] [])
        after-units (get-in after [:scope :units] [])
        before-keys (into #{} (map unit-key) before-units)
        after-keys (into #{} (map unit-key) after-units)]
    {:added (diff-set before-keys after-keys)
     :removed (diff-set after-keys before-keys)
     :rebased (->rebased-units before-units after-units)}))

(defn- ->unit-edges-diff
  [before-edges after-edges]
  (let [before-keys (set (keys before-edges))
        after-keys (set (keys after-edges))
        changed (into (sorted-map)
                      (keep (fn [k]
                              (let [b-via (set (get-in before-edges [k :via]))
                                    a-via (set (get-in after-edges [k :via]))]
                                (when (not= b-via a-via)
                                  [k {:added (set/difference a-via b-via)
                                      :removed (set/difference b-via a-via)}]))))
                      (set/intersection before-keys after-keys))]
    {:added (diff-set before-keys after-keys)
     :removed (diff-set after-keys before-keys)
     :changed changed}))

(defn- ->fact-type-entry-diff
  [b-ft a-ft]
  (let [b-producers (set (:producers b-ft))
        a-producers (set (:producers a-ft))
        b-consumers (set (:consumers b-ft))
        a-consumers (set (:consumers a-ft))]
    (cond-> {}
      (not= b-producers a-producers)
      (assoc :producers {:added (set/difference a-producers b-producers)
                         :removed (set/difference b-producers a-producers)})

      (not= b-consumers a-consumers)
      (assoc :consumers {:added (set/difference a-consumers b-consumers)
                         :removed (set/difference b-consumers a-consumers)}))))

(defn- ->fact-types-diff
  [before after]
  (let [fts (set/union (set (keys (:fact-types before)))
                       (set (keys (:fact-types after))))]
    (into (sorted-map)
          (keep (fn [ft]
                  (let [d (->fact-type-entry-diff (get-in before [:fact-types ft])
                                                  (get-in after [:fact-types ft]))]
                    (when (seq d) [ft d]))))
          fts)))

(defn- ->per-unit-set-diff
  "Diff `{unit-key #{ft}}` maps (entry points, orphans): `:added` is what each
  unit gained, `:resolved` is what it lost. Units with no change are omitted."
  [before after]
  (let [units (set/union (set (keys before)) (set (keys after)))
        added (into (sorted-map)
                    (keep (fn [uk]
                            (let [d (set/difference (set (get after uk))
                                                    (set (get before uk)))]
                              (when (seq d) [uk d]))))
                    units)
        resolved (into (sorted-map)
                       (keep (fn [uk]
                               (let [d (set/difference (set (get before uk))
                                                       (set (get after uk)))]
                                 (when (seq d) [uk d]))))
                       units)]
    {:added added :resolved resolved}))

(defn- ->hierarchy-diff
  [before after]
  (let [b-conflicts (set (keys (get-in before [:hierarchy :conflicts])))
        a-conflicts (set (keys (get-in after [:hierarchy :conflicts])))]
    {:conflicts-added (diff-set b-conflicts a-conflicts)
     :conflicts-resolved (diff-set a-conflicts b-conflicts)}))

(defn diff
  "Diff two indexes over overlapping unit sets — the branch-vs-mainline
  question: build the index twice over the same units, once mainline and once
  with a branch variant selected, and compare. A pure function of the two
  values.

  Reports, per key:

    :units        selection differences, incl. branch swaps (`:rebased`)
    :unit-edges   edges added/removed, and `:via` sets that grew or shrank
    :fact-types   per-type producer/consumer unit changes
    :entry-points types each unit newly cannot satisfy / now can
    :orphans      types each unit newly produces unused / now has a consumer for
    :hierarchy    hierarchy conflicts that appeared / disappeared

  A diff of an index against itself is empty in every key."
  [before after]
  {:units (->units-diff before after)
   :unit-edges (->unit-edges-diff (:unit-edges before) (:unit-edges after))
   :fact-types (->fact-types-diff before after)
   :entry-points (->per-unit-set-diff (:entry-points before) (:entry-points after))
   :orphans (->per-unit-set-diff (:orphans before) (:orphans after))
   :hierarchy (->hierarchy-diff before after)})

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
  "An agent-readable reduction of the index: the scope it is of (units,
  namespaces, and the caller's `:label`), counts, the unit edge list, entry
  points, orphans, hierarchy conflicts, coverage gaps, and per-unit provenance.
  A function on the index, so an answer never depends on the persistence step.
  `:more` names what it omits, for a reader without the classpath."
  [index]
  (let [{:keys [scope fact-types unit-edges entry-points orphans hierarchy coverage provenance]} index]
    {:summary {:unit-count (count (:units coverage))
               :fact-type-count (count fact-types)
               :unit-edge-count (count unit-edges)
               :entry-point-count (count entry-points)
               :orphan-count (count orphans)
               :hierarchy-conflict-count (count (:conflicts hierarchy))}
     :scope scope
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
  An optional `:label` is recorded into the index's `:scope` before writing (a
  label the index already carries is kept unless overridden), so the written
  files name which question they answer without depending on the directory
  name. Returns `{:index path :digest path}`."
  [index {:keys [dir label]}]
  (when (str/blank? dir)
    (throw (ex-info "federate/persist! requires an explicit :dir" {})))
  (let [index (cond-> index
                (some? label) (assoc-in [:scope :label] label))
        index-file (store/get-artifact-file :registry-index {:dir dir})
        digest-file (store/get-artifact-file :registry-digest {:dir dir})]
    (io/make-parents index-file)
    (edn-io/write-edn-file! index-file index)
    (edn-io/write-edn-file! digest-file (->digest index))
    {:index (str index-file)
     :digest (str digest-file)}))

(defn read-index
  "Read the `registry-index.edn` `persist!` wrote under `:dir` back into the
  index value — a plain map every query fn already works on. Nil when the file
  is absent, matching every other reader here."
  [{:keys [dir]}]
  (edn-io/read-edn-file (store/get-artifact-file :registry-index {:dir dir})))

(defn read-digest
  "Read the `registry-digest.edn` `persist!` wrote under `:dir` back into the
  digest value, or nil when absent."
  [{:keys [dir]}]
  (edn-io/read-edn-file (store/get-artifact-file :registry-digest {:dir dir})))
