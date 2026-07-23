(ns clara.server.tools.graph.analyze
  "Static analysis tools for Clara rules using clj-kondo.
   Traces rule RHS call graphs to auto-detect insert/retract fact types.

   ## Session-based analysis

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
     ;; Pass it to the analysis:
     (analyze-session-rules
       {:session-or-rulebase session
        :config-dir \"my-kondo-config\"})"
  (:require [clj-kondo.core :as kondo]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clara.server.tools.graph.analyze.rhs :as rhs]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.annotations :as ann]
            [clara.rules.engine :as eng])
  (:import [clara.rules.engine LocalSession]))

(declare ns->resource-base)

;;
;; Private Helpers
;;

(def ^:private insert-fns
  #{'clara.rules/insert!
    'clara.rules/insert-unconditional!
    'clara.rules/insert-all-unconditional!
    'clara.rules/insert-all!
    'clara.rules.engine/insert-facts!
    'clara.rules/insert
    'clara.rules/insert-unconditional
    'clara.rules/insert-all})

(def ^:private retract-fns
  #{'clara.rules/retract!
    'clara.rules.engine/rhs-retract-facts!
    'clara.rules/retract})

(def ^:private boundary-fns
  (clojure.set/union insert-fns retract-fns))

(defn- fq-name-sym [ns name]
  (symbol (str ns) (str name)))

(defn- var-ns-name
  "Returns the namespace name of a var's metadata :ns."
  [v]
  (ns-name (:ns (meta v))))

(defn- var-name
  "Returns the name symbol from a var's metadata."
  [v]
  (:name (meta v)))

(defn- var-usage-caller
  "Returns the fully-qualified caller symbol from a kondo var-usage map."
  [usage]
  (fq-name-sym (:from usage) (:from-var usage)))

(defn- var-usage-callee
  "Returns the fully-qualified callee symbol from a kondo var-usage map."
  [usage]
  (fq-name-sym (:to usage) (:name usage)))

(defn- normalize-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn- build-graph [analysis]
  ;; :from-var is a symbol for usages inside a def, or nil/absent for
  ;; top-level forms (clj-kondo never produces the *symbol* `nil`).
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if from-var
              (let [caller (fq-name-sym from from-var)
                    callee (fq-name-sym to name)]
                (update acc caller (fnil conj #{}) callee))
              acc))
          {}
          (:var-usages analysis)))

(defn- transitive-reachability [graph start-vars]
  (loop [seen #{}
         todo (set start-vars)]
    (if (empty? todo)
      seen
      (let [seen (into seen todo)
            traversable (clojure.set/difference todo boundary-fns)
            next-vars (set (mapcat graph traversable))
            unvisited (clojure.set/difference next-vars seen)]
        (recur seen unvisited)))))

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
   temp directory and returns its absolute path, suitable for `kondo/run!`
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
    (kondo/run!
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

(defn- direct-callers
  "Returns the set of var names in in `var-names` that directly call any function in `target-fns`
  according to the call `graph`."
  [graph var-names target-fns]
  (into #{}
        (filter (fn [var-name] (some target-fns (get graph var-name))))
        var-names))

(defn- precompute-reachability
  "Returns a map of {var-sym -> reachable-set} for every var in `graph`.
   reachable-set is the transitive closure of callees, stopping at boundary-fns."
  [graph vars]
  (into {}
        (map (fn [v] [v (transitive-reachability graph [v])]))
        vars))

(defn- build-inserter-type-map
  "Bottom-up: for every var that is a direct caller of a target fn,
   find record constructors (map->X, ->X) in its reachable subtree.

   Returns {inserter-var -> #{type-symbols}}.

   Note: because clj-kondo's flat var-usages analysis cannot distinguish
   argument expressions within a callsite from independent calls in the
   same function body, a var's reachable subtree may include constructors
   from unrelated RHS branches (e.g. a helper that builds both a fact and
   an unrelated record value for a side computation). Consumers should use
   manual annotations (:clara-rules/no-output-types) to suppress false
   positives."
  [direct-callers graph {:keys [var-usages]}]
  (let [reachable-cache (precompute-reachability graph direct-callers)]
    (into {}
          (map (fn [v]
                 (let [subtree (get reachable-cache v #{})
                       ;; Find record constructors called from vars in this subtree
                       types
                       (into #{}
                             (comp
                              (filter (fn [vu]
                                        (and (contains? subtree (var-usage-caller vu))
                                             (-> vu :name name rhs/constructor-fn-name?))))
                              (keep (fn [vu]
                                      (rhs/resolve-record-type (:to vu) (:name vu)))))
                             var-usages)]
                   [v types])))
          direct-callers)))

(defn- extract-constructor-types-from-reachable
  "Top-down: given a rule var's reachable set, find which inserter vars are
   in it and union their types from the precomputed inserter-type-map."
  [reachable inserter-type-map]
  (into #{}
        (mapcat (fn [v] (get inserter-type-map v #{})))
        reachable))

(defn- extract-insert-types
  "Determines the fact types a rule inserts or retracts via target-fns:
   statically-traceable record constructors win when present; otherwise every
   boundary-call argument form goes through the runtime resolution chain
   (analyze.rhs) and the optional :callsite-resolver-fn, producing dynamic
   detection entries annotated with :status/:resolved-types plus the
   aggregate :resolution."
  [reachable target-fns {:keys [analysis inserter-type-map] :as ctx}]
  (let [usages (:var-usages analysis)
        direct-caller? (fn [caller]
                         (and (contains? reachable caller)
                              (not (contains? target-fns caller))))
        direct-target-call?
        (fn [usage]
          (and (direct-caller? (var-usage-caller usage))
               (contains? target-fns (var-usage-callee usage))))
        has-direct-callers? (boolean (some direct-target-call? usages))]
    (if-not has-direct-callers?
      {:static-types #{}
       :resolved-types #{}
       :dynamic-forms nil}
      (let [static-types (extract-constructor-types-from-reachable
                          reachable inserter-type-map)]
        (if (seq static-types)
          {:static-types static-types
           :resolved-types #{}
           :dynamic-forms nil}
          (let [boundary-usages (into [] (filter direct-target-call?) usages)
                {:keys [callsites resolved-types resolution]}
                (rhs/resolve-boundary-callsites boundary-usages ctx)]
            {:static-types #{}
             :resolved-types resolved-types
             :dynamic-forms (when (seq callsites)
                              (cond-> {:callsites callsites}
                                resolution (assoc :resolution resolution)))}))))))

(defn- var-reachability
  "For a given `var-name`, returns a map of:

  * :reachable - set of all transitively reachable vars

  * :is-inserter? - true if reachable set includes an insert fn or a direct inserter

  * :is-retractor? - true if reachable set includes a retract fn or a direct retractor"
  [var-name
   {:keys [graph insert-fns retract-fns
           direct-inserters direct-retractors]}]
  (let [reachable (transitive-reachability graph [var-name])
        is-inserter? (some #(or (contains? insert-fns %)
                                (contains? direct-inserters %))
                           reachable)
        is-retractor? (some #(or (contains? retract-fns %)
                                 (contains? direct-retractors %))
                            reachable)]
    {:reachable reachable
     :is-inserter? is-inserter?
     :is-retractor? is-retractor?}))

(defn- infer-annotation-for-var
  "Returns an annotation map for the var referred to by the given `var-name` when it inserts or
  retracts fact types. Returns nil when the var has no output side-effects.

  Statically-traceable constructor types and types resolved by the dynamic
  callsite chain (analyze.rhs) are promoted into :clara-rules/insert-types
  and :clara-rules/retract-types; unresolved or partially-resolved callsites
  remain visible under the dynamic-detection keys.

  `ctx` must contain :graph, :insert-fns, :retract-fns, :direct-inserters, :direct-retractors,
  :analysis, :inserter-type-map, :retractor-type-map, :get-source, :productions-by-name,
  and :callsite-resolver-fn."
  [var-name {:keys [insert-fns retract-fns retractor-type-map productions-by-name] :as ctx}]
  (let [{:keys [is-inserter? is-retractor? reachable]} (var-reachability var-name ctx)]
    (when (or is-inserter? is-retractor?)
      (let [ctx (assoc ctx :rule (get productions-by-name var-name))
            insert-ctx (assoc ctx :direction :insert)
            retract-ctx (assoc ctx
                               :inserter-type-map retractor-type-map
                               :direction :retract)
            inserts  (when is-inserter? (extract-insert-types reachable insert-fns insert-ctx))
            retracts (when is-retractor? (extract-insert-types reachable retract-fns retract-ctx))
            insert-types (into (:static-types inserts) (:resolved-types inserts))
            retract-types (into (:static-types retracts) (:resolved-types retracts))]
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
;; Source synthesis + prune-and-replace (session-based analysis)
;;

(defn- session-rules-by-ns
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

(defn- session-rule-fq-names
  "Returns the fq symbols of the session's rules — productions that carry an
   :rhs (queries excluded)."
  [session-or-rulebase]
  (let [{:keys [productions]} (get-rulebase session-or-rulebase)]
    (into []
          (comp (filter :rhs)
                (map :name)
                (map normalize-key)
                (distinct))
          productions)))

;;; reconstruct-ns-source helpers ;;;

(defn- core-deviations
  "Returns {:excluded [...] :renamed {...}} describing how the given namespace
   deviates from the default clojure.core mappings. Excluded are clojure.core
   publics not present in the ns; renamed are entries whose local symbol
   differs from the var's own name."
  [nsobj]
  (let [core-publics      (->> 'clojure.core ns-publics keys set)
        core-entries      (into {}
                                (filter (fn [[_ v]]
                                          (and (var? v)
                                               (= 'clojure.core (var-ns-name v)))))
                                (ns-map nsobj))
        present-core-names (into #{} (map (comp var-name val)) core-entries)]
    {:excluded (->> core-publics (remove present-core-names) sort vec)
     :renamed  (into {}
                     (filter (fn [[sym v]] (not= sym (var-name v))))
                     core-entries)}))

(defn- build-require-clauses
  "Builds a sorted vector of :require clause vectors from the namespace's
   aliases and refers, excluding clojure.core refers."
  [nsobj]
  (let [alias-clauses (->> (ns-aliases nsobj)
                           (mapv (fn [[a target]] [(ns-name target) :as a])))
        refers        (into []
                            (comp (remove #(= 'clojure.core
                                              (var-ns-name (val %)))))
                            (ns-refers nsobj))
        refer-groups  (group-by (fn [[_ v]] (var-ns-name v)) refers)
        refer-clauses (mapv (fn [[target kvs]]
                              (->> kvs
                                   (map first)
                                   sort
                                   (into [target :refer])))
                            refer-groups)]
    (->> (concat alias-clauses refer-clauses)
         (sort-by (comp str first))
         vec)))

(defn- build-import-clauses
  "Builds a sorted vector of :import clause vectors from the namespace's
   imports, excluding java.lang.* (which are automatic in any new ns)."
  [nsobj]
  (let [imports       (into []
                            (comp (remove #(.startsWith (.getName ^Class (val %))
                                                        "java.lang.")))
                            (ns-imports nsobj))
        import-groups (group-by (fn [[_ ^Class c]] (.getPackageName c)) imports)]
    (->> import-groups
         (map (fn [[pkg kvs]]
                (->> kvs
                     (map first)
                     sort
                     (into [(symbol pkg)]))))
         (sort-by (comp str first))
         vec)))

(defn- unmapped-default-imports
  "Returns the sorted vector of default java.lang.* import symbols missing
   from the given namespace (possible only via dynamic ns-unmap)."
  [nsobj]
  (let [imported (-> nsobj ns-imports keys set)]
    (->> clojure.lang.RT/DEFAULT_IMPORTS
         keys
         (remove imported)
         sort
         vec)))

(defn- reconstruct-ns-source
  "Builds a synthetic source string containing only an (ns ...) form
   reconstructed from the live Namespace object — used for namespaces whose
   source is not on the classpath (jars without sources, eval'd code).

   clojure.core refers and java.lang.* imports are automatic in any new ns,
   so only deviations are emitted: a :refer-clojure clause with :exclude /
   :rename when the live ns deviates from clojure.core defaults, and trailing
   (ns-unmap ...) forms in the rare case a default java.lang import is
   missing (only possible via dynamic ns-unmap in the original ns)."
  [ns-sym]
  (let [nsobj            (the-ns ns-sym)
        {:keys [excluded renamed]} (core-deviations nsobj)
        require-clauses   (build-require-clauses nsobj)
        import-clauses    (build-import-clauses nsobj)
        unmapped-defaults (unmapped-default-imports nsobj)
        refer-clojure-clause (when (or (seq excluded) (seq renamed))
                               (into [:refer-clojure]
                                     cat
                                     [(when (seq excluded) [:exclude excluded])
                                      (when (seq renamed) [:rename renamed])]))
        require-clause (when (seq require-clauses)
                         (cons :require require-clauses))
        import-clause (when (seq import-clauses)
                        (cons :import import-clauses))
        ns-form (concat (list 'ns ns-sym)
                        (remove nil? [refer-clojure-clause require-clause import-clause]))]
    (str (pr-str ns-form) "\n"
         (str/join (map #(format "(ns-unmap (the-ns '%s) '%s)\n" ns-sym %)
                        unmapped-defaults)))))

(defn- synthesize-ns-source
  "Builds the combined source for a rule-owning namespace: the real source
   (or a reconstructed ns form when none is on the classpath) plus one
   synthetic snippet per rule, `(def <tag> (fn [] <pr-str-of-rhs>))`, each on
   its own line. Snippet tags are deterministic ordinals — never raw
   production names (which may not be legal def symbols) — with an explicit
   tag → production-name mapping for attribution.

   Returns {:source combined-str
            :offset base-source-line-count
            :tag->production {tag-sym production-local-name-sym}}"
  [ns-sym productions]
  (let [base-source (or (some-> (find-ns-resource ns-sym) slurp)
                        (reconstruct-ns-source ns-sym))
        offset (count (str/split-lines base-source))
        snippets (map-indexed
                  (fn [idx production]
                    (let [tag (symbol (str "__clara_explorer_rule_" idx "__"))
                          local-name (-> production :name normalize-key name symbol)
                          rhs-str (binding [*print-length* nil
                                            *print-level* nil]
                                    (pr-str (:rhs production)))]
                      {:tag tag
                       :local-name local-name
                       :form (format "(def %s (fn [] %s))" tag rhs-str)}))
                  productions)]
    {:source (str base-source "\n" (str/join "\n" (map :form snippets)) "\n")
     :offset offset
     :tag->production (into {} (map (juxt :tag :local-name)) snippets)}))

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
                                        (and (prune-vars (fq-name-sym (:ns d) (:name d)))
                                             (source-region? (:row d)))))
                              (map (fn [d] (update d :name rename))))
                        defs)))
        (update :var-usages
                (fn [usages]
                  (into []
                        (comp (remove (fn [u]
                                        (and (:from-var u)
                                             (prune-vars (fq-name-sym (:from u) (:from-var u)))
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

(defn build-analysis-from-namespaces
  "Resolves transitive dependencies on the classpath for starting namespaces,
   optionally filtering by include-ns-prefixes and exclude-ns-prefixes,
   runs clj-kondo against them (using the cache), and returns a merged analysis map.

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
                              (see synthesize-ns-source); those namespaces are
                              analyzed from their combined source instead of the
                              classpath, then pruned-and-renamed.
     :prune-vars            - optional set of fq production symbols to prune from
                              synthesized namespaces (see prune-and-rename-analysis).
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              verbatim clara-rules config. Pass nil to disable
                              (falls back to cwd-based `.clj-kondo` discovery).
     :initial-analysis      - optional map of analysis data to seed the merge with;
                              defaults to nil.
     :processed-namespaces  - optional coll of namespace symbols already processed;
                              defaults to nil."
  [{:keys [starting-namespaces include-ns-prefixes exclude-ns-prefixes cache-atom ns-source-map prune-vars config-dir
           initial-analysis processed-namespaces]
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
                       (extract-required-namespaces initial-analysis))
           processed (set processed-namespaces)
           merged-analysis (or initial-analysis {})]
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
  "Returns a (fn [ns-sym filename] -> source-str) that caches source lookups.
   `combined-sources` maps ns-sym -> synthesized source (real source + rule
   snippets, see synthesize-ns-source) and takes precedence over classpath
   resources, so callsite positions in synthesized analyses resolve against
   the exact text clj-kondo analyzed."
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

(defn- build-infer-ctx
  "Builds the context map passed to `infer-annotation-for-var`."
  [graph analysis inserter-type-map retractor-type-map get-source
   productions-by-name callsite-resolver-fn]
  {:graph graph
   :insert-fns insert-fns
   :retract-fns retract-fns
   :direct-inserters (direct-callers graph (keys graph) insert-fns)
   :direct-retractors (direct-callers graph (keys graph) retract-fns)
   :analysis analysis
   :inserter-type-map inserter-type-map
   :retractor-type-map retractor-type-map
   :get-source get-source
   :productions-by-name productions-by-name
   :callsite-resolver-fn callsite-resolver-fn})

(defn generate-annotations-from-analysis
  "Generates rule annotations (insert/retract types etc.) from a pre-computed
   clj-kondo analysis map.

   Options:
     :analysis            - the clj-kondo analysis map (required). When produced
                            by `analyze-session-rules` it carries synthesized
                            sources under ::combined-sources, which take
                            precedence for callsite source extraction.
     :session-or-rulebase - Clara session or rulebase (required). Supplies the
                            full productions handed to :callsite-resolver-fn
                            and, when :rules-filter is not given, the default
                            filter: the session's rules (productions with an
                            :rhs — queries are excluded).
     :rules-filter        - optional coll of rule symbols to filter by.
     :callsite-resolver-fn - optional fn invoked once per callsite argument
                            form the automatic constructor-resolution chain
                            cannot resolve (see analyze.rhs). Receives a map:
                              :rule        - the full production (:name :ns-name
                                             :lhs :rhs :props :env …), when a
                                             session was supplied
                              :ns-name-sym - ns where the callsite was found
                                             (may be a helper ns)
                              :direction   - :insert | :retract
                              :boundary-fn - e.g. clara.rules/insert!
                              :arg-form    - the unresolved argument form
                                             (locals already traced to their
                                             init forms)
                              :source-str  - pr-str of :arg-form
                              :filename    - file containing the callsite
                            Returns nil (still unresolved) or a map with
                            :resolved-types [tokens…]. Exceptions are
                            contained: logged and treated as unresolved."
  [{:keys [analysis rules-filter session-or-rulebase callsite-resolver-fn]}]
  (when-not session-or-rulebase
    (throw (ex-info "generate-annotations-from-analysis requires :session-or-rulebase"
                    {:missing :session-or-rulebase})))
  (doseq [ns-sym (->> analysis
                      :var-definitions
                      (into []
                            (comp (keep :ns) (distinct))))]
    (try (require ns-sym) (catch Exception _ nil)))

  (let [get-source (build-source-loader (::combined-sources analysis))
        graph (build-graph analysis)
        project-vars (keys graph)
        rulebase (get-rulebase session-or-rulebase)
        productions-by-name (into {}
                                  (map (fn [p] [(normalize-key (:name p)) p]))
                                  (:productions rulebase))
        effective-filter (if (seq rules-filter)
                           (mapv normalize-key rules-filter)
                           (session-rule-fq-names session-or-rulebase))
        var-seq (or effective-filter project-vars)
        inserter-type-map (build-inserter-type-map
                           (direct-callers graph project-vars insert-fns)
                           graph analysis)
        retractor-type-map (build-inserter-type-map
                            (direct-callers graph project-vars retract-fns)
                            graph analysis)
        infer-ctx (build-infer-ctx graph analysis inserter-type-map retractor-type-map
                                   get-source productions-by-name
                                   callsite-resolver-fn)
        annotations
        (into (sorted-map)
              (keep (fn [v]
                      (let [annotation (infer-annotation-for-var v infer-ctx)]
                        (cond
                          ;; Real annotation with inferred insert/retract data.
                          (seq annotation)       [v annotation]
                          ;; Explicitly-requested rule that produces nothing: mark it.
                          (seq effective-filter) [v {:clara-rules/no-output-types true}]))))
              var-seq)]
    annotations))

(defn extract-session-rule-names
  "Extracts all rule and query names (symbols) from a Clara session or rulebase."
  [session-or-rulebase]
  (let [{:keys [productions]} (get-rulebase session-or-rulebase)]
    (into []
          (comp (map :name)
                (distinct))
          productions)))

(defn extract-session-namespaces
  "Extracts all namespace symbols where rules or queries in the session are defined."
  [session-or-rulebase]
  (->> session-or-rulebase
       extract-session-rule-names
       (into []
             (comp (map normalize-key)
                   (keep namespace)
                   (map symbol)
                   (distinct)))))

(defn analyze-session-rules
  "Builds a unified clj-kondo analysis map for the rules in a Clara session
   or rulebase:

   1. Rule names, namespaces, and RHS forms come from the rulebase
      productions — the session is the source of truth, so macro-emitted
      rules are included.
   2. For each rule-owning namespace a combined source is synthesized: the
      real source (or a reconstructed ns form when none is on the classpath)
      plus one synthetic snippet def per rule (see synthesize-ns-source).
   3. clj-kondo analyzes each combined source; defrule/defquery constructs
      produced by the active config in the source region are pruned and the
      snippet region is renamed to the production names (prune-and-replace).
   4. Transitive dependencies are analyzed from the classpath and merged.

   Returns the merged clj-kondo analysis map, with the combined sources under
   ::combined-sources for `generate-annotations-from-analysis`.

   Options:
     :session-or-rulebase   - Clara session or rulebase (required)
     :include-ns-prefixes   - optional coll of ns prefix strings; passed to build-analysis-from-namespaces
     :exclude-ns-prefixes   - optional coll of ns prefix strings; passed to build-analysis-from-namespaces
     :cache-atom            - optional atom to use as cache; defaults to a fresh
                              atom per call (one session analysis run).
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              verbatim clara-rules config; replaces it entirely."
  [{:keys [session-or-rulebase include-ns-prefixes exclude-ns-prefixes cache-atom config-dir]
    :or {config-dir @bundled-kondo-config-dir
         cache-atom (atom {})}}]
  (let [rulebase (get-rulebase session-or-rulebase)
        rules-by-ns (session-rules-by-ns rulebase)
        prune-vars (set (map (comp normalize-key :name) (:productions rulebase)))
        ns-source-map (into {}
                            (map (fn [[ns-sym productions]]
                                   [ns-sym (synthesize-ns-source ns-sym productions)]))
                            rules-by-ns)]
    (-> (build-analysis-from-namespaces
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
;; Helpers for add-auto-detected-annotations / enrich-annotations-from-session
;; ---------------------------------------------------------------------------

(defn- annot-type->str
  "Convert an annotation type value (Class, String, Symbol, etc.) to its
   canonical string form for comparison with serialized session fact-types."
  [ns-name type-val]
  (serialize/resolve-type ns-name type-val))

(defn- fq-name->namespace
  "Extract the namespace portion from a fully-qualified rule name string like
   \"some.ns/rule-name\".  Returns a symbol, or nil if the name has no
   namespace segment."
  [fq-str]
  (some-> fq-str symbol namespace symbol))

(defn add-auto-detected-annotations
  "Takes a session-analysis structure (from memory/session-snapshot) and an
   annotations map.  Returns the annotations map updated with
   :clara-rules/dynamic-insert-types-detected entries for rules whose working-
   memory fact types are not already declared in the annotations.

   Each new entry carries :fact-instance-derived-types and :resolution :partial.
   Rules whose session-derived types are already covered by the annotations are
   left unchanged.

   NOTE: This function only compares against the annotations map — it does NOT
   check rule :props.  Use `enrich-annotations-from-session` for the full
   pipeline that also deduplicates against :props."
  [session-analysis annotations]
  (let [rule->session-types
        (reduce-kv (fn [acc _id {:keys [type inserted-from]}]
                     (reduce (fn [acc' {:keys [name]}]
                               (update acc' name (fnil conj #{}) type))
                             acc
                             inserted-from))
                   {}
                   (:facts session-analysis))

        annotations'
        (reduce-kv (fn [acc rule-fq-str session-type-strs]
                     (let [rule-ns       (fq-name->namespace rule-fq-str)
                           rule-ann      (get acc rule-fq-str)
                           existing      (get rule-ann :clara-rules/insert-types)
                           existing-strs (set (map (partial annot-type->str rule-ns)
                                                   existing))
                           new-types     (sort (set/difference session-type-strs
                                                               existing-strs))]
                       (if (seq new-types)
                         (let [existing-dynamic (get rule-ann :clara-rules/dynamic-insert-types-detected)
                               derived-entry    {:fact-instance-derived-types (vec new-types)
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
                   rule->session-types)]
    annotations'))

(defn enrich-annotations-from-session
  "Enriches the given annotations map with fact-type provenance from a live
   Clara session's working memory.

   1. Takes a session snapshot and runs `add-auto-detected-annotations` to
      detect fact types inserted by rules at runtime.
   2. Builds a production-annotation-map from the session's rulebase and the
      enriched annotations to identify types already declared in rule :props
      or sidecar annotations.
   3. Merges truly-new derived types into each rule's :clara-rules/insert-types
      so they connect in the dependency graph.
   4. For rules whose derived types are already fully covered, restores the
      original pre-enrichment annotation (preserving any pre-existing dynamic
      detection keys such as :callsites from static analysis).

   Returns the enriched annotations map suitable for passing to
   `rulebase-analysis`."
  [session annotations]
  (let [original     annotations
        snapshot     (memory/session-snapshot session)
        enriched     (add-auto-detected-annotations snapshot annotations)
        rulebase     (-> session eng/components :rulebase)
        productions  (:productions rulebase)

        pam
        (into {}
              (for [p productions]
                [(:name p)
                 (ann/resolve-annotations p enriched)]))

        result
        (reduce-kv (fn [acc p-name resolved-ann]
                     (let [raw-entry      (get acc p-name)
                           resolved-strs  (set (map (partial annot-type->str nil)
                                                    (:insert-types resolved-ann)))
                           dynamic        (:dynamic-insert-types-detected resolved-ann)
                           derived-types  (set (:fact-instance-derived-types dynamic))
                           truly-new      (set/difference derived-types resolved-strs)]
                       (if dynamic
                         (if (seq truly-new)
                           (let [raw-inserts (:clara-rules/insert-types raw-entry)
                                 merged      (into (vec raw-inserts) truly-new)]
                             (-> acc
                                 (assoc-in [p-name :clara-rules/insert-types] merged)
                                 (assoc-in [p-name :clara-rules/dynamic-insert-types-detected
                                            :fact-instance-derived-types]
                                           (vec truly-new))))
                           ;; No truly-new types from this enrichment pass.
                           ;; Restore the original annotation for this rule to
                           ;; preserve any pre-existing dynamic detection
                           ;; (e.g. :callsites from static analysis).
                           (if-let [orig (get original p-name)]
                             (assoc acc p-name orig)
                             (dissoc acc p-name)))
                         acc)))
                   enriched
                   pam)]
    result))

