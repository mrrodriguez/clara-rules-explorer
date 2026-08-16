(ns clara.server.tools.graph.analyze
  "Static analysis tools for Clara rules using clj-kondo.
   Traces rule RHS call graphs to auto-detect insert/retract fact types.

   ## Rule-source analysis

   The Clara session is the source of truth for rules: rule names, LHS, RHS,
   and props come from the rulebase productions.
   clj-kondo performs all Clojure syntax analysis on the RHS structures.

   ## Custom kondo config

   The bundled config (default) is the verbatim clara-rules clj-kondo import
   (defrule/defquery/defhierarchy hooks, synced from the clara-rules dep) —
   the common case callers would supply themselves. Callers can supply their
   own clj-kondo config via the `:config-dir` option, which completely
   replaces the bundled config: typically the clara-rules import plus hooks
   for their own var-emitting macros.

   Example:
     ;; Copy bundled config as a starting point
     cp -r resources/clara/server/tools/graph/kondo-config my-kondo-config
     ;; Edit my-kondo-config/config.edn to register your own hooks
     ;; Pass it to the rule-source analysis:
     (->rule-source-analysis
       {:session-or-rulebase session
        :config-dir \"my-kondo-config\"})"
  (:require [clj-kondo.core :as kondo-core]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [schema.core :as s]
            [clara.server.tools.graph.analyze.callsite :as callsite]
            [clara.server.tools.graph.analyze.alias :as alias]
            [clara.server.tools.graph.analyze.utils :as u]
            [clara.server.tools.graph.analyze.kondo :as kondo]
            [clara.server.tools.graph.analyze.index :as index]
            [clara.server.tools.graph.analyze.synth :as synth]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.annotations.callsite :as ann.callsite]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.rules.engine :as eng])
  (:import [clara.rules.engine LocalSession]))

;; `ns->resource-base` is defined below but used in private helper fns above
;; it, hence the forward declaration.
(declare ns->resource-base)

;;
;; Private Helpers
;;
;; The call graph, reachability, boundary-fn sets, and every precomputed view
;; over the merged analysis live in `clara.server.tools.graph.analyze.index`
;; (the Index pass); kondo usage helpers live in
;; `clara.server.tools.graph.analyze.utils`.

(defn- normalize-fq-name-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn- extract-required-namespaces [analysis]
  (into []
        (comp (map :to)
              (distinct))
        (:namespace-usages analysis)))

;;
;; Bundled clj-kondo config (verbatim clara-rules import)
;;
;; clj-kondo resolves a linted file's config by walking up from the file's
;; directory (see clj-kondo impl/core `config-dir`). That is cwd-dependent and
;; unusable for a standalone tool run from a foreign working directory or
;; injected into a host JVM (`add-libs`). Instead we ship the clj-kondo import
;; as classpath resources (mirrored by
;; `clara.server.tools.graph.kondo-config-sync`), materialize it into a temp
;; dir once per process, and pass it explicitly as `:config-dir`.

(def ^:private bundled-kondo-config-resource
  "Classpath resource base for the bundled clj-kondo config. Kept in sync with
   `clara.server.tools.graph.kondo-config-sync`'s `default-resources-base`."
  "clara/server/tools/graph/kondo-config")

(defn- materialize-bundled-kondo-config!
  "Copies the bundled clj-kondo config from classpath resources into a fresh
   temp directory and returns its absolute path, suitable for `kondo-core/run!`
   `:config-dir`. Returns nil if the bundled config is not on the classpath.

   Materialization is required because resources may live inside a jar, which
   cannot be handed to `:config-dir` directly."
  []
  (when-let [manifest-res (io/resource (str bundled-kondo-config-resource "/manifest.edn"))]
    (let [manifest (edn/read-string (slurp manifest-res))
          tmp-dir (.toFile (java.nio.file.Files/createTempDirectory
                            "clara-explorer-kondo"
                            (make-array java.nio.file.attribute.FileAttribute 0)))]
      (doseq [rel (:files manifest)]
        (when-let [res (io/resource (str bundled-kondo-config-resource "/" rel))]
          (let [dest (io/file tmp-dir rel)]
            (io/make-parents dest)
            (with-open [in (io/input-stream res)]
              (io/copy in dest)))))
      (.getAbsolutePath tmp-dir))))

(defonce ^:private bundled-kondo-config-dir
  (delay (materialize-bundled-kondo-config!)))

(defn- analyze-source-code [source-code resource-path config-dir]
  (with-in-str source-code
    (kondo-core/run!
     (cond-> {:lint ["-"]
              :lang :clj
              :filename resource-path
              :config {:analysis {:var-definitions true
                                  :var-usages true
                                  :java-class-usages true
                                  :locals true
                                  :local-usages true}}}
       config-dir (assoc :config-dir config-dir)))))

(defn- analyze-ns-source [ns-sym resource-url config-dir]
  (let [source-code (slurp resource-url)
        extension (if (str/ends-with? (str resource-url) ".cljc") ".cljc" ".clj")
        resource-path (format "%s%s" (ns->resource-base ns-sym) extension)]
    (analyze-source-code source-code resource-path config-dir)))

(defn- analyze-ns-string [ns-sym source-code config-dir]
  (let [resource-path (format "%s.clj" (ns->resource-base ns-sym))]
    (analyze-source-code source-code resource-path config-dir)))

(defn- get-rulebase [session-or-rulebase]
  (if (instance? LocalSession session-or-rulebase)
    (-> session-or-rulebase eng/components :rulebase)
    session-or-rulebase))

