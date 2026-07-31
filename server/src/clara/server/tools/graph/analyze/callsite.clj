(ns clara.server.tools.graph.analyze.callsite
  "Runtime resolution of dynamic boundary callsites.

   When a rule's RHS reaches clara.rules/insert!/retract! with argument forms
   whose fact types static analysis cannot determine (Java constructors,
   helper-built values, macro-emitted locals, literals), each captured argument
   form goes through the constructor resolution chain:

   1. Record ctor form — head symbol resolves in the live caller ns to a
      ->X/map->X ctor var whose derived class loads ⇒ fq class-name token.
   2. Java ctor form — (X. …), (new X …), (X/new …): strip the ctor marker,
      resolve the class name in the live caller ns ⇒ fq class-name token.
   3. Local symbol — kondo :local-usages :id → :locals binding → init form
      (read from the source right after the binding symbol) → the chain
      restarts on the traced form (depth-capped).
   4. Otherwise → the caller's :callsite-resolver-fn, then :none (unresolved).

   The automatic chain resolves only what we know the instance type of —
   constructors. Everything else deliberately defers to the caller's
   resolver fn: with-meta maps, helper calls, the var-as-fact pattern, and
   literals. A literal arg is source text read as data (an unevaluated
   template when it contains rule bindings), and classifying it would mean
   running the session's caller-configured fact-type-fn on fabricated data —
   the resolver receives the read object as :arg-form and can do so
   knowingly.

   Two resolution passes run over the same traced arguments
   (`trace-boundary-args`): the constructor-of-interest pass
   (`resolve-constructor-callsites`, caller-declared constructors reached
   through the call chain) and the generic boundary pass
   (`resolve-boundary-callsites`, the chain above).  The constructor pass
   runs first and *owns* the arguments it accounts for, so no insert is
   reported twice.

   Var-alias chains (`:fact-type-spec-fn`, see `analyze.alias`): callsites
   discovered through an alias chain bypass the ctor chain and are recorded
   :none (unresolved) with `:fact-type`/`:fact-type-spec` context attached (then
   handed to the resolver).

   Callsite `:status` and dimension `:resolution` use one three-valued
   vocabulary — `:none` / `:partial` / `:full` (see
   docs/rule-annotations.md).  The analyzer emits only `:full` and `:none`;
   `:partial` is reachable through curation and through dimension-level
   aggregation.

   All Clojure syntax understanding comes from clj-kondo; reading forms at
   kondo positions lives in `analyze.kondo`, constructor recognition in
   `analyze.ctor`."
  (:require [schema.core :as s]
            [clara.server.tools.graph.analyze.utils :as u]
            [clara.server.tools.graph.analyze.kondo :as kondo]
            [clara.server.tools.graph.analyze.ctor :as ctor]))

(def ^:private max-resolution-depth 8)

;; ---------------------------------------------------------------------------
;; Step 3: locals tracing
;; ---------------------------------------------------------------------------

(defn- find-local-binding
  "Finds the kondo `:locals` binding for a local symbol used as a boundary-call
   argument: the `:local-usages` entry matching the arg symbol within the
   boundary usage's position span, linked to its binding via kondo's per-ns-run
   `:id`.  Both lookups are constrained to the boundary usage's `:filename`
   because ids restart per analyzed namespace and collide in the merged analysis.

   Uses the precomputed `:local-usages-by-name` / `:locals-by-id` indexes
   (see `index/AnalysisIndex`) — never scans the full analysis vectors."
  [{:keys [local-usages-by-name locals-by-id]} usage arg-sym]
  (let [{:keys [row col end-row end-col filename]} usage
        within-span? (fn [u]
                       (and (= filename (:filename u))
                            (<= row (:row u) end-row)
                            (or (not= row (:row u)) (<= col (:col u)))
                            (or (not= end-row (:row u)) (< (:col u) end-col))))
        local-usage (->> (get local-usages-by-name [filename arg-sym])
                         (filter within-span?)
                         first)]
    (when-let [id (:id local-usage)]
      (get locals-by-id [filename id]))))

