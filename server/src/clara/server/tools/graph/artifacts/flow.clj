(ns clara.server.tools.graph.artifacts.flow
  "Annotation generation and persistence, end to end.

    generate → (->memory-layer) → persist! → merge-persisted!

  The pieces it composes each live in their own namespace, and callers use them
  directly rather than through this one:

    `clara.server.tools.graph.artifacts.store`
        artifact paths, layer identity, layer read/write, and the ordered fold
        stack
    `clara.server.tools.graph.artifacts.slim`
        which parts of the analysis are worth writing to disk, and which are the
        explorer server's job to answer
    `clara.server.tools.graph.artifacts.compact`
        how the merge is stored: by reference to the layers that already hold
        nearly all of it
    `clara.server.tools.graph.artifacts.digest`
        the KB-sized orientation file written beside it
    `clara.server.tools.graph.artifacts.overlay`
        the agent-curated layer: report → record → lint
    `clara.server.tools.graph.artifacts.manifest`
        the provenance manifest beside them

  Nothing here knows what a fact looks like. A host with its own fact idiom
  teaches the analysis about it through `:fact-constructors`,
  `:callsite-resolver-fn`, `:fact-type-spec-fn`, `:ns-var-defs-fn` and
  `:post-process-fns` — see `schema/GenerateOptions`."
  (:require
   [clara.server.tools.graph.analyze :as analyze]
   [clara.server.tools.graph.annotations :as ann]
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.artifacts.digest :as digest]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.artifacts.slim :as slim]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.core :as core]
   [clara.server.tools.graph.edn-io :as edn-io]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; the analysis, and the merge it is of
;;
;; `core/->rulebase-analysis` costs seconds on a real rulebase, and this flow
;; would otherwise run it three times over the same input: once in `generate`,
;; again once a memory layer is added, and a third time to write the analysis
;; directory. Two of those are pure repetition — the analysis is a function of
;; the rulebase and the merged annotations alone, so an equal merge has an equal
;; analysis.
;;
;; So an analysis never travels on its own. It travels as the pair
;; `->deferred-rulebase-analysis` produces (a
;; `schema/DeferredRulebaseAnalysis`): the work, not yet done, plus the
;; annotations it would be of. Deferred so a step whose analysis nobody wants
;; pays nothing, and stamped with its input so the step that does want one can
;; tell whether the one in hand still describes the layers in front of it. Read
;; one with `get-rulebase-analysis`, never by reaching for the key.
;;
;; The stamp is the annotations map alone, not the whole merge value it was read
;; out of, because that is what the analysis is actually a function of:
;; `->rulebase-analysis` uses `:annotations` and nothing else off that value, and
;; echoes it back as the `:merged-annotations` of its result. Keying on the merge
;; instead would rebuild whenever `:layers` or `:provenance` differed over
;; annotations that fold to the same map — the "different ids, same annotations"
;; case — and would hold a second copy of a value the forced analysis already
;; retains.
;; ===========================================================================

(def ^:private rulebase-analysis-opts
  "The `core/->rulebase-analysis` opts every call in this namespace threads.

  Its `:form-printer` renders each rule's `:lhs` / `:lhs-form` / `:rhs-form`, tens of thousands of
  small forms per analysis, and the default is `clojure.pprint` — on a large session that alone is
  seconds of the run. `edn-io/pretty-edn-str` is the printer every artifact here already goes
  through, so the strings land in the same layout they would have had."
  {:form-printer edn-io/pretty-edn-str})

(s/defn ->deferred-rulebase-analysis :- schema/DeferredRulebaseAnalysis
  "The `core/->rulebase-analysis` of `merged` over `session`, as the pair
  every step of this flow carries it in.

  Nothing here runs until someone calls `get-rulebase-analysis`."
  [session :- schema/SessionOrRulebase
   merged :- schema/MergedAnnotations]
  {:rulebase-analysis-of (:annotations merged)
   :rulebase-analysis (delay (core/->rulebase-analysis session merged rulebase-analysis-opts))})

(s/defn get-rulebase-analysis :- (s/maybe schema/RulebaseAnalysis)
  "Force and return the analysis `x` carries, or nil when it carries none — `x` is a
  `schema/RulebaseAnalysisInHand`, so carrying none is a normal answer
  rather than a mistake: a `generate` result, a `merge-persisted!` opts map, or
  nothing at all.

  The one way to read one. Forcing the delay by hand works, and means every
  callsite has to know it is a delay."
  [x :- (s/maybe schema/RulebaseAnalysisInHand)]
  (some-> (:rulebase-analysis x) force))

