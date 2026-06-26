(ns clara.server.tools.graph.analyze
  "Static analysis tools for Clara rules using clj-kondo.
   Traces rule RHS call graphs to auto-detect insert/retract fact types."
  (:require [clj-kondo.core :as kondo]
            [clojure.string :as str]
            [clojure.set :as set]
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

(defn- fq-name-sym [ns name]
  (symbol (str ns) (str name)))

(defn- normalize-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn- java-class? [class-str]
  (let [last-segment (last (str/split class-str #"\."))]
    (and (seq last-segment)
         (Character/isUpperCase ^Character (first last-segment)))))

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
        (symbol (str ns-pkg "." record-name))))))

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

(defn- build-java-constructors [analysis]
  (let [defs (:var-definitions analysis)
        java-usages (:java-class-usages analysis)]
    (reduce (fn [acc d]
              (let [caller (fq-name-sym (:ns d) (:name d))
                    start-row (:row d)
                    end-row (:end-row d)]
                (if (and start-row end-row)
                  (let [contained-classes
                        (keep (fn [u]
                                (when (and (:row u)
                                           (<= start-row (:row u))
                                           (<= (:row u) end-row)
                                           (not (:import u))
                                           (not= (:col u) (:name-col u)))
                                  (if-let [method-name (:method-name u)]
                                    (cond
                                      (str/starts-with? method-name "map->")
                                      (let [record-name (subs method-name 5)]
                                        (symbol (str (:class u) "." record-name)))

                                      (str/starts-with? method-name "->")
                                      (let [record-name (subs method-name 2)]
                                        (symbol (str (:class u) "." record-name)))

                                      (= method-name "new")
                                      (when (java-class? (:class u))
                                        (symbol (:class u)))

                                      :else nil)
                                    (when (java-class? (:class u))
                                      (symbol (:class u))))))
                              java-usages)]
                    (if (seq contained-classes)
                      (update acc caller (fnil set/union #{}) (set contained-classes))
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
            next-vars (set (mapcat graph todo))
            unvisited (set/difference next-vars seen)]
        (recur seen unvisited)))))

(defn- extract-required-namespaces [analysis]
  (into []
        (comp (map :to)
              (distinct))
        (:namespace-usages analysis)))

(defn- analyze-ns-source [ns-sym resource-url]
  (let [source-code (slurp resource-url)
        extension (if (str/ends-with? (str resource-url) ".cljc") ".cljc" ".clj")
        resource-path (str (ns->resource-base ns-sym) extension)]
    (with-in-str source-code
      (kondo/run!
       {:lint ["-"]
        :lang :clj
        :filename resource-path
        :config {:analysis {:var-definitions true
                            :var-usages true
                            :java-class-usages true}}}))))

(defn- get-rulebase [session-or-rulebase]
  (if (instance? LocalSession session-or-rulebase)
    (-> session-or-rulebase eng/components :rulebase)
    session-or-rulebase))

(defn- direct-callers
  "Returns the set of vars in `vars` that directly call any function in `target-fns`
   according to the call `graph`."
  [graph vars target-fns]
  (into #{}
        (filter (fn [v] (some target-fns (get graph v))))
        vars))

(defn- var-reachability
  "For a given var, returns a map of:
     :reachable      - set of all transitively reachable vars
     :is-inserter?   - true if reachable set includes an insert fn or a direct inserter
     :is-retractor?  - true if reachable set includes a retract fn or a direct retractor
     :types          - set of fact types reachable through constructors"
  [v
   {:keys [graph constructors java-constructors
           insert-fns retract-fns
           direct-inserters direct-retractors]}]
  (let [reachable (transitive-reachability graph [v])
        is-inserter? (some #(or (contains? insert-fns %)
                                (contains? direct-inserters %))
                           reachable)
        is-retractor? (some #(or (contains? retract-fns %)
                                 (contains? direct-retractors %))
                            reachable)
        types (set/union (into #{} (mapcat constructors reachable))
                         (into #{} (mapcat java-constructors reachable)))]
    {:reachable reachable
     :is-inserter? is-inserter?
     :is-retractor? is-retractor?
     :types types}))

(defn- infer-annotation
  "Returns an annotation map for the given var when it inserts or retracts
   fact types. Returns nil when the var has no output side-effects."
  [v ctx]
  (let [{:keys [is-inserter? is-retractor? types]} (var-reachability v ctx)]
    (when (or is-inserter? is-retractor?)
      (cond-> {}
        is-inserter?
        (assoc :clara-rules/insert-types (vec (sort (map symbol types))))
        (and is-inserter? (empty? types))
        (assoc :clara-rules/dynamic-insert-types-detected true)
        is-retractor?
        (assoc :clara-rules/retract-types (vec (sort (map symbol types))))
        (and is-retractor? (empty? types))
        (assoc :clara-rules/dynamic-retract-types-detected true)))))

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
    (or (io/resource (str base-path ".clj"))
        (io/resource (str base-path ".cljc")))))

(defn build-analysis-from-namespaces
  "Resolves transitive dependencies on the classpath for starting namespaces,
   optionally filtering by include-ns-prefixes, runs clj-kondo against them
   (using the cache), and returns a merged analysis map.

   Options:
     :starting-namespaces  - coll of namespace symbols to start from (required)
     :include-ns-prefixes   - optional coll of ns prefix strings; when nil,
                              all transitive dependencies are followed (no filtering).
     :cache-atom            - optional atom to use as cache; defaults to global-analysis-cache."
  [{:keys [starting-namespaces include-ns-prefixes cache-atom]
    :or {cache-atom global-analysis-cache}}]
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
            (if-let [resource-url (find-ns-resource ns-sym)]
              (let [cached-entry (when cache-atom (get @cache-atom ns-sym))
                    analysis (if cached-entry
                               cached-entry
                               (let [res (:analysis (analyze-ns-source ns-sym resource-url))]
                                 (when cache-atom
                                   (swap! cache-atom assoc ns-sym res))
                                 res))
                    dependencies (->> (extract-required-namespaces analysis)
                                      (filter ns-matches-prefix?))]
                (recur (into remaining dependencies)
                       (conj processed ns-sym)
                       (merge-with into merged-analysis analysis)))
              ;; Resource not found on classpath, skip
              (recur remaining (conj processed ns-sym) merged-analysis))))))))

(defn generate-annotations-from-analysis
  "Generates rule annotations (insert/retract types etc.) from a pre-computed
   clj-kondo analysis map.

   Options:
     :analysis     - the clj-kondo analysis map (required)
     :rules-filter - optional coll of rule symbols to filter by; when nil,
                     all project vars are analyzed."
  [{:keys [analysis rules-filter]}]
  (let [graph (build-graph analysis)
        constructors (build-constructors analysis)
        java-constructors (build-java-constructors analysis)
        project-vars (keys graph)

        direct-inserters (direct-callers graph project-vars insert-fns)
        direct-retractors (direct-callers graph project-vars retract-fns)

        var-seq (if (seq rules-filter)
                  (map normalize-key rules-filter)
                  project-vars)

        annotations
        (into (sorted-map)
              (keep (fn [v]
                      (if-let [annotation (infer-annotation
                                           v
                                           {:graph graph
                                            :constructors constructors
                                            :java-constructors java-constructors
                                            :insert-fns insert-fns
                                            :retract-fns retract-fns
                                            :direct-inserters direct-inserters
                                            :direct-retractors direct-retractors})]
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
     :cache-atom            - optional atom to use as cache; defaults to global-analysis-cache."
  [{:keys [session-or-rulebase include-ns-prefixes cache-atom]
    :or {cache-atom global-analysis-cache}}]
  (let [namespaces (extract-session-namespaces session-or-rulebase)]
    (build-analysis-from-namespaces
     {:starting-namespaces namespaces
      :include-ns-prefixes include-ns-prefixes
      :cache-atom cache-atom})))

(defn generate-annotations-from-paths
  "Runs clj-kondo on the specified paths to generate an analysis map,
   then generates rule annotations from that analysis.

   Options:
     :paths        - paths to analyze (required)
     :rules-filter - optional coll of rule symbols to filter by; when nil,
                     all project vars are analyzed."
  [{:keys [paths rules-filter]}]
  (let [res (kondo/run! {:lint paths
                         :config {:analysis {:var-definitions true
                                             :var-usages true
                                             :java-class-usages true}}})]
    (generate-annotations-from-analysis {:analysis (:analysis res) :rules-filter rules-filter})))
