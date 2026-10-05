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

(s/defschema FactProduction
  "A fact and the production it relates to: `:fact` is a wrapped fact,
   `:production` is the rule/query record, and `:type` is its kind."
  {:fact s/Any
   :production s/Any
   :type (s/enum "rule" "query")})

(s/defschema NodeRelations
  "Facts classified by how they participate in beta-node memory (join,
   negation, and accumulate nodes)."
  {:matches-condition-of [FactProduction]
   :blocks-condition-of [FactProduction]
   :blocking-candidate-of [FactProduction]})

(defn match->facts
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
  (into {} (remove gen-binding? bindings)))

(defn- tokens->explanations
  "Converts tokens to `Explanation` maps, one per token.  Accumulator conditions
   carry `:facts-accumulated` (the facts the accumulator ran over) in addition
   to `:fact` (the accumulated result)."
  [session tokens]
  (let [{:keys [memory rulebase]} (eng/components session)
        id-to-node (:id-to-node rulebase)]
    (vec
     (for [{:keys [matches bindings] :as token} tokens]
       {:matches (vec
                  (for [[fact node-id] matches
                        :let [node (id-to-node node-id)
                              condition (if (:accum-condition node)
                                          {:accumulator (get-in node [:accum-condition :accumulator])
                                           :from {:type (get-in node [:accum-condition :from :type])
                                                  :constraints (or (seq (get-in node [:accum-condition :from :original-constraints]))
                                                                   (get-in node [:accum-condition :from :constraints]))}}
                                          {:type (:type (:condition node))
                                           :constraints (or (seq (:original-constraints (:condition node)))
                                                            (:constraints (:condition node)))})]]
                    (if (:accum-condition node)
                      {:fact fact
                       :condition condition
                       :facts-accumulated (eng/token->matching-elements node memory token)}
                      {:fact fact
                       :condition condition})))
        :bindings (dissoc-gen-bindings bindings)}))))

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
  "`{:production <record> :type \"rule\"|\"query\"}` maps for the terminal
   (production/query) nodes reachable from `node` through `:children`."
  [node]
  (->> node
       (tree-seq (comp seq :children) :children)
       (keep (fn [n]
               (cond
                 (production-node? n) {:production (:production n) :type "rule"}
                 (query-node? n) {:production (:query n) :type "query"})))))

(defn- get-node-elements
  "Element entries (`{:fact … :bindings …}`) held by a beta node's element memory."
  [memory node]
  (mem/get-elements-all memory node))

