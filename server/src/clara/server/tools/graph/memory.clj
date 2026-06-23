(ns clara.server.tools.graph.memory
  "Logic for analyzing and snapshotting Clara Rules working memory."
  (:require [clara.server.vendor.tools.inspect :as inspect]
            [clara.rules.engine :as eng]
            [clara.rules.memory :as mem]
            [clara.rules.platform :as platform]
            [clara.server.tools.graph.serialize :as serialize]
            [clara.server.tools.graph.core :as core]))

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

(defn- get-all-facts-wrapped
  [{:keys [all-facts] :as _inspection}]
  (into #{}
        (map platform/fact-id-wrap)
        all-facts))

(defn- get-fact-type-order
  [{:keys [productions] :as _rulebase}]
  (into {}
        (comp (map :lhs)
              (mapcat core/extract-lhs-fact-types)
              (distinct)
              (map-indexed (comp vec reverse vector)))
        productions))

(defn- sort-facts
  [facts fact-type-fn fact-type-order]
  (sort-by (fn [wrapped]
             (let [fact (platform/fact-id-unwrap wrapped)
                   ft (fact-type-fn fact)]
               [(get fact-type-order ft Integer/MAX_VALUE)
                (str ft)
                (hash fact)]))
           facts))

(defn- build-id-map [sorted-facts]
  (let [id-map (java.util.IdentityHashMap.)]
    (doseq [[i wrapped] (map-indexed vector sorted-facts)]
      (.put id-map (platform/fact-id-unwrap wrapped) (inc i)))
    id-map))

(defn- ->production-order-key-fn
  [production-order]
  (fn [{p-name :name :as _production-meta}]
    (get production-order p-name Integer/MAX_VALUE)))

(defn- build-used-by-index
  [inspection get-id production-order-key-fn]
  (let [{:keys [rule-matches query-matches]} inspection
        rule-match-facts
        (for [[rule explanations] rule-matches
              explanation explanations
              match (:matches explanation)
              fact (extract-match-facts match)
              :when fact]
          [(get-id fact) {:name (:name rule)
                          :ns (str (:ns-name rule))
                          :type "rule"}])

        query-match-facts
        (for [[query explanations] query-matches
              explanation explanations
              match (:matches explanation)
              fact (extract-match-facts match)
              :when fact]
          [(get-id fact) {:name (:name query)
                          :ns (str (:ns-name query))
                          :type "query"}])

        add-fact-id-matches
        (fn add-fact-id-matches [used-by-index fact-id id-match-pairs]
          (assoc used-by-index
                 fact-id
                 (->> (map second id-match-pairs)
                      distinct
                      (sort-by production-order-key-fn)
                      vec)))]

    (->> rule-match-facts
         (concat query-match-facts)
         (group-by first)
         (reduce-kv add-fact-id-matches {}))))

(defn- build-origin-map
  [fact->explanations get-id production-order-key-fn]
  (letfn [(explanations->origins [explanations]
            (->> explanations
                 (map :rule)
                 (map (fn [{p-name :name p-ns-name :ns-name}]
                        {:name p-name
                         :ns (str p-ns-name)
                         :type "rule"}))
                 distinct
                 (sort-by production-order-key-fn)
                 vec))]
    (into {}
          (map (juxt (comp get-id first)
                     (comp explanations->origins second)))
          fact->explanations)))

