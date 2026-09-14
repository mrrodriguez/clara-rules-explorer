# Artifact registry — integration problems found

Status: **1–6 resolved** · Scope: `server/` (Clojure) · Related:
[`artifact-registry-plan.md`](artifact-registry-plan.md),
[`artifact-registry-roadmap.md`](artifact-registry-roadmap.md),
`tools/graph/artifacts/federate.clj`, `tools/graph/artifacts/compose.clj`,
`tools/graph/artifacts/registry.clj`

Found integrating the registry from a downstream host: a real registry of
independently generated units, plus the two units checked in under
`server/test-resources/rules-annos/`. Every repro below runs against those
checked-in units, so nothing here needs a private registry to reproduce.

Findings are ordered by severity. 1 and 2 are wrong answers; 3 is a misleading
diagnostic; 4 and 5 are consistency and ergonomics.

## 1. `:entry-points` and `:orphans` are sets of `:lhs-types` **vectors**, not fact types

`federate/->consumed-types` builds the consumed set with `map` where it needs
`mapcat`:

```clojure
(defn- ->consumed-types
  [{:keys [rules queries]}]
  (into #{} (comp cat (map :lhs-types)) [(vals rules) (vals queries)]))
```

`cat` flattens the outer pair into productions; `(map :lhs-types)` then yields
each production's `:lhs-types` **vector**. The result is a set of vectors — the
element type every consumer of it assumes is a fact-type name.

Both consumers are therefore wrong, and wrong in the direction that always
reports a problem:

- `->entry-points` subtracts a set of *types* (`all-satisfies`) from a set of
  *vectors*. Nothing is ever subtracted, so **every production's `:lhs-types`
  vector is reported as an entry point**, including the empty vector of a
  production that matches no type at all.
- `->orphans` runs `->satisfies` over that same set of vectors, so `consumed`
  matches no real type and **every produced type is reported as an orphan**.

`federate/grade`'s `:reference-produced-entry-points` reads `:entry-points`, and
`->digest`'s `:entry-point-count` / `:orphan-count` read both, so the digest and
the grade inherit it.

### Repro

```clojure
;; clojure -M:test -i probe.clj -e nil, from server/
(require '[clara.server.tools.graph.artifacts.registry :as registry]
         '[clara.server.tools.graph.artifacts.federate :as federate])
(let [reg   (registry/discover {:root "test-resources/rules-annos"})
      index (federate/->index reg (registry/units reg))]
  (println (first (get (:entry-points index) "loan-disposition-ruleset"))))
;; => [":loan-app/application-outcome"]      ; a vector, not a fact type
```

Counts on those two units, current versus what the `mapcat` correction gives:

| | current | corrected |
| --- | ---: | ---: |
| entry points, `loan-app-ruleset` | 12 | 6 |
| entry points, `loan-disposition-ruleset` | 3 | 0 |
| orphans, `loan-app-ruleset` | 14 | 7 |
| orphans, `loan-disposition-ruleset` | 2 | 0 |

The `loan-disposition-ruleset` column is the point. Corrected, it has **no**
entry points and **no** orphans, because its one consumed type is satisfied
through the hierarchy by the other unit's producer — which is exactly the
cross-unit contract those two fixtures exist to demonstrate. Today the index
reports that contract as an unexplained entry point.

### Fix

`(comp cat (mapcat :lhs-types))`. Note `->unit-edges` already flattens correctly
with its own `(mapcat :lhs-types)`, which is why the unit edge is right while
the entry points are not.

### Why the suite missed it

`federate-test` has no assertion on `:entry-points` or `:orphans` contents. The
one assertion that touches them —
`(is (empty? (:reference-produced-entry-points graded)))` in
`grade-against-a-composed-reference-test` — passes *because* the entry points
are vectors the reference can never be said to produce. A test pinning the
element type (every member of `:entry-points` is a key of `:fact-types`) would
have caught it, and is the regression test worth adding alongside the fix.

## 2. A `UnitRef`'s `:namespaces` filter narrows nothing

`artifacts/schema.clj`'s `UnitRef` says:

