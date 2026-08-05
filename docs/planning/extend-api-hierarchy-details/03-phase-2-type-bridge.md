## Phase 2 — Type-Bridge Info on Production Dep Links

**Goal:** Each `:upstream` / `:downstream` entry on a rule/query summary
carries enough information to show *which* produced type satisfies *which*
required type, and whether the match is direct or through hierarchy.

### 2a. Dep-graph shape is unchanged — pairs are computed at serialization time

The internal dep-graph keeps its current shape (name → `{:upstream #{…}
:downstream #{…}}` sets of names).  Changing it is unnecessary and
harmful:

- `rule-is-sink?` (core.clj) filters `:downstream` entries as bare
  production names; a richer edge value would break it.
- `test-dep-graph-full`, `test-dep-graph-hierarchy`,
  `annotations_report.bb`, and any `analysis.edn` consumer read the
  current shape.

The `:match` info is a pure function of data that already exists:
`(matching-type-pairs ancestors-set-fn produced-types consumed-types)`
for an adjacent (producer, consumer) pair.  It is computed where it is
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

;; `downstream?` — the memoized fn already returns a set:
(defn- downstream? [ancestors-set-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (contains? (ancestors-set-fn inserter-type) reader-type)))
```

Properties of this approach:

- **Zero breakage.**  `:dep-graph`, `rule-is-source?`, `rule-is-sink?`,
  tests, `analysis.edn`, and external tooling are all untouched.
- **Cheaper.**  Pairs are computed only for actual edges, not for every
  candidate pair in the O(n²) loop.  (`build-dep-graph` keeps its current
  boolean `some-type-consumed?` check, backed by the memoized set fn.)
- **No internal/external naming divergence.**  The serialized keys are
  produced directly; internal naming stays aligned with
  `internal-analysis-models.md` (`:produced-types` / `:consumed-types`).

Consequence: the `analysis.edn` dump's `:dep-graph` does not carry
type-pair info.  If external tooling later wants it, add a separate
top-level `:dep-bridges` key to the analysis output — additive, no shape
change.  Out of scope here.

Note on `analysis.edn` shape: the dump ppprints the full analysis map,
so rule/query/fact-type summaries in it DO gain the new fields
(`:id`, `:ns`, `:ancestors`, and `:match` on dep entries after
Phase 2) — additive only; the `:dep-graph` key itself is unchanged.
EDN consumers reading specific keys are unaffected.

### 2b. Serialize type-bridge info on `:upstream` / `:downstream`

**Current `ProductionDep`:**
```json
{"name": "my.ns/producer-rule", "ns": "my.ns", "type": "rule"}
```

**Enrichment — symmetric model:**

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
    {
      "producer-type": {"name": "my.ns.MarkerRecord", "id": "my.ns.MarkerRecord-a1b2c3d4", "known": true},
      "consumer-type": {"name": "my.ns.IScanMarker", "id": "my.ns.IScanMarker-b2c4d6e8", "known": true}
    }
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
    {
      "producer-type": {"name": "my.ns.MarkerRecord", "id": "my.ns.MarkerRecord-a1b2c3d4", "known": true},
      "consumer-type": {"name": "my.ns.IScanMarker", "id": "my.ns.IScanMarker-b2c4d6e8", "known": true}
    }
  ]
}
```

Each match value is a `TypeReference` (see System Context) — always
`known: true` here, since a bridged type is produced/consumed by
construction — so match rows are directly linkable with no lookup.
When `producer-type` and `consumer-type` name the same type (direct
match, no hierarchy), both are still present.  This avoids client-side
special-casing.

`match` is an array because a single pair of productions may be linked by
multiple type pairs (e.g., producer inserts types A and C, consumer reads
B and D where A descends from B and C descends from D).

The `match` array is **deterministically ordered**: sorted by
`producer-type` `:name`, then `consumer-type` `:name` — the
kind-explicit serialized strings inside each `TypeReference` (raw types
may be Classes or keywords — neither consistently implements
`Comparable`; `sort-by` on them would throw `ClassCastException`).

### 2c. Update `get-production-deps-summary`

This function currently takes `[dep-graph production-name production-map]`
and serializes the name sets.  It also receives the `type-analysis-map`
and the memoized ancestor-set fn.  For each adjacent production name in
`:upstream` / `:downstream`:

1. Look up both ends' `:produced-types` / `:consumed-types` **and
   `:ns-name`** in `type-analysis-map` (upstream name is the producer,
   current production is the consumer — and vice versa for
   `:downstream`).
2. Compute `matching-type-pairs`, serialize each pair's raw types with
   `serialize-type-ref` — **each end in its own ns context**:
   `(serialize-type-ref known-set producer-ns pt)` and
   `(serialize-type-ref known-set consumer-ns ct)`.
   Symbols reach `:produced-types` via EDN sidecar annotations, so
   serializing both ends with the current production's ns would
   misresolve the far end's symbols (e.g. producer's `foo` rendered
   `symbol[ns.b/foo]` instead of `symbol[ns.a/foo]`) and diverge from
   the `:insert-types` entry on the producer's own summary.  Sort by
   `:name`, attach as `:match` on the serialized `ProductionDep`.

