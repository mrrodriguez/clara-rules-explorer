(ns clara.explorer.memory.inspect
  "Working-memory inspection: reads a clara-rules session's Rete memory and
   reports what is actually in working memory and how facts participate in beta
   nodes.

   The output uses clara-rules' own production/query records and fact wrappers —
   no explorer concepts (ProductionDep, route ids, fact-type indexing, ordering)
   live here, so this namespace could move upstream.

   The single admission rule for user-visible facts is `fact-visible?`: nil and
   engine-internal ISystemFact instances are excluded from every fact-bearing
   result.  `get-all-facts` is the accurate \"facts currently in working
   memory\" view — a logical insertion whose fact has since been retracted is
   dropped."
  (:require [clara.rules.engine :as eng]
            [clara.rules.memory :as mem]
            [clara.rules.platform :as platform]
            [clojure.set :as set]
            [schema.core :as s])
  (:import [clara.rules.engine
            AccumulateNode
            AccumulateWithJoinFilterNode
            ISystemFact
            ProductionNode
            QueryNode
            RootJoinNode
            HashJoinNode
            ExpressionJoinNode
            NegationNode
            NegationWithJoinFilterNode]))

(s/defschema ConditionMatch
  "A condition's contribution to an activation or result token.  For an
   accumulator condition, `:fact` is the accumulated result and
   `:facts-accumulated` holds the facts it ran over; otherwise `:fact` is the
   matched fact.  `:condition` is the clara-rules condition structure."
  {:fact s/Any
   :condition s/Any
   (s/optional-key :facts-accumulated) [s/Any]})

(s/defschema Explanation
  "Why a rule or query matched: every condition's matches plus the binding map
   (generated bindings removed)."
  {:matches [ConditionMatch]
   :bindings {s/Keyword s/Any}})

(s/defschema InsertionEntry
  "One logical insertion: the inserted fact and the activation that produced it."
  {:explanation Explanation
   :fact s/Any})

(s/defschema RuleInsertionSupport
  "A fact supporting a rule activation with retained logical insertions: `:fact` is a wrapped fact
   and `:production` is the rule record."
  {:fact s/Any
   :production s/Any
   :type (s/enum "rule")})

(s/defschema QueryResultSupport
  "A fact supporting a current query result: `:fact` is a wrapped fact
   and `:production` is the query record."
  {:fact s/Any
   :production s/Any
   :type (s/enum "query")})

(s/defschema BetaNodeRelation
  "A fact linked to a terminal production through beta-node memory: `:fact` is a wrapped fact,
   `:production` is the rule/query record, and `:type` is its kind."
  {:fact s/Any
   :production s/Any
   :type (s/enum "rule" "query")})

(s/defschema BetaNodeRelations
  "Facts classified by how they participate in beta-node memory (join,
   negation, and accumulate nodes)."
  {:matches-condition-of [BetaNodeRelation]
   :blocks-condition-of [BetaNodeRelation]
   :blocking-candidate-of [BetaNodeRelation]})

(defn condition-match->facts
  "Facts a condition match is actually about: accumulator conditions contribute
   `:facts-accumulated` (the inputs), not the accumulated `:fact` result; every
   other condition contributes `:fact`."
  [{:keys [fact condition facts-accumulated] :as _match}]
  (cond
    (:accumulator condition) facts-accumulated
    (some? fact) [fact]))

(defn- fact-visible?
  "True when `fact` is user-visible: non-nil and not an engine internal
   (ISystemFact such as NegationResult).  This is the single admission rule for
   every fact-bearing result of this namespace.  nil is admitted by the engine
   but cannot be represented downstream (it is a singleton, so N nils cannot be
   told apart), so it is reported as none rather than one aliased ghost."
  [fact]
  (and (some? fact)
       (not (instance? ISystemFact fact))))

(defn- gen-binding?
  [[k _v]]
  (.startsWith (name k) "?__gen__"))

