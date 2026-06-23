(ns clara.server.tools.graph.nodes
  "Logic for building node summaries and parent mappings for the Clara rulebase graph."
  (:require [clara.server.tools.graph.serialize :as serialize])
  (:import [clara.rules.engine
            AccumulateNode
            AlphaNode
            ExpressionJoinNode
            HashJoinNode
            NegationNode
            ProductionNode
            QueryNode
            RootJoinNode
            TestNode]))

(defn- node-summary
  [{:keys [node node-id->productions node-id->queries]}]
  (let [{node-id :id} node
        common {:id node-id
                :children (mapv :id (:children node))
                :productions (get node-id->productions node-id #{})
                :queries     (get node-id->queries node-id #{})}]
    (cond
      (instance? AlphaNode node)
      (assoc common :kind :alpha :fact-type (serialize/resolve-type nil (:fact-type node)))

      (instance? RootJoinNode node)
      (assoc common :kind :root-join :condition (serialize/prune-fns (:condition node)))

      (instance? HashJoinNode node)
      (assoc common :kind :hash-join :condition (serialize/prune-fns (:condition node)))

      (instance? ExpressionJoinNode node)
      (assoc common :kind :expr-join :condition (serialize/prune-fns (:condition node)))

      (instance? NegationNode node)
      (assoc common :kind :negation :condition (serialize/prune-fns (:condition node)))

      (instance? TestNode node)
      (assoc common :kind :test :constraints (serialize/prune-fns (:constraints node)))

      (instance? AccumulateNode node)
      (assoc common :kind :accumulate :accum-condition (serialize/prune-fns (:accum-condition node)))

      (instance? ProductionNode node)
      (assoc common :kind :production :production (:name (:production node)))

      (instance? QueryNode node)
      (assoc common :kind :query :query (:name (:query node)))

      :else (assoc common :kind :unknown))))

(defn- build-reverse-index
  "Builds a mapping from node-id to its parents."
  [id-to-node]
  (letfn [(add-children [acc parent-id node]
            (reduce
             (fn [inner-acc {:keys [id]}]
               (update inner-acc id (fnil conj #{}) parent-id))
             acc
             (:children node)))]
    (reduce-kv add-children {} id-to-node)))

(defn- get-terminal-nodes [id-to-node]
  (->> id-to-node
       vals
       (filterv #(or (instance? ProductionNode %)
                     (instance? QueryNode %)))))

(defn- get-terminal-node-name
  [term-node]
  (if (instance? ProductionNode term-node)
    (:name (:production term-node))
    (:name (:query term-node))))

(defn- build-reachability
  "Returns a map of node-id to the set of production names that are reachable from it."
  [id-to-node]
  (let [rev-index (build-reverse-index id-to-node)
        terminals (get-terminal-nodes id-to-node)]
    (reduce
     (fn [acc term-node]
       (let [node-name (get-terminal-node-name term-node)
             key (if (instance? ProductionNode term-node)
                   :productions
                   :queries)]
         (loop [[cur-id :as work-list] [(:id term-node)]
                visited #{}
                inner-acc acc]
           (if (empty? work-list)
             inner-acc
             (if (contains? visited cur-id)
               (recur (rest work-list) visited inner-acc)
               (let [parents (get rev-index cur-id)]
                 (recur (into (rest work-list) parents)
                        (conj visited cur-id)
                        (update-in inner-acc [cur-id key]
                                   (fnil conj #{})
                                   node-name))))))))
     {}
     terminals)))

(defn- build-node-summary-map
  [id-to-node]
  (let [reachability (build-reachability id-to-node)
        node-id->productions (update-vals reachability :productions)
        node-id->queries (update-vals reachability :queries)]

    (into {}
          (for [[id node] id-to-node]
            [id (node-summary {:node node
                               :node-id->productions node-id->productions
                               :node-id->queries node-id->queries})]))))

(defn- build-node-parents-map
  [nodes id-to-node]
  (let [rev-index (build-reverse-index id-to-node)]
    (reduce-kv (fn [acc id parents]
                 (assoc-in acc [id :parents] (vec parents)))
               nodes
               rev-index)))

(defn build-nodes
  "Builds a map of node-id to node summary, including reachability and parent information."
  [id-to-node]
  (-> id-to-node
      (build-node-summary-map)
      (build-node-parents-map id-to-node)))
