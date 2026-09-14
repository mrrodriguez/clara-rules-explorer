# Scoped merges and composed-unit provenance — plan

Status: **Proposed** · Scope: `server/` (Clojure) · Related:
[`artifact-registry-plan.md`](artifact-registry-plan.md),
[`composed-artifact-persist-plan.md`](composed-artifact-persist-plan.md),
[`ana-registry-integration-problems.md`](ana-registry-integration-problems.md),
`tools/graph/artifacts/compose.clj`, `tools/graph/artifacts/registry.clj`,
`tools/graph/artifacts/federate.clj`, `graph/server.clj`

## Goal

The registry now merges a selection three ways and can materialize a
composition as a unit. Putting that to work downstream — *one merge per
deployment scope, written once, then read offline by the babashka report,
served to the UI, and diffed against the same scope on a branch* — turns up two
correctness gaps and two things that are simply absent.

All four are about the same transition: a merge stops being a value in a REPL
and becomes a directory somebody else reads.

1. **§1** `:namespaces` narrowing reaches the analysis but not the layers, so a
   scoped merge serves and persists annotations for rules it excluded.
2. **§2** A composed unit records nothing about the units it was composed from
   beyond their names, so it cannot answer "is this stale?" offline.
3. **§3** Nothing reads back what `federate/persist!` wrote, and an index does
   not know which question it answers.
4. **§4** No way to diff two indexes, which is the branch-vs-mainline question.

§1 and §2 are correctness; §3 and §4 are capability. Every measurement below is
from the units checked in under `server/test-resources/rules-annos/`.

## 1. Narrowing stops at the analysis

`registry/narrow-analysis` filters `:rules` / `:queries` to the productions
whose `:ns` a `UnitRef`'s `:namespaces` names, and `federate/->index` and
`compose/->composed-analysis` both apply it. The layer side does not:
`compose/fold-layers` and `compose/->standard-role-layers` fold each unit's
whole layer stack, with no reference to the filter.

```clojure
(let [reg      (registry/discover {:root "test-resources/rules-annos"})
      src      (registry/source-units reg)
      narrowed (mapv #(assoc % :namespaces ["clara.server.tools.graph.rules.loan-app-rules"]) src)]
  [(count (:rules (compose/->composed-analysis reg narrowed)))              ;; => 4
   (count (ann.merge/annotations (compose/fold-layers reg narrowed)))       ;; => 20
   (count (ann.merge/annotations (compose/fold-layers reg src)))])          ;; => 20
```

The narrowed fold is byte-identical to the unnarrowed one. Two consequences,
both silent:

- **Registry-backed serving disagrees with itself.** `server/transition-start`
  builds `:rulebase-analysis` from `->composed-analysis` (narrowed) and
  `:annotations` from `fold-layers` (not narrowed). `GET /v1/annotations` then
  describes rules that `GET /v1/rules` does not have, and the annotation count
  a client sees is the count of a scope nobody asked for.
- **`compose-persist!` writes an internally inconsistent unit.**
  `merged-rulebase-analysis/` holds the narrowed productions;
  `auto-gen-annotations.edn` and `merged-annotations.edn` hold every rule of
  every selected unit. The offline report reads the second and reports on rules
  the first cannot explain, with nothing in the directory saying why.

### Fix

`MergedAnnotations` is `{:annotations {RuleName …} :layers […] :provenance
{RuleName …}}` and a `RuleName` is a fully-qualified `ns/name`, so narrowing is
a key filter on the namespace part of two maps:

- Add `registry/narrow-annotations` beside `narrow-analysis`, taking the same
  `UnitRef`, filtering `:annotations` and `:provenance`.
- Apply it per unit in `compose/fold-layers` and `compose/->standard-role-layers`,
  **before** the fold, so a layer's contribution to the merge is the scope's
  contribution and per-callsite `:from-layer` provenance stays truthful.

Three decisions it needs:

- **Where the filter applies.** Per unit, before folding, not to the folded
  result — otherwise a rule filtered out of unit A can still be credited to unit
  A's layer in `:provenance`.
- **Non-production keys.** Whatever a layer carries that is not keyed by rule
  name stays; the filter is a statement about productions, and a fact-type-keyed
  entry has no namespace to test.
- **Whether a narrowed layer says so.** `->standard-role-layers` already writes
  `:source {:composed-of […] :role …}`. A narrowed fold should add the filter
  there, so a reader of the persisted layer can tell a scoped set from a whole
  one.

The weaker alternative — have `compose-persist!` refuse a selection carrying
`:namespaces` — is worth naming only to reject it: scoped composition is the
main reason to materialize a composition at all.

## 2. A composed unit cannot say how stale it is

