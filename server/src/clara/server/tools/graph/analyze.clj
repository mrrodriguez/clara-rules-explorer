(ns clara.server.tools.graph.analyze
  "Static analysis tools for Clara rules using clj-kondo.
   Traces rule RHS call graphs to auto-detect insert/retract fact types.

   ## Custom kondo config

   Callers can supply their own clj-kondo config via the `:config-dir` option
   on `generate-annotations-from-paths` or `analyze-session-rules`. This
   completely replaces the bundled config — useful for adding project-specific
   hooks or overriding rule parsing.

   The bundled config (used when no `:config-dir` is given) contains:
     - config.edn          — registers our defrule hook (LHS-stripping)
     - hooks/strip_lhs.clj_kondo — minimal defrule hook, only analyzes RHS
     - imports/clara/rules/ — synced clara-rules hooks (defquery, defhierarchy, etc.)

   To provide a custom config while keeping clara-rules support, copy the
   bundled config as a base, modify as needed, and pass the directory as
   `:config-dir`. At minimum, ensure your config registers a hook for
   `clara.rules/defrule` that emits a `def` form whose function body
   contains the rule's RHS (clj-kondo analyzes this for var-usages).

   Example:
     ;; Copy bundled config as a starting point
     cp -r resources/clara/server/tools/graph/kondo-config my-kondo-config
     ;; Edit my-kondo-config/config.edn to register your own hooks
     ;; Pass it to the analysis:
     (generate-annotations-from-paths
       {:paths [\"src/rules\"]
        :config-dir \"my-kondo-config\"})"
  (:require [clj-kondo.core :as kondo]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clara.rules.engine :as eng])
  (:import [clara.rules.engine LocalSession]))

(declare ns->resource-base)

;;
;; Private Helpers
;;

(defonce ^:private global-analysis-cache (atom {}))

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

(defn- var-usage-caller
  "Returns the fully-qualified caller symbol from a kondo var-usage map."
  [usage]
  (fq-name-sym (:from usage) (:from-var usage)))

(defn- var-usage-callee
  "Returns the fully-qualified callee symbol from a kondo var-usage map."
  [usage]
  (fq-name-sym (:to usage) (:name usage)))

(defn- constructor-fn-name?
  "True if the given fn-name string looks like a record constructor (->X or map->X)."
  [fn-name]
  (or (str/starts-with? fn-name "map->")
      (str/starts-with? fn-name "->")))

(defn- normalize-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn- build-graph [analysis]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if (and from-var (not= from-var 'nil))
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
;; Bundled clj-kondo config (clara-rules hooks)
;;
;; clj-kondo only produces var-definitions for def-macros it has a hook or
;; :lint-as mapping for. Without the clara-rules hooks, `defrule`/`defquery`
;; etc. are opaque calls, no rule gets a :from-var, and the call graph is empty.
;; Resources that override or extend the synced clara-rules imports.
;; These are maintained by us, not synced from clara-rules.
(def ^:private bundled-override-files
  ["config.edn"
   "hooks/strip_lhs.clj_kondo"])
;; by walking up from the linted file's directory (see clj-kondo impl/core
;; `config-dir`). That is cwd-dependent and unusable for a standalone tool run
;; from a foreign working directory (CLI `-g`) or injected into a host JVM
;; (`add-libs`). Instead we ship the clj-kondo import as classpath resources
;; (mirrored by `clara.server.tools.graph.kondo-config-sync`), materialize it
;; into a temp dir once per process, and pass it explicitly as `:config-dir`.

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
      ;; Copy override files (our hooks, config — not in the sync manifest)
      (doseq [rel bundled-override-files]
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
                                  :java-class-usages true}}}
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

(defn- resolvable-fact-class [sym]
  (try
    (when (class? (resolve sym))
      sym)
    (catch Throwable _ nil)))

(defn- resolve-record-type [ns-sym class-sym]
  (try
    (if-let [resolved (ns-resolve (find-ns ns-sym) class-sym)]
      (cond
        (class? resolved)
        (symbol (.getName ^Class resolved))

        (var? resolved)
        (let [v-meta (meta resolved)
              ns-str (name (ns-name (:ns v-meta)))
              fn-name (name (:name v-meta))
              class-name (cond
                           (str/starts-with? fn-name "->") (subs fn-name 2)
                           (str/starts-with? fn-name "map->") (subs fn-name 5)
                           :else nil)]
          (when class-name
            (let [fq-sym (symbol (str (str/replace ns-str "-" "_") "." class-name))]
              (resolvable-fact-class fq-sym))))

        :else nil)
      nil)
    (catch Exception _ nil)))

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
   positives.

   The LHS is expected to already be stripped by clj-kondo macro parsing
   config — this function only sees RHS var-usages. By default this is
   done via the strip_lhs hook at
   resources/clara/server/tools/graph/kondo-config/hooks/strip_lhs.clj_kondo,
   but callers can override it by passing a custom :config-dir (see the
   namespace docstring)."
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
                                             (constructor-fn-name? (name (:name vu))))))
                              (keep (fn [vu]
                                      (resolve-record-type (:to vu) (:name vu)))))
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

