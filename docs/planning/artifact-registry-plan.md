# Artifact registry — reading, merging and serving many artifact sets — plan

Status: **Proposed** · Scope: `server/` (Clojure) · Related:
`artifacts/store.clj`, `artifacts/slim.clj`, `artifacts/parts.clj`,
`artifacts/digest.clj`, `artifacts/manifest.clj`, `graph/server.clj`,
`graph/api.clj`, `server/docs/persisted-artifacts.md`

## 1. Goal

An artifact set describes one rulebase, analyzed once, in one directory.
`artifacts/store.clj` addresses exactly one of them at a time — `{:root :repo
:branch}` — and every reader above it (`slim`, `parts`, `digest`, `manifest`,
`serve`) is scoped to that one set.

Hosts accumulate many. One per source repo of a rulebase that is composed from
several, one per branch variant of a repo under review, one per captured
session. The questions worth asking span them: *who consumes the type this set
produces*, *what does this branch do to the others*, *what does this set of
sets look like as one rulebase*. Nothing in the library can enumerate them
today, let alone join them.

Three things, in order:

1. **A registry** — discover and read N artifact sets under a root, as a value.
2. **A merge algebra** — combine a caller-named selection of sets, in one of
   three modes, into one queryable structure.
3. **A served merge** — start the explorer server from a registry selection,
   with no live session, and answer the analysis routes from the merge.

The library discovers and merges. It never decides *which* sets belong together
or *what a set means* — that is the host's, and every entry point takes the
selection explicitly. This is the same line `store/get-out-dir` already holds
for `:root`: mechanism here, policy at the caller.

## 2. Vocabulary

| term | meaning | addressed by |
| --- | --- | --- |
| **unit** | one artifact set: a directory holding the files in `layout/artifact-files` | `{:repo :branch}` under a registry `:root` |
| **registry** | a root directory and the units discoverable beneath it | `:root` |
| **variant** | a unit's `branches/<label>/` sibling set | `:branch` on the same `:repo` |
| **selection** | the caller's ordered list of units, with optional per-unit namespace filters, handed to a merge | `[UnitRef]` |

`:repo` is already this library's name for the subdir a run writes under, so a
unit is identified by the pair it is already addressed by. Nothing new is
invented to name one.

## 3. What exists

`store` resolves paths and folds one unit's three layer artifacts. `parts`
splits and rejoins an analysis. `slim` removes what is recoverable and records
the removal in `:slim :dropped` / `:slim :recover`. `digest` reduces an analysis
to what fits in a head. `manifest` records what a run saw. `serve` turns a
selector into layer file paths for the server's `:annotations-spec`.

Every one of those is a pure function of one unit. This plan adds the plural
case and changes none of their contracts.

## 4. A. `artifacts/registry.clj` — the registry as a value

Pure IO over a root. No environment variable, no session, no classpath
requirement beyond this library.

```clojure
(registry/discover {:root "…"})            ; => Registry
(registry/units registry)                  ; => [UnitRef]
(registry/read-analysis  registry unit)    ; => RulebaseAnalysis   (slim, from parts)
(registry/read-digest    registry unit)
(registry/read-manifest  registry unit)
(registry/read-annotations registry unit)  ; => MergedAnnotations  (layer refs expanded)
(registry/->registry {:root "…" :units [{:repo "a"} {:repo "b" :branch "spike"}]})
```

**Discovery rule.** A directory is a unit iff it holds
`rules-inspect-manifest.edn` — the one artifact every complete set has, and the
one that says what the rest of the set is. The walk descends arbitrarily deep,
so a host may group its units however it likes (by kind, by team, by capture
date) and the unit's `:repo` is its path relative to the root. The single
reserved segment is `branches`: a directory of that name is read as the variant
holder `store/branches-subdir` already defines, and its children become
`:branch` labels on the parent unit rather than units of their own.

That rule is why no host layout knowledge is needed here. A host that keeps
per-source-repo sets beside a tree of captured sessions gets both as units,
named by their own paths, without this namespace knowing either concept exists.

**Reads are per-unit and lazy.** `read-analysis` goes through
`store/read-analysis-parts` + `parts/<-parts`, so a caller that only needs the
scan projection opens one part. `Registry` holds unit refs and manifests — the
cheap, complete part — and memoizes the expensive reads it is asked for.

