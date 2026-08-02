# Extend API with Fact Type Hierarchy Details

Design plan for surfacing type-hierarchy information in the explorer API so that
upstream/downstream relationships that cross type-hierarchy boundaries are
self-explanatory without requiring the user to cross-reference fact-type views.

---

## Key Corrections from Critical Review

This revision fixes four findings from reviewing the original plan against the
actual server and clara-rules sources:

1. **`ancestors-fn` is never nil in a real session.** Clara's
   `create-ancestors-fn` (compiler.clj) defaults to `clojure.core/ancestors`
   when the user configures neither `:ancestors-fn` nor `:hierarchy`.  The
   original plan assumed "no hierarchy configured ⇒ `ancestors` is `[]`".
   In reality every Java class fact type has ancestors — for a `defrecord`
   that is ~10–20 entries (`java.lang.Object`, `clojure.lang.IPersistentMap`,
   `java.io.Serializable`, …).  The noise problem and the `known` flag are
   therefore central, not incidental.  (See "System Context" and Phase 1.)

2. **`build-fact-type-summary-map` operates on serialized strings, not raw
   types.**  Its input is the rule/query *summaries*, whose `:lhs-types` /
   `:insert-types` / `:retract-types` are already strings produced by
   `serialize/serialize-fact-type`.  You cannot call `ancestors-fn` on those
   map keys.  Raw types must be threaded through from `rulebase-analysis`.
   (See Phase 1a.)

3. **Phase 2 no longer changes the internal dep-graph shape.**  The original
   plan changed edges from name→set to name→map, which would have silently
   broken `rule-is-sink?` (it filters `:downstream` entries as bare names —
   an internal consumer the plan missed), in addition to the acknowledged
   breakage of tests, `annotations_report.bb`, and `analysis.edn` tooling.
   The `:match` info is fully derivable at serialization time from data that
   already exists, so the shape change is unnecessary.  The revised design
   computes type-bridge pairs in `get-production-deps-summary` and deletes
   old Phase 2.5 entirely.  (See Phase 2.)

4. **Internal naming stays consistent with `internal-analysis-models.md`.**
   The internal model already uses `:consumed-types` / `:produced-types`.
   The original plan introduced a third convention (`:produces` /
   `:satisfies`) plus an internal→external rename layer.  The revised design
   has no internal `:via` structure at all; the serialization helper emits
   `producer-type` / `consumer-type` directly.

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
;; In build-dep-graph (core.clj):
(let [{:keys [ancestors-fn]} (meta get-alphas-fn)] ...)
```

`get-alphas-fn` is a component of the compiled rulebase.  Its metadata carries
`:ancestors-fn` and `:fact-type-fn`.  **Important** (verified against
`clara.rules.compiler/create-ancestors-fn` and `create-get-alphas-fn`):

- The meta `:ancestors-fn` is a *wrapped* fn that returns an empty set for
  internal system facts (`ISystemFact`) and otherwise delegates to the
  session's ancestors-fn.
- The session's ancestors-fn **defaults to `clojure.core/ancestors`** — it is
  non-nil for every real session.  For Java classes, `clojure.core/ancestors`
  returns the *transitive closure* of all superclasses **and all interfaces**.
  For a `defrecord` fact type that is ~10–20 entries, almost all of them
  ghosts (`java.lang.Object`, `clojure.lang.*` interfaces, `Serializable`).
- A hand-built rulebase (unit tests) may lack the meta entirely; only then is
  `ancestors-fn` actually nil.  Precedent for the defensive fallback exists in
  `analyze.clj/build-fallback-type-filter`:
  `(or (-> rulebase :get-alphas-fn meta :ancestors-fn) ancestors)`.

### How `downstream?` works today

```clojure
(defn- downstream? [ancestors-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (and ancestors-fn
           (contains? (set (ancestors-fn inserter-type)) reader-type))))
