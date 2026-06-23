(ns clara.server.tools.graph.core
  "Core logic for Clara rulebase static analysis and dependency graph construction."
  (:require [clara.rules.engine :as eng]
            [clara.rules.schema :as schema]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.nodes :as nodes]
            [clojure.string :as str])
  (:import [clara.rules.engine LocalSession]))

(defn- remove-nil-vals
  "Returns the map `m` with all entries whose value is nil removed."
  [m]
  (->> m
       (reduce-kv (fn [m' k v]
                    (if (nil? v) (dissoc! m' k) m'))
                  (transient m))
       persistent!))

(defn- get-rulebase [session-or-rulebase]
  (if (instance? LocalSession session-or-rulebase)
    (-> session-or-rulebase eng/components :rulebase)
    session-or-rulebase))

(defn extract-lhs-fact-types
  "Recursively walks the LHS of a rule and extracts all fact types."
  [lhs]
  (let [extract (fn extract [condition]
                  (case (schema/condition-type condition)
                    :fact        [(:type condition)]
                    :accumulator (extract (:from condition))
                    (:and :or :not :exists) (mapcat extract (rest condition))
                    :test        []
                    []))]
    (->> lhs
         (mapcat extract)
         (distinct)
         (vec))))

(defn- downstream? [ancestors-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (and ancestors-fn
           (contains? (set (ancestors-fn inserter-type)) reader-type))))

(defn- get-production-deps-summary
  [dep-graph production-name production-map]
  (letfn [(serialize-deps [deps]
            (some->> deps
                     not-empty
                     sort
                     (mapv (partial serialize/serialize-production-dep production-map))))]
    (let [deps (get dep-graph production-name)]
      (-> deps
          (update :upstream serialize-deps)
          (update :downstream serialize-deps)
          (select-keys [:upstream :downstream])
          remove-nil-vals))))

(defn- rule-is-source?
  [{p-name :name :keys [rhs] :as _production} dep-graph]
  (and (some? rhs)
       (-> dep-graph
           (get p-name)
           :upstream
           empty?)))

(defn- rule-is-sink?
  [{p-name :name :keys [rhs] :as _production}
   dep-graph
   production-map]
  (letfn [(dep-is-rule? [d-name]
            (some? (:rhs (get production-map d-name))))]
    (and (some? rhs)
         (->> (get dep-graph p-name)
              :downstream
              (filter dep-is-rule?)
              empty?))))

(defn- rule-summary
  [production
   sidecar-annotations
   dep-graph
   production-map]
  (let [ann (ann/resolve-annotations production sidecar-annotations)
        {p-ns-name :ns-name p-name :name} production
        serialize-fact-type (partial serialize/serialize-fact-type p-ns-name)

        {:keys [upstream downstream]} (get-production-deps-summary dep-graph
                                                                   p-name
                                                                   production-map)
        is-rule? (some? (:rhs production))
        unlinked? (and is-rule?
                       (not (:no-output-types ann))
                       (empty? (:insert-types ann))
                       (empty? (:retract-types ann)))
        source-rule? (rule-is-source? production dep-graph)
        sink-rule? (and (not unlinked?) (rule-is-sink? production dep-graph production-map))
        summary
        (cond-> {:name               p-name
                 :ns                 (str p-ns-name)
                 :doc                (:doc production)
                 :lhs-types          (mapv serialize-fact-type (extract-lhs-fact-types (:lhs production)))
                 :props              (-> (or (:props production) {})
                                         serialize/prune-fns
                                         serialize/stringify-map-keys)
                 :lhs                (-> production :lhs
                                         serialize/prune-fns
                                         serialize/serialize-lhs)
                 :annotation-sources       (:annotation-sources ann)
                 :notes                    (:notes ann)}

          is-rule?
          (assoc :insert-types  (mapv serialize-fact-type (:insert-types ann))
                 :retract-types (mapv serialize-fact-type (:retract-types ann))
                 :rhs-form      (-> production
                                    :rhs
                                    serialize/prune-fns
                                    serialize/serialize-rhs-form)
                 :source-rule   source-rule?
                 :sink-rule     sink-rule?)

          unlinked?
          (assoc :unlinked-rule {:downstream :unknown
                                 :reason "no declared insert-types or retract-types"})

          (:no-output-types ann)
          (assoc :no-output-types true)

          (:params production)
          (assoc :params (serialize/stringify-idents-coll (:params production)))

          (seq upstream) (assoc :upstream upstream)
          (seq downstream) (assoc :downstream downstream))]
    summary))

(defn- detect-unresolved
  "Detects rules that likely have RHS effects but no declared types."
  [production ann]
  (let [rhs (str (:rhs production))]
    ;; NOTE: This is a hack to get started
    (when (and (or (str/includes? rhs "insert!")
                   (str/includes? rhs "insert-unconditional!")
                   (str/includes? rhs "retract!"))
               (empty? (:insert-types ann))
               (empty? (:retract-types ann)))
      {:rule (:name production)
       :reason "RHS likely contains insertion/retraction calls but no :clara-rules/insert-types or :clara-rules/retract-types declared."
       :hint "Add :clara-rules/insert-types to the rule's properties map or a sidecar annotation file."})))

(defn build-dep-graph
  [{:keys [get-alphas-fn productions] :as _rulebase}
   production-annotation-map]
  (let [{:keys [ancestors-fn]} (meta get-alphas-fn)]
    (letfn [(type-analysis [{p-name :name :keys [lhs] :as _production}]
              (let [{:keys [insert-types retract-types]} (get production-annotation-map p-name)
                    upstream-types (extract-lhs-fact-types lhs)
                    produced-types (->> insert-types set (into retract-types))]
                {:consumed-types upstream-types
                 :produced-types produced-types}))

            (some-type-consumed? [produced-types consumed-types]
              (->> produced-types
                   (some (fn [pt]
                           (some (fn [ct] (downstream? ancestors-fn pt ct))
                                 consumed-types)))
                   boolean))

            (add-dep-graph-entry [graph [producer-name consumer-name]]
              (-> graph
                  (update-in [producer-name :downstream]
                             (fnil conj #{})
                             consumer-name)
                  (update-in [consumer-name :upstream]
                             (fnil conj #{})
                             producer-name)))]

      (let [type-analysis-map (->> productions
                                   (into {} (map (juxt :name type-analysis))))

            producer-consumer-pairs
            (for [[p-name1 {produced-types1 :produced-types}] type-analysis-map
                  [p-name2 {consumed-types2 :consumed-types}] type-analysis-map
                  :when (and (not= p-name1 p-name2)
                             (seq produced-types1)
                             (seq consumed-types2)
                             (some-type-consumed? produced-types1 consumed-types2))]
              [p-name1 p-name2])

            graph (reduce add-dep-graph-entry {} producer-consumer-pairs)]

        graph))))

(defn- build-production-map
  "Builds name to production map for the `productions` while maintaining the insertion order."
  [productions]
  (->> productions
       (sequence (comp (map (juxt :name identity)) cat))
       (apply array-map)))

(defn- build-production-summary-map
  "Builds a summary map for the `productions` while maintaining the given load order."
  [{:keys [production-type
           productions
           sidecar-annotations
           dep-graph
           production-map]}]
  (let [filter-xf (case production-type
                    :rule (filter :rhs)
                    (remove :rhs))]
    ;; NOTE: preserve the order of `productions` since it is sorted in load order by the compiler.
    (->> productions
         (sequence
          (comp filter-xf
                (mapcat (juxt :name #(rule-summary %
                                                   sidecar-annotations
                                                   dep-graph
                                                   production-map)))))
         (apply array-map))))

(defn- build-rule-summary-map
  [productions sidecar-annotations dep-graph production-map]
  (build-production-summary-map {:production-type :rule
                                 :productions productions
                                 :sidecar-annotations sidecar-annotations
                                 :dep-graph dep-graph
                                 :production-map production-map}))

(defn- build-query-summary-map
  [productions sidecar-annotations dep-graph production-map]
  (build-production-summary-map {:production-type :query
                                 :productions productions
                                 :sidecar-annotations sidecar-annotations
                                 :dep-graph dep-graph
                                 :production-map production-map}))

(defn- build-fact-type-summary-map
  "Aggregates fact-type usage across rules and queries."
  [rules queries]
  (letfn [(init-summary [type-name]
            {:name type-name
             :used-by-rules []
             :used-by-queries []
             :inserted-by-rules []
             :retracted-by-rules []})

          (update-summary [acc type-name key production-name]
            (update acc type-name
                    (fn [summary]
                      (-> (or summary (init-summary type-name))
                          (update key conj production-name)))))

          (add-production-types [acc [p-name {:keys [lhs-types insert-types retract-types]}]]
            (let [is-rule? (contains? rules p-name)
                  used-key (if is-rule? :used-by-rules :used-by-queries)
                  updates (concat (for [t lhs-types] [t used-key])
                                  (for [t insert-types] [t :inserted-by-rules])
                                  (for [t retract-types] [t :retracted-by-rules]))]
              (reduce (fn [a [t k]] (update-summary a t k p-name)) acc updates)))]

    (let [summary-map (reduce add-production-types {} (concat rules queries))]
      (->> (concat rules queries)
           (sequence (comp (map val)
                           (mapcat (juxt :lhs-types :insert-types :retract-types))
                           cat
                           (distinct)))
           (mapcat (juxt identity summary-map))
           (apply array-map)))))

(defn- build-production-annotation-map
  [productions sidecar-annotations]
  (into {}
        (for [p productions]
          [(:name p) (ann/resolve-annotations p sidecar-annotations)])))

(defn rulebase-analysis
  [session-or-rulebase sidecar-annotations]
  (let [{:keys [productions id-to-node] :as rulebase} (get-rulebase session-or-rulebase)

        production-annotation-map (build-production-annotation-map productions sidecar-annotations)

        dep-graph (build-dep-graph rulebase production-annotation-map)
        production-map (build-production-map productions)

        rules (build-rule-summary-map productions
                                      sidecar-annotations
                                      dep-graph
                                      production-map)

        queries (build-query-summary-map productions
                                         sidecar-annotations
                                         dep-graph
                                         production-map)

        fact-types (build-fact-type-summary-map rules queries)

        nodes (nodes/build-nodes id-to-node)

        unresolved (into []
                         (keep (fn [p]
                                 (detect-unresolved p
                                                    (get production-annotation-map (:name p)))))
                         productions)]
    {:rules rules
     :queries queries
     :fact-types fact-types
     :nodes nodes
     :dep-graph dep-graph
     :unresolved (vec unresolved)}))

(defn rulebase-summary
  "Returns a high-level summary of the rulebase counts using kebab-case keys."
  [analysis]
  {:rule-count (count (:rules analysis))
   :query-count (count (:queries analysis))
   :fact-type-count (count (:fact-types analysis))})

(defn rules-list
  "Returns a sequence of lightweight rule summaries, preserving load order."
  [analysis]
  (mapv #(select-keys % [:name :ns :doc :lhs-types :insert-types :retract-types
                         :source-rule :sink-rule :unlinked-rule
                         :no-output-types :upstream :downstream])
        (vals (:rules analysis))))

(defn queries-list
  "Returns a sequence of lightweight query summaries, preserving load order."
  [analysis]
  (mapv #(select-keys % [:name :ns :doc :lhs-types :params :upstream :downstream])
        (vals (:queries analysis))))

(defn fact-types-list
  "Returns a sequence of lightweight fact type summaries, preserving order."
  [analysis]
  (mapv #(select-keys % [:name :used-by-rules :used-by-queries
                         :inserted-by-rules :retracted-by-rules])
        (vals (:fact-types analysis))))

(defn session-fact-types-summary
  "Returns a lightweight summary of fact types in the session and the total count."
  [snapshot]
  {:types (->> (:fact-types snapshot)
               vals
               (mapv #(select-keys % [:name :count])))
   :total-count (count (:facts snapshot))})