**Compatibility is reported, not assumed.** Units in one registry are written by
different versions of this library at different times. Per unit, `discover`
records: which artifacts are present, the `:slim :dropped` key set from
`merged-rulebase-analysis/meta.edn`, `:layer-ids`, and the manifest's `:created`
/ `:history` head. `registry/compatibility-report` turns that into the one
question a merge has to answer first — *do these sets have the same shape* —
and names the units that do not. A merge over mismatched shapes throws, naming
the differing key sets; it never silently unions two vocabularies.

## 5. B. `artifacts/rehydrate.clj` — putting back what `slim` says is recoverable

`slim` removes two kinds of thing: what is *derivable* from what it keeps, and
what needs a live session. `:slim :recover` already distinguishes them, one
entry at a time, in prose. This namespace makes the first kind executable.

```clojure
(rehydrate/rehydrate-analysis analysis)                 ; inverses + indexes rebuilt
(rehydrate/rehydrate-analysis analysis {:annotations …}) ; also restores :merged-annotations
```

Rebuilt, in every case from a key `slim` keeps:

| restored | from |
| --- | --- |
| fact-type `:inserted-by-rules` / `:retracted-by-rules` | the inverse of each production's `:insert-types` / `:retract-types`, closed over **`:ancestors`** |
| fact-type `:used-by-rules` / `:used-by-queries` | the inverse of `:lhs-types`, closed over **`:descendants`** — the opposite direction |
| fact-type `:descendants` | the transpose of `:ancestors` |
| production `:upstream` / `:downstream`, `:dep-graph` `:downstream` | `:dep-graph` `:upstream` |
| `:id` on every production and fact type, `:production-id-index`, `:fact-type-id-index` | the `:name` each is keyed by, through the same id fn the analysis uses |
| `:merged-annotations`, `:dynamic-*-detected` (authored form) | the `merged-annotations.edn` beside it, when the caller passes it |

The two closure directions are the part to get right and the part `recovery`
already states: inverting `:lhs-types` alone matched 2033 of 3687 fact types in
the measurement recorded there, so an unclosed inverse is not a smaller answer
but a wrong one. `slim-test`'s existing inversion cases pin both directions.

Left absent, and still declared in a narrowed `:slim` block: `:nodes`,
`:lhs-form`, `:raw-condition`, `::conditions/normalized`, and `:ns-deps`.
`:lhs-form` is the one that looks recoverable and is not — it renders from
`conditions/get-raw-lhs`, the raw clara LHS, which `:raw-condition` carried and
`slim` drops; re-rendering from the serialized `:lhs` would produce a similar
string that is not the same string, and a near-miss is worse than an absence.

Two reasons this is worth its own namespace rather than being folded into the
server:

- **It is `slim`'s inverse, so it is testable as one.** `slim-test` already
  asserts each dropped direction inverts back (`dropped-directions-invert-back-test`);
  this turns that property into the shipped function and the test becomes
  `(= analysis (-> analysis slim rehydrate))` modulo the absent set above.
- **Every consumer of a persisted analysis needs it.** The composed analysis
  (§6) and the server (§8) both do, and a federated index is cheaper to build
  over rehydrated units than to teach every query fn to walk forward keys only.

## 6. C. Merge modes — `artifacts/compose.clj` and `artifacts/federate.clj`

Three ways to combine a selection, split across two namespaces by what they
return. Each takes the selection explicitly; none of them discovers intent.

### `compose/fold-layers` — mode `:layers`

The generalization of `store/get-layer-stack` across units: a selection of
`{:repo :branch :layers}` entries folded in caller order, lowest precedence
first, into one `MergedAnnotations`. The use is a host that keeps curated
annotations in a unit of their own, or serves a base unit with a variant's
curated overlay on top of it.

**One schema consequence.** `LayerId` is a per-set identity — the three ids in
`store/layer-artifacts`, plus `:props`. Folding two units together collides all
three, and `:provenance` would then name a layer without saying whose. Across
units the fold qualifies each id as `<repo>[@<branch>]/<layer-id>` and
`MergedAnnotations` `:layers` carries the qualified form. Within one unit
nothing changes, so no existing artifact moves.

