# Persisted artifacts

Writing an analysis to disk, and reading it back without pulling megabytes into a
context window.

This is the **only** doc that covers persistence. Everything about the in-memory
annotation algebra — what a layer is, how the fold combines two of them, what
`:provenance` means — is in [`rule-annotations.md`](rule-annotations.md). The
dividing line: anything that is a *file, a directory, a filename, a byte count,
or a recovery instruction* is here.

Most users of this library never write an artifact. The HTTP API serves
everything these files hold and more, off a live session. What the files buy is
answering questions **with no session, no classpath and no JVM** — which is the
case a tool, a CI job, or an agent reading a checked-in registry is in.

The code is `clara.explorer.artifacts.*`.

## The artifact set

Everything is EDN and everything is keyed by `:name` — a fully-qualified
production name (`some.ns/some-rule`) or a fact type (`:loan/applicant`). There
are no ids on disk; the name is the handle.

```
<root>/<repo>/                    mainline unit, or any explicit :dir
  auto-gen-annotations.edn        layer — what static analysis found
  memory-annotations.edn          layer — what a fired session proved
  agent-annotations.edn           layer — what a curator settled. The one precious file
  merged-annotations.edn          the fold of those three + rule :props
  merged-rulebase-analysis/       the analysis over that merge — a DIRECTORY
  rulebase-analysis-digest.edn    ~1–35KB of counts and work lists
  rules-inspect-manifest.edn      provenance: shas, namespaces, history

<root>/_variants/<repo>/          a variant unit, under the one root-level holder
  <axis>=<value>/…/ref=<ref>/     the host's axes, then the checkout ref
```

A run writes `<root>/<repo>/` only when the host marks its axes canonical
(`:canonical? true`) **and** the checkout's ref is the remote's default branch.
Every other run writes under `_variants/<repo>/<axis>=<value>/…/ref=<ref>/` —
the axes are the host's, in nesting order; `ref` is read off the checkout, never
passed by the caller. A dirty working tree does not change this; it is recorded
in `:source :working-tree` as always. `_variants/` is a single root-level
holder, so a host that keeps variants out of version control ignores one path.
Segment values percent-encode `%`, `/`, `@`, and `+` (`/` is how git branch
names survive a level separator).

**Start with `rulebase-analysis-digest.edn`.** It is the only artifact meant to
be read whole: counts, per-namespace rule and query totals, the
source/sink/unlinked tallies, the unlinked rule names, and the `:unresolved` work
list. That is the starting point; the parts it points at are where a question
lives.

## Writing a set

```clojure
(require '[clara.explorer.artifacts.flow :as flow])

(def opts {:root "/path/to/artifacts"
           :repo "my-ruleset"
           :variant [[:region "eu"]]   ; host axes; absent for a mainline run
           :canonical? true            ; this run's axes are mainline
           :generated-by "my-tool"
           :session session})

(-> (flow/generate {:session session :generated-by "my-tool"})
    (flow/persist! opts))
```

