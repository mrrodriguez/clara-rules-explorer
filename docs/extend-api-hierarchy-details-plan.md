# Extend API with Fact Type Hierarchy Details

Design plan for surfacing type-hierarchy information in the explorer API so that
upstream/downstream relationships that cross type-hierarchy boundaries are
self-explanatory without requiring the user to cross-reference fact-type views.

---

## Problem Statement

The analysis graph (`build-dep-graph`) already respects Clara's `ancestors-fn`
hierarchy: when Rule X inserts type `A` and Rule Y's LHS reads type `B` (where
`A` is a descendant of `B` via `ancestors-fn`), the dep-graph correctly links
Rule X → Rule Y.  However, the API that serves this information to the UI
does not convey any hierarchy context:

- **Fact-type views** (`/v1/fact-types`, `/v1/analysis`) show usage counters
  but nothing about what ancestors a type has.  You can't tell that
  `MarkerRecord` satisfies `IScanMarker` from the API.

- **Production-dependency links** (`:upstream` / `:downstream`) carry only
  `{name, ns, type}` — no indication of *which* produced type satisfies
  *which* required type.  When Rule Y requires type `B` and its upstream Rule X
  produces type `A` (a descendant of `B`), neither the upstream entry on Y nor
  the downstream entry on X makes this visible.  The user sees two unrelated
  types and has to manually correlate fact-type pages to understand the link.

**Goal:** Make the API self-explanatory so that from any production or
fact-type view, the user can see how types relate through the hierarchy
without cross-referencing.

---

## System Context

### Where `ancestors-fn` lives

```clojure
;; In build-dep-graph (core.clj:178):
(let [{:keys [ancestors-fn]} (meta get-alphas-fn)] ...)
```

`get-alphas-fn` is a component of the compiled rulebase. Its metadata carries
`:ancestors-fn` (a fn of one arg returning a seq of ancestor types) and
`:fact-type-fn` (returns the concrete type of a fact).  These are set by
Clara's session factory and reflect user-provided `:ancestors-fn` and/or
`:hierarchy` options.

### How `downstream?` works today

```clojure
(defn- downstream? [ancestors-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (and ancestors-fn
           (contains? (set (ancestors-fn inserter-type)) reader-type))))
```

Two cases: (a) direct type match, or (b) the inserter's type has the reader's
type among its ancestors.  This boolean check is used to build the dep-graph
but the *specific pair* that caused a match is not retained.

### Current data structures

**ProductionDep** (on `:upstream` / `:downstream`):
```json
{"name": "my.ns/rule-x", "ns": "my.ns", "type": "rule"}
```

**FactTypeSummary** (on `/v1/fact-types` and `:fact-types` in analysis):
```json
{
  "name": "my.ns.MarkerRecord",
  "used-by-rules": ["my.ns/consume-marker"],
  "used-by-queries": [],
  "inserted-by-rules": ["my.ns/insert-marker"],
  "retracted-by-rules": []
}
```

---

## Phase 1 — Fact Type Hierarchy Representation

**Goal:** Every fact-type summary shows its ancestors in hierarchy order.

### 1a. Thread `ancestors-fn` through the analysis pipeline

Currently `build-fact-type-summary-map` has no access to `ancestors-fn`.
We need to:

1. Extract `ancestors-fn` in `rulebase-analysis` (it's already available
   from the rulebase passed to `build-dep-graph`).
2. Pass `ancestors-fn` to `build-fact-type-summary-map`.
3. For each fact type in the summary map, call `(ancestors-fn type)` and
   serialize the result.

### 1b. New field: `:ancestors`

Add to each fact-type entry:

```json
{
  "name": "my.ns.MarkerRecord",
  "ancestors": ["my.ns.IScanMarker", "java.lang.Object"],
  "used-by-rules": [...],
  ...
}
```

- `ancestors` is a **vector of serialized type strings** representing the
  direct result of `(ancestors-fn type)` — not a recursive closure.
  Each ancestor may itself appear as a fact type in the map with its own
  `ancestors` field, so the full hierarchy is reconstructible from the map.
