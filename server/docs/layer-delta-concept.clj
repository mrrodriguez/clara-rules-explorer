(require '[clara.server.tools.graph.analyze :as analyze]
         '[clara.server.tools.graph.annotations :as ann]
         '[clara.server.tools.graph.annotations.merge :as ann.merge])

;; ---------------------------------------------------------------------------
;; the working-memory layer
;;
;; `enrich-annotations-from-session` returns the whole enriched map. Persisting
;; that as the generated layer (what this namespace used to do) makes runtime
;; discoveries indistinguishable from source-derived ones. So diff it back down
;; to what enrichment actually added, and carry only that.
;; ---------------------------------------------------------------------------

(def ^:private enrichable-dimensions
  "The `[types-key detection-key]` pairs enrichment can contribute to.

  Both type keys merge by `:union` and both detection keys go through
  `merge/fold-detection-key`, so the delta logic is identical per dimension.
  `analyze/enrich-annotations-from-session` only populates the insert dimension
  today — but that is its business, not this function's, and a retract dimension
  appearing later should flow through rather than be silently dropped."
  [[:clara-rules/insert-types :clara-rules/dynamic-insert-types-detected]
   [:clara-rules/retract-types :clara-rules/dynamic-retract-types-detected]])

(defn- new-types
  "Types under `types-key` in `enriched` that `base` did not have. Compared
  through `ann.merge/type-str`, the same normalization the merge uses, so a Class
  and its string form are not counted as different types."
  [types-key base enriched]
  (let [known (into #{} (map ann.merge/type-str) (get base types-key))]
    (into [] (remove #(contains? known (ann.merge/type-str %)))
          (get enriched types-key))))

(defn- dimension-delta
  "What enrichment added to one rule in one dimension, or nil if nothing.

  The types key merges by **union** (`merge/fold-key`), so emitting only the new
  types yields the right merged value *and* a `:provenance` of
  `[… :working-memory]` — both layers recorded as contributing.

  The detection key is the audit trail of which types came from memory, and it
  is emitted whenever enrichment produced one. `merge/fold-detection-key` merges
  a callsite-less detection map into whatever is already under the key rather
  than replacing it, and `merge/merge-detection-maps` keeps non-callsite keys
  from **both** sides, so this layer's `:fact-instance-derived-types` and the
  generated layer's `:callsites` both survive the fold.

  That was not always true. Before clara-rules-explorer `2fbe58a` the fold
  replaced wholesale and the deep path kept non-callsite keys only from the
  accumulator, so either the callsites or the derived types were lost; this
  function withheld the audit key whenever the generated layer had callsites,
  which is exactly the rules worth auditing. Do not reintroduce that guard
  without checking those two functions."
  [[types-key detection-key] base enriched]
  (let [added (new-types types-key base enriched)
        derived (get-in enriched [detection-key :fact-instance-derived-types])]
    (when (seq added)
      (cond-> {types-key (vec added)}
        (seq derived)
        (assoc detection-key {:fact-instance-derived-types (vec derived)})))))

(defn- memory-rule-delta
  "What working-memory enrichment added to one rule across every dimension, or
  nil if nothing.

  Adds one tombstone: `:clara-rules/no-output-types` is an assertion that the
  rule produces nothing, and observing it produce something disproves it. An
  explicit nil erases the key and its provenance (`merge/fold-key`). Leaving it
  set would contradict the very types this layer just added, and
  `core/production-annotation` reads it to suppress sink classification — so a
  rule proven to insert would still be reported as producing no output."
  [base enriched]
  (when-let [delta (not-empty
                    (into {}
                          (mapcat #(dimension-delta % base enriched))
                          enrichable-dimensions))]
    (cond-> delta
      (:clara-rules/no-output-types base) (assoc :clara-rules/no-output-types nil))))

(defn memory-delta
  "The bare annotations map for the working-memory layer: per rule, only what
  enrichment added over `annotations`. Empty when a fired session told us
  nothing new — which is the honest result for an unfired session, rather than
  a layer restating the generated one."
  [{:keys [session annotations]}]
  (let [base (ann/normalize-annotations annotations)
        enriched (analyze/enrich-annotations-from-session session base)]
    (into (sorted-map)
          (keep (fn [[rule-name enriched-ann]]
                  (when-let [delta (memory-rule-delta (get base rule-name) enriched-ann)]
                    [rule-name delta])))
          enriched)))

(defn ->memory-layer
  "Wrap a working-memory enrichment delta as a validated explorer `Layer`.

  `annotations` holds **only what working memory added** — see
  `memory-delta`. Carrying the full enriched
  map instead would make this layer re-claim every key the generated layer
  already owns, which is exactly the provenance the split exists to preserve."
  [annotations]
  (ann.merge/layer {:id :clara.tools.graph.analyze/memory
                    :source {:generated-by "clara-rules-explorer"
                             :derived-from "session working memory"
                             :rule-count (count annotations)}
                    :annotations annotations}))

(defn memory-layer
  "`memory-delta` wrapped as a validated `Layer`, or nil when the delta is empty."
  [opts]
  (let [delta (memory-delta opts)]
    (when (seq delta)
      (store/->memory-layer delta))))