`compose-persist!` records `:analysis-run {:mode :compose :units [{:repo …} …]}`
— the unit names, and nothing else. The manifest's `:source` block is the git
state of *this library's* checkout, and `:staleness` is the standard
`{:policy "review-when-sha-drifts"}`, which points a reader at that sha.

For a source unit those two keys are exactly right: one directory, one repo, one
sha to be true of. For a composed unit they are the wrong question. The sha that
matters is each source's, and a reader holding the directory cannot get at any
of them:

```clojure
(:analysis-run (registry/read-manifest reg {:repo "composed/loan-app-plus-disposition"}))
;; :units [{:repo "loan-app-ruleset"} {:repo "loan-disposition-ruleset"}]   ; no shas
```

`federate/->index` does carry `:provenance` with `:sha` and `:created` per unit
— but only when the sidecar was also written, and neither the offline report nor
the server reads it. The artifact that every consumer *does* read is the one
that cannot answer.

This matters more for a composition than for anything else in the registry: a
composed unit is stale as soon as *any* of N independently-moving sources has
moved, which is N times more likely than a single unit going stale and is the
first thing a reviewer needs to know before trusting an answer.

### Fix

`compose-persist!` already holds the `Registry`, and `registry/unit-info`
already carries `:manifest-head` with `:sha` and `:created`. So:

- Enrich each `:analysis-run :units` entry to `{:repo :branch :sha :created}`
  from the source unit's `unit-info` — no new read, no new IO.
- State the composition's staleness in its own terms:
  `:staleness {:policy "review-when-any-source-sha-drifts" :sources {…}}`, or
  the same policy string with the per-source shas above as the thing to compare.
  Either way the directory answers "is this current?" without the sidecar.
- Leave `:source` as-is. It is honest about what wrote the files; it just is not
  what a reader of a composition is asking about, and the two blocks reading
  differently is the point.

## 3. Read back what `persist!` wrote, and let an index name its question

`federate/persist!` writes `registry-index.edn` and `registry-digest.edn`.
Nothing reads them. A host that persisted an index and wants to ask it something
later either hand-rolls the EDN read or rebuilds the index from the registry —
and rebuilding is the thing persistence existed to avoid.

- `federate/read-index` / `federate/read-digest`, taking the same `:dir`
  `persist!` takes. A read index is a plain value and every query fn already
  works on one.
- A caller-supplied `:label` (or `:question`) recorded into the index's
  `:scope`, so an index says which scope it is of. Without it, two persisted
  indexes side by side are distinguishable only by their directory names, which
  is exactly the information the file should not depend on — a directory gets
  copied, renamed, or attached to a ticket.

Small, and the pair is what makes §4 usable.

## 4. `federate/diff` — the branch-vs-mainline question

The reason `UnitRef` carries `:branch` is to ask what a branch does to the
units around it: build the index twice over the same unit set, once mainline and
once with the branch variant selected, and compare. Upstream has both halves and
none of the comparison, so every host writes the same reduce.

`(federate/diff before after)` over two indexes of overlapping unit sets:

```clojure
{:units        {:added […] :removed […] :rebased […]}   ; selection differences, incl. branch swaps
 :unit-edges   {:added […] :removed […] :changed {…}}   ; :via sets that grew or shrank
 :fact-types   {ft {:producers {:added … :removed …}
                    :consumers {:added … :removed …}}}
 :entry-points {:added {unit #{ft}} :resolved {unit #{ft}}}
 :orphans      {:added {unit #{ft}} :resolved {unit #{ft}}}
 :hierarchy    {:conflicts-added […] :conflicts-resolved […]}}
```

The two properties worth pinning in tests: a diff of an index against itself is
empty in every key, and swapping one unit for its branch variant reports only
edges and types that variant touches. Nothing here needs a new read — it is a
pure function of two values.

Lower priority than §1–§3 because a host can write it; included because it is
the one question the registry was designed to answer that still has no answer in
the library.

## Non-goals

- **No change to the single-unit write path.** `flow/merge-persisted!` has no
  filter and no sources; nothing here touches it.
- **No new artifact filenames.** §2 enriches blocks inside the manifest;
  §3 reads the two files `persist!` already writes.
- **No policy about which units belong together.** §1 makes a scope the caller
  named actually hold; it does not infer one.

## Phases

1. **§1 narrowing through the layers** — `registry/narrow-annotations`, applied
   in both fold paths, with a test asserting the folded annotation count equals
   the composed rule count for a narrowed selection, and a server test asserting
   `/v1/annotations` and `/v1/rules` agree in registry mode.
2. **§2 composed provenance** — enriched `:analysis-run :units` and the
   staleness block, with the checked-in composed fixture regenerated.
3. **§3 readers and the label** — `read-index` / `read-digest`, `:label`
   through `->index` / `persist!`.
4. **§4 `diff`** — last, on top of §3.

§1 and §2 are independent of each other; §3 and §4 are a pair.
