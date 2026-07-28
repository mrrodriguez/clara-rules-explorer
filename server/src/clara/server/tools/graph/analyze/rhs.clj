(ns clara.server.tools.graph.analyze.rhs
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
   4. Otherwise → the caller's :callsite-resolver-fn, then :unresolved.

   The automatic chain resolves only what we know the instance type of —
   constructors. Everything else deliberately defers to the caller's
   resolver fn: with-meta maps, helper calls, the var-as-fact pattern, and
   literals. A literal arg is source text read as data (an unevaluated
   template when it contains rule bindings), and classifying it would mean
   running the session's caller-configured fact-type-fn on fabricated data —
   the resolver receives the read object as :arg-form and can do so
   knowingly.

   All Clojure syntax understanding comes from clj-kondo; this namespace only
   reads single forms at kondo-provided positions and resolves symbols via the
   live namespace.

   Var-alias chains (:fact-type-spec-fn): the caller may declare that a fact
   type aliases a var (the var-as-fact pattern — a fact IS a function var,
   bound on the LHS and invoked in the RHS). alias-usage-map emits synthetic
   var-usages linking consuming rules to their aliased vars so the existing
   reachability explores the var's call chain; callsites discovered through
   that chain bypass the ctor chain above and are recorded :unresolved with
   :fact-type/:fact-type-spec context attached (then handed to the resolver)."
  (:require [clara.rules.schema :as schema]
            [schema.core :as s]
            [clojure.string :as str]))

(def ^:private max-resolution-depth 8)

;; ---------------------------------------------------------------------------
;; Source reading at kondo positions
;; ---------------------------------------------------------------------------

(defn- source-text-at
  "Extracts source text from a position range.  Returns nil on any error."
  [source-str row col end-row end-col]
  (try
    (let [lines (str/split-lines source-str)]
      (when (and row col end-row end-col
                 (<= 1 row (count lines))
                 (<= 1 end-row (count lines))
                 (<= row end-row))
        (let [relevant-lines (subvec (vec lines) (dec row) end-row)]
          (if (= (count relevant-lines) 1)
            (let [line (first relevant-lines)]
              (when (and (<= 0 (dec col) (count line))
                         (<= 0 (dec end-col) (count line))
                         (<= col end-col))
                (subs line (dec col) (dec end-col))))
            (let [first-line (first relevant-lines)
                  last-line (last relevant-lines)
                  middle-lines (subvec relevant-lines 1 (dec (count relevant-lines)))
                  trimmed-first (if (<= 0 (dec col) (count first-line))
                                  (subs first-line (dec col))
                                  first-line)
                  trimmed-last (if (<= 0 (dec end-col) (count last-line))
                                 (subs last-line 0 (dec end-col))
                                 last-line)]
              (str/join "\n" (concat [trimmed-first] middle-lines [trimmed-last])))))))
    (catch Exception _
      nil)))

(defn- read-boundary-args
  "Reads the argument forms of the boundary call (`insert!`/`retract!`/…) described
   by a kondo `:var-usage`.  Returns a (possibly empty) sequence of forms."
  [{:keys [row end-row col end-col from filename] :as _usage} get-source]
  (let [source (get-source from filename)
        call-str (source-text-at source
                                 row
                                 col
                                 end-row
                                 end-col)]
    (if call-str
      (try
        (rest (read-string call-str))
        (catch Exception _ nil))
      nil)))

(defn- read-init-form
  "Reads the init form following a `:locals` binding symbol in the source —
   the text from just after the binding symbol's end position onward, parsed
   as a single form.  Returns nil on any error."
  [source {:keys [row end-col]}]
  (try
    (when (and source row end-col)
      (let [lines (str/split-lines source)
            line (nth lines (dec row))
            tail (str/join "\n" (cons (subs line (dec end-col)) (drop row lines)))]
        (read-string tail)))
    (catch Throwable _
      nil)))