(defn- build-fact-table
  [{:keys [sorted-facts
           fact-type-fn
           root-facts
           get-fact-id
           origin-map
           used-by-index]}]
  (into {}
        (map (fn [wrapped]
               (let [fact (platform/fact-id-unwrap wrapped)
                     id (get-fact-id fact)]
                 [id {:id id
                      :type (serialize/serialize-fact-type nil (fact-type-fn fact))
                      :data (serialize/prune-fns fact)
                      :is-root (boolean (some #(identical? fact %) root-facts))
                      :inserted-from (get origin-map id [])
                      :used-by (get used-by-index id [])}])))
        sorted-facts))

(defn- group-instances-by-role
  "Groups instances of a fact type by their origin (inserted-from) or usage (used-by)."
  [instances role-key production-order-key-fn]
  (->> (for [inst instances
             role (case role-key
                    :inserted-from (let [origins (:inserted-from inst)]
                                     (if (empty? origins)
                                       [{:name "Root Facts (External)" :type "root"}]
                                       origins))
                    :used-by (:used-by inst))]
         (assoc role :fact inst))
       (group-by (juxt :name :type))
       (map (fn [[[name type] items]]
              (let [first-item (first items)]
                (cond-> {:name name
                         :type type
                         :facts (mapv :fact (sort-by (comp :id :fact) items))}
                  (:ns first-item) (assoc :ns (:ns first-item))))))
       (sort-by (fn [entry]
                  (if (= "root" (:type entry))
                    -1
                    (production-order-key-fn entry))))
       vec))

(defn- build-fact-type-index
  [fact-table production-order-key-fn]
  (letfn [(add-fact-type-instance-data [m fact-type instances]
            (assoc m fact-type
                   {:name fact-type
                    :count (count instances)
                    :inserted-from (group-instances-by-role instances
                                                            :inserted-from
                                                            production-order-key-fn)
                    :used-by (group-instances-by-role instances
                                                      :used-by
                                                      production-order-key-fn)
                    :ids (mapv :id instances)}))]
    (->> (vals fact-table)
         (group-by :type)
         (reduce-kv add-fact-type-instance-data {}))))

(defn- explanations->fact-match-data
  [explanations fact-table get-fact-id]
  (vec
   (for [{:keys [bindings matches]} explanations
         match matches
         fact (extract-match-facts match)
         :let [id (get-fact-id fact)]
         :when id
         :let [fact-entry (get fact-table id)]]
     (assoc fact-entry :data bindings))))

(defn- build-rule-match-index
  [rule-matches
   fact->explanations
   fact-table
   get-fact-id]
  (let [rule-to-inserted-fact-ids
        (->> (for [[fact explanations] fact->explanations
                   {:keys [rule]} explanations]
               [(:name rule) (get-fact-id fact)])
             (group-by first)
             (reduce-kv (fn [m p-name name-id-pairs]
                          (assoc m p-name
                                 (into []
                                       (comp (map second) (distinct))
                                       name-id-pairs)))
                        {}))

        p-name->inserted-facts (fn [p-name]
                                 (mapv #(get fact-table %)
                                       (get rule-to-inserted-fact-ids p-name)))]

    (into {}
          (map (fn [[{p-name :name :as _rule} explanations]]
                 [p-name {:matches (explanations->fact-match-data explanations
                                                                  fact-table
                                                                  get-fact-id)
                          :inserted-facts (p-name->inserted-facts p-name)}]))
          rule-matches)))

(defn- build-query-match-index
  [query-matches
   fact-table
   get-fact-id]
  (into {}
        (map (fn [[{p-name :name} explanations]]
               [p-name {:matches (explanations->fact-match-data explanations
                                                                fact-table
                                                                get-fact-id)}]))
        query-matches))

(defn session-snapshot
  "Return a snapshot of the memory state of the given `session`. This includes details of all facts
  in the memory and information about rule/query matches for those facts."
  [session]
  (let [{:keys [root-facts fact->explanations query-matches rule-matches] :as inspection}
        (inspect/inspect session)

        {:keys [get-alphas-fn rulebase]} (eng/components session)
        {:keys [fact-type-fn]} (meta get-alphas-fn)

        production-order (get-production-order rulebase)
        fact-type-order (get-fact-type-order rulebase)

        all-facts-wrapped (get-all-facts-wrapped inspection)
        sorted-facts (sort-facts all-facts-wrapped fact-type-fn fact-type-order)
        id-map (build-id-map sorted-facts)
        get-fact-id (fn get-fact-id [fact] (.get ^java.util.IdentityHashMap id-map fact))

        production-order-key-fn (->production-order-key-fn production-order)

        used-by-index (build-used-by-index inspection
                                           get-fact-id
                                           production-order-key-fn)
        origin-map (build-origin-map fact->explanations
                                     get-fact-id
                                     production-order-key-fn)

        fact-table (build-fact-table {:sorted-facts sorted-facts
                                      :fact-type-fn fact-type-fn
                                      :root-facts root-facts
                                      :get-fact-id get-fact-id
                                      :origin-map origin-map
                                      :used-by-index used-by-index})
        fact-type-index (build-fact-type-index fact-table
                                               production-order-key-fn)
        rule-match-index (build-rule-match-index rule-matches
                                                 fact->explanations
                                                 fact-table
                                                 get-fact-id)
        query-match-index (build-query-match-index query-matches
                                                   fact-table
                                                   get-fact-id)]
    {:fact-types    fact-type-index
     :facts         fact-table
     :used-by       used-by-index
     :origin        origin-map
     :rule-matches  rule-match-index
     :query-matches query-match-index}))

(defn get-session-rule-activity
  "Returns a unified activity map for a rule: {:matches [...] :inserted-facts [...]}"
  [snapshot fq-name]
  (get-in snapshot [:rule-matches fq-name]))

(defn get-session-query-activity
  "Returns a unified activity map for a query: {:matches [...]}"
  [snapshot fq-name]
  (get-in snapshot [:query-matches fq-name]))

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

