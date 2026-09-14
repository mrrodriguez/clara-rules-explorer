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

The code is `clara.server.tools.graph.artifacts.*`.

## The artifact set

Everything is EDN and everything is keyed by `:name` — a fully-qualified
production name (`some.ns/some-rule`) or a fact type (`:loan/applicant`). There
are no ids on disk; the name is the handle.

```
<root>/<repo>/                    ... or any explicit :dir
  auto-gen-annotations.edn        layer — what static analysis found
  memory-annotations.edn          layer — what a fired session proved
  agent-annotations.edn           layer — what a curator settled. The one precious file
  merged-annotations.edn          the fold of those three + rule :props
  merged-rulebase-analysis/       the analysis over that merge — a DIRECTORY
  rulebase-analysis-digest.edn    ~1–35KB of counts and work lists
  rules-inspect-manifest.edn      provenance: shas, namespaces, history
```

`:branch` nests the whole set one level down, under `<base>/branches/<label>/`,
so a run over work in progress never clobbers the mainline state of the world.
The label is the caller's, not git's.

**Start with `rulebase-analysis-digest.edn`.** It is the only artifact meant to
be read whole: counts, per-namespace rule and query totals, the
source/sink/unlinked tallies, the unlinked rule names, and the `:unresolved` work
list. Its `:more` key tells you where to go next.

## Writing a set

```clojure
(require '[clara.server.tools.graph.artifacts.flow :as flow])

(def opts {:root "/path/to/artifacts"
           :repo "my-ruleset"
           :generated-by "my-tool"
           :session session})

(-> (flow/generate {:session session :generated-by "my-tool"})
    (flow/persist! opts))
```

Two options have no default, on purpose.

**`:root`** — this library reads no environment variable. A host that keeps its
artifacts under some `$…_HOME` resolves that itself and passes the result, so the
error its users see names their own variable rather than a key in this schema.
An explicit `:dir` replaces `<root>/<repo>` entirely, which is what a run with no
repo to name (a session restored from a serialized artifact, say) uses.

**`:generated-by`** — a provenance claim, written into every layer and into the
manifest, that outlives the process. A library has no standing to guess it.

### The checked-in example

`clara.server.tools.graph.artifacts.regen-example/example-out-dir` (relative to `server/`) is a
committed example of the whole set, generated from the
loan-doc-rules + loan-app-rules + loan-doc-queries session (the same session
`clara.server.graph.integration-test/run-loan-app-rules` builds, with approved-app working memory).
The `rules-annos/` parent is the registry root and the `loan-app-ruleset` subdir one named ruleset
under it — the layout a rules registry keeps for holding more than one ruleset later.
It is there so a change to any generation step shows up as a reviewable diff rather than a silent
format drift. Regenerate it (from `server/`) with `make regen-artifacts`, which runs
`dev/regen_artifacts.clj`; a regeneration is byte-identical when nothing has changed, and anything
that did change is exactly what the diff should be read for. The golden test
`clara.server.tools.graph.artifacts.regen-example-test` regenerates the set into a temp dir and
pins it against the checked-in copy.

One byte-level caveat: the `def-fact-fn` macro emits an auto-gensym
(`resolved__N__auto__`) for `extract-doc-meta-rule`'s var-as-fact local, and `N` depends on the
JVM's gensym counter — so a regeneration from a different process (a REPL, the test runner) shows
churn in `auto-gen-annotations.edn`, `merged-annotations.edn`, and
`production-details.edn` that is process noise, not a generation change. The golden test normalizes
exactly those two byte sequences (the gensym number and the `:callsite-id` hash derived from it),
matching how `clara.server.tools.graph.analyze-test` already asserts the gensym's *shape*, never its
value.

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

`meta.edn`'s `:slim` key is self-describing: `:dropped` is the exact set of keys
removed and `:recover` maps each one to a sentence naming who answers it instead.
**Read that before concluding something is missing.**

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
ancestors. If you have to, `:slim :recover` in `meta.edn` names the
reconstruction for each, and
[`artifacts/slim.clj`](../src/clara/server/tools/graph/artifacts/slim.clj)'s
header comment is the reasoning behind all of them.

Also gone: the Rete `:nodes` graph, `:lhs-form`, the id indexes, `:ns-deps`, and
the analysis's own `:raw-condition` / `::normalized` normalization bookkeeping.
All of them need a live session — serve the analysis and `GET /v1/…`.

Nothing is dropped by omission. A top-level key `artifacts.parts` has no file for
is **refused**, not silently discarded, so the only way a key leaves these
artifacts is a deliberate entry in `slim/dropped-top-level-keys` with a
`:recover` sentence beside it.

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
S="$CLARA_RULES_EXPLORER_HOME/server/bin/annotations_report.bb"
D="/path/to/artifacts/<repo>"       # a branch run is $D/branches/<label>

bb "$S" "$D"                              # summary + resolution tallies
bb "$S" "$D" gaps                         # rules whose :resolution is not :full
bb "$S" "$D" types                        # every resolved insert-type + producer count
bb "$S" "$D" producers :loan/applicant   # who inserts it            (annotations)
bb "$S" "$D" consumers :loan/applicant   # who matches it            (production-index)
bb "$S" "$D" rule some.ns/some-rule       # one rule's whole annotation
bb "$S" "$D" edges some.ns/some-rule      # up/downstream             (dep-graph)
bb "$S" "$D" curated                      # what the overlay changed vs the baseline
bb "$S" "$D" layers                       # the fold + per-key provenance
```

Two of the nine subcommands touch the analysis at all — `consumers` reads
`production-index.edn`, `edges` reads `dep-graph.edn` and inverts `:upstream` for
the downstream side. The rest read the annotation layers. Fact types may be
written `:foo/bar` or `foo/bar`, and both types and rule names fall back to
substring matching.

`--file auto|agent|merged` picks which annotations the annotation-reading
subcommands use. Default is `merged`, except `gaps`, which defaults to `auto`
because the deterministic baseline is the real work list.

It is babashka, so it cannot `require` the namespaces that wrote the files. It
`load-file`s `bin/layout.cljc` — a symlink to
`src/clara/server/tools/graph/artifacts/layout.cljc`, the one definition of every
filename, the layer fold order, and the `merged-annotations.edn` decode. **Do not
replace that symlink with a copy**: a copy still parses long after it stops
agreeing with what wrote the files. Run the script from a checkout, not from a
detached copy of the file alone.

## Writing your own reader

Open the one part your question lives in. On the JVM:

```clojure
(require '[clara.server.tools.graph.artifacts.store :as store])

(store/read-analysis-part :index {:dir "…"})       ; 1.9MB, not 12MB
(store/read-analysis-part :dep-graph {:dir "…"})
(store/read-merged-annotations {:dir "…"})         ; references resolved
```

From babashka or anything else, `clojure.edn/read-string` over the one file is
enough — the artifacts are plain EDN with no tagged literals, and
`artifacts/layout.cljc` gives you the filenames without any of the dependencies
around it.

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

## Related

- [`rule-annotations.md`](rule-annotations.md) — the layer model, the fold, and
  provenance, in memory
- [`../../docs/explorer-graph-api.md`](../../docs/explorer-graph-api.md) — the
  `/v1` routes that answer what the files do not
- `artifacts/slim.clj`, `artifacts/parts.clj`, `artifacts/compact.clj` — the
  reasoning behind what is dropped, how it is split, and how the merge is encoded
