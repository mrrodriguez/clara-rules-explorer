(ns clara.server.tools.graph.analyze.index
  "Pass 2 (Index) of the annotation-generation pipeline.

   Builds every derived, precomputed view over the merged clj-kondo analysis
   exactly once per `generate-annotations-from-analysis` run and shares them
   across all per-rule passes:

   * the call graph plus a memoized transitive-reachability fn (boundary fns
     terminate expansion);
   * var-usages indexed by caller and by callee — per-rule passes must never
     scan the whole `:var-usages` vector (that scan is what made generation
     quadratic in rules × usages at real-world scale);
   * `:locals` / `:local-usages` indexed by [filename id] / [filename name]
     for locals tracing;
   * direct inserter/retractor sets and the bottom-up inserter-type-maps;
   * the constructor-of-interest callsite map (when configured);
   * per-run memoized record-type resolution (live-ns lookups + class loads)
     and constructor-form source reads.

   Nothing here is rule-specific; per-rule work starts from the
   `AnalysisIndex`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [schema.core :as s]
            [clara.server.tools.graph.analyze.utils :as u]
            [clara.server.tools.graph.analyze.kondo :as kondo]
            [clara.server.tools.graph.analyze.ctor :as ctor]))

(def insert-fns
  "clara.rules vars that insert facts into working memory."
  #{'clara.rules/insert!
    'clara.rules/insert-unconditional!
    'clara.rules/insert-all-unconditional!
    'clara.rules/insert-all!
    'clara.rules.engine/insert-facts!
    'clara.rules/insert
    'clara.rules/insert-unconditional
    'clara.rules/insert-all})

(def retract-fns
  "clara.rules vars that retract facts from working memory."
  #{'clara.rules/retract!
    'clara.rules.engine/rhs-retract-facts!
    'clara.rules/retract})

(def boundary-fns
  "All insert/retract vars — the boundary where RHS execution reaches working
   memory.  Reachability expansion stops at these."
  (set/union insert-fns retract-fns))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(s/defschema CtorUsageMatch
  "A constructor-of-interest callsite found in an inserter's reachable subtree,
   paired with the `:type-resolver-fn` of the `:fact-constructors` spec that
   matched it (first matching spec in vector order wins)."
  {:usage u/KondoVarUsage
   :type-resolver-fn (s/=> s/Any s/Any)})

(s/defschema CtorCallsiteMap
  "Constructor-of-interest callsites by inserter var:
   {inserter-var -> [CtorUsageMatch …]}."
  {s/Symbol [CtorUsageMatch]})

(s/defschema AnalysisIndex
  "Everything the per-rule passes need, derived once from the merged analysis.

   `:get-source`, `:read-ctor-form` and `:resolve-record-type` are fns and so
   are schematized with `s/=>`; their shapes:

   * `:get-source`          - (fn [ns-sym filename] -> source-str-or-nil)
   * `:read-ctor-form`      - (fn [ctor-usage get-source] -> call-form-or-nil),
                              memoized per run
   * `:resolve-record-type` - (fn [ns-sym class-sym] -> fq-class-name-sym-or-nil),
                              memoized per run (live-ns lookups + class loads)

   `:productions-by-name` values are full rulebase productions — open maps;
   the keys of interest are :name :ns-name :lhs :rhs :props (relates to
   `clara.server.graph.api` production schemas, which add serialization
   concerns and stay at that layer)."
  {:graph                  {s/Symbol #{s/Symbol}}
   :usages-by-caller      {s/Symbol [u/KondoVarUsage]}
   :usages-by-callee      {s/Symbol [u/KondoVarUsage]}
   ;; [filename name] -> :local-usages entries (open kondo maps; keys of
   ;; interest: :id :name :row :col :filename)
   :local-usages-by-name  {[s/Any] [s/Any]}
   ;; [filename id] -> :locals binding (open kondo map; keys of interest:
   ;; :id :row :end-col :filename)
   :locals-by-id          {[s/Any] s/Any}
   :reachable-set         (s/=> #{s/Symbol} s/Symbol)
   :direct-inserters      #{s/Symbol}
   :direct-retractors     #{s/Symbol}
   :inserter-type-map     {s/Symbol {s/Symbol {:usage u/KondoVarUsage}}}
   :retractor-type-map    {s/Symbol {s/Symbol {:usage u/KondoVarUsage}}}
   :constructor-callsite-map (s/maybe CtorCallsiteMap)
   :get-source            (s/=> s/Any s/Any s/Any)
   :read-ctor-form        (s/=> s/Any s/Any s/Any)
   :resolve-record-type   (s/=> s/Any s/Any s/Any)
   :productions-by-name   {s/Symbol s/Any}})

;; ---------------------------------------------------------------------------
;; Call graph + reachability
;; ---------------------------------------------------------------------------

(defn- build-graph
  "Builds the var call graph {caller -> #{callee …}} from kondo `:var-usages`.
   :from-var is a symbol for usages inside a def, or nil/absent for top-level
   forms (clj-kondo never produces the *symbol* `nil`)."
  [usages]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if from-var
              (let [caller (u/fq-sym from from-var)
                    callee (u/fq-sym to name)]
                (update acc caller (fnil conj #{}) callee))
              acc))
          {}
          usages))

(defn- transitive-reachability
  "Transitive closure of callees from `start-vars`, stopping expansion at
   boundary fns (boundary fns themselves are included in the result)."
  [graph start-vars]
  (loop [seen #{}
         todo (set start-vars)]
    (if (empty? todo)
      seen
      (let [seen (into seen todo)
            traversable (set/difference todo boundary-fns)
            next-vars (set (mapcat graph traversable))
            unvisited (set/difference next-vars seen)]
        (recur seen unvisited)))))

(defn- memoized-reachability
  "Returns a (fn [var-sym] -> reachable-set) memoized per index build — every
   pass shares one cache instead of recomputing BFS closures per rule."
  [graph]
  (let [cache (atom {})]
    (fn [v]
      (if-let [hit (get @cache v)]
        hit
        (let [rs (transitive-reachability graph [v])]
          (swap! cache assoc v rs)
          rs)))))

(defn- direct-callers
  "Returns the set of graph vars that directly call any fn in `target-fns`."
  [graph target-fns]
  (into #{}
        (filter (fn [v] (some target-fns (get graph v))))
        (keys graph)))

;; ---------------------------------------------------------------------------
;; Bottom-up type maps (built via the by-caller index, never full scans)
;; ---------------------------------------------------------------------------

(defn- usage-pos
  "Deterministic source-position sort key for a kondo usage."
  [usage]
  [(str (:filename usage)) (or (:row usage) 0) (or (:col usage) 0)])

(defn- resolve-ctor-type-entry
  "Resolves a single kondo usage to a [type {:usage usage}] entry when the
   usage names a record constructor (`->X` / `map->X`) that resolves to a
   loadable type.  Returns nil for non-constructor usages and unresolvable
   types.  When `type-filter` is present, rejected types fire a `tap>` event
   and return nil."
  [usage {:keys [inserter-var resolve-record-type type-filter boundary mode]}]
  (when-let [t (resolve-record-type (:to usage) (:name usage))]
    (if (or (nil? type-filter)
            (type-filter {:inserter-var inserter-var
                          :usage usage
                          :type t}))
      [t {:usage usage}]
      (do (tap> {:event :clara-rules/type-fallback-skipped
                 :boundary boundary
                 :mode mode
                 :inserter-var inserter-var
                 :skipped-type t
                 :ctor-ns (:to usage)
                 :ctor-name (:name usage)
                 :filename (:filename usage)
                 :row (:row usage)
                 :col (:col usage)})
          nil))))

(defn- build-inserter-type-map
  "Bottom-up: for every var that directly calls a boundary fn, find record
   constructors (`map->X`, `->X`) resolvable to fact types within its
   reachable subtree.  Returns
   {inserter-var -> {fq-class-name-sym {:usage kondo-usage}}} — the usage is
   the first (by source position) constructor usage that resolved to the
   type, kept as provenance for heuristic callsites.

   This scan is a *heuristic fallback* for the caller-driven resolution paths
   (see `analyze/extract-insert-types`): because `clj-kondo`'s flat
   `:var-usages` analysis cannot distinguish argument expressions within a
   callsite from independent calls in the same function body, a var's
   reachable subtree may include constructors from unrelated RHS branches
   (e.g. a helper that builds both a fact and an unrelated record value for a
   side computation).

   `:type-filter` (optional) is a (fn [{:keys [inserter-var usage type]}] ->
   bool) consulted per resolved type — typically the
   `:rulebase-fact-types-only` restriction of
   `:dynamic-type-fallback-resolution`.  Rejected types are excluded and
   reported via `tap>` with full context (inert unless a consumer registers a
   tap).  `:boundary` (:insert or :retract) and `:mode` only enrich the tap
   context."
  [{:keys [direct-callers usages-by-caller reachable-set
           resolve-record-type type-filter boundary mode]
    :or {type-filter nil}}]
  (let [entry-opts {:resolve-record-type resolve-record-type
                    :type-filter type-filter
                    :boundary boundary
                    :mode mode}]
    (into {}
          (map (fn [v]
                 (let [subtree (reachable-set v)
                       entry-opts' (assoc entry-opts :inserter-var v)
                       pairs (into []
                                   (comp (mapcat #(get usages-by-caller %))
                                         (filter #(-> % :name name ctor/constructor-fn-name?))
                                         (keep #(resolve-ctor-type-entry % entry-opts')))
                                   subtree)
                       ;; Deterministic: keep the first usage by source
                       ;; position when several constructors resolve to the
                       ;; same type.
                       types (reduce (fn [m [t {:keys [usage] :as entry}]]
                                       (let [old (get m t)]
                                         (if (or (nil? old)
                                                 (neg? (compare (usage-pos usage)
                                                                (usage-pos (:usage old)))))
                                           (assoc m t entry)
                                           m)))
                                     {}
                                     pairs)]
                   [v types])))
          direct-callers)))

(defn- build-constructor-callsite-map
  "Like `build-inserter-type-map`, but for caller-supplied constructors of
   interest (`fact-constructors` — a vector of {:match-fn :type-resolver-fn}
   specs).  Each usage whose callee matches is paired with the *first*
   matching spec; vector order is precedence.

   Returns a `CtorCallsiteMap`."
  [direct-callers usages-by-caller reachable-set fact-constructors]
  (let [match-spec (fn [callee]
                     (some (fn [{:keys [match-fn] :as spec}]
                             (when (match-fn callee)
                               spec))
                           fact-constructors))]
    (into {}
          (keep (fn [v]
                  (let [subtree (reachable-set v)
                        ctor-matches (into []
                                           (comp (mapcat #(get usages-by-caller %))
                                                 (keep (fn [vu]
                                                         (when-let [spec (match-spec (u/var-usage-callee vu))]
                                                           {:usage vu
                                                            :type-resolver-fn (:type-resolver-fn spec)}))))
                                           subtree)]
                    (when (seq ctor-matches)
                      [v ctor-matches]))))
          direct-callers)))

;; ---------------------------------------------------------------------------
;; The index
;; ---------------------------------------------------------------------------

(defn build-analysis-index
  "Derives the `AnalysisIndex` from a merged clj-kondo `analysis` map.

   `fact-constructors` (optional, [{:match-fn :type-resolver-fn} …]) enables
   the constructor-of-interest callsite map.

   `fallback-type-filter` (optional) is a (fn [{:keys [inserter-var usage
   type]}] -> bool) restricting which record-ctor types the heuristic
   inserter/retractor type maps may credit (see
   `analyze/:dynamic-type-fallback-resolution`); rejected types are excluded
   and reported via `tap>`.  `fallback-mode` is the mode keyword, used only
   for tap context."
  [{:keys [analysis get-source productions-by-name fact-constructors
           fallback-type-filter fallback-mode]}]
  (let [usages (:var-usages analysis)
        graph (build-graph usages)
        usages-by-caller (group-by u/var-usage-caller usages)
        usages-by-callee (group-by u/var-usage-callee usages)
        local-usages-by-name (group-by (juxt :filename :name) (:local-usages analysis))
        locals-by-id (into {}
                           (map (juxt (juxt :filename :id) identity))
                           (:locals analysis))
        reachable-set (memoized-reachability graph)
        direct-inserters (direct-callers graph insert-fns)
        direct-retractors (direct-callers graph retract-fns)
        resolve-record-type (memoize ctor/resolve-record-type)
        inserter-type-map (build-inserter-type-map
                           {:direct-callers direct-inserters
                            :usages-by-caller usages-by-caller
                            :reachable-set reachable-set
                            :resolve-record-type resolve-record-type
                            :type-filter fallback-type-filter
                            :boundary :insert
                            :mode fallback-mode})
        retractor-type-map (build-inserter-type-map
                            {:direct-callers direct-retractors
                             :usages-by-caller usages-by-caller
                             :reachable-set reachable-set
                             :resolve-record-type resolve-record-type
                             :type-filter fallback-type-filter
                             :boundary :retract
                             :mode fallback-mode})
        constructor-callsite-map (when (seq fact-constructors)
                                   (build-constructor-callsite-map
                                    (direct-callers graph boundary-fns)
                                    usages-by-caller reachable-set fact-constructors))
        all-boundary-fns (into insert-fns retract-fns)
        boundary-usages-by-caller (reduce
                                   (fn [m u]
                                     (if (contains? all-boundary-fns
                                                    (u/var-usage-callee u))
                                       (let [caller (u/var-usage-caller u)]
                                         (update m caller (fnil conj []) u))
                                       m))
                                   {}
                                   usages)
        get-lines (let [lines-cache (atom {})]
                    (fn [ns-sym filename]
                      (let [k (or ns-sym filename)]
                        (or (get @lines-cache k)
                            (when-let [source (get-source ns-sym filename)]
                              (let [ls (str/split-lines source)]
                                (swap! lines-cache assoc k ls)
                                ls))))))]
    {:graph graph
     :usages-by-caller usages-by-caller
     :usages-by-callee usages-by-callee
     :boundary-usages-by-caller boundary-usages-by-caller
     :local-usages-by-name local-usages-by-name
     :locals-by-id locals-by-id
     :reachable-set reachable-set
     :direct-inserters direct-inserters
     :direct-retractors direct-retractors
     :inserter-type-map inserter-type-map
     :retractor-type-map retractor-type-map
     :constructor-callsite-map constructor-callsite-map
     :get-source get-source
     :get-lines get-lines
     :read-ctor-form (memoize (fn [ctor-usage] (kondo/read-ctor-form ctor-usage get-source get-lines)))
     :resolve-record-type resolve-record-type
     :productions-by-name productions-by-name}))