(defn- trace-local-form
  "Follows local-symbol arguments to their binding init forms.  Depth-capped.

   Returns `{:form … :binding …}` — :form is the deepest form reached (the
   init form of the innermost traced local, or arg-form itself when it is
   not a traceable local); :binding is the kondo `:locals` entry whose init
   form IS :form, or nil when no local was traced.  The binding is the
   *identity link* constructor-ownership rule 3 matches on (see
   `arg-reaches-ctor?`): it ties the traced form to one exact source
   position, so two textually-identical forms at different positions can
   never be confused."
  [arg-form {:keys [get-source usage] :as ctx} depth]
  (if (and (symbol? arg-form) (< depth max-resolution-depth))
    (if-let [binding (find-local-binding ctx usage arg-form)]
      (if-let [init-form (kondo/read-init-form (get-source (:from usage) (:filename usage))
                                               binding)]
        (let [deeper (trace-local-form init-form ctx (inc depth))]
          (if (:binding deeper)
            deeper
            {:form (:form deeper) :binding binding}))
        {:form arg-form :binding nil})
      {:form arg-form :binding nil})
    {:form arg-form :binding nil}))

;; ---------------------------------------------------------------------------
;; Token normalization
;; ---------------------------------------------------------------------------

(defn- normalize-token
  "Normalizes a resolved fact-type token: `Class` objects become their fq
   class-name symbols (consistent with statically-resolved ctor tokens);
   anything else (keywords, symbols, arbitrary fact-type shapes) passes through."
  [t]
  (if (class? t)
    (symbol (.getName ^Class t))
    t))

;; ---------------------------------------------------------------------------
;; Step 5: caller-supplied resolution
;; ---------------------------------------------------------------------------

(defn- resolver-context
  "Builds the context map handed to `:callsite-resolver-fn` (see
   `analyze/generate-annotations-from-analysis`).  Alias context keys
   (`:fact-type`/`:fact-type-spec`) are present only for callsites discovered
   through a var-alias chain (`:fact-type-spec-fn`)."
  [{:keys [rule direction usage alias-context]} arg-form]
  (cond-> {:rule rule
           :ns-name-sym (:from usage)
           :direction direction
           :boundary-fn (symbol (str (:to usage)) (str (:name usage)))
           :arg-form arg-form
           :source-str (pr-str arg-form)
           :filename (:filename usage)}
    alias-context (merge (select-keys alias-context [:fact-type :fact-type-spec]))))

(defn- apply-resolver
  "Invokes the caller's `:callsite-resolver-fn`; exceptions are contained
   (logged, treated as unresolved).  Returns the resolver's `:resolved-types`
   sequence, or nil."
  [resolver-fn call-ctx]
  (when resolver-fn
    (try
      (some-> (resolver-fn call-ctx) :resolved-types seq)
      (catch Throwable t
        (binding [*out* *err*]
          (println (str "clara.server.tools.graph.analyze: :callsite-resolver-fn threw: "
                        (ex-message t))))
        nil))))

;; ---------------------------------------------------------------------------
;; The chain + detection-map assembly
;; ---------------------------------------------------------------------------