> `:namespaces`, when present, narrows the unit to the named namespaces for the
> merge

It does not. It narrows the *reported* `:scope :namespaces` and feeds
`:coverage :unknown-namespaces`; no production is excluded from either merge.
`federate/->index` passes only `analyses-by-unit` to `->production-maps`,
`->unit-edges`, `->entry-points`, `->orphans` and `->fact-types-index`, and
`compose/->composed-analysis` never looks at `:namespaces` at all. The
server's `RegistryConfig` takes `[UnitRef]`, so registry-backed serving inherits
the same no-op.

### Repro

```clojure
(let [reg   (registry/discover {:root "test-resources/rules-annos"})
      units (registry/units reg)
      all   (federate/->index reg units)
      none  (federate/->index reg (mapv #(assoc % :namespaces ["no.such.ns"]) units))]
  [(count (:fact-types all)) (count (:fact-types none))     ;; => [38 38]
   (count (:unit-edges all)) (count (:unit-edges none))     ;; => [1 1]
   (get-in none [:scope :namespaces])])                     ;; => all units narrowed to []
```

Composing the same fully-narrowed selection still yields all 20 rules.

A host cannot work around this: both entry points read the analyses off the
registry themselves, so there is no seam to hand in a pre-filtered analysis.
Any caller whose scope is *a subset of a unit's namespaces* — a deployment that
composes some of a unit's namespaces and excludes others — currently gets an
answer that includes rules which are not in its scope, silently.

### Fix, and the decisions it needs

Narrow the analysis when it is read for a merge — filter `:rules` / `:queries`
to productions whose `:ns` is in the filter. Three things to settle while doing
it, because they are visible in the output either way:

- **`:fact-types`.** Cheapest is to leave the map whole (it is keyed by type,
  not by namespace) and let the index's forward keys thin out naturally. The
  alternative — dropping types no surviving production mentions — changes
  `:hierarchy`, and the hierarchy is the one thing that benefits from being
  global. Leaving it whole is the recommendation.
- **`:dep-graph`.** `compose` already recomputes it over the merged production
  set, so it follows for free once the productions are filtered. `federate`
  does not read it.
- **Unknown namespaces.** A filter naming a namespace no unit covers is already
  reported; the same report should keep working when the filter actually bites,
  so it has to be computed before the narrowing, not after.

Until this lands, either implement it or delete the promise from `UnitRef`'s
docstring — a documented filter that silently does nothing is worse than no
filter.

## 3. `compatibility-report` lets units with no analysis vote for the majority shape

`registry/->unit-info` only assocs `:slim-dropped` when
`merged-rulebase-analysis/meta.edn` is readable. A unit without an analysis
therefore has `nil` for its shape, and `compatibility-report` groups by that
value like any other shape — so `nil` competes in the majority vote.

On a registry that is mid-migration, where most units predate the current
artifact layout and a few have been regenerated, this inverts the report: the
majority shape is `nil`, and the units that are *correct* are named as the
mismatch. `assert-compatible!` then throws naming them, which sends a reader to
fix the one thing that is not broken.

### Repro

```sh
mkdir -p /tmp/reg-probe && cd server
cp -R test-resources/rules-annos/loan-app-ruleset \
      test-resources/rules-annos/loan-disposition-ruleset /tmp/reg-probe/
for n in a b c; do
  cp -R /tmp/reg-probe/loan-app-ruleset /tmp/reg-probe/legacy-$n
  rm -rf /tmp/reg-probe/legacy-$n/merged-rulebase-analysis
done
```

```clojure
(let [reg (registry/discover {:root "/tmp/reg-probe"})]
  (registry/compatibility-report reg (registry/units reg)))
;; :majority-shape  nil
;; :shape-mismatch  [{:repo "loan-app-ruleset"} {:repo "loan-disposition-ruleset"}]
;; throw: Cannot merge registry units with differing slim shapes:
;;        ["loan-app-ruleset" "loan-disposition-ruleset"]
```

With two legacy units instead of three the tie-break happens to pick the real
shape, so the report's correctness currently depends on which group is larger.

