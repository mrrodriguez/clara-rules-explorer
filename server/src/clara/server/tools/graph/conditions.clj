(ns clara.server.tools.graph.conditions
  "Per-production LHS condition analysis.

  This namespace owns the analysis-time passes over a production's raw LHS
  conditions.  Serialization (`clara.server.tools.graph.serialize`) renders the
  results of these passes to JSON-friendly shapes but performs no analysis of
  its own.

  Passes:

  * `accumulator-info` — evaluate an accumulator condition's `:accumulator`
    form in the production namespace and return
    `{:form form :some-initial-value? bool}`.

  * `analyze-lhs-bindings` — reproduce the compiler's binding bookkeeping
    (used / join / new bindings per condition) using clara-rules' own
    `sort-conditions` and `condition-to-node`, tagged with each record's
    origin path in the raw LHS.

  * `augment-lhs` — the pass `core` actually calls: evaluate accumulators and
    attach per-leaf binding info to the original LHS shape."
  (:require [clara.rules.compiler :as com]
            [clojure.set :as set]
            [clojure.walk :as walk]))

(defn accumulator-info
  "Evaluates an accumulator form in the production's namespace and returns
   `{:form form :some-initial-value? bool}`.  The `:form` is returned unchanged
   (the raw Clojure form); serialization renders it to a string.

   `:some-initial-value?` is `(some? (:initial-value <evaluated-accumulator>))`.

   Throws when the production namespace is not loaded or the form does not
   evaluate to a map.  Analysis assumes the rulebase's namespaces are already
   loaded in the runtime — the same assumption the rest of the analysis makes
   for symbol resolution."
  [form prod-ns]
  (let [acc-ns (or (some-> prod-ns find-ns)
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

(defn- flatten-tagged
  "Flattens top-level `:and` groups, tagging each flattened condition with its
   origin path into the original LHS tree.  A top-level map entry gets origin
   `[i]`; the `j`th child of a top-level `:and` at index `i` gets
   `[i (inc j)]`."
  [lhs]
  (vec
   (mapcat (fn [i condition]
             (if (#{'and :and} (first condition))
               (map-indexed (fn [j child]
                              {:origin [i (inc j)]
                               :condition child})
                            (rest condition))
               [{:origin [i]
                 :condition condition}]))
           (range) lhs)))

(defn- sort-tagged
  "Reimplements `clara.rules.compiler/sort-conditions` while preserving each
   condition's origin.  Uses the compiler's own `analyze-condition` for the
   per-condition classification, so the ordering is identical."
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
          (recur (into sorted newly) updated still))))))

(defn- disjunction-branches
  "Returns the conjunction branches of `condition` after `to-dnf`, each branch
   a seq of conditions.  Mirrors the compiler's disjunction handling."
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
   `clara.rules.compiler/extract-exists`."
  [conjunctions]
  (mapcat (fn [condition]
            (if (= :exists (com/condition-type condition))
              [{:accumulator '(clara.rules.accumulators/exists)
                :from (second condition)
                :result-binding (keyword (gensym "?__gen__"))}]
              [condition]))
          conjunctions))

(defn- attach-path
  "Returns the path into the original LHS tree where `condition`'s binding info
   should be attached, or nil when the condition is a grouped form we do not
   augment yet (`:or` / `:exists`)."
  [origin condition]
  (cond
    (map? condition) origin
    (#{:not 'not} (first condition)) (conj origin 1)
    :else nil))

(defn- conjunction-binding
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
             :join-bindings (:join-bindings node)
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
                                  (analyze-branch (expand-exists branch)
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
  "Analyzes the compiler's binding bookkeeping for `lhs` (a production's raw
   LHS conditions) using clara-rules' own `sort-conditions` and
   `condition-to-node`.

   Returns a flat vector of origin-tagged records, in compiler processing
   order.  Each record:

   * `:origin` — path into the raw LHS tree the record came from;
   * `:attach-path` — path where this record's info should be attached when
     augmenting the original LHS (nil for `:or` / `:exists` groups);
   * `:condition` — the expanded conjunction form (after `:exists` expansion);
   * `:used-bindings` — variables the condition references;
   * `:join-bindings` — variables already bound upstream that this condition
     joins on (the compiled node's `:binding-keys`);
   * `:new-bindings` — variables introduced by this condition's constraints;
   * `:join-filter-join-bindings` — present only when the condition has
     non-equality unifications;
   * `:result-binding` / `:fact-binding` — present when the condition binds one;
   * `:ancestor-bindings` — bindings available before the condition;
   * `:all-bindings` — bindings available after it.

   `env` is the production's `:env` (usually nil)."
  [lhs env]
  (analyze-tagged (sort-tagged (flatten-tagged lhs)) env))

(defn- sort-bindings
  "Returns a deterministic, sorted vector of binding keywords."
  [bindings]
  (vec (sort-by name bindings)))

(defn- path-index
  "Builds `{attach-path binding-summary}` from origin-tagged records, with
   binding sets sorted into vectors for deterministic consumers."
  [records]
  (into {}
        (keep (fn [{:keys [attach-path used-bindings join-bindings new-bindings
                           ancestor-bindings all-bindings]}]
                (when attach-path
                  [attach-path {:used-bindings (sort-bindings used-bindings)
                                :binding-keys (sort-bindings join-bindings)
                                :new-bindings (sort-bindings new-bindings)
                                :ancestor-bindings (sort-bindings ancestor-bindings)
                                :all-bindings (sort-bindings all-bindings)}])))
        records))

(defn- walk-augment
  "Walks the (already accumulator-enriched) LHS tree, merging binding info into
   leaf maps by path.  Group vectors are kept as vectors and only their nested
   leaf maps are augmented."
  [x path binding-index]
  (cond
    (map? x) (if-let [binding-info (get binding-index path)]
               (merge x binding-info)
               x)

    (vector? x) (into [(first x)]
                      (map-indexed (fn [j child]
                                     (walk-augment child
                                                   (conj path (inc j))
                                                   binding-index)))
                      (rest x))

    :else x))

(defn augment-lhs
  "Returns `lhs` enriched for analysis: accumulator conditions carry
   `accumulator-info`, and leaf conditions carry per-leaf binding info
   (`:used-bindings`, `:binding-keys`, `:new-bindings`,
   `:ancestor-bindings`, `:all-bindings`).

   Group vectors (`:and`, `:or`, `:not`, `:exists`) are kept as vectors; only
   their nested leaf maps are augmented.  `:or` / `:exists` group leaves are
   not augmented in this pass.

   `opts`:
   * `:prod-ns` — production namespace (required for accumulator evaluation);
   * `:env` — the production's `:env` (usually nil)."
  [lhs {:keys [prod-ns env]}]
  (let [binding-index (path-index (analyze-lhs-bindings lhs env))
        enriched (enrich-accumulators lhs prod-ns)]
    (map-indexed (fn [i entry]
                   (walk-augment entry [i] binding-index))
                 enriched)))