`:variant` (the host's axes, in nesting order, may be empty) plus `:canonical?`
place the unit: canonical on the default branch writes mainline; everything else
writes `_variants/`. An explicit `:dir` wins outright over both.

Two options have no default, on purpose.

**`:root`** — this library reads no environment variable. A host that keeps its
artifacts under some `$…_HOME` resolves that itself and passes the result, so the
error its users see names their own variable rather than a key in this schema.
An explicit `:dir` replaces `<root>/<repo>` entirely, which is what a run with no
repo to name (a session restored from a serialized artifact, say) uses.

**`:generated-by`** — a provenance claim, written into every layer and into the
manifest, that outlives the process. A library has no standing to guess it.

### The checked-in example

`clara.explorer.artifacts.regen-example/example-out-dir` (a test namespace under `explorer/test/`; the checked-in directory is relative to `explorer/`) is a
committed example of the whole registry, holding two named ruleset bundles under the `rules-annos/`
root — the layout a rules registry keeps for more than one ruleset:

- `loan-app-ruleset` — the loan-doc-rules + loan-app-rules + loan-doc-queries session (the same
  session `clara.explorer.server.integration-test/run-loan-app-rules` builds, with approved-app working
  memory), including a fired working-memory layer.
- `loan-disposition-ruleset` — the single-ns
  `clara.explorer.test.rules.loan-outcome-notices` session, unfired, so it records the
  downstream consume/produce contract with no memory-derived layer.
- `_variants/loan-disposition-ruleset/ref=feature%2Fnew-tax` — the same unfired session as a
  variant unit, exercising the `_variants/` layout and the `/`→`%2F` segment encoding. Its `ref` is a
  fixed literal, not a git read: the disposition ruleset is test namespaces inside this checkout,
  with no independent repo to read a truthful ref from, and a fixed ref keeps regeneration
  byte-identical. The git-derived write path is pinned in
  `clara.explorer.artifacts.store-test`.

The example is there so a change to any generation step shows up as a reviewable diff rather than a
silent format drift. Regenerate it (from `explorer/`) with `make regen-artifacts`, which runs
`dev/clara/explorer/dev/regen_artifacts.clj`; a regeneration is byte-identical when nothing has changed, and anything
that did change is exactly what the diff should be read for. The golden test
`clara.explorer.artifacts.regen-example-test` regenerates the whole registry into a temp
dir and pins it against the checked-in copy byte-for-byte: minted names
(`resolved__N__auto__` auto-gensyms, digest-suffixed locals) are canonicalized
at emission, so a regeneration from a different process (a REPL, the test
runner) yields identical bytes.

## `merged-rulebase-analysis/` is split by what you are asking

One 12MB value answers every question at the price of the largest one. The split
is by **access pattern**, not by namespace — so a query that wants one column
across every rule opens one file instead of all of them.

| file | holds | opened by |
|---|---|---|
| `production-index.edn` | `:name` `:ns` `:lhs-types` `:insert-types` `:retract-types` + the flags | almost everything |
| `production-conditions.edn` | `:lhs` — the condition trees | per-condition work: polarity, join bindings |
| `production-details.edn` | `:rhs-form` `:doc` `:props` `:notes` `:params` | a single production you have already picked |
| `fact-types.edn` | the hierarchy — `:ancestors` and the rest | anything type-directed |
| `dep-graph.edn` | `:upstream` per production | producer→consumer edges |
| `meta.edn` | `:slim` + `:unresolved` | orientation; see below |

The three `production-*` files partition every production — rule *or* query —
three ways, each keyed by the same name. Nothing is duplicated across them and
their union is the whole record: read one for a column, all three for a complete
production. `production-index.edn` is the one that records whether a name is a
rule or a query.

Rough sizes for a single 3,400-rule ruleset: index 1.9MB, conditions 4.3MB,
details 3.0MB, dep-graph 1.7MB, fact-types 0.9MB, meta 22KB. A scan reads the
1.9MB.

`:lhs` has its own file for a reason that is easy to get wrong: it *looks* like
per-production detail, but classifying every condition of every rule is a full
corpus scan. Filed with `:rhs-form` it would cost that reader 3MB per repo it
never looks at.

Every part file is written even when empty, so the directory is the same shape on
every run and a reader never branches on which files exist.

## What is not on disk, and why

`meta.edn`'s `:slim` key records `:dropped` — the exact set of keys removed —
plus `:written-by` (the writing function, resolvable at the manifest's sha) and
the `:unknown-fact-types` the cross-reference collapse had to hoist. **Read that
before concluding something is missing.**

The pattern behind the list: **every relationship is written once, in the
direction the production states it.** So a fact type says nothing about which
rules touch it — the rules say it, and the reverse is derived. Absent for that
reason: `:used-by-rules`, `:used-by-queries`, `:inserted-by-rules`,
`:retracted-by-rules`, `:descendants`, and `:dep-graph`'s `:downstream`.

**Ask for them rather than rebuilding them.** `GET /v1/fact-types/:fq-name`
serves all four production directions ready-made, and `bb … edges <rule>` gives
you both sides of the dep-graph. Reconstructing one by hand means walking the
hierarchy, and the walk goes a different way per key — a production *matching* a
type also matches its descendants, where one *inserting* a type affects its
ancestors. [`artifacts/slim.clj`](../src/clara/explorer/artifacts/slim.clj)'s
header comment is the reasoning behind all of them.

Also gone: the Rete `:nodes` graph, `:lhs-form`, the id indexes, `:ns-deps`, and
the analysis's own `:raw-condition` / `:clara.explorer.internal/normalized`
normalization bookkeeping. All of them need a live session — serve the analysis
and `GET /v1/…`.

Nothing is dropped by omission. A top-level key `artifacts.parts` has no file for
is **refused**, not silently discarded, so the only way a key leaves these
artifacts is a deliberate entry in `slim/dropped-top-level-keys`.

## `merged-annotations.edn` is stored by reference

~99% of rules have a merged annotation identical to one layer's, so the file
names that layer instead of restating the value — 34KB where the spelled-out
value was 5.6MB. Only rules the fold genuinely combined appear under
`:annotations`. Provenance splits on the same line: a by-reference rule has no
entry, and `:provenance :verbatim` is a per-key template. **A rule appears in the
file once, or not at all.**

The consequence: **the merge is not readable without the layer files beside it.**
Both readers resolve the references for you — `store/read-merged-annotations` on
the JVM, and `annotations_report.bb` offline — so you never handle the stored
shape unless you go looking. `store/read-compact-merged-annotations` is the way
to look.

The layer files themselves are never trimmed. A server is handed those files
directly, so anything taken out of one would vanish from the API; the merge is
the one annotation artifact the server never reads, which is what lets it point
at them.

## Querying it offline: `annotations_report.bb`

```bash
S="$CLARA_RULES_EXPLORER_HOME/explorer/bin/annotations_report.bb"
D="/path/to/artifacts/<repo>"       # a variant unit is $ROOT/_variants/<repo>/<variant path>

bb "$S" "$D"                              # summary + resolution tallies
bb "$S" "$D" gaps                         # rules whose :resolution is not :full
bb "$S" "$D" types                        # every resolved insert-type + producer count
bb "$S" "$D" producers :loan/applicant    # who inserts it or a descendant   (annotations + fact-types)
bb "$S" "$D" consumers :loan/applicant    # who matches it or an ancestor    (production-index + fact-types)
bb "$S" "$D" hierarchy :loan/applicant    # that type's ancestors + descendants  (fact-types)
bb "$S" "$D" rule some.ns/some-rule       # one rule's whole annotation (+ :unit)
bb "$S" "$D" edges some.ns/some-rule      # up/downstream             (dep-graph)
bb "$S" "$D" curated                      # what the overlay changed vs the baseline
bb "$S" "$D" layers                       # the fold + per-key provenance
bb "$S" "$D" diff "$AFTER"               # production-level diff of this unit vs $AFTER
```

Five of the ten subcommands never open the analysis at all — `summary`, `gaps`,
`types`, `curated`, and `layers` read only the annotation layers. `producers`,
`consumers`, and `hierarchy` read `fact-types.edn`: `producers` closes over a
type's descendants, `consumers` over its ancestors — the two opposite closures —
and both print the split between exact matches and ones reached through the
hierarchy; `hierarchy` shows a type's ancestors and descendants directly.
`consumers` reads `production-index.edn`; `edges` reads `dep-graph.edn` and
inverts `:upstream` for the downstream side; `rule` reads `production-index.edn`
for `:unit` attribution on a composed set.

`producers`, `consumers`, and `hierarchy` resolve a fact type against the names
in `fact-types.edn` — exact first, then substring. A substring that lands on one
name prints what it resolved to; one that lands on several lists the options and
closes over all of them. Fact types may be written `:foo/bar` or `foo/bar`, and
rule names fall back to substring matching the same way.

`--file auto|agent|merged` picks which annotations the annotation-reading
subcommands use. Default is `merged`, except `gaps`, which defaults to `auto`
because the deterministic baseline is the real work list.

`diff` compares two unit-shaped directories — two ruleset units, a unit and
its variant, or two composed units — production by production: which rules and
queries were added or removed, which changed and how (`:lhs`, `:rhs-form`,
types, `:resolution`), which fact types appeared or lost ancestors, and which
dep-graph edges were gained or lost. `:unit` attribution is not a change: it
is the source unit that carried a production, not part of its identity, so it
stays in `--edn` / `--rule` output but never tags a changed production. Two
composed units instead get a `units` section comparing each manifest's
`:analysis-run :units` by repo — which source units were added, removed, or
changed (variant or sha). Any other unit records no source units, so a
composed unit diffed against one has no `units` section. It reads both units' `merged-rulebase-analysis/`
parts plus their merged annotations, and refuses units with differing
`:slim :dropped` shapes, since those do not hold the same keys. Namespaces
present on only one side are reported under `scope`, never as added or
removed; `scope` also lists the fact types only scope-only namespaces touch,
through the `:ancestors` hierarchy. `--rule NAME` (substring matching, as
`rule` does) prints one production's before and after per changed field;
`--edn` prints the diff value. `--rule` reads only the production files and
skips the full diff, so it answers even when the dep-graph, fact-types, or
shape are absent. Three things it does not answer: a renamed production shows
as one removed and one added; an `:rhs` tag says the text changed, not what it
does at runtime; names compare verbatim, so per-build stamps get no useful
diff. It also cannot trace a fact type's changed ancestors (or an edge that
appears only through such a change) back to a namespace, since the analysis
records a type's `:ancestors`, not where a `derive` was declared.

It is babashka, so it cannot `require` the namespaces that wrote the files. The
one definition it shares with the JVM — every filename, the layer fold order,
and the `merged-annotations.edn` decode — lives in namespaces marked
`:clara-rules-explorer/bb-loaded true` on their ns form, which is what makes
them safe for bb to `require`. Run the script from a checkout, not from a
detached copy of the file alone.

## Navigating offline: `editor_client.bb`

`bin/editor_client.bb` is the babashka twin of the editor navigation query
(`clara.explorer.server.client/navigate`): EDN-in, EDN-out over a registry
selection, no JVM, no session, no Jetty.

```bash
S="$CLARA_RULES_EXPLORER_HOME/explorer/bin/editor_client.bb"
bb "$S" '{:root "…" :units [{:repo "…"} {:repo "…"}]}' \
        '{:production nil :side :lhs :token ":loan/applicant"}'
```

It composes the selected units on the fly with the same shared composition and
rehydration logic the JVM `:registry` mode runs, so the bb answer and the nREPL
answer over the same selection agree. The editor resolves aliased/`::` tokens
to fq over its repl first; source locations are always `:var? false` (bb loads
no rule namespaces).

### The `shared.` convention

Logic both the JVM and bb `require` is marked
`:clara-rules-explorer/bb-loaded true` on its ns form, and `make bb-smoke-test`
requires every marked namespace under bb — so a namespace that pulls in
anything bb cannot load fails the check rather than shipping.

## Writing your own reader

Open the one part your question lives in. On the JVM:

```clojure
(require '[clara.explorer.artifacts.store :as store])

(store/read-analysis-part :index {:dir "…"})       ; 1.9MB, not 12MB
(store/read-analysis-part :dep-graph {:dir "…"})
(store/read-merged-annotations {:dir "…"})         ; references resolved
```

From babashka or anything else, `clojure.edn/read-string` over the one file is
enough — the artifacts are plain EDN with no tagged literals, and
`clara.explorer.artifacts.layout` gives you the filenames.

Two things not to do:

- **Do not `cat` or `slurp` the whole directory.** That is the cost the split
  exists to avoid, and `production-conditions.edn` alone will not fit in a
  context window.
- **Do not edit anything but `agent-annotations.edn`.** Every other file is
  rewritten on the next `persist!`. The overlay is the one artifact no machine
  step overwrites.

## Teaching the generation pass about your facts

`flow/generate` knows nothing about how a host builds facts. Five hooks are where
that knowledge goes, and each is a function the caller hands over rather than a
mode the library implements:

| hook | for |
|---|---|
| `:fact-constructors` | a runtime constructor (`(->fact :some/type m)`) instead of a record type. A `:match-fn` may be a set of fully-qualified symbols |
| `:callsite-resolver-fn` | a boundary-arg idiom no declared constructor covers |
| `:fact-type-spec-fn` | the var-as-fact pattern — a fact that *is* a function var |
| `:ns-var-defs-fn` | rule namespaces with no source on the classpath |
| `:post-process-fns` | knowledge only visible at runtime: var metadata, a registry the host populates at load |

See [`rule-annotations.md`](rule-annotations.md) for what the analysis does with
what they return.

## Provenance: `rules-inspect-manifest.edn`

State-of-the-world only — git shas, branch and working-tree state of the inputs
that drift, the analyzed namespace list, the Clojure version, the layer ids, and
an append-only `:history`. No derived analysis: what resolved and how is recorded
per callsite in the layers, not counted here.

`artifacts/manifest.clj` knows the artifact set, the run's own checkout and the
runtime. Anything else — the git state of a host's tooling, how the session was
built, what scope it covers — arrives through two seams: `:blocks`, merged into
the manifest whole, and `:analysis-run`, merged into that block.

### `:source :branch` names the remote branch — or nothing

`:source :branch` is the branch the analyzed commit belongs to: the checkout's
own branch when attached, the remote branch pointing at the commit when the
checkout is detached (a `git worktree add --detach` worktree, a CI checkout, a
bisect). Detached checkouts resolve through the checkout's `origin` — its
default branch first, then the first sorted remote-tracking ref pointing at the
commit — with the `origin/` alias stripped, so a detached and an attached
checkout of the same commit record equal `:source` maps. When no remote branch
points at the commit, `:branch` is nil: `HEAD` is not a branch, and a local
branch that happens to point at a detached commit says nothing about what the
commit was synced against. Whether the checkout was detached is a fact about
one machine and is not recorded. The manifest's top-level `:variant`, when
present, is the unit's variant — not git's branch, which stays under
`:source`.

### Checking a unit: `status`

`bb annotations_report.bb <dir> status` answers "is this unit current?" from
the manifest (and, for a composed unit, its sources' manifests) — no JVM, no
analysis parts. A source unit compares against `--checkout PATH` (`--ref`,
default `HEAD`): a remote mismatch, a sha drift, a dirty generation tree, or an
`:updated` older than the policy's `:max-age-days` makes it stale. Without
`--checkout` only the checkout-independent checks run and the report says the
sha was not compared. A composed unit compares each recorded per-source sha
against that source's manifest under `--root` (default: `<dir>` with the
manifest's `:repo` and `_variants/<repo>/<variant…>` stripped off) and is current only if
every source is; `--checkout` is rejected for it, since it has one checkout per
source. An aggregate with no per-source shas is reported by kind and source
provenance, with no verdict. `--edn` prints the result map the text report
renders. The comparison itself is
`clara.explorer.artifacts.status/unit-status`, so a host can call it and
append its own `:reasons` (tool versions, configuration slices kept in
`:blocks`) before rendering.

## The artifact registry

The architecture of the registry — the merge modes, the shared selection pass,
and why there are three modes — is
[`registry-architecture.md`](registry-architecture.md); this chapter is the
on-disk view of it.

Everything above addresses **one** artifact set at a time. Hosts accumulate
many — one per source repo of a rulebase composed from several, one per variant
under review, one per captured session — and the questions worth asking
span them: *who consumes the type this set produces*, *what does this variant do
to the others*, *what does this set of sets look like as one rulebase*.

Five namespaces answer that, all under
`clara.explorer.artifacts.*`:

- **`registry`** — discovers and reads N units under a root, as a value. A
  directory is a **unit** iff it holds `rules-inspect-manifest.edn`, the one
  artifact every complete set has; its `:repo` is its path relative to the
  root. The one reserved root-level directory is `_variants/`: a path under it
  splits into a repo (segments before the first `<axis>=…` segment) and a
  variant (that segment and everything after), named by the decoded
  `[axis value]` vector ending in `[:ref …]`. Discovery also compares each
  variant unit's decoded path with its manifest's `:variant` and reports a
  directory where they differ (a hand rename), via
  `registry/get-variant-mismatches`.
  `registry/compatibility-report` compares each unit's `:slim :dropped` key set
  and names the units that do not share a shape — the question a merge answers
  first. `unit-info` records, for an aggregate unit, the manifest's
  `:analysis-run :mode` (as `:mode`) and `:analysis-run :units` (as
  `:composed-from`); absence of `:mode` marks a source unit.
  `registry/aggregate-unit?` and `registry/source-units` turn that marker into
  the selection a federation usually wants (the source units, no compositions
  or captured whole-rulebase units), beside `units-with-analysis`.
- **`rehydrate`** — the inverse of `slim`. Rebuilds the reverse directions a
  persisted analysis drops because they are recomputable: fact-type
  `:used-by-*` / `:inserted-by-rules` / `:retracted-by-rules` (with the two
  hierarchy closures running in opposite directions), `:descendants`, the id
  indexes, and `:dep-graph :downstream`. `:nodes`, `:lhs-form`, the condition
  bookkeeping, and `:ns-deps` stay absent.
- **`compose`** — merges a caller-named selection in two modes. `fold-layers`
  folds every unit's layer stack into one `MergedAnnotations`, qualifying each
  layer id as `<repo>[@<variant>]/<layer-id>`; `->composed-analysis` asserts the
  units are components of ONE rulebase and returns one slim `RulebaseAnalysis`,
  rules/queries merged by fq name (a name in two units is refused), fact types
  merged per name with ancestors unioned, the dep-graph recomputed over the
  merged set so cross-unit edges exist, and each production tagged `:unit`.
  Both fold paths narrow each unit's layers to the unit's `:namespaces` filter
  before folding (via `registry/narrow-annotations`), so a scoped merge serves
  and persists only the scope's annotations; `->standard-role-layers` records
  the per-unit filter under the layer's `:source :namespaces`.
- **`federate`** — the union mode. `->index` builds a queryable value over
  units that share a fact-type vocabulary but are NOT claimed to compose: the
  globally re-closed hierarchy (with `:conflicts` where units disagree),
  per-type producers/consumers with polarity, cross-unit `:unit-edges`, entry
  points, and orphans. A `UnitRef` may carry a `:namespaces` filter that
  narrows the unit's scope; requested namespaces no selected unit covers are
  reported under `:coverage :unknown-namespaces`. `impact-of`,
  `producers-of`, `dependents-of`, `paths-between`, and
  `unit-dependency-graph` answer over it; `->digest` + `persist!` write
  `registry-index.edn` / `registry-digest.edn` to an explicit `:dir`, and
  `read-index` / `read-digest` read them back. `->index` and `persist!` accept
  a caller `:label` recorded into the index's `:scope`, so a persisted index
  names its question without depending on its directory. `diff` compares two
  indexes over overlapping unit sets — the variant-vs-mainline question —
  reporting selection, edge, fact-type, entry-point, orphan, and hierarchy
  differences. `grade` checks the union against a composed reference (a
  captured session or monolithic run). `->index` refuses a selection that
  mixes aggregate and
  source units (an aggregate describes the same productions as the units it
  overlaps, so both would silently double-count), and always refuses an
  aggregate whose `:composed-from` names another selected unit.
- **`selection`** — the shared preamble both merge modes consume: read each
  unit's analysis (refusing shape skew and absent analysis), narrow it to its
  `:namespaces` filter, and union the hierarchy plus coverage in one pass
  (`selection/->selection`).

The library discovers and merges; it never decides *which* sets belong together
or *what a set means* — every entry point takes the selection explicitly. The
join key is the fact type name, and shape skew is refused, not bridged.

The server serves a composed selection directly: `server/start!` accepts
`{:registry {:root … :units […]}}`, and the analysis routes answer
from the composed, rehydrated analysis with no live session (session routes
return 409 `:no-session`). See
[`../../docs/explorer-graph-api.md`](../../docs/explorer-graph-api.md).

### Composing into a unit

`flow/compose-persist!` materializes a composed selection as a normal
single-unit artifact directory, so the offline report reads it without knowing
it is a composition:

```clojure
(require '[clara.explorer.artifacts.flow :as flow])

(flow/compose-persist!
  {:root "rules-annos"
   :repo "composed/loan-app-plus-disposition"
   :units [{:repo "loan-app-ruleset"}
           {:repo "loan-disposition-ruleset"}]
   :generated-by "me"})
```

It writes the three standard layers, compact `merged-annotations.edn`,
`merged-rulebase-analysis/`, `rulebase-analysis-digest.edn`, and a manifest —
the same shape `bin/annotations_report.bb` already reads. Per-unit provenance
lives in the manifest's `:analysis-run` block rather than in the flattened
layer files; the manifest's `:analysis-run :mode :compose` and `:units` are
exactly the aggregate marker `registry/unit-info` reads back, so a composed
unit is not mistaken for a source unit when discovered again. Each `:units`
entry also carries its source's `:sha` and `:created` (and `:variant` when
present), and `:staleness` names the `review-when-any-source-sha-drifts` policy
with those per-source shas — so a reader holding only the directory can answer
"is this current?" for a composition that is stale as soon as any of its N
independently-moving sources has moved.

## Related

- [`rule-annotations.md`](rule-annotations.md) — the layer model, the fold, and
  provenance, in memory
- [`../../docs/explorer-graph-api.md`](../../docs/explorer-graph-api.md) — the
  `/v1` routes that answer what the files do not
- `artifacts/slim.clj`, `artifacts/parts.clj`, `artifacts/compact.clj` — the
  reasoning behind what is dropped, how it is split, and how the merge is encoded