### `compose/->composed-analysis` — mode `:compose`

The caller asserts that the selected units are components of **one** rulebase
and gets one `RulebaseAnalysis` back.

- `:rules` / `:queries` merged by fq name. A name in two units is an error, the
  way `parts/->flat-productions` already refuses a rule/query collision —
  two units claiming one production name means the selection is wrong, and
  guessing a winner would be a silent wrong answer for every query downstream.
- `:fact-types` merged per name, with `:ancestors` unioned across units (see
  §7 — this is the same closure the federated index computes, and both call it).
- `:dep-graph` unioned.
- `:slim` is the union of the units' dropped sets, plus `:nodes` unconditionally:
  a composed analysis has no Rete network, because the composition never
  compiled.
- Each production gains `:unit`, naming the unit it came from. It is the only
  key composition adds, and without it no answer over a composed analysis can
  say where a rule lives.

This is what the server serves in §8, and it is the reason `rehydrate` comes
first: composing slim analyses and then rehydrating the result rebuilds the
inverses *over the whole composition*, which is the point — a fact type's
`:used-by-rules` across all units is exactly what a per-unit artifact cannot
contain.

### `federate/->index` — mode `:union`

The selected units are **not** claimed to compose. They are independent
rulebases that share a fact-type vocabulary, and the question is how they relate.
This is §7.

The three modes are not alternatives to pick between. A host typically folds
layers within each unit, federates to find out which units interact, and
composes the subset it decides really is one rulebase.

## 7. D. The federated index

```clojure
{:scope       {:units [UnitRef] :namespaces {unit [ns]}}
 :provenance  {unit {:sha … :created … :branch … :artifacts […]}}
 :coverage    {:units [unit] :unknown-namespaces [ns]}
 :hierarchy   {:ancestors   {ft #{ft}}
               :descendants {ft #{ft}}
               :conflicts   {ft {unit #{ft}}}}
 :fact-types  {ft {:declared-in          #{unit}
                   :inserted-by          {unit #{rule}}
                   :retracted-by         {unit #{rule}}
                   :matched-by           {unit #{rule}}   ; exact type match
                   :matched-via-ancestor {unit #{rule}}   ; rule matches an ancestor of ft
                   :polarity             {rule #{:positive :negated :exists :accumulated}}
                   :producers            #{unit}
                   :consumers            #{unit}}}
 :unit-edges  {[producer consumer] {:via #{ft} :rules n}}
 :entry-points {unit #{ft}}   ; consumed in scope, produced by nothing in scope
 :orphans      {unit #{ft}}}  ; produced in scope, consumed by nothing in scope
```

Query fns over the value:

| question | call |
| --- | --- |
| I am changing `T` — who breaks? | `(federate/impact-of index T)` |
| …including everything deriving from it? | `(federate/impact-of index T {:descendants? true})` |
| Where does `T` come from? | `(federate/producers-of index T)` |
| Which units depend on this one, through what? | `(federate/dependents-of index unit)` |
| How does a fact get from A to B? | `(federate/paths-between index a b)` (bounded BFS) |
| What is the unit-level dependency graph? | `(federate/unit-dependency-graph index)` |
| What can this index not answer? | `(federate/coverage-report index)` |

`impact-of` answers two questions under one name because reviewers ask both:
consumers matching the type exactly, and consumers matching any *ancestor* of it
— a change to the type reaches those too. The descendant direction is opt-in,
because it is the blast radius of changing a parent rather than of changing the
type named.

### The hierarchy is per-unit, and that is a defect the index has to repair

`:ancestors` is a transitive closure computed by clara's ancestor fn *on the
classpath that analysis had*. A `derive` that lives in a component the analysis
did not load is simply absent from it. So two units can hold different ancestor
sets for the same type name, and both are locally correct.

Measured on a real 10-unit registry: of the 393 fact-type names present in more
than one unit, **99 have differing ancestor sets between units**. So
`->hierarchy` unions every unit's edge set, re-closes transitively, and records
the disagreements under `:conflicts` rather than picking a winner silently.

