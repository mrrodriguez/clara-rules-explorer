# Artifact registry architecture

The registry is the plural side of persistence. Where
[`persisted-artifacts.md`](persisted-artifacts.md) documents **one** artifact
set — a *unit* — and its files, this doc is the architecture of combining
**many** units: how a caller-named selection becomes one queryable value, and
why the library offers three merge modes rather than one.

Read `persisted-artifacts.md` first if you do not already know what a unit, a
layer, a slim analysis, or the manifest are; this doc assumes that vocabulary.
The questions the registry exists to answer span units: *who consumes the type
this set produces*, *what does this branch do to the others*, and *what does
this set of sets look like as one rulebase*.

## The one join key, and the three relations over it

Every unit is addressed by `:repo` and optionally `:branch`; the **join key
between units is the fact type name**. Two units using one name for two
different things produce a wrong join, and nothing here can detect that. It is
the assumption the whole registry states and does not verify — the hierarchy
`:conflicts` and per-type `:declared-in` are what a host reads to check it.

Everything the registry answers is a projection over three relations derivable
from any production set, closed through the fact-type hierarchy:

| relation | from | closure direction |
| --- | --- | --- |
| **produces** | a rule's `:insert-types` | ancestors — inserting `T` inserts `T` and every ancestor of `T` |
| **consumes** | a rule's `:lhs-types` | descendants — matching `T` is reached by every descendant of `T` |
| **retracts** | a rule's `:retract-types` | coupling only — a retract changes the fact set but does not supply it |

The composed dep-graph, the federated fact-type index, unit edges, entry
points, orphans, `diff`, and `grade` are all joins or set-differences over
these three relations. Retraction is deliberately not *production*:
`federate/->index` records it as coupling (`:retracted-by`, unit edges) but
never as supply (`:producers`, `:entry-points`, `:orphans`).

## The hierarchy is per-unit, and repair is step one

`:ancestors` is a transitive closure computed by the ancestors fn on the
classpath each unit's analysis had. A `derive` that lives in a component one
unit did not load is absent from that unit, so two units can hold different
ancestor sets for one type name and both be locally correct. Measured on a
10-unit registry: 99 of the 393 fact-type names present in more than one unit
disagree.

Both merge modes repair this the same way, in
`clara.server.tools.graph.artifacts.hierarchy`: union every unit's ancestor edge
set (`union-ancestors`), re-close transitively (`closed-ancestors`), transpose
(`->descendants`), order deepest-first (`hierarchy-order`), and record the
disagreements under `:hierarchy :conflicts` rather than pick a winner.

The two closure directions are the one thing that is easy to get wrong, because
an unclosed inverse is a wrong answer that nothing flags: inverting
`:lhs-types` alone matches 2033 of the 3687 fact types. They are therefore
named, in one place:

- `clara.server.tools.graph.artifacts.hierarchy/ancestor-closure` — the set a
  *holder* of `base-types` satisfies.
- `clara.server.tools.graph.artifacts.hierarchy/descendant-closure` — the set a
  *matcher* of `base-types` is reached by.

## Three merge modes, three invariants

The modes are not one function with a flag. Each asserts a different
relationship between the selected units, and collapsing them would lose exactly
the distinction the registry exists to draw.

| mode | entry point | invariant |
| --- | --- | --- |
| **fold layers** | `compose/fold-layers` | union semantics; no collision concept. Layer ids are qualified `<repo>[@<branch>]/<layer-id>` so `:provenance` names whose layer claimed what. |
| **compose** | `compose/->composed-analysis` | the caller asserts the units are components of ONE rulebase. Productions merge by fq name, a name claimed by two units is refused, and the dep-graph is recomputed so cross-unit edges exist. |
| **federate** | `federate/->index` | the caller asserts the units are NOT claimed to compose. Productions stay per-unit; disagreements are recorded (`:hierarchy :conflicts`), never resolved. |

A host typically uses all three in sequence: fold layers within each unit,
federate to see which units interact, compose the subset it decides really is
one rulebase.

`flow/compose-persist!` and the server's `:registry` mode are both the compose
mode wearing a different face: the first materializes it as a normal
single-unit directory for the offline babashka report, the second serves it
rehydrated over HTTP. Neither is a fourth mode.

## The pass structure

The two analysis-consuming modes share one preamble, in
`clara.server.tools.graph.artifacts.selection/->selection`:

```
selection (caller-named, ordered)
  → registry/assert-compatible!       refuse shape skew / missing analysis
  → read each unit's slim analysis    (throw a named error if absent)
  → narrow each to its :namespaces    (per-unit filter)
  → union + re-close the hierarchy    (hierarchy ns)
  → coverage                          (scoped namespaces + unknown-namespaces)
```

`->selection` returns
`{:analyses :ancestors :descendants :hierarchy-conflicts :coverage}` — one value
both modes consume. Compose takes `:analyses` + `:ancestors` and merges
productions; federate takes the whole thing and builds its per-unit relation
maps.

The derivation/projection split is what keeps each mode small: `federate`
builds the three relations once (its internal production-maps pass), then
`:fact-types`, `:unit-edges`, `:entry-points`, `:orphans`, `diff`, and `grade`
are projections over them. Compose's dep-graph is the same producer→consumer
join at production granularity, over the same closed `:ancestors`.

## The two output values

- **`compose/->composed-analysis`** returns one slim `RulebaseAnalysis`, each
  production tagged `:unit` naming its source unit — the only key composition
  adds. Rehydrate it (`rehydrate/rehydrate-analysis`) to rebuild the inverses
  over the *whole* composition, the one thing a per-unit artifact cannot
  contain. `flow/compose-persist!` writes it to disk as a normal unit (see
  `persisted-artifacts.md`, "Composing into a unit"); the annotation side of
  that directory comes from `compose/->standard-role-layers`, which flattens
  each unit's layers to the three standard roles.

- **`federate/->index`** returns an index value. It is computed, not persisted:
  reading the units and building it is ~0.16s for a 10-unit registry, so
  nothing is written on the path to an answer. `federate/persist!` +
  `federate/read-index` / `federate/read-digest` exist only for handing a file
  to a reader; `federate/->digest` reduces the index to what fits in a head.
  `federate/diff` compares two indexes (the branch-vs-mainline question);
  `federate/grade` checks the union against a composed reference.

## Namespace map

| namespace | responsibility |
| --- | --- |
| `registry` | discover/read N units as a value; unit-key; `narrow-analysis` / `narrow-annotations`; `compatibility-report` / `assert-compatible!`; `source-units` / `aggregate-unit?` |
| `selection` | the shared merge preamble: read + narrow + assert-compatible + unioned hierarchy + coverage |
| `hierarchy` | union / re-close / transpose / order of ancestor maps; the two named closures |
| `rehydrate` | slim's inverse: rebuild the recomputable reverse directions |
| `compose` | `fold-layers`, `->standard-role-layers`, `->composed-analysis`, `union-fact-types` |
| `federate` | `->index` + query fns, `diff`, `grade`, `->digest`, `persist!` / `read-*` |
| `flow` | `generate` → `persist!` (single unit) and `compose-persist!` (composed unit) |
| `slim` / `parts` / `compact` / `digest` / `manifest` | one unit's on-disk shape — see `persisted-artifacts.md` |

## Related

- [`persisted-artifacts.md`](persisted-artifacts.md) — the on-disk artifact set,
  the bb report, and the composed-unit materialization
- [`rule-annotations.md`](rule-annotations.md) — the in-memory layer model and
  the fold
- [`../../docs/explorer-graph-api.md`](../../docs/explorer-graph-api.md) — the
  server routes, including the `:registry` mode