**Cross-field consistency invariant:** `:match`'s `producer-type`
`:name` must string-equal the `:name` of the corresponding entry in
the producer's own `:insert-types`/`:retract-types`, and
`consumer-type` `:name` must string-equal the `:name` of the
corresponding entry in the consumer's `:lhs-types` — guaranteed by
using each end's own `:ns-name` (the same context `production-summary`
uses) and pinned in a test.

The internal keys `:producer-type` / `:consumer-type` are already
kebab-case, so serialization is just `resolve-type` on the values — no
rename layer.  `production-summary` passes the two new args through.
The full threading chain is: `rulebase-analysis` →
`build-rule-summary-map` / `build-query-summary-map` →
`build-production-summary-map` → `production-summary` →
`get-production-deps-summary` (alongside the existing `dep-graph` and
`production-map` args).

**Deduplication:** the dedup key is the raw `(producer-type,
consumer-type)` pair — structural, not hierarchy-path-based.  `distinct`
handles a rule inserting the same type in multiple `insert!` calls, a
consumer matching the same type in multiple LHS conditions, and
multi-path ancestry (`A → C` appears once even when `A` also descends
from an intermediate `Mid`; if the consumer reads both `Mid` and `C`,
those are two distinct, correct pairs).

### 2d. Function decomposition

1. Hoist `type-analysis` / `type-analysis-map` from `build-dep-graph` into
   `rulebase-analysis` (pure refactor, Phase 1a).
2. `->memoized-ancestors` helper (Phase 1a).
3. `matching-type-pairs` helper (with `distinct`).
4. `get-production-deps-summary` gains the new args (type-analysis-map,
   ancestors-set fn, known set) and a
   `serialize-match` sub-helper in `serialize.clj`:
   ```clojure
   (defn serialize-match
     "Serializes raw {:producer-type ... :consumer-type ...} pairs
     (matching-type-pairs output) into TypeReference pairs, each end
     in its own ns context, sorted by producer then consumer name."
     [raw-pairs known-set producer-ns consumer-ns]
     (->> raw-pairs
          (map (fn [{:keys [producer-type consumer-type]}]
                 {:producer-type (serialize-type-ref known-set producer-ns producer-type)
                  :consumer-type (serialize-type-ref known-set consumer-ns consumer-type)}))
          (sort-by (juxt (comp :name :producer-type)
                         (comp :name :consumer-type)))
          (vec)))
   ```

### 2e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | Hoist `type-analysis-map`. `get-production-deps-summary`: compute and attach `:match`. `production-summary` / `build-production-summary-map`: thread new args. `build-dep-graph`: use memoized ancestor-set fn (behavior unchanged). |
| `server/src/clara/server/tools/graph/serialize.clj` | `serialize-match` helper: raw pair → sorted `producer-type`/`consumer-type` `TypeReference` pairs. |
| `server/src/clara/server/graph/api.clj` | Extend `ProductionDep` schema with `(s/optional-key :match)` array of `{producer-type, consumer-type}` `TypeReference` pairs. Schema docstring states the symmetric semantics: identical shape and meaning on upstream and downstream entries — `producer-type` is what the producing rule inserts, `consumer-type` is what the consuming rule's LHS requires. |
| `server/test/clara/server/tools/graph/core_test.clj` | Add tests for `:match` on dep links (direct, hierarchy, multi-type, dedup, symmetric both-directions). Existing dep-graph shape tests stay green unchanged — confirming zero internal breakage. |
| `ui/src/lib/types/api.ts` | Extend `ProductionReference` with `match?: TypeBridgeMatch[]`. Add `TypeBridgeMatch` interface (`producer-type`/`consumer-type`: `TypeReference`). |
| `docs/explorer-graph-api.md` | Document `:match` field on upstream/downstream entries. |

### 2f. API compatibility

Additive at **both** levels: the API gains an optional `:match` key, and
the internal `:dep-graph` shape (consumed by `analysis.edn` tooling) is
untouched.  `:match` is always present when the pair links via at least
one type pair (direct matches included), which normalizes the consumer's
parsing path.

### 2g. Test cases

In the examples below, match values are shown as bare strings for
readability — each actually stands for a `TypeReference` whose `:name`
is that string (`:id` and `known: true` elided).

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

7. **Cross-field consistency:** `:match` `producer-type` `:name` equals
   the `:name` of the corresponding entry in the producer's own
   `:insert-types`; `consumer-type` `:name` equals the `:name` of the
   corresponding entry in the consumer's own `:lhs-types` — including a
   case where a symbol insert-type comes from a sidecar annotation in a
   different ns than the consumer.
8. **Regression:** `test-dep-graph-full`, `test-dep-graph-hierarchy`,
   `rule-is-source?`/`rule-is-sink?` behavior all unchanged (dep-graph shape
   untouched).

---

