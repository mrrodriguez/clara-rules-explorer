# Artifact registry architecture

The registry is the plural side of persistence. Where
[`persisted-artifacts.md`](persisted-artifacts.md) documents **one** artifact
set — a *unit* — and its files, this doc is the architecture of combining
**many** units: how a caller-named selection becomes one queryable value, and
why the library offers two merge modes rather than one.

Read `persisted-artifacts.md` first if you do not already know what a unit, a
layer, a slim analysis, or the manifest are; this doc assumes that vocabulary.
The questions the registry exists to answer span units: *who consumes the type
this set produces*, *what does this variant do to the others*, and *what does
this set of sets look like as one rulebase*.

## The one join key, and the three relations over it

Every unit is addressed by `:repo` and optionally `:variant`; the **join key
between units is the fact type name**. Two units using one name for two
different things produce a wrong join, and nothing here can detect that. It is
the assumption the whole registry states and does not verify.

Everything the registry answers is a projection over three relations derivable
from any production set, closed through the fact-type hierarchy:

| relation | from | closure direction |
| --- | --- | --- |
| **produces** | a rule's `:insert-types` | ancestors — inserting `T` inserts `T` and every ancestor of `T` |
| **consumes** | a rule's `:lhs-types` | descendants — matching `T` is reached by every descendant of `T` |
| **retracts** | a rule's `:retract-types` | coupling only — a retract changes the fact set but does not supply it |

The composed dep-graph, unit edges, entry points, and `diff` are all joins or
set-differences over these three relations. Retraction is deliberately not
*production*: unit edges record it as coupling but never as supply.

## The hierarchy is per-unit, and repair is step one

`:ancestors` is a transitive closure computed by the ancestors fn on the
classpath each unit's analysis had. A `derive` that lives in a component one
unit did not load is absent from that unit, so two units can hold different
ancestor sets for one type name and both be locally correct.

Composition repairs this in `clara.explorer.artifacts.hierarchy`: union every
unit's ancestor edge set (`union-ancestors`), re-close transitively
(`closed-ancestors`), transpose (`->descendants`), and order deepest-first
(`hierarchy-order`). It records no conflicts — `derive` is additive, so units
that saw different subsets of one hierarchy do not disagree, and the union is
the answer. The union is complete because analysis records every declared
hierarchy edge (see `fact-types` Part A in `core/->rulebase-analysis`), not
just the edges a unit's productions happened to touch.

The two closure directions are the one thing that is easy to get wrong, because
an unclosed inverse is a wrong answer that nothing flags: inverting
`:lhs-types` alone matches 2033 of the 3687 fact types. They are therefore
named, in one place:

- `clara.explorer.artifacts.hierarchy/ancestor-closure` — the set a
  *holder* of `base-types` satisfies.
- `clara.explorer.artifacts.hierarchy/descendant-closure` — the set a
  *matcher* of `base-types` is reached by.

## Two merge modes, two invariants

The modes are not one function with a flag. Each asserts a different
relationship between the selected units, and collapsing them would lose exactly
the distinction the registry exists to draw.

| mode | entry point | invariant |
| --- | --- | --- |
| **fold layers** | `compose/fold-layers` | union semantics; no collision concept. Layer ids are qualified `<repo>[@<variant>]/<layer-id>` so `:provenance` names whose layer claimed what. |
| **compose** | `compose/->composed-analysis` | the caller asserts the units are components of ONE rulebase. Productions merge by fq name, a name claimed by two units is refused, and the dep-graph is recomputed so cross-unit edges exist. |

`flow/compose-persist!` and the server's `:registry` mode are both the compose
mode wearing a different face: the first materializes it as a normal
single-unit directory for the offline babashka report, the second serves it
rehydrated over HTTP. Neither is a third mode.

## The pass structure

The compose mode's preamble lives in
`clara.explorer.artifacts.selection/->selection`:

```
selection (caller-named, ordered)
  → registry/assert-compatible!       refuse shape skew / missing analysis
  → read each unit's slim analysis    (throw a named error if absent)
  → narrow each to its :namespaces    (per-unit filter)
  → union + re-close the hierarchy    (hierarchy ns)
  → coverage                          (scoped namespaces + unknown-namespaces)
```

`->selection` returns `{:analyses :ancestors :descendants :coverage}`.
Compose takes `:analyses` + `:ancestors` and merges productions; the same value
also supplies the composed manifest's `:coverage`, so the units are read once.

Compose's dep-graph is the producer→consumer join at production granularity,
over the same closed `:ancestors`.

## The output value

- **`compose/->composed-analysis`** returns one slim `RulebaseAnalysis`, each
  production tagged `:unit` naming its source unit — the only key composition
  adds. Rehydrate it (`rehydrate/rehydrate-analysis`) to rebuild the inverses
  over the *whole* composition, the one thing a per-unit artifact cannot
  contain. `flow/compose-persist!` writes it to disk as a normal unit (see
  `persisted-artifacts.md`, "Composing into a unit"); the annotation side of
  that directory comes from `compose/->standard-role-layers`, which flattens
  each unit's layers to the three standard roles.

  Cross-unit questions over that directory are answered by the bb report's
  `units`, `unit-edges`, and `entry-points` subcommands, and by `diff`.

## Namespace map

| namespace | responsibility |
| --- | --- |
| `registry` | discover/read N units as a value; `narrow-annotations`; `compatibility-report` / `assert-compatible!`; `source-units` / `aggregate-unit?` (pure helpers `unit-key` / `narrow-analysis` live in `clara.explorer.artifacts.shared.registry`) |
| `selection` | the shared merge preamble: read + narrow + assert-compatible + unioned hierarchy + coverage |
| `hierarchy` | union / re-close / transpose / order of ancestor maps; the two named closures |
| `rehydrate` | slim's inverse: rebuild the recomputable reverse directions |
| `compose` | `fold-layers`, `->standard-role-layers`, `->composed-analysis` (`union-fact-types` lives in `clara.explorer.artifacts.shared.compose`) |
| `cross-unit` | `entry-points` and `unit-edges` over a composed analysis, shared by the report and `diff` |
| `flow` | `generate` → `persist!` (single unit) and `compose-persist!` (composed unit) |
| `slim` / `parts` / `compact` / `digest` / `manifest` | one unit's on-disk shape — see `persisted-artifacts.md` |

## Related

- [`persisted-artifacts.md`](persisted-artifacts.md) — the on-disk artifact set,
  the bb report, and the composed-unit materialization
- [`rule-annotations.md`](rule-annotations.md) — the in-memory layer model and
  the fold
- [`../../docs/explorer-graph-api.md`](../../docs/explorer-graph-api.md) — the
  server routes, including the `:registry` mode
