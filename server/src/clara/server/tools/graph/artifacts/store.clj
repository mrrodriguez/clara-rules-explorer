(ns clara.server.tools.graph.artifacts.store
  "Where annotation artifacts live on disk, and how they are read back as layers.

  Layout is `<root>/<repo>/` for a repo-scoped run, or any explicit `:dir` — a
  run over a session restored from a serialized artifact passes its own, since it
  has no repo. Three annotation *layers* in, three derived artifacts out. The
  split exists so that knowledge each pass contributes is attributable
  afterwards: what source analysis found, what a *running session* proved, and
  what a human/agent settled by reading source that static analysis provably
  cannot recover.

    INPUT LAYERS (`schema/Layer`)
      auto-gen-annotations.edn  deterministic; rewritten on every persist
      memory-annotations.edn    fact types observed in a fired session's working
                                memory that static analysis missed; rewritten by
                                `clara.server.tools.graph.artifacts.flow/->memory-derived-annotations`
                                + persist. Holds only the *delta* over the
                                generated layer, so `:provenance` shows both
                                contributing
      agent-annotations.edn     curated overlay; seeded by
                                `clara.server.tools.graph.artifacts.overlay`,
                                never machine-overwritten after that

    DERIVED OUTPUTS (rewritten on every persist, by
    `clara.server.tools.graph.artifacts.flow/merge-persisted!`)
      merged-annotations.edn    the `schema/MergedAnnotations` value, stored by
                                REFERENCE to the layers above it — see
                                `clara.server.tools.graph.artifacts.compact`.
                                Nearly every rule's merged annotation is
                                identical to one layer's, so the file names the
                                layer instead of restating the value, and
                                `read-merged-annotations` expands it back. Named
                                apart from the layers because it is a different
                                structure, not another layer: it cannot be fed
                                back into `ann.merge/merge-layers`.
      merged-rulebase-analysis/ a DIRECTORY of six files, not one file:
                                ->rulebase-analysis over that merge, minus the Rete
                                node graph and its restatements, with every
                                cross-reference collapsed to a bare name —
                                information is only ever removed, never
                                recomputed. See
                                `clara.server.tools.graph.artifacts.slim` for
                                what is dropped and who answers it instead
                                (always the running explorer or a sibling file),
                                and `clara.server.tools.graph.artifacts.parts`
                                for why it is split the way it is.
                                The split is by ACCESS PATTERN, so a scan opens
                                production-index.edn (1.9MB) rather than the
                                whole 11.9MB value
      rulebase-analysis-digest.edn       counts, namespaces, flagged rules and the
                                unresolved list — ~16KB, the only one of these
                                small enough to read whole

  The layers are written whole and never trimmed: a server is handed those files
  themselves, so anything taken out of one would vanish from the API. The merge
  is the one annotation artifact the server never reads, which is what lets it be
  stored by reference to them.

  A third layer folds in that is *not* a file: the rule-`:props` layer, read off
  the compiled rulebase. It is the base, and it needs a session — which is why
  most things here take one. `->rulebase-analysis` does not merge props itself, so
  leaving that layer out silently drops every annotation authored in a `defrule`
  props map.

  Only the overlay is precious, and it is the one file no machine step writes
  after seeding. Everything else is reproducible from a session."
  (:require
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.artifacts.compact :as compact]
   [clara.server.tools.graph.artifacts.layout :as layout]
   [clara.server.tools.graph.artifacts.parts :as parts]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [schema.core :as s])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; layer identity
;;
;; Defined in `layout`, which has no dependencies so the babashka report script
;; can load it rather than restate it, and exposed here because this is the
;; namespace that owns layer identity. Same objects, one definition.
;; ===========================================================================

(def generated-layer-id
  "`:id` of the deterministically generated layer. See
  `layout/generated-layer-id`."
  layout/generated-layer-id)

(def memory-layer-id
  "`:id` of the memory-derived layer. See `layout/memory-layer-id`."
  layout/memory-layer-id)

(def agent-layer-id
  "`:id` of the agent-curated overlay layer. See `layout/agent-layer-id`."
  layout/agent-layer-id)

(s/defn ->generated-layer :- schema/Layer
  "Wrap a generated annotations map as a validated explorer layer. Validation
  is the point of doing this here rather than at write time:
  `ann.merge/->layer` also assigns every callsite a `:callsite-id`, and those ids
  are the handles a curator writes against, so they must exist before anything is
  persisted or seeded.

  `:generated-by` is required and never defaulted — see `schema/ProvenanceOpts`."
  [{:keys [generated-by]} :- schema/ProvenanceOpts
   annotations :- schema/Annotations]
  (ann.merge/->layer {:id generated-layer-id
                      :source {:generated-by generated-by
                               :rule-count (count annotations)}
                      :annotations annotations}))