### Fix

Partition the units before voting: those with a `:slim-dropped` and those
without. Vote only among the former, and report the latter under their own key
(`:no-analysis`, say) rather than as a shape. `assert-compatible!` should then
distinguish the two failures in its message — "N units have no analysis to
merge" is a different instruction to the reader than "N units have a different
slim shape". The information is already there: `:missing-artifacts` names
`:rulebase-analysis` for exactly those units.

Related: `federate/->index` and `compose/->composed-analysis` both call
`assert-compatible!` before `read-analysis-or-throw`, so today the shape error
fires first and the clearer "Unit X has no merged-rulebase-analysis" is never
seen on this path. Fixing the report fixes the ordering symptom too.

## 4. Three definitions of "produced" inside one index

The three fns that ask "does this unit produce this type" disagree:

| fn | produced = |
| --- | --- |
| `->unit-edges` | `:insert-types` + `:retract-types` |
| `->entry-points` | `:insert-types` only |
| `->orphans` | `:insert-types` + `:retract-types` |

So a type that one unit only retracts and another consumes can simultaneously
carry a producer→consumer edge, not be an orphan of the producer, and be an
entry point of the consumer — the index saying both "A supplies it to B" and
"nothing in scope produces it". `producers-of` reads a fourth path
(`->fact-type-entry`'s `producer-units`, inserts only), which agrees with
`->entry-points` and not with the edges.

One predicate, defined once and used by all four, with the choice stated in the
namespace docstring. Insert-only is the defensible reading — retracting a type
is not producing it — which makes `->unit-edges` and `->orphans` the two to
change. Whichever way it goes, the fix for finding 1 touches both call sites, so
it is cheap to settle at the same time.

## 5. Minor

- **No supported way to select the readable units.** On a registry that is
  partially regenerated, the only path to a working selection is for each host
  to write the same `(filter #(some? (registry/read-analysis reg %)) units)`
  loop — which also warms the cache with analyses it may not want. A
  `registry/units-with-analysis` (or a `:skip-unreadable?` option on the merges,
  reporting skipped units in `:coverage`) keeps that decision in one place.
- **`paths-between` returns one path.** The name reads plural and the plan
  called it a bounded BFS; the implementation returns the first shortest path or
  nil, which its docstring says plainly. Either rename to `path-between` or
  return the set of shortest paths — a reviewer asking "how does a fact get from
  A to B" usually wants to know whether there is more than one route.

---

## Resolution checklist

Changes made in `server/` to close these findings. Every item leaves
`make test lint reflection-check format-check` green.

### Finding 1 — entry points / orphans were `:lhs-types` vectors

- [x] `federate/->consumed-types` flattens with `(comp cat (mapcat :lhs-types))`,
  so the result is a set of fact-type names, not a set of vectors.
- [x] Regression test pins the element type: every member of `:entry-points` and
  `:orphans` is a key of `:fact-types`
  (`entry-points-and-orphans-are-fact-types-not-lhs-vectors-test`).
- [x] The corrected fixture counts are pinned: `loan-app-ruleset` has 6 entry
  points and 6 orphans; `loan-disposition-ruleset` has neither (its one consumed
  type is satisfied through the hierarchy by the other unit's producer).

### Finding 2 — `UnitRef` `:namespaces` narrowed nothing

- [x] `registry/narrow-analysis` filters an analysis's `:rules` / `:queries` to
  productions whose `:ns` is in the filter (strings), leaving `:fact-types`,
  `:dep-graph`, `:unresolved`, and `:slim` alone.
- [x] `federate/->index` applies it (via `narrow-analyses-by-unit`) and
  `compose/->composed-analysis` applies it before the merge — so the server's
  `RegistryConfig` path inherits it.
- [x] Unknown-namespace reporting is computed from **full** coverage before
  narrowing, so a namespace one unit covers but another's filter excludes is not
  misreported.
- [x] Tests: `namespace-filter-narrows-productions-not-just-scope-test` and
  `composed-analysis-narrows-to-unit-namespace-filter-test`.

### Finding 3 — no-analysis units voted as the `nil` shape

- [x] `registry/compatibility-report` partitions units into `:no-analysis` vs
  analyzed before voting; only analyzed units vote for the majority shape.
- [x] `:compatible?` now requires both no missing analyses and no shape skew;
  `:no-analysis` is a first-class report key and is in `schema/CompatibilityReport`.
- [x] `registry/assert-compatible!` throws two distinct messages: "N unit(s) have
  no merged-rulebase-analysis to merge" vs "differing slim shapes". This also
  fixes the ordering symptom (the clearer missing-analysis error now fires first).
- [x] Tests: `compatibility-report-keeps-units-without-analysis-out-of-the-vote`
  and `units-with-analysis-returns-only-readable-units`.

### Finding 4 — three definitions of "produced"

Resolved as **two named readings**, not the single insert-only predicate this
section proposed. The proposed insert-only `:unit-edges` would have diverged
from the live dep-graph in `clara.server.tools.graph.core` and from
`compose/->dep-graph`, which both treat retract as a coupling (core tags it
`:via :retract`).

- [x] Supply reading (insert-only): `federate/production-produced-types` and
  `->produced-types`, used by `:entry-points`, `:orphans`, and `:producers` —
  retracting a type removes it, so it neither supplies an entry point nor makes
  an orphan.
- [x] Coupling reading (insert + retract): `federate/->coupled-types`, used by
  `:unit-edges` — both directions change the consumer's fact set, matching
  `core/->dep-graph` and `compose/->dep-graph`.
- [x] `->orphans` moved to the supply reading (it was the odd one out);
  `->unit-edges` stayed on the coupling reading.
- [x] Both readings are stated in the `federate` namespace docstring.
- [x] Regression test `retract-couples-units-but-does-not-supply-test` pins all
  four behaviors for a retract-only producer.

### Finding 5 — minor

- [x] `registry/units-with-analysis` returns the readable units (tests artifact
  presence, warms nothing).
- [x] `federate/paths-between` returns the **set** of all shortest paths (kept the
  plural name) rather than one path.
- [x] Tests cover both; the `paths-between` callers were updated to the set
  shape.

### Follow-up correction

- [x] Renamed the vague `produced-types` extraction to `->produced-types`, and
  corrected `->satisfies`'s misleading `[ancestors produced]` params to
  `[hierarchy base-types]`.

## 6. An aggregate unit is indistinguishable from a source unit, and silently double-counts

Found while verifying the fixes above, against the composed unit now checked in
at `test-resources/rules-annos/composed/loan-app-plus-disposition/`.

`flow/compose-persist!` writes a composition as a unit-shaped directory inside
the registry, which is the point — the bb script and the server read it like any
other unit. But `registry/discover` then returns it alongside the units it was
composed *from*, and nothing in the registry API says which is which:
`unit-info` records `:repo`, `:dir`, `:artifacts`, `:slim-dropped`,
`:layer-ids`, `:manifest-head` — not `:mode`. The only tell is
`(get-in (registry/read-manifest reg unit) [:analysis-run :mode])`, an extra
read per unit that a caller has to know to make.

The two merges then fail differently, and only one of them fails loudly:

- **`compose/->composed-analysis` throws** — the composed unit claims every
  production name its sources claim, so `merge-production-map` refuses. Correct
  and obvious.
- **`federate/->index` silently double-counts.** Over the three checked-in
  units, the one real cross-unit edge becomes four (composition ↔ each source),
  and the six entry points of `loan-app-ruleset` are reported a second time
  under the composition's name. Every per-unit count in the digest inflates the
  same way, and no message is emitted.

```clojure
(let [reg (registry/discover {:root "test-resources/rules-annos"})
      all (registry/units-with-analysis reg)                       ; includes composed/…
      src (filterv #(not= "composed/loan-app-plus-disposition" (:repo %)) all)]
  [(count (:unit-edges (federate/->index reg src)))    ;; => 1
   (count (:unit-edges (federate/->index reg all)))])  ;; => 4
```

`units-with-analysis` (finding 5) makes this easier to hit, not harder: it is
the natural "give me everything readable" call, and on a registry that holds one
composition it hands back a selection that quietly answers wrong.

### `compose-persist!` is not the only aggregate

A host that analyzes a **whole assembled rulebase** — a restored serialized
session, a monolithic run over everything at once — persists that as a unit too,
beside the per-component units it overlaps. It is the same shape of unit as a
composition, produced by a different route, and it needs the same treatment.

That case is worse in one respect. A host whose compiler rewrites namespaces
when it assembles a rulebase (appending a content hash to each production
namespace, say) yields an aggregate whose fq production names match **no**
source unit's. So:

- `compose`'s name-collision refusal — the one loud failure that catches the
  `compose-persist!` overlap — never fires.
- `federate/->index` sees two disjoint production sets over one shared
  fact-type vocabulary and reports a full mesh of unit edges between the
  aggregate and every component, plus every component's producers duplicated
  under the aggregate's name.

There is no error, no warning, and nothing in the artifact set that says the two
describe the same rules. Whatever marker closes this has to be one the host can
set for an aggregate it produced itself, not a flag only `compose-persist!`
writes.

### Fix options, in increasing strength

- **Record the mode.** `->unit-info` reads the manifest already; carrying
  `:mode` (and `:composed-from`, the `:analysis-run :units`) costs nothing and
  makes every host-side decision a pure function of the registry value.
  `:mode` should be **host-settable** through the existing manifest
  contributions — `:compose` for `compose-persist!`, whatever a host calls its
  own aggregates otherwise — with the absence of the key meaning "a source
  unit". A closed enum would only push hosts back to reading the raw manifest.
- **Offer the selection.** `registry/source-units` — units with no aggregate
  mode — beside `units-with-analysis`, since "the units someone analyzed" is the
  selection nearly every federation wants.
- **Refuse the overlap.** `federate/->index` can detect that a selected unit
  declares an aggregate mode and throw unless the caller says it means it. For a
  `:composed-from` that names other selected units, refusing outright is right,
  the way compose already does. For an aggregate that names nothing — a captured
  session — refusing any mix of aggregate and source units in one selection is
  the only check available, and it is the correct default: those two are never
  one question.

The first is the one that matters; the others are cheap once it exists.

`composed-artifact-persist-plan.md` §7 raises where the federated sidecar files
should live, which is adjacent, but not this: the hazard is the composed unit
itself being indistinguishable from what it was composed from.

### Resolution

The three options above landed, weakest first:

- [x] **Record the mode.** `registry/->unit-info` records `:mode` (the
  manifest's `:analysis-run :mode`) and `:composed-from` (the
  `:analysis-run :units`, normalized to `UnitRef`s); absence of `:mode` marks a
  source unit. The `:mode` value is host-set and open — `compose-persist!`
  writes `:compose`, a captured session writes whatever the host calls it — so
  no closed enum. `schema/UnitInfo` carries both keys.
- [x] **Offer the selection.** `registry/aggregate-unit?` is the presence
  predicate and `registry/source-units` is the "units someone analyzed"
  selection beside `units-with-analysis` — excluding compositions and captured
  whole-rulebase units that would double-count them.
- [x] **Refuse the overlap.** `federate/->index` refuses before any analysis
  is read:
  - an aggregate whose `:composed-from` names another selected unit throws
    outright, the way `compose/->composed-analysis` already refuses a name
    claimed by two units;
  - any other mix of aggregate and source units also throws — those two are
    never one question.
- [x] Tests pin all of it: `registry` records the mode/composed-from and
  `source-units` excludes aggregates
  (`discover-records-aggregate-mode-and-composed-from-test`,
  `source-units-excludes-aggregate-units-test`); `federate` refuses the
  composed unit beside its sources and the mix
  (`aggregate-unit-beside-its-sources-is-refused-test`,
  `aggregate-source-mix-is-refused-test`).

The checked-in repro now refuses: indexing `units-with-analysis` over
`rules-annos/` names the composition and its selected sources, where it
previously reported four edges and no message.
