(ns clara.server.tools.graph.artifacts.layout
  "What the persisted artifacts are called, and how to read back the one of them
  that is not stored literally.

  **This namespace has no dependencies, and that is its whole reason to exist.**
  `bin/annotations_report.bb` is a babashka script that `load-file`s it: it
  cannot load `schema.core`, clara, or anything else the namespaces around here
  pull in, but it has to agree with them on every filename, on the layer fold
  order, and on how `merged-annotations.edn` decodes.

  So what belongs here is narrow: a name or a pure function that **both** the JVM
  code and the babashka tooling need, in plain Clojure with no reader
  conditionals. Schema validation, IO, and anything touching a session stay in
  `clara.server.tools.graph.artifacts.store` /
  `clara.server.tools.graph.artifacts.compact` /
  `clara.server.tools.graph.artifacts.parts`, which wrap what is here.

  The JVM side re-exposes these under the namespace that owns the concept —
  `clara.server.tools.graph.artifacts.store/artifact-files`,
  `clara.server.tools.graph.artifacts.parts/part-files`,
  `clara.server.tools.graph.artifacts.schema/detection-keys-by-dimension` — so
  callers keep reading the name in its natural home. Those are aliases of these;
  this namespace is the definition.")

;; ===========================================================================
;; filenames
;; ===========================================================================

(def artifact-files
  "Per-run artifact filenames, by role. The roles are
  `clara.server.tools.graph.artifacts.schema/ArtifactKey`.

  `:rulebase-analysis` is a DIRECTORY, not a file — see `part-files`."
  {:auto "auto-gen-annotations.edn"
   :memory "memory-annotations.edn"
   :agent "agent-annotations.edn"
   :merged "merged-annotations.edn"
   :rulebase-analysis "merged-rulebase-analysis"
   :rulebase-analysis-digest "rulebase-analysis-digest.edn"
   :manifest "rules-inspect-manifest.edn"})

(def part-files
  "`clara.server.tools.graph.artifacts.schema/AnalysisPartKey` → its filename
  inside the `merged-rulebase-analysis/` directory. See
  `clara.server.tools.graph.artifacts.parts` for why the analysis is split this
  way."
  (array-map
   :index "production-index.edn"
   :conditions "production-conditions.edn"
   :details "production-details.edn"
   :fact-types "fact-types.edn"
   :dep-graph "dep-graph.edn"
   :meta "meta.edn"))

;; ===========================================================================
;; layer identity
;; ===========================================================================

(def generated-layer-id
  "`:id` of the deterministically generated layer. The explorer's own
  `--generate-analysis` stamps the same keyword on its output, and it is the
  honest statement of provenance: everything in that file came out of
  `clara.server.tools.graph.analyze`. Nothing in the library privileges the id —
  it is a marker for humans and tooling."
  :clara.tools.graph.analyze/generated)

(def memory-layer-id
  "`:id` of the memory-derived layer: fact types observed in a fired session's
  working memory that static analysis did not find. Its own layer rather than an
  edit to the generated one, so `:provenance` can say which types were *proven at
  runtime* versus read out of source.

  `:memory` rather than `:working-memory` to match the explorer's own
  `clara.tools.graph.analyze/memory`, the artifact role key, and
  `memory-annotations.edn` — one word for the channel, everywhere."
  :memory)

(def agent-layer-id
  "`:id` of the agent-curated overlay layer. Appears in the merged artifact's
  `:provenance` and on each callsite entry it claimed, as `:from-layer`."
  :agent)

(def layer-artifacts
  "The artifacts that are layer files
  (`clara.server.tools.graph.artifacts.schema/LayerArtifactKey`), and the `:id`
  each one carries. The keys of this map are exactly the fold order, lowest
  precedence first: generated, then working-memory enrichment over it, then the
  curated overlay over both."
  (array-map :auto generated-layer-id
             :memory memory-layer-id
             :agent agent-layer-id))

(def detection-keys-by-dimension
  "Detection dimension → the annotation key holding its detection map. Three
  callers walk callsites by dimension —
  `clara.server.tools.graph.artifacts.overlay`,
  `clara.server.tools.graph.artifacts.compact`, and the babashka report — and
  all three must agree on which keys those are."
  {:insert :clara-rules/dynamic-insert-types-detected
   :retract :clara-rules/dynamic-retract-types-detected})

;; ===========================================================================
;; decoding merged-annotations.edn
;;
;; Only the EXPANSION half lives here. Compaction needs the fold's own layer
;; stack and is a write-time concern, so it stays in
;; `clara.server.tools.graph.artifacts.compact`; every reader, JVM or babashka,
;; needs the inverse.
;; ===========================================================================

(defn stamp-annotation
  "`annotation` as the fold would have emitted it had `layer-id` been the only
  layer to touch the rule: `:from-layer` set on every callsite.

  The fold stamps each callsite with the layer that declared its conclusion, and
  layer files carry no `:from-layer` of their own — over a large real rulebase
  that stamp is the *only* difference between the merge and the generated layer
  for 3,315 of the 3,403 rules that match.

  Used by `clara.server.tools.graph.artifacts.compact` in both directions, so
  whatever it does, it does symmetrically."
  [layer-id annotation]
  (reduce (fn [ann detection-key]
            (cond-> ann
              (seq (get-in ann [detection-key :callsites]))
              (update-in [detection-key :callsites]
                         (partial mapv #(assoc % :from-layer layer-id)))))
          annotation
          (vals detection-keys-by-dimension)))

(defn expand-rule-provenance
  "One rule's origins as expansion rebuilds them: the template's entry for each
  key the rule's annotation has. `clara.server.tools.graph.artifacts.compact`
  uses this on the way out too, so compaction can check its own work against the
  exact thing expansion will do."
  [template annotation]
  (into (sorted-map)
        (keep (fn [k] (when-let [entry (find template k)] [k (val entry)])))
        (keys annotation)))

(defn expand-annotations
  "The merged `:annotations` map, references resolved against `layers`.

  `verbatim` is the stored `{:default :except}`: a rule not named in `:except`
  came from `:default`. A reference to a layer that is no longer on disk resolves
  to nothing and the rule drops out, rather than the read failing — the same
  posture as the rest of the store, where a missing artifact is an absent one."
  [{:keys [default except]} inlined layers]
  (let [by-id (into {} layers)]
    (into (into (sorted-map)
                (keep (fn [rule-name]
                        (let [layer-id (get except rule-name default)]
                          (when-let [annotation (get-in by-id [layer-id rule-name])]
                            [rule-name (stamp-annotation layer-id annotation)]))))
                (into (sorted-set)
                      (comp (mapcat (comp keys second))
                            (remove inlined))
                      layers))
          inlined)))

(defn expand-provenance
  "Each rule's origins: its own entry when it has one, else rebuilt from the
  per-key template. `:except` entries are whole, never overlays, so there is no
  order to get wrong."
  [{:keys [verbatim except]} annotations]
  (into {}
        (map (fn [[rule-name annotation]]
               [rule-name (or (get except rule-name)
                              (expand-rule-provenance verbatim annotation))]))
        annotations))

(defn expand-merged-annotations
  "`merged-annotations.edn` as a whole merge — a
  `clara.server.tools.graph.artifacts.schema/MergedAnnotations`, from the
  `clara.server.tools.graph.artifacts.schema/CompactMergedAnnotations` that is
  actually on disk.

  `layers` is the file-backed stack in fold order, as `[[layer-id annotations]
  …]`. The file stores a rule by REFERENCE — the id of a layer holding that exact
  annotation — whenever one does, which is ~99% of them, so reading the merge
  costs opening the layers beside it. Callsites come back WHOLE, `:via` and
  `:source-str` included, because they come off the layer.

  `clara.server.tools.graph.artifacts.compact/<-compact` is this with schema
  validation on both ends."
  [{:keys [verbatim annotations provenance] :as compacted} layers]
  (let [expanded (expand-annotations verbatim annotations layers)]
    {:layers (:layers compacted)
     :annotations expanded
     :provenance (expand-provenance provenance expanded)}))
