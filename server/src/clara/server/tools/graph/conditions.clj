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
    record's origin path in the raw LHS.

  * `augment-lhs` — normalize the LHS, evaluate accumulators, and attach
    per-leaf binding info."
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

(defn- normalize-condition
  "Converts one raw Clara condition into the normalized homogeneous shape:
   boolean group vectors become `{:condition-type … :children […]}`; accumulator
   maps have their `:from` subtree normalized; leaf maps are unchanged.  Group
   and accumulator nodes retain their raw form under `:raw-condition` (so the
   compiler-coupled binding walk can read it without a reverse conversion) and
   are tagged with `::normalized` so re-normalization is a no-op.

   Idempotent: an already-normalized node is returned unchanged."
  [condition]
  (cond
    (normalized-node? condition) condition

    (map? condition)
    (if (contains? condition :accumulator)
      (assoc (update condition :from normalize-condition)
             :raw-condition condition
             ::normalized true)
      condition)

    (and (sequential? condition) (seq condition))
    (let [op (first condition)]
      (when-not (or (keyword? op) (symbol? op))
        (throw (ex-info "Unsupported LHS condition shape: group vector head must be a keyword or symbol"
                        {:condition condition
                         :head op})))
      {:condition-type (if (keyword? op) op (keyword (name op)))
       :children (mapv normalize-condition (rest condition))
       :raw-condition condition
       ::normalized true})

    :else condition))

(defn normalize-lhs
  "Normalizes a production's raw LHS conditions into the homogeneous shape
   used by the rest of the analysis: every entry is a map; group entries carry
   `:condition-type` + `:children`; leaf entries keep their raw fields.  Group
   and accumulator entries retain their raw form under `:raw-condition` (for
   the compiler-coupled binding walk) and are tagged with `::normalized`;
   `augment-lhs` strips the internal keys once consumed.

   Idempotent: an already-normalized LHS is returned unchanged."
  [lhs]
  (mapv normalize-condition lhs))

(defn- get-raw-condition
  "Returns the raw Clara form retained on a normalized condition (or the
   condition itself when it is already a raw leaf map)."
  [node]
  (or (:raw-condition node) node))

(defn get-raw-lhs
  "Returns the raw Clara LHS retained on a normalized LHS (see
   `normalize-lhs`)."
  [lhs]
  (mapv get-raw-condition lhs))

(defn- extract-condition-fact-types
  "Returns the fact types referenced by a single normalized LHS condition
   subtree (fact leaves and accumulator `:from` subtrees; groups are walked;
   test leaves contribute none).  Duplicates are preserved; callers that need
   a deduplicated view use `extract-lhs-fact-types`."
  [condition]
  (case (get-condition-type condition)
    :fact [(:type condition)]
    :accumulator (extract-condition-fact-types (:from condition))
    (:and :or :not :exists) (mapcat extract-condition-fact-types (:children condition))
    :test []
    []))

