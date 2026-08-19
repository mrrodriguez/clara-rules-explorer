(ns clara.server.tools.graph.core
  "Core logic for Clara rulebase static analysis and dependency graph
   construction.  Production-level logic (dep-graph, rule/query summaries)
   lives here; fact-type hierarchy bookkeeping and read-side accessors live
   in `clara.server.tools.graph.fact-types`."
  (:require [clara.rules.engine :as eng]
            [clara.rules.schema :as schema]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.fact-types :as ft]
            [clara.server.tools.graph.nodes :as nodes]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.utils :as utils]
            [clojure.string :as str]))

(defn- get-rulebase [session-or-rulebase]
  (if (satisfies? eng/ISession session-or-rulebase)
    (-> session-or-rulebase eng/components :rulebase)
    session-or-rulebase))

(defn working-memory-available?
  "True when `session-or-rulebase` is a live session with inspectable working memory."
  [session-or-rulebase]
  (satisfies? eng/ISession session-or-rulebase))

(defn extract-ancestors-fn
  "Returns the rulebase's ancestors-fn: the wrapped fn from `:get-alphas-fn`
   metadata when present (Clara's own, which filters internal system facts),
   else `clojure.core/ancestors`.  The fallback matches
   `clara.server.tools.graph.analyze/build-fallback-type-filter`; only a hand-built rulebase lacks the
   meta."
  [session-or-rulebase]
  (or (-> session-or-rulebase get-rulebase :get-alphas-fn meta :ancestors-fn)
      clojure.core/ancestors))

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
    (into []
          (comp (mapcat extract)
                (remove nil?)
                (distinct))
          lhs)))