(defn- get-node-tokens
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

(defn- token->match-facts
  "Facts a token's condition matches are actually about: accumulator conditions
   contribute their `:from` inputs (via `eng/token->matching-elements`); every
   other condition contributes its own fact."
  [memory id-to-node token]
  (for [[fact node-id] (:matches token)
        :let [node (id-to-node node-id)]
        fact (if (:accum-condition node)
               (eng/token->matching-elements node memory token)
               [fact])]
    fact))

(defn- negation-element-blocks?
  "True when `element` blocks at least one waiting token at negation `node`.
   `token-groups` is the node's waiting tokens grouped by join bindings
   (`(select-keys (:bindings token) (:binding-keys node))`); a join-filter
   negation additionally requires the node's join filter to accept the pair."
  [node element token-groups]
  (let [binding-keys (:binding-keys node)
        join-bindings (select-keys (:bindings element) binding-keys)
        join-filter-fn (:join-filter-fn node)
        env (get-in node [:condition :env])]
    (boolean
     (some (fn [token]
             (if join-filter-fn
               (boolean (join-filter-fn token (:fact element) (:bindings element) env))
               true))
           (get token-groups join-bindings)))))

;; --- Working-memory accuracy ----------------------------------------------

(defn- ->wrapped-facts-from-alpha-memory
  "Wrapped user-visible facts held in alpha memory — the beta-node element
   memory of join and negation nodes."
  [memory]
  (into #{}
        (comp (mapcat vals)
              (mapcat identity)
              (map :fact)
              (filter fact-visible?)
              (map platform/fact-id-wrap))
        (vals (:alpha-memory memory))))

(defn- ->insertion-record-facts
  "Wrapped user-visible facts named by a logical insertion record."
  [memory rulebase]
  (->> (for [rule-node (:production-nodes rulebase)
             token (keys (mem/get-insertions-all memory rule-node))
             insertion-group (mem/get-insertions memory rule-node token)
             fact insertion-group
             :when (fact-visible? fact)]
         (platform/fact-id-wrap fact))
       (into #{})))

(defn- ->wrapped-facts-from-accum-memory
  "Wrapped user-visible facts held in accumulate-node memory (the accumulator
   `:from` inputs)."
  [memory rulebase]
  (into #{}
        (for [node (vals (:id-to-node rulebase))
              :when (accumulate-node? node)
              fact-group (->accumulated-facts memory node)
              fact fact-group
              :when (fact-visible? fact)]
          (platform/fact-id-wrap fact))))

(defn- ->beta-memory-facts
  "Wrapped user-visible facts with definite presence in beta-node memory:
   join/negation element memory plus accumulate-node memory."
  [memory rulebase]
  (set/union (->wrapped-facts-from-alpha-memory memory)
             (->wrapped-facts-from-accum-memory memory rulebase)))

(defn- alpha-accepts-fact?
  "True when at least one alpha node's activation accepts `fact`."
  [get-alphas-fn fact]
  (boolean
   (some (fn [[alpha-nodes _]]
           (some (fn [node]
                   (when-let [bindings ((:activation node) fact (:env node))]
                     bindings))
                 alpha-nodes))
         (get-alphas-fn [fact]))))

(defn- ->fact-retained-pred
  "Returns `(fn [fact] bool)` — whether `fact` is still in working memory.
   A fact is retained when held by beta-node memory (join/negation element
   memory or accumulate-node memory), or accepted by no alpha node (presence
   undecidable, so kept).  False only when absent from beta-node memory yet some
   alpha node would accept it — i.e. it was retracted."
  [get-alphas-fn memory rulebase]
  (let [present-facts (->beta-memory-facts memory rulebase)]
    (fn [fact]
      (or (contains? present-facts (platform/fact-id-wrap fact))
          (not (alpha-accepts-fact? get-alphas-fn fact))))))

(defn- ->wrapped-all-facts
  "Accurate set of wrapped user-visible facts in working memory: the union of
   beta-node memory (join/negation element memory and accumulate-node memory)
   and logical insertion records, minus insertion-record facts that have since
   been retracted."
  [memory rulebase get-alphas-fn]
  (let [insertion-facts (->insertion-record-facts memory rulebase)
        fact-retained? (->fact-retained-pred get-alphas-fn memory rulebase)
        union (set/union (->beta-memory-facts memory rulebase)
                         insertion-facts)]
    (into #{}
          (remove (fn [wrapped]
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
        wrapped-all (->wrapped-all-facts memory rulebase get-alphas-fn)]
    (mapv platform/fact-id-unwrap
          (set/difference wrapped-all (->insertion-record-facts memory rulebase)))))

;; --- Production-level views ------------------------------------------------

(s/defn get-insertions :- {s/Any [InsertionEntry]}
  "Per-rule logical insertions, one `InsertionEntry` per inserted fact
   (user-visible facts only)."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (into {}
          (for [rule-node (:production-nodes rulebase)
                :let [rule (:production rule-node)]]
            [rule
             (vec
              (for [token (keys (mem/get-insertions-all memory rule-node))
                    :let [explanation (first (tokens->explanations session [token]))]
                    insertion-group (mem/get-insertions memory rule-node token)
                    insertion insertion-group
                    :when (fact-visible? insertion)]
                {:explanation explanation
                 :fact insertion}))]))))

(s/defn get-rule-matches :- {s/Any [Explanation]}
  "Per-rule activation `Explanation`s — one per token with a recorded logical
   insertion."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (into {}
          (for [rule-node (:production-nodes rulebase)]
            [(:production rule-node)
             (tokens->explanations session (keys (mem/get-insertions-all memory rule-node)))]))))

(s/defn get-query-matches :- {s/Any [Explanation]}
  "Per-query result `Explanation`s — one per current result token."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (into {}
          (for [[_query-name query-node] (:query-nodes rulebase)]
            [(:query query-node)
             (tokens->explanations session (mem/get-tokens-all memory query-node))]))))

