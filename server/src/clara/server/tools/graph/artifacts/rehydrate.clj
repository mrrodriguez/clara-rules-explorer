(ns clara.server.tools.graph.artifacts.rehydrate
  "The inverse of `clara.server.tools.graph.artifacts.slim`: rebuild every
  direction the slim pass drops because it is recomputable, so a persisted
  analysis can answer the same questions a live one can.

  `slim` removes two kinds of thing: what is *derivable* from what it keeps, and
  what needs a live session. This namespace makes the first kind executable:

    (rehydrate-analysis analysis)                  ; inverses + indexes rebuilt
    (rehydrate-analysis analysis {:annotations …}) ; also :merged-annotations + authored dynamic detections

  The two closure directions are the part to get right and the part
  `slim`'s header comment already states: inverting `:lhs-types` alone is not a
  smaller answer but a wrong one. `:used-by-*` closes over DESCENDANTS,
  `:inserted-by-rules` / `:retracted-by-rules` over ANCESTORS — the opposite
  direction. `clara.server.tools.graph.artifacts.slim-test` pins both.

  Left absent, and still declared in a narrowed `:slim` block: `:nodes`,
  `:lhs-form`, `:raw-condition`, `::conditions/normalized`, and `:ns-deps`.
  `:lhs-form` looks recoverable and is not — it renders from the raw Clara LHS,
  which `:raw-condition` carried and `slim` drops; a re-render from the
  serialized `:lhs` would produce a similar string that is not the same string."
  (:require
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.conditions :as conditions]
   [clara.server.tools.graph.serialize :as serialize]
   [clojure.set :as set]
   [clojure.walk :as walk]))

(set! *warn-on-reflection* true)

(defn- route-id
  "The deterministic id fn, shared with the analysis that wrote these names.
  Coerces to a string first: names may arrive as symbols off an in-memory slim
  analysis, and `serialize/route-id` is only total over strings."
  [name]
  (serialize/route-id (str name)))

