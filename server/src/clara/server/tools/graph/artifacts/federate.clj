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

  The index is a value — nothing is persisted on the path to an answer. Read the
  units, build the index, ask it."
  (:require
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
      (throw (ex-info (str "Unit " (unit-key unit) " has no merged-rulebase-analysis")
                      {:unit unit}))))

(defn- ->analyses-by-unit
  [registry selection]
  (into {}
        (map (fn [unit] [(unit-key unit) (read-analysis-or-throw registry unit)]))
        selection))

;; ===========================================================================
;; hierarchy union + re-closure
;; ===========================================================================

(defn- ->raw-ancestors
  "`{type-name #{ancestor-name …}}` unioned across every unit's slim fact-type
  map."
  [analyses]
  (reduce (fn [acc analysis]
            (reduce-kv (fn [acc ft {:keys [ancestors]}]
                         (update acc ft (fnil into #{}) ancestors))
                       acc
                       (:fact-types analysis)))
          {}
          analyses))

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

(defn- ->closed-ancestors
  "Transitively close the unioned ancestor sets: a `derive` that lived in a
  component one unit did not load is absent from that unit, so the union can
  contain a type that a locally-correct unit's list never reached."
  [raw]
  (loop [m raw]
    (let [m' (reduce-kv (fn [acc ft as]
                          (let [expanded (into as (mapcat #(get m % #{})) as)]
                            (if (= as expanded) acc (assoc acc ft expanded))))
                        m m)]
      (if (= m' m) m' (recur m')))))

(defn- ->descendants
  "Transpose of the closed ancestor map: `{ancestor-name #{descendant-name …}}`."
  [ancestors]
  (reduce-kv (fn [acc ft as]
               (reduce (fn [acc a] (update acc a (fnil conj #{}) ft)) acc as))
             {}
             ancestors))

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

(defn- add-matched-via-ancestor
  "Record `p-name` under every descendant of `t` (except `t` itself — that is
  the exact match `:matched-by` already records)."
  [m p-name t descendants]
  (reduce (fn [m d]
            (if (= d t)
              m
              (update-in m [d] (fnil conj #{}) p-name)))
          m
          (get descendants t #{})))

(defn- ->production-maps
  "`{:matched-by {uk {ft #{rule}}} :matched-via-ancestor {uk {ft #{rule}}}
    :inserted-by {uk {ft #{rule}}} :retracted-by {uk {ft #{rule}}}
    :rule-polarities {rule {ft #{polarity}}}}`.

  `:matched-via-ancestor` is the descendant expansion of each rule's `:lhs-types`:
  a rule matching `T` is reached by any descendant of `T`, so every descendant
  records the rule."
  [analyses-by-unit descendants]
  (reduce
   (fn [acc [uk analysis]]
     (let [rules (get analysis :rules {})
           queries (get analysis :queries {})
           productions (concat (map #(conj % true) rules)
                               (map #(conj % false) queries))]
       (reduce
        (fn [acc [p-name production rule?]]
          (let [lhs-types (:lhs-types production)
                acc (assoc-in acc [:rule-polarities p-name]
                              (->type-polarities (:lhs production)))
                acc (reduce (fn [acc t]
                              (-> acc
                                  (update-in [:matched-by uk t] (fnil conj #{}) p-name)
                                  (update-in [:matched-via-ancestor uk]
                                             #(add-matched-via-ancestor % p-name t descendants))))
                            acc
                            lhs-types)
                acc (if rule?
                      (reduce (fn [acc t] (update-in acc [:inserted-by uk t] (fnil conj #{}) p-name))
                              acc
                              (:insert-types production))
                      acc)
                acc (if rule?
                      (reduce (fn [acc t] (update-in acc [:retracted-by uk t] (fnil conj #{}) p-name))
                              acc
                              (:retract-types production))
                      acc)]
            acc))
        acc
        productions)))
   {:matched-by {} :matched-via-ancestor {} :inserted-by {} :retracted-by {} :rule-polarities {}}
   analyses-by-unit))

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

(defn- all-fact-type-names
  [analyses]
  (into (sorted-set)
        (mapcat (fn [analysis]
                  (concat (keys (:fact-types analysis))
                          (mapcat :ancestors (vals (:fact-types analysis))))))
        analyses))

(defn- producer-units
  "Units that produce `ft` — a rule inserts a type that IS `ft` or derives from
  it, so the fact it makes is one of each ancestor."
  [analyses-by-unit ancestors ft]
  (into #{}
        (keep (fn [[uk analysis]]
                (when (some (fn [[_ rule]]
                              (some (fn [t] (or (= ft t) (contains? (get ancestors t #{}) ft)))
                                    (:insert-types rule)))
                            (:rules analysis))
                  uk)))
        analyses-by-unit))

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
        (all-fact-type-names (vals analyses-by-unit))))

;; ===========================================================================
;; unit edges, entry points, orphans
;; ===========================================================================

(defn- ->unit-edges
  [analyses-by-unit ancestors]
  (let [produced (into {}
                       (map (fn [[uk analysis]]
                              [uk (into #{} (mapcat (fn [[_ r]]
                                                      (concat (:insert-types r) (:retract-types r))))
                                        (:rules analysis))]))
                       analyses-by-unit)
        consumed (into {}
                       (map (fn [[uk analysis]]
                              [uk (into #{} (mapcat :lhs-types)
                                        (concat (vals (:rules analysis))
                                                (vals (:queries analysis))))]))
                       analyses-by-unit)
        satisfies (fn [uk]
                    (reduce (fn [acc t] (into acc (cons t (get ancestors t #{}))))
                            #{} (get produced uk #{})))
        consumer-productions (fn [uk]
                               (concat (get-in analyses-by-unit [uk :rules])
                                       (get-in analyses-by-unit [uk :queries])))
        edges (volatile! (sorted-map))]
    (doseq [[p-uk _] analyses-by-unit
            [c-uk _] analyses-by-unit
            :when (not= p-uk c-uk)
            :let [via (set/intersection (get consumed c-uk #{}) (satisfies p-uk))]
            :when (seq via)]
      (vswap! edges assoc [p-uk c-uk]
              {:via via
               :rules (count (filter (fn [[_ r]]
                                       (seq (set/intersection (set (:lhs-types r)) via)))
                                     (consumer-productions c-uk)))}))
    @edges))

(defn- ->entry-points
  [analyses-by-unit ancestors]
  (let [all-satisfies (reduce (fn [acc [_ analysis]]
                                (reduce (fn [acc [_ r]]
                                          (reduce (fn [acc t] (into acc (cons t (get ancestors t #{}))))
                                                  acc
                                                  (:insert-types r)))
                                        acc
                                        (:rules analysis)))
                              #{}
                              analyses-by-unit)]
    (into {}
          (keep (fn [[uk analysis]]
                  (let [consumed (into #{} (mapcat :lhs-types)
                                       (concat (vals (:rules analysis))
                                               (vals (:queries analysis))))
                        entry (set/difference consumed all-satisfies)]
                    (when (seq entry) [uk entry]))))
          analyses-by-unit)))

(defn- ->orphans
  [analyses-by-unit descendants]
  (let [consumed-closure (reduce (fn [acc [_ analysis]]
                                   (reduce (fn [acc production]
                                             (reduce (fn [acc t] (into acc (cons t (get descendants t #{}))))
                                                     acc
                                                     (:lhs-types production)))
                                           acc
                                           (concat (vals (:rules analysis))
                                                   (vals (:queries analysis)))))
                                 #{}
                                 analyses-by-unit)]
    (into {}
          (keep (fn [[uk analysis]]
                  (let [produced (into #{} (mapcat (fn [[_ r]]
                                                     (concat (:insert-types r) (:retract-types r))))
                                       (:rules analysis))
                        orphan (set/difference produced consumed-closure)]
                    (when (seq orphan) [uk orphan]))))
          analyses-by-unit)))

;; ===========================================================================
;; the index
;; ===========================================================================

(defn- ->namespaces
  [analyses-by-unit]
  (into {}
        (map (fn [[uk analysis]]
               [uk (vec (sort (into #{} (keep :ns)
                                    (concat (vals (:rules analysis))
                                            (vals (:queries analysis))))))]))
        analyses-by-unit))

(defn- ->provenance
  [registry selection]
  (into {}
        (map (fn [unit]
               (let [info (registry/unit-info registry unit)]
                 [(unit-key unit)
                  (cond-> {:artifacts (set (:artifacts info))
                           :created (get-in info [:manifest-head :created])}
                    (:branch unit) (assoc :branch (:branch unit)))])))
        selection))

(defn ->index
  "The federated index over `selection` (an ordered vector of `UnitRef`s), a
  value. Units are read as slim; the hierarchy is unioned and re-closed; shape
  skew is refused via `registry/assert-compatible!`."
  [registry selection]
  (registry/assert-compatible! registry selection)
  (let [analyses-by-unit (->analyses-by-unit registry selection)
        raw (->raw-ancestors (vals analyses-by-unit))
        ancestors (->closed-ancestors raw)
        descendants (->descendants ancestors)
        maps (->production-maps analyses-by-unit descendants)]
    {:scope {:units (mapv #(select-keys % [:repo :branch]) selection)
             :namespaces (->namespaces analyses-by-unit)}
     :provenance (->provenance registry selection)
     :coverage {:units (mapv unit-key selection)
                :unknown-namespaces []
                :shape-mismatch (:shape-mismatch (registry/compatibility-report registry selection))}
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

(defn paths-between
  "The first shortest unit-dependency path from `a` to `b` (bounded BFS), or nil."
  [index a b]
  (let [a (if (map? a) (unit-key a) a)
        b (if (map? b) (unit-key b) b)
        adj (reduce (fn [m [[p c] _]] (update m p (fnil conj #{}) c)) {} (:unit-edges index))]
    (if (= a b)
      [a]
      (loop [queue (list [a])
             seen #{a}]
        (when (seq queue)
          (let [path (peek queue)
                node (peek path)]
            (if (= node b)
              path
              (recur (into (pop queue)
                           (map #(conj path %))
                           (remove seen (get adj node #{})))
                     (into seen (get adj node #{}))))))))))

(defn coverage-report
  "What this index cannot answer: its units, requested namespaces it does not
  cover, and units whose slim shape differs from the majority."
  [index]
  (:coverage index))

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