(defn- callsite->dynamic-entries
  "Extracts dynamic annotation entries from a single kondo var-usage.
   Returns a vector of {:source-str :ns-name-sym :filename} maps, one per arg."
  [usage get-source]
  (let [source (get-source (:from usage) (:filename usage))
        call-str (source-text-at source (:row usage) (:col usage) (:end-row usage) (:end-col usage))]
    (if call-str
      (try
        (let [form (read-string call-str)
              args (rest form)]
          (mapv (fn [arg]
                  {:source-str (pr-str arg)
                   :ns-name-sym (:from usage)
                   :filename (:filename usage)})
                args))
        (catch Exception _ []))
      [])))

(defn- extract-insert-types [reachable target-fns {:keys [analysis inserter-type-map get-source]}]
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
       :dynamic-forms nil}
      (let [static-types (extract-constructor-types-from-reachable
                          reachable inserter-type-map)]
        (if (seq static-types)
          {:static-types static-types
           :dynamic-forms nil}
          (let [callsites (into []
                                (comp (filter direct-target-call?)
                                      (mapcat #(callsite->dynamic-entries % get-source))
                                      (distinct))
                                usages)]
            {:static-types #{}
             :dynamic-forms (when (seq callsites) {:callsites callsites})}))))))

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

  `ctx` must contain :graph, :insert-fns, :retract-fns, :direct-inserters, :direct-retractors,
  :analysis, :inserter-type-map, :retractor-type-map, and :get-source."
  [var-name ctx]
  (let [{:keys [is-inserter? is-retractor? reachable]} (var-reachability var-name ctx)]
    (when (or is-inserter? is-retractor?)
      (let [retract-ctx (assoc ctx :inserter-type-map (:retractor-type-map ctx))
            inserts (when is-inserter? (extract-insert-types reachable (:insert-fns ctx) ctx))
            retracts (when is-retractor? (extract-insert-types reachable (:retract-fns ctx) retract-ctx))]
        (cond-> {}
          (seq (:static-types inserts))
          (assoc :clara-rules/insert-types (vec (sort (map symbol (:static-types inserts)))))
          (:dynamic-forms inserts)
          (assoc :clara-rules/dynamic-insert-types-detected (:dynamic-forms inserts))

          (seq (:static-types retracts))
          (assoc :clara-rules/retract-types (vec (sort (map symbol (:static-types retracts)))))
          (:dynamic-forms retracts)
          (assoc :clara-rules/dynamic-retract-types-detected (:dynamic-forms retracts)))))))

;;
;; API
;;

(defn clear-global-analysis-cache!
  "Clears the global analysis cache to prevent memory leaks or stale state."
  []
  (reset! global-analysis-cache {}))

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
     :cache-atom            - optional atom to use as cache; defaults to global-analysis-cache.
     :in-memory-sources     - optional map of {ns-symbol source-string} for dynamically
                              defined in-memory namespaces.
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              clara-rules hooks. Pass nil to disable (falls back to
                              cwd-based `.clj-kondo` discovery).
     :initial-analysis      - optional map of analysis data to seed the merge with;
                              defaults to nil.
     :processed-namespaces  - optional coll of namespace symbols already processed;
                              defaults to nil."
  [{:keys [starting-namespaces include-ns-prefixes exclude-ns-prefixes cache-atom in-memory-sources config-dir
           initial-analysis processed-namespaces]
    :or {cache-atom global-analysis-cache
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
            (let [in-mem-source (get in-memory-sources ns-sym)
                  resource-url (when-not in-mem-source (find-ns-resource ns-sym))]
              (if (or in-mem-source resource-url)
                (let [analyze-fn (if in-mem-source
                                   #(analyze-ns-string ns-sym in-mem-source config-dir)
                                   #(analyze-ns-source ns-sym resource-url config-dir))
                      analysis (get-or-analyze-ns-analysis ns-sym analyze-fn cache-atom)
                      dependencies (extract-required-namespaces analysis)]
                  (recur (into remaining (filter ns-matches-prefix?) dependencies)
                         (conj processed ns-sym)
                         (merge-with into merged-analysis analysis)))
                ;; Resource not found on classpath or in-memory, skip
                (recur remaining (conj processed ns-sym) merged-analysis)))))))))

