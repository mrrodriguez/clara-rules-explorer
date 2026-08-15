(ns clara.server.tools.graph.fact-types
  "Fact-type analysis: ancestors-index construction, fact-type summary
   aggregation with hierarchy-aware usage lists, and read-side accessors
   for the API.

   This namespace owns the type-hierarchy bookkeeping — the
   `ancestors-index`, serialized `known`-set, and the hierarchy expansion
   that populates `used-by-*` / `inserted-by-rules` / `retracted-by-rules`
   on fact-type views.  Production-level logic (dep-graph, rule/query
   summaries) lives in `clara.server.tools.graph.core`."
  (:require [clara.server.tools.graph.serialize :as serialize]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn raw-type-ns
  "Best-effort namespace/package of a raw fact type for grouping: keyword or
   symbol → `(namespace x)`, class → package name, other kinds → nil."
  [x]
  (cond
    (keyword? x) (namespace x)
    (symbol? x) (namespace x)
    (class? x) (not-empty (.getPackageName ^Class x))
    :else nil))

(defn known-type-names
  "Serialized names of every raw type in any production's consumed or produced
   types, each serialized in its own production's ns context.  Equals the
   future fact-types map keys by construction; the ancestors enrichment and
   TypeReference `known` flags are computed against this set upfront."
  [type-analysis-map]
  (into #{}
        (mapcat (fn [{:keys [consumed-types produced-types ns-name]}]
                  (map (partial serialize/resolve-type ns-name)
                       (distinct (concat consumed-types produced-types)))))
        (vals type-analysis-map)))

;; ---------------------------------------------------------------------------
;; Ancestors-index construction
;; ---------------------------------------------------------------------------

(defn- ^:private next-hierarchy-node
  "Picks the next raw ancestor type to emit in the deterministic topological
   order: a node with no remaining descendant (deepest), ties broken
   lexicographically by serialized name.  Falls back to the lexicographically
   smallest remaining node when the custom ancestors-fn is cyclic (mutual
   ancestry) — the cycle guard."
  [remaining serialized ancestors-set-fn]
  (or (->> remaining
           (filter (fn [x]
                     (not-any? (fn [d]
                                 (contains? (ancestors-set-fn d) x))
                               remaining)))
           (sort-by serialized)
           first)
      (first (sort-by serialized remaining))))

(defn- ^:private hierarchy-order
  "Deterministically orders a set of raw ancestor types: descendants before
   their own ancestors (per `ancestors-set-fn`), ties broken lexicographically
   on the serialized names.  `serialize-fn` maps a raw type to its serialized
   string.  A pathological custom ancestors-fn with mutual ancestry is handled
   by a cycle guard that emits the lexicographically smallest remaining node."
  [raw-ancestors ancestors-set-fn serialize-fn]
  (let [serialized (into {} (map (fn [t] [t (serialize-fn t)])) raw-ancestors)]
    (loop [remaining (set raw-ancestors)
           ordered []]
      (if (empty? remaining)
        ordered
        (let [pick (next-hierarchy-node remaining serialized ancestors-set-fn)]
          (recur (disj remaining pick)
                 (conj ordered (serialized pick))))))))

(defn- ^:private warn-serialization-divergence!
  "Logs a warning (once per raw type per analysis build) when the same raw
   type serializes to different strings under different production ns
   contexts — only possible for unresolved symbols.  Localized degradation:
   the first (load-order) serialization is kept and the build continues, so
   one bad sidecar symbol cannot take down every analysis endpoint."
  [raw-type existing-serialized new-serialized warned-types]
  (when (and (not= existing-serialized new-serialized)
             (not (contains? @warned-types raw-type)))
    (swap! warned-types conj raw-type)
    (log/warnf "type serialization divergence — %s serializes as both %s and %s across production ns contexts; keeping %s"
               raw-type existing-serialized new-serialized existing-serialized)))

(defn- ^:private ->ancestors-index-entry
  "Fresh ancestors-index entry for `raw-type`, serialized in `ns-name` context:
   {:serialized kind-explicit name, :ns best-effort namespace, :ancestors
   hierarchy-ordered serialized ancestor names}."
  [ancestors-set-fn resolve-memo ns-name raw-type]
  {:serialized (resolve-memo ns-name raw-type)
   :ns (raw-type-ns raw-type)
   :ancestors (hierarchy-order (ancestors-set-fn raw-type)
                               ancestors-set-fn
                               (partial resolve-memo ns-name))})

(defn- ^:private register-ancestors-entry
  "Adds `raw-type` to the per-raw-type index, keyed by its first (load-order)
   serialization.  When the same raw type serializes differently under another
   production's ns context, the first serialization is kept and a warning is
   logged — localized degradation instead of a build-wide failure."
  [acc resolve-memo ancestors-set-fn warned-types ns-name raw-type]
  (if-let [existing (get acc raw-type)]
    (do (warn-serialization-divergence! raw-type
                                        (:serialized existing)
                                        (resolve-memo ns-name raw-type)
                                        warned-types)
        acc)
    (assoc acc raw-type
           (->ancestors-index-entry ancestors-set-fn resolve-memo ns-name raw-type))))

(defn- ^:private register-production-types
  "Registers every raw type in one production's consumed/produced types into
   the per-raw-type index."
  [acc resolve-memo ancestors-set-fn warned-types {:keys [consumed-types produced-types ns-name]}]
  (reduce (fn [acc raw-type]
            (register-ancestors-entry acc resolve-memo ancestors-set-fn warned-types ns-name raw-type))
          acc
          (distinct (concat consumed-types produced-types))))

(defn build-ancestors-index
  "Builds {serialized-type-name {:ancestors [hierarchy-ordered serialized
   ancestor-name ...] :ns <best-effort namespace>}} for every raw type
   appearing in any production's consumed/produced types.  Each raw type is
   serialized in its production's ns context; raw ancestors come from the
   memoized ancestor-set fn and are serialized with the same context.

   Productions are iterated in load order, so a raw type that serializes
   differently under different nses (unresolved symbols) is canonically keyed
   by its first production's serialization — deterministic, never hash-order.
   Divergence logs a warning and keeps the first serialization (localized
   degradation; the type's other serializations still surface via the
   per-production rule summaries and the known-set)."
  [type-analysis-map ancestors-set-fn productions]
  (let [resolve-memo (memoize (fn [ns-name t] (serialize/resolve-type ns-name t)))
        warned-types (atom #{})
        per-raw-type
        (reduce (fn [acc production]
                  (register-production-types acc
                                             resolve-memo
                                             ancestors-set-fn
                                             warned-types
                                             (get type-analysis-map (:name production))))
                {}
                productions)]
    (reduce-kv (fn [idx _raw-type {:keys [serialized ancestors ns]}]
                 (assoc idx serialized {:ancestors ancestors :ns ns}))
               {}
               per-raw-type)))

