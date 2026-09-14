(ns clara.server.tools.graph.artifacts.overlay
  "The agent-curated overlay layer: report → record → lint.

  What the deterministic pass cannot resolve, a source-reading pass can. This
  namespace stages that work and derives every conclusion from it; the curator
  only ever supplies per-callsite resolutions, keyed by `:callsite-id`.

  There is no finalize step: `merge-layers` derives the rule-level types and
  each dimension's `:resolution` from the merged callsites itself, so a curated
  callsite becomes a graph edge with nothing in between."
  (:require
   [clara.server.tools.graph.annotations :as cann]
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.annotations.report :as ann.report]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.utils :as utils]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

(s/defn get-unresolved-report :- schema/UnresolvedReport
  "The curation work list for the persisted stack: every callsite still short of
  `:status :full`, plus the quarantined (`:dangling?`) ones and who wrote them.

  Computed from the *merged* value, not from the generated layer alone — so a
  callsite an existing overlay already closed is correctly absent, and a stale
  overlay assertion shows up under `:dangling` instead of masquerading as work.
  See `ann.report/unresolved-report`."
  [opts :- schema/StackOpts]
  (ann.report/unresolved-report (store/->merged-annotations opts)))

(def ^:private dimension-detection-keys
  "`schema/DetectionDimension` → the annotation key holding its detection map.
  Mirrors the report ns's own (private) mapping, which is what
  `get-unresolved-report` keys its `:rules` entries by."
  schema/detection-keys-by-dimension)

(s/defn ^:private ->callsites-by-id :- schema/DiscoveredCallsites
  "Index every callsite of a merge by id, tagged with the rule and dimension it
  was found under."
  [merged :- schema/MergedAnnotations]
  (into {}
        (for [[rule-name annotation] (:annotations merged)
              [dimension k] dimension-detection-keys
              cs (:callsites (get annotation k))]
          [(:callsite-id cs) (assoc cs :rule rule-name :dimension dimension)])))

