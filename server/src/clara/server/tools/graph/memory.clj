(ns clara.server.tools.graph.memory
  "Helpers for analyzing and snapshotting Clara Rules working memory."
  (:require [clara.server.vendor.tools.inspect :as inspect]
            [clara.rules.engine :as eng]
            [clara.rules.memory :as mem]
            [clara.rules.platform :as platform]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.fact-types :as ft]
            [clara.server.tools.graph.serialize :as serialize]))

(defn- deterministic-fact-str
  "Returns a deterministic pr-str representation of a fact for stable sorting.
   Uses pr-str-ordered vector forms with ::map / ::set markers instead of
   sorted-map/sorted-set (which require Comparable keys and fail on sets of
   maps or maps keyed by maps)."
  [fact]
  (letfn [(canonicalize [x]
            (cond
              (map? x) (into [::map]
                             (sort-by pr-str
                                      (map (fn [[k v]] [(canonicalize k) (canonicalize v)])
                                           x)))
              (set? x) (into [::set]
                             (sort-by pr-str (map canonicalize x)))
              (sequential? x) (mapv canonicalize x)
              :else x))]
    (pr-str (canonicalize (serialize/prune-fns fact)))))

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
                (deterministic-fact-str fact)]))
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
                          :id (serialize/route-id (str (:name rule)))
                          :ns (str (:ns-name rule))
                          :type "rule"}])

        query-match-facts
        (for [[query explanations] query-matches
              explanation explanations
              match (:matches explanation)
              fact (extract-match-facts match)
              :when fact]
          [(get-id fact) {:name (:name query)
                          :id (serialize/route-id (str (:name query)))
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
                         :id (serialize/route-id (str p-name))
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
           used-by-index
           known-set]}]
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
                                 type-name (serialize/serialize-fact-type nil raw-type)]
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
                                  :ns (ft/raw-type-ns raw-type)
                                  :data (serialize/prune-fns fact)
                                  :is-root (boolean (some #(identical? fact %) root-facts))
                                  :inserted-from (get origin-map id [])
                                  :used-by (get used-by-index id [])}])))
                    sorted-facts)]
    {:facts facts
     :raw-types raw-types}))

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
                         :id (serialize/route-id (str name))
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
  (letfn [(add-fact-type-instance-data [m fact-type-name instances]
            (assoc m fact-type-name
                   {:name fact-type-name
                    :id (serialize/route-id fact-type-name)
                    :ns (:ns (first instances))
                    :count (count instances)
                    :inserted-from (group-instances-by-role instances
                                                            :inserted-from
                                                            production-order-key-fn)
                    :used-by (group-instances-by-role instances
                                                      :used-by
                                                      production-order-key-fn)
                    :ids (mapv :id instances)}))]
    (->> (vals fact-table)
         (group-by (comp :name :type))
         (reduce-kv add-fact-type-instance-data {}))))

(defn- build-id-name-index
  "Reverse index {route-id(name) → name} for a collection of serialized
   names, asserting id uniqueness (a route-id collision throws loudly at
   snapshot-build time rather than silently mislinking)."
  [names]
  (reduce (fn [idx name]
            (let [id (serialize/route-id (str name))]
              (if-let [existing (get idx id)]
                (throw (ex-info (format "Session route-id collision: %s and %s both map to %s"
                                        existing name id)
                                {:id id :names [existing name]}))
                (assoc idx id name))))
          {}
          names))

(defn- explanations->fact-match-data
  [explanations fact-table get-fact-id]
  (vec
   (for [{:keys [bindings matches]} explanations
         match matches
         fact (extract-match-facts match)
         :let [id (get-fact-id fact)]
         :when id
         :let [fact-entry (get fact-table id)]]
     (assoc fact-entry :data (serialize/prune-fns bindings)))))

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
  in the memory and information about rule/query matches for those facts.

  Two-arity takes the analysis's serialized fact-type names (`known-set`);
  session TypeReference `known` flags are honest membership checks against it
  (runtime-derived types absent from the analysis are marked unknown).  The
  one-arity defaults to no known types."
  ([session]
   (session-snapshot session #{}))
  ([session known-set]
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
                                       :used-by-index used-by-index
                                       :known-set known-set})
         fact-type-index (build-fact-type-index (:facts fact-table)
                                                production-order-key-fn)
         rule-match-index (build-rule-match-index rule-matches
                                                  fact->explanations
                                                  (:facts fact-table)
                                                  get-fact-id)
         query-match-index (build-query-match-index query-matches
                                                    (:facts fact-table)
                                                    get-fact-id)]
     {:fact-types        fact-type-index
      :facts             (:facts fact-table)
      ;; Internal fact-id → raw type index for the annotation-enrichment
      ;; boundary (add-auto-detected-annotations / enrich-annotations-from-session):
      ;; session-derived types must merge into annotations as the raw objects
      ;; the analysis itself serializes — never as serialized name strings,
      ;; which would double-serialize (phantom string-kinded fact types).
      ;; Stripped from the served snapshot in api.clj.
      :fact-raw-types    (:raw-types fact-table)
      :used-by           used-by-index
      :origin            origin-map
      :rule-matches      rule-match-index
      :query-matches     query-match-index
      ;; Per-snapshot id→name indexes for the session detail handlers — built
      ;; here (no analysis-cache dependency) with the same id function over the
      ;; snapshot's serialized names, so session ids align with analysis ids.
      :fact-type-id-index (build-id-name-index (keys fact-type-index))
      :rule-id-index      (build-id-name-index (keys rule-match-index))
      :query-id-index     (build-id-name-index (keys query-match-index))})))

(defn session-snapshot-from-analysis
  "Returns a working-memory snapshot like `session-snapshot`, deriving the
   known-type set from the static `rulebase-analysis` result so TypeReference
   `:known` flags are honest membership checks against the analysis.
   Returns nil when `session` has no working memory (rulebase only)."
  [session analysis]
  (when (core/working-memory-available? session)
    (session-snapshot session (-> analysis :fact-types keys set))))

(defn get-session-rule-activity
  "Returns a unified activity map for a rule: {:matches [...] :inserted-facts [...]}"
  [snapshot p-name]
  (get-in snapshot [:rule-matches p-name]))

(defn get-session-query-activity
  "Returns a unified activity map for a query: {:matches [...]}"
  [snapshot p-name]
  (get-in snapshot [:query-matches p-name]))

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

