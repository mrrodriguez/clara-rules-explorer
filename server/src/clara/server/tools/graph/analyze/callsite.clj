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
            [clojure.tools.logging :as log]
            [clara.server.tools.graph.analyze.utils :as u]
            [clara.server.tools.graph.analyze.kondo :as kondo]
            [clara.server.tools.graph.analyze.ctor :as ctor]
            [clara.server.tools.graph.analyze.index :as index]))

(def ^:private max-resolution-depth 8)

;; ---------------------------------------------------------------------------
;; Step 3: locals tracing
;; ---------------------------------------------------------------------------

(defn- usage->span
  "The `{:filename … :start [row col] :end [row col]}` source span of a kondo
   usage (`:end` exclusive)."
  [{:keys [filename row col end-row end-col]}]
  {:filename filename
   :start [row col]
   :end [end-row end-col]})

(defn- find-local-binding
  "Finds the kondo `:locals` binding for a local symbol used at a known source
   span: the `:local-usages` entry matching the symbol within `span`, linked to
   its binding via kondo's per-ns-run `:id`.  Lookups are constrained to the
   span's `:filename` because ids restart per analyzed namespace and collide in
   the merged analysis.

   Uses the precomputed `:local-usages-by-name` / `:locals-by-id` indexes (see
   `index/AnalysisIndex`) — never scans the
   full analysis vectors."
  [{:keys [local-usages-by-name locals-by-id]} {:keys [filename start end]} arg-sym]
  (let [[row col] start
        [end-row end-col] end
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
  "Follows local-symbol arguments to their binding init forms, depth-capped.
   `span` is the source span to resolve the current local usage in — the
   boundary-call span on the first hop, then each traced binding's init span.
   Returns the deepest form reached: the init form of the innermost traced
   local, or `arg-form` itself when it is not a traceable local."
  [arg-form {:keys [get-lines] :as ctx}
   ns-sym span depth]
  (if (and (symbol? arg-form) (< depth max-resolution-depth))
    (if-let [binding (find-local-binding ctx span arg-form)]
      (if-let [init-form (kondo/read-init-form get-lines ns-sym binding)]
        (if-let [init-span (and (symbol? init-form)
                                (kondo/init-form-span get-lines ns-sym binding))]
          (recur init-form ctx ns-sym init-span (inc depth))
          init-form)
        arg-form)
      arg-form)
    arg-form))

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

(defn- ->callsite-resolver-context
  "Builds the context map handed to `:callsite-resolver-fn` (see
  `clara.server.tools.graph.analyze/->annotations-from-rule-source-analysis`). Alias context keys
  (`:fact-type`/`:fact-type-spec`) are present only for callsites discovered through a var-alias
  chain (`:fact-type-spec-fn`)."
  [{:keys [rule direction usage alias-context]} arg-form]
  (cond-> {:rule rule
           :ns-name-sym (:from usage)
           :direction direction
           :boundary-fn (symbol (str (:to usage)) (str (:name usage)))
           :arg-form arg-form
           :source-str (pr-str arg-form)
           :filename (:filename usage)}
    alias-context (merge (select-keys alias-context [:fact-type :fact-type-spec]))))

(defn- invoke-callsite-resolver
  "Invokes the caller's `:callsite-resolver-fn`; exceptions are contained
   (logged, treated as unresolved).  Returns the resolver's `:resolved-types`
   sequence, or nil."
  [resolver-fn call-ctx]
  (when resolver-fn
    (try
      (some-> (resolver-fn call-ctx) :resolved-types seq)
      (catch Throwable t
        (log/errorf t "clara.server.tools.graph.analyze: :callsite-resolver-fn threw: %s"
                    (ex-message t))
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
         (invoke-callsite-resolver callsite-resolver-fn (->callsite-resolver-context ctx traced))
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

   Returns a vector of `TracedArg` entries."
  [usages {:keys [get-lines alias-context-for] :as ctx}]
  (into []
        (comp (mapcat (fn [usage]
                        (let [alias-ctx (when alias-context-for
                                          (alias-context-for usage))]
                          (map (fn [arg]
                                 {:usage usage
                                  :arg arg
                                  :alias-context alias-ctx
                                  :traced (trace-local-form arg ctx (:from usage) (usage->span usage) 0)})
                               (or (kondo/read-boundary-args usage get-lines) '())))))
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

(defn shortest-call-path
  "BFS from start to end in the call graph.
   Returns [start … end] or nil when unreachable.
   Neighbors are sorted by str for deterministic traversal.

   Shared by the constructor pass (for `:boundary-to-constructor-path`) and by `memoized-rule-to-boundary-path`
   (for the rule-side `:rule-to-boundary-path`); in both cases the result is a *shortest*
   path through a var-level call graph, not the observed runtime path."
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

(defn memoized-rule-to-boundary-path
  "Returns a memoized fn from `boundary-in-var` to a vector of `ViaEntry`
   entries (or nil), computing the shortest call-graph path from `rule-var` to
   `boundary-in-var`, both ends inclusive, as `{:var-name-sym …}` entries.  nil
   when the two vars are equal (a boundary call in the rule's own RHS) or
   unreachable.

   Memoized per (rule-var, boundary-in-var) pair — a rule's callsites cluster
   in a few boundary-holding vars, and the path is the same for every callsite
   they hold."
  [graph rule-var]
  (memoize
   (fn [boundary-in-var]
     (when (and rule-var (not= rule-var boundary-in-var))
       (when-let [path (shortest-call-path graph rule-var boundary-in-var)]
         (mapv (fn [v] {:var-name-sym v}) path))))))

(defn- ->boundary-via
  "The boundary-side `:via` keys shared by both resolution passes: the boundary
   fn and the var the boundary call is written in, plus `:rule-to-boundary-path` when that
   var is not the rule itself (see `memoized-rule-to-boundary-path`)."
  [boundary-fn-sym boundary-in-var rule-to-boundary-path-for]
  (let [rule-to-boundary-path (when rule-to-boundary-path-for (rule-to-boundary-path-for boundary-in-var))]
    (cond-> {:boundary-var-name-sym boundary-fn-sym
             :boundary-in-var boundary-in-var}
      rule-to-boundary-path (assoc :rule-to-boundary-path rule-to-boundary-path))))

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
   ;; :fact-type is s/Any: keywords, fq class-name symbols, strings are all
   ;; legitimate fact types; :fact-type-spec is an open caller-defined map
   :alias-context (s/maybe {(s/optional-key :fact-type) s/Any
                            (s/optional-key :fact-type-spec) {s/Keyword s/Any}})})

(s/defschema ViaEntry
  "A single entry in a `:rule-to-boundary-path` / `:boundary-to-constructor-path`
   chain (internal symbol form; `clara.server.graph.api/ViaEntry` is its
   serialized string counterpart)."
  {:var-name-sym s/Symbol})

(s/defschema ViaChain
  "Provenance chain from a boundary fn to a constructor callsite (internal
   symbol form; `clara.server.graph.api/ViaChain` is its serialized string
   counterpart).  `:boundary-in-var` is the var the boundary call is written
   in; `:rule-to-boundary-path` is the rule→`:boundary-in-var` chain (omitted when the two
   are the same var).  `:rule-to-boundary-path` and `:boundary-to-constructor-path` are shortest paths through
   a var-level call graph, not observed runtime call paths.  `:source` marks
   heuristic provenance — `:record-ctor-scan` when the callsite comes from the
   subtree-wide record-ctor scan fallback rather than a traced call chain;
   heuristic entries have no `:boundary-to-constructor-path`."
  {(s/optional-key :boundary-var-name-sym) s/Symbol
   (s/optional-key :boundary-in-var) s/Symbol
   (s/optional-key :boundary-to-constructor-path) [ViaEntry]
   (s/optional-key :rule-to-boundary-path) [ViaEntry]
   (s/optional-key :source) (s/enum :record-ctor-scan)})

(s/defschema CallsiteResolverContext
  "Context map passed to `:callsite-resolver-fn` by
   `clara.server.tools.graph.analyze/->annotations-from-rule-source-analysis`.
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
   which the boundary pass must skip so no insert is reported twice.
   `:dropped-ctor-provenance` (constructor pass only) maps the `:idx` of an
   argument whose constructor the type-resolver could not type to that
   dropped entry's `:constructor-sym`/`:boundary-to-constructor-path`; the boundary pass merges
   them into its entry for the argument (ambiguously-owned args are omitted)."
  {:callsites [CallsiteEntry]
   :resolved-types #{s/Any}                  ; type-agnostic tokens — see CallsiteEntry
   :resolution (s/maybe (s/enum :full :partial :none))
   (s/optional-key :owned-arg-idxs) #{s/Int}
   (s/optional-key :resolved-arg-idxs) #{s/Int}
   (s/optional-key :dropped-ctor-provenance)
   {s/Int {(s/optional-key :constructor-sym) s/Symbol
           (s/optional-key :boundary-to-constructor-path) [ViaEntry]}}})

(s/defschema BoundaryCallsiteCtx
  "The `ctx` map for `resolve-boundary-callsites`: an `index/AnalysisIndex`
   plus the per-rule resolution keys.  `:rule` may be nil when the consuming
   var is not a rulebase production; `:rule-var` is the fq rule var symbol
   (head of every `:rule-to-boundary-path`).  `:dropped-ctor-provenance` maps
   a boundary argument's `:idx` to the `:constructor-sym` /
   `:boundary-to-constructor-path` of the constructor the ctor pass owned but
   could not type, so the argument's entry still carries that provenance; nil
   when no ctor pass ran.  `:rule-to-boundary-path-for` and
   `:callsite-resolver-fn` are fns, so the `s/=>` fn schemas are
   documentation (prismatic FnSchema does not validate fn-ness).

   Open map — the caller's ctx carries more than the declared keys
   (`:get-lines`, `:read-ctor-form`, `:alias-context-for`, …)."
  (merge index/AnalysisIndex
         {:direction (s/enum :insert :retract)
          :rule (s/maybe s/Any)                            ; full production (:name, :ns-name, :lhs, :rhs, …)
          :rule-var s/Symbol
          :rule-to-boundary-path-for (s/=> (s/maybe [ViaEntry]) s/Symbol)
          :callsite-resolver-fn (s/maybe (s/=> s/Any s/Any))
          :dropped-ctor-provenance (s/maybe
                                    {s/Int {(s/optional-key :constructor-sym) s/Symbol
                                            (s/optional-key :boundary-to-constructor-path) [ViaEntry]}})
          s/Any s/Any}))

(s/defschema ConstructorCallsiteCtx
  "The `ctx` map for `resolve-constructor-callsites`: an `index/AnalysisIndex`
   plus the per-rule keys that pass consumes — `:direction`, `:rule`,
   `:rule-to-boundary-path-for`, and the source-reading closures
   `:get-lines` / `:read-ctor-form`.  `:rule` may be nil when the consuming
   var is not a rulebase production.  `:rule-to-boundary-path-for`,
   `:get-lines` and `:read-ctor-form` are fns, so the `s/=>` fn schemas are
   documentation (prismatic FnSchema does not validate fn-ness).

   Open map — the caller's ctx carries more than the declared keys."
  (merge index/AnalysisIndex
         {:direction (s/enum :insert :retract)
          :rule (s/maybe s/Any)                           ; full production (:name, :ns-name, :lhs, :rhs, …)
          :rule-to-boundary-path-for (s/=> (s/maybe [ViaEntry]) s/Symbol)
          :get-lines (s/=> s/Any s/Any)
          :read-ctor-form (s/=> s/Any s/Any)
          s/Any s/Any}))

(s/defn resolve-boundary-callsites
  :- CallsiteResolution
  "Resolves boundary-call arguments via the ctor chain and the optional
   `:callsite-resolver-fn`.

   `traced-args` — entries from `trace-boundary-args`, already filtered to those
   the constructor path did not own.

   `ctx` — a `BoundaryCallsiteCtx`.

   Every entry gains a boundary-side `:via` (`:boundary-var-name-sym`,
   `:boundary-in-var`, and `:rule-to-boundary-path` when the boundary call is not in the
   rule's own RHS).  When the constructor pass dropped an unresolvable
   constructor for this argument, its `:constructor-sym` and `:boundary-to-constructor-path` are
   merged in — so an unresolvable call to `->fact` is still described as such
   rather than emitted with no provenance.

   Returns a `CallsiteResolution` including `:resolved-arg-idxs` — the `:idx`
   of every traced argument that resolved to at least one type (used by
   `analyze/extract-insert-types` for per-inserter-var heuristic fallback
   attribution)."
  [traced-args :- [TracedArg]
   {:keys [dropped-ctor-provenance] :as ctx} :- BoundaryCallsiteCtx]
  (let [pairs (into []
                    (map (fn [{:keys [idx usage arg traced alias-context]}]
                           (let [ctx' (assoc ctx :usage usage :alias-context alias-context)
                                 tokens (resolve-traced-arg traced ctx' (:from usage))
                                 dropped (get dropped-ctor-provenance idx)
                                 entry (cond-> {:source-str (pr-str arg)
                                                :ns-name-sym (:from usage)
                                                :filename (:filename usage)
                                                :status (if (empty? tokens) :none :full)
                                                :via (->boundary-via (u/var-usage-callee usage)
                                                                     (u/var-usage-caller usage)
                                                                     (:rule-to-boundary-path-for ctx))}
                                         (seq tokens)
                                         (assoc :resolved-types (vec (sort-by str tokens)))

                                         alias-context
                                         (merge (select-keys alias-context [:fact-type :fact-type-spec]))

                                         (:constructor-sym dropped)
                                         (assoc :constructor-sym (:constructor-sym dropped))

                                         (:boundary-to-constructor-path dropped)
                                         (assoc-in [:via :boundary-to-constructor-path] (:boundary-to-constructor-path dropped)))]
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

(defn- position<=
  "Source-position ordering: `[row col]` before-or-equal `[row col]`."
  [r1 c1 r2 c2]
  (or (< r1 r2) (and (= r1 r2) (<= c1 c2))))

(defn- span-contains-pos?
  "True when 1-indexed `[row col]` lies in `span` (`{:filename … :start
   [row col] :end [row col]}`, `:end` exclusive)."
  [{:keys [filename start end]} ufilename row col]
  (boolean
   (and (= filename ufilename)
        start end row col
        (position<= (first start) (second start) row col)
        (let [[er ec] end]
          (or (< row er) (and (= row er) (< col ec)))))))

(defn- first-usage-index-at-or-after
  "Binary search: first index in `[row col]`-sorted `sorted-usages` at or
   after `[srow scol]`."
  [sorted-usages srow scol]
  (loop [lo 0 hi (count sorted-usages)]
    (if (>= lo hi)
      lo
      (let [mid (quot (+ lo hi) 2)
            u (nth sorted-usages mid)
            r (or (:row u) 0)
            c (or (:col u) 0)]
        (if (or (> r srow) (and (= r srow) (>= c scol)))
          (recur lo mid)
          (recur (inc mid) hi))))))

(defn- usages-in-span
  "Entries of `[row col]`-sorted `sorted-usages` (one file) whose start
   position lies in `span`. Nil-safe on malformed spans."
  [sorted-usages {:keys [start end] :as _span}]
  (if (or (nil? start) (nil? end))
    []
    (let [[sr sc] start
          [er ec] end
          starts-before-end? (fn [u]
                               (let [r (or (:row u) 0)
                                     c (or (:col u) 0)]
                                 (or (< r er) (and (= r er) (< c ec)))))]
      (->> sorted-usages
           (drop (first-usage-index-at-or-after sorted-usages sr sc))
           (take-while starts-before-end?)
           (into [])))))

(defn- arg-span-set
  "The ephemeral span set for one boundary-call argument: the boundary usage
   span plus the init spans of every local transitively reachable from usages
   inside it (see docs/planning/locals-expand-ana-plan.md).

   Returns `{:spans […] :var-syms #{…}}`: spans are
   `{:filename … :start [row col] :end [row col]}` (`:end` exclusive);
   `:var-syms` are the fq callee symbols of var-usages starting in any span.
   Kondo usage→binding linkage drives the fixpoint, so shadowing and inner
   binders resolve without a special-form walker; `max-resolution-depth`
   bounds cycles. Nothing here is persisted — `:source-str` keeps the
   original arg."
  [{:keys [usage] :as _traced-arg}
   {:keys [var-usages-by-filename local-usages-by-filename locals-by-id get-lines] :as _ctx}]
  (let [filename (:filename usage)
        ns-sym (:from usage)
        seed (usage->span usage)]
    (if (or (nil? filename) (nil? (:row usage)) (nil? (:col usage)))
      {:spans [seed] :var-syms #{}}
      (let [final-spans
            (loop [spans [seed] scanned 0 seen-ids #{} depth 0]
              (if (or (>= depth max-resolution-depth) (>= scanned (count spans)))
                spans
                (let [fresh-keys
                      (into []
                            (comp (mapcat (fn [span]
                                            (usages-in-span (get local-usages-by-filename
                                                                 (:filename span) [])
                                                            span)))
                                  (map (juxt :filename :id))
                                  (remove (fn [[f id]] (or (nil? f) (nil? id))))
                                  (remove seen-ids))
                            (subvec spans scanned))
                      seen-ids (into seen-ids fresh-keys)
                      new-spans (into []
                                      (comp (map (fn [[f id]] (get locals-by-id [f id])))
                                            (remove nil?)
                                            (map (fn [binding]
                                                   (kondo/init-form-span get-lines ns-sym binding)))
                                            (remove nil?))
                                      fresh-keys)]
                  (recur (into spans new-spans)
                         (count spans)
                         seen-ids
                         (inc depth)))))]
        {:spans final-spans
         :var-syms (into #{}
                         (comp (mapcat (fn [span]
                                         (usages-in-span (get var-usages-by-filename
                                                              (:filename span) [])
                                                         span)))
                               (map (fn [u] (u/fq-sym (:to u) (:name u)))))
                         final-spans)}))))

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
     :rule-to-boundary-path-for - memoized (fn [boundary-in-var] -> [ViaEntry …] | nil)
     :resolver-fn - the `:type-resolver-fn` of the `:fact-constructors` spec
       that matched this callsite"
  [{:keys [ctor-usage ctor-form boundary-usage call-path direction rule
           rule-to-boundary-path-for resolver-fn]}]
  (let [boundary-fn-sym (u/fq-sym (:to boundary-usage) (:name boundary-usage))
        ctor-sym (u/fq-sym (:to ctor-usage) (:name ctor-usage))
        via (when (seq call-path)
              (assoc (->boundary-via boundary-fn-sym (first call-path) rule-to-boundary-path-for)
                     :boundary-to-constructor-path (conj (mapv (fn [v] {:var-name-sym v}) call-path)
                                                         {:var-name-sym ctor-sym})))
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
                     (log/errorf t "clara.server.tools.graph.analyze: :fact-constructors :type-resolver-fn threw: %s"
                                 (ex-message t))
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

(defn- ctor-call-in-span-set?
  "The constructor call is written inside the argument's span set (see
   `arg-span-set`): inline — `(insert! (->fact :t m))` — reached through a
   local binding — `(let [f (->fact :t m)] (insert! f))` — or in a
   transitively-reached init. Position identity, never form value: two
   identical forms at different positions never cross-attribute."
  [spans ctor-usage]
  (boolean
   (and (seq spans)
        (some #(span-contains-pos? % (:filename ctor-usage)
                                   (:row ctor-usage) (:col ctor-usage))
              spans))))

(defn- intermediate-call-in-span-set?
  "A var-usage inside the argument's span set names a link on `intermediates`
   — the call-graph path from the inserter to the constructor's containing
   var: `(insert! (my-middle-fn args))`, including a helper called in a
   reached local init."
  [intermediates var-syms]
  (boolean (some intermediates var-syms)))

(defn- arg-reaches-ctor?
  "True when a traced boundary argument demonstrably reaches the given
   constructor usage: the constructor call sits inside the argument's span
   set (position identity), or a var-usage inside that span set names a link
   on `intermediates`."
  [{:keys [traced-arg ctor-usage intermediates span-set]}]
  (let [{:keys [alias-context]} traced-arg
        {:keys [spans var-syms]} span-set]
    (and (not alias-context)       ; alias callsites are never auto-resolved
         (or (ctor-call-in-span-set? spans ctor-usage)
             (intermediate-call-in-span-set? intermediates var-syms)))))

(defn- find-owning-boundary-arg
  "The boundary argument a constructor call was reached *through*, or nil.

   Two ways an argument reaches a constructor, both decided from the
   argument's ephemeral span set (`arg-span-set`):

     1. **The constructor is written inside the span set.** Position identity,
        not form value: `(insert! (->fact :t m))`,
        `(let [f (->fact :t m)] (insert! f))`, or a ctor in a
        transitively-reached local init.
     2. **A call inside the span set names a link on `intermediates`** — the
        call-graph path from the inserter to the constructor's containing var.

   `intermediates` deliberately excludes the constructor symbol itself — only
   route 1 may match the constructor, and by *usage identity*, not by name.
   Otherwise a rule with two separate `->fact` calls would attribute both to
   whichever boundary call happened to contain one of them.

   Takes a map: `:ctor-usage`, `:intermediates`, `:traced-args`,
   `:span-set-by-idx` (see `arg-span-set`).

   nil means no boundary argument demonstrably reaches this constructor: the
   constructor call is not on an insert path out of this rule."
  [{:keys [ctor-usage intermediates traced-args span-set-by-idx]}]
  (some #(when (arg-reaches-ctor? {:traced-arg %
                                   :ctor-usage ctor-usage
                                   :intermediates intermediates
                                   :span-set (get span-set-by-idx
                                                  (:idx %)
                                                  {:spans [] :var-syms #{}})})
           %)
        traced-args))

(defn- resolve-ctor-usage-for-inserter
  "Attempts to resolve a single constructor-of-interest match against the
   traced boundary arguments of a single inserter var.  `ctor-match` is an
   `index/CtorUsageMatch` — {:usage … :type-resolver-fn …}.

   Returns
     {:owned   {:idx i :entry callsite}} — resolved; the boundary pass skips i
     {:dropped {:idx i :provenance p}}  — owned but unresolvable; the boundary
                                           pass merges p's keys into its entry
     nil                                — unreachable or unowned (not an insert)

   `:provenance` is the `:constructor-sym` + `:via :boundary-to-constructor-path` of the dropped
   constructor entry, so the boundary pass can emit the provenance it would
   otherwise throw away."
  [{:keys [ctor-match inserter-var graph read-ctor-form
           cfg-base candidates span-set-by-idx]}]
  (let [{:keys [usage type-resolver-fn]} ctor-match
        ctor-usage usage
        path (ctor-call-path graph inserter-var ctor-usage)
        ctor-form (read-ctor-form ctor-usage)
        owner (find-owning-boundary-arg {:ctor-usage ctor-usage
                                         :intermediates (set (rest path))
                                         :traced-args candidates
                                         :span-set-by-idx span-set-by-idx})
        {:keys [status constructor-sym] :as entry}
        (when owner
          (resolve-ctor-callsite
           (assoc cfg-base
                  :ctor-usage ctor-usage
                  :ctor-form ctor-form
                  :call-path path
                  :resolver-fn type-resolver-fn
                  :boundary-usage (:usage owner))))
        ctor-path (:boundary-to-constructor-path (:via entry))]
    (when owner
      (if (not= :none status)
        {:owned {:idx (:idx owner) :entry entry}}
        {:dropped {:idx (:idx owner)
                   :provenance (cond-> {:constructor-sym constructor-sym}
                                 ctor-path
                                 (assoc :boundary-to-constructor-path ctor-path))}}))))

(defn- resolve-ctor-matches-for-inserter
  "Resolves every constructor-of-interest match for one inserter var against
   the boundary arguments written in that var.  Returns the per-match results
   (see `resolve-ctor-usage-for-inserter` for the outcome shapes).

   `env` — the shared resolution context (`:args-by-caller`, `:graph`,
   `:get-lines`, `:read-ctor-form`, `:cfg-base`, plus the
   `:var-usages-by-filename`, `:local-usages-by-filename` and `:locals-by-id`
   expansion indexes) plus `:inserter-var` and `:ctor-matches`."
  [{:keys [inserter-var ctor-matches] :as env}]
  (let [candidates (->> (get (:args-by-caller env) inserter-var)
                        (sort-by (juxt #(:row (:usage %)) #(:col (:usage %)))))
        span-set-by-idx (into {}
                              (map (juxt :idx #(arg-span-set % env)))
                              candidates)]
    (keep #(resolve-ctor-usage-for-inserter
            (assoc env
                   :ctor-match %
                   :candidates candidates
                   :span-set-by-idx span-set-by-idx))
          ctor-matches)))

(defn- unambiguous-dropped-ctor-provenance
  "`:idx` -> dropped-constructor provenance for the constructor pass result.
   An `:idx` is kept only when exactly one constructor claimed it — an
   ambiguously-owned argument's provenance is omitted rather than reported
   (see `CallsiteResolution`)."
  [dropped]
  (into {}
        (keep (fn [[idx ds]]
                (when (= 1 (count ds))
                  [idx (:provenance (first ds))])))
        (group-by :idx dropped)))

(defn- ->ctor-pass-resolution
  "Shapes the constructor pass's per-match `results` into its
   `CallsiteResolution`: owned results become callsite entries
   (`:callsites`, `:owned-arg-idxs`, `:resolved-types`); dropped results
   become `:dropped-ctor-provenance`."
  [results]
  (let [owned (keep :owned results)
        pairs (mapv (juxt :idx :entry) owned)
        entries (mapv second pairs)
        dropped (keep :dropped results)]
    {:callsites entries
     :resolved-types (into #{} (mapcat :resolved-types) entries)
     :owned-arg-idxs (into #{} (map first) pairs)
     :dropped-ctor-provenance (unambiguous-dropped-ctor-provenance dropped)
     :resolution (resolution-status entries)}))

(s/defn resolve-constructor-callsites
  :- CallsiteResolution
  "Resolves constructor-of-interest callsites reached from a rule's boundary calls.

   `traced-args` — entries from `trace-boundary-args` for this rule var.
   `constructor-ctr-map` — an `index/CtorCallsiteMap`
     ({inserter-var -> [CtorUsageMatch …]} from `index/->analysis-index`),
     scoped to this rule var.
   `ctx` — a `ConstructorCallsiteCtx`.

   A constructor is emitted only when some boundary argument is shown to reach
   it (see `find-owning-boundary-arg`) *and* the resolver returns a type.  A constructor call
   that no insert flows through is not an insert — dropping it is what keeps a
   `(let [f (->fact :x)] (insert! (other)))` from claiming `:x`.  A constructor
   the resolver cannot type is left to the boundary path rather than reported
   twice; its provenance is returned under `:dropped-ctor-provenance` so the
   boundary entry can carry `:constructor-sym`/`:boundary-to-constructor-path`.

   Returns a `CallsiteResolution` including `:owned-arg-idxs` — the `:idx` of
   every boundary argument a constructor accounted for.  Those must not also go
   through `resolve-boundary-callsites`, or the same insert would be reported
   twice (see `analyze/extract-insert-types`)."
  [traced-args :- [TracedArg]
   constructor-ctr-map :- index/CtorCallsiteMap
   {:keys [get-lines read-ctor-form graph direction rule
           rule-to-boundary-path-for
           var-usages-by-filename local-usages-by-filename
           locals-by-id]} :- ConstructorCallsiteCtx]
  (let [args-by-caller (group-by #(u/var-usage-caller (:usage %)) traced-args)
        cfg-base {:direction direction
                  :rule rule
                  :rule-to-boundary-path-for rule-to-boundary-path-for}
        resolver-env {:args-by-caller args-by-caller
                      :graph graph
                      :get-lines get-lines
                      :read-ctor-form read-ctor-form
                      :cfg-base cfg-base
                      :var-usages-by-filename var-usages-by-filename
                      :local-usages-by-filename local-usages-by-filename
                      :locals-by-id locals-by-id}
        results (into []
                      (mapcat (fn [[inserter-var ctor-matches]]
                                (resolve-ctor-matches-for-inserter
                                 (assoc resolver-env
                                        :inserter-var inserter-var
                                        :ctor-matches ctor-matches))))
                      constructor-ctr-map)]
    (->ctor-pass-resolution results)))

