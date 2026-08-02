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

**Goal:** Every fact-type summary shows its ancestors with enough context to
distinguish "real" types from ghost types (ancestors that only appear in
the hierarchy, never in a production's LHS/insert/retract).

### 1a. Thread `ancestors-fn` through the analysis pipeline

Currently `build-fact-type-summary-map` has no access to `ancestors-fn`.
We need to:

1. Extract `ancestors-fn` in `rulebase-analysis` (it's already available
   from the rulebase passed to `build-dep-graph`).
2. Pass `ancestors-fn` to `build-fact-type-summary-map`.
3. For each fact type in the summary map, call `(ancestors-fn type)` and
   serialize the result into objects with `type` and `known` keys.
4. Compute `known` by checking whether the ancestor type key exists in the
   fact-types summary map (i.e., is it used on any production's LHS or
   inserted/retracted by any rule).
5. Cache `(ancestors-fn type)` per unique type so it is evaluated at most
   once per type (relevant for large type sets with expensive custom
   `ancestors-fn` implementations).

### 1b. New field: `:ancestors`

Add to each fact-type entry:

```json
{
  "name": "my.ns.MarkerRecord",
  "ancestors": [
    {"type": "my.ns.IScanMarker", "known": true},
    {"type": "java.lang.Object", "known": false}
  ],
  "used-by-rules": [...],
  ...
}
```

Each ancestor entry is an object:

- **`type`** — serialized type string.
- **`known`** — `true` if this ancestor type appears anywhere in the
  `fact-types` map (i.e., some production uses it on its LHS or it is
  inserted/retracted), `false` if it is a "ghost type" — only visible as
  an ancestor of another type.  This lets the UI decide whether to render
  it as a hyperlink or plain text.

Additional rules:

- `ancestors` is **deterministically sorted lexicographically** on the
  serialized `type` string.  While user-provided `ancestors-fn` return values
  may carry semantically significant ordering for Clara's internal dispatch,
  `clojure.core/ancestors` (the default when a `:hierarchy` is given)
  returns an unordered set.  Lexicographic sort guarantees stable API
  responses regardless of the underlying implementation.
  (UI-layer reordering/filtering based on `known` is a Phase 3 concern.)
- The concrete type itself (the `name` key) is NOT included in the
  `ancestors` array.  The data structure already places it before its
  ancestors.
- `ancestors` is a vector representing the direct result of
  `(ancestors-fn type)` — not a recursive closure.  Each ancestor may
  itself appear as a fact type in the map with its own `ancestors` field,
  so the full hierarchy is reconstructible from the map.
- When `ancestors-fn` is nil (no hierarchy configured), `ancestors` is `[]`
  (empty vector).
- When a type has no ancestors, `ancestors` is `[]`.

### 1c. Fact types with no recorded usage ("ghost types")

Fact types that appear ONLY as ancestors of other types (e.g., `IScanMarker`
might only appear as an ancestor of `MarkerRecord` but also on LHS of a rule)
need to appear in the fact-types map to carry their own `ancestors`.  This is
already the case today: `build-fact-type-summary-map` iterates over LHS types
from all rules/queries, so `IScanMarker` appears because it is on a rule's LHS
even if no rule inserts it.

If a type is discovered purely through `ancestors-fn` and never appears in
any production's LHS-types, insert-types, or retract-types, it currently
would not appear in the fact-types map.  **Decision for Phase 1:** do NOT
add such "ghost" types to the `fact-types` map.  They appear only in
`ancestors` arrays with `"known": false`.  This gives the UI enough
information to render them as plain text rather than broken links.  If
this proves insufficient, a follow-up can compute transitive closure.

### 1d. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `rulebase-analysis`: extract `ancestors-fn`. `build-fact-type-summary-map`: accept `ancestors-fn`, enrich each entry with `:ancestors` (objects with `:type` and `:known` keys, sorted lexicographically). Cache `(ancestors-fn type)` per unique type. |
| `server/src/clara/server/tools/graph/serialize.clj` | Helper to serialize ancestor entries into `{:type ... :known ...}` objects. `resolve-type` already handles type → string. |
| `server/src/clara/server/graph/api.clj` | Add `AncestorEntry` schema. Update `FactTypeSummary` schema with `(s/optional-key :ancestors)`. Update `fact-types-list` select-keys. |
| `server/test/clara/server/tools/graph/core_test.clj` | Tests verifying `:ancestors` shape with `:known` flag, deterministic ordering, and nil `ancestors-fn` case. |
| `ui/src/lib/types/api.ts` | Add `AncestorEntry` interface. Update `FactTypeSummary` with `ancestors?: AncestorEntry[]`. |
| `docs/explorer-graph-api.md` | Document the new `:ancestors` field with `type`/`known` keys. |

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
   → `{:name "my.ns/child" :ancestors [{:type "my.ns/grandparent" :known false}
                                         {:type "my.ns/parent" :known false}]}`
   (both ghost types — neither appears on any production's LHS)
3. **Java interface hierarchy:** `MarkerRecord` implements `IScanMarker` →
   `{:name "..." :ancestors [{:type "my.ns.IScanMarker" :known true}
                              {:type "java.lang.Object" :known false}]}`
   (`IScanMarker` is `known: true` because a rule has it on its LHS)
4. **Custom `ancestors-fn`:** User-provided fn returning arbitrary types,
   sorted lexicographically in the output.
5. **Deterministic ordering:** even when `clojure.core/ancestors` returns an
   unordered set, the output is stable across calls.
6. **Caching:** `ancestors-fn` called once per unique type, even when the
   type appears in multiple productions.

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
- Multiple entries in `:via` mean multiple *distinct* type pairs connect the
  same two productions.

Direct matches (no hierarchy) produce entries where `:produces` equals
`:satisfies`.  This redundant representation is intentional: it makes the
consumer side uniform — you always look at `:produces` / `:satisfies` without
needing to detect the direct-match case client-side.

**Implementation approach:** modify the inner loop of `build-dep-graph` to,
instead of checking `some-type-consumed?` and adding a simple edge, enumerate
all matching type pairs and build the `:via` structure.

```clojure
(defn- matching-type-pairs [ancestors-fn produced-types consumed-types]
  (->> (for [pt produced-types
             ct consumed-types
             :when (downstream? ancestors-fn pt ct)]
         {:produces pt :satisfies ct})
       (distinct)   ;; deduplicate: same (pt, ct) from multiple RHS insert! calls
       vec))

;; And in the pair-generation loop:
(for [[p-name1 {produced-types1 :produced-types}] type-analysis-map
      [p-name2 {consumed-types2 :consumed-types}] type-analysis-map
      :when (not= p-name1 p-name2)
      :let [pairs (matching-type-pairs ancestors-fn produced-types1 consumed-types2)]
      :when (seq pairs)]
  [p-name1 p-name2 pairs])
```

**Deduplication:** `distinct` is applied because a rule may insert the same
fact type in multiple `insert!` calls, and the downstream rule may match it
in multiple LHS conditions.  Without `distinct`, the `:via` vector would
contain duplicate `{:produces Foo :satisfies Foo}` entries.

### 2b. Serialize type-bridge info on `:upstream` / `:downstream`

**Current `ProductionDep`:**
```json
{"name": "my.ns/producer-rule", "ns": "my.ns", "type": "rule"}
```

**Proposed enrichment — symmetric model:**

Both upstream and downstream entries use the same two keys with consistent
semantics.  Whether you see an upstream or downstream entry is determined by
context (the parent key), so the UI inherently knows how to interpret
`producer-type` / `consumer-type` without ambiguity or Union types:

- `producer-type` — the concrete type the inserting rule produces.
- `consumer-type` — the concrete type the consuming rule's LHS requires.

Upstream entry (on consumer Rule Y):
```json
{
  "name": "my.ns/producer-rule",
  "ns": "my.ns",
  "type": "rule",
  "match": [
    {"producer-type": "my.ns.MarkerRecord", "consumer-type": "my.ns.IScanMarker"}
  ]
}
```

Downstream entry (on producer Rule X):
```json
{
  "name": "my.ns/consumer-rule",
  "ns": "my.ns",
  "type": "query",
  "match": [
    {"producer-type": "my.ns.MarkerRecord", "consumer-type": "my.ns.IScanMarker"}
  ]
}
```

When `producer-type` equals `consumer-type` (direct match, no hierarchy),
both are still present.  This avoids client-side special-casing.

`match` is an array because a single pair of productions may be linked by
multiple type pairs (e.g., producer inserts types A and C, consumer reads
B and D where A descends from B and C descends from D).

### 2c. Update `get-production-deps-summary`

This function currently serializes dep-graph entries into `ProductionDep`
vectors.  It must accept the new dep-graph shape and serialize the
`:via` info into `:match` arrays with serialized type strings.

The `:via` entries use the internal keys `:produces` / `:satisfies`.
`get-production-deps-summary` maps them to the symmetric serialized keys:
`producer-type` / `consumer-type`.  This internal/external separation
keeps the dep-graph's internal names aligned with its own semantics while
producing the clean symmetric API shape.

### 2d. Function decomposition

The signature of `build-dep-graph` does not change externally. Internally:

1. Extract `matching-type-pairs` as a helper (with `distinct`).
2. Replace `add-dep-graph-entry` with a version that stores `:via` pairs.
3. Update `get-production-deps-summary` (and its helper `serialize-deps`)
   to serialize the new shape.

### 2e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `build-dep-graph`: enumerate type pairs (with `distinct`), store `:via`. `get-production-deps-summary`: serialize `:via` → `:match` with `producer-type` / `consumer-type` keys. `production-summary`: pass serialized match info through. |
| `server/src/clara/server/tools/graph/serialize.clj` | Helper to serialize type-pair maps from `:produces`/`:satisfies` to `producer-type`/`consumer-type`. `resolve-type` already handles type → string. |
| `server/src/clara/server/graph/api.clj` | Extend `ProductionDep` schema with optional `:match` array. |
| `server/test/clara/server/tools/graph/core_test.clj` | Update `test-dep-graph-full` for new shape. Update `test-dep-graph-hierarchy` for `:via` pairs. Add tests for `:match` on dep links with and without hierarchy, dedup. |
| `ui/src/lib/types/api.ts` | Extend `ProductionReference` with `match?: TypeBridgeMatch[]`. Add `TypeBridgeMatch` interface with `producer-type` and `consumer-type`. |
| `docs/explorer-graph-api.md` | Document `:match` field on upstream/downstream entries with symmetric `producer-type`/`consumer-type` keys. |

### 2f. API backward compatibility

Additive only.  New optional `:match` key on each `ProductionDep` object.
Existing keys (`name`, `ns`, `type`) unchanged.  When `:match` is absent
(no ancestors-fn configured, or direct-only matches), the relationship is
still expressed by the presence of the dep entry itself; the `:match` array
clarifies the specific type bridge for hierarchy cases.

We always include `:match` even when it's a direct match — it normalizes
the consumer's parsing path.

### 2g. Test cases

1. **Direct match (no hierarchy):**
   Rule A inserts `Foo`, Rule B requires `Foo` →
   both directions: `match: [{"producer-type": "Foo", "consumer-type": "Foo"}]`

2. **Single hierarchy jump:**
   Rule A inserts `MarkerRecord`, Rule B requires `IScanMarker` (interface) →
   both directions: `match: [{"producer-type": "MarkerRecord", "consumer-type": "IScanMarker"}]`

3. **Multi-type bridge:**
   Rule A inserts `[A, C]`, Rule B requires `[B, D]` where A→B and C→D via hierarchy →
   both directions: `match: [{"producer-type": A, "consumer-type": B},
                             {"producer-type": C, "consumer-type": D}]`

4. **Production with both direct and hierarchy matches:**
   Rule A inserts `[Foo, MarkerRecord]`, Rule B requires `[Foo, IScanMarker]` →
   both directions: `match: [{"producer-type": "Foo", "consumer-type": "Foo"},
                             {"producer-type": "MarkerRecord", "consumer-type": "IScanMarker"}]`

5. **Deduplication:** Rule A calls `(insert! Foo-1) (insert! Foo-2)`, Rule B
   has two LHS conditions matching `Foo` → `match` still has one entry for `Foo`.

---

## Phase 2.5 — Update Internal Tooling for Dep-Graph Shape Change

Phase 2a changes the internal `:dep-graph` from name→set to name→map.  This
breaks consumers that read the dep-graph directly, including:

- **`analysis.edn` dump** (written by `main.clj` via `pprint`): external
  tooling in other repos (e.g., ruleset repos using babashka scripts) that
  reads `analysis.edn` will encounter the new shape.
- **In-repo tests** (`core_test.clj`): `test-dep-graph-full` does exact
  shape comparison against sets; must be updated to match the new map shape.
  `test-dep-graph-hierarchy` uses `contains?` against the set entries;
  must be updated for the nested `:via` structure.
- **`clara-rules-inspect` skill — `scripts/annotations_report.bb`**
  (`~/.pi/agent/skills/clara-rules-inspect/scripts/annotations_report.bb`):
  the `edges` function (§234) does `(sort (:upstream e))` and
  `(doseq [d (sort (:downstream e))] …)`, treating `:upstream`/`:downstream`
  as flat sets of production-name strings.  After the shape change these become
  maps keyed by production name, so iteration must destructure the map entries
  or extract `(keys e)`.  The output should also include the `:via` type-pair
  info for clarity.

### 2.5a. Files to audit and update

| File | Change |
|------|--------|
| `server/test/clara/server/tools/graph/core_test.clj` | `test-dep-graph-full`: update expected shape to `{:via [...]}` maps. `test-dep-graph-hierarchy`: update to check nested `:via` entries. `test-dependency-graph-correctness`: if it accesses `:dep-graph`, update. |
| `~/.pi/agent/skills/clara-rules-inspect/scripts/annotations_report.bb` | `edges` fn: iterate over map keys instead of set elements; optionally display `:via` type-pair info. |
| External tooling (ruleset repos) | Any other babashka scripts reading `analysis.edn` `:dep-graph` must be updated for the new shape. |

### 2.5b. Checklist items

- [ ] **2.5.1:** Update `test-dep-graph-full` and `test-dep-graph-hierarchy` in `core_test.clj`
- [ ] **2.5.2:** Update `annotations_report.bb` in `clara-rules-inspect` skill for new dep-graph shape
- [ ] **2.5.3:** Audit and update any other external tooling consuming `analysis.edn` `:dep-graph`

---

## Phase 3 (Future) — UI Integration

Once the server API is extended, the UI can use the new fields:

- **Fact-type detail view:** Render the `:ancestors` chain.  Types with
  `known: true` can be hyperlinks to their fact-type detail page; ghost types
  (`known: false`) render as plain text.
- **Rule detail view:** In the upstream/downstream sections, show each dep
  entry with the `:match` details inline (e.g., "Rule X produces
  `MarkerRecord` → satisfies `IScanMarker`").
- **Graph visualization:** Edges could carry type-bridge labels.

This phase is scoped separately and not detailed here.

---

## Implementation Order

- [ ] **Phase 1a:** Thread `ancestors-fn` through `rulebase-analysis` → `build-fact-type-summary-map` (with caching)
- [ ] **Phase 1b:** Add `:ancestors` field (objects with `type`/`known`, lexicographically sorted) to fact-type entries
- [ ] **Phase 1c:** Update `fact-types-list` select-keys to include `:ancestors`
- [ ] **Phase 1d:** Update API schema (`FactTypeSummary`, `AncestorEntry` in `api.clj`)
- [ ] **Phase 1e:** Add server tests for `:ancestors` (shape, ordering, `known` flag, nil case)
- [ ] **Phase 1f:** Update UI types (`FactTypeSummary`, `AncestorEntry` in `api.ts`)
- [ ] **Phase 1g:** Update API documentation (`explorer-graph-api.md`)
- [ ] **Phase 2a:** Extend `build-dep-graph` to capture type-pair `:via` edges (with `distinct`)
- [ ] **Phase 2b:** Update `get-production-deps-summary` to serialize `:via` → `:match` with symmetric `producer-type`/`consumer-type` keys
- [ ] **Phase 2c:** Update `ProductionDep` schema in `api.clj` with optional `:match` array
- [ ] **Phase 2d:** Add server tests for `:match` on dep links (direct, hierarchy, multi-type, dedup)
- [ ] **Phase 2e:** Update UI types (`ProductionReference`, `TypeBridgeMatch` in `api.ts`)
- [ ] **Phase 2f:** Update API documentation (`explorer-graph-api.md`)
- [ ] **Phase 2.5.1:** Update `core_test.clj` tests for new dep-graph shape (`test-dep-graph-full`, `test-dep-graph-hierarchy`)
- [ ] **Phase 2.5.2:** Update `annotations_report.bb` in `clara-rules-inspect` skill for new dep-graph shape
- [ ] **Phase 2.5.3:** Audit and update any other external tooling consuming `analysis.edn` `:dep-graph`
- [ ] **Phase 3:** UI integration (future, scoped separately)