Hierarchy-aware matching is the difference between a useful answer and a wrong
one. On that same registry, counting only exact name matches, one unit consumes
797 types it does not produce. Under the globally-closed hierarchy — where a
rule matching `T` is satisfied by any produced *descendant* of `T` — that drops
to 423, and 124 more resolve to a producer in another unit. The residual 299 are
genuine entry points: inserted by the caller, produced by a component with no
artifacts, or lost to an unresolved dynamic insert. Only the last is a defect,
and the index has to keep them distinguishable.

### Consumption is exact, and carries polarity

`:lhs-types` is complete over the LHS condition structure —
`conditions/extract-lhs-fact-types` recurses an accumulator through its `:from`,
walks `:and` / `:or` / `:not` / `:exists`, and contributes nothing for a `:test`,
which has no fact type. Checked against artifacts rather than argued from
source: over a 5025-production analysis (14953 top-level conditions, 19405
condition nodes counting groups and accumulator `:from` subtrees), every
production's `:lhs-types` is exactly the set of `:type` values in its condition
tree, in both directions. **Consumer counts are exact, not a lower bound.**

What `:lhs-types` flattens is polarity. A rule coupled to `T` through a `:not`
is indistinguishable there from one matching it positively. Both are affected by
a change to `T` — firing on absence breaks as hard as firing on presence — but
they are different breakages and a reviewer wants them apart. `:lhs` survives
slimming with each node's kind intact, so the index records polarity per
consuming rule instead of inferring it.

Two rules the polarity walk depends on, both properties of the normalized shape
this library emits (`conditions/normalize-lhs`, documented in
`docs/explorer-graph-api.md`):

- A group is a **map** with `:condition-type` and `:children`, not a vector
  head; an accumulator is an **object** (`{:form :some-initial-value?}`) whose
  fact type is under `:from`.
- A group's `:bindings` is the componentwise **union of its children's**, so it
  is derived rather than additional: aggregate over leaves or read group
  summaries, never both. The one node without `:bindings` is an accumulator's
  `:from` subtree, which is a statement of what the unit of condition is, not a
  gap. When the question is "what does this condition depend on", union
  `:binding-keys` with `:join-filter-join-bindings` — the second holds upstream
  variables joined through a non-equality unification, and reading only the
  first silently drops them.

### Cost

Measured, warm JVM, over a real 10-unit registry — 4268 rules, 275 queries,
4892 distinct fact types, ~15MB of EDN: **~0.16s** to read and parse the lot. A
5025-production single analysis is 20MB and parses in ~0.23s on its own.

That settles the design question a persistence tier would otherwise raise: **no
projection artifact is needed and nothing has to be persisted to be usable.**
Read the units, build the index, answer the question — as a value. Writing an
index to disk is an opt-in act for the cases that need a file, not a step on the
path to an answer.

## 8. E. `federate/->digest` and `federate/persist!`

`->digest` reduces an index to something readable whole, the same contract
`digest/->rulebase-analysis-digest` holds for one analysis: unit edge list,
counts, entry points, orphans, hierarchy conflicts, coverage gaps, per-unit
provenance. A function on the index, so an agent-readable answer never depends
on the persistence step, and — as with the per-unit digest — its `:more` names
the routes and vars that answer what it omits, for a reader with no classpath.

`persist!` writes `registry-index.edn` and `registry-digest.edn` to an explicit
`:dir`. It takes no root and derives no directory name: how a host names the
answer to one cross-unit question is the host's, and the same registry answers
many. Both filenames join `layout/artifact-files` under new keys so
`store/get-artifact-path` and the babashka reader see them like any other
artifact.

## 9. F. Serving a registry selection

Today `graph/server.clj` holds a live session and `cache.clj` derives the
analysis from it. The registry mode supplies the analysis instead:

```clojure
(server/start! {:registry {:root "…"
                           :units [{:repo "a"} {:repo "b" :branch "spike"}]}
                :port 8080})
```

- `ServerState` gains `:rulebase-analysis` as an alternative to `:session`.
  Exactly one is present; the config schema enforces that.
- `cache/get-rulebase-analysis` returns a supplied analysis as-is, invalidated
  by `identical?` on the supplied value exactly as it is on a session today.
