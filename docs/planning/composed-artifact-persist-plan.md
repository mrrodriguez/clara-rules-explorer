# Composed artifact persist — Plan

Status: **Proposed** (future potential; no roadmap yet) · Scope: `server/` (Clojure)
· Related: `tools/graph/artifacts/compose.clj`,
`tools/graph/artifacts/registry.clj`, `tools/graph/artifacts/store.clj`,
`tools/graph/artifacts/flow.clj`, `tools/graph/artifacts/digest.clj`,
`tools/graph/artifacts/manifest.clj`, `tools/graph/artifacts/layout.cljc`,
`bin/annotations_report.bb`, `test/clara/server/tools/graph/artifacts/bb_report_test.clj`,
`server/docs/persisted-artifacts.md`, `docs/planning/artifact-registry-plan.md`

## 1. Goal

Add a JVM-side tool that takes several units from a registry and **persists the
composed result as a new, single-unit artifact directory** the caller names:

```
rules-annos/composed/my-named-merged-ruleset/
```

The directory must be indistinguishable from any other artifact unit to
`server/bin/annotations_report.bb` — the bb script continues to take it as its
single `<dir>` input with no knowledge of composition. The JVM builds the merge
(because only it can load `compose` / `federate` / `ann.merge`), and the bb
script stays the offline reader.

This is the third option after "teach bb compose" and "teach bb federate":
instead of moving merge logic into babashka, **materialize the merge on disk in
the shape bb already understands**.

## 2. Current state

### 2.1 What the bb script expects of a `<dir>`

`bin/annotations_report.bb` reads one artifact directory:

- the three standard layer files, in fold order: `auto-gen-annotations.edn`,
  `memory-annotations.edn`, `agent-annotations.edn` (`read-layer-stack`), and
  uses each file's own `:id`;
- `merged-annotations.edn` in its **compact** form, expanded against those layer
  files (`read-merged`, via `layout/expand-merged-annotations`);
- `merged-rulebase-analysis/` part files, read one at a time (`consumers` reads
  `production-index.edn`, `edges` reads `dep-graph.edn`).

It never reads `registry-index.edn` / `registry-digest.edn` and it has no notion
of multiple units.

### 2.2 What composition produces today

`compose/->composed-analysis` returns a slim `RulebaseAnalysis` with each
production tagged `:unit`, cross-unit dep-graph edges recomputed, and a
`:slim` block describing what is absent. `compose/fold-layers` returns a
`MergedAnnotations` whose layer ids are qualified per unit
(`<repo>[@<branch>]/<layer-id>`).

Neither is directly persistable into the single-unit shape the bb script reads,
for one reason: **the bb script only reads the three standard layer files.** The
qualified per-unit layer ids that `compose/fold-layers` produces cannot be
stored in those three slots without losing the per-unit identity.

### 2.3 What the single-unit writer already does

`flow/persist!` writes one unit end-to-end: `store/write-layer!` for the
generated/memory layers, then `flow/merge-persisted!` folds the on-disk stack,
writes compact `merged-annotations.edn`, writes `merged-rulebase-analysis/`
parts, and writes `rulebase-analysis-digest.edn`.
`manifest/write-manifest!` writes `rules-inspect-manifest.edn`, which is what
makes a directory a discoverable registry unit.

So almost every write step already exists. What is missing is an orchestrator
that flattens a multi-unit fold into the three standard layers and writes the
composed analysis parts directly.

## 3. Proposed design

### 3.1 Entry point

No new namespace. The orchestrator is `flow/compose-persist!`, next to
`flow/persist!` and `flow/merge-persisted!`; the layer preparation it needs is a
pure helper in `compose.clj` (§3.3), next to `fold-layers` and
`qualified-layer-id`.

```clojure
(flow/compose-persist!
  {:root  "…"                                 ; source registry root (and default output root)
   :repo  "composed/my-named-merged-ruleset"   ; output unit identity + default subdir
   :units [{:repo "loan-app-ruleset"}
           {:repo "loan-disposition-ruleset"}]
   :generated-by "…"
   :analysis-run {:mode :compose
                  :root "…"
                  :units […]}})
```

