# Composition-first cross-unit queries

Status: **proposal**. Scope: `clara.explorer.fact-types` (analysis), `clara.explorer.artifacts.flow`
and `shared.selection` / `shared.compose` (composition), `explorer/bin/annotations_report.bb` and the
bb-loadable artifact namespaces it requires, `clara.explorer.artifacts.diff`, the removal of
`clara.explorer.artifacts.federate`, and the docs that describe them. Supersedes Part A (`digest`)
of [`annos-report-enhancements-plan.md`](annos-report-enhancements-plan.md); its Part B
(`production`) stands.

Federation's distinguishing claim is that the selected units are *not* one rulebase. No caller makes
that claim. A host asking a cross-unit question selects the components of one rulebase it actually
runs, which is composition's claim. What callers read from federated output falls into three
groups:

| Federated output | Where it belongs |
|---|---|
| `:coverage :units`, `:provenance` | already in the composed unit's manifest (`:analysis-run :units`) |
| `:coverage :unknown-namespaces` | computed by `shared.selection/->selection` during composition, then discarded (Part B) |
| `:unit-edges`, `:entry-points` | derivable from the composed analysis, whose productions carry `:unit` (Part C) |
| `:hierarchy-conflicts` | not a defect: `derive` is additive, so units that saw different subsets of one hierarchy do not disagree, and composition's union is the answer |
| `:orphans`, `paths-between`, `grade`, the index-vs-index `diff` | no caller beyond the digest; `diff` and `grade` are covered by the production-level unit diff (Part D) |

Diffing a real composition against a captured session of the same rulebase also showed that the
union is *incomplete*: a unit drops `derive`s on types its own productions never mention (Part A).
Federation's `:hierarchy-conflicts` could not see this, because no unit records the missing edge.

## Replacement for each federated query

| Federated | Replacement |
|---|---|
| `producers-of T` | `producers T` on the composed unit |
| `impact-of T` | `consumers T` on the composed unit (exact or via an ancestor: the rules that receive a T fact) |
| `dependents-of U` | `unit-edges U` |
| `unit-dependency-graph` | `unit-edges` |
| `coverage-report` | `units` |
| entry points | `entry-points [U]` |
| `diff` | `diff <after-dir>`, with its new `entry-points` section |
| `grade` | `diff` of the composition against the captured session: `scope` holds namespaces only one side has, `edges` and `fact-types` hold what the composition misses, `entry-points` holds types it thinks nothing produces |

## 1. Part A: record every declared hierarchy edge at analysis

### 1.1 Problem

`fact-types/->ancestors-index` creates an entry for every type a production consumes or produces,
plus each of their ancestors. A `derive` whose child no production in the unit mentions is never
recorded. Composition unions per-unit ancestor sets and re-closes them, but it cannot union an edge
that no unit wrote down:

```clojure
;; unit A
(derive :x/b :x/c)                      ; no rule in A mentions :x/b
(defrule a-rule [:x/c] => …)

;; unit B
(derive :x/a :x/b)
(defrule b-rule … => (insert! (->fact :x/a)))
```

In the running rulebase `:x/a` isa `:x/b` isa `:x/c`, so `b-rule` feeds `a-rule`. In the composition,
B records `:x/a → [:x/b]`, A records no entry for `:x/b`, and the union has no path to `:x/c`. The
edge `b-rule → a-rule` is missing, and `:x/c` reads as an entry point.

### 1.2 Change

- The ancestors fn (`core/extract-ancestors-fn`) answers one type at a time and can't list edges.
  Add a source of declared tags: by default the tags with parents in the global hierarchy
  (`(keys (:parents @#'clojure.core/global-hierarchy))`), which is what `clojure.core/ancestors`
  and Clara's default ancestors fn read. An analysis option overrides it for a session built over
  a custom hierarchy.
- `->ancestors-index` registers each declared tag the same way `register-hierarchy-ancestors`
  registers ancestor-only types today: `:name`, `:ns`, `:ancestors`. No new artifact and no new key.
- The `known` set (`get-known-type-names`) keeps its meaning: types a production uses. Declared-only
  tags are not `known`.
- Existing units hold the incomplete index until they are regenerated.

### 1.3 Tests

- The two-unit fixture above: each unit's `fact-types` has an entry for the declared tag, and the
  composed `:x/a` has ancestors `[:x/b :x/c]`, with the edge `b-rule → a-rule` in the dep-graph.
- A declared tag no production uses is not in the `known` set.
- The override option replaces the global-hierarchy default.

## 2. Part B: the composed manifest records coverage

### 2.1 Change

- `flow/compose-persist!` writes `:coverage {:unknown-namespaces [ns …]}` into the manifest (as a
  `:blocks` entry, like `:staleness`). These are namespaces a unit's `:namespaces` filter names that
  the unit's analysis doesn't cover. The value comes from the same `->selection` call the
  composition uses, so the units are read once: `compose-persist!` passes the selection to the
  composition rather than letting `->composed-analysis` build its own.
- `:coverage` is an open map. A host adds its own gaps, e.g. the components its topology names that
  no unit supplies, through `:manifest-fn`. Readers print unknown keys unchanged.
- The manifest schema admits `:coverage`.

### 2.2 Tests (`compose_persist_test`)

- A selection whose filter names a namespace no unit covers records it under `:coverage
  :unknown-namespaces`. A fully covered selection records an empty vector.
- A `:manifest-fn` that adds a key under `:coverage` keeps it.

## 3. Part C: report subcommands over a composed unit