- `:annotations` come from `compose/fold-layers` over the same selection, so
  `GET /v1/annotations` answers across sources — which is the one route whose
  whole point is "what do we know about this rulebase, and who said it".
- Session-dependent routes (`/v1/session/*`, `/v1/memory-analysis`) answer 409
  with a new reason `:no-session`, beside the existing `:disabled-by-config` and
  `:rulebase-input`.
- Every other route answers from the composed, rehydrated analysis.
  `/v1/rulebase-analysis` carries the `:slim` block so a client can tell what is
  absent, and `:nodes` is empty.

The server is the right home for this because it is the thing that can afford to
rebuild what `slim` dropped. `slim` drops the reverse directions *because* they
are recomputable; §5 recomputes them in memory; the result is that serving a
registry answers nearly everything a live session does, for a rulebase nobody
can compile in one process.

A reload endpoint is deliberately out of the first cut: re-reading a registry is
`start!` with the same selection, and what a partial refresh should mean when
one unit of twelve has moved is a question worth having a use for before
answering.

## 10. G. Grading a federation against a composed reference

When a host has an artifact set for a rulebase that *really did* compose — a
captured session, a monolithic run — it is ground truth for a federation over
the same components. `federate/grade index reference-analysis` reports:

- entry points the reference has a producer for (so the union was missing a
  component, not the rulebase),
- unit edges the reference confirms, and edges the reference contradicts,
- namespaces in the reference that no unit in the index covers.

This grades the union; it does not replace it. A reference exists only where
someone captured one, and the union is the answer available for the rest.

## 11. Assumptions and limits

- **The join key is the fact type name.** Two units using one name for two
  different things produce a wrong join, and nothing here can detect it. The
  library states the assumption; `:conflicts` and `:declared-in` are what a host
  reads to check it.
- **Units are read as slim.** Everything above is stated over what `slim`
  leaves; `:nodes` and the raw condition forms never participate.
- **Shape skew is refused, not bridged.** A registry whose units were written by
  differing versions is a regeneration problem; `compatibility-report` names it
  and the merges throw. A reader that tolerates two shapes is a permanent tax
  for a transient state.
- **Nothing is discovered about meaning.** Which units compose, which are
  variants of each other, what a unit's name signifies — all host-supplied.

## 12. Phases

Each phase leaves `make test lint reflection-check` green in `server/`.

1. **`artifacts/registry.clj`** — discovery, per-unit reads, compatibility
   report. Tests build a registry of small hand-written units in a temp dir.
2. **`artifacts/rehydrate.clj`** — the inverse of `slim`, with the round-trip
   property test against `slim-test`'s existing fixtures.
3. **`artifacts/compose.clj`** — `fold-layers` across units (with qualified
   layer ids) and `->composed-analysis`, including the name-collision refusal.
4. **`artifacts/federate.clj`** — hierarchy union and re-closure, `->index`, the
   query fns, polarity. The analytic core; nothing is written.
5. **`->digest` + `persist!`** — the two new `layout/artifact-files` entries and
   the babashka reader's view of them.
6. **Serving** — `ServerState`, `cache`, the 409, config schema, route tests
   over a registry-backed server with no session.
7. **`grade`** — last, because it is a report over two things phases 4 and 3
   both have to exist to produce.

Phases 1–2 are usable on their own: a host that only wants to read N units and
rebuild their inverses stops there. Phase 6 is the only one that touches
anything outside `artifacts/`.

## 13. Docs

`server/docs/persisted-artifacts.md` is the on-disk story and gains the registry
as its plural chapter: what a unit is, how discovery works, the three merge
modes, and the shape/skew rule. `docs/explorer-graph-api.md` gains the
registry-backed server mode beside the session-backed one, with the 409 list.
`README.md` gains one line in the Documentation list. Nothing restates the
index shape outside the one doc that owns it.

## 14. What this plan does not decide

- **Whether an index should be persisted by default.** §7 measures that it need
  not be; `persist!` exists for handing a file to a reader, and the default
  stays "compute it".
- **Incremental refresh.** Re-reading is cheap enough (§7) that invalidating one
  unit of a built index has no demonstrated need.
- **A cross-unit write path.** Nothing here writes annotations back into a unit;
  curation stays per-unit, where the callsite ids are unique.
