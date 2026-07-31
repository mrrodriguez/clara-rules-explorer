(ns clara.server.tools.graph.annotations
  "Layered rule annotations: format, merge semantics, provenance, reporting,
   and validation — see docs/anno-merging-update-plan.md §4–§7.  Handles
   arbitrary fact types (classes, keywords, symbols) as supported by Clara's
   pluggable fact-type-fn.

   A *layer* is one annotation source (generated analysis, curated file, rule
   :props) in the form it is written and read.  `merge-layers` folds an
   ordered sequence of layers — lowest precedence first — into
   `MergedAnnotations`: sparse overlay semantics (omission = no opinion),
   explicit-nil tombstones, deep callsite merge keyed by :callsite-id, and
   per-key provenance.

   Rule-name normalization:
     Clara rules use strings for `:name` (schema: `s/Str` or `s/Keyword`),
     but annotations may arrive with symbol keys from EDN or kondo analysis.
     All public functions that touch annotation maps normalize top-level
     rule-name keys to strings: `normalize-rule-name` converts a single key,
     `normalize-annotations` transforms an entire map, and `get-annotation`
     normalizes the lookup key before access."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [schema.core :as s]
            [clara.rules.engine :as eng]
            [clara.server.tools.graph.analyze.callsite :as callsite]))

(declare normalize-annotations layer)

(defn normalize-rule-name
  "Normalizes a rule-name key to its canonical string form.
   Symbols and keywords are converted to strings; strings pass through."
  [k]
  (if (or (symbol? k) (keyword? k))
    (str (symbol k))
    k))

(defn- resolve-type-locally
  [production-ns x]
  (if (symbol? x)
    (try
      (let [resolved (and production-ns (ns-resolve production-ns x))]
        (cond
          (class? resolved) resolved
          ;; Not likely to have this happen.
          (var? resolved) (-> resolved deref)
          :else x))
      (catch Exception _ x))
    x))

(defn- get-production-ns
  [production]
  (some-> production :ns-name symbol the-ns))

(defn- resolve-types
  [production-ns unresolved-types]
  (mapv #(resolve-type-locally production-ns %)
        unresolved-types))

(defn- unqualify-keyword
  [kw]
  (if (and (keyword? kw) (namespace kw))
    (keyword (name kw))
    kw))

(defn normalize-annotations
  "Normalizes an annotations map to canonical form: all top-level rule-name
   keys are converted to strings via `normalize-rule-name`, and the result
   is a deterministically sorted map."
  [annotations]
  (into (sorted-map)
        (map (fn [[k v]] [(normalize-rule-name k) v]))
        annotations))

(defn get-annotation
  "Looks up a rule entry in the annotations map, normalizing the lookup key
   via `normalize-rule-name` before access."
  [annotations rule-name]
  (get annotations (normalize-rule-name rule-name)))

(defn props-layer
  "The rule-:props layer (plan §5.5): annotations authored on the rule form
   itself, read off the compiled productions.  The whole `:props` map is
   copied — nothing is filtered; unknown keys are preserved through every
   merge (F2) and reach consumers untouched.  Accepts a session or a
   rulebase.  Position in the fold is the caller's choice — first (the
   convention) makes source-authored types the base that generated and
   curated layers add to."
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

(def ^:private production-annotation-keys
  #{:clara-rules/insert-types
    :clara-rules/retract-types
    :clara-rules/no-output-types
    :clara-rules/notes
    :clara-rules/dynamic-insert-types-detected
    :clara-rules/dynamic-retract-types-detected})

