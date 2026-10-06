(ns clara.explorer.artifacts.schema
  "Every shape the artifact namespaces hand each other, in one place.

  Persisting an analysis is a pipeline of maps — an options map threaded through
  half a dozen namespaces, a layer, a merge, a deferred analysis, a curation
  resolution — and each one is passed far enough from where it is built that the
  only practical description of it would otherwise be prose in the docstring of
  whichever function happened to receive it. Prose restated at four callsites
  cannot be checked against the code, and drifts silently. These schemas are that
  description, in a form `s/with-fn-validation` runs against the real values every
  test produces. A docstring names the schema; the schema says what is in it.

  Only the shapes that *travel between* these namespaces live here. Shapes owned
  elsewhere in the library — `clara.explorer.annotations.merge/Layer`,
  `clara.explorer.analyze/FactConstructorSpec` — are aliased rather
  than restated, so an upstream change reaches this subtree as a validation
  failure rather than as documentation that quietly stopped being true. A shape
  that never leaves the namespace holding it is not here either.

  Annotation is `s/defn`, which validates **only** under `s/with-fn-validation`;
  production runs pay nothing but the metadata."
  (:require
   [clara.rules.engine :as eng]
   [clara.explorer.analyze :as analyze]
   [clara.explorer.analyze.callsite :as analyze.callsite]
   [clara.explorer.analyze.synth :as synth]
   [clara.explorer.annotations.callsite :as ann.callsite]
   [clara.explorer.annotations.merge :as ann.merge]
   [clara.explorer.artifacts.layout :as layout]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; shapes owned elsewhere in the library, aliased
;;
;; The same objects, not copies: `(def Layer ann.merge/Layer)` is incapable of
;; drifting from what the merge validates against. Aliased so this namespace is
;; one index of every shape the artifact flow carries, and so a caller
;; annotating against `Layer` need not know which namespace defined it.
;; ===========================================================================

(def Layer
  "One annotation source in the form it is written and read:
  `{:id … :annotations {…} :source … :notes …}`.
  `clara.explorer.artifacts.store/->generated-layer` and
  `clara.explorer.artifacts.store/->memory-layer` build validated ones;
  `clara.explorer.artifacts.store/read-layer` reads them back off disk."
  ann.merge/Layer)

(def MergedAnnotations
  "The output of folding a layer stack: `{:annotations :layers :provenance}`.
  A different structure from a `Layer`, not another layer — it cannot be fed
  back into the fold, which is why `merged-annotations.edn` is named apart from
  the layer files.

  This is the in-memory shape and what
  `clara.explorer.artifacts.store/read-merged-annotations` hands back.
  On disk it is stored compacted — see `CompactMergedAnnotations`."
  ann.merge/MergedAnnotations)

(def Origin
  "Where one merged annotation key's value came from: one layer, several (when
  the fold combined them), or `:derived`. The values of a merge's
  `:provenance`."
  ann.merge/Origin)

(def RuleAnnotation
  "What one rule's entry in an annotations map holds — insert/retract types, the
  `:no-output-types` flag, notes, and the two dynamic-detection maps. Open, and
  every value is nilable, because an explicit nil is a tombstone and an absent
  key is no opinion."
  ann.merge/RuleAnnotation)

(def CallsiteEntry
  "One `insert!`/`retract!` argument form the analyzer found, plus the
  resolution conclusion over it. `:callsite-id` is the handle a curator writes
  against."
  ann.callsite/CallsiteEntry)

(def CallsiteId
  "The stable handle for one callsite: `ns:ctor:hash:ordinal`. Assigned by the merge, reported by
  `clara.explorer.artifacts.overlay/get-unresolved-report`, and the join key a curated
  resolution is written against."
  ann.callsite/CallsiteId)

(def LayerId
  "A layer's `:id`. The artifact set has three of them — see
  `clara.explorer.artifacts.store/layer-artifacts` — plus the `:props`
  layer the fold reads off the rulebase."
  ann.callsite/LayerId)

(def Resolution
  "The three-valued resolution vocabulary: `:full`, `:partial`, `:none`. Used
  per callsite and per detection dimension."
  ann.callsite/Resolution)

(def FactConstructorSpec
  "One constructor of interest: `{:match-fn :type-resolver-fn}`. See
  `clara.explorer.analyze/FactConstructorSpec`."
  analyze/FactConstructorSpec)

(def ConstructorMatchFn
  "A spec's `:match-fn`: a set of fully-qualified constructor symbols, or a
  predicate over one. See `clara.explorer.analyze/ConstructorMatchFn`."
  analyze/ConstructorMatchFn)

(def VarDef
  "One top-level definition to include in a synthesized namespace source: `{:name <unqualified
  sym> :form <the whole def form>}`."
  synth/VarDef)

;; ===========================================================================
;; primitives
;; ===========================================================================

(s/defschema Delay
  "A `clojure.lang.Delay`. Named because the deferred analysis is the one place
  this subtree puts unforced work into a map that travels."
  (s/pred delay? 'delay?))

(s/defschema RuleName
  "How a rule is keyed in an annotations map: the string form of its fully-qualified name, as
  `clara.explorer.annotations/normalize-rule-name` normalizes it.

  `s/named` because `s/Str` is a bare `Class`, which carries no metadata for
  `s/defschema` to name it with — and an unnamed `java.lang.String` in a
  validation error says nothing about which string was wrong."
  (s/named s/Str "RuleName"))

(s/defschema FactType
  "One fact type token. Callers using a data-oriented fact idiom have keywords,
  or keyword-headed vectors when compound; clara's own are classes, which the
  analysis carries as fully-qualified class-name symbols or strings.

  Nothing narrower within those kinds: the explorer is deliberately
  type-agnostic — token shape is the caller's decision — so a tighter rule here
  would be this subtree enforcing something the merge does not. What it does
  refuse is a value of no such kind, nil above all: a resolver that could not
  read a type says so by returning no `:resolved-types` at all, never by naming
  nil as one.

  `s/conditional` rather than `s/cond-pre` because a sequence schema admits nil
  as the empty seq, which would let the one value this most needs to catch
  through."
  (s/conditional
   keyword? s/Keyword
   symbol? s/Symbol
   string? s/Str
   vector? [s/Any]
   class? (s/pred class? 'class?)
   'fact-type?))

(s/defschema DetectionDimension
  "Which of the two detection dimensions a callsite belongs to. The report keys its `:rules`
  entries by these, and
  `clara.explorer.artifacts.overlay/apply-resolutions` maps each back to
  the annotation key holding its detection map."
  (s/enum :insert :retract))

(def detection-keys-by-dimension
  "`DetectionDimension` → the annotation key holding its detection map. Exposed here because that
  is where the dimension itself is defined; the value lives in `layout`, which the babashka report
  script also loads."
  layout/detection-keys-by-dimension)

(s/defschema Annotations
  "The payload of a layer, a merge, or an enrichment pass: rule name → its
  annotation."
  {RuleName RuleAnnotation})

;; ---------------------------------------------------------------------------
;; the session
;; ---------------------------------------------------------------------------

(s/defschema Session
  "A live Clara session — what every step needing working memory, a props layer,
  or an analysis takes."
  (s/pred #(satisfies? eng/ISession %) 'clara-session?))

(s/defschema Rulebase
  "A compiled rulebase: the `:productions`-bearing map inside a session. Enough
  on its own for anything that only reads rule definitions."
  (s/pred #(some? (:productions %)) 'rulebase?))

(s/defschema SessionOrRulebase
  "A live session *or* a bare rulebase. The either-or
  `clara.explorer.annotations.merge/->props-layer` accepts, matched here so no entry
  point in this subtree disagrees with it about what may be passed.
  `clara.explorer.utils/get-rulebase` is what collapses the two."
  (s/cond-pre Rulebase Session))

;; ===========================================================================
;; the rulebase analysis
;; ===========================================================================

(s/defschema RulebaseAnalysis
  "What `clara.explorer.core/->rulebase-analysis` returns.

  Open, and every section optional: it is the value shaped for `GET
  /v1/rulebase-analysis`, and
  `clara.explorer.artifacts.slim/slim-rulebase-analysis` is explicitly
  required to handle a partial one without conjuring the sections it was missing.
  The keys named are the ones this subtree reads."
  {(s/optional-key :rules) {RuleName {s/Keyword s/Any}}
   (s/optional-key :queries) {RuleName {s/Keyword s/Any}}
   (s/optional-key :fact-types) {s/Str {s/Keyword s/Any}}
   (s/optional-key :dep-graph) {s/Any s/Any}
   (s/optional-key :unresolved) [s/Any]
   (s/optional-key :nodes) s/Any
   (s/optional-key :merged-annotations) s/Any
   s/Keyword s/Any})

(s/defschema DeferredRulebaseAnalysis
  "How an analysis travels: the work, not yet done, plus the annotations it
  would be of.

  `->rulebase-analysis` costs seconds on a real rulebase, so it is deferred and stamped with its
  input — a step that wants one can ask whether the one in hand still describes the layers in
  front of it without forcing anything. Built by
  `clara.explorer.artifacts.flow/->deferred-rulebase-analysis`, which always supplies
  both keys; read with `clara.explorer.artifacts.flow/get-rulebase-analysis`.

  Open, so a value carrying the pair alongside other things — `AnnotationData`
  does — still is one."
  {:rulebase-analysis Delay
   :rulebase-analysis-of Annotations
   s/Any s/Any})

(s/defschema RulebaseAnalysisInHand
  "What a step *might* have an analysis in: usually the `AnnotationData` of the
  run doing the persisting, which carries the pair alongside its layers.

  Both keys are optional here, where `DeferredRulebaseAnalysis` requires them, because a caller
  can hold layers it never derived an analysis for, or an analysis it cannot say which merge is
  of. Those two cases are not errors — they are precisely what
  `clara.explorer.artifacts.flow/rulebase-analysis-of?` answers false to, and what makes
  a live `:session` the only honest way to get one."
  {(s/optional-key :rulebase-analysis) Delay
   (s/optional-key :rulebase-analysis-of) Annotations
   s/Any s/Any})

(s/defschema AnnotationData
  "What one generation run produced, as
  `clara.explorer.artifacts.flow/generate` returns it and
  `clara.explorer.artifacts.flow/persist!` consumes it: the generated
  layer, the working-memory layer once one has been derived, and the
  `DeferredRulebaseAnalysis` pair over the stack they make up.

  The analysis pair is optional because a caller can persist a layer it did not
  derive an analysis for; `generate` always supplies one."
  {:layer Layer
   (s/optional-key :memory-layer) Layer
   (s/optional-key :rulebase-analysis) Delay
   (s/optional-key :rulebase-analysis-of) Annotations})

;; ===========================================================================
;; artifacts on disk
;; ===========================================================================

(s/defschema ArtifactKey
  "Which of a run's artifacts. The keys of
  `clara.explorer.artifacts.store/artifact-files`, which holds the
  filename for each — `artifacts.schema-test` pins the two in step."
  (s/enum :auto :memory :agent :merged :rulebase-analysis :rulebase-analysis-digest :manifest))

(s/defschema AnalysisPartKey
  "Which file of the `merged-rulebase-analysis/` directory. The keys of
  `clara.explorer.artifacts.parts/part-files`, which holds the filename
  for each."
  (s/enum :index :conditions :details :fact-types :dep-graph :meta))

(s/defschema AnalysisParts
  "A slim `RulebaseAnalysis` split by access pattern — see
  `clara.explorer.artifacts.parts`. Every part key is always present, so a reader never
  branches on which files a directory happens to have.

  The two `s/maybe` sections carry a distinction the files cannot: nil is a section the analysis
  never had, `{}` one it had and was empty. `clara.explorer.artifacts.slim` is required
  to leave a partial analysis partial, so
  `clara.explorer.artifacts.parts/<-parts` needs to tell those apart to
  rebuild the key set it was given. `:index` says the same thing by omitting the
  production map instead."
  {:index {(s/optional-key :rules) {RuleName {s/Keyword s/Any}}
           (s/optional-key :queries) {RuleName {s/Keyword s/Any}}}
   :conditions {RuleName {s/Keyword s/Any}}
   :details {RuleName {s/Keyword s/Any}}
   :fact-types (s/maybe {s/Str {s/Keyword s/Any}})
   :dep-graph (s/maybe {s/Any s/Any})
   :meta {s/Keyword s/Any}})

(s/defschema LayerAnnotationsStack
  "The file-backed layers' payloads, in fold order — lowest precedence first, the same order as
  `clara.explorer.artifacts.store/layer-artifacts`. A vector of pairs rather than a map
  because the order is the tie-break: `clara.explorer.artifacts.compact` searches it
  from the top down, so a rule two layers happen to agree on is attributed to the one the fold
  would have credited.

  Only *file* layers belong here. The rule-`:props` layer is read off a live
  rulebase and has no file to point at, so a rule it alone accounts for is
  inlined instead."
  [(s/pair LayerId "layer-id" Annotations "annotations")])

(s/defschema CompactMergedAnnotations
  "`merged-annotations.edn` as it is written: a `MergedAnnotations` with every value the layer
  files already hold replaced by a reference to the layer that holds it.
  `clara.explorer.artifacts.compact` converts both ways and
  `clara.explorer.artifacts.store/read-merged-annotations` expands on the way in, so no
  consumer sees this shape unless it goes looking.

  A rule is stored **by reference** only when some layer file reproduces its
  merged annotation exactly — tested by value at write time, never predicted —
  which over two large real rulebases is 3,403 of 3,426 rules and 4,523 of 4,591.
  The rest are inlined whole under `:annotations`, evidence and all: those are
  the rules the fold actually combined, and there is nothing to point at.

  `:verbatim` names the layer for the by-reference rules. `:default` is whichever
  layer the most of them point at, `:except` the rest — pure frequency
  compression of a per-rule `{rule layer}` map, which would otherwise cost 245KB
  where the whole file now costs 34KB. A rule verbatim from a minority layer is
  named in `:except`, never inlined for it.

  `:provenance` follows the same line. A by-reference rule came whole from one
  layer, so every key of it has that layer as its origin and the rule needs no
  entry at all; `:verbatim` there is the per-key template that says which shape
  the merge writes each origin in. `:except` holds the rules the template does
  not reproduce, whole — which in both measured artifacts is exactly the set
  already inlined above it. So a rule appears once in this file, or not at all."
  {:layers [{:id LayerId (s/optional-key :source) s/Any}]
   :verbatim {:default (s/maybe LayerId)
              :except {RuleName LayerId}}
   :annotations Annotations
   :provenance {:verbatim {s/Keyword Origin}
                :except {RuleName {s/Keyword Origin}}}})

(s/defschema LayerArtifactKey
  "The three artifacts that are `Layer` files. The keys of
  `clara.explorer.artifacts.store/layer-artifacts`, whose order is the
  fold order."
  (s/enum :auto :memory :agent))

(s/defschema Variant
  "An ordered vector of `[axis value]` pairs naming a unit's variant, in
  directory-nesting order. A variant unit always ends with `[:ref …]` — the
  checkout ref the artifacts were generated from — and the pairs before it are
  the host's own axes, in the host's own nesting order. A mainline unit carries
  none."
  [[(s/one s/Keyword "axis") (s/one s/Str "value")]])

(s/defschema ArtifactOpts
  "Where a run's artifacts live, and the map every artifact step is threaded.

  An explicit `:dir`, or a `:repo` subdir of `:root`; `:variant` (the host's
  axes, in nesting order, may be empty) and `:canonical?` (the host's claim that
  those axes are mainline) place the unit under `_variants/<repo>/…` or
  `<root>/<repo>/`. `clara.explorer.artifacts.store/get-out-dir` resolves
  them, and it is the one place a bad combination throws.

  `:root` is the caller's artifact root. This library reads no environment
  variable to find one — a host that keeps its artifacts under some `$…_HOME`
  resolves that itself and passes the result, so the error a user sees names
  their env var rather than this schema.

  Open, because the steps that take one carry their own keys on the same map —
  `:session`, `:replace?`, `:rulebase-analysis-in-hand`, and whatever a caller's
  manifest contributions need."
  {(s/optional-key :root) s/Str
   (s/optional-key :dir) s/Str
   (s/optional-key :repo) s/Str
   (s/optional-key :variant) Variant
   (s/optional-key :canonical?) s/Bool
   (s/optional-key :repo-path) s/Str
   s/Any s/Any})

(s/defschema StackOpts
  "`ArtifactOpts` plus the session whose rule `:props` are the base of the fold.
  Absent, the props layer is simply omitted — fine for file-only work, wrong for
  anything feeding `->rulebase-analysis`."
  (assoc ArtifactOpts (s/optional-key :session) SessionOrRulebase))

(s/defschema ProvenanceOpts
  "Who wrote a layer. Required rather than defaulted wherever it is taken: it is
  a provenance claim written into an artifact that outlives the process, and a
  library has no standing to guess it. The honest value is the name of the tool
  that ran, whichever repo its code lives in.

  Open, because it always arrives on a map that carries `ArtifactOpts` too."
  {:generated-by s/Str
   s/Any s/Any})

;; ===========================================================================
;; generation
;; ===========================================================================

(s/defschema NsVarDefsFn
  "The `:ns-var-defs-fn` hook: `(fn [ns-sym] -> nil | [VarDef …])`, called once
  per rule-owning namespace whose source is not on the classpath."
  (s/=> (s/maybe [VarDef]) s/Symbol))

(s/defschema ResolvedTypes
  "What a resolver hook returns when it could read the fact type off a callsite,
  and nil when it could not. Nil rather than an empty vector: an unresolved
  callsite is a gap the curated overlay knows to close, where a resolution
  naming nothing is a claim that the callsite produces no type."
  (s/maybe {:resolved-types [FactType]}))

(s/defschema CallsiteResolverFn
  "The `:callsite-resolver-fn` hook: `(fn [ctx] -> ResolvedTypes)` for
  boundary-arg idioms that are not a declared constructor call. The explorer
  does not consult it for callsites the constructor path already owns."
  (s/=> ResolvedTypes analyze.callsite/CallsiteResolverContext))

(s/defschema FactTypeResolverContext
  "What a `:type-resolver-fn` is handed for one callsite.
  `analyze.callsite/ConstructorTypeResolverContext` is the shape the explorer
  actually builds; this is deliberately looser, with every key optional, because
  a resolver runs inside code this library does not own and has to be total over
  whatever it is given. The explorer treats a throw as unresolved, so a resolver
  that insisted on a key would degrade to silence rather than to an error."
  {(s/optional-key :constructor-sym) s/Symbol
   (s/optional-key :arg-form) s/Any
   (s/optional-key :ns-name-sym) s/Symbol
   (s/optional-key :filename) s/Str
   (s/optional-key :direction) DetectionDimension
   (s/optional-key :rule) s/Any
   (s/optional-key :via) s/Any})

(s/defschema FactTypeResolverFn
  "A spec's `:type-resolver-fn`: `(fn [FactTypeResolverContext] ->
  ResolvedTypes)`, extracting the fact type a matched constructor call
  produces."
  (s/=> ResolvedTypes FactTypeResolverContext))

(s/defschema FactTypeSpecFn
  "The `:fact-type-spec-fn` hook: `(fn [fact-type] -> nil | {:aliases-var v})`."
  (s/=> (s/maybe {s/Keyword s/Any}) FactType))

(s/defschema AnnotationsPostProcessFn
  "One entry of `:post-process-fns`: `(fn [Annotations] -> Annotations)`, run
  over the generated map in order before it is wrapped as a layer.

  The seam for knowledge that is only visible at *runtime* and so cannot be part
  of static analysis — var metadata, a registry a host populates at load. A
  caller hands one over rather than the library taking a flag for it, the same
  posture as `:callsite-resolver-fn` and `:ns-var-defs-fn`."
  (s/=> Annotations Annotations))

(s/defschema GenerateOptions
  "Everything `clara.explorer.artifacts.flow/generate` takes.

  Closed on purpose: a mistyped key here is a hook silently not installed, which
  shows up as an analysis that resolved less than it could have rather than as
  an error.

  `:fact-constructors` and `:config-dir` have no default here. Absent means
  absent — no constructors of interest, and
  `clara.explorer.analyze`'s own bundled clj-kondo config
  respectively — never a guess at what some particular caller's fact idiom or
  macro set is."
  {:session SessionOrRulebase
   :generated-by s/Str
   (s/optional-key :rules-filter) (s/maybe [s/Symbol])
   (s/optional-key :fact-constructors) [FactConstructorSpec]
   (s/optional-key :callsite-resolver-fn) (s/maybe CallsiteResolverFn)
   (s/optional-key :fact-type-spec-fn) (s/maybe FactTypeSpecFn)
   (s/optional-key :ns-var-defs-fn) (s/maybe NsVarDefsFn)
   (s/optional-key :include-ns-prefixes) (s/maybe [s/Str])
   (s/optional-key :exclude-ns-prefixes) (s/maybe [s/Str])
   (s/optional-key :post-process-fns) (s/maybe [AnnotationsPostProcessFn])
   (s/optional-key :config-dir) (s/maybe s/Str)})

(s/defschema MemoryEnrichmentOptions
  "What the working-memory pass reads: a **fired** session, and the annotations
  to cross-check its actually-inserted fact types against."
  {:session SessionOrRulebase
   :annotations Annotations})

(s/defschema MemoryLayerOptions
  "`MemoryEnrichmentOptions` plus the provenance the layer it writes claims.
  Separate from the bare enrichment options because the pure diff underneath
  (`clara.explorer.artifacts.flow/->memory-delta`) has no layer to
  attribute and should not have to be told who is asking."
  (merge MemoryEnrichmentOptions ProvenanceOpts))

;; ===========================================================================
;; merge + write
;; ===========================================================================

(s/defschema MergePersistedOptions
  "`StackOpts` plus how the analysis beside the merge is obtained.

  `:rulebase-analysis-in-hand` is used in place of building one whenever it is
  `rulebase-analysis-of?` this same merge; with no `:session` it is the only way
  to get one at all. `:rulebase-analysis? false` writes the merged annotations
  and leaves the analysis and digest files untouched.

  `:layer-stack` is the pre-read fold stack, supplied by
  `clara.explorer.artifacts.flow/persist!` when it already holds the layers it
  just wrote; absent, the stack is read off disk."
  (assoc StackOpts
         (s/optional-key :rulebase-analysis-in-hand) RulebaseAnalysisInHand
         (s/optional-key :rulebase-analysis?) s/Bool
         (s/optional-key :layer-stack) [Layer]))

(s/defschema MergePersistedResult
  "What `clara.explorer.artifacts.flow/merge-persisted!` returns: the
  paths it wrote, the layer ids that folded, and how many rules the merge covers.
  The analysis paths are absent when `:rulebase-analysis? false` left those files
  alone."
  {:merged s/Str
   :layers [LayerId]
   :rule-count s/Int
   (s/optional-key :rulebase-analysis) s/Str
   (s/optional-key :rulebase-analysis-digest) s/Str})

(s/defschema RulebaseAnalysisDigest
  "`rulebase-analysis-digest.edn`: counts, namespaces, flag tallies, and the two
  work lists that carry names rather than counts. The only analysis artifact
  small enough to read whole."
  {:summary {s/Keyword s/Any}
   :counts {:node-count s/Int
            :dep-edge-count s/Int
            :unresolved-count s/Int}
   :namespaces {(s/maybe s/Str) {:rules s/Int :queries s/Int}}
   :flag-counts {s/Keyword s/Int}
   :unlinked-rules #{RuleName}
   :no-output-rules #{RuleName}
   :unresolved [s/Any]})

;; ===========================================================================
;; the curated overlay
;; ===========================================================================

(s/defschema CurationResolution
  "One gap a curation pass **actually settled**, as
  `clara.explorer.artifacts.overlay/record-resolutions!` takes it. Only settled
  callsites: an unresolved one needs no entry, because
  `clara.explorer.artifacts.overlay/get-unresolved-report` already has it.

  `:callsite-id` is the join key, taken from that report. `:rule` and
  `:dimension` are looked up from the discovered callsite, so supply them only
  for an id the analyzer never discovered — which is written anyway and
  quarantined by the merge."
  {:callsite-id CallsiteId
   :resolved-types [FactType]
   (s/optional-key :status) Resolution
   (s/optional-key :note) s/Str
   (s/optional-key :rule) RuleName
   (s/optional-key :dimension) DetectionDimension})

(s/defschema DiscoveredCallsite
  "One callsite the analyzer discovered, tagged with the rule and dimension it was found under.
  Those two tags are what let
  `clara.explorer.artifacts.overlay/apply-resolutions` file a resolution
  without the curator restating where the callsite lives."
  (assoc CallsiteEntry
         :rule RuleName
         :dimension DetectionDimension))

(s/defschema DiscoveredCallsites
  "`:callsite-id` → its `DiscoveredCallsite`, over a whole merge.

  This is what a resolution is allowed to talk about: only the analyzer
  discovers callsites, and the overlay annotates ones that already exist."
  {CallsiteId DiscoveredCallsite})

(s/defschema ResolutionsSummary
  "What `clara.explorer.artifacts.overlay/record-resolutions!` returns: where the
  overlay was written and what is now in it. `:unknown-callsite-ids` is present only when a
  resolution named an id the analyzer never discovered."
  {:file s/Str
   :rule-count s/Int
   :callsite-count s/Int
   (s/optional-key :unknown-callsite-ids) [CallsiteId]})

(s/defschema LintFinding
  "One finding from `clara.explorer.artifacts.overlay/lint-layers`. `:severity` and
  `:type` are always present; which of the locating keys accompany them depends on the check — see
  that function for the catalogue. Open, so a new check does not fail validation
  before it can be reported."
  {:severity (s/enum :error :warn)
   :type s/Keyword
   (s/optional-key :layer) LayerId
   (s/optional-key :rule) RuleName
   (s/optional-key :dimension) s/Keyword
   (s/optional-key :callsite-id) CallsiteId
   (s/optional-key :message) s/Str
   s/Any s/Any})

(s/defschema UnresolvedReport
  "The curation work list, computed from the merged value: per rule and
  dimension, the callsites still short of `:status :full`, plus the quarantined
  ones and the layer responsible for each."
  {:summary {s/Keyword s/Any}
   :rules {RuleName {DetectionDimension {s/Keyword s/Any}}}
   :dangling [{s/Keyword s/Any}]})

;; ===========================================================================
;; provenance manifest
;; ===========================================================================

(s/defschema GitInfo
  "State-of-the-world for one checkout. Nil for a path that is missing or is not
  a git repo — every input the manifest describes is optional in that way.

  Identity only, no location: manifests are meant to be committed beside the
  artifacts they describe, so a local filesystem path would say nothing to any
  reader but the machine that wrote it."
  (s/maybe
   {:remote (s/maybe s/Str)
    :sha s/Str
    :sha-short s/Str
    :branch (s/maybe s/Str)
    :default-branch (s/maybe s/Str)
    :working-tree (s/enum "clean" "dirty")}))

(s/defschema NamespacesFn
  "The `:namespaces-fn` hook: `(fn [ManifestOptions] -> [ns-name …])`, the last
  resort in the manifest's namespace-list preference order. A caller that can
  derive the analyzed namespaces from its own configuration supplies one; without
  it, a run with no explicit `:namespaces` and no `:session` records none."
  (s/=> [(s/cond-pre s/Str s/Symbol)] {s/Any s/Any}))

(s/defschema ManifestHistoryEntry
  "One `:history` entry of a `Manifest`: `{:date :change}`. Open, because a
  host's `:manifest-fn` may enrich the entries it writes."
  {:date s/Str
   :change s/Str
   s/Keyword s/Any})

(s/defschema Manifest
  "The provenance manifest `clara.explorer.artifacts.manifest` writes: git +
  runtime state-of-the-world plus an append-only `:history` without exact
  duplicates. Open, because callers merge their own `:blocks` in and
  `:manifest-fn` may add anything."
  {(s/optional-key :history) [ManifestHistoryEntry]
   s/Keyword s/Any})

(s/defschema ManifestContext
  "What a `:manifest-fn` is called with beside the manifest: the `:dir` it is
  written to and, for a composition, `:sources`: each source unit's `UnitRef`
  and its whole manifest as the registry read it (nil when it has none), in the
  order of the manifest's `:analysis-run :units`."
  {:dir s/Str
   (s/optional-key :sources) [{:unit {s/Keyword s/Any}
                               :manifest (s/maybe {s/Keyword s/Any})}]})

(s/defschema ManifestFn
  "The `:manifest-fn` hook: `(fn [manifest ManifestContext] -> manifest)`, called
  on the finished manifest (`:created` preserved, `:history` appended without
  exact duplicates) just
  before it is written. What it returns is what is written, so it may add or
  change anything; keeping the keys readers rely on intact (`:source`,
  `:analysis-run`, `:staleness`) is the caller's to get right."
  (s/=> {s/Keyword s/Any} {s/Keyword s/Any} ManifestContext))

(s/defschema ManifestOptions
  "What the provenance manifest is built from: `ArtifactOpts` for where it lands,
  `ProvenanceOpts` for who wrote it, plus the run's own description of itself.

  `:namespaces` / `:session` / `:namespaces-fn` are alternatives for the analyzed
  namespace list, in that preference order.

  `:blocks` is merged into the manifest whole — the seam for anything only the
  caller can know, such as the git state of its own tooling. `:analysis-run` is
  merged into the `:analysis-run` block rather than over it, so a caller can
  state its `:method` / `:session-build` / `:scope` without restating the parts
  this namespace derives. `:manifest-fn` (a `ManifestFn`) is the last word, for
  what a merge cannot say, e.g. enriching each `:analysis-run :units` entry from
  its source unit's manifest.

  `:repo` is **required**, where `ArtifactOpts` has it optional. A manifest is a
  claim about a named body of rules; writing one that silently describes the
  wrong repo — or no repo — is worse than refusing to write it.

  Open, because the same map carries whatever a caller's `:namespaces-fn` reads."
  (merge (dissoc ArtifactOpts (s/optional-key :repo))
         ProvenanceOpts
         {:repo s/Str
          (s/optional-key :namespaces) [(s/cond-pre s/Str s/Symbol)]
          (s/optional-key :namespaces-fn) NamespacesFn
          (s/optional-key :session) SessionOrRulebase
          (s/optional-key :working-tree-notes) s/Str
          (s/optional-key :change) s/Str
          (s/optional-key :blocks) {s/Keyword s/Any}
          (s/optional-key :analysis-run) {s/Keyword s/Any}
          (s/optional-key :manifest-fn) ManifestFn
          (s/optional-key :out-dir) s/Str}))

;; ===========================================================================
;; serving
;; ===========================================================================

(s/defschema ServedLayerSelector
  "Which layer stack to serve: `:merged` for everything on disk, one
  `LayerArtifactKey`, a coll of those, or explicit path(s) — lowest precedence
  first. See `clara.explorer.artifacts.serve/->served-layer-paths`."
  (s/cond-pre (s/eq :merged)
              LayerArtifactKey
              s/Str
              [(s/cond-pre LayerArtifactKey s/Str)]))

;; ===========================================================================
;; the registry (plural artifact sets)
;; ===========================================================================

(s/defschema UnitRef
  "One artifact unit: a `:repo` path under a registry `:root`, optionally a
  `:variant` — the ordered `[axis value]` vector, always ending in `[:ref …]`,
  naming a unit under `_variants/<repo>/<variant…>/`. `:repo` is the path
  relative to the root, `/`-joined; a mainline unit carries no `:variant`.

  `:namespaces`, when present, narrows the unit to the named namespaces for the
  merge — a filter the caller supplies, not a claim about what the unit covers.
  A namespace the caller names but no selected unit covers is recorded under the
  composed manifest's `:coverage :unknown-namespaces`.

  Addressed by the same pair
  `clara.explorer.artifacts.store/get-out-dir` already resolves."
  {:repo s/Str
   (s/optional-key :variant) Variant
   (s/optional-key :namespaces) [(s/cond-pre s/Str s/Symbol)]})

(s/defschema ComposePersistOptions
  "Options for `clara.explorer.artifacts.flow/compose-persist!`.
  `:root` is the registry root the source `:units` are read from, and — absent an
  explicit `:dir` — the root the composed unit is written under. `:name` is the
  composition's name, a `/`-joined path under `_compose/`; the composed unit's
  `:repo` (the manifest's `:repo`, and its default output subdir) is
  `_compose/<name>`. `:dir` overrides the output location without changing that
  identity claim, so the two are independent.

  `:units` is the ordered source selection. `:generated-by` is required by
  `ProvenanceOpts`; `:analysis-run` is merged into the manifest's
  `:analysis-run` block as the seam for recording the composition.
  `:manifest-fn` is `ManifestOptions`' hook, its context carrying `:sources`."
  (merge (dissoc ArtifactOpts
                 (s/optional-key :repo)
                 (s/optional-key :root))
         ProvenanceOpts
         {:root s/Str
          :name s/Str
          :units [UnitRef]
          (s/optional-key :analysis-run) {s/Keyword s/Any}
          (s/optional-key :manifest-fn) ManifestFn}))

(s/defschema ComposePersistResult
  "What `flow/compose-persist!` returns: the output dir, the layer ids that
  folded, the composed rule count, and the paths it wrote."
  {:dir s/Str
   :layers [LayerId]
   :rule-count s/Int
   :rulebase-analysis s/Str
   :rulebase-analysis-digest s/Str
   :manifest s/Str})

(s/defschema VariantMismatch
  "A variant unit whose decoded directory path disagrees with its manifest's
  `:variant` — e.g. a directory renamed by hand. `:path` is the directory's
  decoded `:variant` (nil for a mainline dir), `:manifest` the manifest's.
  Consumed by `clara.explorer.artifacts.registry/get-variant-mismatches`."
  {:path (s/maybe Variant)
   :manifest (s/maybe Variant)})

(s/defschema UnitInfo
  "What
  `clara.explorer.artifacts.registry/discover` records per unit: its
  `UnitRef`, the resolved `:dir`, the artifact roles present, the slim
  `:dropped` key set (the merge's shape), the layer ids the manifest records,
  the manifest's `:created` / `:sha` / `:history` head and the whole manifest
  as read (`:manifest`), and — for an aggregate
  unit — the manifest's `:analysis-run :mode` and the units it was composed
  from (`:analysis-run :units`). A variant whose directory disagrees with its
  manifest's `:variant` carries a `:variant-mismatch`. Absence of `:mode`
  marks a source unit; the `:mode` value is host-set and open.

  Validated at build time by `clara.explorer.artifacts.registry/->unit-info`,
  so a key the walk records but this schema omits fails the test suite rather
  than drifting silently."
  (merge UnitRef
         {:dir s/Str
          :artifacts #{ArtifactKey}
          (s/optional-key :slim-dropped) (s/maybe #{s/Keyword})
          (s/optional-key :layer-ids) (s/maybe {s/Keyword s/Any})
          (s/optional-key :manifest-head) {s/Keyword s/Any}
          (s/optional-key :manifest) {s/Keyword s/Any}
          (s/optional-key :mode) s/Any
          (s/optional-key :composed-from) [UnitRef]
          (s/optional-key :variant-mismatch) VariantMismatch}))

(s/defschema CompatibilityReport
  "What
  `clara.explorer.artifacts.registry/compatibility-report` returns:
  whether every selected unit is mergeable (the analyzed units share one slim
  `:dropped` key set and no selected unit is missing its analysis), which
  analyzed units disagree with the majority shape, which units have no analysis
  to read, the per-unit dropped sets, and the artifacts each unit is missing.
  Keyed by `clara.explorer.artifacts.shared.registry/unit-key` rather
  than by the `UnitRef` map, which is not a comparable map key."
  {:compatible? s/Bool
   :majority-shape (s/maybe #{s/Keyword})
   :shape-mismatch [UnitRef]
   :no-analysis [UnitRef]
   :dropped-key-sets {s/Str (s/maybe #{s/Keyword})}
   :missing-artifacts {s/Str #{ArtifactKey}}})

(s/defschema SwapSourceEntry
  "One entry of the `:source` stack handed to a running server: a `Layer` held in
  memory, or a path to a layer file the server re-reads."
  (s/cond-pre Layer s/Str))

(s/defschema ServerSwapOpts
  "The `clara.explorer.server.client/swap-session!` argument: the session to serve
  and the annotation stack to serve it under. `:annotations` is the server's
  `AnnotationsSpec`."
  {:session SessionOrRulebase
   :annotations {:source [SwapSourceEntry]}})