(defn- build-source-loader
  "Returns a (fn [ns-sym filename] -> source-str) that caches source lookups."
  [in-memory-sources]
  (let [cache (atom {})]
    (fn [ns-sym filename]
      (let [k (or ns-sym filename)]
        (if-let [cached (get @cache k)]
          cached
          (let [source (try
                         (or (and ns-sym (get in-memory-sources ns-sym))
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
  "Builds the context map passed to infer-annotation-for-var."
  [graph analysis inserter-type-map retractor-type-map get-source]
  {:graph graph
   :insert-fns insert-fns
   :retract-fns retract-fns
   :direct-inserters (direct-callers graph (keys graph) insert-fns)
   :direct-retractors (direct-callers graph (keys graph) retract-fns)
   :analysis analysis
   :inserter-type-map inserter-type-map
   :retractor-type-map retractor-type-map
   :get-source get-source})

(defn generate-annotations-from-analysis
  "Generates rule annotations (insert/retract types etc.) from a pre-computed
   clj-kondo analysis map.

   Options:
     :analysis           - the clj-kondo analysis map (required)
     :rules-filter       - optional coll of rule symbols to filter by; when nil,
                           all project vars are analyzed.
     :in-memory-sources  - optional map of {ns-symbol source-string} for dynamically
                           defined in-memory namespaces."
  [{:keys [analysis rules-filter in-memory-sources]}]
  (let [_ (doseq [ns-sym (distinct (keep :ns (:var-definitions analysis)))]
            (try (require ns-sym) (catch Exception _ nil)))
        get-source (build-source-loader in-memory-sources)
        graph (build-graph analysis)
        project-vars (keys graph)
        var-seq (if (seq rules-filter)
                  (map normalize-key rules-filter)
                  project-vars)
        inserter-type-map (build-inserter-type-map
                           (direct-callers graph project-vars insert-fns)
                           graph analysis)
        retractor-type-map (build-inserter-type-map
                            (direct-callers graph project-vars retract-fns)
                            graph analysis)
        infer-ctx (build-infer-ctx graph analysis inserter-type-map retractor-type-map get-source)
        annotations
        (into (sorted-map)
              (keep (fn [v]
                      (if-let [annotation (infer-annotation-for-var v infer-ctx)]
                        [v annotation]
                        (when (seq rules-filter)
                          [v {:clara-rules/no-output-types true}]))))
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
   or rulebase by:
   1. Extracting the namespaces and rule names from the session.
   2. Building a merged analysis map from the classpath (using the cache).

   Returns the merged clj-kondo analysis map.
   Consumers can feed this to `generate-annotations-from-analysis` with
   `extract-session-rule-names` to generate annotations.

   Options:
     :session-or-rulebase   - Clara session or rulebase (required)
     :include-ns-prefixes   - optional coll of ns prefix strings; passed to build-analysis-from-namespaces
     :exclude-ns-prefixes   - optional coll of ns prefix strings; passed to build-analysis-from-namespaces
     :cache-atom            - optional atom to use as cache; defaults to global-analysis-cache.
     :in-memory-sources     - optional map of {ns-symbol source-string} for dynamically
                              defined in-memory namespaces.
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              clara-rules hooks (see build-analysis-from-namespaces)."
  [{:keys [session-or-rulebase include-ns-prefixes exclude-ns-prefixes cache-atom in-memory-sources config-dir]
    :or {cache-atom global-analysis-cache
         config-dir @bundled-kondo-config-dir}}]
  (let [namespaces (extract-session-namespaces session-or-rulebase)]
    (build-analysis-from-namespaces
     (cond-> {:starting-namespaces namespaces
              :cache-atom cache-atom
              :in-memory-sources in-memory-sources
              :config-dir config-dir}
       include-ns-prefixes (assoc :include-ns-prefixes include-ns-prefixes)
       exclude-ns-prefixes (assoc :exclude-ns-prefixes exclude-ns-prefixes)))))

(defn generate-annotations-from-paths
  "Runs clj-kondo on the specified paths to generate an analysis map,
   then generates rule annotations from that analysis.

   Options:
     :paths        - paths to analyze (required)
     :rules-filter - optional coll of rule symbols to filter by; when nil,
                     all project vars are analyzed.
     :config-dir   - optional clj-kondo config dir; defaults to the bundled
                     clara-rules hooks. Pass nil to disable (falls back to
                     cwd-based `.clj-kondo` discovery).
     :include-ns-prefixes - optional coll of ns prefix strings; passed to build-analysis-from-namespaces
     :exclude-ns-prefixes - optional coll of ns prefix strings; passed to build-analysis-from-namespaces"
  [{:keys [paths rules-filter config-dir include-ns-prefixes exclude-ns-prefixes]
    :or {config-dir @bundled-kondo-config-dir}}]
  (let [initial-res (kondo/run! (cond-> {:lint paths
                                         :config {:analysis {:namespace-definitions true
                                                             :var-definitions true
                                                             :var-usages true
                                                             :java-class-usages true}}}
                                  config-dir (assoc :config-dir config-dir)))
        starting-namespaces (map :name (get-in initial-res [:analysis :namespace-definitions]))
        merged-analysis (if (seq starting-namespaces)
                          (build-analysis-from-namespaces
                           (cond-> {:starting-namespaces starting-namespaces
                                    :config-dir config-dir
                                    :initial-analysis (:analysis initial-res)
                                    :processed-namespaces (set starting-namespaces)}
                             include-ns-prefixes (assoc :include-ns-prefixes include-ns-prefixes)
                             exclude-ns-prefixes (assoc :exclude-ns-prefixes exclude-ns-prefixes)))
                          (:analysis initial-res))]
    (generate-annotations-from-analysis {:analysis merged-analysis :rules-filter rules-filter})))
