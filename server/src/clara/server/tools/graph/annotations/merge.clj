(ns clara.server.tools.graph.annotations.merge
  "Layered annotations: format, merge semantics, provenance, and derivation —
   see docs/rule-annotations.md, \"Sources of Annotations\" and \"Annotation
   Merging\".

   A *layer* is one annotation source (generated analysis, curated file, rule
   :props) in the form it is written and read.  `merge-layers` folds an
   ordered sequence of layers — lowest precedence first — into
   `MergedAnnotations`: sparse overlay semantics (omission = no opinion),
   explicit-nil tombstones, deep callsite merge keyed by :callsite-id, and
   per-key provenance."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [schema.core :as s]
            [clara.rules.engine :as eng]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.annotations.callsite :as ann.callsite]
            [clara.server.tools.graph.serialize :as serialize]))

(def ^:private RuleName s/Str)

;; The analyzer is type-agnostic; token shape is the caller's decision.
(def ^:private FactType s/Any)

(s/defschema MergeProps
  "Per-key merge strategy overrides.  Keys are the unqualified names of
   annotation keys."
  {(s/optional-key :insert-types) (s/enum :union :replace)
   (s/optional-key :retract-types) (s/enum :union :replace)
   (s/optional-key :notes) (s/enum :replace :append)
   (s/optional-key :dynamic-insert-types-detected) (s/enum :deep :replace)
   (s/optional-key :dynamic-retract-types-detected) (s/enum :deep :replace)})

(s/defschema RuleAnnotation
  "Open map: unknown keys are preserved through merges.  Every value is
   `s/maybe` because an explicit nil is a tombstone — distinct from the key
   being absent, which means 'no opinion'."
  {(s/optional-key :clara-rules/insert-types) (s/maybe [FactType])
   (s/optional-key :clara-rules/retract-types) (s/maybe [FactType])
   (s/optional-key :clara-rules/no-output-types) (s/maybe s/Bool)
   (s/optional-key :clara-rules/notes) (s/maybe s/Str)
   (s/optional-key :clara-rules/dynamic-insert-types-detected) (s/maybe ann.callsite/DetectionMap)
   (s/optional-key :clara-rules/dynamic-retract-types-detected) (s/maybe ann.callsite/DetectionMap)
   (s/optional-key :clara-rules/merge-props) MergeProps
   s/Any s/Any})

(s/defschema Layer
  "One annotation source, in the form it is written and read.  `:annotations`
   is the payload; everything else describes where the layer came from and
   how it wants to be merged.  `:source` is descriptive only — never
   interpreted, never resolved."
  {:id ann.callsite/LayerId
   :annotations {RuleName RuleAnnotation}
   (s/optional-key :source) s/Any
   (s/optional-key :merge-props) MergeProps
   (s/optional-key :notes) s/Str})

(s/defschema Origin
  "Where a merged value came from: one layer, several (for keys merged by
   union or deep callsite merge), or the derivation pass rather than any
   layer."
  (s/cond-pre (s/eq :derived) ann.callsite/LayerId [ann.callsite/LayerId]))

(s/defschema MergedAnnotations
  "The output of `merge-layers`: the payload plus enough provenance to answer
   'which layer claimed this?' without re-running the merge.  Per-callsite
   provenance lives on the entry as `:from-layer`."
  {:annotations {RuleName RuleAnnotation}
   :layers [{:id ann.callsite/LayerId (s/optional-key :source) s/Any}]
   :provenance {RuleName {s/Keyword Origin}}})

;; ---------------------------------------------------------------------------
;; Layer construction and IO
;; ---------------------------------------------------------------------------

(defn normalize-layer
  "Normalizes a layer map: rule-name keys under `:annotations` become strings
   in a sorted map (same contract as `ann/normalize-annotations`), and
   callsite ids are derived for entries that omit them."
  [m]
  (update m :annotations
          (fn [anns]
            (into (sorted-map)
                  (map (fn [[k v]] [(ann/normalize-rule-name k)
                                    (ann.callsite/derive-ids-in-rule-annotation v)]))
                  anns))))

(defn layer
  "Constructs and validates an in-memory Layer.  Layers are plain values — an
   in-memory layer is a first-class input everywhere a file-backed one is."
  [m]
  (s/validate Layer (normalize-layer m)))