;; ---------------------------------------------------------------------------
;; Constructor resolution (shared with the static inference path)
;; ---------------------------------------------------------------------------

(defn constructor-fn-name?
  "True if the given fn-name string looks like a record constructor (`->X` or `map->X`)."
  [fn-name]
  (or (str/starts-with? fn-name "map->")
      (str/starts-with? fn-name "->")))

(defn resolvable-fact-class [sym]
  (try
    (when (class? (resolve sym))
      sym)
    (catch Throwable _ nil)))

(defn resolve-record-type
  "Resolves `class-sym` in the given live namespace to a fact-type token:
   a Class (imported or fq class name) ⇒ its fq class-name symbol; a `->X`/`map->X`
   record ctor var ⇒ the fq class-name symbol of the record, but only when the
   derived class actually loads (rejects constructor-named helper fns such as
   a custom `->fact` builder).  Returns nil when unresolvable."
  [ns-sym class-sym]
  (try
    (if-let [resolved (ns-resolve (find-ns ns-sym) class-sym)]
      (cond
        (class? resolved)
        (symbol (.getName ^Class resolved))

        (var? resolved)
        (let [v-meta (meta resolved)
              ns-str (-> v-meta :ns ns-name name)
              fn-name (-> v-meta :name name)
              class-name (cond
                           (str/starts-with? fn-name "->") (subs fn-name 2)
                           (str/starts-with? fn-name "map->") (subs fn-name 5))]
          (when class-name
            (let [fq-sym (-> ns-str
                             (str/replace "-" "_")
                             (str  "." class-name)
                             symbol)]
              (resolvable-fact-class fq-sym))))

        :else nil)
      nil)
    (catch Exception _ nil)))

(defn- resolve-ctor-form
  "a seq arg whose head is a record ctor (->X/map->X)
   or a Java ctor (X., new X, X/new), resolved against the live caller ns.
   Returns a set of one fq class-name token, or nil."
  [caller-ns-sym arg-form]
  (let [head (first arg-form)]
    (when (symbol? head)
      (or
       ;; Step 1: record constructor (->X …) / (map->X …)
       (when (constructor-fn-name? (name head))
         (some-> (resolve-record-type caller-ns-sym head) hash-set))
       ;; Step 2: Java constructors
       (cond
         ;; (new X …)
         (contains? '#{new clojure.core/new} head)
         (when (symbol? (second arg-form))
           (some-> (resolve-record-type caller-ns-sym (second arg-form)) hash-set))

         ;; (X. …)
         (str/ends-with? (name head) ".")
         (let [class-name (subs (name head) 0 (dec (count (name head))))
               class-sym (if (namespace head)
                           (symbol (namespace head) class-name)
                           (symbol class-name))]
           (some-> (resolve-record-type caller-ns-sym class-sym) hash-set))

         ;; (X/new …) — namespace part is the class name
         (and (namespace head) (= "new" (name head)))
         (some-> (resolve-record-type caller-ns-sym (symbol (namespace head))) hash-set)

         :else nil)))))

;; ---------------------------------------------------------------------------
;; Step 3: locals tracing
;; ---------------------------------------------------------------------------

(defn- find-local-binding
  "Finds the kondo `:locals` binding for a local symbol used as a boundary-call
   argument: the `:local-usages` entry matching the arg symbol within the
   boundary usage's position span, linked to its binding via kondo's per-ns-run
   `:id`.  Both lookups are constrained to the boundary usage's `:filename`
   because ids restart per analyzed namespace and collide in the merged analysis."
  [analysis usage arg-sym]
  (let [{:keys [row col end-row end-col filename]} usage
        within-span? (fn [u]
                       (and (= filename (:filename u))
                            (<= row (:row u) end-row)
                            (or (not= row (:row u)) (<= col (:col u)))
                            (or (not= end-row (:row u)) (< (:col u) end-col))))
        local-usage (->> (:local-usages analysis)
                         (filter #(and (= arg-sym (:name %))
                                       (within-span? %)))
                         first)]
    (when-let [id (:id local-usage)]
      (->> (:locals analysis)
           (filter #(and (= id (:id %))
                         (= filename (:filename %))))
           first))))

