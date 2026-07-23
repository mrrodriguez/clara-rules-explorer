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
            [clojure.string :as str]))

(def ^:private max-resolution-depth 8)

;; ---------------------------------------------------------------------------
;; Source reading at kondo positions
;; ---------------------------------------------------------------------------

(defn- source-text-at
  "Extracts source text from a position range. Returns nil on any error."
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
  "Reads the argument forms of the boundary call (insert!/retract!/…) described
   by a kondo var-usage. Returns a (possibly empty) sequence of forms."
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
  "Reads the init form following a :locals binding symbol in the source —
   the text from just after the binding symbol's end position onward, parsed
   as a single form. Returns nil on any error."
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
  "True if the given fn-name string looks like a record constructor (->X or map->X)."
  [fn-name]
  (or (str/starts-with? fn-name "map->")
      (str/starts-with? fn-name "->")))

(defn resolvable-fact-class [sym]
  (try
    (when (class? (resolve sym))
      sym)
    (catch Throwable _ nil)))

(defn resolve-record-type
  "Resolves class-sym in the given live namespace to a fact-type token:
   a Class (imported or fq class name) ⇒ its fq class-name symbol; a ->X/map->X
   record ctor var ⇒ the fq class-name symbol of the record, but only when the
   derived class actually loads (rejects constructor-named helper fns such as
   a custom ->fact builder). Returns nil when unresolvable."
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
  "Finds the kondo :locals binding for a local symbol used as a boundary-call
   argument: the :local-usages entry matching the arg symbol within the
   boundary usage's position span, linked to its binding via kondo's per-ns-run
   :id. Both lookups are constrained to the boundary usage's :filename because
   ids restart per analyzed namespace and collide in the merged analysis."
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
  "Normalizes a resolved fact-type token: Class objects become their fq
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
  "Builds the context map handed to :callsite-resolver-fn (see
   analyze/generate-annotations-from-analysis). Alias context keys
   (:fact-type/:fact-type-spec) are present only for callsites discovered
   through a var-alias chain (:fact-type-spec-fn)."
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
  "Invokes the caller's :callsite-resolver-fn; exceptions are contained
   (logged, treated as unresolved). Returns the resolver's resolved-types
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