(s/defn rulebase-analysis-of? :- s/Bool
  "Is the analysis `x` carries an analysis of `merged`? False when it carries
  none, so this is also the \"is there one to reuse\" test, and it answers
  without forcing anything — which is why the stamp is carried beside the delay
  rather than read off `:merged-annotations` once the analysis exists.

  Comparison is by value over the annotations alone, which is the whole and
  exact precondition for reuse and costs milliseconds against the seconds a
  rebuild costs. Layer *ids* would not do: equal ids can carry different
  annotations, and different ids can fold to the same ones.

  The layers this is checked against reach disk whole, and `merged-annotations.edn` expands to
  exactly the value it was written from, so the fold read back off disk equals the stamp taken
  from the fold held in memory, and a plain `=` is the right test."
  [x :- (s/maybe schema/RulebaseAnalysisInHand)
   merged :- schema/MergedAnnotations]
  (boolean (and (:rulebase-analysis x) (= (:annotations merged) (:rulebase-analysis-of x)))))

;; ===========================================================================
;; generation
;; ===========================================================================

(s/defn ^:private ->rule-source-analysis-opts :- {s/Keyword s/Any}
  "The `analyze/->rule-source-analysis` argument for a `generate` opts map.

  Absent options are omitted rather than passed as nil: an ns filter read as nil
  means \"filter on nothing\", and an absent `:config-dir` is what selects
  `analyze`'s own bundled clj-kondo config."
  [{:keys [session config-dir include-ns-prefixes exclude-ns-prefixes ns-var-defs-fn]}
   :- schema/GenerateOptions]
  (cond-> {:session-or-rulebase session}
    config-dir (assoc :config-dir config-dir)
    include-ns-prefixes (assoc :include-ns-prefixes include-ns-prefixes)
    exclude-ns-prefixes (assoc :exclude-ns-prefixes exclude-ns-prefixes)
    ns-var-defs-fn (assoc :ns-var-defs-fn ns-var-defs-fn)))

(s/defn ^:private ->annotations-opts :- analyze/RuleSourceAnnotationsOptions
  "The `analyze/->annotations-from-rule-source-analysis` argument for a
  `generate` opts map plus the clj-kondo rule-source analysis it just produced."
  [{:keys [session rules-filter callsite-resolver-fn fact-type-spec-fn fact-constructors]}
   :- schema/GenerateOptions
   rule-source-analysis :- {s/Any s/Any}]
  (cond-> {:rule-source-analysis rule-source-analysis
           :session-or-rulebase session}
    (seq fact-constructors) (assoc :fact-constructors fact-constructors)
    callsite-resolver-fn (assoc :callsite-resolver-fn callsite-resolver-fn)
    rules-filter (assoc :rules-filter rules-filter)
    fact-type-spec-fn (assoc :fact-type-spec-fn fact-type-spec-fn)))

(s/defn generate :- schema/AnnotationData
  "Generate the annotation layer for a live session, running
  `analyze/->rule-source-analysis` →
  `analyze/->annotations-from-rule-source-analysis` wired with whatever hooks
  `opts` supplies, and offer the rulebase analysis over it.

  `opts` is a `schema/GenerateOptions`. Nothing is defaulted except by absence
  meaning absence:

    :fact-constructors  none. A host whose facts are built by a runtime helper
                        rather than a record constructor declares it here; see
                        `analyze/FactConstructorSpec`

    :post-process-fns   none. Run in order over the generated annotations before
                        they are wrapped as a layer — the seam for knowledge
                        only visible at runtime, which static analysis cannot
                        reach

    :config-dir         absent, so `analyze`'s own bundled clj-kondo config is
                        used. Supplying one **replaces** that config wholesale;
                        it is not additive

    every other hook absent, and an ns filter passed as nil is read as \"filter
                        on nothing\"

  Returns the generated `:layer` plus a `->deferred-rulebase-analysis` of the merge of the
  rule-`:props` layer and this one — read it with `get-rulebase-analysis`.
  `core/->rulebase-analysis` does not fold `props` itself, and skipping
  it silently loses every annotation authored in a `defrule` props map."
  [{:keys [session post-process-fns] :as opts} :- schema/GenerateOptions]
  (when-not session
    (throw (ex-info "generate requires :session (a live session or rulebase)" {})))
  (let [rule-source-analysis (analyze/->rule-source-analysis (->rule-source-analysis-opts opts))
        annotations (reduce (fn [anns f] (f anns))
                            (analyze/->annotations-from-rule-source-analysis
                             (->annotations-opts opts rule-source-analysis))
                            post-process-fns)
        layer (store/->generated-layer opts annotations)
        merged (ann.merge/merge-layers [(ann.merge/->props-layer session) layer])]
    (assoc (->deferred-rulebase-analysis session merged) :layer layer)))

