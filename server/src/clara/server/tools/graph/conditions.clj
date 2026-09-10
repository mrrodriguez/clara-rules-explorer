(ns clara.server.tools.graph.conditions
  "Per-production LHS condition analysis.

  This namespace owns the analysis-time passes over a production's raw LHS
  conditions.  Serialization (`clara.server.tools.graph.serialize`) renders the
  results of these passes to JSON-friendly shapes but performs no analysis of
  its own.

  Passes:

  * `normalize-lhs` — convert a production's raw Clara LHS into the
    homogeneous shape used throughout the analysis: every entry is a map;
    boolean groups carry `:condition-type` + `:children`; leaves keep their
    raw fields.

  * `extract-lhs-fact-types` / `extract-var-bindings` — structural walkers
    over the normalized LHS (fact types and bound fact variables).

  * `accumulator-info` — evaluate an accumulator condition's `:accumulator`
    form in the production namespace and return its `:form` and
    `:some-initial-value?` details.

  * `analyze-lhs-bindings` — reproduce the compiler's binding bookkeeping
    (used / join / new bindings per condition) using clara-rules' own
    `com/sort-conditions` and `com/condition-to-node`, tagged with each
    record's origin path in the raw LHS.  Groups (`:or` / `:exists` /
    negations) are analyzed in their own scope via `analyze-node`: nested
    leaves attach at their real tree paths and every group carries the
    componentwise union of its children's summaries.

  * `augment-lhs` — evaluate accumulators and attach per-node binding info
    (leaves and groups alike) on an already-normalized LHS."
  (:require [clara.rules.compiler :as com]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [schema.core :as s]))

(s/defschema AccumulatorInfo
  "In-memory (pre-serialization) accumulator analysis shape.  `:form` is the
   raw accumulator form (list/symbol); `serialize.clj` renders it to a string
   at the API boundary."
  {:form s/Any
   :some-initial-value? s/Bool})