- Order is preserved from `ancestors-fn`'s return value (the user controls
  ordering in `ancestors-fn`; it is semantically significant for Clara's
  internal dispatch).
- When `ancestors-fn` is nil (no hierarchy configured), `ancestors` is `[]`
  (empty vector).
- When a type has no ancestors, `ancestors` is `[]`.

### 1c. Fact types with no recorded usage

Fact types that appear ONLY as ancestors of other types (e.g., `IScanMarker`
might only appear as an ancestor of `MarkerRecord` but also on LHS of a rule)
need to appear in the fact-types map to carry their own `ancestors`.  This is
already the case today: `build-fact-type-summary-map` iterates over LHS types
from all rules/queries, so `IScanMarker` appears because it is on a rule's LHS
even if no rule inserts it.

If a type is discovered purely through `ancestors-fn` and never appears in
any production's LHS-types, insert-types, or retract-types, it currently
would not appear in the fact-types map.  **Decision for Phase 1:** do NOT
add such "ghost" types — only types already present in the map get an
`ancestors` field.  Ancestors not in the map are still visible as strings
in their descendants' `ancestors` arrays.  If this proves insufficient,
a follow-up can compute transitive closure.

### 1d. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `rulebase-analysis`: extract `ancestors-fn`. `build-fact-type-summary-map`: accept `ancestors-fn`, enrich each entry with `:ancestors`. `build-fact-type-summary-map` call sites. |
| `server/src/clara/server/tools/graph/serialize.clj` | `resolve-type` already handles all type forms; no new serialization needed beyond calling it per ancestor. |
| `server/src/clara/server/graph/api.clj` | Add `(s/optional-key :ancestors)` to schema. Update `fact-types-list` `select-keys` if we want ancestors in the list view (we should). |
| `server/test/clara/server/tools/graph/core_test.clj` | Tests verifying `:ancestors` on fact types with and without hierarchy. |
| `ui/src/lib/types/api.ts` | Add `ancestors?: string[]` to `FactTypeSummary`. |
| `docs/explorer-graph-api.md` | Document the new `:ancestors` field in fact-type responses. |

### 1e. API backward compatibility

Additive only.  New `:ancestors` key on fact-type objects.  Existing keys
unchanged.  Clients that ignore unknown keys are unaffected.

### 1f. Test cases

1. **No hierarchy configured:** `ancestors-fn` nil → all `:ancestors` are `[]`.
2. **Clojure `derive` hierarchy:**
   ```clojure
   (derive ::child ::parent)
   (derive ::parent ::grandparent)
   ```
   → `{:name "my.ns/child" :ancestors ["my.ns/parent" "my.ns/grandparent"]}`
3. **Java interface hierarchy:** `MarkerRecord` implements `IScanMarker` →
   `{:name "..." :ancestors ["my.ns.IScanMarker" "java.lang.Object"]}`
4. **Custom `ancestors-fn`:** User-provided fn returning arbitrary types.

---

## Phase 2 — Type-Bridge Info on Production Dep Links

**Goal:** Each `:upstream` / `:downstream` entry on a rule/query summary
carries enough information to show *which* produced type satisfies *which*
required type, and whether the match is direct or through hierarchy.

### 2a. Extend `build-dep-graph` to capture type-pair edges

Current dep-graph edges are name→name sets with no type-level granularity:

```clojure
{"producer-name" {:downstream #{"consumer-name"}}
 "consumer-name" {:upstream   #{"producer-name"}}}
```

**Proposed:** each edge value becomes a map keyed by the related production
name, with a `:via` vector of type-pair records:

```clojure
{"producer-name"
 {:downstream {"consumer-name" {:via [{:produces TypeA :satisfies TypeB}]}}}
 "consumer-name"
 {:upstream   {"producer-name" {:via [{:produces TypeA :satisfies TypeB}]}}}}
```

