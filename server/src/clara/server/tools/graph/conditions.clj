(ns clara.server.tools.graph.conditions
  "Per-production LHS condition analysis.

  This namespace owns the analysis-time passes over a production's raw LHS
  conditions.  Serialization (`clara.server.tools.graph.serialize`) renders the
  results of these passes to JSON-friendly shapes but performs no analysis of
  its own.

  Two passes live here today:

  * `accumulator-info` / `enrich-lhs` — evaluate an accumulator condition's
    `:accumulator` form in the production namespace and attach
    `{:form form :some-initial-value? bool}` to the condition.

  * `analyze-lhs-bindings` — reproduce the compiler's binding bookkeeping
    (used / join / new bindings per condition) using clara-rules' own
    `sort-conditions` and `condition-to-node`, without building a beta graph."
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

(defn enrich-lhs
  "Returns `lhs` with every accumulator condition's `:accumulator` replaced by
   its `accumulator-info` map.  Non-accumulator conditions are unchanged."
  [lhs prod-ns]
  (walk/prewalk
   (fn [x]
     (if (and (map? x) (contains? x :accumulator))
       (update x :accumulator #(accumulator-info % prod-ns))
       x))
   lhs))

(defn- flatten-top-level-ands
  "Flattens top-level `:and` groups, mirroring the compiler's first LHS step."
  [lhs]
  (mapcat (fn [condition]
            (if (#{'and :and} (first condition))
              (rest condition)
              [condition]))
          lhs))

(defn- disjunction-branches
  "Returns the conjunction branches of `condition` after `to-dnf`, each branch a
   seq of conditions.  Mirrors the compiler's disjunction handling."
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

(defn- conjunction-binding
  "Computes the binding record for one expanded conjunction."
  [conjunction env ancestor-bindings]
  (let [node (com/condition-to-node conjunction env ancestor-bindings)
        {:keys [result-binding fact-binding]} conjunction
        all-bindings (cond-> (set/union ancestor-bindings (:used-bindings node))
                       result-binding (conj result-binding)
                       fact-binding (conj fact-binding))]
    (cond-> {:condition conjunction
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
  [conjunctions env ancestor-bindings]
  (loop [remaining conjunctions
         ancestor ancestor-bindings
         records []]
    (if-let [conjunction (first remaining)]
      (let [record (conjunction-binding conjunction env ancestor)]
        (recur (rest remaining)
               (:all-bindings record)
               (conj records record)))
      {:records records
       :final-bindings ancestor})))

(defn analyze-lhs-bindings
  "Analyzes the compiler's binding bookkeeping for `lhs` (a production's raw
   LHS conditions) using clara-rules' own `sort-conditions` and
   `condition-to-node`.

   Returns a flat vector of per-conjunction records, in compiler processing
   order.  Each record:

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
  (loop [remaining (com/sort-conditions (flatten-top-level-ands lhs))
         ancestor-bindings #{}
         records []]
    (if-let [condition (first remaining)]
      (let [branch-results (map (fn [branch]
                                  (analyze-branch (expand-exists branch)
                                                  env
                                                  ancestor-bindings))
                                (disjunction-branches condition))
            next-bindings (reduce set/union ancestor-bindings
                                  (map :final-bindings branch-results))
            next-records (into records (mapcat :records) branch-results)]
        (recur (rest remaining) next-bindings next-records))
      records)))
