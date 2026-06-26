(ns clara.server.tools.graph.analyze
  "Static analysis tools for Clara rules using clj-kondo.
   Traces rule RHS call graphs to auto-detect insert/retract fact types."
  (:require [clj-kondo.core :as kondo]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(defn- fq-name-sym [ns name]
  (symbol (str ns) (str name)))

(defn- normalize-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn constructor->fact-type
  "Maps a record constructor function name to its fully qualified record class symbol.
   E.g. clara.rules.facts/map->Application => clara.rules.facts.Application"
  [ns name-str]
  (let [record-name (cond
                      (str/starts-with? name-str "map->") (subs name-str 5)
                      (str/starts-with? name-str "->") (subs name-str 2)
                      :else nil)]
    (when record-name
      (let [ns-pkg (str/replace (str ns) "-" "_")]
        (symbol (str ns-pkg "." record-name))))))

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

(defn- transitive-reachability [graph start-vars]
  (loop [seen #{}
         todo (set start-vars)]
    (if (empty? todo)
      seen
      (let [seen (into seen todo)
            next-vars (set (mapcat graph todo))
            unvisited (set/difference next-vars seen)]
        (recur seen unvisited)))))

(defn analyze-rules
  "Runs clj-kondo on the specified paths, builds the call graph,
   and returns a map of rule FQ-name symbols to their inferred annotations."
  ([paths]
   (analyze-rules paths nil))
  ([paths rules-filter]
   (let [{:keys [analysis]} (kondo/run! {:lint paths
                                         :config {:analysis {:var-definitions true
                                                             :var-usages true}}})
         graph (build-graph analysis)
         constructors (build-constructors analysis)
         project-vars (keys graph)

         ;; Find all project vars that directly call insert/retract
         direct-inserters (into #{} (filter (fn [v] (some insert-fns (get graph v))) project-vars))
         direct-retractors (into #{} (filter (fn [v] (some retract-fns (get graph v))) project-vars))

         ;; For each project var, compute transitive insert/retract flag
         ;; and collected fact types.
         annotations
         (into {}
               (for [v project-vars
                     :let [reachable (transitive-reachability graph [v])
                           is-inserter? (some #(or (contains? insert-fns %) (contains? direct-inserters %)) reachable)
                           is-retractor? (some #(or (contains? retract-fns %) (contains? direct-retractors %)) reachable)
                           types (set (mapcat constructors reachable))]
                     :when (or is-inserter? is-retractor?)]
                 [v (cond-> {}
                      is-inserter? (assoc :clara-rules/insert-types (vec (sort (map symbol types))))
                      is-retractor? (assoc :clara-rules/retract-types (vec (sort (map symbol types)))))]))]
     ;; If rules-filter is provided, filter the results to only include those rules
     (if (seq rules-filter)
       (select-keys annotations (map normalize-key rules-filter))
       annotations))))

(defn merge-annotations
  "Merges existing annotations with generated annotations.
   Existing annotations take precedence, but empty or missing fields are populated.
   New rules in generated annotations are added."
  [existing generated]
  (let [normalized-existing (into {} (map (fn [[k v]] [(normalize-key k) [k v]]) existing))
        merged-normalized
        (reduce (fn [acc [rule-sym gen-val]]
                  (if-let [[orig-key orig-val] (get normalized-existing rule-sym)]
                    ;; Rule exists, merge properties
                    (let [merged-val (cond-> orig-val
                                       (and (not (contains? orig-val :clara-rules/insert-types))
                                            (seq (:clara-rules/insert-types gen-val)))
                                       (assoc :clara-rules/insert-types (:clara-rules/insert-types gen-val))

                                       (and (not (contains? orig-val :clara-rules/retract-types))
                                            (seq (:clara-rules/retract-types gen-val)))
                                       (assoc :clara-rules/retract-types (:clara-rules/retract-types gen-val))

                                       (and (not (contains? orig-val :clara-rules/no-output-types))
                                            (:clara-rules/no-output-types gen-val)
                                            (not (seq (:clara-rules/insert-types orig-val)))
                                            (not (seq (:clara-rules/retract-types orig-val))))
                                       (assoc :clara-rules/no-output-types true))]
                      (assoc acc orig-key merged-val))
                    ;; New rule, add it
                    (assoc acc rule-sym gen-val)))
                existing
                generated)]
    merged-normalized))

(defn write-annotations!
  "Writes the annotations map to the specified file path as pretty-printed EDN."
  [path annotations]
  (with-open [w (io/writer path)]
    (binding [*print-meta* true]
      (pp/pprint annotations w))))