(s/defschema LhsBindingRecord
  "In-memory shape of one origin-tagged binding record produced by
   `analyze-lhs-bindings`.  The cumulative `:used-bindings` /
   `:ancestor-bindings` / `:all-bindings` sets are kept here because the walk
   needs them for propagation; only `:binding-keys` / `:new-bindings` (plus
   `:join-filter-join-bindings` when present) are surfaced on the wire."
  {:condition s/Any
   :origin [s/Int]
   :attach-path (s/maybe [s/Int])
   :used-bindings #{s/Keyword}
   :binding-keys #{s/Keyword}
   :new-bindings #{s/Keyword}
   :ancestor-bindings #{s/Keyword}
   :all-bindings #{s/Keyword}
   (s/optional-key :result-binding) s/Keyword
   (s/optional-key :fact-binding) s/Keyword
   (s/optional-key :join-filter-join-bindings) #{s/Keyword}})

(defn- get-condition-type
  "Returns the condition type of a normalized LHS node: the `:condition-type`
   of a group, else `:accumulator` / `:fact` / `:test` by the leaf's keys."
  [node]
  (or (:condition-type node)
      (cond
        (:accumulator node) :accumulator
        (:type node) :fact
        :else :test)))

(defn- normalized-node?
  "True when `condition` was already normalized by `normalize-condition` — it
   carries the `::normalized` marker.  The marker is namespaced to this
   namespace, so it cannot collide with raw Clara condition keys."
  [condition]
  (boolean (::normalized condition)))

(defn- assert-normalized-condition
  "Throws when `node` does not carry the `::normalized` marker added by
   `normalize-condition`.  Every normalized condition — group, accumulator,
   and leaf — is tagged, so a missing marker means raw (or malformed) input
   and fails loudly here instead of being silently mis-classified."
  [node]
  (when-not (::normalized node)
    (throw (ex-info "Expected a normalized LHS condition (call conditions/normalize-lhs first)"
                    {:condition node}))))

(defn- normalize-condition
  "Converts one raw Clara condition into the normalized homogeneous shape:
   boolean group vectors become `{:condition-type … :children […]}`; accumulator
   maps have their `:from` subtree normalized; leaf maps keep their raw fields.
   Every normalized node is tagged with `::normalized`; group and accumulator
   nodes additionally retain their raw form under `:raw-condition` (so the
   compiler-coupled binding walk can read it without a reverse conversion).

   Idempotent: an already-normalized node is returned unchanged."
  [condition]
  (cond
    (normalized-node? condition) condition

    (map? condition)
    (if (contains? condition :accumulator)
      (-> condition
          (update :from normalize-condition)
          (assoc :raw-condition condition
                 ::normalized true))
      (assoc condition ::normalized true))

    (and (sequential? condition) (seq condition))
    (let [group-head (first condition)]
      (when-not (or (keyword? group-head) (symbol? group-head))
        (throw (ex-info "Unsupported LHS condition shape: group vector head must be a keyword or symbol"
                        {:condition condition
                         :head group-head})))
      {:condition-type (if (keyword? group-head) group-head (keyword (name group-head)))
       :children (mapv normalize-condition (rest condition))
       :raw-condition condition
       ::normalized true})

    :else condition))

(defn normalize-lhs
  "Normalizes a production's raw LHS conditions into the homogeneous shape
   used by the rest of the analysis: every entry is a map; group entries carry
   `:condition-type` + `:children`; leaf entries keep their raw fields.  Every
   entry is tagged with `::normalized`; group and accumulator entries
   additionally retain their raw form under `:raw-condition` (for the
   compiler-coupled binding walk).  The internal keys are kept through
   analysis and stripped at the serialization boundary (see
   `strip-internal-keys`).

   Idempotent: an already-normalized LHS is returned unchanged."
  [lhs]
  (mapv normalize-condition lhs))

(defn- get-raw-condition
  "Returns the raw Clara form retained on a normalized condition.  Group and
   accumulator nodes keep their original form under `:raw-condition`; leaf maps
   are unchanged apart from the `::normalized` marker, which is stripped here
   so the returned LHS is the pure raw form.  Raw (non-normalized) conditions
   pass through unchanged."
  [node]
  (or (:raw-condition node)
      (cond-> node (map? node) (dissoc ::normalized))))

(defn get-raw-lhs
  "Returns the raw Clara LHS retained on a normalized LHS (see
   `normalize-lhs`)."
  [lhs]
  (mapv get-raw-condition lhs))

(defn- extract-condition-fact-types
  "Returns the fact types referenced by a single normalized LHS condition
   subtree (fact leaves and accumulator `:from` subtrees; groups are walked;
   test leaves contribute none).  Duplicates are preserved; callers that need
   a deduplicated view use `extract-lhs-fact-types`.

   Throws when `condition` is not a normalized condition (see
   `assert-normalized-condition`)."
  [condition]
  (assert-normalized-condition condition)
  (case (get-condition-type condition)
    :fact [(:type condition)]
    :accumulator (extract-condition-fact-types (:from condition))
    (:and :or :not :exists) (mapcat extract-condition-fact-types (:children condition))
    :test []
    []))

(defn extract-lhs-fact-types
  "Returns the distinct fact types referenced by a normalized production LHS,
   in traversal order.  Throws when any entry is not normalized (missing the
   `::normalized` marker)."
  [lhs]
  (into []
        (comp (mapcat extract-condition-fact-types)
              (remove nil?)
              (distinct))
        lhs))

(defn extract-var-bindings
  "Scans a normalized production LHS for bound fact variables:
   `:fact-binding` on fact leaves and `:result-binding` on accumulator leaves
   (whose `:from` subtree supplies the fact types).  Returns
   `[{:binding ?sym :fact-type t} …]` with `:binding` as a symbol.

   Throws when any condition is not normalized (missing the `::normalized`
   marker)."
  [lhs]
  (letfn [(walk [condition]
            (assert-normalized-condition condition)
            (case (get-condition-type condition)
              :fact (if-let [bound-var (:fact-binding condition)]
                      [{:binding (-> bound-var name symbol) :fact-type (:type condition)}]
                      [])
              :accumulator (if-let [bound-var (:result-binding condition)]
                             (->> (extract-condition-fact-types (:from condition))
                                  distinct
                                  (into [] (map (fn [fact-type]
                                                  {:binding (-> bound-var name symbol)
                                                   :fact-type fact-type}))))
                             [])
              (:and :or :not :exists) (mapcat walk (:children condition))
              :test []
              []))]
    (into [] (mapcat walk) lhs)))

(s/defn accumulator-info :- AccumulatorInfo
  "Evaluates an accumulator form in the production's namespace and returns a
   map with:

   * `:form` — the raw accumulator form, unchanged (serialization renders it
     to a string);
   * `:some-initial-value?` — true when the evaluated accumulator's
     `:initial-value` is non-nil.

   Throws when the production namespace is not loaded or the form does not
   evaluate to a map.  Analysis assumes the rulebase's namespaces are already
   loaded in the runtime — the same assumption the rest of the analysis makes
   for symbol resolution."
  [form prod-ns]
  (when (nil? prod-ns)
    (throw (ex-info "Cannot evaluate accumulator: production namespace not derivable"
                    {:prod-ns prod-ns
                     :accumulator form})))
  (let [eval-ns (or (find-ns prod-ns)
                    (throw (ex-info "Cannot evaluate accumulator: production namespace not loaded"
                                    {:prod-ns prod-ns
                                     :accumulator form})))
        evaluated (try
                    (binding [*ns* eval-ns]
                      (eval form))
                    (catch Throwable t
                      (throw (ex-info "Failed to evaluate accumulator form"
                                      {:prod-ns prod-ns
                                       :accumulator form}
                                      t))))]
    (when-not (map? evaluated)
      (throw (ex-info "Accumulator form did not evaluate to a map"
                      {:prod-ns prod-ns
                       :accumulator form
                       :result evaluated})))
    {:form form
     :some-initial-value? (some? (:initial-value evaluated))}))

(defn strip-internal-keys
  "Removes the internal analysis keys (`:raw-condition` and `::normalized`)
   from a serialized (or normalized/augmented) LHS tree.  The keys are
   serialized into the in-memory `:lhs` and removed here at the external-view
   boundary so they are not externalized via the API."
  [lhs]
  (walk/prewalk (fn [x] (if (map? x) (dissoc x :raw-condition ::normalized) x)) lhs))

(defn- enrich-accumulators
  "Returns `lhs` with every accumulator condition's `:accumulator` replaced by
   its `accumulator-info` map.  Only the normalized condition structure is
   traversed (`:from` / `:children`); the retained `:raw-condition` subtrees are
   skipped, so each accumulator form is evaluated exactly once."
  [lhs prod-ns]
  (letfn [(enrich-node [node]
            (cond
              (and (map? node) (contains? node :accumulator))
              (-> node
                  (update :accumulator #(accumulator-info % prod-ns))
                  (update :from enrich-node))

              (and (map? node) (contains? node :children))
              (update node :children (fn [children] (mapv enrich-node children)))

              :else node))]
    (mapv enrich-node lhs)))

(defn- group-child-paths
  "Returns a seq of `[child-path child]` pairs for the child conditions of a
   group vector (the entries after the leading operator).  This is the single
   place that encodes how a child's path is derived from its parent's path;
   `flatten-and-tag-conditions`, `analyze-node`, and
   `merge-bindings-into-tree` all rely on it."
  [path group]
  (map-indexed (fn [j child] [(conj path j) child])
               (rest group)))

(defn- group-op
  "Returns the normalized group operator (`:and` / `:or` / `:not` / `:exists`)
   for a raw group vector, or nil for leaf maps.  Heads may be keywords or
   symbols (mirroring `normalize-condition`)."
  [condition]
  (when (and (sequential? condition) (seq condition))
    (let [h (first condition)]
      (when (or (keyword? h) (symbol? h))
        (keyword (name h))))))

(defn- flatten-and-tag-conditions
  "Flattens a raw LHS into origin-tagged conditions (`{:origin [i] :condition
   c}` maps), flattening top-level `:and` groups so their children each carry
   their own origin path.  A top-level map entry gets origin `[i]`; the `j`th
   child of a top-level `:and` at index `i` gets `[i j]`."
  [lhs]
  (vec
   (mapcat (fn [i condition]
             (if (#{'and :and} (first condition))
               (map (fn [[path child]]
                      {:origin path
                       :condition child})
                    (group-child-paths [i] condition))
               [{:origin [i]
                 :condition condition}]))
           (range) lhs)))

(defn- sort-tagged-conditions
  "Reimplements `clara.rules.compiler/sort-conditions` over origin-tagged
   conditions while preserving each condition's origin.  Uses the compiler's
   own `com/analyze-condition` for the per-condition classification, so the
   ordering is identical."
  [tagged-conditions]
  (let [classified (mapv (fn [{:keys [condition] :as item}]
                           (assoc item :classified (com/analyze-condition condition)))
                         tagged-conditions)]
    (loop [sorted []
           bound #{}
           remaining classified]
      (if (empty? remaining)
        sorted
        (let [satisfied? (fn [item]
                           (set/subset? (get-in item [:classified :unbound]) bound))
              satisfied-non-accum? (fn [item]
                                     (and (not (get-in item [:classified :is-accumulator]))
                                          (set/subset? (get-in item [:classified :unbound]) bound)))
              has-non-accum (some satisfied-non-accum? remaining)
              newly-satisfied (if has-non-accum
                                (filter satisfied-non-accum? remaining)
                                (filter satisfied? remaining))
              still-unsatisfied (if has-non-accum
                                  (remove satisfied-non-accum? remaining)
                                  (remove satisfied? remaining))
              updated-bound (->> newly-satisfied
                                 (map (comp :bound :classified))
                                 (apply set/union bound))]
          (when (empty? newly-satisfied)
            (let [unbound-union (->> still-unsatisfied
                                     (map (comp :unbound :classified))
                                     (apply set/union))
                  unsatisfiable (set/difference unbound-union bound)]
              (throw (ex-info "Using variable that is not previously bound"
                              {:unbound-variables unsatisfiable}))))
          (recur (into sorted newly-satisfied) updated-bound still-unsatisfied))))))

(defn- compound-negation?
  "True when `condition` is a `[:not [:and/:or/:not ...]]` group.  The compiler
   extracts these into a helper production plus a `NegationResult` condition
   (see `com/get-complex-negation`), so the De Morgan DNF expansion of the raw
   form models something the network never builds.  Nested leaves are analyzed
   by a sub-scope walk over the inner `negation-expr` instead, which reproduces
   the generated helper rule's own LHS."
  [condition]
  (and (#{:not 'not} (first condition))
       (sequential? (second condition))
       (#{:and :or :not 'and 'or 'not} (first (second condition)))))

(defn- sort-bindings
  "Returns a deterministic, sorted vector of binding keywords."
  [bindings]
  (vec (sort-by name bindings)))

(defn- binding-summary
  "Reduces an origin-tagged record to the small, deterministic wire shape
   attached under `:bindings`: `:binding-keys` and `:new-bindings`, plus
   `:join-filter-join-bindings` when the condition has non-equality
   unifications that reference an upstream binding (empty sets are omitted).
   The cumulative / superset groups stay internal to the walk."
  [{:keys [binding-keys new-bindings] :as record}]
  (cond-> {:binding-keys (sort-bindings binding-keys)
           :new-bindings (sort-bindings new-bindings)}
    (seq (:join-filter-join-bindings record))
    (assoc :join-filter-join-bindings
           (sort-bindings (:join-filter-join-bindings record)))))

(defn- union-binding-summaries
  "Componentwise union of wire binding summaries (`:binding-keys` /
   `:new-bindings` / optional `:join-filter-join-bindings`).  A group's
   `:bindings` is the union of its children's — one vocabulary on leaves and
   groups alike.  A group's summary is derived, not additional: consumers
   aggregate over leaves *or* read group summaries, never both."
  [summaries]
  (let [binding-keys (into #{} (mapcat :binding-keys) summaries)
        new-bindings (into #{} (mapcat :new-bindings) summaries)
        join-filter (into #{} (mapcat :join-filter-join-bindings) summaries)]
    (cond-> {:binding-keys (sort-bindings binding-keys)
             :new-bindings (sort-bindings new-bindings)}
      (seq join-filter)
      (assoc :join-filter-join-bindings (sort-bindings join-filter)))))

(s/defn ^:private conjunction-binding-record :- LhsBindingRecord
  "Computes one `LhsBindingRecord` for a single condition conjunction."
  [conjunction env ancestor-bindings origin attach-path]
  (let [compiled-node (com/condition-to-node conjunction env ancestor-bindings)
        {:keys [result-binding fact-binding]} conjunction
        all-bindings (cond-> (set/union ancestor-bindings (:used-bindings compiled-node))
                       result-binding (conj result-binding)
                       fact-binding (conj fact-binding))]
    (cond-> {:condition conjunction
             :origin origin
             :attach-path attach-path
             :used-bindings (:used-bindings compiled-node)
             :binding-keys (or (:join-bindings compiled-node) #{})
             :new-bindings (:new-bindings compiled-node)
             :ancestor-bindings ancestor-bindings
             :all-bindings all-bindings}
      (:join-filter-join-bindings compiled-node)
      (assoc :join-filter-join-bindings (:join-filter-join-bindings compiled-node))

      result-binding
      (assoc :result-binding result-binding)

      fact-binding
      (assoc :fact-binding fact-binding))))

(declare analyze-node)

(defn- analyze-leaf-node
  "Analyzes a leaf map (fact / test / accumulator) at `path` with `ancestor`
   in scope.  Returns `{:index :summary :outer :records}` where `:index` holds
   the wire summary at `path`, `:summary` is that same summary, `:outer` is
   the binding set available after the leaf, and `:records` is the single
   origin-tagged record."
  [leaf path ancestor env]
  (let [record (conjunction-binding-record leaf env ancestor path path)
        summary (binding-summary record)]
    {:index {path {:bindings summary}}
     :summary summary
     :outer (:all-bindings record)
     :records [record]}))

(defn- analyze-and-children
  "Analyzes `:and` children sequentially, threading each child's `:outer`
   into the next sibling's ancestor set.  The group's summary is the union of
   its children's; `:outer` is the final sibling's output."
  [children path ancestor env]
  (loop [j 0
         local ancestor
         index {}
         summaries []
         records []]
    (if (>= j (count children))
      {:index (if (seq summaries)
                (assoc index path {:bindings (union-binding-summaries summaries)})
                index)
       :summary (union-binding-summaries summaries)
       :outer local
       :records records}
      (let [child (nth children j)
            child-path (conj path j)
            sub (analyze-node child child-path local env)]
        (recur (inc j)
               (:outer sub)
               (merge index (:index sub))
               (conj summaries (:summary sub))
               (into records (:records sub)))))))

(defn- analyze-or-children
  "Analyzes `:or` branches independently: every branch starts from the group's
   ancestor set, never from the previous branch's result.  The group's summary
   is the union of its branches'; `:outer` is the union of the branches'
   finals (matching the compiler's disjunction handling)."
  [children path ancestor env]
  (let [subs (mapv (fn [j child]
                     (analyze-node child (conj path j) ancestor env))
                   (range (count children))
                   children)
        index (into {} (mapcat :index) subs)
        summaries (mapv :summary subs)
        summary (union-binding-summaries summaries)
        outer (reduce set/union ancestor (map :outer subs))]
    {:index (assoc index path {:bindings summary})
     :summary summary
     :outer outer
     :records (into [] (mapcat :records) subs)}))

(defn- analyze-not-group
  "Analyzes a `[:not …]` group.  Simple negations compute one
   `condition-to-node` record for the whole group and attach its summary at
   both the child and the group path.  Compound negations run the sub-scope
   walk over the inner `negation-expr` (the decomposition the compiler
   actually builds) and attach its union at the group path.  Either way
   nothing escapes: `:outer` is the incoming ancestor set."
  [raw path ancestor env]
  (if (compound-negation? raw)
    (let [negation-expr (second raw)
          child-path (conj path 0)
          sub (analyze-node negation-expr child-path ancestor env)
          summary (:summary sub)]
      {:index (assoc (:index sub) path {:bindings summary})
       :summary summary
       :outer ancestor
       :records (:records sub)})
    (let [child-path (conj path 0)
          record (conjunction-binding-record raw env ancestor path child-path)
          summary (binding-summary record)]
      {:index {child-path {:bindings summary}
               path {:bindings summary}}
       :summary summary
       :outer ancestor
       :records [record]})))

(defn- analyze-exists-group
  "Analyzes a `[:exists child]` group by analyzing `child` directly: the
   synthesized accumulator's `:from` *is* the child, so its binding set is the
   child's.  The summary attaches at both the child and the group path; no
   synthetic `:?__exists__…` binding is ever surfaced.  Nothing escapes:
   `:outer` is the incoming ancestor set."
  [raw path ancestor env]
  (let [child (second raw)
        child-path (conj path 0)
        sub (analyze-node child child-path ancestor env)
        summary (:summary sub)]
    {:index (assoc (:index sub) path {:bindings summary})
     :summary summary
     :outer ancestor
     :records (:records sub)}))

(defn- analyze-node
  "Analyzes one raw condition at `path` with `ancestor` bindings in scope.
   Returns `{:index :summary :outer :records}`:

   * `:index` — `{attach-path {:bindings summary}}` for this subtree, including
     the group's own path;
   * `:summary` — the wire summary for this node (leaf summary, or the union
     of children's for groups);
   * `:outer` — the binding set available *after* this node (what the parent
     threads onward; negations and `:exists` contribute nothing);
   * `:records` — flat origin-tagged leaf records for `analyze-lhs-bindings`."
  [raw-condition path ancestor env]
  (cond
    (map? raw-condition)
    (analyze-leaf-node raw-condition path ancestor env)

    (sequential? raw-condition)
    (let [op (group-op raw-condition)
          children (vec (rest raw-condition))]
      (case op
        :and (analyze-and-children children path ancestor env)
        :or (analyze-or-children children path ancestor env)
        :not (analyze-not-group raw-condition path ancestor env)
        :exists (analyze-exists-group raw-condition path ancestor env)
        (analyze-leaf-node raw-condition path ancestor env)))

    :else
    {:index {}
     :summary {:binding-keys [] :new-bindings []}
     :outer ancestor
     :records []}))

(defn- analyze-tagged-conditions
  "Runs the compiler-order binding walk over sorted, origin-tagged conditions.
   Each tagged condition is analyzed in its own scope via `analyze-node`; the
   returned `:outer` becomes the next condition's ancestor set.  Returns
   `{:index :records}` covering the whole walk, including nested leaves and
   group summaries."
  [tagged-conditions env]
  (loop [remaining tagged-conditions
         ancestor-bindings #{}
         index {}
         records []]
    (if-let [{:keys [origin condition]} (first remaining)]
      (let [sub (analyze-node condition origin ancestor-bindings env)]
        (recur (rest remaining)
               (:outer sub)
               (merge index (:index sub))
               (into records (:records sub))))
      {:index index
       :records records})))

(defn- top-level-and-group-paths
  "Returns the set of top-level `[i]` paths whose normalized entry is an `:and`
   group.  Top-level `:and` groups are flattened before sorting (mirroring the
   compiler), so their children carry `[i j]` origins; the group node itself
   still needs the union of its children's summaries."
  [lhs]
  (into #{}
        (keep-indexed (fn [i entry]
                        (when (and (map? entry)
                                   (= :and (:condition-type entry)))
                          [i])))
        lhs))

(defn- attach-top-level-and-groups
  "Adds union summaries at each top-level `:and` group path in `group-paths`.
   Each group's summary is the union of its direct children's summaries
   already present in `index`."
  [index group-paths]
  (reduce (fn [idx group-path]
            (let [child-summaries (->> idx
                                       (filter (fn [[p _]]
                                                 (and (= (count p) (inc (count group-path)))
                                                      (= (vec (butlast p)) group-path))))
                                       (map (comp :bindings val))
                                       vec)]
              (if (seq child-summaries)
                (assoc idx group-path {:bindings (union-binding-summaries child-summaries)})
                idx)))
          index
          group-paths))

(defn- analyze-lhs->index
  "Full binding walk returning `{attach-path {:bindings summary}}` for every
   node in the normalized `lhs` — leaves, nested leaves, and groups.  Group
   summaries are the componentwise union of their children's."
  [lhs env]
  (let [raw-lhs (get-raw-lhs lhs)
        and-paths (top-level-and-group-paths lhs)
        tagged (-> raw-lhs
                   flatten-and-tag-conditions
                   sort-tagged-conditions)
        {:keys [index]} (analyze-tagged-conditions tagged env)]
    (attach-top-level-and-groups index and-paths)))

(defn analyze-lhs-bindings
  "Analyzes the compiler's binding bookkeeping for a normalized `lhs` using
   clara-rules' own `com/sort-conditions` and `com/condition-to-node`.  The
   normalized LHS is read via `get-raw-lhs` only for this compiler-coupled
   walk.

   Returns a flat vector of origin-tagged records, in compiler processing
   order — one per leaf, including leaves nested inside `:or` / `:exists` /
   negation groups.  Each record:

   * `:origin` — path into the LHS tree the record came from;
   * `:attach-path` — path where this record's info is attached when
     augmenting the LHS (always non-nil; nested leaves attach at their real
     tree paths);
   * `:condition` — the analyzed condition form (`:exists` is analyzed via
     its child directly, so no synthetic `:?__exists__…` binding is ever
     surfaced);
   * `:used-bindings` — variables the condition references;
   * `:binding-keys` — variables already bound upstream that this condition
     joins on (the compiled node's `:binding-keys`);
   * `:new-bindings` — variables introduced by this condition's constraints;
   * `:join-filter-join-bindings` — present only when the condition has
     non-equality unifications that reference an upstream binding;
   * `:result-binding` / `:fact-binding` — present when the condition binds one;
   * `:ancestor-bindings` — bindings available before the condition;
   * `:all-bindings` — bindings available after it.

   Group summaries (the union of children's) live in the augment index (see
   `analyze-lhs->index`), not as records.

   `env` is the production's `:env` (usually nil)."
  [lhs env]
  (let [raw-lhs (get-raw-lhs lhs)
        tagged (-> raw-lhs
                   flatten-and-tag-conditions
                   sort-tagged-conditions)
        {:keys [records]} (analyze-tagged-conditions tagged env)]
    records))

(defn- merge-bindings-into-tree
  "Walks the (normalized, accumulator-enriched) LHS tree, merging binding info
   into leaf and group maps by path.  Group maps keep their `:condition-type`
   and have their `:children` recursed before merging their own summary.  The
   internal `:raw-condition` and `::normalized` keys are left intact for
   in-memory consumers."
  [node path binding-index]
  (cond
    (and (map? node) (contains? node :children))
    (cond-> (update node :children
                    (fn [children]
                      (mapv (fn [j child]
                              (merge-bindings-into-tree child (conj path j) binding-index))
                            (range)
                            children)))
      (contains? binding-index path) (merge (get binding-index path)))

    (map? node) (if-let [binding-info (get binding-index path)]
                  (merge node binding-info)
                  node)

    :else node))

(defn augment-lhs
  "Enriches a normalized LHS for analysis.  Every entry is a map: group entries
   are `{:condition-type … :children […]}`; leaf entries carry
   `accumulator-info` (accumulator conditions).  Every node — leaves, nested
   leaves, and groups — carries a nested `:bindings` map (`:binding-keys` /
   `:new-bindings`, plus `:join-filter-join-bindings` when the condition has
   non-equality unifications that reference an upstream binding).  A group's
   `:bindings` is the componentwise union of its children's (derived, not
   additional: aggregate over leaves *or* read group summaries, never both).
   Bindings inside negations and `:exists` do not leak outward — the nested
   summaries describe the sub-scope; the outer walk is unchanged by them.

   The caller is responsible for normalizing the raw LHS first (see
   `normalize-lhs`, which is idempotent); this function only enriches an
   already-normalized LHS.  The enriched LHS retains the internal
   `:raw-condition` and `::normalized` keys for in-memory consumers;
   `strip-internal-keys` removes them at the serialization boundary.

   `opts`:
   * `:prod-ns` — production namespace (required for accumulator evaluation);
   * `:env` — the production's `:env` (usually nil)."
  [lhs {:keys [prod-ns env]}]
  (let [binding-index (analyze-lhs->index lhs env)
        enriched (enrich-accumulators lhs prod-ns)]
    (map-indexed (fn [i entry]
                   (merge-bindings-into-tree entry [i] binding-index))
                 enriched)))