(s/defn ->memory-layer :- schema/Layer
  "Wrap a working-memory enrichment delta as a validated explorer layer.

  `annotations` holds **only what working memory added** — see
  `clara.server.tools.graph.artifacts.flow/->memory-delta`. Carrying the full
  enriched map instead would make this layer re-claim every key the generated
  layer already owns, which is exactly the provenance the split exists to
  preserve."
  [{:keys [generated-by]} :- schema/ProvenanceOpts
   annotations :- schema/Annotations]
  (ann.merge/->layer {:id memory-layer-id
                      :source {:generated-by generated-by
                               :derived-from "session working memory"
                               :rule-count (count annotations)}
                      :annotations annotations}))

;; ===========================================================================
;; artifact paths
;; ===========================================================================

(def artifact-files
  "Per-repo artifact filenames, by role —
  `clara.server.tools.graph.artifacts.manifest` and any façade over this read
  them from here. The roles are `schema/ArtifactKey`, and `:rulebase-analysis` is
  a DIRECTORY: `clara.server.tools.graph.artifacts.parts` splits the analysis by
  access pattern and `parts/part-files` names what goes in it.

  Defined in `layout/artifact-files` so the babashka report script shares it."
  layout/artifact-files)

(def layer-artifacts
  "The artifacts that are layer files (`schema/LayerArtifactKey`), and the `:id`
  each one carries. The keys of this map are exactly the fold order, lowest
  precedence first: generated, then working-memory enrichment over it, then the
  curated overlay over both. See `layout/layer-artifacts`."
  layout/layer-artifacts)

(def branches-subdir
  "The directory, under a run's base dir, that holds its per-branch variants. A
  fixed name so a branch can never collide with an artifact file, and so the
  mainline dir stays readable as \"the state of the world\" with its experiments
  gathered in one place beneath it."
  "branches")

