(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.server.navigate
  "Pure editor navigation over a rehydrated rulebase-analysis map: given an analysis and a
  `schema/NavigateInput`, answers which productions produce or consume the type under the
  cursor.

   Runtime-provided capabilities arrive as a `schema/NavigateRuntime`, so this namespace
  stays free of namespace resolution, class-loading, and var metadata.
  `clara.explorer.server.client/navigate` supplies the JVM implementations (live `ns-resolve` over the
  editor's namespaces, source locations from var metadata); the babashka client supplies
  fully-qualified-assuming ones with an always-absent source."
  (:require [clara.explorer.server.schema :as schema]
            [clara.explorer.server.tokens :as tokens]
            [clojure.set :as set]
            [schema.core :as s]))

;; ---------------------------------------------------------------------------
;; Analysis access helpers
;; ---------------------------------------------------------------------------

(defn- production-summary
  [analysis production]
  (or (get-in analysis [:rules production])
      (get-in analysis [:queries production])))

(defn- type-name-set
  [type-refs]
  (into #{} (keep :name) type-refs))

(defn- declared-lhs-type-names [summary]
  (type-name-set (:lhs-types summary)))

(defn- declared-rhs-type-names [summary]
  (type-name-set (concat (:insert-types summary) (:retract-types summary))))

(defn- resolve-rhs-types
  "Resolves the RHS token to a set of candidate kind-explicit type names, combining direct
  (kind/ctor) resolution with the production's serialized dynamic-insert/retract callsite linkage."
  [runtime summary caller-ns-sym token]
  (let [{:keys [resolve-token token->fq-sym]} runtime
        direct (resolve-token caller-ns-sym token)
        fq-sym (token->fq-sym caller-ns-sym token)
        callsite-types
        (->> [(get summary :dynamic-insert-types-detected)
              (get summary :dynamic-retract-types-detected)]
             (keep :callsites)
             (apply concat)
             (filter #(when fq-sym (tokens/callsite-matches-token? (str fq-sym) %)))
             (mapcat :resolved-types)
             (keep :name)
             set)]
    (cond-> callsite-types
      (tokens/real-type-name? direct) (conj direct))))

(defn- exclude-querying-production
  "Transducer dropping navigate targets that name `production` itself. The dep-graph carries no
  self-edges — both dep-graph builders refuse producer = consumer — so scoped navigation excludes
  the querying production from the global closure."
  [production]
  (remove #(= (:name %) production)))

(defn- dedupe-targets
  "Merges same-name navigate targets with `:retract` winning: one production can both insert and
  retract (a hierarchy reach of) one type, reaching the global closure twice. Answers one row per
  production, name-sorted like the closures."
  [targets]
  (->> targets
       (group-by :name)
       (sort-by key)
       (mapv (fn [[_ rows]]
               (assoc (first rows)
                      :via (if (some #(= :retract (:via %)) rows)
                             :retract
                             :insert))))))

(defn- dep->target
  "Builds a `schema/NavigateTarget` from a serialized production dep plus `via`, with the
  source location from the runtime. `:unit` rides along when the composition tagged the dep."
  [runtime dep via]
  (let [source (:production-source runtime)]
    (cond-> {:name   (:name dep)
             :ns     (:ns dep)
             :type   (:type dep)
             :via    via
             :source (source (:name dep))}
      (:unit dep) (assoc :unit (:unit dep)))))

;; ---------------------------------------------------------------------------
;; Global closures (hierarchy-aware production refs per fact type)
;; ---------------------------------------------------------------------------

(defn- global-producer-targets
  "Builds navigate targets from a fact type's `inserted-by-rules` / `retracted-by-rules` production
  refs (hierarchy-aware producers)."
  [runtime analysis type-name]
  (let [fact-type (get-in analysis [:fact-types type-name])
        inserted (:inserted-by-rules fact-type)
        retracted (:retracted-by-rules fact-type)]
    (->> (concat (map #(dep->target runtime % :insert) inserted)
                 (map #(dep->target runtime % :retract) retracted))
         (sort-by :name)
         vec)))

(defn- global-consumer-targets
  "Builds navigate targets from a fact type's `used-by-rules` / `used-by-queries` production refs
  (hierarchy-aware consumers)."
  [runtime analysis type-name]
  (let [fact-type (get-in analysis [:fact-types type-name])
        refs (concat (:used-by-rules fact-type)
                     (:used-by-queries fact-type))]
    (->> refs
         (map #(dep->target runtime % :insert))
         (sort-by :name)
         vec)))

(defn- scoped-producer-targets
  "Producer targets for `type-name` in `production`'s scope: the global
   producer closure minus the querying production itself, deduped with
   `:retract` winning. A dep-graph edge exists exactly when the global closure
   reaches, and `:via` comes from the same inserted/retracted split."
  [runtime analysis production type-name]
  (dedupe-targets
   (into []
         (exclude-querying-production production)
         (global-producer-targets runtime analysis type-name))))

(defn- scoped-consumer-targets
  "Consumer targets for `matched-types` in `production`'s scope: per-type
   consumers from the global closure, tagged `:retract` when the type is in
   this production's retract set, minus the querying production itself, deduped
   with `:retract` winning."
  [runtime analysis summary production matched-types]
  (let [retract-names (type-name-set (:retract-types summary))]
    (dedupe-targets
     (into []
           (comp (mapcat (fn [t]
                           (let [via (if (contains? retract-names t) :retract :insert)]
                             (map #(assoc % :via via)
                                  (global-consumer-targets runtime analysis t)))))
                 (exclude-querying-production production))
           matched-types))))

;; ---------------------------------------------------------------------------
;; Scoped navigation (inside a defrule/defquery)
;; ---------------------------------------------------------------------------

(defn- lhs-navigate
  [runtime analysis summary production resolve-ns token]
  (let [{:keys [resolve-token]} runtime
        token-name (resolve-token resolve-ns token)]
    (cond
      (nil? token-name)
      {:error (str "no fact type found under cursor in " production)}

      (contains? (declared-lhs-type-names summary) token-name)
      (let [targets (scoped-producer-targets runtime analysis production token-name)]
        (if (empty? targets)
          (let [global (global-producer-targets runtime analysis token-name)]
            (if (seq global)
              {:direction  :producer
               :production production
               :type       token-name
               :targets    global}
              {:error (str "no producer of " token-name " for " production)}))
          {:direction  :producer
           :production production
           :type       token-name
           :targets    targets}))

      :else
      (if (contains? (:fact-types analysis) token-name)
        (let [global (global-producer-targets runtime analysis token-name)]
          (if (seq global)
            {:direction  :producer
             :production production
             :type       token-name
             :targets    global}
            {:error (str "no producer of " token-name " for " production)}))
        {:error (str "no fact type found under cursor in " production)}))))

(defn- rhs-navigate
  [runtime analysis summary production resolve-ns token]
  (let [candidates (resolve-rhs-types runtime summary resolve-ns token)
        matched-types (set/intersection candidates (declared-rhs-type-names summary))]
    (if (seq matched-types)
      (let [targets (scoped-consumer-targets runtime analysis summary production matched-types)
            type-name (first (sort matched-types))]
        (if (empty? targets)
          (let [global (->> matched-types
                            (mapcat #(global-consumer-targets runtime analysis %))
                            (sort-by :name)
                            vec)]
            (if (seq global)
              {:direction  :consumer
               :production production
               :type       type-name
               :targets    global}
              {:error (str "no consumer of " type-name " for " production)}))
          {:direction  :consumer
           :production production
           :type       type-name
           :targets    targets}))
      (let [known-types (set (keys (:fact-types analysis)))
            global-candidates (set/intersection candidates known-types)]
        (if (seq global-candidates)
          (let [type-name (first (sort global-candidates))
                global (->> global-candidates
                            (mapcat #(global-consumer-targets runtime analysis %))
                            (sort-by :name)
                            vec)]
            (if (seq global)
              {:direction  :consumer
               :production production
               :type       type-name
               :targets    global}
              {:error (str "no fact type found under cursor in " production)}))
          {:error (str "no fact type found under cursor in " production)})))))

(defn- navigate-scoped
  [runtime analysis production side caller-ns-sym token]
  (let [prod-ns (some-> production symbol namespace symbol)
        resolve-ns (or caller-ns-sym prod-ns)
        summary (production-summary analysis production)]
    (cond
      (nil? summary)
      {:error (str "no production named " production)}

      (and (= :rhs side)
           (not (contains? (:rules analysis) production)))
      {:error (str production " has no RHS (queries have no RHS)")}

      (= :lhs side) (lhs-navigate runtime analysis summary production resolve-ns token)

      (= :rhs side) (rhs-navigate runtime analysis summary production resolve-ns token)

      :else {:error "a :side is required for scoped navigation"})))

;; ---------------------------------------------------------------------------
;; Global navigation (outside a defrule/defquery)
;; ---------------------------------------------------------------------------

(defn- global-callsite-resolved-types
  "Across every production, finds callsites whose `:constructor-sym` /
   `:fact-type` matches the fq token symbol and collects their resolved type
   names."
  [analysis fq-sym]
  (when fq-sym
    (let [fq-str (str fq-sym)
          detections
          (fn [summary]
            [(get summary :dynamic-insert-types-detected)
             (get summary :dynamic-retract-types-detected)])]
      (->> (concat (vals (:rules analysis)) (vals (:queries analysis)))
           (mapcat detections)
           (keep :callsites)
           (apply concat)
           (filter #(tokens/callsite-matches-token? fq-str %))
           (mapcat :resolved-types)
           (keep :name)
           set))))

(defn- navigate-global
  [runtime analysis caller-ns-sym token side]
  (let [{:keys [resolve-token token->fq-sym]} runtime
        direct (resolve-token caller-ns-sym token)
        fq-sym (token->fq-sym caller-ns-sym token)
        callsite-names (global-callsite-resolved-types analysis fq-sym)
        ;; `callsite-names` is nil when the token is not a symbol (keyword/string/
        ;; vector), so conjing onto nil would produce a list and
        ;; `set/intersection` would throw for a non-matching type. Start from a
        ;; set so the fall-through below reaches the clean error path.
        candidates (cond-> (or callsite-names #{})
                     (tokens/real-type-name? direct) (conj direct))
        known-types (set (keys (:fact-types analysis)))
        matched (set/intersection candidates known-types)]
    (cond
      (empty? matched)
      {:error (str "no fact type found under cursor for token " (pr-str token))}

      :else
      (let [type-name (first (sort matched))
            producer? (= :lhs side)
            targets (if producer?
                      (dedupe-targets
                       (->> matched
                            (mapcat #(global-producer-targets runtime analysis %))
                            vec))
                      (dedupe-targets
                       (->> matched
                            (mapcat #(global-consumer-targets runtime analysis %))
                            vec)))
            direction (if producer? :producer :type)]
        (if (empty? targets)
          {:error (str "no " (if producer? "producer" "consumer") " of " type-name)}
          {:direction  direction
           :production nil
           :type       type-name
           :targets    targets})))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(s/defn navigate :- schema/NavigateResponse
  "Resolves editor navigation for a fact-type token over `analysis`, answering a
   `schema/NavigateResponse`. `input` is a `schema/NavigateInput` (`:production` nil
   selects the global path). Performs no input validation and no logging —
   `clara.explorer.server.client/navigate` is the validating, logging JVM entry point."
  [analysis :- s/Any runtime :- schema/NavigateRuntime input :- schema/NavigateInput]
  (let [{:keys [production side caller-ns token]} input
        caller-ns-sym (some-> caller-ns symbol)]
    (if (nil? production)
      (navigate-global runtime analysis caller-ns-sym token side)
      (navigate-scoped runtime analysis production side caller-ns-sym token))))

;; ---------------------------------------------------------------------------
;; Fact type provenance (producer / consumer trees)
;; ---------------------------------------------------------------------------

(def ^:private provenance-directions #{:producer :consumer})

(declare ->fact-type-node ->production-children)

(defn- type-name->short-name
  "The display form of a serialized fact-type name: a class-like name keeps only its last dotted
   segment (`a.b.DocumentCheck` -> `DocumentCheck`); keywords, strings, tuples, and maps are
   already their own best short form and come back unchanged."
  [type-name]
  (if (and (string? type-name)
           (not (re-find #"^[:\[\{\"]" type-name)))
    (or (second (re-find #"\.([^./]+)$" type-name)) type-name)
    type-name))

(defn- ->vias
  "The documented `:vias` order: `[:insert]`, `[:retract]`, or both."
  [via-set]
  (cond-> []
    (contains? via-set :insert) (conj :insert)
    (contains? via-set :retract) (conj :retract)))

(defn- ->type-matches
  "The name-sorted `{:name :short}` entries for the types a production references that reach the
   parent type, i.e. those in `allowed` (`{T} ∪ descendants(T)` for producers,
   `{T} ∪ ancestors(T)` for consumers)."
  [referenced allowed]
  (->> referenced
       (filter allowed)
       distinct
       sort
       (mapv (fn [type-name]
               {:name type-name :short (type-name->short-name type-name)}))))

(defn- ->bridge?
  "True when the edge reaches `parent-type` through the hierarchy rather than directly."
  [matches parent-type]
  (not= (mapv :name matches) [parent-type]))

(defn- ->producer-targets
  "The productions that insert or retract `type-name` (or a descendant), one entry per
   production: `:via` is `:retract` when it also retracts, `:vias` keeps both, and `:matches`
   unions the insert- and retract-side bridge types."
  [analysis type-name]
  (let [fact-type (get-in analysis [:fact-types type-name])
        allowed (into #{type-name} (keep :name (:descendants fact-type)))
        deps (concat (map #(assoc % :via :insert) (:inserted-by-rules fact-type))
                     (map #(assoc % :via :retract) (:retracted-by-rules fact-type)))]
    (->> (group-by :name deps)
         (sort-by key)
         (mapv (fn [[p-name rows]]
                 (let [vias (into #{} (map :via) rows)
                       summary (production-summary analysis p-name)
                       matches (->type-matches
                                (cond-> []
                                  (contains? vias :insert)
                                  (into (keep :name (:insert-types summary)))
                                  (contains? vias :retract)
                                  (into (keep :name (:retract-types summary))))
                                allowed)]
                   {:name     p-name
                    :via      (if (contains? vias :retract) :retract :insert)
                    :vias     (when (> (count vias) 1) (->vias vias))
                    :matches  matches
                    :bridge?  (->bridge? matches type-name)}))))))

(defn- ->consumer-targets
  "The productions that consume `type-name` (or an ancestor), one entry per production, with the
   `:matches` that made it reach the type."
  [analysis type-name]
  (let [fact-type (get-in analysis [:fact-types type-name])
        allowed (into #{type-name} (keep :name (:ancestors fact-type)))
        deps (concat (:used-by-rules fact-type) (:used-by-queries fact-type))]
    (->> deps
         (group-by :name)
         (sort-by key)
         (mapv (fn [[p-name _rows]]
                 (let [matches (->type-matches
                                (keep :name (:lhs-types (production-summary analysis p-name)))
                                allowed)]
                   {:name     p-name
                    :via      :consume
                    :matches  matches
                    :bridge?  (->bridge? matches type-name)}))))))

(defn- production-type
  "`\"rule\"` or `\"query\"` — which map the analysis keeps the production in."
  [analysis p-name]
  (if (contains? (:queries analysis) p-name) "query" "rule"))

(defn- producer-leaf
  "`{:children []}` for a production with no LHS types, else nil."
  [summary]
  (when (empty? (:lhs-types summary)) {:children []}))

(defn- consumer-leaf
  "`{:children []}` (plus `:outputs :unknown` for an unlinked rule) when a consumer production
   resolves nothing downstream, else nil."
  [analysis summary p-name]
  (let [outputs (concat (:insert-types summary) (:retract-types summary))]
    (cond
      (= "query" (production-type analysis p-name)) {:children []}
      (:no-output-types summary) {:children []}
      (:unlinked-rule summary) {:children [] :outputs :unknown}
      (empty? outputs) {:children []}
      :else nil)))

(defn- ->production-node*
  "Assemble one production node. `ctx` already carries this production's name in `:ancestors`;
   `extra` supplies `:cycle?` / `:seen?` and, when known, `:children` or `:outputs`."
  [{:keys [analysis runtime]} {:keys [name via vias matches bridge?]} extra]
  (let [summary (production-summary analysis name)]
    (merge
     (cond-> {:kind   :production
              :name   name
              :ns     (:ns summary)
              :type   (production-type analysis name)
              :source ((:production-source runtime) name)}
       (:unit summary) (assoc :unit (:unit summary))
       via (assoc :via via)
       (seq vias) (assoc :vias vias)
       (some? matches) (assoc :matches matches :bridge? bridge?))
     extra)))

(defn- ->production-node
  "One production child node. Expanded (and marked seen) when the remaining depth allows and the
   production is neither a true path cycle nor already expanded in this response; otherwise a
   leaf gets `:children []` and an expandable one omits `:children`."
  [{:keys [analysis direction ancestors seen remaining] :as ctx} dep]
  (let [{:keys [name]} dep
        summary (production-summary analysis name)
        cycle? (contains? ancestors name)
        seen? (and (not cycle?) (contains? @seen name))
        expand? (and (not cycle?)
                     (not seen?)
                     (or (= :full remaining) (and (integer? remaining) (pos? remaining))))
        leaf (when (and (not cycle?) (not seen?) (not expand?))
               (if (= :producer direction)
                 (producer-leaf summary)
                 (consumer-leaf analysis summary name)))]
    (if expand?
      (do
        (vswap! seen conj name)
        (->production-node*
         ctx dep
         {:cycle? false
          :seen?  false
          :children (->production-children
                     (assoc ctx
                            :ancestors (conj ancestors name)
                            :remaining (if (= :full remaining) :full (dec remaining)))
                     name)}))
      (->production-node* ctx dep (merge {:cycle? cycle? :seen? seen?} leaf)))))

(defn- ->consumer-child-types
  "A consumer production's children: `:insert-types` in declaration order, then the remaining
   `:retract-types`, each with its `:vias`."
  [summary]
  (let [inserted (keep :name (:insert-types summary))
        retracted (keep :name (:retract-types summary))]
    (->> (distinct (concat inserted retracted))
         (mapv (fn [type-name]
                 [type-name (->vias (cond-> #{}
                                      (some #{type-name} inserted) (conj :insert)
                                      (some #{type-name} retracted) (conj :retract)))])))))

(defn- ->production-children
  "The fact-type children of production `name` — its LHS types upstream, its insert/retract types
   downstream. `ctx`'s `:remaining` budget applies to the production layer those children
   expose."
  [{:keys [analysis direction] :as ctx} name]
  (let [summary (production-summary analysis name)]
    (if (= :producer direction)
      (mapv #(->fact-type-node ctx % nil)
            (distinct (keep :name (:lhs-types summary))))
      (mapv (fn [[type-name vias]] (->fact-type-node ctx type-name vias))
            (->consumer-child-types summary)))))

(defn- ->fact-type-node
  "One fact-type node: its production children in `direction`, with `:external?` on the producer
   side when nothing inserts the type, and `:vias` when it is a consumer production's child."
  [{:keys [analysis direction] :as ctx} type-name vias]
  (let [targets (if (= :producer direction)
                  (->producer-targets analysis type-name)
                  (->consumer-targets analysis type-name))]
    (cond-> {:kind :fact-type
             :type type-name
             :type-short (type-name->short-name type-name)
             :children (mapv #(->production-node ctx %) targets)}
      (= :producer direction)
      (assoc :external? (not-any? #(= :insert (:via %)) targets))
      (seq vias) (assoc :vias vias))))

(defn- ->production-root-node
  "The requested `{:kind :production}` root: always expanded, and absent `:via` / `:matches`
   because there is no parent type."
  [{:keys [ancestors] :as ctx} name]
  (if (nil? (production-summary (:analysis ctx) name))
    {:error (str "no production named " name)}
    (let [ctx (assoc ctx :ancestors (conj ancestors name))]
      (vswap! (:seen ctx) conj name)
      (->production-node* ctx {:name name}
                          {:cycle? false
                           :seen? false
                           :children (->production-children ctx name)}))))

;; --- cursor token resolution ---------------------------------------------

(defn- ->global-candidates
  "Known fact types the token resolves to globally: direct resolution plus every production's
   callsite linkage."
  [runtime analysis caller-ns-sym token]
  (let [{:keys [resolve-token token->fq-sym]} runtime
        direct (resolve-token caller-ns-sym token)
        known (set (keys (:fact-types analysis)))]
    (set/intersection
     known
     (cond-> (or (global-callsite-resolved-types analysis (token->fq-sym caller-ns-sym token)) #{})
       (tokens/real-type-name? direct) (conj direct)))))

(defn- ->scoped-rhs-candidates
  "Known fact types the token resolves to inside `production`'s RHS: its own callsites and the
   declared RHS types win over the global scan, so a shared helper stays rule-local."
  [runtime analysis production caller-ns-sym token]
  (let [summary (production-summary analysis production)
        resolve-ns (or caller-ns-sym (some-> production symbol namespace symbol))
        candidates (resolve-rhs-types runtime summary resolve-ns token)
        declared (declared-rhs-type-names summary)
        known (set (keys (:fact-types analysis)))]
    (or (not-empty (set/intersection candidates declared))
        (set/intersection candidates known))))

(defn- resolve-cursor-fact-type
  "The fact-type name a `:cursor` input points at, or nil. Deliberately independent of
   `navigate`'s non-empty-target rule, so a type with zero producers or zero consumers still
   roots a tree. Scoped RHS callsites win when the input names an enclosing production and a
   structural `:rhs` side; everything else resolves globally."
  [runtime analysis {:keys [production side caller-ns token]}]
  (let [caller-ns-sym (some-> caller-ns symbol)
        candidates (if (and production (= :rhs side) (production-summary analysis production))
                     (->scoped-rhs-candidates runtime analysis production caller-ns-sym token)
                     (->global-candidates runtime analysis caller-ns-sym token))]
    (first (sort candidates))))

(s/defn fact-type-provenance :- schema/FactTypeProvenanceResponse
  "Answers one `schema/FactTypeProvenanceInput` over `analysis`: a producer (upstream) or consumer
   (downstream) reference tree around one fact type or one production.

   `:depth` is how many production levels below the requested root to inline (`:full` expands
   until a path cycle repeats, `:cycle?`, or a production was already expanded in this response,
   `:seen?`). A `:cursor` root resolves any token to its fact type; a `:production` root expands
   that production one layer. Performs no input validation beyond what the schema cannot express —
   `clara.explorer.server.client/fact-type-provenance` is the validating, logging JVM entry point."
  [analysis :- s/Any runtime :- schema/NavigateRuntime input :- schema/FactTypeProvenanceInput]
  (let [{:keys [node direction depth ancestors]} input
        missing-ancestor (first (remove #(production-summary analysis %) ancestors))]
    (cond
      (not (contains? provenance-directions direction))
      {:error (str "unknown :direction " (pr-str direction))}

      (and (integer? depth) (neg? depth))
      {:error (str ":depth must be a non-negative integer or :full, got " (pr-str depth))}

      (some? missing-ancestor)
      {:error (str "no production named " missing-ancestor)}

      :else
      (let [ctx {:runtime   runtime
                 :analysis  analysis
                 :direction direction
                 :ancestors (set ancestors)
                 :seen      (volatile! #{})
                 :remaining depth}]
        (case (:kind node)
          :cursor
          (if-let [type-name (resolve-cursor-fact-type runtime analysis (:input node))]
            {:node (->fact-type-node ctx type-name nil)}
            {:error (str "no fact type found under cursor for token "
                         (pr-str (get-in node [:input :token])))})

          :production
          (let [root (->production-root-node ctx (:name node))]
            (if (:error root) root {:node root}))

          {:error (str "unknown :node kind " (pr-str (:kind node)))})))))
