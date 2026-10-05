(ns clara.explorer.memory
  "Helpers for analyzing Clara Rules working memory."
  (:require [clara.explorer.vendor.tools.inspect :as inspect]
            [clara.rules.engine :as eng]
            [clara.rules.memory :as mem]
            [clara.rules.platform :as platform]
            [clara.explorer.conditions :as conditions]
            [clara.explorer.fact-types :as ft]
            [clara.explorer.serialize :as serialize]
            [clara.explorer.utils :as utils]
            [clojure.tools.logging :as log])
  (:import [clara.rules.engine
            ExpressionJoinNode
            HashJoinNode
            NegationNode
            NegationWithJoinFilterNode
            ProductionNode
            QueryNode
            RootJoinNode]))

(defn- deterministic-fact-str
  "Returns a deterministic pr-str representation of a fact for stable sorting.
   `prune-fn` strips functions/classes from the fact first (see
   `serialize/prune-fns`).  Uses pr-str-ordered vector forms with ::map / ::set
   markers instead of sorted-map/sorted-set (which require Comparable keys and
   fail on sets of maps or maps keyed by maps)."
  [fact prune-fn]
  (letfn [(canonicalize [x]
            (cond
              (map? x) (into [::map]
                             (utils/sort-by-key pr-str
                                                (map (fn [[k v]] [(canonicalize k) (canonicalize v)]) x)))
              (set? x) (into [::set]
                             (utils/sort-by-key pr-str (map canonicalize x)))
              (sequential? x) (mapv canonicalize x)
              :else x))]
    (pr-str (canonicalize (prune-fn fact)))))

(defn- extract-match-facts
  "Returns a sequence of actual facts involved in a match, skipping accumulator
   results which are not themselves facts in working memory."
  [{:keys [fact condition facts-accumulated] :as _match}]
  (cond
    (:accumulator condition) facts-accumulated
    (some? fact) [fact]))

(defn- get-production-order [rulebase]
  (->> (:productions rulebase)
       (map-indexed (fn [i p] [(:name p) i]))
       (into {})))

(defn- ->wrapped-fact-set
  [facts]
  (into #{}
        (map platform/fact-id-wrap)
        facts))

(defn- get-fact-type-order
  [{:keys [productions] :as _rulebase}]
  (into {}
        (comp (map #(conditions/normalize-lhs (:lhs %)))
              (mapcat conditions/extract-lhs-fact-types)
              (distinct)
              (map-indexed (comp vec reverse vector)))
        productions))

(defn- sort-facts
  [facts fact-type-fn fact-type-order prune-fn]
  (utils/sort-by-key
   (fn [wrapped]
     (let [fact (platform/fact-id-unwrap wrapped)
           ft (fact-type-fn fact)]
       [(get fact-type-order ft Integer/MAX_VALUE)
        (str ft)
        (deterministic-fact-str fact prune-fn)]))
   facts))

(defn- ->id-map [sorted-facts]
  (let [id-map (java.util.IdentityHashMap.)]
    (doseq [[i wrapped] (map-indexed vector sorted-facts)]
      (.put id-map (platform/fact-id-unwrap wrapped) (inc i)))
    id-map))

(defn- ->production-order-key-fn
  [production-order]
  (fn [{p-name :name :as _production-meta}]
    (get production-order p-name Integer/MAX_VALUE)))

(defn- ->production-dep
  "ProductionDep for a rule or query map (see
   `clara.explorer.server.api/ProductionDep`).  `:ns` is derived via
   `serialize/production-ns-name-sym`, which falls back to the production's
   fully-qualified `:name` when `:ns-name` is absent (queries)."
  [production p-type]
  {:name (:name production)
   :id   (-> production :name str serialize/route-id)
   :ns   (-> production serialize/production-ns-name-sym str)
   :type p-type})

