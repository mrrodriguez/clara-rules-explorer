(ns clara.server.tools.graph.analyze
  "Static analysis tools for Clara rules using clj-kondo.
   Traces rule RHS call graphs to auto-detect insert/retract fact types."
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

(defn- normalize-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn- java-class? [class-str]
  (let [last-segment (last (str/split class-str #"\."))]
    (and (seq last-segment)
         (let [^Character first-char (first last-segment)]
           (Character/isUpperCase first-char)))))

(defn- constructor->fact-type
  "Maps a record constructor function name to its fully qualified record class symbol.
   Uses clojure.core/munge to handle namespace symbol munging."
  [ns name-str]
  (let [record-name (cond
                      (str/starts-with? name-str "map->") (subs name-str 5)
                      (str/starts-with? name-str "->") (subs name-str 2)
                      :else nil)]
    (when record-name
      (let [ns-pkg (munge (str ns))]
        (symbol (format "%s.%s" ns-pkg record-name))))))

(defn- build-graph [analysis]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if (and from-var (not= from-var 'nil))
              (let [caller (fq-name-sym from from-var)
                    callee (fq-name-sym to name)]
                (update acc caller (fnil conj #{}) callee))
              acc))
          {}
          (:var-usages analysis)))

(defn- build-constructors [analysis]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if (and from-var (not= from-var 'nil))
              (let [caller (fq-name-sym from from-var)
                    fact-type (constructor->fact-type to (str name))]
                (if fact-type
                  (update acc caller (fnil conj #{}) fact-type)
                  acc))
              acc))
          {}
          (:var-usages analysis)))

(defn- usage->fact-type [u]
  (if-let [method-name (:method-name u)]
    (cond
      (str/starts-with? method-name "map->")
      (let [record-name (subs method-name 5)]
        (symbol (format "%s.%s" (:class u) record-name)))

      (str/starts-with? method-name "->")
      (let [record-name (subs method-name 2)]
        (symbol (format "%s.%s" (:class u) record-name)))

      (= method-name "new")
      (when (java-class? (:class u))
        (symbol (:class u)))

      :else nil)
    (when (java-class? (:class u))
      (symbol (:class u)))))

(defn- build-java-constructors [analysis]
  (let [defs (:var-definitions analysis)
        java-usages (:java-class-usages analysis)]
    (reduce (fn [acc d]
              (let [caller (fq-name-sym (:ns d) (:name d))
                    start-row (:row d)
                    end-row (:end-row d)
                    def-filename (:filename d)]
                (if (and start-row end-row)
                  (let [contained-classes
                        (into #{}
                              (keep (fn [u]
                                      (when (and (= (:filename u) def-filename)
                                                 (:row u)
                                                 (<= start-row (:row u))
                                                 (<= (:row u) end-row)
                                                 (not (:import u))
                                                 (not= (:col u) (:name-col u)))
                                        (usage->fact-type u))))
                              java-usages)]
                    (if (seq contained-classes)
                      (update acc caller (fnil set/union #{}) contained-classes)
                      acc))
                  acc)))
            {}
            defs)))

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
;;
;; clj-kondo would normally load these hooks from a `.clj-kondo` dir discovered
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

(defn- extract-form-from-source [source-str row col end-row end-col]
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

(defn- extract-insert-args-from-call [call-str]
  (try
    (let [form (read-string call-str)
          args (rest form)]
      (mapv pr-str args))
    (catch Exception _
      [call-str])))

(defn- dynamic-forms-for-var [reachable target-fns {:keys [analysis get-source]}]
  (let [usages (:var-usages analysis)
        callsites (into []
                        (comp
                         (filter (fn [u]
                                   (let [caller (symbol (str (:from u)) (str (:from-var u)))]
                                     (and (contains? reachable caller)
                                          (not (contains? target-fns caller))
                                          (contains? target-fns (symbol (str (:to u)) (str (:name u))))))))
                         (mapcat (fn [u]
                                   (let [source (get-source (:from u) (:filename u))
                                         call-str (extract-form-from-source source (:row u) (:col u) (:end-row u) (:end-col u))]
                                     (if call-str
                                       (let [args (extract-insert-args-from-call call-str)]
                                         (map (fn [arg]
                                                {:source-str arg
                                                 :ns-name-sym (:from u)
                                                 :filename (:filename u)})
                                              args))
                                       []))))
                         (distinct))
                        usages)]
    (when (seq callsites)
      {:callsites callsites})))

(defn- var-reachability
  "For a given `var-name`, returns a map of:

  * :reachable - set of all transitively reachable vars

  * :is-inserter? - true if reachable set includes an insert fn or a direct inserter

  * :is-retractor? - true if reachable set includes a retract fn or a direct retractor

  * :types - set of fact types reachable through constructors"
  [var-name
   {:keys [graph constructors java-constructors
           insert-fns retract-fns
           direct-inserters direct-retractors]}]
  (let [reachable (transitive-reachability graph [var-name])
        is-inserter? (some #(or (contains? insert-fns %)
                                (contains? direct-inserters %))
                           reachable)
        is-retractor? (some #(or (contains? retract-fns %)
                                 (contains? direct-retractors %))
                            reachable)
        types (set/union (into #{} (mapcat constructors) reachable)
                         (into #{} (mapcat java-constructors) reachable))]
    {:reachable reachable
     :is-inserter? is-inserter?
     :is-retractor? is-retractor?
     :types types}))

(defn- infer-annotation-for-var
  "Returns an annotation map for the var referred to by the given `var-name` when it inserts or
  retracts fact types. Returns nil when the var has no output side-effects."
  [var-name ctx]
  (let [{:keys [is-inserter? is-retractor? types reachable]} (var-reachability var-name ctx)]
    (when (or is-inserter? is-retractor?)
      (cond-> {}
        is-inserter?
        (assoc :clara-rules/insert-types (vec (sort (map symbol types))))
        (and is-inserter? (empty? types))
        (assoc :clara-rules/dynamic-insert-types-detected
               (dynamic-forms-for-var reachable (:insert-fns ctx) ctx))
        is-retractor?
        (assoc :clara-rules/retract-types (vec (sort (map symbol types))))
        (and is-retractor? (empty? types))
        (assoc :clara-rules/dynamic-retract-types-detected
               (dynamic-forms-for-var reachable (:retract-fns ctx) ctx))))))

;;
;; API
;;

(defn clear-global-analysis-cache!
  "Clears the global analysis cache to prevent memory leaks or stale state."
  []
  (reset! global-analysis-cache {}))

(defn ns->resource-base
  "Mimics Clojure's core root-resource logic (without a leading slash)
   to map a namespace symbol to a base path."
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

(defn build-analysis-from-namespaces
  "Resolves transitive dependencies on the classpath for starting namespaces,
   optionally filtering by include-ns-prefixes, runs clj-kondo against them
   (using the cache), and returns a merged analysis map.

   Options:
     :starting-namespaces  - coll of namespace symbols to start from (required)
     :include-ns-prefixes   - optional coll of ns prefix strings; when nil,
                              all transitive dependencies are followed (no filtering).
     :cache-atom            - optional atom to use as cache; defaults to global-analysis-cache.
     :in-memory-sources     - optional map of {ns-symbol source-string} for dynamically
                              defined in-memory namespaces.
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              clara-rules hooks. Pass nil to disable (falls back to
                              cwd-based `.clj-kondo` discovery)."
  [{:keys [starting-namespaces include-ns-prefixes cache-atom in-memory-sources config-dir]
    :or {cache-atom global-analysis-cache
         config-dir @bundled-kondo-config-dir}}]
  (let [ns-matches-prefix? (if include-ns-prefixes
                             (fn [ns-sym]
                               (let [ns-str (str ns-sym)]
                                 (some #(str/starts-with? ns-str (str %)) include-ns-prefixes)))
                             (constantly true))]
    (loop [queue (set starting-namespaces)
           processed #{}
           merged-analysis {}]
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
  (let [graph (build-graph analysis)
        constructors (build-constructors analysis)
        java-constructors (build-java-constructors analysis)
        project-vars (keys graph)

        direct-inserters (direct-callers graph project-vars insert-fns)
        direct-retractors (direct-callers graph project-vars retract-fns)

        var-seq (if (seq rules-filter)
                  (map normalize-key rules-filter)
                  project-vars)

        source-cache (atom {})
        get-source (fn [ns-sym filename]
                     (let [k (or ns-sym filename)]
                       (if-let [cached (get @source-cache k)]
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
                           (swap! source-cache assoc k source)
                           source))))

        annotations
        (into (sorted-map)
              (keep (fn [v]
                      (if-let [annotation (infer-annotation-for-var
                                           v
                                           {:graph graph
                                            :constructors constructors
                                            :java-constructors java-constructors
                                            :insert-fns insert-fns
                                            :retract-fns retract-fns
                                            :direct-inserters direct-inserters
                                            :direct-retractors direct-retractors
                                            :analysis analysis
                                            :get-source get-source})]
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
     :cache-atom            - optional atom to use as cache; defaults to global-analysis-cache.
     :in-memory-sources     - optional map of {ns-symbol source-string} for dynamically
                              defined in-memory namespaces.
     :config-dir            - optional clj-kondo config dir; defaults to the bundled
                              clara-rules hooks (see build-analysis-from-namespaces)."
  [{:keys [session-or-rulebase include-ns-prefixes cache-atom in-memory-sources config-dir]
    :or {cache-atom global-analysis-cache
         config-dir @bundled-kondo-config-dir}}]
  (let [namespaces (extract-session-namespaces session-or-rulebase)]
    (build-analysis-from-namespaces
     {:starting-namespaces namespaces
      :include-ns-prefixes include-ns-prefixes
      :cache-atom cache-atom
      :in-memory-sources in-memory-sources
      :config-dir config-dir})))

(defn generate-annotations-from-paths
  "Runs clj-kondo on the specified paths to generate an analysis map,
   then generates rule annotations from that analysis.

   Options:
     :paths        - paths to analyze (required)
     :rules-filter - optional coll of rule symbols to filter by; when nil,
                     all project vars are analyzed.
     :config-dir   - optional clj-kondo config dir; defaults to the bundled
                     clara-rules hooks. Pass nil to disable (falls back to
                     cwd-based `.clj-kondo` discovery)."
  [{:keys [paths rules-filter config-dir]
    :or {config-dir @bundled-kondo-config-dir}}]
  (let [res (kondo/run! (cond-> {:lint paths
                                 :config {:analysis {:var-definitions true
                                                     :var-usages true
                                                     :java-class-usages true}}}
                          config-dir (assoc :config-dir config-dir)))]
    (generate-annotations-from-analysis {:analysis (:analysis res) :rules-filter rules-filter})))