(s/defn ->memory-derived-annotations :- schema/Annotations
  "Cross-check a (fired) session's actually inserted fact types against `annotations` and merge in
  any newly-detected ones. Passthrough to
  `analyze/merge-memory-derived-insert-types`, which returns a bare
  annotations map — the *whole* merged map, not a delta. Requires a fired session.

  Prefer `->memory-layer`, which turns that into an attributable layer."
  [{:keys [session annotations]} :- schema/MemoryEnrichmentOptions]
  (analyze/merge-memory-derived-insert-types annotations session))

;; ---------------------------------------------------------------------------
;; the memory-derived layer
;;
;; `merge-memory-derived-insert-types` returns the whole merged map. Persisting
;; that as the generated layer would make runtime discoveries indistinguishable
;; from source-derived ones. So diff it back down to what working memory actually
;; added, and carry only that.
;;
;; The diff itself is `ann/annotations-delta` — the same per-dimension rules the
;; fold uses, including the `:clara-rules/no-output-types` tombstone and the type
;; comparison. What stays here is only the layer *identity*
;; (`store/->memory-layer`), since these layer ids are tied to the on-disk
;; artifacts.
;; ---------------------------------------------------------------------------

(s/defn ->memory-delta :- (s/maybe schema/Annotations)
  "The bare annotations map for the memory-derived layer: per rule, only what
  working memory added over `annotations`. Nil when a fired session told us
  nothing new — which is the honest result for an unfired session, rather than a
  layer restating the generated one."
  [{:keys [session annotations]} :- schema/MemoryEnrichmentOptions]
  (let [base (ann/normalize-annotations annotations)
        merged (analyze/merge-memory-derived-insert-types base session)]
    (ann/annotations-delta base merged)))

(s/defn ->memory-layer :- (s/maybe schema/Layer)
  "`->memory-delta` wrapped as a validated layer, or nil when the delta is empty. Nil rather than
  an empty layer so `store/get-layer-stack` and the merged artifact's `:layers` stay an honest
  record of what actually contributed.

  `analyze/->memory-layer` is the equivalent one level down and computes the
  identical delta; it is not used because it stamps the analyzer's own
  `:clara.tools.graph.analyze/memory` id, and these layer ids name the artifact
  files they round-trip through (`store/layer-artifacts`)."
  [opts :- schema/MemoryLayerOptions]
  ;; `MemoryEnrichmentOptions` is closed, so the provenance is dropped on the way
  ;; into the pure diff rather than widened out of it.
  (let [delta (->memory-delta (select-keys opts [:session :annotations]))]
    (when (seq delta)
      (store/->memory-layer opts delta))))

;; ===========================================================================
;; merge + write
;; ===========================================================================

(s/defn ^:private cannot-derive-rulebase-analysis-message :- s/Str
  "Why `->merged-rulebase-analysis` can produce nothing honest for the fold `ids`:
  either there is no analysis in hand at all, or there is one but it is of a
  different merge than the layers now describe."
  [ids :- [schema/LayerId]
   rulebase-analysis-in-hand :- (s/maybe schema/RulebaseAnalysisInHand)]
  (str "Cannot derive " (:rulebase-analysis store/artifact-files) ": pass :session. "
       (if (:rulebase-analysis rulebase-analysis-in-hand)
         (str "The analysis in hand is of a different merge than the fold "
              (pr-str ids) " produced, so it describes a different graph and cannot "
              "be reused.")
         "No analysis was supplied to reuse.")))

(s/defn ^:private ->merged-rulebase-analysis :- schema/RulebaseAnalysis
  "The rulebase analysis to persist alongside the merged annotations.

  `:rulebase-analysis-in-hand` is usually the annotation data of the run doing
  the persisting. It is used when it is `rulebase-analysis-of?` this merge, and
  that is the only case where using it says anything true.

  Failing that, a live `:session` builds one, which is the only honest option
  once a layer the pair never saw is contributing."
  [merged :- schema/MergedAnnotations
   {:keys [session rulebase-analysis-in-hand]} :- schema/MergePersistedOptions]
  (cond
    (rulebase-analysis-of? rulebase-analysis-in-hand merged)
    (get-rulebase-analysis rulebase-analysis-in-hand)

    session
    (core/->rulebase-analysis session merged rulebase-analysis-opts)

    :else
    (throw (ex-info (cannot-derive-rulebase-analysis-message (mapv :id (:layers merged))
                                                             rulebase-analysis-in-hand)
                    {:layers (mapv :id (:layers merged))}))))