(s/defn ^:private get-branch-path :- s/Str
  "Validate a caller-supplied branch label as a relative path under
  `branches-subdir`. Slashes are kept — `feature/foo` nests two deep, the way
  the name already reads — but nothing that could climb out of the base dir."
  [branch :- s/Str]
  (let [branch (str/trim branch)]
    (when (some #{"" "." ".."} (str/split branch #"/"))
      (throw (ex-info (str "Branch must be a relative path with no empty, '.' or "
                           "'..' segments")
                      {:branch branch})))
    branch))

(s/defn get-out-dir :- s/Str
  "Persistence dir for a run: explicit `:dir`, else `<:root>/<:repo>/`.

  No environment variable is read here. A host that keeps its artifacts under
  some `$…_HOME` resolves that itself and passes the result as `:root`, so the
  message its own users see names their own variable rather than a key in this
  library's schema.

  `:branch` nests the whole artifact set one level down, under
  `<base>/branches/<branch>/`. It is a caller-supplied label, not a git branch —
  nothing reads it back off a checkout — so it names whatever variant of a
  repo you want kept apart: an in-flight refactor, a spike, a comparison run.
  The point is that the base dir keeps meaning *the mainline state of the
  world*, and an experiment never has to clobber it to be persisted.

  It applies to an explicit `:dir` too, so the rule is one rule: a branch is
  always a subdir of the run's base."
  [{:keys [root dir repo branch]} :- schema/ArtifactOpts]
  (let [base (or dir
                 (do
                   (when (str/blank? root)
                     (throw (ex-info "No output dir: pass :root (an artifact root) or :dir" {})))
                   (when (str/blank? repo)
                     (throw (ex-info "Pass :repo (subdir) or an explicit :dir" {})))
                   (str (io/file root repo))))]
    (if (str/blank? branch)
      base
      (str (io/file base branches-subdir (get-branch-path branch))))))

(s/defn get-artifact-path :- s/Str
  "Absolute path, as a string, of one artifact under `get-out-dir`."
  [k :- schema/ArtifactKey
   opts :- schema/ArtifactOpts]
  (let [filename (or (get artifact-files k)
                     (throw (ex-info "Unknown artifact" {:artifact k
                                                         :known (keys artifact-files)})))]
    (str (io/file (get-out-dir opts) filename))))

(s/defn get-artifact-file :- File
  "`get-artifact-path` as a `java.io.File`. Bind this once rather than re-deriving
  the path each time you need a handle."
  [k :- schema/ArtifactKey
   opts :- schema/ArtifactOpts]
  (io/file (get-artifact-path k opts)))

;; ===========================================================================
;; layer IO
;;
;; `*print-meta*` is off in both writers. Callsite `:ns-name-sym` symbols carry
;; clj-kondo location metadata describing a position inside a *synthesized*
;; source snippet (`^{:row 1 :col 5 …}`) — meaningless once persisted, and it
;; bloats every callsite and churns diffs. `edn-io` binds it false, and so does
;; `ann.merge/write-layer!` around whatever `:edn-printer` it is given.
;; ===========================================================================

(s/defn read-layer :- (s/maybe schema/Layer)
  "Read one persisted layer artifact. `nil` when the file is absent — callers
  that need it to exist say so themselves.

  The file's own `:id` stands. Every writer here goes through
  `->generated-layer` / `->memory-layer` /
  `clara.server.tools.graph.artifacts.overlay/->layer`, each of which
  validates a `Layer` in, and `layer-artifacts` gives each role the same id
  every time — so a file whose id disagrees with its filename was hand-edited,
  most likely an overlay started by copying auto-gen-annotations.edn. Correcting
  it here would relabel analyzer output as curated and put that in the merged
  artifact's `:provenance`, which is the attribution the layer split exists to
  keep honest. Left alone, `ann.merge/merge-layers` throws on the duplicate id
  and names the real problem."
  [k :- schema/LayerArtifactKey
   opts :- schema/ArtifactOpts]
  (let [^File file (get-artifact-file k opts)]
    (when (.exists file)
      (ann.merge/->layer file))))

(s/defn write-layer! :- s/Str
  "Write `layer` to the `k` artifact, creating the dir. Returns the path.

  Through `edn-io/print-edn-to!`, so a layer file is written by the same printer
  as every derived artifact rather than by `ann.merge/write-layer!`'s
  `clojure.pprint` default — the layouts match, and the generated layer for a
  large session is the second-biggest thing a persist writes."
  [k :- schema/LayerArtifactKey
   opts :- schema/ArtifactOpts
   layer :- schema/Layer]
  (let [file (get-artifact-file k opts)]
    (io/make-parents file)
    (ann.merge/write-layer! file layer {:edn-printer edn-io/print-edn-to!})
    (str file)))

(s/defn ->layer-annotations-stack :- schema/LayerAnnotationsStack
  "The payload of every layer file that exists, in fold order. What
  `clara.server.tools.graph.artifacts.compact` resolves
  `merged-annotations.edn`'s references against.

  Only file layers, so the props layer is absent by construction — see
  `schema/LayerAnnotationsStack` for why that is the right set rather than a
  shortfall."
  [opts :- schema/ArtifactOpts]
  (into []
        (keep (fn [k]
                (when-let [layer (read-layer k opts)]
                  [(:id layer) (:annotations layer)])))
        (keys layer-artifacts)))

(s/defn write-merged-annotations! :- s/Str
  "Write `merged` to `merged-annotations.edn`, compacted against `layers`.
  Returns the path.

  `layers` is the fold's own file-backed input, passed in rather than re-read:
  the caller has just folded it, and re-reading the generated layer is the most
  expensive read in the pipeline."
  [opts :- schema/ArtifactOpts
   merged :- schema/MergedAnnotations
   layers :- schema/LayerAnnotationsStack]
  (let [file (get-artifact-file :merged opts)]
    (io/make-parents file)
    (edn-io/write-edn-file! file (compact/->compact merged layers))
    (str file)))

(s/defn read-compact-merged-annotations :- (s/maybe schema/CompactMergedAnnotations)
  "`merged-annotations.edn` exactly as stored, references unresolved. `nil` when
  absent.

  For reading the encoding — its `:layers`, its `:verbatim` attribution — without
  paying for the layer files. Anything that wants annotation *values* wants
  `read-merged-annotations`."
  [opts :- schema/ArtifactOpts]
  (edn-io/read-edn-file (get-artifact-file :merged opts)))

(s/defn read-merged-annotations :- (s/maybe schema/MergedAnnotations)
  "Read `merged-annotations.edn` back — a whole merge, not a layer. `nil` when
  absent.

  Expanded on the way in, so callers get a `MergedAnnotations` and never see the
  stored shape. That costs reading the layer files beside it, which is why this
  takes `opts` and not a path: the merge is stored by reference to them and is
  not meaningful alone. Reach for `read-compact-merged-annotations` only to
  inspect the encoding itself."
  [opts :- schema/ArtifactOpts]
  (when-let [compacted (read-compact-merged-annotations opts)]
    (compact/<-compact compacted (->layer-annotations-stack opts))))

;; ===========================================================================
;; the analysis directory
;; ===========================================================================

(s/defn get-analysis-part-file :- File
  "One file of the `merged-rulebase-analysis/` directory."
  [k :- schema/AnalysisPartKey
   opts :- schema/ArtifactOpts]
  (let [filename (or (get parts/part-files k)
                     (throw (ex-info "Unknown analysis part" {:part k
                                                              :known (keys parts/part-files)})))]
    (io/file (get-artifact-path :rulebase-analysis opts) filename)))

(s/defn write-analysis-parts! :- {schema/AnalysisPartKey s/Str}
  "Split `analysis` by access pattern and write each part. Returns
  `{part-key path}`.

  Every part is written even when empty, so the directory is the same shape on
  every run and a reader can open the one it needs without checking. A section
  the analysis did not have at all is nil out of `parts/->parts` and is written
  as `{}` — the nil/`{}` distinction is what makes `<-parts` exact in memory, and
  it is not worth a file that reads `nil`."
  [opts :- schema/ArtifactOpts
   analysis :- schema/RulebaseAnalysis]
  (let [written (parts/->parts analysis)]
    (into (sorted-map)
          (map (fn [[k _]]
                 (let [file (get-analysis-part-file k opts)]
                   (io/make-parents file)
                   (edn-io/write-edn-file! file (or (get written k) {}))
                   [k (str file)])))
          parts/part-files)))

(s/defn read-analysis-part :- (s/maybe {s/Any s/Any})
  "One part of the analysis directory, or nil when the directory has no such
  file. The way to read the analysis: open the part your question lives in — an
  `:index` scan is 1.9MB where the whole value is 11.9MB."
  [k :- schema/AnalysisPartKey
   opts :- schema/ArtifactOpts]
  (edn-io/read-edn-file (get-analysis-part-file k opts)))

(s/defn read-analysis-parts :- (s/maybe schema/AnalysisParts)
  "Every part, as `parts/<-parts` takes them. `nil` when the directory is not
  there. Reads the whole analysis, so prefer `read-analysis-part`."
  [opts :- schema/ArtifactOpts]
  (when (.isDirectory (io/file (get-artifact-path :rulebase-analysis opts)))
    (into {} (map (fn [[k _]] [k (or (read-analysis-part k opts) {})])) parts/part-files)))

;; ===========================================================================
;; the fold
;; ===========================================================================

(s/defn get-layer-stack :- [schema/Layer]
  "The ordered fold input for a persisted artifact set, lowest precedence first:

    [rule-:props (needs :session)] → generated → memory → agent-curated

  Absent layers are omitted rather than faked, so the stack is an honest record
  of what was available: the merged artifact's own `:layers` key names exactly
  the ones that contributed. Omitting `:session` therefore omits props, which is
  fine for file-only work (migration, offline lint) and wrong for anything that
  feeds `->rulebase-analysis`.

  `omit` is a set of `layer-artifacts` keys to leave out — see
  `clara.server.tools.graph.artifacts.overlay/get-discovered-callsites`, the one
  caller that needs a view of the stack without a layer that is physically
  present: only the analyzer *discovers* callsites, so what the overlay may
  annotate has to be defined without the overlay."
  ([opts :- schema/StackOpts] (get-layer-stack opts #{}))
  ([{:keys [session] :as opts} :- schema/StackOpts
    omit :- #{schema/LayerArtifactKey}]
   (into []
         (filter some?)
         (cons (when session (ann.merge/->props-layer session))
               (map (fn [k] (when-not (omit k) (read-layer k opts)))
                    (keys layer-artifacts))))))

(s/defn fold-layers :- schema/MergedAnnotations
  "Fold an explicit stack. `:type-derivation` is the library default
  `:additive`: merged authored types ∪ resolved-callsite types.

  Separate from `->merged-annotations` so a caller that needs the stack *and* the
  merge — `clara.server.tools.graph.artifacts.flow/merge-persisted!`, which
  compacts one against the other — can read the layer files once."
  [stack :- [schema/Layer]]
  (ann.merge/merge-layers stack))

(s/defn ->file-layer-annotations :- schema/LayerAnnotationsStack
  "The file-backed layers of `stack`, as
  `clara.server.tools.graph.artifacts.compact` takes them. The props layer is
  dropped: it has no file for a reference to point at."
  [stack :- [schema/Layer]]
  (let [file-layer-ids (set (vals layer-artifacts))]
    (into []
          (comp (filter (comp file-layer-ids :id))
                (map (juxt :id :annotations)))
          stack)))

(s/defn ->merged-annotations :- schema/MergedAnnotations
  "Fold `get-layer-stack` into a merge."
  [opts :- schema/StackOpts]
  (fold-layers (get-layer-stack opts)))