(defn- trace-arg-form
  "Follows local-symbol arguments to their binding init forms.
   Returns the deepest form reached — the init form of the innermost traced
   local — or arg-form itself when it is not a traceable local. Depth-capped."
  [arg-form {:keys [analysis get-source usage] :as ctx} depth]
  (if (and (symbol? arg-form) (< depth max-resolution-depth))
    (if-let [binding (find-local-binding analysis usage arg-form)]
      (if-let [init-form (read-init-form (get-source (:from usage) (:filename usage))
                                         binding)]
        (trace-arg-form init-form ctx (inc depth))
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
           (resolve-ctor-form live-ns-sym traced))
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

   Returns `[{:idx … :usage … :arg … :traced … :alias-context …}]`."
  [usages {:keys [get-source alias-context-for] :as ctx}]
  (into []
        (comp (mapcat (fn [usage]
                        (let [alias-ctx (when alias-context-for
                                          (alias-context-for usage))]
                          (map (fn [arg]
                                 (let [ctx' (assoc ctx :usage usage :alias-context alias-ctx)]
                                   {:usage usage
                                    :arg arg
                                    :alias-context alias-ctx
                                    :traced (trace-arg-form arg ctx' 0)}))
                               (or (read-boundary-args usage get-source) '())))))
              (map-indexed (fn [i ta] (assoc ta :idx i))))
        usages))

(defn resolution-status
  "Classifies a sequence of callsite entries: nil (empty), :none (all
   :unresolved), :partial (some :unresolved), or :full (all resolved)."
  [callsites]
  (cond
    (empty? callsites) nil
    (every? #(= :unresolved (:status %)) callsites) :none
    (some #(= :unresolved (:status %)) callsites) :partial
    :else :full))

(defn resolve-boundary-callsites
  "Resolves boundary-call arguments via the ctor chain and the optional
   `:callsite-resolver-fn`.

   `traced-args` — entries from `trace-boundary-args`, already filtered to those
   the constructor path did not own.

   `ctx` keys:
     `:analysis`              - merged `clj-kondo` analysis (for `:locals` tracing)
     `:direction`             - `:insert` | `:retract`
     `:rule`                  - the full production map of the consuming rule (may be nil)
     `:callsite-resolver-fn`  - optional caller escape hatch

   Returns `{:callsites [...] :resolved-types #{…} :resolution :full|:partial|:none}`
   (`:resolution` is nil when there are no callsites)."
  [traced-args ctx]
  (let [entries
        (into []
              (comp (map (fn [{:keys [usage arg traced alias-context]}]
                           (let [ctx' (assoc ctx :usage usage :alias-context alias-context)
                                 tokens (resolve-traced-arg traced ctx' (:from usage))]
                             (cond-> {:source-str (pr-str arg)
                                      :ns-name-sym (:from usage)
                                      :filename (:filename usage)
                                      :status (cond (empty? tokens) :unresolved
                                                    (= 1 (count tokens)) :resolved
                                                    :else :resolved-multi)}
                               (seq tokens)
                               (assoc :resolved-types (vec (sort-by str tokens)))
                               alias-context
                               (merge (select-keys alias-context [:fact-type :fact-type-spec]))))))
                    (distinct))
              traced-args)
        resolved-types (into #{} (mapcat :resolved-types) entries)]
    {:callsites entries
     :resolved-types resolved-types
     :resolution (resolution-status entries)}))

;; ---------------------------------------------------------------------------
;; :fact-type-spec-fn — var-alias chains (caller-guided var-as-fact discovery)
;; ---------------------------------------------------------------------------

(defn- subtree-fact-types
  "All fact types in a condition subtree (fact conditions, accumulators, and
   and/or/not/exists compounds; test conditions contribute none)."
  [condition]
  (case (schema/condition-type condition)
    :fact [(:type condition)]
    :accumulator (subtree-fact-types (:from condition))
    (:and :or :not :exists) (mapcat subtree-fact-types (rest condition))
    :test []
    []))

(defn lhs-var-bindings
  "Scans a production's :lhs (constrained DSL data) for bound fact variables:
   :fact-binding on fact conditions and :result-binding on accumulator
   conditions (whose :from subtree supplies the fact types — a result binding
   binds a collection, but the spec lookup keys on the accumulated fact type
   the same way). Returns [{:binding ?sym :fact-type t} …] with :binding as a
   symbol (production bindings are keywords like :?t)."
  [lhs]
  (letfn [(walk [condition]
            (case (schema/condition-type condition)
              :fact (if-let [b (:fact-binding condition)]
                      [{:binding (symbol (name b)) :fact-type (:type condition)}]
                      [])
              :accumulator (if-let [b (:result-binding condition)]
                             (into []
                                   (map (fn [t] {:binding (symbol (name b)) :fact-type t}))
                                   (distinct (subtree-fact-types (:from condition))))
                             [])
              (:and :or :not :exists) (mapcat walk (rest condition))
              :test []
              []))]
    (into [] (mapcat walk) lhs)))

(defn- rhs-uses-binding?
  "True when ?sym occurs as a free symbol in the rule's RHS. Kondo records
   free ?syms as var-usages (:to :clj-kondo/unknown-namespace) attributed to
   the rule's snippet var; snippets contain only the RHS form, so any such
   usage is an RHS usage."
  [analysis rule-ns rule-local-name binding-sym]
  (boolean
   (some (fn [u]
           (and (= binding-sym (:name u))
                (= rule-ns (:from u))
                (= rule-local-name (:from-var u))))
         (:var-usages analysis))))

(defn- apply-spec-fn
  "Invokes the caller's `:fact-type-spec-fn` on a fact type; exceptions are
   contained (logged, treated as no spec)."
  [fact-type-spec-fn fact-type]
  (try
    (fact-type-spec-fn fact-type)
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "clara.server.tools.graph.analyze: :fact-type-spec-fn threw: "
                      (ex-message t))))
      nil)))

;; ---------------------------------------------------------------------------
;; Schemas for `alias-usage-map`
;; ---------------------------------------------------------------------------

(s/defschema VarAliasSyntheticUsage
  "A synthetic `:var-usage` linking a rule to its aliased var."
  {:from s/Symbol
   :from-var s/Symbol
   :to s/Symbol
   :name s/Symbol
   :via-var-alias {:fact-type s/Keyword
                   :fact-type-spec {s/Keyword s/Any}
                   :var s/Symbol}})

(s/defschema VarAliasContext
  "Per-chain alias context attached to callsites discovered through a
   var-alias chain."
  {:fact-type s/Keyword
   :fact-type-spec {s/Keyword s/Any}
   :var s/Symbol
   :root s/Symbol})

(s/defschema AliasUsageMapEntry
  "Per-rule value in the `alias-usage-map` result."
  {:usages [VarAliasSyntheticUsage]
   :contexts [VarAliasContext]})

(s/defschema AliasUsageMap
  "Return type of `alias-usage-map`."
  {s/Symbol AliasUsageMapEntry})

;; ---------------------------------------------------------------------------
;; `alias-usage-map` helpers
;; ---------------------------------------------------------------------------

(defn- build-alias-pair
  "Returns nil or a single `{:fact-type :fact-type-spec :var}` entry when
   `fact-type` maps through `fact-type-spec-fn` to an alias and the binding
   is used in the rule's RHS."
  [fact-type-spec-fn analysis rule-ns rule-local {:keys [binding fact-type]}]
  (when-let [spec (apply-spec-fn fact-type-spec-fn fact-type)]
    (when-let [v (:aliases-var spec)]
      (when (and (symbol? v)
                 (namespace v)
                 (rhs-uses-binding? analysis rule-ns rule-local binding))
        {:fact-type fact-type
         :fact-type-spec spec
         :var v}))))

(defn- build-alias-pairs
  "Scans the production's `:lhs` for `lhs-var-bindings` and returns the
   deduplicated vector of alias pair entries for rules whose bound fact types
   map through `fact-type-spec-fn` to an alias."
  [production analysis fact-type-spec-fn rule-ns rule-local]
  (let [pairs (into []
                    (comp (mapcat lhs-var-bindings)
                          (keep (partial build-alias-pair
                                         fact-type-spec-fn analysis rule-ns rule-local)))
                    [(:lhs production)])]
    (distinct pairs)))

(defn- build-synthetic-usage
  "Builds a synthetic `:var-usage` map for a single alias pair."
  [rule-ns rule-local {:keys [fact-type fact-type-spec] aliased-var :var}]
  {:from rule-ns
   :from-var rule-local
   :to (symbol (namespace aliased-var))
   :name (symbol (name aliased-var))
   :via-var-alias {:fact-type fact-type
                   :fact-type-spec fact-type-spec
                   :var aliased-var}})

(defn- build-alias-context
  "Builds a context entry for a single alias pair — carried by callsites
   discovered through the chain (see `:fact-type`, `:fact-type-spec` keys)."
  [{:keys [fact-type fact-type-spec] aliased-var :var}]
  {:fact-type fact-type
   :fact-type-spec fact-type-spec
   :var aliased-var
   :root (symbol (namespace aliased-var) (name aliased-var))})

(defn alias-usage-map
  "Builds the var-alias linkage for the `:fact-type-spec-fn` mechanism.

   For each rule production in `rule-vars`: scans the `:lhs` for bound fact
   variables (`lhs-var-bindings`), and when `(fact-type-spec-fn fact-type)`
   returns a spec with `:aliases-var` pointing at a fully-qualified var AND
   the binding is used in the rule's RHS, emits a synthetic `:var-usage`
   tagged `:via-var-alias`.  Merged into the analysis before graph building,
   this lets the existing reachability explore the aliased var's whole call
   chain for boundary fns.  (If the var is invisible to `clj-kondo` —
   macro-emitted, unhooked — its chain is empty and nothing is found; that
   is the caller `:config-dir` situation.)

   `productions-by-name` maps fq rule symbol -> full production.

   Returns an `AliasUsageMap` — `{rule-fq-sym {:usages [...] :contexts [...]}}`."
  [productions-by-name rule-vars analysis fact-type-spec-fn]
  (->> rule-vars
       (keep (fn [rule-fq-sym]
               (when-let [production (get productions-by-name rule-fq-sym)]
                 (let [rule-ns (symbol (namespace rule-fq-sym))
                       rule-local (symbol (name rule-fq-sym))
                       pairs (build-alias-pairs production analysis
                                                fact-type-spec-fn rule-ns rule-local)]
                   (when (seq pairs)
                     [rule-fq-sym
                      {:usages (mapv (partial build-synthetic-usage rule-ns rule-local) pairs)
                       :contexts (mapv build-alias-context pairs)}])))))
       (into {})))

;; ---------------------------------------------------------------------------
;; Fact-constructor callsite resolution
;; ---------------------------------------------------------------------------

(defn- fq-sym
  "Returns the fully-qualified symbol [ns name] as a single symbol."
  [ns name]
  (symbol (str ns) (str name)))

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

(defn- read-ctor-form
  "The constructor call form as written, read from source at the usage's span."
  [ctor-usage get-source]
  (let [source (get-source (:from ctor-usage) (:filename ctor-usage))]
    (when-let [call-str (source-text-at source
                                        (:row ctor-usage) (:col ctor-usage)
                                        (:end-row ctor-usage) (:end-col ctor-usage))]
      (try (read-string call-str) (catch Exception _ nil)))))

(defn- resolve-ctor-callsite
  "Resolves a single constructor-of-interest callsite.
   Returns a callsite entry map.

   `cfg` is a map with:
     :ctor-usage  - the kondo :var-usage for the constructor call
     :ctor-form   - its call form, from `read-ctor-form`
     :boundary-usage - the boundary call this constructor was reached from
     :call-path - [inserter-var … containing-var] from `ctor-call-path`
     :direction - :insert or :retract
     :rule - the rule production
     :resolver-fn - the :fact-constructor-type-resolver-fn"
  [{:keys [ctor-usage ctor-form boundary-usage call-path direction rule resolver-fn]}]
  (let [boundary-fn-sym (fq-sym (:to boundary-usage) (:name boundary-usage))
        ctor-sym (fq-sym (:to ctor-usage) (:name ctor-usage))
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
                        (format "clara.server.tools.graph.analyze: :fact-constructor-type-resolver-fn threw: %s"
                                (ex-message t))))
                     nil))
        tokens (into #{} (map normalize-token) (or resolved '()))]
    (cond-> {:source-str (pr-str arg-form)
             :ns-name-sym (:from ctor-usage)
             :filename (:filename ctor-usage)
             :constructor-sym ctor-sym
             :status (cond (empty? tokens) :unresolved
                           (= 1 (count tokens)) :resolved
                           :else :resolved-multi)}
      (seq tokens) (assoc :resolved-types (vec (sort-by str tokens)))
      via (assoc :via via))))

(defn- ctor-call-path
  "Call-graph path `[inserter-var … containing-var]` for a constructor usage —
   how the insert chain gets from the boundary call's caller to the var the
   constructor is written in.  `[inserter-var]` when the constructor is written
   in the inserter itself; nil when unreachable."
  [graph inserter-var ctor-usage]
  (let [ctor-caller (fq-sym (:from ctor-usage) (:from-var ctor-usage))]
    (if (= inserter-var ctor-caller)
      [ctor-caller]
      (shortest-call-path graph inserter-var ctor-caller))))

(defn- arg-reaches-ctor?
  "True when a traced boundary argument demonstrably reaches the given
   constructor usage — see `owning-arg` for the three ownership routes."
  [{:keys [traced-arg ctor-usage ctor-form intermediates sibling-usages]}]
  (let [{:keys [usage traced alias-context]} traced-arg]
    (and (not alias-context)       ; alias callsites are never auto-resolved
         (or (usage-encloses? usage ctor-usage)
             (and ctor-form (= traced ctor-form))
             (some (fn [u]
                     (and (usage-encloses? usage u)
                          (contains? intermediates (fq-sym (:to u) (:name u)))))
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
        names nothing, so nothing else can join it back.

   `intermediates` deliberately excludes the constructor symbol itself — only
   rule 1 may match the constructor, and by *usage identity*, not by name.
   Otherwise a rule with two separate `->fact` calls would attribute both to
   whichever boundary call happened to contain one of them.

   `sibling-usages` are the var-usages written in the same var as the boundary
   calls — the candidates for \"a call written inside this boundary call\".

   nil means no boundary argument demonstrably reaches this constructor: the
   constructor call is not on an insert path out of this rule."
  [ctor-usage ctor-form intermediates traced-args sibling-usages]
  (some #(when (arg-reaches-ctor? {:traced-arg %
                                   :ctor-usage ctor-usage
                                   :ctor-form ctor-form
                                   :intermediates intermediates
                                   :sibling-usages sibling-usages})
           %)
        traced-args))

(defn- resolve-ctor-usage-for-inserter
  "Attempts to resolve a single constructor-of-interest usage against the
   traced boundary arguments of a single inserter var.  Returns `[idx entry]`
   when a boundary argument is shown to reach the constructor *and* the
   type-resolver returns a type; nil when the constructor is unreachable,
   unowned, or unresolved (the argument then falls through to the boundary
   path instead of being reported twice)."
  [{:keys [ctor-usage inserter-var graph get-source cfg-base candidates siblings]}]
  (let [path (ctor-call-path graph inserter-var ctor-usage)
        ctor-form (read-ctor-form ctor-usage get-source)
        owner (owning-arg ctor-usage ctor-form (set (rest path))
                          candidates siblings)]
    (when owner
      (let [entry (resolve-ctor-callsite
                   (assoc cfg-base
                          :ctor-usage ctor-usage
                          :ctor-form ctor-form
                          :call-path path
                          :boundary-usage (:usage owner)))]
        (when (not= :unresolved (:status entry))
          [(:idx owner) entry])))))

(defn resolve-constructor-callsites
  "Resolves constructor-of-interest callsites reached from a rule's boundary calls.

   `traced-args` — entries from `trace-boundary-args` for this rule var.
   `constructor-ctr-map` — {inserter-var -> [ctor-usage …]} from
     `build-constructor-callsite-map`, scoped to this rule var.
   `ctx` — must contain :get-source, :graph, :direction, :rule,
     :usages-by-container, :fact-constructor-type-resolver-fn.

   A constructor is emitted only when some boundary argument is shown to reach
   it (see `owning-arg`) *and* the resolver returns a type.  A constructor call
   that no insert flows through is not an insert — dropping it is what keeps a
   `(let [f (->fact :x)] (insert! (other)))` from claiming `:x`.  A constructor
   the resolver cannot type is left to the boundary path rather than reported
   twice.

   Returns `{:callsites […] :resolved-types #{…} :resolution …}` (the shape
   `resolve-boundary-callsites` returns) plus `:owned-arg-idxs` — the `:idx` of
   every boundary argument a constructor accounted for.  Those must not also go
   through `resolve-boundary-callsites`, or the same insert would be reported
   twice (see `analyze/extract-insert-types`)."
  [traced-args constructor-ctr-map {:keys [get-source graph direction rule
                                           usages-by-container
                                           fact-constructor-type-resolver-fn]}]
  (let [args-by-caller (group-by #(fq-sym (:from (:usage %)) (:from-var (:usage %)))
                                 traced-args)
        cfg-base {:direction direction
                  :rule rule
                  :resolver-fn fact-constructor-type-resolver-fn}
        pairs (into []
                    (mapcat
                     (fn [[inserter-var ctor-usages]]
                       (let [candidates (sort-by (juxt #(:row (:usage %)) #(:col (:usage %)))
                                                 (get args-by-caller inserter-var))
                             siblings (get usages-by-container inserter-var)]
                         (keep #(resolve-ctor-usage-for-inserter
                                 {:ctor-usage %
                                  :inserter-var inserter-var
                                  :graph graph
                                  :get-source get-source
                                  :cfg-base cfg-base
                                  :candidates candidates
                                  :siblings siblings})
                               ctor-usages)))
                     constructor-ctr-map))
        entries (mapv second pairs)
        resolved-types (into #{} (mapcat :resolved-types) entries)]
    {:callsites entries
     :resolved-types resolved-types
     :owned-arg-idxs (into #{} (map first) pairs)
     :resolution (resolution-status entries)}))
;; Schemas
;; ---------------------------------------------------------------------------

(s/defschema ViaEntry
  {:var-name-sym s/Symbol})

(s/defschema ViaChain
  {:boundary-var-name-sym s/Symbol
   :callstack [ViaEntry]})

(s/defschema ConstructorTypeResolverContext
  "Context map passed to `:fact-constructor-type-resolver-fn`."
  {:constructor-sym s/Symbol
   :arg-form s/Any
   :ns-name-sym s/Symbol
   :filename s/Str
   :direction (s/enum :insert :retract)
   :rule s/Any
   (s/optional-key :via) ViaChain})