(defn- dissoc-gen-bindings
  [bindings]
  (into {} (remove gen-binding?) bindings))

(defn- ->match-condition
  "Condition structure for an explanation entry of `node`: accumulator metadata for
  accumulate nodes, type plus constraints otherwise."
  [{:keys [accum-condition condition] :as _node}]
  (if accum-condition
    (let [{:keys [accumulator from] :as _accum} accum-condition
          {:keys [type constraints original-constraints] :as _from} from]
      {:accumulator accumulator
       :from {:type type
              :constraints (or (seq original-constraints) constraints)}})
    (let [{:keys [type constraints original-constraints] :as _condition} condition]
      {:type type
       :constraints (or (seq original-constraints) constraints)})))

(defn- ->token-match
  "Single condition-match entry for `[fact node-id]` in `token`."
  [memory id-to-node token [fact node-id]]
  (let [node (id-to-node node-id)
        condition (->match-condition node)]
    (if (:accum-condition node)
      {:fact fact
       :condition condition
       :facts-accumulated (eng/token->matching-elements node memory token)}
      {:fact fact
       :condition condition})))

(defn- ->token-explanation
  "`Explanation` map for a single result/activation `token`."
  [memory id-to-node {:keys [matches bindings] :as token}]
  {:matches (mapv #(->token-match memory id-to-node token %) matches)
   :bindings (dissoc-gen-bindings bindings)})

(defn- tokens->explanations
  "Converts tokens to `Explanation` maps, one per token.  Accumulator conditions
   carry `:facts-accumulated` (the facts the accumulator ran over) in addition
   to `:fact` (the accumulated result)."
  [session tokens]
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [id-to-node]} rulebase]
    (mapv #(->token-explanation memory id-to-node %) tokens)))

;; --- Node taxonomy ---------------------------------------------------------

(defn- join-node?
  [node]
  (or (instance? RootJoinNode node)
      (instance? HashJoinNode node)
      (instance? ExpressionJoinNode node)))

(defn- negation-node?
  [node]
  (or (instance? NegationNode node)
      (instance? NegationWithJoinFilterNode node)))

(defn- accumulate-node?
  [node]
  (or (instance? AccumulateNode node)
      (instance? AccumulateWithJoinFilterNode node)))

(defn- production-node?
  [node]
  (instance? ProductionNode node))

(defn- query-node?
  [node]
  (instance? QueryNode node))

(defn- ->descendant-terminal-productions
  "Returns a vec of maps with `:production` and `:type` entries for the terminal (production/query)
  nodes reachable from `node` through `:children`."
  [node]
  (->> node
       (tree-seq (comp seq :children) :children)
       (keep (fn [n]
               (cond
                 (production-node? n) {:production (:production n) :type "rule"}
                 (query-node? n) {:production (:query n) :type "query"})))))

(defn- get-beta-node-elements
  "Element entries, maps with keys `:fact` and `:bindings`, held by a beta node's element memory."
  [memory node]
  (mem/get-elements-all memory node))

(defn- get-beta-node-tokens
  "Token entries held by a node's token memory."
  [memory node]
  (mem/get-tokens-all memory node))

(defn- ->accumulated-facts
  "Facts held in `node`'s accumulate memory (the accumulator `:from` inputs).
   `AccumulateNode` stores `[facts reduced]` pairs, so the facts are the pair's
   first element; `AccumulateWithJoinFilterNode` stores the candidate facts
   vector directly."
  [memory node]
  (for [{:keys [result]} (mem/get-accum-reduced-complete memory node)]
    (if (instance? AccumulateNode node)
      (first result)
      result)))

(defn- token->facts
  "Facts a token's condition matches are actually about: accumulator conditions
   contribute their `:from` inputs (via `eng/token->matching-elements`); every
   other condition contributes its own fact."
  [memory id-to-node {:keys [matches] :as token}]
  (into []
        (mapcat (fn [[fact node-id]]
                  (let [{:keys [accum-condition] :as node} (id-to-node node-id)]
                    (if accum-condition
                      (eng/token->matching-elements node memory token)
                      [fact]))))
        matches))

(defn- negation-element-blocks?
  "True when `element` blocks at least one waiting token at negation `node`. `token-groups` is the
  node's waiting tokens grouped by join bindings. A join-filter negation additionally requires the
  node's join filter to accept the pair."
  [{:keys [binding-keys join-filter-fn condition] :as _node}
   {:keys [bindings fact] :as _element}
   token-groups]
  (let [join-bindings (select-keys bindings binding-keys)
        {:keys [env]} condition]
    (->> join-bindings
         (get token-groups)
         (some (fn [token]
                 (or (not join-filter-fn)
                     (join-filter-fn token fact bindings env))))
         boolean)))

;; --- Working-memory accuracy ----------------------------------------------

(defn- ->wrapped-facts-from-alpha-memory
  "Wrapped user-visible facts held in alpha memory — the beta-node element
   memory of join and negation nodes."
  [{:keys [alpha-memory] :as _memory}]
  (->> alpha-memory
       vals
       (into #{}
             (comp (mapcat vals)
                   (mapcat identity)
                   (map :fact)
                   (filter fact-visible?)
                   (map platform/fact-id-wrap)))))

(defn- ->rule-insertion-record-facts
  "Wrapped user-visible facts named by `rule-node`'s logical insertion records."
  [memory rule-node]
  (->> rule-node
       (mem/get-insertions-all memory)
       keys
       (into [] (comp (mapcat #(mem/get-insertions memory rule-node %))
                      cat
                      (filter fact-visible?)
                      (map platform/fact-id-wrap)))))

(defn- ->insertion-record-facts
  "Wrapped user-visible facts named by a logical insertion record."
  [memory {:keys [production-nodes] :as _rulebase}]
  (into #{}
        (mapcat #(->rule-insertion-record-facts memory %))
        production-nodes))

(defn- ->wrapped-facts-from-accumulate-memory
  "Wrapped user-visible facts held in accumulate-node memory (the accumulator `:from` inputs)."
  [memory {:keys [id-to-node] :as _rulebase}]
  (set
   (for [node (vals id-to-node)
         :when (accumulate-node? node)
         fact-group (->accumulated-facts memory node)
         fact fact-group
         :when (fact-visible? fact)]
     (platform/fact-id-wrap fact))))

(defn- ->beta-memory-facts
  "Wrapped user-visible facts with definite presence in beta-node memory: join/negation element
  memory plus accumulate-node memory."
  [memory rulebase]
  (-> memory
      ->wrapped-facts-from-alpha-memory
      (set/union (->wrapped-facts-from-accumulate-memory memory rulebase))))

(defn- alpha-group-accepts-fact?
  "True when any alpha node in `alpha-nodes` accepts `fact`."
  [[alpha-nodes _token :as _alpha-group] fact]
  (->> alpha-nodes
       (some (fn [{:keys [activation env] :as _node}]
               (activation fact env)))
       boolean))

(defn- alpha-accepts-fact?
  "True when at least one alpha node's activation accepts `fact`."
  [get-alphas-fn fact]
  (->> [fact]
       get-alphas-fn
       (some #(alpha-group-accepts-fact? % fact))
       boolean))

(defn- ->fact-retained-pred
  "Returns `(fn [fact] bool)` — whether `fact` is still in working memory.
   `present-facts` is the precomputed set of wrapped facts with definite presence in
   beta-node memory (see `->beta-memory-facts`); callers compute it once and share it
   instead of recomputing it per predicate.  A fact is retained when present, or
   accepted by no alpha node (presence undecidable, so kept).  False only when absent
   from beta-node memory yet some alpha node would accept it — i.e. it was retracted."
  [get-alphas-fn present-facts]
  (fn [fact]
    (or (contains? present-facts (platform/fact-id-wrap fact))
        (not (alpha-accepts-fact? get-alphas-fn fact)))))

(defn- ->wrapped-all-facts
  "Accurate set of wrapped user-visible facts in working memory: the union of
   beta-node memory (join/negation element memory and accumulate-node memory)
   and logical insertion records, minus insertion-record facts that have since
   been retracted."
  [memory rulebase get-alphas-fn]
  (let [beta-facts (->beta-memory-facts memory rulebase)
        insertion-facts (->insertion-record-facts memory rulebase)
        fact-retained? (->fact-retained-pred get-alphas-fn beta-facts)
        union (set/union beta-facts insertion-facts)]
    (into #{} (remove (fn [wrapped]
                        (and (contains? insertion-facts wrapped)
                             (not (fact-retained? (platform/fact-id-unwrap wrapped))))))
          union)))

(s/defn get-all-facts :- [s/Any]
  "All user-visible facts currently in working memory."
  [session]
  (let [{:keys [get-alphas-fn memory rulebase]} (eng/components session)]
    (mapv platform/fact-id-unwrap (->wrapped-all-facts memory rulebase get-alphas-fn))))

(s/defn get-root-facts :- [s/Any]
  "All user-visible facts in working memory that were not inserted by a rule."
  [session]
  (let [{:keys [get-alphas-fn memory rulebase]} (eng/components session)
        wrapped-all (->wrapped-all-facts memory rulebase get-alphas-fn)
        insertion-facts (->insertion-record-facts memory rulebase)]
    (->> wrapped-all
         (into [] (comp (remove #(contains? insertion-facts %))
                        (map platform/fact-id-unwrap))))))

;; --- Production-level views ------------------------------------------------

(defn- ->rule-insertion-entries
  "Insertion entries for `rule-node`: one per user-visible logically inserted fact."
  [session memory rule-node]
  (->> rule-node
       (mem/get-insertions-all memory)
       keys
       (into [] (mapcat (fn [token]
                          (let [explanation (first (tokens->explanations session [token]))]
                            (->> token
                                 (mem/get-insertions memory rule-node)
                                 (into [] (comp (mapcat identity)
                                                (filter fact-visible?)
                                                (map (fn [insertion]
                                                       {:explanation explanation
                                                        :fact insertion})))))))))))

(s/defn get-insertions :- {s/Any [InsertionEntry]}
  "Per-rule logical insertions, one `InsertionEntry` per inserted fact
   (user-visible facts only)."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [production-nodes]} rulebase]
    (into {} (map (fn [{:keys [production] :as rule-node}]
                    [production (->rule-insertion-entries session memory rule-node)]))
          production-nodes)))

(s/defn get-rule-matches :- {s/Any [Explanation]}
  "Per-rule activation `Explanation`s — one per token with a recorded logical
   insertion."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [production-nodes]} rulebase]
    (into {} (map (fn [{:keys [production] :as rule-node}]
                    [production
                     (tokens->explanations session
                                           (keys (mem/get-insertions-all memory rule-node)))]))
          production-nodes)))

(s/defn get-query-matches :- {s/Any [Explanation]}
  "Per-query result `Explanation`s — one per current result token."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [query-nodes]} rulebase]
    (into {} (map (fn [[_query-name {:keys [query] :as query-node}]]
                    [query (tokens->explanations session
                                                 (mem/get-tokens-all memory query-node))]))
          query-nodes)))

