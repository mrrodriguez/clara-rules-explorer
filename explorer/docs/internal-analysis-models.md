# Clara Rules Explorer — Internal Models

This page orients you to the **internal** Clojure structures the explorer builds
on. It is deliberately thin — the authoritative descriptions live with the code
and in the sibling docs:

- [`../../docs/explorer-graph-api.md`](../../docs/explorer-graph-api.md) — the external API contract.
- [`analyze-pipeline-concepts.md`](analyze-pipeline-concepts.md) — how annotations are derived from source via clj-kondo.
- [`rule-annotations.md`](rule-annotations.md) — the annotation layer model and fold.
- [`persisted-artifacts.md`](persisted-artifacts.md) and [`registry-architecture.md`](registry-architecture.md) — the on-disk artifact model.

## Productions

The rulebase stores rules and queries as **Production** maps, exactly as the
clara-rules compiler produces them. The explorer reads the compiled rulebase —
it never re-parses DSL forms — via `clara.explorer.utils/get-rulebase`
(`(-> session-or-rulebase eng/components :rulebase)`). The field set
(`:name`, `:ns-name`, `:lhs`, `:rhs`, `:props`, …) is defined by
`clara.rules.schema`, not by this project.

LHS conditions are normalized into `{:condition-type … :children …}` / leaf
maps by `clara.explorer.conditions/normalize-lhs`; fact types on the LHS are
extracted by `clara.explorer.conditions/extract-lhs-fact-types`.

## Where each concern lives

| Concern | Namespace / entry point |
| --- | --- |
| Static rulebase analysis, dep graph, type-hierarchy indexes, summaries | `clara.explorer.core` (`->rulebase-analysis`, `->dep-graph`) |
| LHS normalization, condition walking, binding summaries | `clara.explorer.conditions` |
| Kind-explicit type serialization, route ids, dep `:match` | `clara.explorer.serialize` (`resolve-type`, `route-id`) |
| Working-memory analysis | `clara.explorer.memory` |
| Annotation layers, fold, derivation | `clara.explorer.annotations` (`merge-layers`) |
| Source analysis (kondo) → generated annotations | `clara.explorer.analyze` |
| Persisted artifacts + registry | `clara.explorer.artifacts.*` |

## Dependency graph

Edges are **type-based**, not Rete-node-based. `clara.explorer.core/->dep-graph`
links rule A → rule B when a type A inserts/retracts is compatible with a type
B's LHS matches — an inserted type satisfies its ancestors, a matched type is
reached by its descendants. The insert/retract types come from the folded
annotation layers (rule `:props` + generated + curated — see
[`rule-annotations.md`](rule-annotations.md)), not from `:props` alone.

The Rete node graph (`:id-to-node`) is still what `clara.explorer.memory` reads
for working-memory analysis, but it is not the basis of the static dependency
graph.

## Example

`clara.explorer.test.rules.loan-app-rules` is the demo ruleset; the tests under
`explorer/test/clara/explorer/` are the worked examples.