Where:
- `:produces` — the concrete type the upstream rule inserts (or retracts).
- `:satisfies` — the concrete type the downstream rule's LHS requires.
- Multiple entries in `:via` mean multiple type pairs connect the same two
  productions.

Direct matches (no hierarchy) produce entries where `:produces` equals
`:satisfies`.  This redundant representation is intentional: it makes the
consumer side uniform — you always look at `:produces` / `:satisfies` without
needing to detect the direct-match case client-side.

**Implementation approach:** modify the inner loop of `build-dep-graph` to,
instead of checking `some-type-consumed?` and adding a simple edge, enumerate
all matching type pairs and build the `:via` structure.

```clojure
;; Before (current):
;; (some-type-consumed? produced-types1 consumed-types2) → boolean → edge

;; After:
(defn- matching-type-pairs [ancestors-fn produced-types consumed-types]
  (for [pt produced-types
        ct consumed-types
        :when (downstream? ancestors-fn pt ct)]
    {:produces pt :satisfies ct}))

;; And in the pair-generation loop:
(for [[p-name1 {produced-types1 :produced-types}] type-analysis-map
      [p-name2 {consumed-types2 :consumed-types}] type-analysis-map
      :when (not= p-name1 p-name2)
      :let [pairs (matching-type-pairs ancestors-fn produced-types1 consumed-types2)]
      :when (seq pairs)]
  [p-name1 p-name2 pairs])
```

### 2b. Serialize type-bridge info on `:upstream` / `:downstream`

**Current `ProductionDep`:**
```json
{"name": "my.ns/producer-rule", "ns": "my.ns", "type": "rule"}
```

