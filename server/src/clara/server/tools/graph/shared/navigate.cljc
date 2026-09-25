(ns clara.server.tools.graph.shared.navigate
  "Pure editor navigation over a rehydrated rulebase-analysis map: given an
   analysis and a `shared-schema/NavigateInput`, answers which productions
   produce or consume the type under the cursor.

   Runtime-provided capabilities arrive as a `shared-schema/NavigateRuntime`,
   so this namespace stays free of namespace resolution, class-loading, and
   var metadata. `clara.server.graph.client/navigate` supplies the JVM
   implementations (live `ns-resolve` over the editor's namespaces, source
   locations from var metadata); the babashka client supplies
   fully-qualified-assuming ones with an always-absent source.

   Discipline: dependency-free like `tokens` (only `clojure.*`, `schema.core`,
   plus shared requires) so both runtimes load this file."
  (:require [clara.server.tools.graph.shared.schema :as shared-schema]
            [clara.server.tools.graph.shared.tokens :as tokens]
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
  "Resolves the RHS token to a set of candidate kind-explicit type names,
   combining direct (kind/ctor) resolution with the production's serialized
   dynamic-insert/retract callsite linkage."
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
  "Transducer dropping navigate targets that name `production` itself. The
   dep-graph carries no self-edges — both dep-graph builders refuse
   producer = consumer — so scoped navigation excludes the querying production
   from the global closure."
  [production]
  (remove #(= (:name %) production)))

(defn- dedupe-targets
  "Merges same-name navigate targets with `:retract` winning: one production
   can both insert and retract (a hierarchy reach of) one type, reaching the
   global closure twice. Answers one row per production, name-sorted like the
   closures."
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
  "Builds a `shared-schema/NavigateTarget` from a serialized production dep
   plus `via`, with the source location from the runtime."
  [runtime dep via]
  (let [source (:production-source runtime)]
    {:name   (:name dep)
     :ns     (:ns dep)
     :type   (:type dep)
     :via    via
     :source (source (:name dep))}))

;; ---------------------------------------------------------------------------
;; Global closures (hierarchy-aware production refs per fact type)
;; ---------------------------------------------------------------------------

(defn- global-producer-targets
  "Builds navigate targets from a fact type's `inserted-by-rules` /
   `retracted-by-rules` production refs (hierarchy-aware producers)."
  [runtime analysis type-name]
  (let [fact-type (get-in analysis [:fact-types type-name])
        inserted (:inserted-by-rules fact-type)
        retracted (:retracted-by-rules fact-type)]
    (->> (concat (map #(dep->target runtime % :insert) inserted)
                 (map #(dep->target runtime % :retract) retracted))
         (sort-by :name)
         vec)))

(defn- global-consumer-targets
  "Builds navigate targets from a fact type's `used-by-rules` /
   `used-by-queries` production refs (hierarchy-aware consumers)."
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
        candidates (cond-> callsite-names
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
                      (->> matched
                           (mapcat #(global-producer-targets runtime analysis %))
                           (sort-by :name)
                           vec)
                      (->> matched
                           (mapcat #(global-consumer-targets runtime analysis %))
                           (sort-by :name)
                           vec))
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

(s/defn navigate :- shared-schema/NavigateResponse
  "Resolves editor navigation for a fact-type token over `analysis`,
   answering a `shared-schema/NavigateResponse`. `input` is a
   `shared-schema/NavigateInput` (`:production` nil selects the global
   path). Performs no input validation and no logging —
   `clara.server.graph.client/navigate` is the validating, logging JVM
   entry point."
  [analysis :- s/Any runtime :- shared-schema/NavigateRuntime input :- shared-schema/NavigateInput]
  (let [{:keys [production side caller-ns token]} input
        caller-ns-sym (some-> caller-ns symbol)]
    (if (nil? production)
      (navigate-global runtime analysis caller-ns-sym token side)
      (navigate-scoped runtime analysis production side caller-ns-sym token))))