(s/defn merge-persisted! :- schema/MergePersistedResult
  "Fold the persisted layer stack and write the three derived artifacts: `merged-annotations.edn`
  (the whole merge: annotations, the layers that contributed, and per-key provenance),
  `merged-rulebase-analysis/` (through `slim/slim-rulebase-analysis`, then split by access pattern
  into six files — see `clara.server.tools.graph.artifacts.parts`), and
  `rulebase-analysis-digest.edn` (built from the *pre-slim* analysis, so it counts `:nodes` too).
  All three are rewritten; the layers are only read.

  `merged-annotations.edn` is written by REFERENCE to the layers — see
  `clara.server.tools.graph.artifacts.compact`. The layers themselves are written untouched,
  because a server is handed those files directly. The merge is the one annotation artifact the
  server never reads, which is what lets it point at them instead of restating them.

  `opts` is a `schema/MergePersistedOptions`. It needs `:session` and/or
  `:rulebase-analysis-in-hand` to produce an analysis at all — see `->merged-rulebase-analysis`
  for which wins.

  `:rulebase-analysis? false` writes the merged annotations and leaves the analysis directory and
  digest file untouched — not a shortcut, but for a caller that can fold the layers yet cannot
  re-derive an analysis, where the existing files beat none."
  [{:keys [rulebase-analysis? session-hint] :or {rulebase-analysis? true} :as opts}
   :- schema/MergePersistedOptions]
  (let [;; The stack is read once and used twice: folded, then used again as
        ;; what the fold is compacted against. Re-reading it would be the most
        ;; expensive read in the pipeline.
        stack (store/get-layer-stack opts)
        merged (store/fold-layers stack)
        analysis-dir (store/get-artifact-path :rulebase-analysis opts)
        digest-file (store/get-artifact-file :rulebase-analysis-digest opts)
        merged-file (store/write-merged-annotations!
                     opts merged (store/->file-layer-annotations stack))]
    (when rulebase-analysis?
      (let [analysis (->merged-rulebase-analysis merged opts)]
        ;; The digest reads the PRE-slim analysis — `:nodes` is one of its
        ;; counts — so it is built from `analysis`, not from what goes to disk.
        (store/write-analysis-parts! opts (slim/slim-rulebase-analysis analysis))
        (edn-io/write-edn-file! digest-file
                                (digest/->rulebase-analysis-digest analysis session-hint))))
    (cond-> {:merged merged-file
             :layers (mapv :id (:layers merged))
             :rule-count (count (:annotations merged))}
      rulebase-analysis? (assoc :rulebase-analysis analysis-dir
                                :rulebase-analysis-digest (str digest-file)))))

(s/defn persist! :- s/Str
  "Write the generated layer to `auto-gen-annotations.edn`, the memory-derived layer to
  `memory-annotations.edn` when there is one, then rebuild the derived pair
  (`merged-annotations.edn` + `merged-rulebase-analysis/`) via `merge-persisted!`. The first
  argument is a `generate` return map, optionally carrying `:memory-layer`.

  Never writes `agent-annotations.edn` — a curated overlay survives any number of regenerations
  and is folded into the merge on each one.

  A run with no `:memory-layer` leaves any existing `memory-annotations.edn` alone rather than
  deleting it, matching how the overlay is treated: this function's job is to write what it was
  given, not to decide that a layer it was not handed is now void. Re-derive one to refresh it, or
  delete the file to drop it.

  `opts` is a `schema/MergePersistedOptions` minus the analysis in hand,
  which this supplies: `:session` is required once any layer beyond the generated one exists, so
  the analysis reflects the merged graph. Returns the directory path.

  `annotation-data` carries the `->deferred-rulebase-analysis` pair itself, so it rides through to
  `merge-persisted!` as `:rulebase-analysis-in-hand`: a run whose layers still describe the merge
  that analysis is of writes it rather than building another."
  [{:keys [layer memory-layer] :as annotation-data} :- schema/AnnotationData
   opts :- schema/MergePersistedOptions]
  ;; Resolved first: `get-out-dir` is where a bad `:repo`/`:dir` throws, and it
  ;; should throw before anything has been written rather than half way through.
  (let [dir (store/get-out-dir opts)]
    (store/write-layer! :auto opts layer)
    (when memory-layer
      (store/write-layer! :memory opts memory-layer))
    (merge-persisted! (assoc opts :rulebase-analysis-in-hand annotation-data))
    dir))