(defn- resolve-type-key
  [m k production-ns]
  (if (contains? m k)
    (update m k #(resolve-types production-ns %))
    m))

(defn production-annotation
  "Reads one production's annotation from a merged (or bare) annotations map
   with unqualified keys (:insert-types, :retract-types, :no-output-types,
   :notes, :dynamic-insert-types-detected,
   :dynamic-retract-types-detected).  Symbol type tokens are resolved against
   the production's namespace (classes, vars) as Clara's pluggable
   fact-type-fn allows.  The lookup tolerates an unnormalized map (symbol or
   keyword rule-name keys) as a convenience."
  [annotations production]
  (let [annotations (if (every? (comp string? key) annotations)
                      annotations
                      (normalize-annotations annotations))
        rule-ann (get-annotation annotations (:name production))
        production-ns (get-production-ns production)]
    (-> (select-keys rule-ann production-annotation-keys)
        (resolve-type-key :clara-rules/insert-types production-ns)
        (resolve-type-key :clara-rules/retract-types production-ns)
        (update-keys unqualify-keyword))))

;; ===========================================================================
;; Layered annotations — docs/anno-merging-update-plan.md §4–§5
;;
;; A *layer* is one annotation source (generated analysis, curated file, rule
;; :props) in the form it is written and read.  `merge-layers` folds an
;; ordered sequence of layers — lowest precedence first — into
;; `MergedAnnotations`: sparse overlay semantics (omission = no opinion),
;; explicit-nil tombstones, deep callsite merge keyed by :callsite-id, and
;; per-key provenance.
;; ===========================================================================

(s/defschema LayerId (s/cond-pre s/Keyword s/Str))

(def ^:private RuleName s/Str)

;; The analyzer is type-agnostic; token shape is the caller's decision.
(def ^:private FactType s/Any)

(s/defschema CallsiteId (s/named s/Str "CallsiteId"))

(s/defschema Resolution
  "Three-valued resolution vocabulary, used per-callsite and per-dimension
   (plan §4.3)."
  (s/enum :none :partial :full))

(s/defschema MergeProps
  "Per-key merge strategy overrides (plan §4.7).  Keys are the unqualified
   names of annotation keys."
  {(s/optional-key :insert-types) (s/enum :union :replace)
   (s/optional-key :retract-types) (s/enum :union :replace)
   (s/optional-key :notes) (s/enum :replace :append)
   (s/optional-key :dynamic-insert-types-detected) (s/enum :deep :replace)
   (s/optional-key :dynamic-retract-types-detected) (s/enum :deep :replace)})

(s/defschema ResolutionEvidence
  "Why a callsite's conclusion is what it is (plan §4.4).  Open map; `:note`
   is the only key the library reads, and only to display it."
  {(s/optional-key :note) s/Str
   s/Any s/Any})

(s/defschema CallsiteEntry
  "One callsite in a layer or merged annotation (plan §4.4).  `:callsite-id`
   is optional on input — ids are derived on read for entries that omit one
   (phase 3) — and always present after `assign-callsite-ids`.  `:from-layer`
   and `:dangling?` are computed by the merge; authored values are ignored."
  {(s/optional-key :callsite-id) CallsiteId
   ;; discovery — only the analyzer produces these
   (s/optional-key :source-str) s/Str
   (s/optional-key :ns-name-sym) s/Symbol
   (s/optional-key :filename) s/Str
   (s/optional-key :constructor-sym) s/Symbol
   (s/optional-key :via) callsite/ViaChain
   (s/optional-key :fact-type) s/Any
   (s/optional-key :fact-type-spec) {s/Keyword s/Any}
   ;; conclusion — analyzer or curator
   :status Resolution
   (s/optional-key :resolved-types) [FactType]
   (s/optional-key :resolution-evidence) ResolutionEvidence
   ;; computed by the merge — never authored
   (s/optional-key :from-layer) LayerId
   (s/optional-key :dangling?) s/Bool})

(s/defschema DetectionMap
  "Plan §4.5.  `:resolution` is derived by the merge (§4.3) and never taken
   from a layer (F4), so it is optional on input and always present in merged
   output.  `:fact-instance-derived-types` is the session-enrichment channel
   (analyze/enrich-annotations-from-session) — types observed in working
   memory rather than resolved from source; such maps carry no `:callsites`
   and merge as opaque values."
  {(s/optional-key :callsites) [CallsiteEntry]
   (s/optional-key :resolution) Resolution
   (s/optional-key :fact-instance-derived-types) [s/Any]})

(s/defschema RuleAnnotation
  "Open map: unknown keys are preserved through merges (F2).  Every value is
   `s/maybe` because an explicit nil is a tombstone (§5.4) — distinct from the
   key being absent, which means 'no opinion'."
  {(s/optional-key :clara-rules/insert-types) (s/maybe [FactType])
   (s/optional-key :clara-rules/retract-types) (s/maybe [FactType])
   (s/optional-key :clara-rules/no-output-types) (s/maybe s/Bool)
   (s/optional-key :clara-rules/notes) (s/maybe s/Str)
   (s/optional-key :clara-rules/dynamic-insert-types-detected) (s/maybe DetectionMap)
   (s/optional-key :clara-rules/dynamic-retract-types-detected) (s/maybe DetectionMap)
   (s/optional-key :clara-rules/merge-props) MergeProps
   s/Any s/Any})

(s/defschema Layer
  "One annotation source, in the form it is written and read (plan §4.1).
   `:annotations` is the payload; everything else describes where the layer
   came from and how it wants to be merged.  `:source` is descriptive only —
   never interpreted, never resolved."
  {:id LayerId
   :annotations {RuleName RuleAnnotation}
   (s/optional-key :source) s/Any
   (s/optional-key :merge-props) MergeProps
   (s/optional-key :notes) s/Str})

(s/defschema Origin
  "Where a merged value came from (plan §4.6): one layer, several (for keys
   merged by union or deep callsite merge), or the derivation pass (§5.7)
   rather than any layer."
  (s/cond-pre (s/eq :derived) LayerId [LayerId]))

(s/defschema MergedAnnotations
  "The output of `merge-layers` (plan §4.6): the payload plus enough
   provenance to answer 'which layer claimed this?' without re-running the
   merge.  Per-callsite provenance lives on the entry as `:from-layer`."
  {:annotations {RuleName RuleAnnotation}
   :layers [{:id LayerId (s/optional-key :source) s/Any}]
   :provenance {RuleName {s/Keyword Origin}}})

;; ---------------------------------------------------------------------------
;; Callsite identity (§4.4)
;;
;; Identity is only needed within one rule and one dimension — the rule name
;; and dimension key already address the callsites vector.  Ids are
;; ns:ctor:hash:ordinal, where the hash covers
;; [ns-name-sym constructor-sym source-str] and the ordinal is the index
;; within the duplicate group (siblings sharing the full basis), so adding or
;; removing unrelated callsites does not renumber the group.
;; ---------------------------------------------------------------------------

(defn- sha256-hex
  [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest digest (.getBytes s "UTF-8"))))))

(defn- ctor-short-name
  [ctor-sym]
  (if ctor-sym (name ctor-sym) "-"))

(defn- callsite-basis
  "The id basis (plan §4.4): namespace, constructor, and source text.
   Deliberately excluded: :status/:resolved-types (what curation changes),
   :via (shifts when an unrelated helper is renamed), :row/:col (churn on
   every edit above), :filename (equivalent to :ns-name-sym, less legible)."
  [c]
  [(:ns-name-sym c) (:constructor-sym c) (:source-str c)])