(defn- resolve-arg
  "Runs the full resolution chain for one boundary-call argument form.
   Returns a set of resolved fact-type tokens (empty when unresolved).
   Alias-discovered callsites (:alias-context in ctx) bypass the ctor chain —
   they are never automatically resolved — and go straight to the resolver."
  [arg-form {:keys [callsite-resolver-fn alias-context] :as ctx} live-ns-sym]
  (let [traced (trace-arg-form arg-form ctx 0)
        resolved
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

(defn resolve-boundary-callsites
  "Resolves every argument form of every boundary usage via the ctor chain and
   the optional :callsite-resolver-fn.

   `ctx` keys:
     :analysis              - merged clj-kondo analysis (for :locals tracing)
     :get-source            - (fn [ns-sym filename] -> source-str)
     :direction             - :insert | :retract
     :rule                  - the full production map of the consuming rule (may be nil)
     :callsite-resolver-fn  - optional caller escape hatch
     :alias-context-for     - optional (fn [usage] -> alias context or nil);
                              when it returns a context for a boundary usage,
                              the callsite bypasses the ctor chain and carries
                              :fact-type/:fact-type-spec (see alias-usage-map)

   Returns {:callsites [{:source-str :ns-name-sym :filename :status :resolved-types?
                         :fact-type? :fact-type-spec?}]
            :resolved-types #{token …}
            :resolution :full | :partial | :none (nil when no callsites)}"
  [usages {:keys [get-source alias-context-for] :as ctx}]
  (let [entries
        (into []
              (comp (mapcat (fn [usage]
                              (map (fn [arg] [usage arg])
                                   (or (read-boundary-args usage get-source) '()))))
                    (map (fn [[usage arg]]
                           (let [alias-ctx (when alias-context-for
                                             (alias-context-for usage))
                                 tokens (resolve-arg arg
                                                     (assoc ctx :usage usage :alias-context alias-ctx)
                                                     (:from usage))]
                             (cond-> {:source-str (pr-str arg)
                                      :ns-name-sym (:from usage)
                                      :filename (:filename usage)
                                      :status (cond (empty? tokens) :unresolved
                                                    (= 1 (count tokens)) :resolved
                                                    :else :resolved-multi)}
                               (seq tokens)
                               (assoc :resolved-types (vec (sort-by str tokens)))
                               alias-ctx
                               (merge (select-keys alias-ctx [:fact-type :fact-type-spec]))))))
                    (distinct))
              usages)
        resolved-types (into #{} (mapcat :resolved-types) entries)]
    {:callsites entries
     :resolved-types resolved-types
     :resolution (cond
                   (empty? entries) nil
                   (every? #(= :unresolved (:status %)) entries) :none
                   (some #(= :unresolved (:status %)) entries) :partial
                   :else :full)}))

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
  "Invokes the caller's :fact-type-spec-fn on a fact type; exceptions are
   contained (logged, treated as no spec)."
  [fact-type-spec-fn fact-type]
  (try
    (fact-type-spec-fn fact-type)
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "clara.server.tools.graph.analyze: :fact-type-spec-fn threw: "
                      (ex-message t))))
      nil)))

(defn alias-usage-map
  "Builds the var-alias linkage for the :fact-type-spec-fn mechanism.

   For each rule production in `rule-vars`: scan the :lhs for bound fact
   variables (lhs-var-bindings), and when (fact-type-spec-fn fact-type)
   returns a spec with :aliases-var pointing at a fully-qualified var AND the
   binding is used in the rule's RHS, emit a synthetic var-usage
   {:from rule-ns :from-var rule-name :to (namespace v) :name (name v)}
   tagged :via-var-alias. Merged into the analysis before graph building,
   this lets the existing reachability explore the aliased var's whole call
   chain for boundary fns. (If the var is invisible to kondo — macro-emitted,
   unhooked — its chain is empty and nothing is found; that is the caller
   :config-dir situation.)

   `productions-by-name` maps fq rule symbol -> full production.

   Returns {rule-fq-sym {:usages [synthetic var-usages …]
                         :contexts [{:fact-type t :fact-type-spec spec
                                     :var v :root fq-var-sym} …]}}.
   The :contexts entries carry the alias context attached to callsites
   discovered through each chain (:fact-type/:fact-type-spec keys; :root is
   the aliased var's fq symbol, the reachability seed)."
  [productions-by-name rule-vars analysis fact-type-spec-fn]
  (into {}
        (keep (fn [rule-fq-sym]
                (when-let [production (get productions-by-name rule-fq-sym)]
                  (let [rule-ns (symbol (namespace rule-fq-sym))
                        rule-local (symbol (name rule-fq-sym))
                        pairs
                        (into []
                              (comp (mapcat lhs-var-bindings)
                                    (keep (fn [{:keys [binding fact-type]}]
                                            (when-let [spec (apply-spec-fn fact-type-spec-fn fact-type)]
                                              (when-let [v (:aliases-var spec)]
                                                (when (and (symbol? v)
                                                           (namespace v)
                                                           (rhs-uses-binding? analysis rule-ns rule-local binding))
                                                  {:fact-type fact-type
                                                   :fact-type-spec spec
                                                   :var v}))))))
                              [(:lhs production)])
                        pairs (distinct pairs)]
                    (when (seq pairs)
                      [rule-fq-sym
                       {:usages (into []
                                      (map (fn [{:keys [fact-type fact-type-spec] aliased-var :var}]
                                             {:from rule-ns
                                              :from-var rule-local
                                              :to (symbol (namespace aliased-var))
                                              :name (symbol (name aliased-var))
                                              :via-var-alias {:fact-type fact-type
                                                              :fact-type-spec fact-type-spec
                                                              :var aliased-var}}))
                                      pairs)
                        :contexts (into []
                                        (map (fn [{:keys [fact-type fact-type-spec] aliased-var :var}]
                                               {:fact-type fact-type
                                                :fact-type-spec fact-type-spec
                                                :var aliased-var
                                                :root (symbol (namespace aliased-var) (name aliased-var))}))
                                        pairs)}])))))
        rule-vars))