(defn- resolve-traced-arg
  "Runs the full resolution chain for an already-locals-traced argument form.
   Returns a set of resolved fact-type tokens (empty when unresolved).
   Alias-discovered callsites (`:alias-context` in ctx) bypass the ctor chain —
   they are never automatically resolved — and go straight to the resolver."
  [traced {:keys [callsite-resolver-fn alias-context] :as ctx} live-ns-sym]
  (let [resolved
        (or
         ;; constructors (on the traced form); not for alias-discovered callsites.
         (when (and (not alias-context) (seq? traced))
           (ctor/resolve-ctor-form (:resolve-record-type ctx) live-ns-sym traced))
         ;; everything else defers to the caller's escape hatch (receives the
         ;; traced form): helper calls, with-meta, var-as-fact, literals.
         (apply-resolver callsite-resolver-fn (resolver-context ctx traced))
         '())]
    (into #{}
          (map normalize-token)
          resolved)))

(defn trace-boundary-args
  "Reads and locals-traces every argument of every boundary usage, once.

   Both resolution paths work from this: the constructor path decides which of
   these arguments it owns, and the boundary path resolves the rest.  Tracing up
   front is what lets the constructor path recognise
   `(let [f (->fact :t m)] (insert! f))` — the argument `f` names nothing, but
   its traced form is the constructor call.

   Returns `[TracedArg …]`."
  [usages {:keys [get-source alias-context-for] :as ctx}]
  (into []
        (comp (mapcat (fn [usage]
                        (let [alias-ctx (when alias-context-for
                                          (alias-context-for usage))]
                          (map (fn [arg]
                                 (let [ctx' (assoc ctx :usage usage :alias-context alias-ctx)
                                       {:keys [form binding]} (trace-local-form arg ctx' 0)]
                                   {:usage usage
                                    :arg arg
                                    :alias-context alias-ctx
                                    :traced form
                                    :traced-binding binding}))
                               (or (kondo/read-boundary-args usage get-source) '())))))
              (map-indexed (fn [i ta] (assoc ta :idx i))))
        usages))

(defn resolution-status
  "Aggregates a callsite vector into a dimension-level resolution: nil (no
   callsites — the dimension is absent), :full (all :full), :none (all
   :none), :partial (otherwise).  Shared by the analyzer and by
   `annotations.callsite/aggregate-resolution`, which additionally excludes
   quarantined (`:dangling?`) callsites first."
  [callsites]
  (cond
    (empty? callsites) nil
    (every? #(= :full (:status %)) callsites) :full
    (every? #(= :none (:status %)) callsites) :none
    :else :partial))

(defn resolve-boundary-callsites
  "Resolves boundary-call arguments via the ctor chain and the optional
   `:callsite-resolver-fn`.

   `traced-args` — entries from `trace-boundary-args`, already filtered to those
   the constructor path did not own.

   `ctx` keys (an `index/AnalysisIndex` plus):
     `:local-usages-by-name` / `:locals-by-id` - locals indexes (for tracing)
     `:resolve-record-type`   - memoized record-type resolver
     `:direction`             - `:insert` | `:retract`
     `:rule`                  - the full production map of the consuming rule (may be nil)
     `:callsite-resolver-fn`  - optional caller escape hatch

   Returns a `CallsiteResolution` including `:resolved-arg-idxs` — the `:idx`
   of every traced argument that resolved to at least one type (used by
   `analyze/extract-insert-types` for per-inserter-var heuristic fallback
   attribution)."
  [traced-args ctx]
  (let [pairs (into []
                    (map (fn [{:keys [idx usage arg traced alias-context]}]
                           (let [ctx' (assoc ctx :usage usage :alias-context alias-context)
                                 tokens (resolve-traced-arg traced ctx' (:from usage))
                                 entry (cond-> {:source-str (pr-str arg)
                                                :ns-name-sym (:from usage)
                                                :filename (:filename usage)
                                                :status (if (empty? tokens) :none :full)}
                                         (seq tokens)
                                         (assoc :resolved-types (vec (sort-by str tokens)))

                                         alias-context
                                         (merge (select-keys alias-context [:fact-type :fact-type-spec])))]
                             [idx entry])))
                    traced-args)
        entries (into [] (comp (map second) (distinct)) pairs)
        resolved-arg-idxs (into #{}
                                (comp (filter (fn [[_ entry]] (seq (:resolved-types entry))))
                                      (map first))
                                pairs)
        resolved-types (into #{} (mapcat :resolved-types) entries)]
    {:callsites entries
     :resolved-types resolved-types
     :resolved-arg-idxs resolved-arg-idxs
     :resolution (resolution-status entries)}))

;; ---------------------------------------------------------------------------
;; Fact-constructor callsite resolution
;; ---------------------------------------------------------------------------

(defn- pos<=
  "Source-position ordering: `[row col]` before-or-equal `[row col]`."
  [r1 c1 r2 c2]
  (or (< r1 r2) (and (= r1 r2) (<= c1 c2))))

(defn- usage-encloses?
  "Does the source span of usage `outer` lexically enclose the start of usage
   `inner`?  Both must be in the same file.

   This is how a constructor callsite is attributed to the specific
   `insert!`/`retract!` call it was written inside — `(insert! (->fact …))` —
   as opposed to merely living somewhere in the same rule var."
  [outer inner]
  (let [{r1 :row c1 :col er1 :end-row ec1 :end-col f1 :filename} outer
        {r2 :row c2 :col f2 :filename} inner]
    (boolean
     (and f1 f2 (= f1 f2)
          r1 c1 er1 ec1 r2 c2
          (pos<= r1 c1 r2 c2)
          (pos<= r2 c2 er1 ec1)))))

(defn- shortest-call-path
  "BFS from start to end in the call graph.
   Returns [start … end] or nil when unreachable.
   Neighbors are sorted by str for deterministic traversal."
  [graph start end]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [start])
         visited #{start}]
    (when-let [path (peek queue)]
      (let [node (peek path)]
        (if (= node end)
          path
          (let [neighbors (->> (get graph node)
                               (remove visited)
                               (sort-by str)
                               vec)]
            (recur (into (pop queue) (map #(conj path %) neighbors))
                   (into visited neighbors))))))))

(defn- resolve-ctor-callsite
  "Resolves a single constructor-of-interest callsite.
   Returns a callsite entry map.

   `cfg` is a map with:
     :ctor-usage  - the kondo :var-usage for the constructor call
     :ctor-form   - its call form, from `kondo/read-ctor-form`
     :boundary-usage - the boundary call this constructor was reached from
     :call-path - [inserter-var … containing-var] from `ctor-call-path`
     :direction - :insert or :retract
     :rule - the rule production
     :resolver-fn - the `:type-resolver-fn` of the `:fact-constructors` spec
       that matched this callsite"
  [{:keys [ctor-usage ctor-form boundary-usage call-path direction rule resolver-fn]}]
  (let [boundary-fn-sym (u/fq-sym (:to boundary-usage) (:name boundary-usage))
        ctor-sym (u/fq-sym (:to ctor-usage) (:name ctor-usage))
        via (when (seq call-path)
              {:boundary-var-name-sym boundary-fn-sym
               :callstack (conj (mapv (fn [v] {:var-name-sym v}) call-path)
                                {:var-name-sym ctor-sym})})
        arg-form ctor-form
        resolver-ctx (cond-> {:constructor-sym ctor-sym
                              :arg-form arg-form
                              :ns-name-sym (:from ctor-usage)
                              :filename (:filename ctor-usage)
                              :direction direction
                              :rule rule}
                       via (assoc :via via))
        resolved (try
                   (some-> (resolver-fn resolver-ctx)
                           :resolved-types seq)
                   (catch Throwable t
                     (binding [*out* *err*]
                       (println
                        (format "clara.server.tools.graph.analyze: :fact-constructors :type-resolver-fn threw: %s"
                                (ex-message t))))
                     nil))
        tokens (into #{} (map normalize-token) (or resolved '()))]
    (cond-> {:source-str (pr-str arg-form)
             :ns-name-sym (:from ctor-usage)
             :filename (:filename ctor-usage)
             :constructor-sym ctor-sym
             :status (if (empty? tokens) :none :full)}
      (seq tokens) (assoc :resolved-types (vec (sort-by str tokens)))
      via (assoc :via via))))

(defn- ctor-call-path
  "Call-graph path `[inserter-var … containing-var]` for a constructor usage —
   how the insert chain gets from the boundary call's caller to the var the
   constructor is written in.  `[inserter-var]` when the constructor is written
   in the inserter itself; nil when unreachable."
  [graph inserter-var ctor-usage]
  (let [ctor-caller (u/fq-sym (:from ctor-usage) (:from-var ctor-usage))]
    (if (= inserter-var ctor-caller)
      [ctor-caller]
      (shortest-call-path graph inserter-var ctor-caller))))

(defn- arg-reaches-ctor?
  "True when a traced boundary argument demonstrably reaches the given
   constructor usage — see `owning-arg` for the three ownership routes.

   Rule 3 matches by *position identity*, not form value: the traced form's
   originating binding (`:traced-binding`) must have its init form starting
   at exactly the constructor usage's call-form position.  Two
   textually-identical constructor forms in one rule can therefore never
   cross-attribute."
  [{:keys [traced-arg ctor-usage intermediates sibling-usages get-source]}]
  (let [{:keys [usage traced-binding alias-context]} traced-arg]
    (and (not alias-context)       ; alias callsites are never auto-resolved
         (or (usage-encloses? usage ctor-usage)
             (and traced-binding
                  (= (:filename usage) (:filename ctor-usage))
                  (= (kondo/init-form-start (get-source (:from usage) (:filename usage))
                                            traced-binding)
                     [(:row ctor-usage) (:col ctor-usage)]))
             (some (fn [u]
                     (and (usage-encloses? usage u)
                          (contains? intermediates (u/fq-sym (:to u) (:name u)))))
                   sibling-usages)))))

(defn- owning-arg
  "The boundary argument a constructor call was reached *through*, or nil.

   Three ways an argument reaches a constructor — one per way the argument can
   be written, each decided from data clj-kondo already gives us:

     1. **It is the constructor call.** The constructor's source span sits
        inside the boundary call's: `(insert! (->fact :t m))`, including nested
        forms such as `(insert-all! (mapv #(->fact :t %) xs))`.
     2. **It is a call that leads there.** Some call written inside the boundary
        call names a link on `intermediates` — the call-graph path from the
        inserter to the constructor's containing var:
        `(insert! (my-middle-fn args))`. Works at any depth.
     3. **It is a local bound to it.** The argument's locals-traced form is the
        constructor call: `(let [f (->fact :t m)] (insert! f))`.  A bare local
        names nothing, so the match is by *position identity*: the traced
        form's binding init position must equal the constructor usage's
        call-form position (see `arg-reaches-ctor?`).  Two identical forms
        at different positions never cross-attribute.

   `intermediates` deliberately excludes the constructor symbol itself — only
   rule 1 may match the constructor, and by *usage identity*, not by name.
   Otherwise a rule with two separate `->fact` calls would attribute both to
   whichever boundary call happened to contain one of them.

   `sibling-usages` are the var-usages written in the same var as the boundary
   calls — the candidates for \"a call written inside this boundary call\".

   nil means no boundary argument demonstrably reaches this constructor: the
   constructor call is not on an insert path out of this rule."
  [ctor-usage intermediates traced-args sibling-usages get-source]
  (some #(when (arg-reaches-ctor? {:traced-arg %
                                   :ctor-usage ctor-usage
                                   :intermediates intermediates
                                   :sibling-usages sibling-usages
                                   :get-source get-source})
           %)
        traced-args))

(defn- resolve-ctor-usage-for-inserter
  "Attempts to resolve a single constructor-of-interest match against the
   traced boundary arguments of a single inserter var.  `ctor-match` is an
   `index/CtorUsageMatch` — {:usage … :type-resolver-fn …}.  Returns
   `[idx entry]` when a boundary argument is shown to reach the constructor
   *and* the type-resolver returns a type; nil when the constructor is
   unreachable, unowned, or unresolved (the argument then falls through to
   the boundary path instead of being reported twice)."
  [{:keys [ctor-match inserter-var graph get-source read-ctor-form cfg-base candidates siblings]}]
  (let [{:keys [usage type-resolver-fn]} ctor-match
        ctor-usage usage
        path (ctor-call-path graph inserter-var ctor-usage)
        ctor-form (read-ctor-form ctor-usage get-source)
        owner (owning-arg ctor-usage (set (rest path))
                          candidates siblings get-source)]
    (when owner
      (let [entry (resolve-ctor-callsite
                   (assoc cfg-base
                          :ctor-usage ctor-usage
                          :ctor-form ctor-form
                          :call-path path
                          :resolver-fn type-resolver-fn
                          :boundary-usage (:usage owner)))]
        (when (not= :none (:status entry))
          [(:idx owner) entry])))))

(defn resolve-constructor-callsites
  "Resolves constructor-of-interest callsites reached from a rule's boundary calls.

   `traced-args` — entries from `trace-boundary-args` for this rule var.
   `constructor-ctr-map` — an `index/CtorCallsiteMap`
     ({inserter-var -> [CtorUsageMatch …]} from `index/build-analysis-index`),
     scoped to this rule var.
   `ctx` — must contain :get-source, :read-ctor-form (memoized), :graph,
     :direction, :rule, :usages-by-caller.

   A constructor is emitted only when some boundary argument is shown to reach
   it (see `owning-arg`) *and* the resolver returns a type.  A constructor call
   that no insert flows through is not an insert — dropping it is what keeps a
   `(let [f (->fact :x)] (insert! (other)))` from claiming `:x`.  A constructor
   the resolver cannot type is left to the boundary path rather than reported
   twice.

   Returns a `CallsiteResolution` including `:owned-arg-idxs` — the `:idx` of
   every boundary argument a constructor accounted for.  Those must not also go
   through `resolve-boundary-callsites`, or the same insert would be reported
   twice (see `analyze/extract-insert-types`)."
  [traced-args constructor-ctr-map {:keys [get-source read-ctor-form graph direction rule
                                           usages-by-caller]}]
  (let [args-by-caller (group-by #(u/fq-sym (:from (:usage %)) (:from-var (:usage %)))
                                 traced-args)
        cfg-base {:direction direction
                  :rule rule}
        pairs (into []
                    (mapcat
                     (fn [[inserter-var ctor-matches]]
                       (let [candidates (sort-by (juxt #(:row (:usage %)) #(:col (:usage %)))
                                                 (get args-by-caller inserter-var))
                             siblings (get usages-by-caller inserter-var)]
                         (keep #(resolve-ctor-usage-for-inserter
                                 {:ctor-match %
                                  :inserter-var inserter-var
                                  :graph graph
                                  :get-source get-source
                                  :read-ctor-form read-ctor-form
                                  :cfg-base cfg-base
                                  :candidates candidates
                                  :siblings siblings})
                               ctor-matches)))
                     constructor-ctr-map))
        entries (mapv second pairs)
        resolved-types (into #{} (mapcat :resolved-types) entries)]
    {:callsites entries
     :resolved-types resolved-types
     :owned-arg-idxs (into #{} (map first) pairs)
     :resolution (resolution-status entries)}))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(s/defschema TracedArg
  "One boundary-call argument after reading + locals tracing (the output of
   `trace-boundary-args`).  `:arg` and `:traced` are unevaluated source data —
   rule bindings inside them are free symbols.  `:alias-context` is non-nil
   only for callsites discovered through a var-alias chain."
  {:idx s/Int
   :usage u/KondoVarUsage
   :arg s/Any
   :traced s/Any
   ;; the kondo :locals entry whose init form is :traced (open kondo map;
   ;; keys of interest: :row :end-col) — nil when :traced is :arg itself
   :traced-binding (s/maybe s/Any)
   ;; :fact-type is s/Any: keywords, fq class-name symbols, strings are all
   ;; legitimate fact types; :fact-type-spec is an open caller-defined map
   :alias-context (s/maybe {(s/optional-key :fact-type) s/Any
                            (s/optional-key :fact-type-spec) {s/Keyword s/Any}})})

(s/defschema ViaEntry
  "A single entry in a constructor callstack chain (internal symbol form;
   `clara.server.graph.api/ViaEntry` is its serialized string counterpart)."
  {:var-name-sym s/Symbol})

(s/defschema ViaChain
  "Provenance chain from a boundary fn to a constructor callsite (internal
   symbol form; `clara.server.graph.api/ViaChain` is its serialized string
   counterpart).  `:source` marks heuristic provenance — `:record-ctor-scan`
   when the callsite comes from the subtree-wide record-ctor scan fallback
   rather than a traced call chain; heuristic entries have no `:callstack`."
  {(s/optional-key :boundary-var-name-sym) s/Symbol
   (s/optional-key :callstack) [ViaEntry]
   (s/optional-key :source) (s/enum :record-ctor-scan)})

(s/defschema CallsiteResolverContext
  "Context map passed to `:callsite-resolver-fn` by
   `generate-annotations-from-analysis`.
   `:rule` is the full rulebase production — `s/Any` because productions are
   large open maps; the keys of interest are :name :ns-name :lhs :rhs :props
   (relates to `clara.server.graph.api` production schemas, which add
   serialization concerns and stay at that layer).  `:arg-form` is `s/Any`
   because it is unevaluated source data of arbitrary shape."
  {:rule s/Any                               ; full production (:name, :ns-name, :lhs, :rhs, …)
   :ns-name-sym s/Symbol                     ; ns where the callsite was found
   :direction (s/enum :insert :retract)
   :boundary-fn s/Symbol                     ; e.g. `clara.rules/insert!`
   :arg-form s/Any                           ; the unresolved argument form
   :source-str s/Str                         ; `pr-str` of `:arg-form`
   :filename s/Str
   (s/optional-key :fact-type) s/Any         ; present only for alias-discovered callsites;
                                             ;   s/Any: keywords, fq class-name symbols, strings
   (s/optional-key :fact-type-spec)          ; present only for alias-discovered callsites
   {s/Keyword s/Any}})

(s/defschema ConstructorTypeResolverContext
  "Context map passed to a fact-constructor's `:type-resolver-fn`.
   `:rule` is the full rulebase production — `s/Any` because productions are
   large open maps; the keys of interest are :name :ns-name :lhs :rhs :props
   (relates to `clara.server.graph.api` production schemas, which add
   serialization concerns and stay at that layer).  `:arg-form` is `s/Any`
   because it is unevaluated source data of arbitrary shape."
  {:constructor-sym s/Symbol
   :arg-form s/Any
   :ns-name-sym s/Symbol
   :filename s/Str
   :direction (s/enum :insert :retract)
   :rule s/Any
   (s/optional-key :via) ViaChain})

(s/defschema CallsiteEntry
  "One captured boundary/constructor callsite, internal form: fact-type tokens
   are still arbitrary Clojure values (keywords, fq class-name symbols) — the
   serialize pass stringifies them for the API.  Relates to
   `clara.server.graph.api/DynamicCallsiteEntry` (its serialized counterpart).
   `:resolved-types` is `[s/Any]` because the analyzer is type-agnostic by
   design: token shape is the caller resolver's decision."
  {:source-str s/Str
   :ns-name-sym s/Symbol
   :filename s/Str
   :status (s/enum :none :partial :full)
   (s/optional-key :resolved-types) [s/Any]
   (s/optional-key :constructor-sym) s/Symbol
   (s/optional-key :via) ViaChain
   (s/optional-key :fact-type) s/Any         ; alias context only — s/Any: keywords,
                                             ;   fq class-name symbols, strings
   (s/optional-key :fact-type-spec) {s/Keyword s/Any}})

(s/defschema CallsiteResolution
  "Result of one callsite-resolution pass — the boundary chain
   (`resolve-boundary-callsites`) or the constructor-of-interest chain
   (`resolve-constructor-callsites`).  `:owned-arg-idxs` is present only on
   the constructor pass result: the `TracedArg` `:idx`s it accounted for,
   which the boundary pass must skip so no insert is reported twice."
  {:callsites [CallsiteEntry]
   :resolved-types #{s/Any}                  ; type-agnostic tokens — see CallsiteEntry
   :resolution (s/maybe (s/enum :full :partial :none))
   (s/optional-key :owned-arg-idxs) #{s/Int}
   (s/optional-key :resolved-arg-idxs) #{s/Int}})