(defn- ->memoized-ancestors
  "Returns a memoized fn mapping a raw fact type to its set of ancestor raw
   types.  Never returns nil: a nil or throwing ancestors-fn result degrades
   to the empty set.  Memoizing the set (not the seq) also removes the
   per-call `(set ...)` allocation from `downstream?`."
  [ancestors-fn]
  (memoize (fn [t]
             (try (set (ancestors-fn t))
                  (catch Exception _ #{})))))

(defn- downstream? [ancestors-set-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (contains? (ancestors-set-fn inserter-type) reader-type)))

(defn- matching-type-pairs
  "All (produced, consumed) raw-type pairs linking a producer to a consumer.
   Direct matches included (producer-type = consumer-type).  A pair whose
   producer-type is a retract type of the producer carries `:via :retract` so
   the UI can distinguish retraction coupling from production.  The dep-graph
   edge already exists, so this is computed only for actual edges — never in
   the O(n²) candidate loop."
  [{:keys [ancestors-set-fn produced-types consumed-types retract-types]}]
  (->> (for [pt produced-types
             ct consumed-types
             :when (downstream? ancestors-set-fn pt ct)]
         (cond-> {:producer-type pt :consumer-type ct}
           (contains? retract-types pt) (assoc :via :retract)))
       (distinct)))

(defn- get-production-deps-summary
  "Serializes the :upstream / :downstream production refs of `production-name`
   and attaches `:match` — the raw type pairs that link each adjacent
   production (each end serialized in its own production's ns context).
   `ctx` is the shared analysis context map (see `->rulebase-analysis`)."
  [production-name
   {:keys [dep-graph production-map type-analysis-map ancestors-set-fn known-set]}]
  (letfn [(->match [direction adjacent-name]
            ;; For an :upstream entry the adjacent production is the producer
            ;; and this production is the consumer; :downstream is the reverse.
            (let [[producer-name consumer-name] (if (= direction :upstream)
                                                  [adjacent-name production-name]
                                                  [production-name adjacent-name])
                  {:keys [produced-types retract-types] :as producer} (get type-analysis-map producer-name)
                  {:keys [consumed-types] :as consumer} (get type-analysis-map consumer-name)
                  producer-ns (:ns-name producer)
                  consumer-ns (:ns-name consumer)
                  pairs (matching-type-pairs {:ancestors-set-fn ancestors-set-fn
                                              :produced-types produced-types
                                              :consumed-types consumed-types
                                              :retract-types retract-types})]
              (when (seq pairs)
                (serialize/serialize-match {:raw-pairs pairs
                                            :known-set known-set
                                            :producer-ns producer-ns
                                            :consumer-ns consumer-ns}))))

          (serialize-deps [deps direction]
            (some->> deps
                     not-empty
                     sort
                     (mapv (fn [adjacent-name]
                             (let [dep (serialize/serialize-production-dep production-map
                                                                           adjacent-name)]
                               (if-let [match (->match direction adjacent-name)]
                                 (assoc dep :match match)
                                 dep))))))]
    (let [deps (get dep-graph production-name)]
      (-> deps
          (update :upstream #(serialize-deps % :upstream))
          (update :downstream #(serialize-deps % :downstream))
          (select-keys [:upstream :downstream])
          utils/remove-nil-vals))))

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

(defn- get-production-ns-name-sym
  [{p-name :name p-ns-name :ns-name}]
  (or p-ns-name
      (when-let [derived-ns-str (cond
                                  (string? p-name) (-> p-name symbol namespace)
                                  (symbol? p-name) (namespace p-name)
                                  (keyword? p-name) (namespace p-name))]
        (symbol derived-ns-str))))

(defn- production-summary
  "Builds a summary map for a single production (rule or query).
  When :ns-name is nil (as with queries in the underlying clara-rules schema),
  derives the namespace from the fully-qualified :name.

  `annotations` is the merged rule→annotation map (see
  annotations/merge-layers).  `ctx` is the shared analysis context map
  (annotations, dep-graph, production-map, type-analysis-map,
  ancestors-set-fn, known-set; see `->rulebase-analysis`).  `known-set` is the
  analysis's serialized fact-type names, used for
  `clara.server.graph.api/TypeReference` `known` flags.

  Form printing is controlled by the dynamic var `serialize/*form-printer*`."
  [{p-name :name :as production}
   {:keys [annotations dep-graph production-map known-set] :as ctx}]
  (let [ann (ann/production-annotation annotations production)
        ;; Queries in clara.rules.schema/Query have no :ns-name — derive it.
        p-ns-name (get-production-ns-name-sym production)
        serialize-type-ref (partial serialize/serialize-type-ref known-set p-ns-name)

        {:keys [upstream downstream]} (get-production-deps-summary p-name ctx)
        is-rule? (some? (:rhs production))
        unlinked? (and is-rule?
                       (not (:no-output-types ann))
                       (empty? (:insert-types ann))
                       (empty? (:retract-types ann)))
        source-rule? (rule-is-source? production dep-graph)
        sink-rule? (and (not unlinked?)
                        (not (:no-output-types ann))
                        (rule-is-sink? production dep-graph production-map))
        dynamic-inserts (some-> (:dynamic-insert-types-detected ann)
                                (serialize/serialize-dynamic-detection p-ns-name known-set (str p-name)))
        dynamic-retracts (some-> (:dynamic-retract-types-detected ann)
                                 (serialize/serialize-dynamic-detection p-ns-name known-set (str p-name)))
        summary
        (cond-> {:name      p-name
                 :id        (serialize/route-id (str p-name))
                 :ns        (str p-ns-name)
                 :doc       (:doc production)
                 :lhs-types (mapv serialize-type-ref (extract-lhs-fact-types (:lhs production)))
                 :props     (-> (or (:props production) {})
                                serialize/prune-fns
                                serialize/stringify-map-keys)
                 :lhs       (-> production :lhs
                                (serialize/serialize-lhs p-ns-name known-set)
                                serialize/prune-fns)
                 :lhs-form   (-> production :lhs
                                 serialize/serialize-lhs-form)
                 :notes     (:notes ann)}

          is-rule?
          (assoc :insert-types  (->> ann
                                     :insert-types
                                     (into []
                                           (comp (map serialize-type-ref)
                                                 (distinct))))
                 :retract-types (->> ann
                                     :retract-types
                                     (into []
                                           (comp (map serialize-type-ref)
                                                 (distinct))))
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
          (seq downstream) (assoc :downstream downstream)

          (some? dynamic-inserts) (assoc :dynamic-insert-types-detected dynamic-inserts)
          (some? dynamic-retracts) (assoc :dynamic-retract-types-detected dynamic-retracts))]
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

(defn ->type-analysis-map
  "Builds the per-production raw type analysis map used by the dep-graph and
   the serialized ancestors index: {:consumed-types [...] :produced-types
   [...] :retract-types <set> :ns-name <sym-or-nil>} per production name.
   `:produced-types` is `(into insert-types retract-types)` — it includes
   retracts; `:retract-types` keeps the retract subset so type-bridge matches
   can be flagged as retraction-based.  Each entry carries the production's
   ns-name (queries have no `:ns-name`; derived via
   `get-production-ns-name-sym`) so types can be serialized in per-production
   ns context later."
  [productions production-annotation-map]
  (into {}
        (map (fn [{p-name :name :keys [lhs] :as production}]
               (let [{:keys [insert-types retract-types]} (get production-annotation-map p-name)
                     upstream-types (extract-lhs-fact-types lhs)
                     retract-set (into #{}
                                       (remove nil?)
                                       retract-types)
                     produced-types (into retract-set
                                          (remove nil?)
                                          insert-types)]
                 [p-name {:consumed-types upstream-types
                          :produced-types produced-types
                          :retract-types retract-set
                          :ns-name (get-production-ns-name-sym production)}])))
        productions))

(defn ->production-id-index
  "Reverse index {id → name} for every rule and query in the analysis,
   asserting id uniqueness (a route-id collision throws loudly at
   analysis-build time rather than silently mislinking)."
  [analysis]
  (reduce (fn [idx {:keys [id name]}]
            (if-let [existing (get idx id)]
              (throw (ex-info (format "Production route-id collision: %s and %s both map to %s"
                                      existing name id)
                              {:id id :names [existing name]}))
              (assoc idx id name)))
          {}
          (concat (vals (:rules analysis))
                  (vals (:queries analysis)))))

(defn- ->consumers-by-type
  "Returns a map: consumed-type -> #{consumer-name ...}
   for every consumer in `type-analysis-map` that has at least one consumed type."
  [type-analysis-map]
  (let [idx (volatile! {})]
    (doseq [[consumer-name {:keys [consumed-types]}] type-analysis-map
            ct consumed-types]
      (vswap! idx update ct (fnil conj #{}) consumer-name))
    @idx))

(defn ->dep-graph
  "Builds the production dependency graph: {production-name {:upstream #{...}
   :downstream #{...}}} where an edge producer → consumer exists when some
   produced type of the producer satisfies some consumed type of the consumer
   directly or via the ancestors-fn hierarchy.

   The algorithm is linear in (produced types × hierarchy depth) plus the
   edges actually emitted: it builds a consumers-by-type index, then for each
   producer walks the ancestor chain of each produced type to find consumers,
   rather than evaluating every producer-consumer pair O(n²)."
  [type-analysis-map ancestors-set-fn]
  (let [consumers-by-type (->consumers-by-type type-analysis-map)
        graph (volatile! {})]
    (doseq [[producer-name {:keys [produced-types]}] type-analysis-map
            :when (seq produced-types)
            pt produced-types
            :let [consumers (reduce into #{}
                                    (keep consumers-by-type
                                          (cons pt (ancestors-set-fn pt))))]
            consumer-name consumers
            :when (not= producer-name consumer-name)]
      (vswap! graph
              (fn [g]
                (-> g
                    (update-in [producer-name :downstream]
                               (fnil conj #{})
                               consumer-name)
                    (update-in [consumer-name :upstream]
                               (fnil conj #{})
                               producer-name)))))
    @graph))

(defn- ->production-map
  "Builds name to production map for the `productions` while maintaining the insertion order."
  [productions]
  (->> productions
       (sequence (comp (map (juxt :name identity)) cat))
       (apply array-map)))

(defn- ->production-summary-map
  "Builds a summary map for the `productions` while maintaining the given load order.
   `ctx` is the shared analysis context map (see `->rulebase-analysis`)."
  [{:keys [production-type productions] :as ctx}]
  (let [filter-xf (case production-type
                    :rule (filter :rhs)
                    (remove :rhs))]
    ;; NOTE: preserve the order of `productions` since it is sorted in load order by the compiler.
    (->> productions
         (sequence
          (comp filter-xf
                (mapcat (juxt :name #(production-summary % ctx)))))
         (apply array-map))))

(defn- ->rule-summary-map
  [productions ctx]
  (->production-summary-map (assoc ctx :production-type :rule
                                   :productions productions)))

(defn- ->query-summary-map
  [productions ctx]
  (->production-summary-map (assoc ctx :production-type :query
                                   :productions productions)))

(defn- ->production-annotation-map
  [productions annotations]
  (into {}
        (for [p productions]
          [(:name p) (ann/production-annotation annotations p)])))

(defn- coerce-annotations-arg
  "Normalizes the annotations argument of `->rulebase-analysis`: a
   `ann.merge/MergedAnnotations` value passes through; a bare rule→annotation
   map is wrapped as merged content with no provenance."
  [x]
  (if (ann.merge/merged-annotations? x)
    x
    {:annotations (or x {}) :provenance {}}))

(defn- ->rulebase-analysis*
  "Implementation of `->rulebase-analysis`.  Callers go through the public
   multi-arity `->rulebase-analysis`, which manages the `*form-printer*`
   dynamic binding."
  [session-or-rulebase annotations]
  (let [{:keys [productions id-to-node] :as rulebase} (get-rulebase session-or-rulebase)

        annotations (:annotations (coerce-annotations-arg annotations))
        annotations (if (every? (comp string? key) annotations)
                      annotations
                      (ann/normalize-annotations annotations))

        production-annotation-map (->production-annotation-map productions annotations)

        ancestors-fn (extract-ancestors-fn rulebase)
        ancestors-set-fn (->memoized-ancestors ancestors-fn)
        type-analysis-map (->type-analysis-map productions production-annotation-map)
        known-set (ft/get-known-type-names type-analysis-map)
        ancestors-index (ft/->ancestors-index type-analysis-map ancestors-set-fn productions)

        dep-graph (->dep-graph type-analysis-map ancestors-set-fn)
        production-map (->production-map productions)

        ;; Shared context threaded through every production summary — the
        ;; per-production summary functions destructure what they need.
        analysis-ctx {:annotations annotations
                      :dep-graph dep-graph
                      :production-map production-map
                      :type-analysis-map type-analysis-map
                      :ancestors-set-fn ancestors-set-fn
                      :known-set known-set}

        rules (->rule-summary-map productions analysis-ctx)

        queries (->query-summary-map productions analysis-ctx)

        fact-types (ft/->fact-type-summary-map {:rules rules
                                                :queries queries
                                                :ancestors-index ancestors-index
                                                :known-set known-set})

        nodes (nodes/->nodes id-to-node)

        unresolved (into []
                         (keep (fn [p]
                                 (detect-unresolved p
                                                    (get production-annotation-map (:name p)))))
                         productions)
        analysis {:rules rules
                  :queries queries
                  :fact-types fact-types
                  :nodes nodes
                  :dep-graph dep-graph
                  :unresolved (vec unresolved)
                  :merged-annotations annotations}]
    (assoc analysis
           :fact-type-id-index (ft/->fact-type-id-index analysis)
           :production-id-index (->production-id-index analysis))))

(defn ->rulebase-analysis
  "Analyzes a rulebase against merged annotations.  `annotations` is either
   a `ann.merge/MergedAnnotations` value (`ann.merge/merge-layers` output —
   annotations and provenance are both used) or a bare rule→annotation map.

   This function is pure: the result depends only on the rulebase and the
   annotations argument.  It touches no working memory and no mutable state,
   so callers may safely cache the result keyed on (rulebase, annotations).
   The analysis map includes `:merged-annotations` — the normalized
   annotations used for computation — so a caller holding a cached analysis
   can test validity: `(= (:merged-annotations cached) current-annotations)`.

   `opts` is an optional map:
   - `:form-printer` — (fn [form] String) for serializing LHS/RHS forms.
     Defaults to `serialize/default-form-printer` (clojure.pprint).
     Pass `pr-str` for a cheap non-pretty-printing alternative."
  ([session-or-rulebase annotations]
   (->rulebase-analysis session-or-rulebase annotations nil))
  ([session-or-rulebase annotations opts]
   (let [form-printer (:form-printer opts)]
     (if form-printer
       (binding [serialize/*form-printer* form-printer]
         (->rulebase-analysis* session-or-rulebase annotations))
       (->rulebase-analysis* session-or-rulebase annotations)))))

(defn get-rulebase-counts
  "Returns a high-level summary of the rulebase counts using kebab-case keys."
  [analysis]
  {:rule-count (count (:rules analysis))
   :query-count (count (:queries analysis))
   :fact-type-count (count (:fact-types analysis))})

(defn get-rules-list
  "Returns a sequence of lightweight rule summaries, preserving load order.
   Omits :upstream and :downstream — they are only needed in the detail view
   and add significant payload weight at scale (3k+ rules)."
  [analysis]
  (mapv #(select-keys % [:name :id :ns :doc :lhs-types :insert-types :retract-types
                         :source-rule :sink-rule :unlinked-rule
                         :no-output-types
                         :dynamic-insert-types-detected
                         :dynamic-retract-types-detected])
        (vals (:rules analysis))))

(defn get-queries-list
  "Returns a sequence of lightweight query summaries, preserving load order.
   Omits :upstream and :downstream — they are only needed in the detail view."
  [analysis]
  (mapv #(select-keys % [:name :id :ns :doc :lhs-types :params])
        (vals (:queries analysis))))

(defn get-rulebase-analysis-external-view
  "Returns the analysis map stripped of internal implementation details
   (`:fact-type-id-index`, `:production-id-index`, `:merged-annotations`).
   Suitable for serialization to external consumers (e.g. the HTTP API)
   that should not depend on those details."
  [analysis]
  (dissoc analysis :fact-type-id-index :production-id-index :merged-annotations))