**Proposed enrichment — upstream entries** (seen from a consumer's perspective):
```json
{
  "name": "my.ns/producer-rule",
  "ns": "my.ns",
  "type": "rule",
  "match": [
    {"produces": "my.ns.MarkerRecord", "satisfies": "my.ns.IScanMarker"}
  ]
}
```

**Proposed enrichment — downstream entries** (seen from a producer's perspective):
```json
{
  "name": "my.ns/consumer-rule",
  "ns": "my.ns",
  "type": "rule",
  "match": [
    {"requires": "my.ns.IScanMarker", "satisfied-by": "my.ns.MarkerRecord"}
  ]
}
```

**Key naming rationale:**
- Upstream: `:produces` / `:satisfies` — answers "what does upstream produce,
  and what requirement of mine does it satisfy?"
- Downstream: `:requires` / `:satisfied-by` — answers "what does downstream
  require, and which of my outputs satisfies it?"

When `produces` equals `satisfies` (direct match, no hierarchy), both are
still present.  This avoids client-side special-casing.

`match` is an array because a single pair of productions may be linked by
multiple type pairs (e.g., producer inserts types A and C, consumer reads
B and D where A descends from B and C descends from D).

### 2c. Update `get-production-deps-summary`

This function currently serializes dep-graph entries into `ProductionDep`
vectors.  It needs to accept the new dep-graph shape and serialize the
`:via` info into `:match` arrays with serialized type strings.

### 2d. Function decomposition

The signature of `build-dep-graph` does not change externally. Internally:

1. Extract `matching-type-pairs` as a helper.
2. Replace `add-dep-graph-entry` with a version that stores `:via` pairs.
3. Update `get-production-deps-summary` (and its helper `serialize-deps`)
   to serialize the new shape.

### 2e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `build-dep-graph`: enumerate type pairs, store `:via`. `get-production-deps-summary`: serialize `:via` → `:match`. `production-summary`: pass serialized match info through. |
| `server/src/clara/server/tools/graph/serialize.clj` | If needed, helper to serialize type-pair maps. `resolve-type` already handles type → string. |
| `server/src/clara/server/graph/api.clj` | Extend `ProductionDep` schema with optional `:match` array. |
| `server/test/clara/server/tools/graph/core_test.clj` | Tests for `:match` on dep links with and without hierarchy; verify type-pair entries are correct. |
| `ui/src/lib/types/api.ts` | Extend `ProductionReference` with `match?: TypeBridgeMatch[]`. Add `TypeBridgeMatch` interface. |
| `docs/explorer-graph-api.md` | Document `:match` field on upstream/downstream entries. |

### 2f. API backward compatibility

Additive only.  New optional `:match` key on each `ProductionDep` object.
Existing keys (`name`, `ns`, `type`) unchanged.  When `:match` is absent
(no ancestors-fn configured, or direct-only matches), the relationship is
still expressed by the presence of the dep entry itself; the `:match` array
clarifies the specific type bridge for hierarchy cases.

**Open question:** Should we always include `:match` even when it's a direct
match?  **Recommendation: yes** — it normalizes the consumer's parsing path.

### 2g. Test cases

1. **Direct match (no hierarchy):**
   Rule A inserts `Foo`, Rule B requires `Foo` →
   upstream on B: `match: [{produces: "Foo", satisfies: "Foo"}]`
   downstream on A: `match: [{requires: "Foo", satisfied-by: "Foo"}]`

2. **Single hierarchy jump:**
   Rule A inserts `MarkerRecord`, Rule B requires `IScanMarker` (interface) →
   upstream on B: `match: [{produces: "MarkerRecord", satisfies: "IScanMarker"}]`
   downstream on A: `match: [{requires: "IScanMarker", satisfied-by: "MarkerRecord"}]`

3. **Multi-type bridge:**
   Rule A inserts `[A, C]`, Rule B requires `[B, D]` where A→B and C→D via hierarchy →
   upstream on B: `match: [{produces: A, satisfies: B}, {produces: C, satisfies: D}]`

4. **Production with both direct and hierarchy matches:**
   Rule A inserts `[Foo, MarkerRecord]`, Rule B requires `[Foo, IScanMarker]` →
   upstream on B: `match: [{produces: Foo, satisfies: Foo},
                            {produces: MarkerRecord, satisfies: IScanMarker}]`

---

## Phase 3 (Future) — UI Integration

Once the server API is extended, the UI can use the new fields:

- **Fact-type detail view:** Render the `:ancestors` chain as a breadcrumb or
  hierarchy tree, with links to each ancestor's detail page.
- **Rule detail view:** In the upstream/downstream sections, show each dep
  entry with the `:match` details inline (e.g., "Rule X produces
  `MarkerRecord` → satisfies `IScanMarker`").
- **Graph visualization:** Edges could carry type-bridge labels.

This phase is scoped separately and not detailed here.

---

## Implementation Order

- [ ] **Phase 1a:** Thread `ancestors-fn` through `rulebase-analysis` → `build-fact-type-summary-map`
- [ ] **Phase 1b:** Add `:ancestors` field to fact-type entries in `build-fact-type-summary-map`
- [ ] **Phase 1c:** Update `fact-types-list` select-keys to include `:ancestors`
- [ ] **Phase 1d:** Update API schema (`FactTypeSummary` in `api.clj`)
- [ ] **Phase 1e:** Add server tests for `:ancestors`
- [ ] **Phase 1f:** Update UI types (`FactTypeSummary` in `api.ts`)
- [ ] **Phase 1g:** Update API documentation (`explorer-graph-api.md`)
- [ ] **Phase 2a:** Extend `build-dep-graph` to capture type-pair `:via` edges
- [ ] **Phase 2b:** Update `get-production-deps-summary` to serialize `:via` → `:match`
- [ ] **Phase 2c:** Update `ProductionDep` schema in `api.clj` with optional `:match`
- [ ] **Phase 2d:** Add server tests for `:match` on dep links
- [ ] **Phase 2e:** Update UI types (`ProductionReference` in `api.ts`)
- [ ] **Phase 2f:** Update API documentation (`explorer-graph-api.md`)
- [ ] **Phase 3:** UI integration (future, scoped separately)