(defn read-layer
  "Reads an EDN file into a Layer.  `:source` defaults to the path; entries
   in `m` override file content.  Rule-name keys are normalized to strings."
  ([path] (read-layer path {}))
  ([path m]
   (let [f (io/file path)]
     (when-not (.exists f)
       (throw (ex-info (format "annotation layer file does not exist: %s" path)
                       {:path (str path)})))
     (with-open [r (io/reader f)]
       (layer (merge {:source (str path)}
                     (edn/read (java.io.PushbackReader. r))
                     m))))))

(defn write-layer!
  "Writes a Layer as pretty-printed EDN.  `*print-meta*` is bound false:
   reader metadata from synthesized analysis snippets must not leak into
   artifacts."
  [path layer]
  (with-open [w (io/writer path)]
    (binding [*print-meta* false]
      (pp/pprint layer w))))

(defn props-layer
  "The rule-:props layer: annotations authored on the rule form itself, read
   off the compiled productions.  The whole `:props` map is copied — nothing
   is filtered; unknown keys are preserved through every merge and reach
   consumers untouched.  Accepts a session or a rulebase.  Position in the
   fold is the caller's choice — first (the convention) makes source-authored
   types the base that generated and curated layers add to."
  [session-or-rulebase]
  (let [rulebase (if (:productions session-or-rulebase)
                   session-or-rulebase
                   (-> session-or-rulebase eng/components :rulebase))
        anns (into {}
                   (keep (fn [p]
                           (when (seq (:props p))
                             [(:name p) (:props p)])))
                   (:productions rulebase))]
    (layer {:id :props :source :rulebase :annotations anns})))

;; ---------------------------------------------------------------------------
;; Merge internals
;; ---------------------------------------------------------------------------

(def ^:private conclusion-keys
  "Callsite fields that constitute a conclusion (as opposed to discovery);
   the highest layer declaring any of them becomes the entry's `:from-layer`."
  [:status :resolved-types :resolution-evidence])

(defn- stamp-callsite
  "Prepares an authored callsite entry for the merged output: derived fields
   (`:from-layer`, `:dangling?`) are never taken from a layer."
  [layer-id c]
  (-> c
      (dissoc :from-layer :dangling?)
      (assoc :from-layer layer-id)))