(def ^:private always-absent
  "Keys rehydrate can never put back — they need the live rulebase, not what
  `slim` kept. `:raw-condition` / `::conditions/normalized` are condition-node
  keys inside a kept `:lhs`, not top-level keys, but they are part of the
  `:slim :dropped` vocabulary and stay named there."
  #{:nodes :lhs-form :raw-condition ::conditions/normalized :ns-deps})

(def ^:private annotation-restored
  "Keys restored only when the caller hands over the merged annotations the
  analysis was computed from: the input itself, and the authored dynamic
  detection maps (symbols / raw type tokens) the serialized form was derived
  from."
  #{:merged-annotations :dynamic-insert-types-detected :dynamic-retract-types-detected})

;; ===========================================================================
;; reference builders
;; ===========================================================================

(defn- ->type-ref
  "A fact-type `clara.server.graph.api/TypeReference` from its name. `:known` is
  the one bit `slim` could not keep on the name — `unknown` is the
  `:slim :unknown-fact-types` set it hoisted."
  [unknown name]
  {:name name
   :id (route-id (str name))
   :known (not (contains? unknown name))})

(defn- fq-ns
  "Best-effort namespace of a fully-qualified production name."
  [p-name]
  (some-> (str p-name) symbol namespace))

(defn- ->production-ref
  "A production `clara.server.graph.api/ProductionDep` from its name. Rule vs
  query is which map the name lives in — the distinction `:type` records and a
  slim analysis keeps by splitting `:rules` and `:queries`."
  [rules _queries p-name]
  {:name (str p-name)
   :id (route-id (str p-name))
   :ns (fq-ns p-name)
   :type (if (contains? rules p-name) "rule" "query")})

(defn- expand-type-ref
  "A slim-collapsed fact-type name back into a `TypeReference`."
  [unknown x]
  (if (string? x) (->type-ref unknown x) x))

(defn- expand-lhs
  "One production's `:lhs` with every collapsed condition `:type` name expanded
  back to a `TypeReference`. Only `:type` slots are touched — constraint and
  arg strings are values, not references."
  [unknown lhs]
  (walk/postwalk (fn [node]
                   (if (and (map? node) (string? (:type node)))
                     (assoc node :type (->type-ref unknown (:type node)))
                     node))
                 lhs))

;; ===========================================================================
;; hierarchy and usage inverses
;; ===========================================================================

(defn- ->descendants-index
  "Transpose of `:ancestors`: `{ancestor-name #{descendant-name …}}`."
  [fact-types]
  (reduce-kv (fn [idx type-name {:keys [ancestors]}]
               (reduce #(update %1 %2 (fnil conj #{}) type-name) idx ancestors))
             {}
             fact-types))

(defn- descendant-depth
  "Hierarchy distance from `root` to `descendant`, off the kept (deepest-first)
  `:ancestors` — the same definition
  `clara.server.tools.graph.fact-types` uses for its shallowest-first ordering."
  [fact-types root descendant]
  (count (take-while #(not= % root)
                     (get-in fact-types [descendant :ancestors]))))

(defn- ->ordered-descendants
  "Descendants of `type-name`, shallowest-first, ties by name — matching
  `clara.server.tools.graph.fact-types/->ordered-descendants`."
  [fact-types descendants-index type-name]
  (->> (get descendants-index type-name #{})
       (sort-by (fn [d] [(descendant-depth fact-types type-name d) d]))
       vec))

(defn- ->usage-maps
  "The four fact-type usage vectors, keyed by type name and property, each a
  name-sorted vector of production refs. This is the shipped form of the
  inversion `slim-test/dropped-directions-invert-back-test` pins:

    :used-by-rules     :lhs-types over :rules,     closed over DESCENDANTS
    :used-by-queries   :lhs-types over :queries,   closed over DESCENDANTS
    :inserted-by-rules :insert-types,              closed over ANCESTORS
    :retracted-by-rules :retract-types,            closed over ANCESTORS

  Restricted to the fact types the analysis has entries for, the way the field
  it replaces is."
  [rules queries fact-types]
  (let [descendants-index (->descendants-index fact-types)
        ancestors-of (fn [t] (get-in fact-types [t :ancestors] []))
        descendants-of (fn [t] (get descendants-index t #{}))
        acc (volatile! {})]
    (doseq [[p-name production rule?] (concat (map #(conj % true) rules)
                                              (map #(conj % false) queries))
            :let [used-key (if rule? :used-by-rules :used-by-queries)
                  {:keys [lhs-types insert-types retract-types]} production
                  add! (fn [t prop]
                         (vswap! acc update t
                                 (fn [m] (update m prop (fnil conj #{}) p-name))))]]
      (doseq [t lhs-types]
        (add! t used-key)
        (doseq [d (descendants-of t)] (add! d used-key)))
      (doseq [t insert-types]
        (add! t :inserted-by-rules)
        (doseq [a (ancestors-of t)] (add! a :inserted-by-rules)))
      (doseq [t retract-types]
        (add! t :retracted-by-rules)
        (doseq [a (ancestors-of t)] (add! a :retracted-by-rules))))
    (into {}
          (map (fn [type-name]
                 [type-name
                  (reduce-kv (fn [m prop pnames]
                               (assoc m prop (->> pnames
                                                  (map #(->production-ref rules queries %))
                                                  (sort-by :name)
                                                  vec)))
                             {:used-by-rules []
                              :used-by-queries []
                              :inserted-by-rules []
                              :retracted-by-rules []}
                             (get @acc type-name))]))
          (keys fact-types))))

(defn- ->fact-types
  "Every fact-type entry with its dropped fields restored: `:id`, the reference
  shapes back on `:ancestors`, `:descendants` transposed back, and the four
  production directions."
  [fact-types rules queries unknown]
  (let [descendants-index (->descendants-index fact-types)
        usage (->usage-maps rules queries fact-types)]
    (into (sorted-map)
          (map (fn [[type-name entry]]
                 [type-name
                  (-> entry
                      (assoc :id (route-id type-name))
                      (assoc :ancestors (mapv #(->type-ref unknown %) (:ancestors entry)))
                      (assoc :descendants (mapv #(->type-ref unknown %)
                                                (->ordered-descendants fact-types
                                                                       descendants-index
                                                                       type-name)))
                      (assoc :used-by-rules (get-in usage [type-name :used-by-rules] []))
                      (assoc :used-by-queries (get-in usage [type-name :used-by-queries] []))
                      (assoc :inserted-by-rules (get-in usage [type-name :inserted-by-rules] []))
                      (assoc :retracted-by-rules (get-in usage [type-name :retracted-by-rules] [])))]))
          fact-types)))

;; ===========================================================================
;; dep-graph inverses
;; ===========================================================================

(defn- ->downstream
  "Transpose of `:upstream` across the dep-graph map."
  [dep-graph]
  (reduce-kv (fn [idx node {:keys [upstream]}]
               (reduce #(update %1 %2 (fnil conj #{}) node) idx upstream))
             {} dep-graph))

(defn- ->dep-graph
  "Every dep-graph entry with its `:downstream` transposed back in. `:upstream`
  is kept exactly as slim left it — a source node has no `:upstream` key, and a
  sink node has no `:downstream` key, which is the shape the analysis emits."
  [dep-graph]
  (let [downstream (->downstream dep-graph)]
    (into (sorted-map)
          (map (fn [[node entry]]
                 [node (cond-> entry
                         (seq (get downstream node #{}))
                         (assoc :downstream (get downstream node #{})))]))
          dep-graph)))

(defn- rehydrate-production
  "One rule/query: `:id` back, the collapsed `:lhs-types` / `:insert-types` /
  `:retract-types` / `:lhs` references expanded, and `:upstream` / `:downstream`
  rebuilt as plain production deps from the rehydrated dep-graph. `:match` is
  not rebuilt — it needs raw types the persisted analysis does not carry."
  [rules queries dep-graph unknown p-name production]
  (let [up (sort (get-in dep-graph [p-name :upstream] #{}))
        down (sort (get-in dep-graph [p-name :downstream] #{}))]
    (cond-> production
      true (assoc :id (route-id (str p-name)))
      (:lhs-types production) (update :lhs-types #(mapv (partial expand-type-ref unknown) %))
      (:insert-types production) (update :insert-types #(mapv (partial expand-type-ref unknown) %))
      (:retract-types production) (update :retract-types #(mapv (partial expand-type-ref unknown) %))
      (:lhs production) (update :lhs #(expand-lhs unknown %))
      (seq up) (assoc :upstream (mapv #(->production-ref rules queries %) up))
      (seq down) (assoc :downstream (mapv #(->production-ref rules queries %) down)))))

;; ===========================================================================
;; annotation-backed restoration
;; ===========================================================================

(defn- restore-dynamic-detected
  "The authored dynamic-detection maps from the merged annotations, per rule.
  This is the authored form (symbols, raw type tokens), not the serialized one
  `GET /v1/rules/:fq-name` serves."
  [analysis annotations]
  (let [restore (fn [productions]
                  (when productions
                    (into (sorted-map)
                          (map (fn [[p-name production]]
                                 [p-name
                                  (cond-> production
                                    (contains? (get annotations p-name)
                                               :clara-rules/dynamic-insert-types-detected)
                                    (assoc :dynamic-insert-types-detected
                                           (get-in annotations
                                                   [p-name :clara-rules/dynamic-insert-types-detected]))

                                    (contains? (get annotations p-name)
                                               :clara-rules/dynamic-retract-types-detected)
                                    (assoc :dynamic-retract-types-detected
                                           (get-in annotations
                                                   [p-name :clara-rules/dynamic-retract-types-detected])))]))
                          productions)))]
    (-> analysis
        (update :rules restore)
        (update :queries restore))))

(defn- ->id-index
  "`{id name}` over an already-rehydrated `:rules` / `:queries` / `:fact-types`."
  [name-keyed]
  (into {} (map (fn [[k v]] [(:id v) k])) name-keyed))

(defn- narrow-slim
  "The still-absent set, written back into the `:slim` block so a reader knows
  what rehydrate put back and what it could not."
  [analysis annotations?]
  (let [dropped (set/union always-absent
                           (when-not annotations? annotation-restored))]
    (update analysis :slim
            (fn [slim-block]
              (-> slim-block
                  (assoc :dropped dropped)
                  (update :recover #(select-keys % dropped)))))))

;; ===========================================================================
;; entry point
;; ===========================================================================

(defn rehydrate-analysis
  "`analysis` with every direction `slim` dropped because it is recomputable put
  back: production and fact-type `:id`s, the two id indexes, `:descendants`,
  the four fact-type production directions, `:dep-graph :downstream`, and each
  production's `:upstream` / `:downstream`.

  `opts`:
    :annotations — a merged annotations value (or bare rule→annotation map).
    When present, `:merged-annotations` is restored and each production regains
    its authored `:dynamic-insert-types-detected` /
    `:dynamic-retract-types-detected` maps.

  The `:slim` block is narrowed to what is still absent: `:nodes`, `:lhs-form`,
  `:raw-condition`, `::conditions/normalized`, and `:ns-deps` (plus
  `:merged-annotations` / the dynamic detection keys when no annotations were
  supplied)."
  ([analysis]
   (rehydrate-analysis analysis nil))
  ([analysis {:keys [annotations]}]
   (let [rules (get analysis :rules)
         queries (get analysis :queries)
         fact-types (get analysis :fact-types)
         dep-graph (get analysis :dep-graph)
         unknown (set (get-in analysis [:slim :unknown-fact-types]))
         annotations (some-> annotations ann.merge/->bare-annotations)
         rehydrated-dep-graph (when dep-graph (->dep-graph dep-graph))
         rehydrated-rules (when rules
                            (into (sorted-map)
                                  (map (fn [[k v]]
                                         [k (rehydrate-production rules queries rehydrated-dep-graph unknown k v)]))
                                  rules))
         rehydrated-queries (when queries
                              (into (sorted-map)
                                    (map (fn [[k v]]
                                           [k (rehydrate-production rules queries rehydrated-dep-graph unknown k v)]))
                                    queries))
         rehydrated-fact-types (when fact-types
                                 (->fact-types fact-types rules queries unknown))
         result (cond-> analysis
                  rehydrated-rules (assoc :rules rehydrated-rules)
                  rehydrated-queries (assoc :queries rehydrated-queries)
                  rehydrated-fact-types (assoc :fact-types rehydrated-fact-types)
                  rehydrated-dep-graph (assoc :dep-graph rehydrated-dep-graph)
                  (some? annotations) (assoc :merged-annotations annotations))
         result (if (some? annotations)
                  (restore-dynamic-detected result annotations)
                  result)
         result (cond-> result
                  rehydrated-fact-types (assoc :fact-type-id-index (->id-index rehydrated-fact-types))
                  (or rehydrated-rules rehydrated-queries)
                  (assoc :production-id-index
                         (->id-index (merge rehydrated-rules rehydrated-queries))))]
     (narrow-slim result (some? annotations)))))