(defn- rule-insertions-retained?
  "True when `rule-node`'s recorded insertions for `token` still hold a retained fact."
  [memory rule-node fact-retained? token]
  (->> (mem/get-insertions memory rule-node token)
       (into [] cat)
       (some fact-retained?)
       boolean))

(defn- ->rule-insertion-supports-for-node
  "Rule-insertion supports for one production node."
  [memory id-to-node fact-retained? {:keys [production] :as rule-node}]
  (->> rule-node
       (mem/get-insertions-all memory)
       keys
       (into [] (comp (filter #(rule-insertions-retained? memory rule-node fact-retained? %))
                      (mapcat #(token->facts memory id-to-node %))
                      (filter fact-visible?)
                      (map (fn [fact]
                             {:fact (platform/fact-id-wrap fact)
                              :production production
                              :type "rule"}))))))

(s/defn ->rule-insertion-supports :- [RuleInsertionSupport]
  "`RuleInsertionSupport` entries for facts in a rule activation whose recorded
   logical insertions still contain at least one retained fact.  Accumulator
   conditions contribute their `:from` inputs, matching `->query-result-supports`."
  [session]
  (let [{:keys [get-alphas-fn memory rulebase]} (eng/components session)
        {:keys [id-to-node production-nodes]} rulebase
        beta-facts (->beta-memory-facts memory rulebase)
        fact-retained? (->fact-retained-pred get-alphas-fn beta-facts)]
    (into []
          (mapcat #(->rule-insertion-supports-for-node memory
                                                       id-to-node
                                                       fact-retained?
                                                       %))
          production-nodes)))

(defn- ->query-result-supports-for-query
  "Query-result supports for one `[query explanations]` entry."
  [[query explanations]]
  (into []
        (comp (mapcat (fn [{:keys [matches] :as _explanation}] matches))
              (mapcat condition-match->facts)
              (filter fact-visible?)
              (map (fn [fact]
                     {:fact (platform/fact-id-wrap fact)
                      :production query
                      :type "query"})))
        explanations))

(s/defn ->query-result-supports :- [QueryResultSupport]
  "`QueryResultSupport` entries for facts that support a current query result — the
   facts in each result token's matches (accumulator inputs included)."
  [query-matches]
  (into []
        (mapcat ->query-result-supports-for-query)
        query-matches))

;; --- Beta-node relation classification -------------------------------------

(defn- ->beta-node-relations-for-fact
  "`BetaNodeRelation` entries pairing `fact` with each terminal `production`."
  [productions fact]
  (mapv (fn [{:keys [production type]}]
          {:fact (platform/fact-id-wrap fact)
           :production production
           :type type})
        productions))

(s/defn ->beta-node-relations :- BetaNodeRelations
  "Classifies each fact in a beta node's memory by how it participates
   (see `BetaNodeRelations`)."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (letfn [(handle-join-node [acc node]
              (let [productions (->descendant-terminal-productions node)]
                (->> node
                     (get-beta-node-elements memory)
                     (reduce (fn [acc {:keys [fact] :as _element}]
                               (update acc :matches-condition-of into (->beta-node-relations-for-fact productions fact)))
                             acc))))
            (handle-negation-node [acc {:keys [binding-keys] :as node}]
              (let [productions (->descendant-terminal-productions node)
                    token-groups (->> node
                                      (get-beta-node-tokens memory)
                                      (group-by (fn [{:keys [bindings] :as _token}]
                                                  (select-keys bindings binding-keys))))]
                (->> node
                     (get-beta-node-elements memory)
                     (reduce (fn [acc {:keys [fact] :as element}]
                               (let [relation (if (negation-element-blocks? node element token-groups)
                                                :blocks-condition-of
                                                :blocking-candidate-of)]
                                 (update acc relation into (->beta-node-relations-for-fact productions fact))))
                             acc))))
            (handle-accum-node [acc node]
              (let [productions (->descendant-terminal-productions node)]
                (transduce cat
                           (completing (fn [acc fact]
                                         (update acc :matches-condition-of
                                                 into (->beta-node-relations-for-fact productions fact))))
                           acc
                           (->accumulated-facts memory node))))]
      (->> rulebase
           :id-to-node
           (reduce
            (fn [acc [_id node]]
              (cond
                (join-node? node) (handle-join-node acc node)
                (negation-node? node) (handle-negation-node acc node)
                (accumulate-node? node) (handle-accum-node acc node)
                :else acc))
            {:matches-condition-of []
             :blocks-condition-of []
             :blocking-candidate-of []})))))