(defn- heuristic-fallback-callsites
  "Emits scan-derived record-ctor types as heuristic callsites for the given
   fallback inserter vars — direct-inserter vars whose boundary arguments no
   caller-driven resolution path (constructor-of-interest or boundary chain)
   accounted for.  Each (var, type) pair becomes one callsite labeled
   `:via {:source :record-ctor-scan}` so consumers can distinguish the weaker
   evidence; the inserter's boundary fn is included when known from the graph."
  [{:keys [fallback-vars inserter-type-map graph target-fns]}]
  (into []
        (mapcat (fn [v]
                  (let [boundary-fn (->> (get graph v)
                                         (filter target-fns)
                                         (sort-by str)
                                         first)]
                    (map (fn [[t {:keys [usage]}]]
                           (cond-> {:source-str (str (:name usage))
                                    :ns-name-sym (:from usage)
                                    :filename (:filename usage)
                                    :status :full
                                    :resolved-types [t]
                                    :via {:source :record-ctor-scan}}
                             boundary-fn (assoc-in [:via :boundary-var-name-sym] boundary-fn)))
                         (sort-by (comp str key) (get inserter-type-map v))))))
        fallback-vars))

(defn- compute-heuristic-fallback-callsites
  "Computes heuristic record-ctor scan callsites for direct-inserter vars
   whose boundary arguments were handled by neither the
   constructor-of-interest path nor the boundary resolution chain.  Returns a
   (possibly nil) vector of callsites labeled
   `:via {:source :record-ctor-scan}`.

   `:dynamic-type-fallback-resolution :none` disables the fallback entirely;
   `handled-vars` collects callers that had at least one boundary argument
   owned by a constructor-of-interest (`:owned-arg-idxs`) or resolved through
   the boundary chain (`:resolved-arg-idxs`) — a var with any handled
   argument cedes its scan types."
  [{:keys [reachable traced-args owned-arg-idxs resolved-arg-idxs
           inserter-type-map graph target-fns
           dynamic-type-fallback-resolution]}]
  (let [handled-vars (into #{}
                           (comp (filter (fn [{:keys [idx]}]
                                           (or (contains? owned-arg-idxs idx)
                                               (contains? resolved-arg-idxs idx))))
                                 (map (comp u/var-usage-caller :usage)))
                           traced-args)
        fallback-vars (when-not (= :none dynamic-type-fallback-resolution)
                        (->> reachable
                             (filter #(contains? inserter-type-map %))
                             (remove handled-vars)
                             (sort-by str)
                             vec))]
    (when (seq fallback-vars)
      (heuristic-fallback-callsites
       {:fallback-vars fallback-vars
        :inserter-type-map inserter-type-map
        :graph graph
        :target-fns target-fns}))))

(defn- extract-insert-types
  "Determines the fact types a rule inserts or retracts via `target-fns`.

   Caller-driven resolution always runs first and is never displaced: constructor-of-interest
  callsites (when :fact-constructors is supplied) are resolved via their matched
  `:type-resolver-fn`, then every remaining boundary-call argument form goes through the runtime
  resolution chain (`analyze.callsite`) and the optional `:callsite-resolver-fn`.

   The record-ctor scan (`inserter-type-map`) is a *heuristic fallback*, applied per direct-inserter
  var: a var's scan types are credited only when no caller-driven path accounted for any of that
  var's boundary arguments. The scan is name-shape based and subtree-wide — it cannot tell an
  argument expression apart from an unrelated call in the same body — so it must never override
  explicit registration. Fallback types are emitted as callsites labeled `:via {:source
  :record-ctor-scan}`; the `:dynamic-type-fallback-resolution` option controls whether the fallback
  runs at all and the index's type filter scopes what it may credit.

   Boundary usages are found via the `:usages-by-callee` index — the merged `:var-usages` vector is
  never scanned per rule (that scan makes generation quadratic in rules × usages at).

   Returns {:resolved-types #{…} :dynamic-forms …}."
  [reachable target-fns {:keys [inserter-type-map
                                constructor-callsite-map graph
                                boundary-usages-by-caller
                                dynamic-type-fallback-resolution] :as ctx}]
  (let [boundary-usages
        (into []
              (comp (mapcat #(get boundary-usages-by-caller %))
                    (filter (fn [usage]
                              (let [caller (u/var-usage-caller usage)]
                                (and (contains? target-fns (u/var-usage-callee usage))
                                     (not (contains? target-fns caller)))))))
              reachable)]
    (if (empty? boundary-usages)
      {:resolved-types #{}
       :dynamic-forms nil}
      (let [;; Read + locals-trace the boundary arguments once; both paths
            ;; work from this.
            traced-args (callsite/trace-boundary-args boundary-usages ctx)
            ;; Constructor-of-interest resolution runs FIRST — it is the more
            ;; specific mechanism, and it decides which arguments the generic
            ;; path still needs to look at.
            ctor-inserter-vars (when constructor-callsite-map
                                 (->> reachable
                                      (filter #(contains? constructor-callsite-map %))
                                      (sort-by str)
                                      vec))
            ctor-result (when (seq ctor-inserter-vars)
                          (let [scoped-map (select-keys constructor-callsite-map ctor-inserter-vars)]
                            (callsite/resolve-constructor-callsites
                             traced-args
                             scoped-map
                             ctx)))
            ctor-callsites (:callsites ctor-result)
            ;; An argument a constructor accounted for is dropped before
            ;; resolving, so `:callsite-resolver-fn` is never invoked for it
            ;; and the same insert cannot be reported a second time without
            ;; provenance.
            owned (:owned-arg-idxs ctor-result)
            remaining-args (if (seq owned)
                             (into [] (remove (comp owned :idx)) traced-args)
                             traced-args)
            {:keys [callsites resolved-types resolved-arg-idxs]}
            (callsite/resolve-boundary-callsites remaining-args ctx)
            ;; Anything still here is a boundary argument the constructor path
            ;; did NOT own, so it stands on its own — including when it is
            ;; unresolved. Dropping those would erase a real insert we cannot
            ;; explain and inflate `:resolution` to :full.
            all-callsites (into (vec ctor-callsites) callsites)
            all-types (into (or (:resolved-types ctor-result) #{}) resolved-types)
            fallback-callsites (compute-heuristic-fallback-callsites
                                {:reachable reachable
                                 :traced-args traced-args
                                 :owned-arg-idxs owned
                                 :resolved-arg-idxs resolved-arg-idxs
                                 :inserter-type-map inserter-type-map
                                 :graph graph
                                 :target-fns target-fns
                                 :dynamic-type-fallback-resolution dynamic-type-fallback-resolution})
            all-callsites (into all-callsites fallback-callsites)
            all-types (into all-types (mapcat :resolved-types) fallback-callsites)
            ;; callsite identity: the discovering layer derives ids — it has
            ;; the full entries (see annotations.callsite/assign-callsite-ids).
            all-callsites (ann.callsite/assign-callsite-ids all-callsites)
            all-resolution (callsite/resolution-status all-callsites)]
        {:resolved-types all-types
         :dynamic-forms (when (seq all-callsites)
                          (cond-> {:callsites all-callsites}
                            all-resolution
                            (assoc :resolution all-resolution)))}))))

(defn- var-reachability
  "For a given `var-name`, returns a map of:

  * :reachable - set of all transitively reachable vars (from the index's
    shared memoized closure)

  * :is-inserter? - true if reachable set includes an insert fn or a direct inserter

  * :is-retractor? - true if reachable set includes a retract fn or a direct retractor"
  [var-name
   {:keys [reachable-set direct-inserters direct-retractors]}]
  (let [reachable (reachable-set var-name)
        is-inserter? (some #(or (contains? index/insert-fns %)
                                (contains? direct-inserters %))
                           reachable)
        is-retractor? (some #(or (contains? index/retract-fns %)
                                 (contains? direct-retractors %))
                            reachable)]
    {:reachable reachable
     :is-inserter? is-inserter?
     :is-retractor? is-retractor?}))

(defn- alias-context-for-fn
  "Builds the (fn [usage] -> alias-context-or-nil) for one rule's var-alias
  contexts (:fact-type-spec-fn): a boundary usage whose caller is reachable
  from an aliased var was discovered through the alias chain — it bypasses
  the ctor chain and carries :fact-type/:fact-type-spec. Contexts are tried
  in deterministic ((str :fact-type)) order when several alias chains reach
  the same caller.  Reachability comes from the index's shared memoized
  closure.  Returns nil when the rule has no alias contexts."
  [reachable-set contexts]
  (when (seq contexts)
    (let [sorted (sort-by (comp str :fact-type) contexts)]
      (fn [usage]
        (let [caller (u/var-usage-caller usage)]
          (some (fn [{:keys [root] :as ctx}]
                  (when (contains? (reachable-set root) caller)
                    (select-keys ctx [:fact-type :fact-type-spec])))
                sorted))))))

(defn- infer-annotation-for-var
  "Returns an annotation map for the var referred to by the given `var-name` when it inserts or
  retracts fact types. Returns nil when the var has no output side-effects.

  Statically-traceable constructor types and types resolved by the dynamic
  callsite chain (analyze.callsite) are promoted into :clara-rules/insert-types
  and :clara-rules/retract-types; unresolved or partially-resolved callsites
  remain visible under the dynamic-detection keys.

  `ctx` is the `index/AnalysisIndex` plus :callsite-resolver-fn and
  :alias-by-rule (nil when no :fact-type-spec-fn)."
  [var-name {:keys [retractor-type-map productions-by-name alias-by-rule reachable-set] :as ctx}]
  (let [{:keys [is-inserter? is-retractor? reachable]} (var-reachability var-name ctx)]
    (when (or is-inserter? is-retractor?)
      (let [ctx (cond-> (assoc ctx :rule (get productions-by-name var-name))
                  alias-by-rule (assoc :alias-context-for
                                       (alias-context-for-fn reachable-set
                                                             (:contexts (get alias-by-rule var-name)))))
            insert-ctx (assoc ctx :direction :insert)
            retract-ctx (assoc ctx
                               :inserter-type-map retractor-type-map
                               :direction :retract)
            inserts  (when is-inserter? (extract-insert-types reachable index/insert-fns insert-ctx))
            retracts (when is-retractor? (extract-insert-types reachable index/retract-fns retract-ctx))
            insert-types (:resolved-types inserts)
            retract-types (:resolved-types retracts)]
        (cond-> {}
          (seq insert-types)
          (assoc :clara-rules/insert-types
                 (->> insert-types (sort-by str) vec))
          (:dynamic-forms inserts)
          (assoc :clara-rules/dynamic-insert-types-detected (:dynamic-forms inserts))

          (seq retract-types)
          (assoc :clara-rules/retract-types
                 (->> retract-types (sort-by str) vec))
          (:dynamic-forms retracts)
          (assoc :clara-rules/dynamic-retract-types-detected (:dynamic-forms retracts)))))))

;;
;; API
;;

(defn ns->resource-base
  "Maps a namespace symbol to a classpath resource path by converting
   dots to slashes and hyphens to underscores (how Clojure resolves ns→file)."
  [ns-sym]
  (-> (name ns-sym)
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn find-ns-resource
  "Finds the resource URL on the classpath for a Clojure namespace symbol,
   checking both .clj and .cljc extensions sequentially."
  [ns-sym]
  (let [base-path (ns->resource-base ns-sym)]
    (or (io/resource (format "%s.clj" base-path))
        (io/resource (format "%s.cljc" base-path)))))

(defn- get-or-analyze-ns-analysis [ns-sym analyze-fn cache-atom]
  (if-let [cached-entry (when cache-atom (get @cache-atom ns-sym))]
    cached-entry
    (let [res (:analysis (analyze-fn))]
      (when cache-atom
        (swap! cache-atom assoc ns-sym res))
      res)))

;;
;; Source synthesis + prune-and-replace (rule-source analysis)
;;

(defn- rulebase-rules-by-ns
  "Groups rulebase productions that carry an :rhs (rules, not queries) by
   their :ns-name. Returns {ns-sym [production ...]} with productions sorted
   by name for deterministic snippet tag assignment."
  [{:keys [productions] :as _rulebase}]
  (->> productions
       (filter #(and (:rhs %) (:ns-name %)))
       (group-by :ns-name)
       (into {}
             (map (fn [[ns-sym rules]]
                    [ns-sym (vec (sort-by (comp str :name) rules))])))))

(defn- all-rule-fq-names
  "Returns the fq symbols of the session's rules — productions that carry an
   :rhs (queries excluded)."
  [session-or-rulebase]
  (let [{:keys [productions]} (get-rulebase session-or-rulebase)]
    (into []
          (comp (filter :rhs)
                (map :name)
                (map normalize-fq-name-key)
                (distinct))
          productions)))

;; Source synthesis — see `clara.server.tools.graph.analyze.synth`

(defn- prune-and-rename-analysis
  "Prune-and-replace for one rule-owning namespace's analysis:

   1. Drops source-region (row <= offset) :var-definitions and :var-usages
      attributed to known production vars (session rules *and* queries) —
      whatever defrule/defquery constructs the active kondo config produced.
   2. Renames snippet tags to the production names they stand for, so
      downstream graph keys are the true production fq symbols.

   After pruning, the snippet region (row > offset) is the authoritative
   analysis of each rule. Robust to any caller config because it keys on
   production names, not macro identity."
  [analysis {:keys [offset tag->production]} prune-vars]
  (let [source-region? (fn [row] (and row (<= row offset)))
        rename (fn [n] (get tag->production n n))]
    (-> analysis
        (update :var-definitions
                (fn [defs]
                  (into []
                        (comp (remove (fn [d]
                                        (and (prune-vars (u/fq-sym (:ns d) (:name d)))
                                             (source-region? (:row d)))))
                              (map (fn [d] (update d :name rename))))
                        defs)))
        (update :var-usages
                (fn [usages]
                  (into []
                        (comp (remove (fn [u]
                                        (and (:from-var u)
                                             (prune-vars (u/fq-sym (:from u) (:from-var u)))
                                             (source-region? (:row u)))))
                              (map (fn [u] (update u :from-var rename))))
                        usages))))))

(def ^:private default-exclude-ns-prefixes
  #{"clojure."
    "cljs."
    "schema."
    "ham-fisted."
    "futurama."
    "manifold."
    "potemkin."
    "riddley."
    "clj-commons."
    "clj-kondo."
    "cider."
    "nrepl."})

(defn ->rule-source-analysis-from-namespaces
  "Resolves transitive dependencies on the classpath for starting namespaces,
   optionally filtering by include-ns-prefixes and exclude-ns-prefixes,
   runs clj-kondo against them (using the cache), and returns a merged rule-source analysis map.

   When both include-ns-prefixes and exclude-ns-prefixes are provided, a namespace is
   included only if it matches include-ns-prefixes AND does not match exclude-ns-prefixes
   (exclude-ns-prefixes takes precedence).

   Options:
     :starting-namespaces  - coll of namespace symbols to start from (required)
     :include-ns-prefixes   - optional coll of ns prefix strings; when nil,
                              all transitive dependencies are followed (no filtering).
     :exclude-ns-prefixes   - optional coll of ns prefix strings; defaults to
                              default-exclude-ns-prefixes.
     :cache-atom            - optional atom to use as cache; defaults to a fresh
                              atom per call (one analysis run).
     :ns-source-map         - optional internal map of {ns-symbol synthesis-result}
                              (see `synth/synthesize-ns-source`); those namespaces
                              are analyzed from their combined source instead of
                              the classpath, then pruned-and-renamed.
     :prune-vars            - optional set of fq production symbols to prune from
                              synthesized namespaces (see prune-and-rename-analysis).
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              verbatim clara-rules config. Pass nil to disable
                              (falls back to cwd-based `.clj-kondo` discovery).
     :initial-rule-source-analysis - optional rule-source analysis map to seed the merge
                              with; defaults to nil.
     :processed-namespaces  - optional coll of namespace symbols already processed;
                              defaults to nil."
  [{:keys [starting-namespaces include-ns-prefixes exclude-ns-prefixes cache-atom ns-source-map prune-vars config-dir
           initial-rule-source-analysis processed-namespaces]
    :or {cache-atom (atom {})
         config-dir @bundled-kondo-config-dir
         exclude-ns-prefixes default-exclude-ns-prefixes}}]
  (let [ns-matches-prefix? (fn [ns-sym]
                             (let [ns-str (str ns-sym)]
                               (and (if include-ns-prefixes
                                      (some #(str/starts-with? ns-str (str %)) include-ns-prefixes)
                                      true)
                                    (if exclude-ns-prefixes
                                      (not (some #(str/starts-with? ns-str (str %)) exclude-ns-prefixes))
                                      true))))]
    (loop [queue (into (set starting-namespaces)
                       (filter ns-matches-prefix?)
                       (extract-required-namespaces initial-rule-source-analysis))
           processed (set processed-namespaces)
           merged-analysis (or initial-rule-source-analysis {})]
      (if (empty? queue)
        merged-analysis
        (let [ns-sym (first queue)
              remaining (disj queue ns-sym)]
          (if (contains? processed ns-sym)
            (recur remaining processed merged-analysis)
            (let [synth (get ns-source-map ns-sym)
                  resource-url (when-not synth (find-ns-resource ns-sym))]
              (if (or synth resource-url)
                (let [analyze-fn (if synth
                                   #(update (analyze-ns-string ns-sym (:source synth) config-dir)
                                            :analysis prune-and-rename-analysis synth prune-vars)
                                   #(analyze-ns-source ns-sym resource-url config-dir))
                      analysis (get-or-analyze-ns-analysis ns-sym analyze-fn cache-atom)
                      dependencies (extract-required-namespaces analysis)]
                  (recur (into remaining (filter ns-matches-prefix?) dependencies)
                         (conj processed ns-sym)
                         (merge-with into merged-analysis analysis)))
                ;; Resource not found on classpath, skip
                (recur remaining (conj processed ns-sym) merged-analysis)))))))))

(defn- build-source-loader
  "Returns a `(fn [ns-sym filename] -> source-str)` that caches source lookups.
   `combined-sources` maps `ns-sym` -> synthesized source (real source + rule
   snippets, see `synth/synthesize-ns-source`) and takes precedence over
   classpath resources, so callsite positions in synthesized analyses resolve
   against the exact text `clj-kondo` analyzed."
  [combined-sources]
  (let [cache (atom {})]
    (fn [ns-sym filename]
      (let [k (or ns-sym filename)]
        (if-let [cached (get @cache k)]
          cached
          (let [source (try
                         (or (and ns-sym (get combined-sources ns-sym))
                             (if-let [res (and ns-sym (find-ns-resource ns-sym))]
                               (slurp res)
                               (when filename
                                 (let [^java.io.File file (io/as-file filename)]
                                   (when (.exists file)
                                     (slurp file))))))
                         (catch Exception _ nil))]
            (swap! cache assoc k source)
            source))))))

(defn- build-lines-loader
  "Returns a (fn [ns-sym filename] -> lines-vec) that caches str/split-lines
   by the same key as get-source.  Source strings are fetched via get-source
   (already memoized by ns-sym)."
  [get-source]
  (let [cache (atom {})]
    (fn [ns-sym filename]
      (let [k (or ns-sym filename)]
        (or (get @cache k)
            (when-let [source (get-source ns-sym filename)]
              (let [ls (str/split-lines source)]
                (swap! cache assoc k ls)
                ls)))))))

(defn- build-infer-ctx
  "Builds the context map passed to `infer-annotation-for-var`: the shared
   `index/AnalysisIndex` plus the caller-supplied resolution hooks, the
   var-alias linkage, the heuristic-fallback mode, and source-reading helpers.

   `build-lines-loader` creates a memoized `get-lines` from `get-source`;
   `read-ctor-form` is a closure over `get-lines` for reading constructor
   forms by kondo usage position."
  [{:keys [index callsite-resolver-fn alias-by-rule fallback-mode get-source]}]
  (let [get-lines (build-lines-loader get-source)
        read-ctor-form (fn [ctor-usage] (kondo/read-ctor-form ctor-usage get-lines))]
    (assoc index
           :get-lines get-lines
           :read-ctor-form read-ctor-form
           :callsite-resolver-fn callsite-resolver-fn
           :alias-by-rule alias-by-rule
           :dynamic-type-fallback-resolution fallback-mode)))

(s/defschema FactConstructorSpec
  "One constructor of interest for `:fact-constructors`.
   `:match-fn` — (fn [fq-var-sym] -> truthy/nil) — decides whether a
   fully-qualified var symbol is a constructor of interest.
   `:type-resolver-fn` — (fn [callsite/ConstructorTypeResolverContext] -> nil
   or {:resolved-types [token …]}) — extracts fact types from its callsite.
   (The `s/=>` fn schemas are documentation: prismatic FnSchema does not
   validate fn-ness; `s/validate` enforces the required keys only.)"
  {:match-fn (s/=> s/Any s/Symbol)
   :type-resolver-fn (s/=> s/Any callsite/ConstructorTypeResolverContext)})

(defn- type-name-str
  "Canonical name string for a fact type: Class objects become their binary
   name; symbols, keywords, and anything else stringify directly."
  [t]
  (if (class? t)
    (.getName ^Class t)
    (str t)))

(defn- build-fallback-type-filter
  "Builds the (fn [{:keys [type]}] -> bool) for `:rulebase-fact-types-only`
   mode: a scanned record-ctor type is admitted when it — or any of its
   ancestors via the session's `:ancestors-fn` — appears as a fact type on
   the LHS of some rule/query production.  LHS matching is hierarchical (an
   inserted subtype satisfies an LHS on its supertype), so a direct set
   lookup is not enough.

   The ancestors-fn is the session's own, recovered from the rulebase's
   `:get-alphas-fn` metadata (attached by `clara.rules.compiler` — the same
   wrapped fn runtime insertion uses); falls back to `clojure.core/ancestors`
   when absent (e.g. a hand-built rulebase).  Called on the loaded Class,
   since scan tokens are fq class-name symbols."
  [rulebase]
  (let [lhs-type-names (into #{}
                             (map type-name-str)
                             (alias/rulebase-fact-types rulebase))
        ancestors-fn (core/extract-ancestors-fn rulebase)
        allowed? (memoize
                  (fn [type-sym]
                    (or (contains? lhs-type-names (str type-sym))
                        (try
                          (boolean
                           (some lhs-type-names
                                 (map type-name-str
                                      (ancestors-fn (Class/forName (str type-sym))))))
                          (catch Throwable _ false)))))]
    (fn [{:keys [type]}]
      (allowed? type))))

(s/defschema DynamicTypeFallbackResolution
  "Modes for the heuristic record-ctor scan fallback (see
   `extract-insert-types`): `:none` disables it entirely;
   `:rulebase-fact-types-only` (the default) credits only scanned types that
   — directly or via the session's `:ancestors-fn` — appear on some
   rule/query production's LHS; `:all-resolvable-fact-types` credits any
   resolvable record-ctor type (pre-fix recall)."
  (s/enum :none :rulebase-fact-types-only :all-resolvable-fact-types))

(s/defschema RuleSourceAnnotationsOptions
  "Options for `->annotations-from-rule-source-analysis` — validated with
   `s/validate` at function entry (edge-only validation; nothing in the hot
   path is schema-checked).  `:rule-source-analysis` and `:session-or-rulebase`
   are `s/Any`: the rule-source analysis is a large open clj-kondo map and the
   session is a Clara LocalSession or rulebase — neither has a useful closed
   shape here."
  {:rule-source-analysis s/Any                ; merged clj-kondo rule-source analysis (required)
   :session-or-rulebase s/Any                 ; Clara session or rulebase (required)
   (s/optional-key :dynamic-type-fallback-resolution) DynamicTypeFallbackResolution
   (s/optional-key :rules-filter) [s/Symbol]
   (s/optional-key :callsite-resolver-fn)     ; (fn [callsite/CallsiteResolverContext] -> nil or
                                               ;   {:resolved-types [token …]})
   (s/=> s/Any callsite/CallsiteResolverContext)
   (s/optional-key :fact-type-spec-fn)        ; (fn [fact-type] -> nil or {:aliases-var v});
                                               ;   fact-type is s/Any: keywords, fq class-name
                                               ;   symbols, strings are all legitimate
   (s/=> s/Any s/Any)
   (s/optional-key :fact-constructors) [FactConstructorSpec]})

(defn ->annotations-from-rule-source-analysis
  "Generates rule annotations (insert/retract types etc.) from a pre-computed `clj-kondo` analysis
  map.

   Key options (see `RuleSourceAnnotationsOptions` for the full schema):

   * `:rule-source-analysis` — the clj-kondo rule-source analysis map (required). When produced by
  `->rule-source-analysis` it carries synthesized sources under `::combined-sources`, which take
  precedence for callsite source extraction.

   * `:session-or-rulebase` — Clara session or rulebase (required).

   * `:rules-filter` — optional coll of rule symbols to filter by.

   * `:callsite-resolver-fn` — optional fn invoked once per callsite argument form the automatic
  constructor-resolution chain cannot resolve (see `analyze.callsite`). Receives a
  `callsite/CallsiteResolverContext`. Returns nil (still unresolved) or `{:resolved-types [tokens …]}`.
  Exceptions are contained: logged and treated as unresolved.

   * `:fact-type-spec-fn` — optional fn declaring caller-specific fact patterns. Receives a fact
  type (from a rule's LHS bindings) and returns a spec map or nil; currently one key: `{:aliases-var
  fully.qualified/var-name}` (the var-as-fact pattern — a fact IS a function var, bound on the LHS
  and invoked in the RHS). When a rule binds an alias-mapped fact type and uses the binding in its
  RHS, a synthetic var-usage links the rule to the aliased var so the var's call chain is explored
  for boundary calls (see `alias/alias-usage-map`). Callsites discovered through that chain bypass the
  ctor chain: recorded `:none` (unresolved) with `:fact-type`/`:fact-type-spec` attached, and handed to
  `:callsite-resolver-fn` with the same context.

   * `:fact-constructors` — optional vector of `FactConstructorSpec` maps
  `[{:match-fn … :type-resolver-fn} …]` declaring additional constructors of
  interest.  When a boundary-call argument chain reaches a callsite whose
  callee a `:match-fn` accepts (fully-qualified match; first matching spec in
  vector order wins), that spec's `:type-resolver-fn` is invoked with a
  `callsite/ConstructorTypeResolverContext` (including a `:via` provenance
  chain), and the callsite is owned by the constructor path — it never also
  reaches `:callsite-resolver-fn`.

   * `:dynamic-type-fallback-resolution` — controls the heuristic record-ctor
  scan fallback (see `DynamicTypeFallbackResolution`): `:none` |
  `:rulebase-fact-types-only` (default) | `:all-resolvable-fact-types`.
  Types the default filter rejects are reported via `tap>` with
  `:event :clara-rules/type-fallback-skipped` context — register a tap with
  `add-tap` to trace what was skipped."
  [{:keys [rule-source-analysis rules-filter session-or-rulebase callsite-resolver-fn
           fact-type-spec-fn fact-constructors dynamic-type-fallback-resolution] :as options}]
  (s/validate RuleSourceAnnotationsOptions options)
  (when-not session-or-rulebase
    (throw (ex-info "->annotations-from-rule-source-analysis requires :session-or-rulebase"
                    {:missing :session-or-rulebase})))
  (doseq [ns-sym (->> rule-source-analysis
                      :var-definitions
                      (into []
                            (comp (keep :ns) (distinct))))]
    (try (require ns-sym) (catch Exception _ nil)))

  (let [get-source (build-source-loader (::combined-sources rule-source-analysis))
        rulebase (get-rulebase session-or-rulebase)
        fallback-mode (or dynamic-type-fallback-resolution :rulebase-fact-types-only)
        fallback-type-filter (when (= :rulebase-fact-types-only fallback-mode)
                               (build-fallback-type-filter rulebase))
        productions-by-name (into {}
                                  (map (fn [p] [(normalize-fq-name-key (:name p)) p]))
                                  (:productions rulebase))
        effective-filter (if (seq rules-filter)
                           (mapv normalize-fq-name-key rules-filter)
                           (all-rule-fq-names session-or-rulebase))
        ;; Var-alias chains (:fact-type-spec-fn): synthetic rule → aliased-var
        ;; usages are injected before graph building so the existing
        ;; reachability explores each aliased var's call chain.
        alias-by-rule (when fact-type-spec-fn
                        (alias/alias-usage-map productions-by-name effective-filter
                                               (group-by u/var-usage-caller (:var-usages rule-source-analysis))
                                               fact-type-spec-fn))
        rule-source-analysis (cond-> rule-source-analysis
                               (seq alias-by-rule)
                               (update :var-usages into (mapcat :usages) (vals alias-by-rule)))
        index (index/build-analysis-index
               {:analysis rule-source-analysis
                :get-source get-source
                :productions-by-name productions-by-name
                :fact-constructors fact-constructors
                :fallback-type-filter fallback-type-filter
                :fallback-mode fallback-mode})
        project-vars (keys (:graph index))
        var-seq (or effective-filter project-vars)
        infer-ctx (build-infer-ctx
                   {:index index
                    :callsite-resolver-fn callsite-resolver-fn
                    :alias-by-rule alias-by-rule
                    :fallback-mode fallback-mode
                    :get-source get-source})
        annotations
        (into {}
              (keep (fn [v]
                      (let [annotation (infer-annotation-for-var v infer-ctx)]
                        (cond
                          ;; Real annotation with inferred insert/retract data.
                          (seq annotation)       [v annotation]
                          ;; Explicitly-requested rule that produces nothing: mark it.
                          (seq effective-filter) [v {:clara-rules/no-output-types true}]))))
              var-seq)
        ;; Normalize to string keys for external consumers (EDN, API, session enrichment).
        annotations (ann/normalize-annotations annotations)]
    annotations))

(defn extract-rule-names
  "Extracts all rule and query names (symbols) from a Clara session or rulebase."
  [session-or-rulebase]
  (let [{:keys [productions]} (get-rulebase session-or-rulebase)]
    (into []
          (comp (map :name)
                (distinct))
          productions)))

(defn extract-rule-namespaces
  "Extracts all namespace symbols where rules or queries in the session are defined."
  [session-or-rulebase]
  (->> session-or-rulebase
       extract-rule-names
       (into []
             (comp (map normalize-fq-name-key)
                   (keep namespace)
                   (map symbol)
                   (distinct)))))

(defn ->rule-source-analysis
  "Builds a unified clj-kondo analysis map for the rules in a Clara session
   or rulebase:

   1. Rule names, namespaces, and RHS forms come from the rulebase
      productions — the session is the source of truth, so macro-emitted
      rules are included.
   2. For each rule-owning namespace a combined source is synthesized: the
      real source (or a reconstructed ns form when none is on the classpath)
      plus one synthetic snippet def per rule (see `synth/synthesize-ns-source`).
   3. clj-kondo analyzes each combined source; `defrule`/`defquery` constructs
      produced by the active config in the source region are pruned and the
      snippet region is renamed to the production names (see `prune-and-rename-analysis`).
   4. Transitive dependencies are analyzed from the classpath and merged.

   Returns the merged clj-kondo analysis map, with the combined sources under
   ::combined-sources for `->annotations-from-rule-source-analysis`.

   Options:
     :session-or-rulebase   - Clara session or rulebase (required)
     :include-ns-prefixes   - optional coll of ns prefix strings; passed to
                              `->rule-source-analysis-from-namespaces`
     :exclude-ns-prefixes   - optional coll of ns prefix strings; passed to
                              `->rule-source-analysis-from-namespaces`
     :cache-atom            - optional atom to use as cache; defaults to a fresh
                              atom per call (one rule-source-analysis run).
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              verbatim clara-rules config; replaces it entirely.
     :ns-var-defs-fn        - optional (fn [ns-sym] -> nil | [VarDef …]) supplying the
                              definition forms of non-production vars in rule-owning
                              namespaces whose source is not on the classpath (see
                              `synth/VarDef`). Called once per such namespace; nil or
                              empty reproduces current behaviour. Exceptions are
                              contained: logged, treated as no var defs for that
                              namespace."
  [{:keys [session-or-rulebase
           include-ns-prefixes
           exclude-ns-prefixes
           cache-atom
           config-dir
           ns-var-defs-fn]
    :or {config-dir @bundled-kondo-config-dir
         cache-atom (atom {})}}]
  (let [rulebase (get-rulebase session-or-rulebase)
        rules-by-ns (rulebase-rules-by-ns rulebase)
        prune-vars (set (map (comp normalize-fq-name-key :name)
                             (:productions rulebase)))
        ns-source-map (into {}
                            (map (fn [[ns-sym productions]]
                                   [ns-sym (synth/synthesize-ns-source
                                            {:ns-sym ns-sym
                                             :productions productions
                                             :base-source-fn #(some-> (find-ns-resource %) slurp)
                                             :normalize-key-fn normalize-fq-name-key
                                             :var-defs-fn ns-var-defs-fn})]))
                            rules-by-ns)]
    (-> (->rule-source-analysis-from-namespaces
         (cond-> {:starting-namespaces (keys rules-by-ns)
                  :ns-source-map ns-source-map
                  :prune-vars prune-vars
                  :cache-atom cache-atom
                  :config-dir config-dir}
           include-ns-prefixes (assoc :include-ns-prefixes include-ns-prefixes)
           exclude-ns-prefixes (assoc :exclude-ns-prefixes exclude-ns-prefixes)))
        (assoc ::combined-sources
               (into {}
                     (map (fn [[ns-sym synth]] [ns-sym (:source synth)]))
                     ns-source-map)))))

;; ---------------------------------------------------------------------------
;; Helpers for add-memory-derived-insert-type-detections / merge-memory-derived-insert-types
;; ---------------------------------------------------------------------------

(defn- rule->memory-derived-raw-types
  "Per rule name → set of RAW fact types the rule inserted at runtime, from
   the memory-analysis's fact-id → raw-type index crossed with each fact's
   :inserted-from rules.  Raw types (classes, keywords, tuples, strings,
   maps — whatever the session's fact-type-fn returns) flow through the
   enrichment stack un-serialized; only the boundary serializes them."
  [memory-analysis]
  (reduce-kv (fn [acc fact-id {:keys [inserted-from]}]
               (let [raw-type (get-in memory-analysis [:fact-raw-types fact-id])]
                 (reduce (fn [acc' {:keys [name]}]
                           (update acc' name (fnil conj #{}) raw-type))
                         acc
                         inserted-from)))
             {}
             (:facts memory-analysis)))

(defn add-memory-derived-insert-type-detections
  "Takes an annotations map and a memory-analysis (from `memory/->memory-analysis`). Returns the
  annotations map updated with :clara-rules/dynamic-insert-types-detected entries for rules whose
  working- memory fact types are not already declared in the annotations.

   Each new entry carries :fact-instance-derived-types (the rule-ns serialized names, for display)
  and :resolution :partial. Rules whose session-derived types are already covered by the annotations
  are left unchanged.

   Raw types stay objects throughout: the memory-analysis's fact-id → raw-type index is compared
  against the annotation insert-types via per-rule-ns serialization only at the comparison point —
  never demoted to strings ahead of the merge.

   NOTE: This function only compares against the annotations map — it does NOT check rule :props.
  Use `merge-memory-derived-insert-types` for the full pipeline that also deduplicates against
  :props."
  [annotations memory-analysis]
  (let [annotations (ann/normalize-annotations annotations)
        rule->memory-derived-types (rule->memory-derived-raw-types memory-analysis)
        annotations'
        (reduce-kv (fn [acc rule-fq-str raw-types]
                     (let [rule-fq-str (str rule-fq-str)
                           rule-ns (ann/fq-name->namespace rule-fq-str)
                           rule-ann (get acc rule-fq-str)
                           existing (get rule-ann :clara-rules/insert-types)
                           resolve-fn (partial serialize/resolve-type rule-ns)
                           existing-strs (set (map resolve-fn existing))
                           new-types (->> raw-types
                                          (remove (comp existing-strs resolve-fn))
                                          (sort-by resolve-fn)
                                          vec)]
                       (if (seq new-types)
                         (let [existing-dynamic (get rule-ann :clara-rules/dynamic-insert-types-detected)
                               derived-entry    {:fact-instance-derived-types new-types
                                                 :resolution :partial}
                               updated-dynamic  (if existing-dynamic
                                                  (merge existing-dynamic derived-entry)
                                                  derived-entry)]
                           (assoc acc rule-fq-str
                                  (assoc (or rule-ann {})
                                         :clara-rules/dynamic-insert-types-detected
                                         updated-dynamic)))
                         acc)))
                   annotations
                   rule->memory-derived-types)]
    annotations'))

(defn merge-memory-derived-insert-types*
  "Implementation of `merge-memory-derived-insert-types` returning {:annotations … :memory-analysis
  …}, so a caller that also needs the memory-analysis (the cache build) reuses it instead of
  re-inspecting the session. See `merge-memory-derived-insert-types` for the merge semantics."
  [annotations session]
  (let [original           (ann/normalize-annotations annotations)
        memory-analysis    (memory/->memory-analysis session)
        enriched           (add-memory-derived-insert-type-detections original memory-analysis)
        rule->memory-derived-types (rule->memory-derived-raw-types memory-analysis)
        rulebase           (-> session eng/components :rulebase)

        merged-annotations
        (ann.merge/annotations
         (ann.merge/merge-layers [(ann.merge/props-layer rulebase)
                                  (ann.merge/layer {:id :enriched
                                                    :annotations enriched})]))

        resolved-annotation-map
        (into {}
              (for [p (:productions rulebase)]
                [(ann/normalize-rule-name (:name p))
                 (ann/production-annotation merged-annotations p)]))

        enriched-annotations
        (reduce-kv (fn [acc rule-name resolved-ann]
                     (let [rule-ns        (ann/fq-name->namespace rule-name)
                           resolve-fn     (partial serialize/resolve-type rule-ns)
                           rule-entry     (get acc rule-name)
                           declared-strs  (set (map resolve-fn
                                                    (:insert-types resolved-ann)))
                           detection-info (:dynamic-insert-types-detected resolved-ann)
                           raw-types      (get rule->memory-derived-types rule-name)
                           ;; Session-derived types not already declared in the
                           ;; fully-merged annotation's insert-types, compared
                           ;; under this rule's own ns.
                           truly-new      (when (seq raw-types)
                                            (sort-by resolve-fn
                                                     (remove (comp declared-strs resolve-fn)
                                                             raw-types)))]
                       (if detection-info
                         (if (seq truly-new)
                           (let [merged (ann.merge/dedupe-by
                                         resolve-fn
                                         (into (vec (:clara-rules/insert-types rule-entry))
                                               truly-new))]
                             (-> acc
                                 (assoc-in [rule-name :clara-rules/insert-types] merged)
                                 (assoc-in [rule-name :clara-rules/dynamic-insert-types-detected
                                            :fact-instance-derived-types]
                                           truly-new)))
                           ;; No truly-new types from this enrichment pass.
                           ;; Restore the original annotation for this rule to
                           ;; preserve any pre-existing dynamic detection
                           ;; (e.g. :callsites from static analysis).
                           (if-let [orig (get original rule-name)]
                             (assoc acc rule-name orig)
                             (dissoc acc rule-name)))
                         acc)))
                   enriched
                   resolved-annotation-map)]
    {:annotations      enriched-annotations
     :memory-analysis  memory-analysis}))

(defn merge-memory-derived-insert-types
  "Merges memory-derived insert types into the given annotations map from a live Clara session's
  working memory.

   1. Takes a memory-analysis and runs `add-memory-derived-insert-type-detections` to detect fact
  types inserted by rules at runtime.

   2. Builds a production-annotation-map from the session's rulebase and the enriched annotations to
  identify types already declared in rule :props or sidecar annotations.

   3. Merges truly-new derived types (raw objects from the memory-analysis's fact-id → raw-type
  index — classes, keywords, tuples, strings, maps, whatever the session's fact-type-fn returns)
  into each rule's :clara-rules/insert-types so they connect in the dependency graph; types are only
  serialized at the boundary.

   4. For rules whose derived types are already fully covered, restores the original pre-merge
  annotation (preserving any pre-existing dynamic detection keys such as :callsites from static
  analysis).

   Returns the merged annotations map suitable for passing to `->rulebase-analysis`."
  [annotations session]
  (:annotations (merge-memory-derived-insert-types* annotations session)))

(defn ->memory-layer
  "Builds a working-memory annotation Layer from `enriched` (the result of
  merging memory-derived insert types into `base`): the delta of what the
  merge added over `base`, wrapped as a validated Layer with id
  `:clara.tools.graph.analyze/memory`.

  Returns nil when the session contributed nothing new — the honest result
  for an unfired session, rather than a layer restating the base."
  [base enriched]
  (let [delta (ann/annotations-delta base enriched)]
    (when (seq delta)
      (ann.merge/annotations-delta->layer
       :clara.tools.graph.analyze/memory
       {:generated-by "clara-rules-explorer"
        :derived-from "session working memory"
        :rule-count (count delta)}
       delta))))