(defn callsite-id
  "Content-hash identity for a callsite (plan §4.4): ns:ctor:hash — the
   namespace, the constructor's short name (`-` for boundary-path callsites),
   and the first 8 hex chars of SHA-256 over
   (pr-str [ns-name-sym constructor-sym source-str]).  No ordinal: ordinals
   require the sibling group — see `assign-callsite-ids`."
  [c]
  (let [[ns-name-sym constructor-sym _] (callsite-basis c)]
    (format "%s:%s:%s"
            ns-name-sym
            (ctor-short-name constructor-sym)
            (subs (sha256-hex (pr-str (callsite-basis c))) 0 8))))

(defn assign-callsite-ids
  "Assigns :callsite-id to every entry of one rule+dimension callsite vector
   (plan §4.4) — the only place ordinals can be computed.  Entries sharing
   the full basis (namespace, constructor, source text) form a duplicate
   group and receive 0-based ordinals *within the group*, so adding or
   removing unrelated callsites in the same rule does not renumber it.
   Collisions are detected at emission and resolved by lengthening the hash:
   ids are unique by construction, not by probability.  Entries must carry
   :ns-name-sym and :source-str."
  [callsites]
  (let [hashes (mapv (comp sha256-hex pr-str callsite-basis) callsites)
        prefixes (mapv (fn [[ns-name-sym constructor-sym _]]
                         (format "%s:%s" ns-name-sym (ctor-short-name constructor-sym)))
                       (map callsite-basis callsites))
        ;; occurrence index within the basis group (hash == basis, up to the
        ;; 64-hex collision that the lengthening loop below would also catch)
        ordinals (second (reduce (fn [[counts out] h]
                                   (let [o (get counts h 0)]
                                     [(assoc counts h (inc o)) (conj out o)]))
                                 [{} []]
                                 hashes))]
    (loop [len 8]
      (let [ids (mapv (fn [prefix h o]
                        (format "%s:%s:%d" prefix (subs h 0 (min len (count h))) o))
                      prefixes hashes ordinals)]
        (if (or (apply distinct? ids) (>= len 64))
          (mapv (fn [c id] (assoc c :callsite-id id)) callsites ids)
          (recur (inc len)))))))

(defn- has-id-basis?
  [c]
  (and (:ns-name-sym c) (:source-str c)))

(defn- derive-callsite-ids
  "Derives ids for entries that omit one (plan §4.4: hand-written layers need
   not compute hashes as long as they supply enough of the basis).  Entries
   carrying an id keep it; ordinals are computed over all basis-carrying
   siblings so a pre-assigned sibling still occupies its group position."
  [callsites]
  (if (every? :callsite-id callsites)
    callsites
    (let [new-ids (into clojure.lang.PersistentQueue/EMPTY
                        (map :callsite-id)
                        (assign-callsite-ids (filterv has-id-basis? callsites)))]
      (first (reduce (fn [[out q] c]
                       (cond
                         (not (has-id-basis? c)) [(conj out c) q]
                         (:callsite-id c) [(conj out c) (pop q)]
                         :else [(conj out (assoc c :callsite-id (peek q))) (pop q)]))
                     [[] new-ids]
                     callsites)))))

(def ^:private detection-keys
  [:clara-rules/dynamic-insert-types-detected
   :clara-rules/dynamic-retract-types-detected])

(defn- derive-ids-in-rule-annotation
  [rule-ann]
  (reduce (fn [ra k]
            (if-let [callsites (:callsites (get ra k))]
              (assoc-in ra [k :callsites] (derive-callsite-ids callsites))
              ra))
          rule-ann
          detection-keys))

;; ---------------------------------------------------------------------------
;; Layer construction and IO (§6)
;; ---------------------------------------------------------------------------

(defn- normalize-layer
  "Normalizes a layer map: rule-name keys under `:annotations` become strings
   in a sorted map (same contract as `normalize-annotations`), and callsite
   ids are derived for entries that omit them (§4.4)."
  [m]
  (update m :annotations
          (fn [anns]
            (into (sorted-map)
                  (map (fn [[k v]] [(normalize-rule-name k)
                                    (derive-ids-in-rule-annotation v)]))
                  anns))))

(defn layer
  "Constructs and validates an in-memory Layer (plan §6).  Layers are plain
   values — an in-memory layer is a first-class input everywhere a
   file-backed one is."
  [m]
  (s/validate Layer (normalize-layer m)))

(defn read-layer
  "Reads an EDN file into a Layer (plan §6).  `:source` defaults to the path;
   entries in `m` override file content.  Rule-name keys are normalized to
   strings."
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
  "Writes a Layer as pretty-printed EDN (plan §6).  `*print-meta*` is bound
   false: reader metadata from synthesized analysis snippets must not leak
   into artifacts (F7)."
  [path layer]
  (with-open [w (io/writer path)]
    (binding [*print-meta* false]
      (pp/pprint layer w))))

;; ---------------------------------------------------------------------------
;; Merge internals (§5)
;; ---------------------------------------------------------------------------

(defn aggregate-resolution
  "Dimension-level resolution from a callsite vector (plan §4.3):
   no callsites → nil (dimension absent), all :full → :full, all :none →
   :none, otherwise :partial.  Quarantined (`:dangling?`) callsites are
   excluded (§5.6).  The aggregation itself is shared with the analyzer as
   `callsite/resolution-status`."
  [callsites]
  (callsite/resolution-status (remove :dangling? callsites)))

