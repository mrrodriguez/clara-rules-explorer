(ns clara.server.vendor.tools.inspect
  "Tooling to inspect a rule session. Vendored for clara-rules-explorer."
  (:require [clara.rules.engine :as eng]
            [clara.rules.schema :as schema]
            [clara.rules.memory :as mem]
            [clara.rules.platform :as platform]
            [clojure.set :as set]
            [clara.tools.internal.inspect :as i]
            [clojure.main :refer [demunge]]
            [schema.core :as s]
            [clojure.string :as str])
  (:import [clara.rules.engine
            ISystemFact
            ProductionNode
            RootJoinNode
            HashJoinNode
            ExpressionJoinNode
            NegationNode
            NegationWithJoinFilterNode]))

(s/defschema ConditionMatch
  "A structure associating a condition with the facts that matched them."
  {:fact s/Any
   :condition schema/Condition
   (s/optional-key :facts-accumulated) [s/Any]})

;; A structured explanation of why a rule or query matched.
(s/defrecord Explanation [matches :- [ConditionMatch]
                          bindings :- {s/Keyword s/Any}]) ; Bound variables

;; Schema of an inspected rule session.
(def RulesInspectionSchema
  {:rule-matches {schema/Rule [Explanation]}
   :query-matches {schema/Query [Explanation]}
   :condition-matches {schema/Condition [s/Any]}
   :root-facts [s/Any]
   :insertions {schema/Rule [{:explanation Explanation :fact s/Any}]}
   :fact->explanations {s/Any [{:rule schema/Rule
                                :explanation Explanation}]}
   :all-facts [s/Any]
   (s/optional-key :unfiltered-rule-matches) {schema/Rule [Explanation]}})

(def FactsInspectionSchema
  {:rules {s/Int schema/Rule}
   :facts [{:fact s/Any
            (s/optional-key :rule-id) s/Int
            (s/optional-key :bindings) {s/Keyword s/Any}
            :fact-types [s/Any]}]})

(defn- gen-binding?
  [[k _v]]
  (.startsWith (name k) "?__gen__"))

(defn- dissoc-gen-bindings
  [bindings]
  (into {} (remove gen-binding? bindings)))

(defn get-condition-matches
  "Returns facts matching each condition"
  [nodes memory]
  (let [node-class->node-type (fn [node]
                                (get {ExpressionJoinNode :join
                                      HashJoinNode :join
                                      RootJoinNode :join
                                      NegationNode :negation
                                      NegationWithJoinFilterNode :negation} (type node)))

        join-node-ids (for [beta-node nodes
                            :let [node-type (node-class->node-type beta-node)]
                            :when (contains? #{:join :negation}
                                             node-type)]
                        [(:id beta-node) (:condition beta-node) node-type])]
    (reduce
     (fn [matches [node-id condition node-type]]
       (update-in matches
                  (condp = node-type
                    :join [condition]
                    :negation [[:not condition]])
                  concat (map :fact (mem/get-elements-all memory {:id node-id}))))
     {}
     join-node-ids)))

(defn to-explanations
  "Helper function to convert tokens to explanation records."
  [session tokens]
  (let [memory (-> session eng/components :memory)
        id-to-node (get-in (eng/components session) [:rulebase :id-to-node])]

    (for [{:keys [matches bindings] :as token} tokens]
      (->Explanation
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
            :condition condition}))
       (dissoc-gen-bindings bindings)))))

