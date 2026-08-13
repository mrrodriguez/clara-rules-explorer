(ns clara.server.tools.graph.memory
  "Helpers for analyzing and snapshotting Clara Rules working memory."
  (:require [clara.server.vendor.tools.inspect :as inspect]
            [clara.rules.engine :as eng]
            [clara.rules.memory :as mem]
            [clara.rules.platform :as platform]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.fact-types :as ft]
            [clara.server.tools.graph.serialize :as serialize]))

(defn- sort-by-pr-str
  "Sorts `coll` by the pr-str of each element, computing pr-str once per
   element (decorate-sort-undecorate) rather than once per comparison as
   `sort-by` would."
  [coll]
  (->> coll
       (map (fn [x] [(pr-str x) x]))
       (sort-by first)
       (map second)))

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
                             (sort-by-pr-str
                              (map (fn [[k v]] [(canonicalize k) (canonicalize v)]) x)))
              (set? x) (into [::set]
                             (sort-by-pr-str (map canonicalize x)))
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
  [facts fact-type-fn fact-type-order prune-fn]
  (->> facts
       (map (fn [wrapped]
              (let [fact (platform/fact-id-unwrap wrapped)
                    ft (fact-type-fn fact)]
                [[(get fact-type-order ft Integer/MAX_VALUE)
                  (str ft)
                  (deterministic-fact-str fact prune-fn)]
                 wrapped])))
       (sort-by first)
       (map second)))

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
  [{p-name :name p-ns-name :ns-name}]
  {:name p-name
   :id (serialize/route-id (str p-name))
   :ns (str p-ns-name)
   :type "rule"})

(defn- build-origin-map
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

(defn- build-fact-table
  [{:keys [sorted-facts
           fact-type-fn
           root-facts
           get-fact-id
           origin-map
           used-by-index
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
                                       (println
                                        (str "WARN: fact-type-fn returned nil for fact "
                                             (pr-str (prune-fn fact))
                                             " — inserted by rules: " (pr-str rule-names)
                                             " — substituting :clara.tools.graph.analyze/unknown-fact-type"))))
                                 type-name (->> (or raw-type
                                                    :clara.tools.graph.analyze/unknown-fact-type)
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
                                  :ns (ft/raw-type-ns raw-type)
                                  :data (prune-fn fact)
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
  "`[{:fact SessionFact :bindings [binding-map …]}]` — one entry per matched
   fact, carrying every distinct binding set it matched under.  A fact that
   satisfies several conditions of one activation (duplicate pairs) or several
   activations (distinct bindings) appears once; ids the fact table cannot
   describe are dropped, matching `:inserted-facts`.

   Rows are sorted by fact id; binding sets by a deterministic string of the
   pruned binding map, so snapshots are byte-stable across identical sessions."
  [explanations fact-table get-fact-id prune-fn]
  (let [binding-sets (volatile! {})]
    (doseq [{:keys [bindings matches]} explanations
            match matches
            fact (extract-match-facts match)
            :let [id (get-fact-id fact)]
            :when id]
      (vswap! binding-sets update id (fnil conj #{}) (prune-fn bindings)))
    (->> @binding-sets
         (keep (fn [[id binding-set]]
                 (when-let [fact (get fact-table id)]
                   {:fact fact
                    :bindings (->> binding-set
                                   (map (fn [bs] [(deterministic-fact-str bs prune-fn) bs]))
                                   (sort-by first)
                                   (map second)
                                   vec)})))
         (sort-by (comp :id :fact))
         vec)))

(defn- build-rule-match-index
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

(defn- build-query-match-index
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
   (let [{:keys [root-facts insertions query-matches rule-matches] :as inspection}
         (inspect/inspect session)

         {:keys [get-alphas-fn rulebase]} (eng/components session)
         {:keys [fact-type-fn]} (meta get-alphas-fn)

         production-order (get-production-order rulebase)
         fact-type-order (get-fact-type-order rulebase)

         all-facts-wrapped (get-all-facts-wrapped inspection)
         prune-fn (serialize/memoizing-prune-fns)
         sorted-facts (sort-facts all-facts-wrapped fact-type-fn fact-type-order prune-fn)
         id-map (build-id-map sorted-facts)
         get-fact-id (fn get-fact-id [fact] (.get ^java.util.IdentityHashMap id-map fact))

         production-order-key-fn (->production-order-key-fn production-order)

         used-by-index (build-used-by-index inspection
                                            get-fact-id
                                            production-order-key-fn)
         origin-map (build-origin-map insertions
                                      get-fact-id
                                      production-order-key-fn)

         fact-table (build-fact-table {:sorted-facts sorted-facts
                                       :fact-type-fn fact-type-fn
                                       :root-facts root-facts
                                       :get-fact-id get-fact-id
                                       :origin-map origin-map
                                       :used-by-index used-by-index
                                       :known-set known-set
                                       :prune-fn prune-fn})
         fact-type-index (build-fact-type-index (:facts fact-table)
                                                production-order-key-fn)
         rule-match-index (build-rule-match-index rule-matches
                                                  insertions
                                                  (:facts fact-table)
                                                  get-fact-id
                                                  prune-fn)
         query-match-index (build-query-match-index query-matches
                                                    (:facts fact-table)
                                                    get-fact-id
                                                    prune-fn)]
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

(defn update-snapshot-known-set
  "Re-derives the per-fact :type :known flag of an existing snapshot from
   `known-set` (serialized fact-type names), without re-inspecting the session.
   Re-stamps every fact entry wherever it appears — :facts, the rule/query match
   indices, and the :fact-types inserted-from/used-by role grouping — still
   O(total fact entries), far cheaper than a fresh snapshot.  Used by the cache
   build to reuse a snapshot produced during memory enrichment (which is built
   with an empty known-set)."
  [snapshot known-set]
  (letfn [(stamp [fact]
            (assoc-in fact [:type :known]
                      (contains? known-set (get-in fact [:type :name]))))
          (stamp-facts [facts] (mapv stamp facts))
          (stamp-match [match] (update match :fact stamp))
          (stamp-matches [matches] (mapv stamp-match matches))
          (stamp-role [role] (update role :facts stamp-facts))
          (stamp-roles [roles] (mapv stamp-role roles))]
    (-> snapshot
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
                  (-> entry
                      (update :inserted-from stamp-roles)
                      (update :used-by stamp-roles)))))))

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