`flow` is the right home because it already orchestrates the single-unit
`generate → persist! → merge-persisted!` pipeline; `compose-persist!` is that
same shape over a registry selection. `compose` is the right home for the
preparation piece because it already owns `fold-layers`, `qualified-layer-id`,
and the cross-unit layer vocabulary.

Output placement follows the existing `ArtifactOpts` contract, not a new pair
of aligned keys: `store/get-out-dir` writes to an explicit `:dir` when one is
given, else to `<:root>/<:repo>`. `:root` is therefore the source registry root
and the default output root; `:repo` is the output unit's registry-relative
identity (what the manifest claims) and its default subdir. The two are
independent concerns — location vs. identity — and are not required to agree.
The discoverable-unit workflow omits `:dir`; a caller that wants the merged
result elsewhere passes `:dir` and keeps `:repo` as the manifest's identity.

A thin `dev/` CLI entry point (the same pattern as
`dev/regen_artifacts.clj`) can wrap it for manual use.

### 3.2 Build steps

1. `reg = registry/->registry {:root root :units units}`.
2. `analysis = compose/->composed-analysis reg units` — the slim composed
   analysis; do **not** rehydrate, and do not re-slim it later.
3. **Flatten the layer stack per role** (§3.3) into at most three layers with
   the standard ids from `layout/layer-artifacts`.
4. Write each flattened layer with `store/write-layer!`.
5. Fold those standard layers in memory (`store/fold-layers`) and write compact
   `merged-annotations.edn` via `store/write-merged-annotations!`.
6. Write `merged-rulebase-analysis/` directly via `store/write-analysis-parts!`
   with the composed analysis, so the composed `:slim` block survives as-is.
7. Write `rulebase-analysis-digest.edn` via
   `digest/->rulebase-analysis-digest` over the composed analysis.
8. Write `rules-inspect-manifest.edn` via `manifest/write-manifest!`, carrying
   the composition provenance in `:analysis-run` / `:blocks`.
9. Optionally write `registry-index.edn` / `registry-digest.edn` (§5) for the
   richer federated provenance.

### 3.3 Layer flattening (`compose.clj`)

`compose/->standard-role-layers` takes `[registry selection]` and returns the
layers to write, keyed by role. For each `[role role-id]` in
`layout/layer-artifacts` (`:auto`, `:memory`, `:agent`):

- read each selected unit's layer stack (`store/get-layer-stack` per unit);
- keep only the layer whose `:id` equals `role-id`;
- re-id each such layer with `compose/qualified-layer-id` (so the fold does not
  throw on duplicate ids);
- fold them in selection order via `ann.merge/merge-layers`;
- wrap the result as a standard layer:

```clojure
{:id          role-id
 :annotations (ann.merge/annotations folded)
 :source      {:composed-of (mapv registry/unit-key units)
               :role        role}}
```

Skip a role when no selected unit contributed it — the same "absent rather than
faked" rule `flow/persist!` already applies to the memory layer.

### 3.4 Why not reuse `flow/merge-persisted!` whole

`merge-persisted!` would be convenient, but it always runs
`slim/slim-rulebase-analysis` over the analysis it writes. The composed analysis
is already slim and carries a composed-specific `:slim` block (union of the
source units' dropped sets plus `:nodes`, with a composed `:references` note);
re-slimming would replace that block with the generic slim one. Writing
`store/write-analysis-parts!` directly is the same amount of code and preserves
the composed provenance.

## 4. bb compatibility

Because the composed directory is written in the single-unit shape, the existing
script works unchanged:

- `summary` / `gaps` / `types` / `producers` / `rule` / `layers` read
  `merged-annotations.edn` and the standard layers;
- `curated` reads `auto-gen-annotations.edn` + `agent-annotations.edn`;
- `consumers` / `edges` read the composed analysis parts.

The composed analysis parts are slim, exactly like any persisted unit: the bb
script's existing `consumers` (forward `:lhs-types`) and `edges` (forward
`:upstream`, transpose for downstream) already work on forward keys. Reverse
directions remain a JVM/server question, as they are for every persisted unit.