;; ---------------------------------------------------------------------------
;; Fact-type summary aggregation
;; ---------------------------------------------------------------------------
;; Fact-type summary aggregation helpers
;; ---------------------------------------------------------------------------

(defn- init-fact-type-summary
  "Initial fact-type entry for `type-name`: empty usage vectors, ancestors
   as TypeReference maps with `known` flags from `known-set`."
  [ancestors-index known-set type-name]
  (let [{idx-ancestors :ancestors :keys [ns]} (get ancestors-index type-name)]
    {:name type-name
     :id (serialize/route-id type-name)
     :used-by-rules []
     :used-by-queries []
     :inserted-by-rules []
     :retracted-by-rules []
     :ns ns
     :ancestors (mapv (fn [a]
                        {:name a
                         :id (serialize/route-id a)
                         :known (contains? known-set a)})
                      idx-ancestors)}))

(defn- conj-production-ref
  "Adds `production-ref` to the `key` vector of a fact-type summary entry,
   deduping by `:id`.  When `summary` is nil the entry is initialised from
   `ancestors-index`/`known-set`."
  [ancestors-index known-set summary type-name key production-ref]
  (let [s (or summary (init-fact-type-summary ancestors-index known-set type-name))
        existing (set (map :id (get s key [])))]
    (if (existing (:id production-ref))
      s
      (assoc s key (conj (get s key []) production-ref)))))