All three read only `production-index.edn`, `fact-types.edn`, and (for `units`) the manifest. They
compute at query time and persist nothing.

**Unit arguments.** A `<unit>` is a full unit key (`repo@variant`), or a `:repo` that exactly one
source unit has. Anything else fails and lists the unit keys. A bare repo name never silently
matches nothing.

### 3.1 `units` (composed units only)

One line per source unit: its key, sha, and production count (from `:unit` on the productions).
Then `coverage`, one line per key of the manifest's `:coverage`, host keys included. If every value
is empty, it prints that every namespace in scope is covered. A non-composed unit fails, saying it
is not a composition. `--edn` prints `{:units [...] :coverage {...}}`.

### 3.2 `unit-edges [<unit>]` (composed units only)

`{[producer consumer] {:via #{ft} :rules n}}`. There is an edge when a rule in `producer` is
upstream (dep-graph) of a production in `consumer`. `:via` is the upstream rules' inserted and
retracted types, each with its ancestors, intersected with the downstream productions'
`:lhs-types`. `:rules` counts the distinct upstream rules. Edges within one unit are not listed.
With `<unit>`, only that unit's edges, in two groups: `feeds` and `fed by`. Text prints
`producer -> consumer  (n rules)  via t1, t2, …`. `--edn` prints the map.

### 3.3 `entry-points [<unit>]` (any unit)

Types some production matches (`:lhs-types`, rules and queries) that no rule in the unit produces.
A type counts as produced when a rule inserts it or one of its descendants. Retracting a type does
not produce it. For a ruleset unit these are the unit's inputs from outside. For a composed unit
they are grouped by consuming unit; with `<unit>`, only that unit's. Each type lists how many
productions consume it, so `consumers T` is the next step.

Put the computation in a bb-loadable namespace that `diff` also requires (Part D), so the report and
the diff share one definition.

### 3.4 Remove `digest`

Remove the `digest` subcommand, `digest-file`, `digest-report`, the element-count helper, and the
`:`-stripping argument preprocessing that only `digest` needed. Remove their tests and the help row.

### 3.5 Tests (`bb_report_test`)

- `units` on a composed fixture lists each source unit and its coverage. A host key under
  `:coverage` prints. A ruleset unit fails.
- `unit-edges` on a two-unit composition: one edge, the right `:via`, `:rules`. `unit-edges <repo>`
  resolves a bare repo, and an ambiguous or unknown name fails listing the keys.
- `entry-points`: a type consumed and inserted only as a descendant is not an entry point. A type
  only retracted is. In a composed fixture, grouping by consuming unit is correct.

## 4. Part D: diff

- **`entry-points` section.** `{:added #{ft} :resolved #{ft}}`: types that are entry points only
  after, or only before. Both sides come from the Part C definition. A type whose consumers are all
  in scope-only namespaces goes under `:scope :entry-points` instead, so a coordinate difference
  never reads as a code change. This answers "did this change leave a consumed type with no
  producer", and, diffed against a captured session, "which types does the composition think nothing
  produces that the real rulebase does".
- **`--rule NAME` becomes `--production NAME`**, matching the `production` subcommand. The old flag
  is removed, not aliased. Update the help row and the dispatch docstrings.

Tests (`diff_test`, `bb_report_test`): removing the only producer of a consumed type gives `:added`;
the reverse gives `:resolved`; a scope-only consumer goes to `:scope`; `--production` prints one
production's before and after; `--rule` is rejected as unknown.

## 5. Part E: remove federation

- Delete `artifacts/federate.clj` and `test/clara/explorer/artifacts/federate_test.clj`.
- `shared.selection`: remove `:hierarchy-conflicts` and `->ancestor-conflicts`, and update
  `->selection`'s docstring and `artifacts.selection`'s ns docstring.
- `layout`: remove `:registry-index` and `:registry-digest` and the docstring paragraph about them.
  Remove the matching `schema` entries and the `:coverage` docstring that names `federate`.
- Docstrings that name `federate`: `shared.registry` (unit key), `registry/source-units` (keep the
  fn, since it is a valid selection for `fold-layers` too; reword the reason), and `diff`'s ns and
  `diff` fn docstrings.
- Delete `annos-report-enhancements-plan.md` and `annos-report-enhancements-progress.md` once this
  plan lands.

## 6. Docs

- `explorer/docs/registry-architecture.md`:
  - Two merge modes, fold layers and compose. The "typical sequence" sentence goes.
  - The hierarchy section says composition unions and re-closes. It records no conflicts, and Part A
    is why the union is complete.
  - Remove federation from the outputs section and the namespace map.
- `explorer/docs/persisted-artifacts.md`:
  - Rows for `units`, `unit-edges`, `entry-points`, and which parts each reads.
  - Remove `digest` and the federation output directory.
  - The composed manifest's `:coverage`.
  - `fact-types.edn` includes hierarchy-declared tags.
  - `diff --production`.

## 7. Order

Part A is independent, and units need regenerating to benefit. Part B comes before Part C (`units`
reads `:coverage`). Part C's shared entry-points definition comes before Part D. Part E comes last,
once a host reads `units` / `unit-edges` / `entry-points` instead of the digest.

## 8. Verification

- `make test`, `make lint`, `make format-check`, `make reflection-check`, `make bb-smoke-test`.
- Smoke over a real composition: `units`, `unit-edges`, `unit-edges <repo>`, `entry-points`.
- Diff a real composition against a captured session of the same rulebase, before and after Part A
  plus regeneration. The `fact-types` ancestor changes that came from undeclared tags disappear,
  and `entry-points` lists the remaining types the session produces.
