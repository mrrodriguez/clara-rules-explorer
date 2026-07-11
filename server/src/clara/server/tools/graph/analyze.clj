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

(def ^:private record-type-defining-macros
  #{'clojure.core/defrecord 'clojure.core/deftype
    'defrecord 'deftype})



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

(defn- extract-constructors-from-form
  ([form ns-sym ctx]
   (extract-constructors-from-form form ns-sym ctx #{}))
  ([form ns-sym {:keys [analysis get-source] :as ctx} visited]
   (cond
     (and (list? form) (seq form))
     (let [head (first form)
           head-results
           (cond
             (= head 'new)
             (let [clazz (second form)]
               (if (and (symbol? clazz) (resolve-record-type ns-sym clazz))
                 [(resolve-record-type ns-sym clazz)]
                 []))

             (symbol? head)
             (let [s (name head)]
               (cond
                 (or (str/starts-with? s "->")
                     (str/starts-with? s "map->"))
                 (if-let [r (resolve-record-type ns-sym head)] [r] [])

                 (str/ends-with? s ".")
                 (if-let [r (resolve-record-type ns-sym (symbol (namespace head) (subs s 0 (dec (count s)))))] [r] [])

                 (and (= s "new") (namespace head))
                 (if-let [r (resolve-record-type ns-sym (symbol (namespace head)))] [r] [])

                 :else
                 (if-let [v (try (ns-resolve (find-ns ns-sym) head) (catch Exception _ nil))]
                   (let [v-ns-sym (ns-name (:ns (meta v)))
                         v-name (:name (meta v))
                         v-fq (symbol (str v-ns-sym) (str v-name))]
                     (if (not (contains? visited v-fq))
                       (if-let [var-def (first (filter #(and (= (:ns %) v-ns-sym) (= (:name %) v-name)) (:var-definitions analysis)))]
                         (let [source (get-source v-ns-sym (:filename var-def))
                               body-str (extract-form-from-source source (:row var-def) (:col var-def) (:end-row var-def) (:end-col var-def))]
                           (if body-str
                             (extract-constructors-from-form
                              (try (read-string body-str) (catch Exception _ nil))
                              v-ns-sym
                              ctx
                              (conj visited v-fq))
                             []))
                         [])
                       []))
                   [])))
             :else [])]
       (vec (concat head-results (mapcat #(extract-constructors-from-form % ns-sym ctx visited) (rest form)))))

     (coll? form)
     (vec (mapcat #(extract-constructors-from-form % ns-sym ctx visited) form))

     :else [])))

(defn- extract-insert-types [reachable target-fns {:keys [analysis get-source]}]
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
                                       (try
                                         (let [form (read-string call-str)
                                               args (rest form)]
                                           (mapcat (fn [arg]
                                                     (let [statics (extract-constructors-from-form arg (:from u) {:analysis analysis :get-source get-source})]
                                                       (if (seq statics)
                                                         (map (fn [t] {:static t}) statics)
                                                         [{:dynamic {:source-str (pr-str arg)
                                                                     :ns-name-sym (:from u)
                                                                     :filename (:filename u)}}])))
                                                   args))
                                         (catch Exception _ []))
                                       []))))
                         (distinct))
                        usages)
        static-types (into #{} (keep :static) callsites)
        dynamic-forms (into [] (keep :dynamic) callsites)]
    {:static-types static-types
     :dynamic-forms (when (seq dynamic-forms) {:callsites dynamic-forms})}))


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
  retracts fact types. Returns nil when the var has no output side-effects."
  [var-name ctx]
  (let [{:keys [is-inserter? is-retractor? reachable]} (var-reachability var-name ctx)]
    (when (or is-inserter? is-retractor?)
      (let [inserts (when is-inserter? (extract-insert-types reachable (:insert-fns ctx) ctx))
            retracts (when is-retractor? (extract-insert-types reachable (:retract-fns ctx) ctx))]
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
  (let [;; Ensure namespaces are loaded into the runtime so that `ns-resolve`
        ;; and class resolution works for AST constructor extraction.
        _ (doseq [ns-sym (distinct (keep :ns (:var-definitions analysis)))]
            (try (require ns-sym) (catch Exception _ nil)))
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
        graph (build-graph analysis)
        project-vars (keys graph)

        direct-inserters (direct-callers graph project-vars insert-fns)
        direct-retractors (direct-callers graph project-vars retract-fns)

        var-seq (if (seq rules-filter)
                  (map normalize-key rules-filter)
                  project-vars)

        annotations
        (into (sorted-map)
              (keep (fn [v]
                      (if-let [annotation (infer-annotation-for-var
                                           v
                                           {:graph graph

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
