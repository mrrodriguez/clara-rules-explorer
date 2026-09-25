(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.shared.rehydrate
  "The pure core of `clara.server.tools.graph.artifacts.rehydrate`, shared by the JVM and babashka:
  rebuild the directions `slim` drops because they are recomputable from what it keeps.

   This namespace holds the four usage closures (`:used-by-rules` / `:used-by-queries` closed over
  descendants, `:inserted-by-rules` / `:retracted-by-rules` closed over ancestors), the `:downstream`
  dep-graph transpose, and the reference expansion that puts `:id`/`TypeReference` shapes back. It
  is free of live-session concerns: annotation restoration and the `:slim` block narrowing stay in
  the JVM namespace."
  (:require [clara.server.tools.graph.shared.hierarchy :as shared-hierarchy]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- slug
  "URL-safe slug of a name: every char outside [A-Za-z0-9.-] replaced by `-`, runs collapsed,
  leading/trailing `-` trimmed, capped at 60 chars. An empty (or empty-slugging) name yields `x`."
  [s]
  (let [slugged (-> (or s "")
                    (str/replace #"[^A-Za-z0-9.-]" "-")
                    (str/replace #"-+" "-")
                    (str/replace #"^-|-$" ""))
        slugged (subs slugged 0 (min 60 (count slugged)))]
    (if (empty? slugged) "x" slugged)))

(defn- sha1-base36
  "Base36 representation of the SHA-1 digest of `s`; nil treated as \"\" (so a nil name gets the
  same hash as an empty one)."
  [s]
  (let [^String s0 (or s "")
        digest (java.security.MessageDigest/getInstance "SHA-1")
        bytes (.getBytes s0 "UTF-8")]
    (.toString (java.math.BigInteger. 1 (.digest digest bytes)) 36)))

(defn- route-id
  "Deterministic URL-safe id for a canonical serialized name: slug of the name plus an 8-char
  base36 SHA-1 suffix. A port of `clara.server.tools.graph.serialize/route-id*`, without its
  logging dependency."
  [s]
  (when-not (nil? s)
    (str (slug s) "-" (subs (sha1-base36 s) 0 8))))

;; ---------------------------------------------------------------------------
;; reference builders
;; ---------------------------------------------------------------------------

(defn- ->type-ref
  "A fact-type `clara.server.graph.api/TypeReference` from its name. `:known` is the one bit `slim`
  could not keep on the name — `unknown` is the `:slim :unknown-fact-types` set it hoisted."
  [unknown name]
  {:name name
   :id (route-id (str name))
   :known (not (contains? unknown name))})

(defn- fq-ns
  "Best-effort namespace of a fully-qualified production name."
  [p-name]
  (some-> (str p-name) symbol namespace))

(defn- ->production-ref
  "A production `clara.server.graph.api/ProductionDep` from its name. Rule vs query is which map the
  name lives in — the distinction `:type` records and a slim analysis keeps by splitting `:rules`
  and `:queries`."
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
  "One production's `:lhs` with every collapsed condition `:type` name expanded back to a
  `TypeReference`. Only `:type` slots are touched — constraint and arg strings are values, not
  references."
  [unknown lhs]
  (walk/postwalk (fn [node]
                   (if (and (map? node) (string? (:type node)))
                     (assoc node :type (->type-ref unknown (:type node)))
                     node))
                 lhs))

;; ---------------------------------------------------------------------------
;; hierarchy and usage inverses
;; ---------------------------------------------------------------------------

(defn- ->descendants-index
  "Transpose of `:ancestors`: `{ancestor-name #{descendant-name …}}`."
  [fact-types]
  (shared-hierarchy/->descendants
   (into {} (map (fn [[type-name entry]] [type-name (set (:ancestors entry))])) fact-types)))

(defn- descendant-depth
  "Hierarchy distance from `root` to `descendant`, off the kept (deepest-first) `:ancestors`."
  [fact-types root descendant]
  (count (take-while #(not= % root)
                     (get-in fact-types [descendant :ancestors]))))

(defn- ->ordered-descendants
  "Descendants of `type-name`, shallowest-first, ties by name."
  [fact-types descendants-index type-name]
  (->> (get descendants-index type-name #{})
       (sort-by (fn [d] [(descendant-depth fact-types type-name d) d]))
       vec))

(defn- usage-vectors
  "The four usage vectors for one type name from its accumulated `{prop #{p-name}}`, each a
  name-sorted vector of production refs."
  [rules queries accumulated]
  (reduce-kv (fn [m prop pnames]
               (assoc m prop (->> pnames
                                  (map #(->production-ref rules queries %))
                                  (sort-by :name)
                                  vec)))
             {:used-by-rules []
              :used-by-queries []
              :inserted-by-rules []
              :retracted-by-rules []}
             accumulated))

(defn- ->usage-maps
  "The four fact-type usage vectors, keyed by type name and property. This is the shipped form of
  the inversion `slim-test/dropped-directions-invert-back-test` pins:

    :used-by-rules     :lhs-types over :rules,     closed over DESCENDANTS
    :used-by-queries   :lhs-types over :queries,   closed over DESCENDANTS
    :inserted-by-rules :insert-types,              closed over ANCESTORS
    :retracted-by-rules :retract-types,            closed over ANCESTORS

  Restricted to the fact types the analysis has entries for."
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
                 [type-name (usage-vectors rules queries (get @acc type-name))]))
          (keys fact-types))))

(defn- ->fact-types
  "Every fact-type entry with its dropped fields restored: `:id`, the reference shapes back on
  `:ancestors`, `:descendants` transposed back, and the four production directions."
  [fact-types rules queries unknown]
  (let [descendants-index (->descendants-index fact-types)
        usage (->usage-maps rules queries fact-types)]
    (into (sorted-map)
          (map (fn [[type-name entry]]
                 [type-name
                  (-> entry
                      (assoc :id (route-id (str type-name)))
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

;; ---------------------------------------------------------------------------
;; dep-graph inverses
;; ---------------------------------------------------------------------------

(defn- ->downstream
  "Transpose of `:upstream` across the dep-graph map."
  [dep-graph]
  (reduce-kv (fn [idx node {:keys [upstream]}]
               (reduce #(update %1 %2 (fnil conj #{}) node) idx upstream))
             {} dep-graph))

(defn- ->dep-graph
  "Every dep-graph entry with its `:downstream` transposed back in. `:upstream` is kept exactly as
  slim left it — a source node has no `:upstream` key, and a sink node has no `:downstream` key."
  [dep-graph]
  (let [downstream (->downstream dep-graph)]
    (into (sorted-map)
          (map (fn [[node entry]]
                 [node (cond-> entry
                         (seq (get downstream node #{}))
                         (assoc :downstream (get downstream node #{})))]))
          dep-graph)))

(defn- rehydrate-production
  "One rule/query: `:id` back, the collapsed `:lhs-types` / `:insert-types` / `:retract-types` /
  `:lhs` references expanded, and `:upstream` / `:downstream` rebuilt as plain production deps from
  the rehydrated dep-graph. `:match` is not rebuilt — it needs raw types the persisted analysis
  does not carry."
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

(defn- ->id-index
  "`{id name}` over an already-rehydrated `:rules` / `:queries` / `:fact-types`."
  [name-keyed]
  (into {} (map (fn [[k v]] [(:id v) k])) name-keyed))

;; ---------------------------------------------------------------------------
;; entry point
;; ---------------------------------------------------------------------------

(defn rehydrate-analysis
  "`analysis` with every direction `slim` dropped because it is recomputable put back: production
  and fact-type `:id`s, the two id indexes, `:descendants`, the four fact-type production
  directions, `:dep-graph :downstream`, and each production's `:upstream` / `:downstream`.

  The JVM namespace adds annotation restoration and the `:slim` block narrowing on top of this."
  [analysis]
  (let [rules (get analysis :rules)
        queries (get analysis :queries)
        fact-types (get analysis :fact-types)
        dep-graph (get analysis :dep-graph)
        unknown (set (get-in analysis [:slim :unknown-fact-types]))
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
                 rehydrated-dep-graph (assoc :dep-graph rehydrated-dep-graph))
        result (cond-> result
                 rehydrated-fact-types (assoc :fact-type-id-index (->id-index rehydrated-fact-types))
                 (or rehydrated-rules rehydrated-queries)
                 (assoc :production-id-index
                        (->id-index (merge rehydrated-rules rehydrated-queries))))]
    result))