(defn gen-all-rule-matches
  [session]
  (when-let [activation-info (i/get-activation-info session)]
    (let [grouped-info (group-by #(-> % :activation :node) activation-info)]
      (into {}
            (map (fn [[k v]]
                   [(:production k)
                    (to-explanations session (map #(-> % :activation :token) v))]))
            grouped-info))))

(defn gen-fact->explanations
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [production-nodes]} rulebase
        rule-to-rule-node (into {} (for [rule-node production-nodes]
                                     [(:production rule-node) rule-node]))]
    (apply merge-with into
           (for [[rule rule-node] rule-to-rule-node
                 token (keys (mem/get-insertions-all memory rule-node))
                 insertion-group (mem/get-insertions memory rule-node token)
                 insertion insertion-group]
             {insertion [{:rule rule
                          :explanation (first (to-explanations session [token]))}]}))))

(defn- get-wrapped-fact-groups
  "Returns a map of grouped categories of fact wrappers found in the `session` memory."
  [session]
  (let [{:keys [rulebase memory]} (eng/components session)
        {:keys [alpha-memory]} memory
        {:keys [production-nodes id-to-node]} rulebase
        facts-from-alphas (->> (vals alpha-memory)
                               (mapcat vals)
                               (mapcat identity)
                               (map :fact)
                               (map platform/fact-id-wrap)
                               (set))
        facts-from-inserts (->> (for [rule-node production-nodes
                                      token (keys (mem/get-insertions-all memory rule-node))
                                      insertion-group (mem/get-insertions memory rule-node token)
                                      fact insertion-group]
                                  (platform/fact-id-wrap fact))
                                (set))
        facts-from-matches (->> (for [rule-node production-nodes
                                      {:keys [matches] :as token} (keys (mem/get-insertions-all memory rule-node))
                                      [fact node-id] matches
                                      :let [node (id-to-node node-id)
                                            accum (when (:accum-condition node)
                                                    (eng/token->matching-elements node memory token))]
                                      fact (if (and (some? accum) (coll? accum))
                                             accum
                                             [fact])]
                                  (platform/fact-id-wrap fact))
                                (set))
        all-facts (set/union facts-from-alphas facts-from-inserts facts-from-matches)]
    {:facts-from-alphas facts-from-alphas
     :facts-from-inserts facts-from-inserts
     :facts-from-matches facts-from-matches
     :all-facts all-facts}))

(defn get-root-facts
  "Returns all root facts in the session that were not derived from rules."
  ([session]
   (get-root-facts session {:grouped-facts (get-wrapped-fact-groups session)}))
  ([_session {:keys [grouped-facts]}]
   (let [{:keys [facts-from-inserts all-facts]} grouped-facts
         root-facts (set/difference all-facts facts-from-inserts)]
     (for [wrapper root-facts]
       (platform/fact-id-unwrap wrapper)))))

(s/defn inspect :- RulesInspectionSchema
  "Returns a representation of the given rule session useful to understand the state of the underlying rules."
  [session]
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [production-nodes query-nodes id-to-node]} rulebase
        query-to-nodes (->> (for [[_query-name query-node] query-nodes]
                              [(:query query-node) query-node])
                            (into {}))
        query-matches (->> (for [[query query-node] query-to-nodes]
                             [query (to-explanations session
                                                     (mem/get-tokens-all memory query-node))])
                           (into {}))
        rule-to-nodes (->> (for [rule-node production-nodes]
                             [(:production rule-node) rule-node])
                           (into {}))
        rule-matches (->> (for [[rule rule-node] rule-to-nodes]
                            [rule (to-explanations session
                                                   (keys (mem/get-insertions-all memory rule-node)))])
                          (into {}))
        condition-matches (get-condition-matches (vals id-to-node) memory)
        {:keys [all-facts] :as grouped-facts} (get-wrapped-fact-groups session)
        root-facts (get-root-facts session {:grouped-facts grouped-facts})
        insertions (->> (for [[rule rule-node] rule-to-nodes]
                          [rule
                           (for [token (keys (mem/get-insertions-all memory rule-node))
                                 insertion-group (get (mem/get-insertions-all memory rule-node) token)
                                 insertion insertion-group]
                             {:explanation (first (to-explanations session [token])) :fact insertion})])
                        (into {}))
        fact-explanations (into {} (gen-fact->explanations session))
        all-facts-unwrapped (into [] (map platform/fact-id-unwrap) all-facts)
        base-info {:rule-matches rule-matches
                   :query-matches query-matches
                   :condition-matches condition-matches
                   :root-facts root-facts
                   :insertions insertions
                   :fact->explanations fact-explanations
                   :all-facts all-facts-unwrapped}]
    (if-let [unfiltered-rule-matches (gen-all-rule-matches session)]
      (assoc base-info :unfiltered-rule-matches unfiltered-rule-matches)
      base-info)))

(s/defn inspect-facts :- FactsInspectionSchema
  "Returns a map with all rules and their associated facts in the session."
  [session]
  (let [{:keys [memory rulebase get-alphas-fn]} (eng/components session)
        {:keys [fact-type-fn
                ancestors-fn]} (meta get-alphas-fn)
        {:keys [production-nodes]} rulebase
        root-facts (for [fact (get-root-facts session)
                         :let [fact-type (fact-type-fn fact)
                               ancestors (ancestors-fn fact-type)]]
                     {:fact fact
                      :fact-types (cons fact-type ancestors)})
        rule-nodes (for [{:keys [id production]} production-nodes]
                     [id production])
        rule-facts (for [{:keys [id] :as rule-node} production-nodes
                         {:keys [bindings] :as token} (keys (mem/get-insertions-all memory rule-node))
                         insertion-group (mem/get-insertions memory rule-node token)
                         fact insertion-group
                         :let [fact-type (fact-type-fn fact)
                               ancestors (ancestors-fn fact-type)
                               bindings (dissoc-gen-bindings bindings)]
                         :when (and (some? fact)
                                    (not (instance? ISystemFact fact)))]
                     {:fact fact
                      :rule-id id
                      :bindings bindings
                      :fact-types (cons fact-type ancestors)})]
    {:rules (into {} rule-nodes)
     :facts (concat root-facts rule-facts)}))