(defn- production-fact-type-updates
  "Returns [[type-name key] ...] pairs for `production`: direct type matches
   plus hierarchy-expanded used-by / inserted-by / retracted-by."
  [ancestors-index descendants-of rules
   p-name {:keys [lhs-types insert-types retract-types]}]
  (let [is-rule? (contains? rules p-name)
        used-key (if is-rule? :used-by-rules :used-by-queries)
        direct-used (for [t lhs-types] [(:name t) used-key])
        direct-inserted (for [t insert-types] [(:name t) :inserted-by-rules])
        direct-retracted (for [t retract-types] [(:name t) :retracted-by-rules])
        hierarchy-used (for [t lhs-types
                             d (descendants-of (:name t))]
                         [d used-key])
        hierarchy-inserted (for [t insert-types
                                 {:keys [ancestors]} [(get ancestors-index (:name t))]
                                 a ancestors]
                             [a :inserted-by-rules])
        hierarchy-retracted (for [t retract-types
                                  {:keys [ancestors]} [(get ancestors-index (:name t))]
                                  a ancestors]
                              [a :retracted-by-rules])]
    (concat direct-used direct-inserted direct-retracted
            hierarchy-used hierarchy-inserted hierarchy-retracted)))

(defn build-fact-type-summary-map
  "Aggregates fact-type usage across rules and queries, attaching `:ancestors`
   (hierarchy-ordered `TypeReference` entries, from the serialized ancestors
   index), `:ns` (best-effort namespace for grouping), and `[ProductionDep]`
   usage lists.  `ancestors-index` maps each serialized fact-type name to
   {:ancestors [...] :ns ...}; `known-set` is the serialized fact-type names,
   used for `known` flags.

   Hierarchy expansion: when a production reads type T, all descendants of T
   (types that have T as an ancestor) also list the production in `used-by-*`.
   When a production inserts/retracts type T, all ancestors of T also list
   the production in `inserted-by-rules` / `retracted-by-rules`."
  [{:keys [rules queries ancestors-index known-set]}]
  (let [descendants-index
        (reduce-kv (fn [idx type-name {:keys [ancestors]}]
                     (reduce (fn [idx' ancestor]
                               (update idx' ancestor (fnil conj #{}) type-name))
                             idx
                             ancestors))
                   {}
                   ancestors-index)
        descendants-of (fn [type-name] (get descendants-index type-name #{}))

        production-ref-for (fn [p-name ns]
                             {:name p-name
                              :id (serialize/route-id (str p-name))
                              :ns ns
                              :type (if (contains? rules p-name) "rule" "query")})

        summary-map
        (reduce (fn [acc [p-name {:keys [ns] :as summary}]]
                  (let [updates (production-fact-type-updates
                                 ancestors-index descendants-of rules
                                 p-name summary)
                        pref (production-ref-for p-name ns)]
                    (reduce (fn [a [t k]]
                              (update a t
                                      #(conj-production-ref
                                        ancestors-index known-set % t k pref)))
                            acc
                            updates)))
                {}
                (concat rules queries))

        ;; Directly-referenced types (load order) plus hierarchy-only types
        ;; sorted alphabetically at the end.
        type-refs (into []
                        (comp (map val)
                              (mapcat (fn [s]
                                        (concat (:lhs-types s)
                                                (:insert-types s)
                                                (:retract-types s))))
                              (distinct))
                        (concat rules queries))
        direct-names (into #{} (map :name) type-refs)
        ordered-names (concat (map :name type-refs)
                              (sort (remove direct-names (keys summary-map))))]
    (apply array-map
           (mapcat (fn [name] [name (summary-map name)])
                   ordered-names))))

;; ---------------------------------------------------------------------------
;; Read-side accessors (API-facing)
;; ---------------------------------------------------------------------------

(defn build-fact-type-id-index
  "Reverse index {id → name} for every fact type in the analysis, asserting
   id uniqueness (a route-id collision throws loudly at analysis-build time
   rather than silently mislinking)."
  [analysis]
  (reduce (fn [idx {:keys [id name]}]
            (if-let [existing (get idx id)]
              (throw (ex-info (format "Fact-type route-id collision: %s and %s both map to %s"
                                      existing name id)
                              {:id id :names [existing name]}))
              (assoc idx id name)))
          {}
          (vals (:fact-types analysis))))

(defn fact-types-list
  "Returns a sequence of lightweight fact type summaries, preserving order.
   Omits :ancestors (detail-only) but keeps :ns for grouping and :id for
   links."
  [analysis]
  (mapv #(select-keys % [:name :id :ns :used-by-rules :used-by-queries
                         :inserted-by-rules :retracted-by-rules])
        (vals (:fact-types analysis))))

(defn session-fact-types-summary
  "Returns a lightweight summary of fact types in the session and the total count."
  [snapshot]
  {:types (->> (:fact-types snapshot)
               vals
               (mapv #(select-keys % [:name :id :ns :count])))
   :total-count (count (:facts snapshot))})