(defn- merge-callsite-entry
  "Field-level merge of two callsite entries with the same id: the upper
   entry's declared fields win, so a sparse conclusion keeps the analyzer's
   discovery fields without restating them."
  [layer-id a b]
  (let [b' (dissoc b :from-layer :dangling?)
        conclusion? (some #(contains? b' %) conclusion-keys)]
    (cond-> (merge a b')
      conclusion? (assoc :from-layer layer-id))))

(defn- normalize-detection-map
  "First-fold preparation of an authored detection map: callsites are
   stamped, `:resolution` is recomputed.  Returns nil when the map has no
   callsites."
  [layer-id dm]
  (when-let [callsites (not-empty (mapv #(stamp-callsite layer-id %) (:callsites dm)))]
    ;; non-callsite keys (e.g. :fact-instance-derived-types from session
    ;; enrichment) survive
    (merge (dissoc dm :callsites :resolution)
           {:callsites callsites
            :resolution (ann.callsite/aggregate-resolution callsites)})))

(defn- merge-detection-maps
  "Deep detection-map merge: callsites keyed by `:callsite-id`, union of ids
   in `a`'s order with `b`-only entries appended, field-level wins for
   overlaps, `:resolution` recomputed.  `:replace` takes `b` wholesale (still
   stamped and recomputed)."
  [layer-id strategy a b]
  (if (= :replace strategy)
    (normalize-detection-map layer-id b)
    (let [b-by-id (into {} (map (juxt :callsite-id identity)) (:callsites b))
          merged-a (mapv (fn [ca]
                           (if-let [cb (get b-by-id (:callsite-id ca))]
                             (merge-callsite-entry layer-id ca cb)
                             ca))
                         (:callsites a))
          a-ids (into #{} (map :callsite-id) (:callsites a))
          b-only (into []
                       (comp (remove #(contains? a-ids (:callsite-id %)))
                             (map #(stamp-callsite layer-id %)))
                       (:callsites b))
          callsites (not-empty (into merged-a b-only))]
      (when callsites
        ;; non-callsite keys from both sides survive the deep merge,
        ;; with the incoming layer (b) winning over the accumulator (a).
        (merge (dissoc a :callsites :resolution)
               (dissoc b :callsites :resolution)
               {:callsites callsites
                :resolution (ann.callsite/aggregate-resolution callsites)})))))

(defn dedupe-by
  "Like `distinct` but compares by (f x) rather than x itself."
  [f coll]
  (let [seen (volatile! (transient #{}))]
    (filterv #(let [k (f %)]
                (if (contains? @seen k)
                  false
                  (do (vswap! seen conj! k) true)))
             coll)))

(defn- merge-type-vec
  "Type-vector merge: default union, `a` first, deduplicated by
   `serialize/resolve-type` under the rule's namespace so an unqualified
   symbol from :props, its Class, and a qualified sidecar symbol all
   converge.  `:replace` takes `b` only."
  [strategy rule-ns a b]
  (let [resolve-fn (partial serialize/resolve-type rule-ns)]
    (if (= :replace strategy)
      (dedupe-by resolve-fn b)
      (dedupe-by resolve-fn (into (vec a) b)))))

(defn- contributing
  "Adds a layer id to a union/deep-merge origin: keys merged by union record
   several layers, in precedence order."
  [origin layer-id]
  (cond
    (nil? origin) [layer-id]
    (vector? origin) (if (some #(= layer-id %) origin) origin (conj origin layer-id))
    :else [origin layer-id]))

(defn- fold-detection-key
  "Folds a detection-map key.  A value with `:callsites` goes through the
   deep/id-keyed machinery; a value without (e.g. the session-enrichment
   channel, which carries only `:fact-instance-derived-types`) is opaque —
   last declared wins."
  [layer-id props strategy-key merged prov k v]
  (if-not (contains? v :callsites)
    ;; Opaque value without callsites (e.g. session-enrichment channel
    ;; carrying only :fact-instance-derived-types) — merge into any
    ;; existing value rather than replacing it wholesale.
    [(assoc merged k (merge (get merged k) v))
     (assoc prov k (contributing (get prov k) layer-id))]
    (let [strategy (get props strategy-key :deep)
          merged-dm (if-let [a (get merged k)]
                      (merge-detection-maps layer-id strategy a v)
                      (normalize-detection-map layer-id v))]
      [(if merged-dm (assoc merged k merged-dm) (dissoc merged k))
       (if merged-dm
         (assoc prov k (if (= :replace strategy)
                         layer-id
                         (contributing (get prov k) layer-id)))
         (dissoc prov k))])))

(defn- fold-key
  "Folds one key of a layer's rule entry into `[merged-entry provenance-entry]`.
   `props` is the effective merge-props for this layer and rule.
   `rule-ns` is the rule's namespace for type canonicalization."
  [layer-id props rule-ns merged prov k v]
  (if (nil? v)
    ;; explicit nil is a tombstone — erase the key and its provenance
    [(dissoc merged k) (dissoc prov k)]
    (case k
      :clara-rules/insert-types
      (let [strategy (get props :insert-types :union)]
        [(assoc merged k (merge-type-vec strategy rule-ns (get merged k) v))
         (assoc prov k (if (= :replace strategy)
                         layer-id
                         (contributing (get prov k) layer-id)))])

      :clara-rules/retract-types
      (let [strategy (get props :retract-types :union)]
        [(assoc merged k (merge-type-vec strategy rule-ns (get merged k) v))
         (assoc prov k (if (= :replace strategy)
                         layer-id
                         (contributing (get prov k) layer-id)))])

      ;; last declared wins
      :clara-rules/no-output-types
      [(assoc merged k v) (assoc prov k layer-id)]

      :clara-rules/notes
      (let [strategy (get props :notes :replace)]
        [(assoc merged k (if (and (= :append strategy) (string? (get merged k)))
                           (str (get merged k) "\n" v)
                           v))
         (assoc prov k (if (= :append strategy)
                         (contributing (get prov k) layer-id)
                         layer-id))])

      :clara-rules/dynamic-insert-types-detected
      (fold-detection-key layer-id props :dynamic-insert-types-detected merged prov k v)

      :clara-rules/dynamic-retract-types-detected
      (fold-detection-key layer-id props :dynamic-retract-types-detected merged prov k v)

      ;; unknown keys: last declared wins, preserved
      [(assoc merged k v) (assoc prov k layer-id)])))

(defn fold-layer
  "Folds one layer into the merge accumulator `{:annotations {} :provenance {}}`."
  [acc layer]
  (let [layer-id (:id layer)
        layer-props (:merge-props layer)]
    (reduce-kv
     (fn [acc rule-name entry]
       (let [rn (ann/normalize-rule-name rule-name)
             ;; merge strategy: rule-level over layer-level over default;
             ;; merge-props is a directive, consumed here and never emitted
             props (merge layer-props (:clara-rules/merge-props entry))
             entry' (dissoc entry :clara-rules/merge-props)
             [merged prov] (reduce-kv
                            (fn [[m p] k v] (fold-key layer-id props (ann/fq-name->namespace rn) m p k v))
                            [(get-in acc [:annotations rn] {})
                             (get-in acc [:provenance rn] {})]
                            entry')]
         (-> acc
             (assoc-in [:annotations rn] merged)
             (assoc-in [:provenance rn] prov))))
     acc
     (:annotations layer))))

;; ---------------------------------------------------------------------------
;; Dangling references
;;
;; Only the analyzer *discovers* callsites; every other layer *annotates* ones
;; that already exist.  A merged callsite entry is dangling when it has no
;; discovered form — no :source-str.  A layer-introduced entry that supplies
;; its own :source-str (an assertion about a callsite the analyzer missed) is
;; legitimate and not dangling.
;; ---------------------------------------------------------------------------

(defn- dangling-entry?
  [c]
  (nil? (:source-str c)))

(defn- apply-dangling-policy
  "Applies the `:on-dangling` policy to merged annotations: :quarantine
   (default) marks dangling entries `:dangling? true` (excluded from type
   derivation and the resolution aggregate); :keep treats them as ordinary
   entries; :drop removes them.  `:resolution` is recomputed after the policy
   is applied."
  [policy annotations]
  (into (sorted-map)
        (map (fn [[rule-name rule-ann]]
               [rule-name
                (reduce (fn [ra k]
                          (let [dm (get ra k)]
                            (if-not (contains? dm :callsites)
                              ra
                              (let [cs (:callsites dm)
                                    cs' (case policy
                                          :keep cs
                                          :drop (into [] (remove dangling-entry?) cs)
                                          :quarantine (mapv #(if (dangling-entry? %)
                                                               (assoc % :dangling? true)
                                                               %)
                                                            cs))]
                                (if (seq cs')
                                  (let [resolution (ann.callsite/aggregate-resolution cs')]
                                    (assoc ra k (cond-> (dissoc dm :resolution)
                                                  true (assoc :callsites cs')
                                                  resolution (assoc :resolution resolution))))
                                  (dissoc ra k))))))
                        rule-ann
                        ann.callsite/detection-keys)]))
        annotations))

(defn- prune-provenance
  "Drops provenance entries for annotation keys that no longer exist (e.g. a
   detection map emptied by the `:drop` dangling policy)."
  [annotations provenance]
  (into {}
        (map (fn [[rule-name prov]]
               [rule-name (select-keys prov (keys (get annotations rule-name)))]))
        provenance))

;; ---------------------------------------------------------------------------
;; Type derivation
;;
;; After merging, one pass derives rule-level conclusions from the merged
;; evidence: each dimension's :resolution is recomputed, and resolved callsite
;; types are promoted into :clara-rules/insert-types / :retract-types.
;; Derivation reads *only the merged annotation* — never the individual
;; layers — so a :replace or tombstone the merge honored is not undone.
;;
;; Per rule and dimension there are two inputs:
;;   A — the merged authored types (whatever survived the merge's
;;       union / :replace / tombstone rules)
;;   D — the types promoted from the merged detection map's non-quarantined
;;       callsites
;; :additive       → A ∪ D
;; :from-callsites → D for a dimension that has callsites, else A
;; ---------------------------------------------------------------------------

(def dimension-derivation-keys
  "[dimension type-key detection-key] triples, in derivation order."
  [[:insert :clara-rules/insert-types :clara-rules/dynamic-insert-types-detected]
   [:retract :clara-rules/retract-types :clara-rules/dynamic-retract-types-detected]])

(defn- derive-rule-annotation
  "Derives one rule's conclusions.  `rule-ns` is the rule's namespace, used
   for type canonicalization so an unqualified symbol, its Class, and a
   qualified sidecar symbol all deduplicate correctly."
  [mode rule-ns rule-ann]
  (let [resolve-fn (partial serialize/resolve-type rule-ns)]
    (reduce (fn [ra [_ types-k dm-k]]
              (let [dm (get ra dm-k)
                    a (get ra types-k)
                    d (dedupe-by resolve-fn
                                 (into []
                                       (comp (remove :dangling?)
                                             (mapcat :resolved-types))
                                       (:callsites dm)))
                    final (case mode
                            :additive (dedupe-by resolve-fn (into (vec a) d))
                            ;; 'has a detection map' means has callsites — with
                            ;; no callsites there is nothing to derive from and
                            ;; the authored types stand
                            :from-callsites (if (seq (:callsites dm)) d (vec a)))
                    ra' (if (seq final)
                          (assoc ra types-k final)
                          (dissoc ra types-k))
                  ;; recompute :resolution; a detection map with an empty
                  ;; callsites vector carries nothing and is dropped; a map
                  ;; with no :callsites at all (session enrichment) is opaque
                    dm' (cond
                          (nil? dm) nil
                          (not (contains? dm :callsites)) dm
                          (seq (:callsites dm)) (if-let [resolution (ann.callsite/aggregate-resolution
                                                                     (:callsites dm))]
                                                  (assoc dm :resolution resolution)
                                                  (dissoc dm :resolution)))]
                (if dm'
                  (assoc ra' dm-k dm')
                  (dissoc ra' dm-k))))
            rule-ann
            dimension-derivation-keys)))

(defn derive-conclusions
  "Derives rule-level conclusions from merged evidence: dimension
   `:resolution` is recomputed and resolved callsite types are promoted into
   `:clara-rules/insert-types` / `:retract-types` — which is what makes a
   curated callsite produce a graph edge without anyone hand-writing a type.
   Reads only the given annotations; idempotent, so a caller can re-derive
   after hand-assembling annotations without re-merging.

   opts:
     :type-derivation — :additive (default) | :from-callsites"
  ([annotations] (derive-conclusions annotations {}))
  ([annotations {:keys [type-derivation] :or {type-derivation :additive}}]
   (into (sorted-map)
         (map (fn [[rule-name rule-ann]]
                [(ann/normalize-rule-name rule-name)
                 (derive-rule-annotation type-derivation
                                         (ann/fq-name->namespace rule-name)
                                         rule-ann)]))
         annotations)))

(defn- derive-with-provenance
  "Runs derivation over merged annotations and marks provenance `:derived`
   for any type key the derivation pass changed."
  [mode annotations provenance]
  (reduce-kv
   (fn [m rule-name rule-ann]
     (let [derived (derive-rule-annotation mode
                                           (ann/fq-name->namespace rule-name)
                                           rule-ann)
           prov (reduce (fn [p [_ types-k _]]
                          (if (= (get rule-ann types-k) (get derived types-k))
                            p
                            (assoc p types-k :derived)))
                        (get provenance rule-name)
                        dimension-derivation-keys)]
       (-> m
           (assoc-in [:annotations rule-name] derived)
           (assoc-in [:provenance rule-name] prov))))
   {:annotations (sorted-map) :provenance {}}
   annotations))

;; ---------------------------------------------------------------------------
;; The merge and accessors
;; ---------------------------------------------------------------------------

(s/defschema MergeOpts
  {(s/optional-key :on-dangling) (s/enum :quarantine :keep :drop)
   ;; :none skips derivation (internal — used by validate-layers to compare
   ;; pre/post-derivation state)
   (s/optional-key :type-derivation) (s/enum :additive :from-callsites :none)
   s/Keyword s/Any})

(defn merge-layers
  "Folds `layers` — ordered lowest precedence first — into MergedAnnotations.
   The rightmost layer wins a conflict.  Layer ids must be distinct: with two
   layers named the same, `:provenance` and `:from-layer` become ambiguous, so
   a repeated `:id` throws.

   opts:
     :on-dangling     — :quarantine (default) | :keep | :drop
     :type-derivation — :additive (default) | :from-callsites"
  ([layers] (merge-layers layers {}))
  ([layers opts]
   (let [{:keys [on-dangling type-derivation]
          :or {on-dangling :quarantine type-derivation :additive}}
         (s/validate MergeOpts opts)
         layers (mapv #(s/validate Layer (normalize-layer %)) layers)
         dups (->> (map :id layers)
                   (frequencies)
                   (into [] (comp (filter #(> (val %) 1)) (map key))))]
     (when (seq dups)
       (throw (ex-info (format "duplicate layer :id %s — layer ids must be distinct" dups)
                       {:duplicate-ids dups})))
     (let [{:keys [annotations provenance]}
           (reduce fold-layer {:annotations (sorted-map) :provenance {}} layers)
           annotations (apply-dangling-policy on-dangling annotations)
           provenance (prune-provenance annotations provenance)
           {:keys [annotations provenance]} (if (= :none type-derivation)
                                              {:annotations annotations
                                               :provenance provenance}
                                              (derive-with-provenance type-derivation
                                                                      annotations
                                                                      provenance))]
       {:annotations annotations
        :layers (mapv #(select-keys % [:id :source]) layers)
        :provenance provenance}))))

(defn annotations
  "Unwraps MergedAnnotations to the bare rule→annotation map for consumers
   that do not care about provenance."
  [merged]
  (:annotations merged))

(defn provenance
  "Per-rule, per-annotation-key origins."
  ([merged] (:provenance merged))
  ([merged rule-name]
   (get (:provenance merged) (ann/normalize-rule-name rule-name))))

;; ---------------------------------------------------------------------------
;; MergedAnnotations type predicate and coercion
;; ---------------------------------------------------------------------------

(defn merged-annotations?
  "True when `x` is a MergedAnnotations value — a map with both
   `:annotations` and `:provenance` keys.  Key membership is tested with
   `some` because bare maps may have string keys and `contains?` throws
   ClassCastException on those."
  [x]
  (boolean
   (and (map? x)
        (some #{:annotations} (keys x))
        (some #{:provenance} (keys x)))))

(defn ->bare-annotations
  "Unwraps a MergedAnnotations to its bare rule→annotation map; bare maps
   pass through unchanged.  Use at coercion boundaries where either form
   may arrive."
  [x]
  (if (merged-annotations? x)
    (:annotations x)
    x))

(defn annotations-delta->layer
  "Wraps an `annotations-delta` result as a validated Layer with the given
  `id` and `source` provenance info.

  `delta-annotations` holds **only what was added** over the base — see
  `ann/annotations-delta`.  Carrying the full enriched map instead would make
  this layer re-claim every key the base already owns, defeating the
  provenance the split exists to preserve."
  [id source delta-annotations]
  (layer {:id id
          :source source
          :annotations delta-annotations}))

(defn ->layer
  "Coerces `x` to a Layer: a path string or File is read from disk via
   `read-layer`; a map is validated as an in-memory layer via `layer`."
  [x]
  (if (or (string? x) (instance? java.io.File x))
    (read-layer x)
    (layer x)))

(defn coerce-to-bare-annotations
  "Coerces an annotations input to a bare rule→annotation map.

   `annotations-input` may be:
     - A bare rule→annotation map (passes through)
     - A MergedAnnotations value (unwrapped to its `:annotations` payload)
     - A vector of Layer maps (merged via `merge-layers`, with
       `props-layer` from `session` folded in first as the base)
     - A string path to a layer file (read via `read-layer` and merged).

   `session` is only needed when `annotations-input` is a vector of layers
   or a string path."
  [annotations-input session]
  (let [layers (cond
                 (or (string? annotations-input)
                     (instance? java.io.File annotations-input))
                 [annotations-input]
                 (vector? annotations-input) annotations-input
                 :else nil)]
    (if layers
      (:annotations
       (merge-layers
        (into [(props-layer session)]
              (map ->layer)
              layers)))
      (->bare-annotations annotations-input))))