(defn- ->fact-id-dep-pairs->index
  "Folds `[fact-id dep]` pairs into `{fact-id [dep …]}` with deps distinct and
   sorted by production order (then name, so equal-load-order query deps are
   deterministic).  nil fact-ids (facts the fact table cannot describe) are
   dropped."
  [pairs production-order-key-fn]
  (->> pairs
       (reduce (fn [acc [fact-id dep]]
                 (if (nil? fact-id)
                   acc
                   (update acc fact-id (fnil conj #{}) dep)))
               {})
       (reduce-kv (fn [acc fact-id deps]
                    (assoc acc fact-id (->> deps
                                            (sort-by (juxt production-order-key-fn :name))
                                            vec)))
                  {})))

(defn- ->supports-results-of-index
  "`{fact-id [ProductionDep]}` — queries whose current results the fact supports."
  [query-matches get-id production-order-key-fn]
  (->fact-id-dep-pairs->index
   (for [[query explanations] query-matches
         explanation explanations
         match (:matches explanation)
         fact (extract-match-facts match)
         :when fact]
     [(get-id fact) (->production-dep query "query")])
   production-order-key-fn))

(defn- ->supports-insertions-of-index
  "`{fact-id [ProductionDep]}` — rules whose activation includes the fact and
   whose recorded logical insertions still contain at least one retained fact
   (an activation whose only insertion was retracted with `retract!` supports
   nothing).  `fact-retained?` is `->fact-retained-pred`."
  [session get-id production-order-key-fn fact-retained?]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (->fact-id-dep-pairs->index
     (for [rule-node (:production-nodes rulebase)
           :let [rule (:production rule-node)
                 dep (->production-dep rule "rule")]
           token (keys (mem/get-insertions-all memory rule-node))
           :let [insertions (mapcat identity
                                    (mem/get-insertions memory rule-node token))]
           :when (some fact-retained? insertions)
           [fact _node-id] (:matches token)]
       [(get-id fact) dep])
     production-order-key-fn)))

(defn- insertion-id+rule-pairs
  "`([fact-id rule] …)`, one pair per insertion in `inspect`'s `:insertions`.

  Reads the per-rule insertion view so each pair is attached to the instance
  actually inserted. Facts with no id are dropped — `get-id` only knows facts
  that reached the fact table."
  [insertions get-id]
  (for [[rule rule-insertions] insertions
        {:keys [fact]} rule-insertions
        :let [id (get-id fact)]
        :when id]
    [id rule]))

(defn- ->origin
  [p]
  (->production-dep p "rule"))

(defn- ->origin-map
  "`{fact-id [origin …]}` — the rules that inserted each fact."
  [insertions get-id production-order-key-fn]
  (->> (insertion-id+rule-pairs insertions get-id)
       (reduce (fn [acc [id rule]]
                 (update acc id (fnil conj []) rule))
               {})
       (reduce-kv (fn [acc id rules]
                    (assoc acc id (->> rules
                                       (map ->origin)
                                       distinct
                                       (sort-by production-order-key-fn)
                                       vec)))
                  {})))

(defn- ->fact-table
  [{:keys [sorted-facts
           fact-type-fn
           root-facts
           get-fact-id
           origin-map
           supports-insertions-of-index
           supports-results-of-index
           matches-condition-of-index
           blocks-condition-of-index
           blocking-candidate-of-index
           known-set
           prune-fn]}]
  (let [raw-types (reduce (fn [acc wrapped]
                            (let [fact (platform/fact-id-unwrap wrapped)]
                              (assoc acc (get-fact-id fact) (fact-type-fn fact))))
                          {}
                          sorted-facts)
        facts (into {}
                    (map (fn [wrapped]
                           (let [fact (platform/fact-id-unwrap wrapped)
                                 id (get-fact-id fact)
                                 raw-type (get raw-types id)
                                 _ (when (nil? raw-type)
                                     (let [rule-names (into #{}
                                                            (keep :name)
                                                            (get origin-map id []))]
                                       (log/warnf "fact-type-fn returned nil for fact %s — inserted by rules: %s — substituting :clara.explorer.analyze/unknown-fact-type"
                                                  (pr-str (prune-fn fact))
                                                  (pr-str rule-names))))
                                 type-name (->> (or raw-type
                                                    :clara.explorer.analyze/unknown-fact-type)
                                                (serialize/serialize-fact-type nil))]
                             [id {:id id
                                  :type {:name type-name
                                         :id (serialize/route-id type-name)
                                         ;; Honest membership check: a session fact type is
                                         ;; `known` iff the analysis has that serialized
                                         ;; name among its fact types.  Runtime-derived
                                         ;; types absent from the analysis (e.g.
                                         ;; clojure.lang.Symbol from a dynamic insert)
                                         ;; are `known: false` — the UI must not link to a
                                         ;; /fact-types/:id route the analysis cannot
                                         ;; serve.
                                         :known (contains? known-set type-name)}
                                  :ns (ft/get-raw-type-ns raw-type)
                                  :data (prune-fn fact)
                                  :is-root (boolean (some #(identical? fact %) root-facts))
                                  :inserted-from (get origin-map id [])
                                  :supports-insertions-of (get supports-insertions-of-index id [])
                                  :supports-results-of (get supports-results-of-index id [])
                                  :matches-condition-of (get matches-condition-of-index id [])
                                  :blocks-condition-of (get blocks-condition-of-index id [])
                                  :blocking-candidate-of (get blocking-candidate-of-index id [])}])))
                    sorted-facts)]
    {:facts facts
     :raw-types raw-types}))

(defn- group-instances-by-role
  "Groups instances of a fact type by a production relation (`role-key`).
   `:inserted-from` substitutes a root group when a fact has no origin; every
   other role reads the fact's `role-key` vector directly (empty → no group)."
  [instances role-key production-order-key-fn]
  (->> (for [inst instances
             role (if (= role-key :inserted-from)
                    (let [origins (:inserted-from inst)]
                      (if (empty? origins)
                        [{:name "Root Facts (External)" :type "root"}]
                        origins))
                    (get inst role-key))]
         (assoc role :fact inst))
       (group-by (juxt :name :type))
       (map (fn [[[name type] items]]
              (let [first-item (first items)]
                (cond-> {:name name
                         :id (serialize/route-id (str name))
                         :type type
                         :facts (mapv :fact (sort-by (comp :id :fact) items))}
                  (:ns first-item) (assoc :ns (:ns first-item))))))
       (sort-by (fn [entry]
                  (if (= "root" (:type entry))
                    -1
                    (production-order-key-fn entry))))
       vec))

(def ^:private session-relation-role-keys
  "Production-relation role keys on a SessionFact, in fact-type-grouping order.
   `:inserted-from` is special-cased for root groups; every other key groups by
   the fact's own `role-key` vector."
  [:inserted-from
   :supports-insertions-of
   :supports-results-of
   :matches-condition-of
   :blocks-condition-of
   :blocking-candidate-of])

(defn- ->fact-type-index
  [fact-table production-order-key-fn]
  (letfn [(role-groups [instances role-key]
            (group-instances-by-role instances role-key production-order-key-fn))
          (add-fact-type-instance-data [m fact-type-name instances]
            (assoc m fact-type-name
                   (into {:name fact-type-name
                          :id (serialize/route-id fact-type-name)
                          :ns (:ns (first instances))
                          :count (count instances)
                          :ids (mapv :id instances)}
                         (map (fn [role-key]
                                [role-key (role-groups instances role-key)]))
                         session-relation-role-keys)))]
    (->> (vals fact-table)
         (group-by (comp :name :type))
         (reduce-kv add-fact-type-instance-data {}))))

(defn- join-node?
  [node]
  (or (instance? RootJoinNode node)
      (instance? HashJoinNode node)
      (instance? ExpressionJoinNode node)))

(defn- negation-node?
  [node]
  (or (instance? NegationNode node)
      (instance? NegationWithJoinFilterNode node)))

(defn- terminal-node->production-dep
  "ProductionDep for a ProductionNode/QueryNode, or nil for other nodes."
  [node]
  (cond
    (instance? ProductionNode node) (->production-dep (:production node) "rule")
    (instance? QueryNode node) (->production-dep (:query node) "query")))

(defn- descendant-production-deps
  "ProductionDeps for every terminal node reachable from `node` through `:children`."
  [node]
  (->> node
       (tree-seq (comp seq :children) :children)
       (keep terminal-node->production-dep)))

(defn- element-memory-facts
  "Wrapped facts held by a join/negation node's element memory."
  [memory node]
  (map (comp platform/fact-id-wrap :fact)
       (mem/get-elements-all memory node)))

(defn- present-facts-wrapped
  "Set of wrapped facts held by a join/negation node's element memory — the
   facts currently in working memory."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (into #{}
          (mapcat (fn [[_id node]]
                    (when (or (join-node? node) (negation-node? node))
                      (element-memory-facts memory node))))
          (:id-to-node rulebase))))

(defn- alpha-accepts-fact?
  "True when at least one alpha node's activation accepts `fact` (returns bindings)."
  [get-alphas-fn fact]
  (boolean
   (some (fn [[alpha-nodes _]]
           (some (fn [node]
                   (when-let [bindings ((:activation node) fact (:env node))]
                     bindings))
                 alpha-nodes))
         (get-alphas-fn [fact]))))

(defn- ->fact-retained-pred
  "Returns a predicate: true when a fact is still in working memory — held by
   element memory (present) or accepted by no alpha node (presence undecidable,
   so kept).  False only when absent from element memory yet some alpha node
   would accept it — i.e. it was retracted."
  [get-alphas-fn present-facts]
  (fn [fact]
    (or (contains? present-facts (platform/fact-id-wrap fact))
        (not (alpha-accepts-fact? get-alphas-fn fact)))))

(defn- insertion-record-facts-wrapped
  "Set of wrapped facts named by an insertion record (`inspect`'s `:insertions`)."
  [insertions]
  (into #{}
        (map platform/fact-id-wrap)
        (for [[_rule rule-insertions] insertions
              {:keys [fact]} rule-insertions]
          fact)))

(defn- filter-retracted-facts
  "Drops from `all-facts` the facts an insertion record still names but that
   are no longer in working memory: an RHS `retract!` removes the fact from
   alpha memory but leaves the inserting production's record in place.
   Facts not named by any insertion record are kept regardless."
  [all-facts insertion-facts fact-retained?]
  (into []
        (remove (fn [fact]
                  (and (contains? insertion-facts (platform/fact-id-wrap fact))
                       (not (fact-retained? fact)))))
        all-facts))

(defn- negation-element-blocks?
  "True when `element` blocks at least one waiting token at `node`:
   no join bindings → any waiting token; joined on bindings → a token sharing
   the node's join bindings; join filter → a token sharing join bindings for
   which `join-filter-fn` returns bindings."
  [node join-filter-fn env element token-groups]
  (let [binding-keys (:binding-keys node)
        join-bindings (select-keys (:bindings element) binding-keys)]
    (boolean
     (some (fn [token]
             (if join-filter-fn
               (boolean (join-filter-fn token (:fact element) (:bindings element) env))
               true))
           (get token-groups join-bindings)))))

(defn- ->node-relation-pairs
  "Walks `:id-to-node` and returns `{:matches-condition-of [pairs]
   :blocks-condition-of [pairs] :blocking-candidate-of [pairs]}` — each pair
   `[fact-id dep]`.  Join-node elements contribute to `:matches-condition-of`;
   negation-node elements contribute to `:blocks-condition-of` or
   `:blocking-candidate-of`."
  [id-to-node memory get-id]
  (reduce
   (fn [acc [_id node]]
     (cond
       (join-node? node)
       (let [deps (descendant-production-deps node)]
         (reduce (fn [acc element]
                   (let [fact-id (get-id (:fact element))]
                     (if (nil? fact-id)
                       acc
                       (update acc :matches-condition-of
                               into (for [dep deps] [fact-id dep])))))
                 acc
                 (mem/get-elements-all memory node)))

       (negation-node? node)
       (let [deps (descendant-production-deps node)
             join-filter-fn (:join-filter-fn node)
             env (get-in node [:condition :env])
             binding-keys (:binding-keys node)
             token-groups (group-by #(select-keys (:bindings %) binding-keys)
                                    (mem/get-tokens-all memory node))]
         (reduce
          (fn [acc element]
            (let [fact-id (get-id (:fact element))
                  relation (if (negation-element-blocks? node
                                                         join-filter-fn
                                                         env
                                                         element
                                                         token-groups)
                             :blocks-condition-of
                             :blocking-candidate-of)]
              (if (nil? fact-id)
                acc
                (update acc relation into (for [dep deps] [fact-id dep])))))
          acc
          (mem/get-elements-all memory node)))

       :else acc))
   {:matches-condition-of []
    :blocks-condition-of []
    :blocking-candidate-of []}
   id-to-node))

(defn- ->node-relation-maps
  "`{:matches-condition-of {fact-id [dep]} :blocks-condition-of …
   :blocking-candidate-of …}` from `->node-relation-pairs`, deps distinct and
   production-order sorted."
  [id-to-node memory get-id production-order-key-fn]
  (let [pairs (->node-relation-pairs id-to-node memory get-id)]
    {:matches-condition-of (->fact-id-dep-pairs->index (:matches-condition-of pairs)
                                                       production-order-key-fn)
     :blocks-condition-of (->fact-id-dep-pairs->index (:blocks-condition-of pairs)
                                                      production-order-key-fn)
     :blocking-candidate-of (->fact-id-dep-pairs->index (:blocking-candidate-of pairs)
                                                        production-order-key-fn)}))

(defn- ->id-name-index
  "Reverse index {route-id(name) → name} for a collection of serialized
   names, asserting id uniqueness (a route-id collision throws loudly at
   memory-analysis-build time rather than silently mislinking)."
  [names]
  (reduce (fn [idx name]
            (let [id (serialize/route-id (str name))]
              (if (nil? id)
                ;; route-id warned; skip this entry
                idx
                (if-let [existing (get idx id)]
                  (throw (ex-info (format "Session route-id collision: %s and %s both map to %s"
                                          existing name id)
                                  {:id id :names [existing name]}))
                  (assoc idx id name)))))
          {}
          names))

(defn- explanations->fact-match-data
  "`[{:fact clara.explorer.server.api/SessionFact :bindings [binding-map …]}]` —
   one entry per matched fact, carrying every distinct binding set it matched
   under.  A fact that
   satisfies several conditions of one activation (duplicate pairs) or several
   activations (distinct bindings) appears once; ids the fact table cannot
   describe are dropped, matching `:inserted-facts`.

   Rows are sorted by fact id.  Binding sets are deduplicated by value and,
   when a fact has more than one, sorted by a deterministic string of the
   (already-pruned) map so memory-analyses are byte-stable; a single binding set is
   emitted unsorted since its order is trivial.  `bindings` is pruned once per
   explanation and the touched fact ids deduplicated first, so a fact
   satisfying several conditions of one activation contributes a single pair
   and large accumulator bindings are not re-stringified per fact."
  [explanations fact-table get-fact-id prune-fn]
  (let [binding-sets (volatile! {})]
    (doseq [{:keys [bindings matches]} explanations
            :let [pruned-bindings (prune-fn bindings)
                  ids (into #{}
                            (comp (mapcat extract-match-facts)
                                  (keep get-fact-id))
                            matches)]]
      (doseq [id ids]
        (vswap! binding-sets update id (fnil conj #{}) pruned-bindings)))
    (->> @binding-sets
         (keep (fn [[id binding-set]]
                 (when-let [fact (get fact-table id)]
                   {:fact fact
                    :bindings (if (= 1 (count binding-set))
                                (vec binding-set)
                                (vec (utils/sort-by-key
                                      #(deterministic-fact-str % identity)
                                      binding-set)))})))
         (sort-by (comp :id :fact))
         vec)))

(defn- ->rule-match-index
  "`{production-name {:matches [...] :inserted-facts [...]}}`.

  Reads inserted-fact attribution from `:insertions` via
  `insertion-id+rule-pairs`. The fact-table lookup uses `keep` so a fact the
  table cannot describe is absent from `:inserted-facts`, not present as nil."
  [rule-matches
   insertions
   fact-table
   get-fact-id
   prune-fn]
  (let [rule-to-inserted-fact-ids
        (->> (insertion-id+rule-pairs insertions get-fact-id)
             (group-by (comp :name second))
             (reduce-kv (fn [m p-name pairs]
                          (assoc m p-name
                                 (into []
                                       (comp (map first) (distinct))
                                       pairs)))
                        {}))

        p-name->inserted-facts (fn [p-name]
                                 (into []
                                       (keep #(get fact-table %))
                                       (get rule-to-inserted-fact-ids p-name)))]

    (into {}
          (map (fn [[{p-name :name :as _rule} explanations]]
                 [p-name {:matches (explanations->fact-match-data explanations
                                                                  fact-table
                                                                  get-fact-id
                                                                  prune-fn)
                          :inserted-facts (p-name->inserted-facts p-name)}]))
          rule-matches)))

(defn- ->query-match-index
  [query-matches
   fact-table
   get-fact-id
   prune-fn]
  (into {}
        (map (fn [[{p-name :name} explanations]]
               [p-name {:matches (explanations->fact-match-data explanations
                                                                fact-table
                                                                get-fact-id
                                                                prune-fn)}]))
        query-matches))

(defn ->memory-analysis
  "Return a memory-analysis of the given `session`'s working memory. This includes details of all
  facts in the memory and information about rule/query matches for those facts.

  Two-arity takes the rulebase-analysis's serialized fact-type names (`known-set`);
  session `clara.explorer.server.api/TypeReference` `known` flags are honest
  membership checks against it
  (runtime-derived types absent from the analysis are marked unknown).  The
  one-arity defaults to no known types."
  ([session]
   (->memory-analysis session #{}))
  ([session known-set]
   (let [{:keys [root-facts insertions query-matches rule-matches] :as inspection}
         (inspect/inspect session)

         {:keys [get-alphas-fn rulebase]} (eng/components session)
         {:keys [fact-type-fn]} (meta get-alphas-fn)
         {:keys [id-to-node]} rulebase
         memory (-> session eng/components :memory)

         production-order (get-production-order rulebase)
         fact-type-order (get-fact-type-order rulebase)
         production-order-key-fn (->production-order-key-fn production-order)

         present-facts (present-facts-wrapped session)
         fact-retained? (->fact-retained-pred get-alphas-fn present-facts)
         insertion-facts (insertion-record-facts-wrapped insertions)
         all-facts (filter-retracted-facts (:all-facts inspection)
                                           insertion-facts
                                           fact-retained?)
         all-facts-wrapped (->wrapped-fact-set all-facts)
         prune-fn (serialize/memoizing-prune-fns)
         sorted-facts (sort-facts all-facts-wrapped fact-type-fn fact-type-order prune-fn)
         id-map (->id-map sorted-facts)
         get-fact-id (fn get-fact-id [fact] (.get ^java.util.IdentityHashMap id-map fact))

         supports-insertions-of-index (->supports-insertions-of-index session
                                                                      get-fact-id
                                                                      production-order-key-fn
                                                                      fact-retained?)
         supports-results-of-index (->supports-results-of-index query-matches
                                                                get-fact-id
                                                                production-order-key-fn)
         node-relations (->node-relation-maps id-to-node
                                              memory
                                              get-fact-id
                                              production-order-key-fn)
         origin-map (->origin-map insertions
                                  get-fact-id
                                  production-order-key-fn)

         fact-table (->fact-table {:sorted-facts sorted-facts
                                   :fact-type-fn fact-type-fn
                                   :root-facts root-facts
                                   :get-fact-id get-fact-id
                                   :origin-map origin-map
                                   :supports-insertions-of-index supports-insertions-of-index
                                   :supports-results-of-index supports-results-of-index
                                   :matches-condition-of-index (:matches-condition-of node-relations)
                                   :blocks-condition-of-index (:blocks-condition-of node-relations)
                                   :blocking-candidate-of-index (:blocking-candidate-of node-relations)
                                   :known-set known-set
                                   :prune-fn prune-fn})
         fact-type-index (->fact-type-index (:facts fact-table)
                                            production-order-key-fn)
         rule-match-index (->rule-match-index rule-matches
                                              insertions
                                              (:facts fact-table)
                                              get-fact-id
                                              prune-fn)
         query-match-index (->query-match-index query-matches
                                                (:facts fact-table)
                                                get-fact-id
                                                prune-fn)]
     {:fact-types        fact-type-index
      :facts             (:facts fact-table)
      ;; Internal fact-id → raw type index for the memory-derived annotation
      ;; boundary (add-memory-derived-insert-type-detections /
      ;; merge-memory-derived-insert-types):
      ;; session-derived types must merge into annotations as the raw objects
      ;; the analysis itself serializes — never as serialized name strings,
      ;; which would double-serialize (phantom string-kinded fact types).
      ;; Stripped from the served memory-analysis in api.clj.
      :fact-raw-types    (:raw-types fact-table)
      :origin            origin-map
      :rule-matches      rule-match-index
      :query-matches     query-match-index
      ;; Per-memory-analysis id→name indexes for the session detail handlers — built
      ;; here (no analysis-cache dependency) with the same id function over the
      ;; memory-analysis's serialized names, so session ids align with analysis ids.
      :fact-type-id-index (->id-name-index (keys fact-type-index))
      :rule-id-index      (->id-name-index (keys rule-match-index))
      :query-id-index     (->id-name-index (keys query-match-index))})))

(defn update-memory-analysis-known-set
  "Re-derives the per-fact :type :known flag of an existing memory-analysis from
   `known-set` (serialized fact-type names), without re-inspecting the session.
   Re-stamps every fact entry wherever it appears — :facts, the rule/query match
   indices, and the :fact-types production-relation role groupings — still
   O(total fact entries), far cheaper than a fresh memory-analysis.  Used by the
   cache build to reuse a memory-analysis produced during memory enrichment
   (which is built with an empty known-set)."
  [memory-analysis known-set]
  (letfn [(stamp [fact]
            (assoc-in fact [:type :known]
                      (contains? known-set (get-in fact [:type :name]))))
          (stamp-facts [facts] (mapv stamp facts))
          (stamp-match [match] (update match :fact stamp))
          (stamp-matches [matches] (mapv stamp-match matches))
          (stamp-role [role] (update role :facts stamp-facts))
          (stamp-roles [roles] (mapv stamp-role roles))]
    (-> memory-analysis
        (update :facts update-vals stamp)
        (update :rule-matches update-vals
                (fn [rm]
                  (-> rm
                      (update :matches stamp-matches)
                      (update :inserted-facts stamp-facts))))
        (update :query-matches update-vals
                (fn [qm]
                  (update qm :matches stamp-matches)))
        (update :fact-types update-vals
                (fn [entry]
                  (reduce (fn [e role-key]
                            (update e role-key stamp-roles))
                          entry
                          session-relation-role-keys))))))

(defn get-rule-activity
  "Returns a unified activity map for a rule: {:matches [...] :inserted-facts [...]}"
  [memory-analysis p-name]
  (get-in memory-analysis [:rule-matches p-name]))

(defn get-query-activity
  "Returns a unified activity map for a query: {:matches [...]}"
  [memory-analysis p-name]
  (get-in memory-analysis [:query-matches p-name]))

(defn get-node-elements
  "Returns all elements (facts) currently in the memory for the given node ID."
  [session node-id]
  (let [memory (-> session eng/components :memory)]
    (mem/get-elements-all memory {:id node-id})))

(defn get-node-tokens
  "Returns all tokens currently in the memory for the given node ID."
  [session node-id]
  (let [memory (-> session eng/components :memory)
        id-to-node (get-in (eng/components session) [:rulebase :id-to-node])
        node (get id-to-node node-id)]
    (mem/get-tokens-all memory node)))