(s/defn ^:private get-discovered-callsites :- schema/DiscoveredCallsites
  "The discovered callsites of the persisted stack *without* the overlay — the
  overlay annotates callsites, it does not discover them."
  [opts :- schema/StackOpts]
  (->callsites-by-id (ann.merge/merge-layers (store/get-layer-stack opts #{:agent}))))

(def ^:private layer-notes
  (str "Curated resolutions only — one callsite entry per gap actually settled by reading "
       "source. Not a copy of the work list: an unresolved callsite needs no entry here, it "
       "is already reported by get-unresolved-report. Rule-level types and :resolution are "
       "derived by the merge, never written here."))

(defn- upsert-callsite
  "Replace the entry carrying the same `:callsite-id` in `callsites`, else append.
  Re-recording an id you refined is a correction, not a second entry for the
  merge to arbitrate."
  [callsites entry]
  (let [v (vec callsites)
        i (first (keep-indexed #(when (= (:callsite-id %2) (:callsite-id entry)) %1) v))]
    (if i
      (assoc v i entry)
      (conj v entry))))

(s/defn ^:private get-resolution-rule-key :- schema/RuleName
  "The rule key one resolution files under: the discovered callsite's, else one
  the caller restated. Throws when there is neither — an id the analyzer never
  discovered has nothing to look one up from."
  [resolution :- schema/CurationResolution
   discovered-entry :- (s/maybe schema/DiscoveredCallsite)]
  (or (:rule discovered-entry)
      (:rule resolution)
      (throw (ex-info (str "Unknown :callsite-id and no :rule to file it under. "
                           "Supply :rule, or check the id against get-unresolved-report.")
                      {:callsite-id (:callsite-id resolution)}))))

(s/defn ^:private ->callsite-entry :- schema/CallsiteEntry
  "The overlay callsite entry for one resolution — the conclusion and nothing
  else. `:source-str` is copied off the discovered entry as a redundant witness,
  the one thing still legible if the source later moves and the entry dangles."
  [{:keys [callsite-id resolved-types status note]} :- schema/CurationResolution
   discovered-entry :- (s/maybe schema/DiscoveredCallsite)]
  (cond-> {:callsite-id callsite-id
           :status (or status :full)
           :resolved-types (vec resolved-types)}
    (:source-str discovered-entry) (assoc :source-str (:source-str discovered-entry))
    note (assoc :resolution-evidence {:note note})))

(s/defn apply-resolutions :- schema/Annotations
  "Fold `resolutions` into the `existing` overlay annotations, filing each under
  the rule and detection dimension of its discovered callsite (or the ones the
  caller restated). Pure — `discovered` is the already-read index, so the whole
  shape of the overlay is decided here and only written by `record-resolutions!`."
  [{:keys [existing discovered resolutions]}
   :- {:existing (s/maybe schema/Annotations)
       :discovered schema/DiscoveredCallsites
       :resolutions [schema/CurationResolution]}]
  (reduce (fn [acc resolution]
            (let [found (get discovered (:callsite-id resolution))
                  rule (get-resolution-rule-key resolution found)
                  k (get dimension-detection-keys
                         (or (:dimension found) (:dimension resolution) :insert))]
              (update-in acc [rule k :callsites]
                         upsert-callsite
                         (->callsite-entry resolution found))))
          (into (sorted-map) existing)
          resolutions))

(s/defn ^:private ->layer :- schema/Layer
  "Wrap curated overlay `annotations` as a validated explorer layer."
  [annotations :- schema/Annotations]
  (ann.merge/->layer {:id store/agent-layer-id
                      :source {:curated-against (:auto store/artifact-files)}
                      :notes layer-notes
                      :annotations annotations}))

(s/defn ^:private get-resolutions-summary :- schema/ResolutionsSummary
  "The `record-resolutions!` return value for a written overlay."
  [{:keys [annotations unknown-callsite-ids file]}
   :- {:annotations schema/Annotations
       :unknown-callsite-ids [schema/CallsiteId]
       :file s/Str}]
  (cond-> {:file file
           :rule-count (count annotations)
           :callsite-count (transduce (comp (mapcat vals)
                                            (map (comp count :callsites)))
                                      + 0 (vals annotations))}
    (seq unknown-callsite-ids) (assoc :unknown-callsite-ids unknown-callsite-ids)))

(s/defn ^:private ensure-generated-layer! :- (s/eq nil)
  "Throw unless the generated layer exists. A resolution can only talk about a
  callsite the analyzer discovered, and without that file nothing has."
  [opts :- schema/ArtifactOpts]
  (when-not (store/read-layer :auto opts)
    (throw (ex-info (format "No generated layer at %s - run %s first"
                            (store/get-artifact-path :auto opts)
                            'clara.server.tools.graph.artifacts.flow/persist!)
                    {:dir (store/get-out-dir opts)}))))

;; ---------------------------------------------------------------------------
;; What belongs in the overlay, and what the merge does with it
;;
;; ONLY SETTLED CALLSITES. An unresolved one needs no entry: `get-unresolved-report`
;; already has it, computed from the merge, so a file of `:status :none` stubs
;; would be a second copy of the work list to keep in step with the first.
;;
;; `:callsite-id` IS THE JOIN KEY. The merge matches on it and merges field by
;; field, so the analyzer's `:filename` / `:constructor-sym` / `:via` survive
;; underneath a sparse entry. `:source-str` is copied off the discovered entry
;; as a redundant witness — the one thing still legible if the source later
;; moves and the entry dangles. `:rule` and `:dimension` are looked up from the
;; same place, so a caller holding an id need not restate where it lives.
;;
;; AN UNKNOWN ID IS STILL WRITTEN. Refusing would only hide it. It comes back in
;; `:unknown-callsite-ids` and the merge quarantines it (`:dangling?`) rather
;; than letting it produce an edge — but it needs an explicit `:rule` (and
;; `:dimension`, if retract), since there is nothing to look one up from.
;; ---------------------------------------------------------------------------

(s/defn record-resolutions! :- schema/ResolutionsSummary
  "Write the agent overlay from the resolutions a curation pass **actually
  settled** — one `schema/CurationResolution` per gap pinned down by reading
  source:

    (record-resolutions! [{:callsite-id \"some.ns:->fact:a3f19c2b:0\"
                           :resolved-types [:loan/applicant]
                           :note \"reports.clj:1041 — literal ctor\"}]
                         {:dir …})

  Merges into an existing overlay, so a pass can be recorded incrementally and
  across sessions; re-recording an id replaces its entry. `:replace? true`
  starts a fresh overlay instead, discarding whatever was curated before."
  [resolutions :- [schema/CurationResolution]
   {:keys [replace?] :as opts} :- (assoc schema/StackOpts
                                         (s/optional-key :replace?) s/Bool)]
  (ensure-generated-layer! opts)
  ;; read → decide (pure) → write → report (pure).
  (let [discovered (get-discovered-callsites opts)
        existing (when-not replace? (:annotations (store/read-layer :agent opts)))
        unknown (into [] (comp (map :callsite-id) (remove discovered)) resolutions)
        annotations (apply-resolutions {:existing existing
                                        :discovered discovered
                                        :resolutions resolutions})
        file (store/write-layer! :agent opts (->layer annotations))]
    (get-resolutions-summary {:annotations annotations
                              :unknown-callsite-ids unknown
                              :file file})))

(s/defn ^:private get-known-rule-names :- (s/maybe #{schema/RuleName})
  "The rule names `lint-layers` checks a layer's keys against. A live `:session`
  gives every production off the rulebase — including rules the generated layer
  had nothing to say about; without one, the generated layer's own keys, which
  is what keeps the `:unknown-rule` check working offline. Nil when neither is
  available, so the check is skipped rather than failing everything."
  [{:keys [session] :as opts} :- schema/StackOpts]
  (if session
    (into #{}
          (map (comp cann/normalize-rule-name :name))
          (:productions (utils/get-rulebase session)))
    (some-> (store/read-layer :auto opts) :annotations keys set)))

(s/defn lint-layers :- [schema/LintFinding]
  "Check the persisted layer stack. Empty when the stack is sound.

  Delegates to `ann.report/validate-layers`.
  `:known-rule-names` comes from the generated layer, which enables the
  `:unknown-rule` check offline; pass `:session` and it comes from the rulebase
  instead — every production, including rules the generated layer had nothing to
  say about — and the props layer joins the stack, catching a layer that merely
  restates what the rule source already declares.

  What it catches:
    :unknown-rule        (error) rule key matches no known rule — a typo would
                         otherwise merge in as a phantom entry
    :resolved-without-types (error) `:status :full`/`:partial` with no
                         `:resolved-types`, so the callsite contributes no edge
    :dangling-callsite   (warn) the merged entry has no discovered form — the
                         id matched nothing, usually because the source moved.
                         Quarantined by default: it produces no edge and is not
                         silently dropped either
    :ambiguous-callsite-reference (warn) the id's duplicate group has more than
                         one member within the same rule and dimension, so its
                         ordinal is positional
    :authored-derived-field (warn) a layer hand-wrote `:resolution`,
                         `:from-layer`, or `:dangling?` — all derived, ignored
    :no-op-entry         (warn) the entry restates the merged value beneath it"
  [opts :- schema/StackOpts]
  (let [known (get-known-rule-names opts)]
    (ann.report/validate-layers (store/get-layer-stack opts)
                                (cond-> {}
                                  known (assoc :known-rule-names known)))))