(def ^:private conclusion-keys
  "Callsite fields that constitute a conclusion (as opposed to discovery);
   the highest layer declaring any of them becomes the entry's `:from-layer`."
  [:status :resolved-types :resolution-evidence])

(defn- stamp-callsite
  "Prepares an authored callsite entry for the merged output: derived fields
   (`:from-layer`, `:dangling?`, and the map-level `:resolution` handled by
   the caller) are never taken from a layer (F4)."
  [layer-id c]
  (-> c
      (dissoc :from-layer :dangling?)
      (assoc :from-layer layer-id)))

(defn- merge-callsite-entry
  "Field-level merge of two callsite entries with the same id (plan §5.3):
   the upper entry's declared fields win, so a sparse conclusion keeps the
   analyzer's discovery fields without restating them."
  [layer-id a b]
  (let [b' (dissoc b :from-layer :dangling?)
        conclusion? (some #(contains? b' %) conclusion-keys)]
    (cond-> (merge a b')
      conclusion? (assoc :from-layer layer-id))))

(defn- normalize-detection-map
  "First-fold preparation of an authored detection map: callsites are
   stamped, `:resolution` is recomputed (F4).  Returns nil when the map has
   no callsites."
  [layer-id dm]
  (when-let [callsites (not-empty (mapv #(stamp-callsite layer-id %) (:callsites dm)))]
    ;; non-callsite keys (e.g. :fact-instance-derived-types from session
    ;; enrichment) survive
    (merge (dissoc dm :callsites :resolution)
           {:callsites callsites
            :resolution (aggregate-resolution callsites)})))

(defn- merge-detection-maps
  "Deep detection-map merge (plan §5.3): callsites keyed by `:callsite-id`,
   union of ids in `a`'s order with `b`-only entries appended, field-level
   wins for overlaps, `:resolution` recomputed.  `:replace` takes `b`
   wholesale (still stamped and recomputed)."
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
        ;; non-callsite keys on `a` (e.g. :fact-instance-derived-types from
        ;; session enrichment) survive the deep merge
        (merge (dissoc a :callsites :resolution)
               {:callsites callsites
                :resolution (aggregate-resolution callsites)})))))

(defn- merge-type-vec
  "Type-vector merge (plan §5.2): default union, `a` first, distinct;
   `:replace` takes `b` only."
  [strategy a b]
  (if (= :replace strategy)
    (into [] (distinct) b)
    (into [] (comp cat (distinct)) [a b])))

(defn- contributing
  "Adds a layer id to a union/deep-merge origin (plan §4.6: keys merged by
   union record several layers, in precedence order)."
  [origin layer-id]
  (cond
    (nil? origin) [layer-id]
    (vector? origin) (if (some #(= layer-id %) origin) origin (conj origin layer-id))
    :else [origin layer-id]))

(defn- fold-detection-key
  "Folds a detection-map key.  A value with `:callsites` goes through the
   deep/id-keyed machinery (§5.3); a value without (e.g. the
   session-enrichment channel, which carries only
   `:fact-instance-derived-types`) is opaque — last declared wins."
  [layer-id props strategy-key merged prov k v]
  (if-not (contains? v :callsites)
    [(assoc merged k v) (assoc prov k layer-id)]
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
  "Folds one key of a layer's rule entry into `[merged-entry provenance-entry]`
   (plan §5.2, §5.4).  `props` is the effective merge-props for this layer and
   rule (§5.1)."
  [layer-id props merged prov k v]
  (if (nil? v)
    ;; explicit nil is a tombstone — erase the key and its provenance (§5.4)
    [(dissoc merged k) (dissoc prov k)]
    (case k
      :clara-rules/insert-types
      (let [strategy (get props :insert-types :union)]
        [(assoc merged k (merge-type-vec strategy (get merged k) v))
         (assoc prov k (if (= :replace strategy)
                         layer-id
                         (contributing (get prov k) layer-id)))])

      :clara-rules/retract-types
      (let [strategy (get props :retract-types :union)]
        [(assoc merged k (merge-type-vec strategy (get merged k) v))
         (assoc prov k (if (= :replace strategy)
                         layer-id
                         (contributing (get prov k) layer-id)))])

      ;; last declared wins (F5)
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

      ;; unknown keys: last declared wins, preserved (F2)
      [(assoc merged k v) (assoc prov k layer-id)])))

(defn- fold-layer
  "Folds one layer into the merge accumulator `{:annotations {} :provenance {}}`."
  [acc layer]
  (let [layer-id (:id layer)
        layer-props (:merge-props layer)]
    (reduce-kv
     (fn [acc rule-name entry]
       (let [rn (normalize-rule-name rule-name)
             ;; merge strategy: rule-level over layer-level over default (§5.1);
             ;; merge-props is a directive, consumed here and never emitted
             props (merge layer-props (:clara-rules/merge-props entry))
             entry' (dissoc entry :clara-rules/merge-props)
             [merged prov] (reduce-kv
                            (fn [[m p] k v] (fold-key layer-id props m p k v))
                            [(get-in acc [:annotations rn] {})
                             (get-in acc [:provenance rn] {})]
                            entry')]
         (-> acc
             (assoc-in [:annotations rn] merged)
             (assoc-in [:provenance rn] prov))))
     acc
     (:annotations layer))))

;; ---------------------------------------------------------------------------
;; Dangling references (§5.6)
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
  "Applies the `:on-dangling` policy to merged annotations (plan §5.6):
   :quarantine (default) marks dangling entries `:dangling? true` (excluded
   from type derivation and the resolution aggregate); :keep treats them as
   ordinary entries; :drop removes them.  `:resolution` is recomputed after
   the policy is applied."
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
                                  (let [resolution (aggregate-resolution cs')]
                                    (assoc ra k (cond-> (dissoc dm :resolution)
                                                  true (assoc :callsites cs')
                                                  resolution (assoc :resolution resolution))))
                                  (dissoc ra k))))))
                        rule-ann
                        detection-keys)]))
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
;; The merge (§5) and accessors (§6)
;; ---------------------------------------------------------------------------

(s/defschema MergeOpts
  {(s/optional-key :on-dangling) (s/enum :quarantine :keep :drop)
   ;; :none skips derivation (internal — used by validate-layers to compare
   ;; pre/post-derivation state)
   (s/optional-key :type-derivation) (s/enum :additive :from-callsites :none)
   s/Keyword s/Any})

;; ---------------------------------------------------------------------------
;; Type derivation (§5.7)
;;
;; After merging, one pass derives rule-level conclusions from the merged
;; evidence: each dimension's :resolution is recomputed (§4.3), and resolved
;; callsite types are promoted into :clara-rules/insert-types / :retract-types.
;; Derivation reads *only the merged annotation* — never the individual
;; layers — so a :replace or tombstone the merge honored is not undone.
;;
;; Per rule and dimension there are two inputs:
;;   A — the merged authored types (whatever survived §5.2's union / :replace
;;       / tombstone rules)
;;   D — the types promoted from the merged detection map's non-quarantined
;;       callsites
;; :additive      → A ∪ D
;; :from-callsites → D for a dimension that has a merged detection map, else A
;; ---------------------------------------------------------------------------

(def ^:private dimension-derivation-keys
  "[dimension type-key detection-key] triples, in derivation order."
  [[:insert :clara-rules/insert-types :clara-rules/dynamic-insert-types-detected]
   [:retract :clara-rules/retract-types :clara-rules/dynamic-retract-types-detected]])

(defn- derive-rule-annotation
  "Derives one rule's conclusions (§5.7).  Returns the derived annotation."
  [mode rule-ann]
  (reduce (fn [ra [_ types-k dm-k]]
            (let [dm (get ra dm-k)
                  a (get ra types-k)
                  d (into []
                          (comp (remove :dangling?)
                                (mapcat :resolved-types)
                                (distinct))
                          (:callsites dm))
                  final (case mode
                          :additive (into [] (comp cat (distinct)) [a d])
                          ;; 'has a detection map' means has callsites — with
                          ;; no callsites there is nothing to derive from and
                          ;; the authored types stand (§5.4)
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
                        (seq (:callsites dm)) (if-let [resolution (aggregate-resolution (:callsites dm))]
                                                (assoc dm :resolution resolution)
                                                (dissoc dm :resolution)))]
              (if dm'
                (assoc ra' dm-k dm')
                (dissoc ra' dm-k))))
          rule-ann
          dimension-derivation-keys))

(defn derive-conclusions
  "Derives rule-level conclusions from merged evidence (plan §5.7):
   dimension `:resolution` is recomputed and resolved callsite types are
   promoted into `:clara-rules/insert-types` / `:retract-types` — which is
   what makes a curated callsite produce a graph edge without anyone
   hand-writing a type.  Reads only the given annotations; idempotent, so a
   caller can re-derive after hand-assembling annotations without re-merging.

   opts:
     :type-derivation — :additive (default) | :from-callsites (§5.7)"
  ([annotations] (derive-conclusions annotations {}))
  ([annotations {:keys [type-derivation] :or {type-derivation :additive}}]
   (into (sorted-map)
         (map (fn [[rule-name rule-ann]]
                [(normalize-rule-name rule-name)
                 (derive-rule-annotation type-derivation rule-ann)]))
         annotations)))

(defn- derive-with-provenance
  "Runs derivation over merged annotations and marks provenance `:derived`
   for any type key the derivation pass changed (§4.6)."
  [mode annotations provenance]
  (reduce-kv
   (fn [m rule-name rule-ann]
     (let [derived (derive-rule-annotation mode rule-ann)
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

(defn merge-layers
  "Folds `layers` — ordered lowest precedence first — into MergedAnnotations
   (plan §5).  The rightmost layer wins a conflict.  Layer ids must be
   distinct: with two layers named the same, `:provenance` and `:from-layer`
   become ambiguous, so a repeated `:id` throws.

   opts:
     :on-dangling     — :quarantine (default) | :keep | :drop (§5.6)
     :type-derivation — :additive (default) | :from-callsites (§5.7)"
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
  "Unwraps MergedAnnotations to the bare {RuleName RuleAnnotation} map for
   consumers that do not care about provenance (plan §6)."
  [merged]
  (:annotations merged))

(defn provenance
  "Per-rule, per-annotation-key origins (plan §4.6)."
  ([merged] (:provenance merged))
  ([merged rule-name]
   (get (:provenance merged) (normalize-rule-name rule-name))))

;; ---------------------------------------------------------------------------
;; Reporting (§7.1)
;; ---------------------------------------------------------------------------

(def ^:private dimension-keys
  "Report/validation dimension names for the two detection keys."
  {:insert :clara-rules/dynamic-insert-types-detected
   :retract :clara-rules/dynamic-retract-types-detected})

(defn- report-callsite
  "The work-list view of one callsite (plan §7.1)."
  [cs]
  (into {:callsite-id (:callsite-id cs)
         :status (:status cs)}
        (comp (map (fn [k] (when (contains? cs k) [k (get cs k)])))
              (filter some?))
        [:source-str :ns-name-sym :constructor-sym]))

(defn unresolved-report
  "The work list (plan §7.1): what is still unresolved, computed from the
   merged value alone.  `:rules` is the reading material for whoever does the
   resolving — rules with at least one non-:full, non-quarantined callsite —
   and their output is a new sparse layer, not an edit to this report.
   `:dangling` names the layer responsible for each quarantined entry (§5.6)."
  [merged]
  (let [annotations (:annotations merged)
        rules (into (sorted-map)
                    (keep (fn [[rule-name rule-ann]]
                            (let [dims (into {}
                                             (keep (fn [[dim k]]
                                                     (when-let [dm (get rule-ann k)]
                                                       (let [work (into []
                                                                        (comp (remove :dangling?)
                                                                              (remove #(= :full (:status %)))
                                                                              (map report-callsite))
                                                                        (:callsites dm))]
                                                         (when (seq work)
                                                           [dim {:resolution (:resolution dm)
                                                                 :callsites work}])))))
                                             dimension-keys)]
                              (when (seq dims)
                                [rule-name dims]))))
                    annotations)
        dangling (into []
                       (comp (mapcat (fn [[rule-name rule-ann]]
                                       (keep (fn [[dim k]]
                                               (when-let [dm (get rule-ann k)]
                                                 [rule-name dim dm]))
                                             dimension-keys)))
                             (mapcat (fn [[rule-name dim dm]]
                                       (keep (fn [cs]
                                               (when (:dangling? cs)
                                                 (cond-> {:rule rule-name
                                                          :dimension dim
                                                          :callsite-id (:callsite-id cs)
                                                          :from-layer (:from-layer cs)}
                                                   (:resolution-evidence cs)
                                                   (assoc :resolution-evidence
                                                          (:resolution-evidence cs)))))
                                             (:callsites dm)))))
                       annotations)
        by-resolution (frequencies (mapcat (fn [[_ dims]]
                                             (mapcat (fn [[_ {:keys [callsites]}]]
                                                       (map :status callsites))
                                                     dims))
                                           rules))]
    {:summary {:rules (count rules)
               :callsites (reduce + 0 (vals by-resolution))
               :by-resolution by-resolution
               :dangling (count dangling)}
     :rules rules
     :dangling dangling}))

;; ---------------------------------------------------------------------------
;; Validation (§7.2)
;; ---------------------------------------------------------------------------

(defn- finding
  [severity type m]
  (merge {:severity severity :type type} m))

(defn- lint-unknown-rule
  [known-rule-names layer-id rule-name]
  (when (and known-rule-names (not (contains? known-rule-names rule-name)))
    [(finding :error :unknown-rule
              {:layer layer-id
               :rule rule-name
               :message (format "rule %s matches no rule in the rulebase — a typo would merge in as a phantom entry"
                                rule-name)})]))

(defn- lint-callsite-structure
  "Per-callsite structural checks: conclusions with no types, and hand-written
   derived fields."
  [layer-id rule-name dim cs]
  (concat
   (when (and (#{:full :partial} (:status cs))
              (empty? (:resolved-types cs)))
     [(finding :error :resolved-without-types
               {:layer layer-id
                :rule rule-name
                :dimension dim
                :callsite-id (:callsite-id cs)
                :message (format "status %s with no :resolved-types"
                                 (:status cs))})])
   (keep (fn [derived-k]
           (when (contains? cs derived-k)
             (finding :warn :authored-derived-field
                      {:layer layer-id
                       :rule rule-name
                       :dimension dim
                       :callsite-id (:callsite-id cs)
                       :message (format "%s is computed by the merge, never authored — the value is ignored"
                                        derived-k)})))
         [:from-layer :dangling?])))

(defn- discovered-entry?
  "A callsite entry written by the analyzer carries :filename; a curating
   layer's witness (§5.6) restates only :source-str."
  [cs]
  (contains? cs :filename))

(defn- lint-detection-structure
  [layer-id rule-name dim dm]
  (concat
   ;; a discovering (analyzer-written) layer legitimately carries the
   ;; :resolution it derived; anyone else's authored value is ignored (F4)
   (when (and (map? dm)
              (contains? dm :resolution)
              (not (every? discovered-entry? (:callsites dm))))
     [(finding :warn :authored-derived-field
               {:layer layer-id
                :rule rule-name
                :dimension dim
                :message "detection-map :resolution is derived, never authored — the value is ignored"})])
   (mapcat #(lint-callsite-structure layer-id rule-name dim %)
           (:callsites dm))))

(defn- lint-layer-structure
  "Per-layer checks that need no merge state: unknown rules, conclusions with
   no types, and hand-written derived fields."
  [known-rule-names layer]
  (let [layer-id (:id layer)]
    (into []
          (mapcat (fn [[rule-name rule-ann]]
                    (concat (lint-unknown-rule known-rule-names layer-id rule-name)
                            (mapcat (fn [[dim k]]
                                      (lint-detection-structure layer-id rule-name dim
                                                                (get rule-ann k)))
                                    dimension-keys)))
                  (:annotations layer)))))

(defn- id-group-prefix
  "Strips the trailing ordinal segment from a callsite id, leaving the
   duplicate-group prefix (§4.4)."
  [callsite-id]
  (some-> callsite-id (str/split #":") (->> (drop-last) (str/join ":"))))

(defn- annotation-callsites
  "All callsite entries of an annotations-shaped map (a layer's or a merged
   result's `:annotations`), as [rule-name dimension callsite] tuples."
  [annotations]
  (into []
        (mapcat (fn [[rule-name rule-ann]]
                  (mapcat (fn [[dim k]]
                            (map (fn [cs] [rule-name dim cs])
                                 (:callsites (get rule-ann k))))
                          dimension-keys)))
        annotations))

(defn- lint-ambiguous-references
  "Warns when a layer references a callsite id whose duplicate group has more
   than one member — the ordinal is positional, so the reference may
   mis-attribute (§4.4).  Group sizes are computed from the discovered
   callsites in the merged output."
  [layers merged]
  (let [group-sizes (frequencies
                     (into []
                           (comp (filter (fn [[_ _ cs]] (and (:source-str cs)
                                                             (not (:dangling? cs)))))
                                 (map (fn [[_ _ cs]] (id-group-prefix (:callsite-id cs)))))
                           (annotation-callsites (:annotations merged))))
        ambiguous (into #{}
                        (keep (fn [[prefix n]] (when (> n 1) prefix)))
                        group-sizes)]
    (into []
          (mapcat (fn [layer]
                    (keep (fn [[rule-name dim cs]]
                            (when (and (not (discovered-entry? cs))
                                       (contains? ambiguous (id-group-prefix (:callsite-id cs))))
                              (finding :warn :ambiguous-callsite-reference
                                       {:layer (:id layer)
                                        :rule rule-name
                                        :dimension dim
                                        :callsite-id (:callsite-id cs)
                                        :message "referenced id belongs to a duplicate group with more than one member — its ordinal is positional"})))
                          (annotation-callsites (:annotations layer)))))
          layers)))

(defn- lint-dangling
  "Warns on quarantined callsites in the merged output (§5.6)."
  [merged]
  (into []
        (keep (fn [[rule-name dim cs]]
                (when (:dangling? cs)
                  (finding :warn :dangling-callsite
                           {:layer (:from-layer cs)
                            :rule rule-name
                            :dimension dim
                            :callsite-id (:callsite-id cs)
                            :message "no discovered form matches this entry — the assertion annotates nothing"}))))
        (annotation-callsites (:annotations merged))))

(defn- lint-no-op-entries
  "Warns when a layer's assertion is identical to the merged state beneath it
   (§7.2): restating a value changes nothing.  Provenance is ignored — only
   the annotation payload is compared."
  [layers]
  (second
   (reduce (fn [[acc findings] layer]
             (let [layer-id (:id layer)
                   before (:annotations acc)
                   after (:annotations (fold-layer acc layer))
                   layer-findings
                   (into []
                         (mapcat (fn [[rule-name rule-ann]]
                                   (keep (fn [[k v]]
                                           (when (and (not= :clara-rules/merge-props k)
                                                      (some? v)
                                                      (contains? (get before rule-name) k)
                                                      (= v (get-in before [rule-name k])))
                                             (finding :warn :no-op-entry
                                                      {:layer layer-id
                                                       :rule rule-name
                                                       :message (format "%s restates the merged value beneath it — the entry changes nothing"
                                                                        k)})))
                                         rule-ann))
                                 (:annotations layer)))]
               [{:annotations after :provenance {}}
                (into findings layer-findings)]))
           [{:annotations (sorted-map) :provenance {}} []]
           layers)))

(defn- lint-derivation-drops
  "Under :from-callsites, warns on each authored type dropped because the
   dimension's callsites do not support it (§5.7, §7.2).  Compares the
   no-derivation merge (merged authored types) against the derived merge."
  [layers]
  (let [authored (merge-layers layers {:type-derivation :none})
        derived (:annotations (merge-layers layers {:type-derivation :from-callsites}))]
    (into []
          (comp (mapcat (fn [[rule-name rule-ann]]
                          (keep (fn [[dim types-k dm-k]]
                                  (when (get rule-ann dm-k)
                                    (let [dropped (set/difference (set (get rule-ann types-k))
                                                                  (set (get-in derived [rule-name types-k])))]
                                      (when (seq dropped)
                                        [rule-name dim types-k dropped]))))
                                dimension-derivation-keys)))
                (mapcat (fn [[rule-name dim types-k dropped]]
                          (let [origin (get-in authored [:provenance rule-name types-k])
                                layer-id (cond
                                           (keyword? origin) origin
                                           (and (vector? origin) (= 1 (count origin))) (first origin))]
                            (map (fn [t]
                                   (finding :warn :derivation-dropped-authored-type
                                            (cond-> {:rule rule-name
                                                     :dimension dim
                                                     :message (format "authored type %s dropped — the dimension's callsites do not support it under :from-callsites"
                                                                      t)}
                                              layer-id (assoc :layer layer-id))))
                                 dropped)))))
          (:annotations authored))))

;; ---------------------------------------------------------------------------
;; Layer rebasing (phase 7)
;;
;; Renaming or moving a namespace dangles every curated callsite in it —
;; correct, but tedious for a bulk rename.  `rebase-layer` remaps a layer
;; across a known old→new namespace mapping and recomputes callsite ids, so
;; the rebased layer overlays freshly generated discovery again.
;; ---------------------------------------------------------------------------

(defn- ns-path
  "Classpath-style path prefix for a namespace name: acme.my-ns → acme/my_ns."
  [ns-str]
  (-> ns-str
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- rebase-qualified
  "Remaps one qualified name (symbol, keyword, or string) across ns-mapping
   {old-ns-str new-ns-str}.  A bare namespace name (no `/`) remaps whole.
   Unmapped names pass through unchanged."
  [ns-mapping x]
  (if (or (symbol? x) (keyword? x) (string? x))
    (let [s (str (symbol x))
          i (str/index-of s "/")]
      (if-not i
        (if-let [new-ns (get ns-mapping s)]
          (cond
            (symbol? x) (symbol new-ns)
            (keyword? x) (keyword new-ns)
            :else new-ns)
          x)
        (let [ns-part (subs s 0 i)]
          (if-let [new-ns (get ns-mapping ns-part)]
            (cond
              (symbol? x) (symbol new-ns (subs s (inc i)))
              (keyword? x) (keyword new-ns (subs s (inc i)))
              :else (str new-ns "/" (subs s (inc i))))
            x))))
    x))

(defn- rebase-filename
  "Remaps a source filename whose path derives from a mapped namespace
   (acme/pricing.clj or acme/pricing/sub.clj for acme.pricing)."
  [ns-mapping filename]
  (if-not (string? filename)
    filename
    (reduce (fn [f [old-ns new-ns]]
              (let [old-path (ns-path old-ns)]
                (if (and (str/starts-with? f old-path)
                         (< (count old-path) (count f))
                         (let [c (nth f (count old-path))]
                           (or (= c \/) (= c \.))))
                  (str/replace-first f old-path (ns-path new-ns))
                  f)))
            filename
            ns-mapping)))

(defn- update-some
  [m k f]
  (if (contains? m k) (update m k f) m))

(defn- rebase-callsite
  [ns-mapping cs]
  (let [rebased (-> cs
                    (update-some :ns-name-sym #(rebase-qualified ns-mapping %))
                    (update-some :constructor-sym #(rebase-qualified ns-mapping %))
                    (update-some :filename #(rebase-filename ns-mapping %))
                    (update-some :resolved-types (fn [ts] (mapv #(rebase-qualified ns-mapping %) ts)))
                    (update-some :fact-type #(rebase-qualified ns-mapping %))
                    (update-some :via (fn [via]
                                        (-> via
                                            (update-some :boundary-var-name-sym
                                                         #(rebase-qualified ns-mapping %))
                                            (update-some :callstack
                                                         (fn [stack]
                                                           (mapv #(update-some % :var-name-sym
                                                                               (fn [v] (rebase-qualified ns-mapping v)))
                                                                 stack)))))))]
    ;; entries with a basis get fresh ids from the remapped content;
    ;; id-only references (no witness) keep their id and will dangle —
    ;; re-confirmation is the honest answer for those
    (if (has-id-basis? rebased)
      (dissoc rebased :callsite-id)
      rebased)))

(defn rebase-layer
  "Remaps a layer across a known old→new namespace mapping (plan §11), so
   renaming or moving a namespace does not dangle every curated callsite in
   it.  `ns-mapping` is {old-ns new-ns} (symbols or strings).  Rule-name
   keys, callsite discovery fields (`:ns-name-sym`, `:constructor-sym`,
   `:filename`, `:via`), and symbol/keyword fact-type tokens are remapped;
   callsite ids and duplicate-group ordinals are then recomputed from the
   remapped basis.  Unmapped namespaces pass through unchanged."
  [layer-map ns-mapping]
  (let [ns-mapping (into {}
                         (map (fn [[old-ns new-ns]] [(str (symbol old-ns))
                                                     (str (symbol new-ns))]))
                         ns-mapping)]
    (-> layer-map
        (update :annotations
                (fn [anns]
                  (into {}
                        (map (fn [[rule-name rule-ann]]
                               [(rebase-qualified ns-mapping rule-name)
                                (reduce (fn [ra k]
                                          (let [dm (get ra k)]
                                            (if (contains? dm :callsites)
                                              (assoc ra k
                                                     (assoc dm :callsites
                                                            (derive-callsite-ids
                                                             (mapv #(rebase-callsite ns-mapping %)
                                                                   (:callsites dm)))))
                                              ra)))
                                        rule-ann
                                        detection-keys)]))
                        anns)))
        layer)))

(defn validate-layers
  "Pure lint over a layer stack (plan §7.2).  Returns a vector of findings:
   {:severity :error|:warn :type … :layer … :rule … :dimension …
    :callsite-id … :message …}.

   `:known-rule-names` is optional so validation works offline from artifacts
   alone; supply it from a live session or an analysis file to enable
   `:unknown-rule`.  `:type-derivation :from-callsites` additionally enables
   `:derivation-dropped-authored-type` (§5.7)."
  ([layers] (validate-layers layers {}))
  ([layers {:keys [known-rule-names type-derivation]
            :or {type-derivation :additive}}]
   (let [layers (mapv #(s/validate Layer (normalize-layer %)) layers)
         merged (merge-layers layers {:type-derivation type-derivation})]
     (into []
           (comp cat (distinct))
           [(lint-no-op-entries layers)
            (mapcat #(lint-layer-structure known-rule-names %) layers)
            (lint-ambiguous-references layers merged)
            (lint-dangling merged)
            (when (= :from-callsites type-derivation)
              (lint-derivation-drops layers))]))))