(defn extract-lhs-fact-types
  "Returns the distinct fact types referenced by a normalized production LHS,
   in traversal order."
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
   `[{:binding ?sym :fact-type t} …]` with `:binding` as a symbol."
  [lhs]
  (letfn [(walk [condition]
            (case (get-condition-type condition)
              :fact (if-let [b (:fact-binding condition)]
                      [{:binding (symbol (name b)) :fact-type (:type condition)}]
                      [])
              :accumulator (if-let [b (:result-binding condition)]
                             (into []
                                   (map (fn [t] {:binding (symbol (name b)) :fact-type t}))
                                   (distinct (extract-condition-fact-types (:from condition))))
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
  (let [acc-ns (or (find-ns prod-ns)
                   (throw (ex-info "Cannot evaluate accumulator: production namespace not loaded"
                                   {:prod-ns prod-ns
                                    :accumulator form})))
        acc (try
              (binding [*ns* acc-ns]
                (eval form))
              (catch Throwable t
                (throw (ex-info "Failed to evaluate accumulator form"
                                {:prod-ns prod-ns
                                 :accumulator form}
                                t))))]
    (when-not (map? acc)
      (throw (ex-info "Accumulator form did not evaluate to a map"
                      {:prod-ns prod-ns
                       :accumulator form
                       :result acc})))
    {:form form
     :some-initial-value? (some? (:initial-value acc))}))

(defn- strip-internal-keys
  "Removes the internal normalization keys (`:raw-condition` and `::normalized`)
   from a normalized LHS tree.  The binding walk consumes `:raw-condition` (via
   `get-raw-lhs`) before accumulator enrichment runs, so dropping them here
   avoids re-evaluating the retained raw accumulator copies during
   `enrich-accumulators` and keeps the internal markers off the wire."
  [lhs]
  (walk/prewalk (fn [x] (if (map? x) (dissoc x :raw-condition ::normalized) x)) lhs))

(defn- enrich-accumulators
  "Returns `lhs` with every accumulator condition's `:accumulator` replaced by
   its `accumulator-info` map.  Non-accumulator conditions are unchanged."
  [lhs prod-ns]
  (walk/prewalk
   (fn [x]
     (if (and (map? x) (contains? x :accumulator))
       (update x :accumulator #(accumulator-info % prod-ns))
       x))
   lhs))

(defn- group-child-paths
  "Returns a seq of `[child-path child]` pairs for the child conditions of a
   group vector (the entries after the leading operator).  This is the single
   place that encodes how a child's path is derived from its parent's path;
   `flatten-tagged`, `attach-path`, and `walk-augment` all rely on it."
  [path group]
  (map-indexed (fn [j child] [(conj path j) child])
               (rest group)))

(defn- flatten-tagged
  "Flattens top-level `:and` groups, tagging each flattened condition with its
   origin path into the original LHS tree.  A top-level map entry gets origin
   `[i]`; the `j`th child of a top-level `:and` at index `i` gets `[i j]`."
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

(defn- sort-tagged
  "Reimplements `clara.rules.compiler/sort-conditions` while preserving each
   condition's origin.  Uses the compiler's own `com/analyze-condition` for
   the per-condition classification, so the ordering is identical."
  [tagged]
  (let [classified (mapv (fn [{:keys [condition] :as item}]
                           (assoc item :classified (com/analyze-condition condition)))
                         tagged)]
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
              newly (if has-non-accum
                      (filter satisfied-non-accum? remaining)
                      (filter satisfied? remaining))
              still (if has-non-accum
                      (remove satisfied-non-accum? remaining)
                      (remove satisfied? remaining))
              updated (apply set/union bound
                             (map (comp :bound :classified) newly))]
          (when (empty? newly)
            (let [unsatisfiable (set/difference
                                 (apply set/union (map (comp :unbound :classified) still))
                                 bound)]
              (throw (ex-info "Using variable that is not previously bound"
                              {:unbound-variables unsatisfiable}))))
          (recur (into sorted newly) updated still))))))

(defn- disjunction-branches
  "Returns the conjunction branches of `condition` after `com/to-dnf`, each
   branch a seq of conditions.  Mirrors the compiler's disjunction handling."
  [condition]
  (let [dnf (com/to-dnf condition)]
    (for [expression (if (= :or (first dnf))
                       (rest dnf)
                       [dnf])]
      (if (= :and (first expression))
        (rest expression)
        [expression]))))

(defn- expand-exists
  "Expands `:exists` conditions into accumulator conditions, mirroring
   `clara.rules.compiler/extract-exists`.  The generated `:result-binding` is
   deterministic — derived from the origin path and the condition's position in
   its conjunction — rather than a gensym, so repeated analyses of the same LHS
   are stable."
  [origin conjunctions]
  (mapcat (fn [i condition]
            (if (= :exists (com/condition-type condition))
              [{:accumulator '(clara.rules.accumulators/exists)
                :from (second condition)
                :result-binding (keyword (str "?__exists__"
                                              (apply str (interpose "_" origin))
                                              "__" i))}]
              [condition]))
          (range)
          conjunctions))

(defn- compound-negation?
  "True when `condition` is a `[:not [:and/:or/:not ...]]` group.  The compiler
   does not keep these in place — it extracts them into a helper production and
   a `NegationResult` condition — so this analyzer cannot map them back to the
   raw LHS tree and must defer them explicitly."
  [condition]
  (and (#{:not 'not} (first condition))
       (sequential? (second condition))
       (#{:and :or :not 'and 'or 'not} (first (second condition)))))

(defn- attach-path
  "Returns the path into the original LHS tree where `condition`'s binding info
   should be attached, or nil when the condition is a `:or` / `:exists` group or
   a compound negation (those are still analyzed for ancestor-bindings
   propagation, but their nested leaves are not augmented in this pass)."
  [origin condition]
  (cond
    (map? condition) origin
    (compound-negation? condition) nil
    (#{:not 'not} (first condition)) (ffirst (group-child-paths origin condition))
    :else nil))

(s/defn ^:private conjunction-binding :- LhsBindingRecord
  "Computes one binding record for an expanded conjunction."
  [conjunction env ancestor-bindings origin attach-path]
  (let [node (com/condition-to-node conjunction env ancestor-bindings)
        {:keys [result-binding fact-binding]} conjunction
        all-bindings (cond-> (set/union ancestor-bindings (:used-bindings node))
                       result-binding (conj result-binding)
                       fact-binding (conj fact-binding))]
    (cond-> {:condition conjunction
             :origin origin
             :attach-path attach-path
             :used-bindings (:used-bindings node)
             :binding-keys (or (:join-bindings node) #{})
             :new-bindings (:new-bindings node)
             :ancestor-bindings ancestor-bindings
             :all-bindings all-bindings}
      (:join-filter-join-bindings node)
      (assoc :join-filter-join-bindings (:join-filter-join-bindings node))

      result-binding
      (assoc :result-binding result-binding)

      fact-binding
      (assoc :fact-binding fact-binding))))

(defn- analyze-branch
  "Runs the binding walk over one conjunction branch, returning its records and
   final bindings."
  [conjunctions env ancestor-bindings origin attach-path]
  (loop [remaining conjunctions
         ancestor ancestor-bindings
         records []]
    (if-let [conjunction (first remaining)]
      (let [record (conjunction-binding conjunction env ancestor origin attach-path)]
        (recur (rest remaining)
               (:all-bindings record)
               (conj records record)))
      {:records records
       :final-bindings ancestor})))

(defn- analyze-tagged
  "Runs the compiler-order binding walk over sorted, origin-tagged conditions."
  [tagged env]
  (loop [remaining tagged
         ancestor-bindings #{}
         records []]
    (if-let [{:keys [origin condition]} (first remaining)]
      (let [ap (attach-path origin condition)
            branch-results (map (fn [branch]
                                  (analyze-branch (expand-exists origin branch)
                                                  env
                                                  ancestor-bindings
                                                  origin
                                                  ap))
                                (disjunction-branches condition))
            next-bindings (reduce set/union ancestor-bindings
                                  (map :final-bindings branch-results))
            next-records (into records (mapcat :records) branch-results)]
        (recur (rest remaining) next-bindings next-records))
      records)))

(defn analyze-lhs-bindings
  "Analyzes the compiler's binding bookkeeping for a normalized `lhs` using
   clara-rules' own `com/sort-conditions` and `com/condition-to-node`.  The
   normalized LHS is read via `get-raw-lhs` only for this compiler-coupled
   walk.

   Returns a flat vector of origin-tagged records, in compiler processing
   order.  Each record:

   * `:origin` — path into the LHS tree the record came from;
   * `:attach-path` — path where this record's info should be attached when
     augmenting the LHS (nil for `:or` / `:exists` groups and compound
     negations);
   * `:condition` — the expanded conjunction form (after `:exists` expansion);
   * `:used-bindings` — variables the condition references;
   * `:binding-keys` — variables already bound upstream that this condition
     joins on (the compiled node's `:binding-keys`);
   * `:new-bindings` — variables introduced by this condition's constraints;
   * `:join-filter-join-bindings` — present only when the condition has
     non-equality unifications that reference an upstream binding;
   * `:result-binding` / `:fact-binding` — present when the condition binds one;
   * `:ancestor-bindings` — bindings available before the condition;
   * `:all-bindings` — bindings available after it.

   `env` is the production's `:env` (usually nil)."
  [lhs env]
  (analyze-tagged (sort-tagged (flatten-tagged (get-raw-lhs lhs))) env))

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

(defn- path-index
  "Builds `{attach-path :bindings binding-summary}` from origin-tagged records,
   with binding sets sorted into vectors for deterministic consumers.  A
   duplicate attach-path is an analysis invariant violation, so it throws
   rather than silently overwriting."
  [records]
  (reduce (fn [acc {:keys [attach-path] :as record}]
            (if attach-path
              (do
                (when (contains? acc attach-path)
                  (throw (ex-info "Duplicate attach-path in LHS binding analysis"
                                  {:attach-path attach-path})))
                (assoc acc attach-path {:bindings (binding-summary record)}))
              acc))
          {}
          records))

(defn- walk-augment
  "Walks the (normalized, accumulator-enriched) LHS tree, merging binding info
   into leaf maps by path.  Group maps keep their `:condition-type` and have
   their `:children` recursed.  The internal `:raw-condition` and `::normalized`
   keys are already stripped before enrichment (see `strip-internal-keys`)."
  [x path binding-index]
  (cond
    (and (map? x) (contains? x :children))
    (update x :children
            (fn [children]
              (mapv (fn [j child]
                      (walk-augment child (conj path j) binding-index))
                    (range)
                    children)))

    (map? x) (if-let [binding-info (get binding-index path)]
               (merge x binding-info)
               x)

    :else x))

(defn augment-lhs
  "Enriches a normalized LHS for analysis.  Every entry is a map: group entries
   are `{:condition-type … :children […]}`; leaf entries carry
   `accumulator-info` (accumulator conditions) and a nested `:bindings` map
   (`:binding-keys` / `:new-bindings`, plus `:join-filter-join-bindings` when
   the condition has non-equality unifications that reference an upstream
   binding).

   The caller is responsible for normalizing the raw LHS first (see
   `normalize-lhs`, which is idempotent); this function only enriches an
   already-normalized LHS.

   `:or` / `:exists` groups and compound negations
   (`[:not [:and/:or/:not ...]]`) are still analyzed for ancestor-bindings
   propagation, but their nested leaves are not augmented in this pass.

   `opts`:
   * `:prod-ns` — production namespace (required for accumulator evaluation);
   * `:env` — the production's `:env` (usually nil)."
  [lhs {:keys [prod-ns env]}]
  (let [binding-index (path-index (analyze-lhs-bindings lhs env))
        enriched (enrich-accumulators (strip-internal-keys lhs) prod-ns)]
    (map-indexed (fn [i entry]
                   (walk-augment entry [i] binding-index))
                 enriched)))