## 5. Provenance of the merge

Three places, in increasing richness:

1. **`merged-annotations.edn` `:layers`** — the three standard layers with
   `:source` naming the source units and role, so `bb … layers` prints a
   composition summary.
2. **`rules-inspect-manifest.edn` `:analysis-run` / `:blocks`** — the durable
   record of the operation: `{:mode :compose :root … :units […]}` plus per-unit
   manifest heads (shas/created) for reproducibility.
3. **`registry-index.edn` / `registry-digest.edn` (optional)** — the federated
   view over the *source* units, written into the output dir via
   `federate/->index` + `federate/persist!`. It records hierarchy conflicts,
   unit edges, entry points, orphans, and per-unit provenance.

Note the trade-off made explicit: per-rule, per-unit qualified-layer provenance
(`<repo>/<layer-id>` origins) is **not representable** in the bb script's
three-layer model. The sidecar (manifest and/or federated files) is where that
granularity lives; the unit-shaped directory is what the bb script consumes.

## 6. What stays untouched

- `bin/annotations_report.bb` — zero changes.
- `layout.cljc` — no new filenames unless a dedicated sidecar name is chosen;
  reusing `registry-index.edn` / `registry-digest.edn` needs none.
- No new namespace — `compose.clj` absorbs the layer preparation and `flow.clj`
  absorbs the orchestrator.
- The live-session + registry-compose plan — this is a separate, batch, offline
  materialization path, not a server runtime mode.

## 7. Open questions

- **Registry-index placement.** `registry-index.edn` / `registry-digest.edn` are
  registry-level by `layout`'s own definitions (`unit-artifact-files` excludes
  them). Writing them inside the composed unit dir works (the bb script ignores
  them, and registry discovery ignores unknown files) but conflates "federation
  over the sources" with "this unit's provenance." Alternatives: a sibling dir,
  or a new `compose-provenance.edn` sidecar.
- **Role-major flattening vs. unit-major order.** Flattening per role changes
  the fold order from `compose/fold-layers`'s unit-major order to role-major.
  Because compose refuses a production name claimed by two units, rule-keyed
  annotations do not collide across units, so values agree. Fact-type-keyed
  collisions are the theoretical edge; document that compose's collision
  refusal is what makes the flattening safe.
- **`:source` shape for flattened layers.** A map is more machine-readable; a
  string is what the bb `layers` subcommand truncates most cleanly. Pick one and
  pin it in tests.
- **API surface.** A library fn only, or also a `dev/` CLI like
  `regen_artifacts.clj`? The latter makes the workflow a one-liner and should
  be a thin wrapper, not a second implementation.

## 8. Non-goals / deferred

- No changes to the bb script.
- No recomputation of the merge inside babashka.
- No rehydrated reverse directions in the persisted composed parts (slim, like
  any persisted unit).
- No federation-index-as-unit. `federate/->index` is not a `RulebaseAnalysis`;
  this tool is compose-only. The federated files, when written, are provenance,
  not a second artifact unit.
- No server/editor integration here — that is the separate live-session plan.

## 9. Phases (future)

Each phase leaves `make test lint reflection-check` green in `server/`.

1. **Flatten helper + orchestrator** — `compose/->standard-role-layers` for the
   role flattening; `flow/compose-persist!` writes the three layers, compact
   merged annotations, composed analysis parts, digest, and manifest.
2. **Tests** — compose the two checked-in `rules-annos/` units into a temp dir;
   assert the directory shape, composed `:slim` survival, and manifest
   provenance. Extend `bb_report_test.clj` to run the existing script against
   the composed dir unchanged, pinning `consumers` / `edges` / `layers` output.
3. **CLI wrapper** — `dev/compose_artifacts.clj`, a thin entry point reading an
   inline EDN opts map (or an `.edn` file path) and calling
   `flow/compose-persist!`, mirroring how `dev/regen_artifacts.clj` wraps the
   example generator.
4. **Docs** — `server/docs/persisted-artifacts.md` gains a short "Composing into
   a unit" note beside the registry chapter; this plan graduates to a roadmap
   once work starts.