```

Two cases: (a) direct type match, or (b) the inserter's type has the reader's
type among its ancestors.  This boolean check is used to build the dep-graph
but the *specific pair* that caused a match is not retained.  Note it rebuilds
`(set (ancestors-fn ...))` on every call inside an O(rules² × types²) loop —
memoization target (see Phase 1a).

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

### Where raw vs. serialized types live

- `build-dep-graph`'s internal `type-analysis-map` holds **raw** types
  (`java.lang.Class`, keywords, occasionally symbols):
  `{:consumed-types [...] :produced-types [...]}` per production name.
  `:produced-types` is `(into insert-types retract-types)` — note it
  includes retracts (see "Edge Cases").
- `production-summary` **serializes** types to strings via
  `serialize/serialize-fact-type`.
- `build-fact-type-summary-map` consumes those serialized summaries — its
  keys and values are strings.  Raw types are no longer available at that
  layer and must be passed in (Phase 1a).

---

## Phase 1 — Fact Type Hierarchy Representation

**Goal:** Every fact-type summary shows its ancestors with enough context to
distinguish "real" types from ghost types (ancestors that only appear in
the hierarchy, never in a production's LHS/insert/retract).

### 1a. Thread raw types and a memoized ancestors-fn through the analysis

`build-fact-type-summary-map` works on serialized strings, so `ancestors-fn`
cannot be applied at that layer without raw types.  Restructure:

1. **Hoist the type-analysis-map out of `build-dep-graph`.**  Compute
   `type-analysis-map` (production-name → `{:consumed-types [...]
   :produced-types [...]}`, raw types) once in `rulebase-analysis` and pass
   it to `build-dep-graph`.  This is a pure refactor — same data, computed
   in the caller instead of a `letfn`.  (Phase 2 reuses it to compute
   `:match` pairs at serialization time.)
2. **Memoize the ancestor *set*, once, in `rulebase-analysis`:**
   ```clojure
   (defn- ->memoized-ancestors
     "Returns a memoized fn of raw-type → set of ancestor raw types.
     Never returns nil; tolerant of ancestors-fn returning nil."
     [ancestors-fn]
     (memoize (fn [t] (set (ancestors-fn t)))))
   ```
   Clara's own memoization lives inside `fact-type->roots`, **not** on the
   meta-carried fn, so this memoization is not redundant.  One memoized fn
   serves all three consumers: `downstream?` inside `build-dep-graph`,
   Phase 1's `:ancestors` enrichment, and Phase 2's `matching-type-pairs`.
   Memoizing the *set* (not the seq) also fixes the per-call `(set …)`
   allocation in today's `downstream?`.
3. **Build a serialized-name → ancestors-strings index** in
   `rulebase-analysis`:
   - The raw type universe is `(into #{} (mapcat :consumed-types) …)` ∪
     `(mapcat :produced-types)` over the hoisted type-analysis-map.
   - Serialize each raw type with the same `serialize-fact-type` used by
     `production-summary` (pass `nil` ns-name; consumed/produced types are
     already compiler-resolved Classes or qualified keywords — the ns-arg
     only matters for unresolved symbols).
   - Result: `{serialized-name [{:type "ancestor-name" } ...]}` with raw
     ancestors retained only long enough to serialize them.
   - Collision note: two distinct raw types serializing to the same string
     is practically impossible (fully-qualified class names); last-wins is
     acceptable.
4. Pass this index into `build-fact-type-summary-map`, which attaches
   `:ancestors` to each entry in a **second pass** after the usage map is
   complete (only then is the full set of "known" types known).

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

**The `known` flag is the primary noise filter.**  Because Clara's default
ancestors-fn is `clojure.core/ancestors`, every record type carries a long
tail of ghost JDK/CLJ interfaces.  This is expected and accepted for
Phase 1 (faithful to the requirement "show what ancestors-fn returns"):
`known: false` entries are exactly the entries that can never bridge a
dependency edge (a type only creates an edge if some production consumes
it, which makes it `known`).  The UI should render `known` ancestors
first/prominently.  If payload or noise proves problematic in practice, a
follow-up can add server-side suppression of `java.lang.Object` /
`clojure.lang.*` ghosts — deliberately **not** in Phase 1 to keep the
semantics simple and faithful.

Additional rules:

- `ancestors` is **deterministically sorted lexicographically** on the
  serialized `type` string.  `clojure.core/ancestors` returns an unordered
  set, and depth-ordering is not recoverable from a transitive set without
  re-walking the hierarchy — lexicographic sort guarantees stable API
  responses.  (UI-layer reordering/filtering based on `known` is a
  Phase 3 concern.)
- The concrete type itself (the `name` key) is NOT included in the
  `ancestors` array.
- `ancestors` is the direct result of `(ancestors-fn type)` — not a
  recursive closure (though the default ancestors-fn is already
  transitive).  Each ancestor may itself appear as a fact type in the map
  with its own `ancestors` field, so the full hierarchy is reconstructible.
- When the rulebase has no `:ancestors-fn` meta (hand-built rulebases in
  tests) or the fn returns nil, `ancestors` is `[]`.

### 1c. Fact types with no recorded usage ("ghost types")

Fact types that appear ONLY as ancestors of other types need not appear in
the fact-types map.  **Decision for Phase 1 (unchanged):** do NOT add such
"ghost" types to the `fact-types` map.  They appear only in `ancestors`
arrays with `"known": false`.  This gives the UI enough information to
render them as plain text rather than broken links.  If this proves
insufficient, a follow-up can compute transitive closure.

### 1d. List endpoint stays lightweight

The original plan added `:ancestors` to `fact-types-list` (the
`/v1/fact-types` list payload).  **Revised decision: do not.**  The list
endpoint is deliberately lightweight (same rationale as `rules-list`
omitting `:upstream`/`:downstream` — payload weight at 3k+ rules), and
`:ancestors` is the heaviest field this plan adds (10–20 entries per type
with the default ancestors-fn).  The **detail** endpoint
(`handle-get-fact-type`) serves the full entry from the analysis map and
gets `:ancestors` for free once it is in `build-fact-type-summary-map`.

Consequence: `FactTypeListItem` schema and `fact-types-list` select-keys
are unchanged; only the detail schema gains the optional key.

### 1e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `rulebase-analysis`: extract ancestors-fn (with `analyze.clj`-style fallback), build memoized ancestor-set fn, hoist `type-analysis-map`, build serialized ancestors index. `build-fact-type-summary-map`: accept the index, enrich each entry with `:ancestors` in a second pass. |
| `server/src/clara/server/tools/graph/serialize.clj` | Helper to serialize one raw ancestor type into `{:type ... :known ...}` shape (known flag applied by caller). `resolve-type` already handles type → string. |
| `server/src/clara/server/graph/api.clj` | Add `AncestorEntry` schema. Add `(s/optional-key :ancestors)` to the **detail** fact-type schema only (not `FactTypeListItem`). |
| `server/test/clara/server/tools/graph/core_test.clj` | Tests verifying `:ancestors` shape with `:known` flag, deterministic ordering, default-ancestors noise behavior, and missing-meta case. |
| `ui/src/lib/types/api.ts` | Add `AncestorEntry` interface. Add `ancestors?: AncestorEntry[]` to the detail fact-type type. |
| `docs/explorer-graph-api.md` | Document the new `:ancestors` field with `type`/`known` keys on the detail view. |

### 1f. API backward compatibility

Additive only.  New optional `:ancestors` key on fact-type **detail**
objects.  Existing keys and the list payload unchanged.

### 1g. Test cases

1. **Default ancestors-fn (no user hierarchy):** a `defrecord` fact type
   yields a non-empty `:ancestors` containing `java.lang.Object` and
   `clojure.lang.*` interfaces, all `known: false`; an ancestor that some
   rule has on its LHS is `known: true`.  *(Replaces the original plan's
   invalid "no hierarchy ⇒ `[]`" case — Clara always supplies
   `clojure.core/ancestors`.)*
2. **Missing meta (hand-built rulebase):** no `:ancestors-fn` in
   `get-alphas-fn` meta → fallback yields `[]` (or the
   `clojure.core/ancestors` fallback — pick one behavior and pin it in the
   test; recommend matching `analyze.clj`'s fallback for consistency).
3. **Clojure `derive` hierarchy:**
   ```clojure
   (derive ::child ::parent)
   (derive ::parent ::grandparent)
   ```
   → `::child` ancestors include both `::parent` and `::grandparent`
   (transitive), `known` reflecting usage.
4. **Java interface hierarchy:** `MarkerRecord` implements `IScanMarker`,
   a rule has `IScanMarker` on its LHS →
   `{:type "my.ns.IScanMarker" :known true}` present, JDK ghosts
   `known: false`.
5. **Deterministic ordering:** output stable across calls even though
   `clojure.core/ancestors` returns an unordered set.
6. **Memoization:** ancestors-fn invoked at most once per unique raw type
   across the whole analysis (fact-type enrichment + dep-graph build).
7. **nil/throwing ancestors-fn:** a user ancestors-fn returning nil yields
   `[]` for that type (no NPE).  Optionally guard with try/catch as
   `build-fallback-type-filter` does.

---

## Phase 2 — Type-Bridge Info on Production Dep Links

**Goal:** Each `:upstream` / `:downstream` entry on a rule/query summary
carries enough information to show *which* produced type satisfies *which*
required type, and whether the match is direct or through hierarchy.

### 2a. Do NOT change the internal dep-graph shape — compute pairs at serialization time

The original plan changed dep-graph edges from name→set to name→map with a
`:via` vector.  That change is **unnecessary and harmful**:

- It silently breaks `rule-is-sink?` (core.clj), which filters
  `:downstream` entries as bare production names — with map values it
  would call `(get production-map [name {:via …}])`, get nil, and mark
  every rule a sink.  The original plan's consumer audit missed this.
- It breaks `test-dep-graph-full`, `test-dep-graph-hierarchy`,
  `annotations_report.bb`, and any `analysis.edn` consumer (old
  Phase 2.5).

The `:match` info is a pure function of data that already exists:
`(matching-type-pairs ancestors-set-fn produced-types consumed-types)`
for an adjacent (producer, consumer) pair.  Compute it where it is
serialized — in `get-production-deps-summary` — using the hoisted
`type-analysis-map` and the memoized ancestor-set fn from Phase 1a.

```clojure
(defn- matching-type-pairs
  "All (produced, consumed) raw-type pairs linking producer to consumer.
  Direct matches included (producer-type = consumer-type)."
  [ancestors-set-fn produced-types consumed-types]
  (->> (for [pt produced-types
             ct consumed-types
             :when (downstream? ancestors-set-fn pt ct)]
         {:producer-type pt :consumer-type ct})
       (distinct)))

;; `downstream?` simplifies — the memoized fn already returns a set:
(defn- downstream? [ancestors-set-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (contains? (ancestors-set-fn inserter-type) reader-type)))
```

Benefits over the shape change:

- **Zero breakage.**  `:dep-graph`, `rule-is-source?`, `rule-is-sink?`,
  tests, `analysis.edn`, and external tooling are all untouched.
  Old Phase 2.5 is deleted.
- **Cheaper.**  Pairs are computed only for actual edges, not for every
  candidate pair in the O(n²) loop.  (`build-dep-graph` keeps its current
  boolean `some-type-consumed?` check, now backed by the memoized set fn.)
- **No internal/external naming divergence.**  No `:via`/`:produces`/
  `:satisfies` layer; the serialized keys are produced directly.
  Internal naming stays aligned with `internal-analysis-models.md`
  (`:produced-types` / `:consumed-types`).

**Trade-off (accepted):** the `analysis.edn` dump's `:dep-graph` will not
carry type-pair info.  If external tooling later wants it, add a separate
top-level `:dep-bridges` key to the analysis output — additive, no shape
change.  Not in scope now.

### 2b. Serialize type-bridge info on `:upstream` / `:downstream`

**Current `ProductionDep`:**
```json
{"name": "my.ns/producer-rule", "ns": "my.ns", "type": "rule"}
```

**Proposed enrichment — symmetric model (unchanged from original plan):**

Both upstream and downstream entries use the same two keys with consistent
semantics.  Whether you see an upstream or downstream entry is determined by
context (the parent key), so the UI inherently knows how to interpret
`producer-type` / `consumer-type` without ambiguity or union types:

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

The `match` array is **deterministically ordered**: sorted lexicographically
by `producer-type`, then `consumer-type`, applied after `resolve-type`
converts raw types to comparable strings (raw types may be Classes or
keywords — neither consistently implements `Comparable`; `sort-by` on them
would throw `ClassCastException`).

### 2c. Update `get-production-deps-summary`

This function currently takes `[dep-graph production-name production-map]`
and serializes the name sets.  Revised signature: it also receives the
`type-analysis-map` and the memoized ancestor-set fn.  For each adjacent
production name in `:upstream` / `:downstream`:

1. Look up both ends' `:produced-types` / `:consumed-types` in
   `type-analysis-map` (upstream name is the producer, current production
   is the consumer — and vice versa for `:downstream`).
2. Compute `matching-type-pairs`, serialize each pair's raw types with
   `resolve-type`, sort lexicographically, attach as `:match` on the
   serialized `ProductionDep`.

The internal keys `:producer-type` / `:consumer-type` are already
kebab-case, so serialization is just `resolve-type` on the values — no
rename layer.  `production-summary` passes the two new args through (they
are available in `rulebase-analysis` scope; thread them through
`build-production-summary-map` alongside `dep-graph`).

**Deduplication:** `distinct` handles a rule inserting the same type in
multiple `insert!` calls, or a consumer matching the same type in multiple
LHS conditions.

### 2d. Function decomposition

1. Hoist `type-analysis` / `type-analysis-map` from `build-dep-graph` into
   `rulebase-analysis` (pure refactor).
2. `->memoized-ancestors` helper (Phase 1a).
3. `matching-type-pairs` helper (with `distinct`).
4. `get-production-deps-summary` gains the two args and a
   `serialize-match` sub-helper in `serialize.clj`.

### 2e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | Hoist `type-analysis-map`. `get-production-deps-summary`: compute and attach `:match`. `production-summary` / `build-production-summary-map`: thread new args. `build-dep-graph`: use memoized ancestor-set fn (behavior unchanged). |
| `server/src/clara/server/tools/graph/serialize.clj` | `serialize-match` helper: raw pair → sorted `producer-type`/`consumer-type` string maps. |
| `server/src/clara/server/graph/api.clj` | Extend `ProductionDep` schema with `(s/optional-key :match)` array of `{producer-type, consumer-type}`. |
| `server/test/clara/server/tools/graph/core_test.clj` | Add tests for `:match` on dep links (direct, hierarchy, multi-type, dedup, symmetric both-directions). Existing dep-graph shape tests **stay green unchanged** — this is the proof the simplification works. |
| `ui/src/lib/types/api.ts` | Extend `ProductionReference` with `match?: TypeBridgeMatch[]`. Add `TypeBridgeMatch` interface. |
| `docs/explorer-graph-api.md` | Document `:match` field on upstream/downstream entries. |

### 2f. API backward compatibility

Additive only — now at **both** levels: the API gains an optional `:match`
key, and the internal `:dep-graph` shape (consumed by `analysis.edn`
tooling) is untouched.  `:match` is always present when the pair links via
at least one type pair (direct matches included), which normalizes the
consumer's parsing path.

### 2g. Test cases

1. **Direct match (no hierarchy):**
   Rule A inserts `Foo`, Rule B requires `Foo` →
   both directions: `match: [{"producer-type": "Foo", "consumer-type": "Foo"}]`

2. **Single hierarchy jump:**
   Rule A inserts `MarkerRecord`, Rule B requires `IScanMarker` (interface) →
   both directions: `match: [{"producer-type": "MarkerRecord", "consumer-type": "IScanMarker"}]`

3. **Multi-type bridge:**
   Rule A inserts `[A, C]`, Rule B requires `[B, D]` where A→B and C→D via
   hierarchy → both directions: two match entries, sorted.

4. **Both direct and hierarchy matches:**
   Rule A inserts `[Foo, MarkerRecord]`, Rule B requires
   `[Foo, IScanMarker]` → two entries.

5. **Deduplication:** Rule A calls `(insert! Foo-1) (insert! Foo-2)`, Rule B
   has two LHS conditions matching `Foo` → one entry for `Foo`.

6. **Symmetry:** the `match` array on Y's upstream entry for X is identical
   to the one on X's downstream entry for Y.

7. **Regression:** `test-dep-graph-full`, `test-dep-graph-hierarchy`,
   `rule-is-source?`/`rule-is-sink?` behavior all unchanged (dep-graph shape
   untouched).

---

## Edge Cases Checklist (both phases)

- **Default-ancestors noise:** every record type has 10–20 ghost ancestors.
  Accepted for Phase 1; `known` flag is the filter signal; possible
  follow-up suppression of `java.lang.Object` / `clojure.lang.*`.
- **`ancestors-fn` never nil in real sessions;** nil only for hand-built
  rulebases.  Use the `analyze.clj`-style fallback
  `(or meta-ancestors-fn ancestors)` for consistency.
- **User ancestors-fn returning nil or throwing:** treat as `[]` / guard
  (precedent: `build-fallback-type-filter` wraps in try/catch).
- **Retract types are in `:produced-types`** (pre-existing:
  `(into insert-types retract-types)`).  A dep edge created by a *retract*
  will carry a `:match` whose `producer-type` is the retracted type —
  "producer" wording is imperfect there, but the coupling is real (a
  retraction can invalidate downstream joins) and matches today's edge
  semantics.  Documented as a known limitation; a future `:match` entry
  flag (e.g. `via: "retract"`) is out of scope.
- **Self-edges excluded** (`(not= p-name1 p-name2)`): a rule that inserts
  and reads the same type gets no edge and no `:match` — pre-existing,
  unchanged.
- **Keyword hierarchies:** `derive`-based ancestors are transitive;
  keywords without `derive` have none.  Lexicographic sort loses depth
  order — accepted (documented in 1b).
- **Sorting raw types throws:** ordering only ever applied post-
  serialization, on strings.
- **Symbol serialization of ancestors:** ancestors are compiler-resolved
  Classes or qualified keywords in practice; serialize with `nil` ns-name.
- **Payload weight:** `:ancestors` on detail only (1d); `:match` only on
  detail views (list endpoints already omit `:upstream`/`:downstream`).
- **Queries as consumers:** queries appear only on the `:upstream` side of
  their entries; `matching-type-pairs` handles them identically (they have
  `:consumed-types`, empty `:produced-types`).

---

## Phase 3 (Future) — UI Integration

Once the server API is extended, the UI can use the new fields:

- **Fact-type detail view:** Render the `:ancestors` chain, `known` types
  first.  Types with `known: true` hyperlink to their fact-type detail
  page; ghost types (`known: false`) render as plain text.
- **Rule detail view:** In the upstream/downstream sections, show each dep
  entry with the `:match` details inline (e.g., "Rule X produces
  `MarkerRecord` → satisfies `IScanMarker`").
- **Graph visualization:** Edges could carry type-bridge labels.

This phase is scoped separately and not detailed here.

---

## Implementation Order

- [ ] **Phase 1a:** In `rulebase-analysis`: extract ancestors-fn (with fallback), add `->memoized-ancestors`, hoist `type-analysis-map` out of `build-dep-graph`, build serialized ancestors index
- [ ] **Phase 1b:** Add `:ancestors` field (objects with `type`/`known`, lexicographically sorted) to fact-type entries in `build-fact-type-summary-map` (second pass)
- [ ] **Phase 1c:** Update detail fact-type schema (`AncestorEntry` in `api.clj`); leave `FactTypeListItem` and `fact-types-list` unchanged
- [ ] **Phase 1d:** Add server tests for `:ancestors` (default-ancestors noise, `known` flag, ordering, missing-meta, nil-returning fn, memoization)
- [ ] **Phase 1e:** Update UI types (`AncestorEntry`, detail `FactTypeSummary` in `api.ts`)
- [ ] **Phase 1f:** Update API documentation (`explorer-graph-api.md`)
- [ ] **Phase 2a:** Add `matching-type-pairs` helper; simplify `downstream?` to consume the memoized set fn
- [ ] **Phase 2b:** Update `get-production-deps-summary` (+ `serialize-match` in `serialize.clj`) to attach `:match` with symmetric `producer-type`/`consumer-type` keys, sorted post-serialization
- [ ] **Phase 2c:** Update `ProductionDep` schema in `api.clj` with optional `:match` array
- [ ] **Phase 2d:** Add server tests for `:match` (direct, hierarchy, multi-type, dedup, symmetry, dep-graph regression)
- [ ] **Phase 2e:** Update UI types (`ProductionReference`, `TypeBridgeMatch` in `api.ts`)
- [ ] **Phase 2f:** Update API documentation (`explorer-graph-api.md`)
- [ ] **Phase 3:** UI integration (future, scoped separately)

*(Deleted: old Phase 2.5 — no longer needed since the dep-graph shape is
unchanged.  If `analysis.edn` consumers later need type-pair info, add an
additive `:dep-bridges` top-level key as separate work.)*
