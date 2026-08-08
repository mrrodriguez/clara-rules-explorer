(ns clara.server.tools.graph.annotations
  "Rule-name normalization, type normalization, per-production annotation
   lookup, and annotation-map diffing for the layered annotation library
   (see docs/rule-annotations.md).  Handles arbitrary fact types (classes,
   keywords, symbols) as supported by Clara's pluggable fact-type-fn.

   The rest of the library lives in sibling namespaces:

     clara.server.tools.graph.annotations.callsite — callsite format, identity
     clara.server.tools.graph.annotations.merge    — layers, merging, derivation
     clara.server.tools.graph.annotations.report   — work list and validation
     clara.server.tools.graph.annotations.rebase   — namespace-rename rebasing

   Rule-name normalization:
     Clara rules use strings for `:name` (schema: `s/Str` or `s/Keyword`),
     but annotations may arrive with symbol keys from EDN or kondo analysis.
     All public functions that touch annotation maps normalize top-level
     rule-name keys to strings: `normalize-rule-name` converts a single key,
     `normalize-annotations` transforms an entire map, and `get-annotation`
     normalizes the lookup key before access.")

(defn type-str
  "Normalizes a type value to its canonical string form for deduplication
   across layers that may represent the same logical type as a Class, Symbol,
   or String."
  [t]
  (cond
    (nil? t) nil
    (class? t) (.getName ^Class t)
    (keyword? t) (str (symbol t))
    (symbol? t) (str t)
    (string? t) t
    :else (str t)))

(defn fq-name->namespace
  "Extract the namespace portion from a fully-qualified rule name string like
   \"some.ns/rule-name\".  Returns a symbol, or nil if the name has no
   namespace segment."
  [fq-str]
  (some-> fq-str symbol namespace symbol))

(defn canonical-type-str
  "Canonical string form of a type value for COMPARISON (merge dedupe,
   enrichment coverage checks, delta computation).

   Unlike `serialize/resolve-type` — the boundary serializer for display —
   strings pass through unquoted and keywords lose their colon, so a Class,
   its `.getName` string, and its source symbol all canonicalize
   identically.  When `prod-ns` is given, symbols are resolved in that
   namespace first, so a rule's `AuditTrail` symbol and the
   `my.ns.AuditTrail` Class compare equal."
  ([t] (type-str t))
  ([prod-ns t]
   (if (symbol? t)
     (if-let [resolved (and prod-ns (ns-resolve prod-ns t))]
       (cond
         (class? resolved) (.getName ^Class resolved)
         (var? resolved)   (str (symbol (-> resolved meta :ns ns-name str)
                                        (-> resolved meta :name str)))
         :else             (str resolved))
       (str t))
     (type-str t))))

(defn normalize-rule-name
  "Normalizes a rule-name key to its canonical string form.
   Symbols and keywords are converted to strings; strings pass through."
  [k]
  (if (or (symbol? k) (keyword? k))
    (str (symbol k))
    k))

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

;; ---------------------------------------------------------------------------
;; Per-production lookup
;; ---------------------------------------------------------------------------

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
  "Resolves type tokens against production-ns, dropping any that resolve to nil.
   Nil entries in annotation files (or symbols that fail to resolve) are silently
   removed — nil is not a meaningful fact type in a Clara session."
  [production-ns unresolved-types]
  (into []
        (keep #(resolve-type-locally production-ns %))
        unresolved-types))

(defn- unqualify-keyword
  [kw]
  (if (and (keyword? kw) (namespace kw))
    (keyword (name kw))
    kw))

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
   fact-type-fn allows.  The lookup normalizes the map's rule-name keys when
   they are not already strings."
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

;; ---------------------------------------------------------------------------
;; Annotation delta — what one annotation map adds over another
;; ---------------------------------------------------------------------------

(def ^:private enrichable-dimensions
  "The `[types-key detection-key]` pairs that enrichment can contribute to.

  Both type keys merge by `:union` and both detection keys go through
  `merge/fold-detection-key`, so the delta logic is identical per dimension."
  [[:clara-rules/insert-types :clara-rules/dynamic-insert-types-detected]
   [:clara-rules/retract-types :clara-rules/dynamic-retract-types-detected]])

(defn- new-types
  "Types under `types-key` in `enriched` that `base` did not have.
   Compared through `canonical-type-str` with the rule's namespace, so a
   Class, its `.getName` string, and its source symbol all canonicalize
   identically — same form used during enrichment."
  [types-key rule-ns base enriched]
  (let [canon (partial canonical-type-str rule-ns)
        known (into #{} (map canon) (get base types-key))]
    (into [] (remove #(contains? known (canon %)))
          (get enriched types-key))))

(defn- dimension-delta
  "What enrichment added to one rule in one dimension, or nil if nothing.

  The types key merges by **union** (`merge/fold-key`), so emitting only the
  new types yields the right merged value *and* a `:provenance` of
  `[… :working-memory]` — both layers recorded as contributing.

  The detection key is the audit trail of which types came from memory, and
  it is emitted whenever enrichment produced one.  `merge/fold-detection-key`
  merges a callsite-less detection map into whatever is already under the key
  rather than replacing it, and `merge/merge-detection-maps` keeps
  non-callsite keys from **both** sides, so this layer's
  `:fact-instance-derived-types` and the generated layer's `:callsites` both
  survive the fold."
  [[types-key detection-key] base enriched rule-ns]
  (let [added (new-types types-key rule-ns base enriched)
        derived (get-in enriched [detection-key :fact-instance-derived-types])]
    (when (seq added)
      (cond-> {types-key (vec added)}
        (seq derived)
        (assoc detection-key {:fact-instance-derived-types (vec derived)})))))

(defn- rule-delta
  "What `enriched` added to one rule across every dimension, or nil if
  nothing.

  `rule-ns` is the rule's namespace (extracted from its FQ name via
  `fq-name->namespace`), used so symbol types canonicalize the same way
  they did during enrichment.

  Adds one tombstone: `:clara-rules/no-output-types` is an assertion that the
  rule produces nothing, and observing it produce something disproves it.  An
  explicit nil erases the key and its provenance (`merge/fold-key`).  Leaving
  it set would contradict the very types this layer just added, and
  `core/production-annotation` reads it to suppress sink classification — so
  a rule proven to insert would still be reported as producing no output."
  [base enriched rule-ns]
  (when-let [delta (not-empty
                    (into {}
                          (mapcat #(dimension-delta % base enriched rule-ns))
                          enrichable-dimensions))]
    (cond-> delta
      (:clara-rules/no-output-types base) (assoc :clara-rules/no-output-types nil))))

(defn annotations-delta
  "The bare annotations map of only what `extra-annotations` adds over
  `base-annotations`.  Returns nil when `extra-annotations` contributes
  nothing new — which is the honest result, rather than a layer restating
  the base.

  Both inputs are normalized via `normalize-annotations`."
  [base-annotations extra-annotations]
  (let [base (normalize-annotations base-annotations)
        enriched (normalize-annotations extra-annotations)]
    (not-empty
     (into (sorted-map)
           (keep (fn [[rule-name enriched-ann]]
                   (when-let [delta (rule-delta (get base rule-name)
                                                enriched-ann
                                                (fq-name->namespace rule-name))]
                     [rule-name delta])))
           enriched))))