(s/defn ->insertion-support-pairs :- [FactProduction]
  "`FactProduction` entries for facts in a rule activation whose recorded
   logical insertions still contain at least one retained fact.  Accumulator
   conditions contribute their `:from` inputs, matching `->result-support-pairs`."
  [session]
  (let [{:keys [get-alphas-fn memory rulebase]} (eng/components session)
        fact-retained? (->fact-retained-pred get-alphas-fn memory rulebase)
        id-to-node (:id-to-node rulebase)]
    (vec
     (for [rule-node (:production-nodes rulebase)
           :let [rule (:production rule-node)]
           token (keys (mem/get-insertions-all memory rule-node))
           :let [insertions (mapcat identity (mem/get-insertions memory rule-node token))]
           :when (some fact-retained? insertions)
           fact (token->match-facts memory id-to-node token)
           :when (fact-visible? fact)]
       {:fact (platform/fact-id-wrap fact)
        :production rule
        :type "rule"}))))

(s/defn ->result-support-pairs :- [FactProduction]
  "`FactProduction` entries for facts that support a current query result — the
   facts in each result token's matches (accumulator inputs included)."
  [query-matches]
  (vec
   (for [[query explanations] query-matches
         explanation explanations
         match (:matches explanation)
         fact (match->facts match)
         :when (fact-visible? fact)]
     {:fact (platform/fact-id-wrap fact)
      :production query
      :type "query"})))

;; --- Beta-node relation classification -------------------------------------

(s/defn ->node-relation-pairs :- NodeRelations
  "Classifies each fact in a beta node's memory by how it participates
   (see `NodeRelations`)."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)]
    (reduce
     (fn [acc [_id node]]
       (cond
         (join-node? node)
         (let [productions (->descendant-terminal-productions node)]
           (reduce (fn [acc element]
                     (update acc :matches-condition-of
                             into (for [{:keys [production type]} productions]
                                    {:fact (platform/fact-id-wrap (:fact element))
                                     :production production
                                     :type type})))
                   acc
                   (get-node-elements memory node)))

         (negation-node? node)
         (let [productions (->descendant-terminal-productions node)
               binding-keys (:binding-keys node)
               token-groups (group-by #(select-keys (:bindings %) binding-keys)
                                      (get-node-tokens memory node))]
           (reduce (fn [acc element]
                     (let [relation (if (negation-element-blocks? node element token-groups)
                                      :blocks-condition-of
                                      :blocking-candidate-of)]
                       (update acc relation
                               into (for [{:keys [production type]} productions]
                                      {:fact (platform/fact-id-wrap (:fact element))
                                       :production production
                                       :type type}))))
                   acc
                   (get-node-elements memory node)))

         (accumulate-node? node)
         (let [productions (->descendant-terminal-productions node)]
           (reduce (fn [acc fact]
                     (update acc :matches-condition-of
                             into (for [{:keys [production type]} productions]
                                    {:fact (platform/fact-id-wrap fact)
                                     :production production
                                     :type type})))
                   acc
                   (for [fact-group (->accumulated-facts memory node)
                         fact fact-group]
                     fact)))

         :else acc))
     {:matches-condition-of []
      :blocks-condition-of []
      :blocking-candidate-of []}
     (:id-to-node rulebase))))
