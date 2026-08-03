# Plan Review: Extend API with Fact Type Hierarchy Details

## Summary

The plan correctly identifies the core problem and the four key corrections from
an earlier iteration. The two-phase decomposition — ancestors on fact types
first, production match bridges second — is sound. The decision not to change
the internal dep-graph shape is correct given the `rule-is-sink?` dependency and
the BB/`analysis.edn` consumers.

That said, the plan has several underspecified areas, ordering concerns, and
scope issues that could cause implementation friction. This review covers 18
findings, roughly ordered from most-to-least critical.

---

## Finding 1 — Kind-explicit serialization is underspecified

The plan repeatedly references "kind-explicit serialization" as a way to
disambiguate display of different fact-type kinds (classes, keywords, symbols,
tuples, strings). But it never defines the exact serialization format for each
kind.

Current `resolve-type` produces:

| Input | Output |
|-------|--------|
| `class` | `"my.ns.Type"` |
| `keyword` | `":my.ns/child"` (via `(-> x symbol str)`) |
| `symbol` | `"my.ns/foo"` |
| `string` | unchanged |

The plan implies that after the change, keyword types would be displayed as
`:my.ns/child` everywhere. What about symbols — do they get a `#'` prefix or
remain ambiguous with class name strings? What about tuples? What about plain
strings used as fact types?

The `:name` field on `FactTypeListItem` currently holds the serialized form.
Changing its format is a **breaking API change** for the UI and any external
consumers. The plan should include a table defining the kind-explicit form for
every fact-type kind, and note that this is a breaking change.

**Recommendation:** Before implementing Phase 1a, define a `serialize-type/fact-type->kind-explicit-str` function with clear contracts for each input kind. Add this to the plan's Phase 1a tasks.

---

## Finding 2 — Route-id migration scope is mixed with ancestors work

Phase 1g bundles a major infrastructure change — replacing `:fq-name` URL
parameters with opaque `:id` values across all endpoints — into the same phase
as the ancestors feature. These are independent changes:

- **Fact-type ancestors** (Phases 1a–1d): needed to populate the `:ancestors` key with `AncestorEntry` objects that link via `:id`
- **Route ids** (Phase 1g): needed because `AncestorEntry` needs an `:id` to link to

The plan acknowledges this dependency but understates the blast radius. Phase 1g touches:
- Every API endpoint (rule, query, fact-type, session routes)
- `serialize-production-dep` serialization
- UI routing and all link builders
- `fq-name-from-param` deletion
- Test fixtures that construct URLs

**Recommendation:** Either split Phase 1g into its own phase group (1g-i through 1g-iv) to make the scope explicit, or annotate it with a cost estimate so reviewers know this is ~30–40% of the total work.

---

## Finding 3 — `build-fact-type-summary-map` raw-type threading is not designed

The plan correctly identifies that `build-fact-type-summary-map` receives
serialized strings and cannot call `ancestors-fn` on them. It says "raw types
must be threaded through from `rulebase-analysis`" but does not specify how.

Currently `build-fact-type-summary-map` takes only `rules` and `queries` (the
already-serialized summary maps). To thread raw types through, it needs at minimum:

1. A mapping from serialized type name → raw type (for reverse lookup)
2. The memoized ancestors function
3. Access to the namespace context for serializing each ancestor

This requires either:
- Changing the signature of `build-fact-type-summary-map` to accept additional data structures
- Pre-computing ancestors in `rulebase-analysis` and passing them along with serialized summaries
- A two-pass approach: first build the summaries, then enrich with ancestors in a second pass

**Recommendation:** The plan should specify the revised data flow in Phase 1a–1b. A concrete suggestion: in `rulebase-analysis`, after building `type-analysis-map` (which has raw types), build a `type-ancestors-index` — `{serialized-type-name -> [{:type kind-explicit-str, :known bool, :id route-id}]}` — and pass it into a revised `build-fact-type-summary-map`.

---

## Finding 4 — Memoized-ancestors refactor order suboptimal

The plan introduces `->memoized-ancestors` in Phase 1a but defers the
`downstream?` simplification to Phase 2a. These should be done together:

- Phase 1a builds the memoized ancestors function
- `downstream?` currently does `(contains? (set (ancestors-fn inserter-type)) reader-type)`
- With memoization, you'd want `(contains? (memoized-ancestors-set inserter-type) reader-type)` where the set is also memoized
- Phase 2a's `matching-type-pairs` helper would build on the same memoized function

**Recommendation:** Move the `downstream?` simplification (consuming the memoized set fn) into Phase 1a. Phase 2a then just adds the `matching-type-pairs` helper on top.

---

## Finding 5 — Phase 2 `get-production-deps-summary` needs new inputs

Phase 2b says to update `get-production-deps-summary` to attach `:match` with
`producer-type`/`consumer-type` keys. But `get-production-deps-summary` currently
only receives `dep-graph`, `production-name`, and `production-map`. To compute
which specific types bridge the hierarchy, it also needs:

- The `type-analysis-map` (which production produces/consumes which raw types)
- The `ancestors-fn` (to determine hierarchy relationships)
- Namespace contexts for serializing types

The plan should specify how these additional data structures are threaded into
`get-production-deps-summary`. The cleanest approach is to pass a pre-computed
`match-index` — a map from `[producer-name consumer-name]` → set of
`[produced-type consumed-type]` pairs — built once in `rulebase-analysis`.

**Recommendation:** Add a `matching-type-pairs` computation in `rulebase-analysis` that produces a `match-index`, then thread it into `get-production-deps-summary` via the function signature. The serialization of match entries happens inside `get-production-deps-summary` as described.

---

## Finding 6 — `known` flag definition needs tightening

The plan says `known: true` means the ancestor type was an explicitly-declared
fact type in the rulebase. It says to check against "the set of all fact types
seen across LHS/insert/retract declarations." This is mostly clear, but two edge
cases:

1. If type `A` is an ancestor of type `B`, and type `B` is used on an LHS but
   type `A` is never used directly on any LHS/insert/retract — `known: false` for
   `A`. That's correct.

2. But what about types like `java.lang.Object` which will appear for every
   `defrecord`? These will be `known: false` with no `:id`, which is correct.
   However, there could be hundreds of these junk entries. The plan says the
   `:ancestors` field should be "deterministic topological order with
   lexicographic tie-break + cycle guard." This ordering is important because
   the display will show them sorted, and `java.io.Serializable` before
   `java.lang.Object` (or after) could be confusing.

**Recommendation:** Define `known` explicitly: a type is `known` iff it appears in the set of fact-type names returned by `keys` of `build-fact-type-summary-map` (i.e., it has a dedicated fact-type summary entry). This is the simplest definition. Also consider whether ghost types should be grouped separately from known types in the display ordering.

---

## Finding 7 — Performance: ancestors precomputation cost

For a typical Java `defrecord`, `clojure.core/ancestors` returns 10–20 entries
(`java.lang.Object`, `clojure.lang.IPersistentMap`, `clojure.lang.IHashEq`,
`java.io.Serializable`, `clojure.lang.IType`, `clojure.lang.IRecord`, etc.).
For a rulebase with 100 fact types, computing ancestors for each type plus its
ancestors (recursively to determine ordering) could be expensive. The plan
mentions memoization (good) and topological ordering with cycle guard (good),
but doesn't discuss:

- Whether the ancestors index should be computed eagerly during `rulebase-analysis` or lazily on demand
- Whether the full transitive hierarchy closure is needed for every fact type, or just the immediate ancestors
- Performance budgets for large rulebases (the problem statement mentions "3k+ rules" in `rules-list`)

**Recommendation:** Add a note to Phase 1a/1b acknowledging the performance concern and confirming the eager approach is acceptable for the target scale. Consider a `:lazy-ancestors` option if performance issues arise.

---

## Finding 8 — Fact-type kind diversity in tests

The existing test rulebases (`loan-doc-rules`, `loan-app-rules`) only use two
kinds of fact types: Java classes (defrecord-based) and plain keywords
(`:loan-doc-rules/document-check-input`, `:extract-doc-meta`). The hierarchy
test (`test-dep-graph-hierarchy`) uses `derive` with keyword types.

The plan's Phase 1d mentions "mixed-kind hierarchy" testing, which is good, but
the test scenarios should also cover:
- Symbol fact types that don't resolve to classes (keyword-like but symbol-shaped)
- Tuple fact types (if supported by the Clara session under test)
- Fact types with zero ancestors (primitives, or when `ancestors-fn` returns `nil`)
- `nil`-returning `ancestors-fn` edge case (the plan mentions this in Phase 1d)

**Recommendation:** Add explicit test-case checklist items to Phase 1d covering these scenarios. Ensure the test rulebase includes at least one mixed-kind hierarchy (a class type deriving from an interface via Java type hierarchy, and a keyword type deriving via `clojure.core/derive`).

---

## Finding 9 — Match dedup semantics not fully specified

Phase 2d mentions "dedup" in tests. The plan should be explicit: what
constitutes a duplicate `:match` entry?

Consider: Rule X produces types `[A, B]` and Rule Y consumes types `[C, D]`.
If `A → C` and `B → D` are hierarchy matches, the `:match` array has two
entries — that's correct. If `A → C` could be reached through multiple
hierarchy paths (e.g., `A extends Mid extends C` — should `A → C` appear once
or twice? Via `A → Mid → C`, only the direct bridge `(A, C)` matters.

The dedup key should be `(producer-type, consumer-type)` — the pair of
canonical serialized type strings. This is a structural dedup, not a
hierarchy-path dedup.

**Recommendation:** Explicitly define the dedup key as the `(producer-type, consumer-type)` pair in the `:match` array spec. Confirm this in Phase 2d tests.

---

## Finding 10 — Phase ordering: docs and UI updates trail server work

The plan orders phases as:
- Phase 1a–1d: server changes
- Phase 1e: UI types
- Phase 1f: API docs
- Phase 1g: route ids (more server work)

The API documentation should be updated alongside the server changes, not after
UI types. The docs inform both the UI developer and external consumers about the
new contract. By the time Phase 1e starts, the API doc should already reflect
the new shapes.

**Recommendation:** Reorder: move Phase 1f to right after Phase 1d (before UI types). Similarly, move Phase 2f to right after Phase 2d. The docs hygiene pass can remain at the end as a verification step.

---

## Finding 11 — Phase 3 (UI integration) is completely unspecified

This is understandable as the plan defers UI work to a separate scope, but the
plan should acknowledge the UI implications to avoid surprises:

- **Breaking change:** Fact-type `:name` format changes (kind-explicit). Every UI component that displays fact-type names needs to handle the new format.
- **Additive:** `AncestorEntry` list on fact-type detail view — needs new UI section
- **Additive:** `:match` info on rule/query upstream/downstream entries — inline rendering
- **Breaking change:** Route id migration — all URL construction and `encodeURIComponent` calls replaced
- **Migration:** `toRouteId`/`fromRouteId`/`splitQualifiedName` deletion — touched everywhere links are built

**Recommendation:** Add a "UI Impact Summary" section to the plan listing breaking vs. additive changes, even if the actual Phase 3 implementation is deferred.

---

## Finding 12 — `explorer-graph-api.md` regeneration burden

The plan says "Project docs are updated, not appended-as-history" and
"`explorer-graph-api.md` describes the new state directly." The current API doc
is ~290 lines and will need to be rewritten for:

- New `:id` field on every response type
- New `:ancestors` field on fact types with `AncestorEntry` shape
- New `:match` field on `ProductionDep`
- Changed `:name` format (kind-explicit)
- Changed URL patterns (`/:id` instead of `/:fq-name`)
- Deleted `fq-name-from-param` referenced behaviors

This is a substantial rewrite, not a targeted edit. The plan should call out
that Phase 1f/2f likely means rewriting the doc, not editing it.

**Recommendation:** Flag `explorer-graph-api.md` update as a full rewrite in the task descriptions.

---

## Finding 13 — Symbol ns-resolution for ancestors

When a fact type is a symbol like `my.ns/Fact` that resolves to a class
`some.other.ns.ActualClass` via `ns-resolve`, the ancestors should be computed
from the class, not the symbol. The plan mentions "symbol ns-resolution parity"
in Phase 1d tests, which is good, but the implementation strategy isn't
described.

Current `resolve-type` already handles `ns-resolve` for symbols. But for
ancestors computation, the raw (resolved) type must be used, not the symbol.
The plan should specify that the raw types in `type-analysis-map` are already
resolved (which they are, via `production-annotation` → `resolve-type-locally`),
so this should work naturally. But it's worth verifying in Phase 1a.

**Recommendation:** Add a note in Phase 1a confirming that `type-analysis-map`'s raw types are already resolved, and that ancestors are computed from resolved types. This is likely already the case but should be confirmed.

---

## Finding 14 — `AncestorEntry` cycle guard

The plan mentions "cycle guard" in the topological ordering for ancestors.
Cycles in type hierarchies are theoretically possible with `derive` (though
unlikely in practice). The cycle guard should produce a deterministic result —
e.g., break ties by lexicographic ordering of the serialized type name.

**Recommendation:** Specify the cycle-breaking behavior: detect cycles via the standard DFS approach and break ties by lexicographic order of the serialized type name. Add a test case with an artificial cycle.

---

## Finding 15 — `get-production-deps-summary` match symmetry

Phase 2c says to add optional `:match` to `ProductionDep`. The plan says
"dedup" and "symmetry" in Phase 2d tests. The symmetry property is important:
if the `:match` array on Rule X's downstream entry for Rule Y contains
`{producer-type: A, consumer-type: B}`, then Rule Y's upstream entry for Rule X
should contain the same entry (or a swapped equivalent). The plan should clarify
whether the same `:match` structure appears on both sides (producer-aware on the
downstream side, consumer-aware on the upstream side) or if they have different
shapes. The current spec says "symmetric `producer-type`/`consumer-type` keys"
— meaning both sides show both types the same way.

**Recommendation:** Confirmed that both upstream and downstream `:match` entries have identical shape: `{producer-type: kind-explicit-str, consumer-type: kind-explicit-str}`. The semantics are identical — producer-type is what the producing rule inserts, consumer-type is what the consuming rule reads. Add this to the schema docstring.

---

## Finding 16 — Cross-cutting: `fact-type-fn` interaction

Clara supports pluggable `fact-type-fn` that extracts the type from a fact
instance. The plan focuses on `ancestors-fn` but doesn't discuss whether
`fact-type-fn` affects the type serialization or the `known` flag logic. For
keyword-based fact types (using `:type` metadata), `fact-type-fn` extracts the
keyword marker, and `ancestors-fn` resolves against the global hierarchy. The
plan's approach should work for both class-based and keyword-based fact types,
but this is worth verifying.

**Recommendation:** Add a test case in Phase 1d for keyword-type facts with a custom hierarchy (via `derive`). Verify that ancestors are correctly computed and serialized.

---

## Finding 17 — `fq-name-from-param` deletion risk

Phase 1g says "delete `fq-name-from-param` server-side and its tests." This
function is used by `handle-get-rule`, `handle-get-query`, `handle-get-fact-type`,
and the session handlers. These all get replaced by id-based lookups. The
deletion must be coordinated with the route-id index implementation — if the
reverse-index is buggy, there's no fallback to the old name-based lookup.

**Recommendation:** The plan should specify that `fq-name-from-param` deletion happens in the same commit as the route-id index addition so they can be reverted atomically if issues arise.

---

## Finding 18 — Missing task: update `analyze.clj` `build-fallback-type-filter`

The `analyze.clj` module has its own `ancestors-fn` usage in
`build-fallback-type-filter` (line ~613), which recovers `ancestors-fn` from
the rulebase metadata and uses it to filter dynamic-insert types. This function
is independent of the core analysis pipeline and probably doesn't need changes,
but the plan should confirm this. If `ancestors-fn` extraction is refactored
into a shared utility in Phase 1a, this code path should use it too (or at
least be verified to remain correct).

**Recommendation:** Add a verification step: confirm `analyze.clj`'s `build-fallback-type-filter` remains correct after Phase 1a changes. It may benefit from using the same shared `memoized-ancestors` utility, reducing duplicate extraction logic.

---

## Overall Assessment

The plan's architecture is sound: ancestors-on-fact-types first, match-bridges
second, route-ids bundled where needed. The four key corrections from the
critical review are accurate. The main risks are:

1. **Underspecification of kind-explicit format** (Finding 1) — resolve before coding
2. **Incomplete design for raw-type threading** in `build-fact-type-summary-map` (Finding 3) — design the data flow before Phase 1b
3. **Missing inputs for `get-production-deps-summary`** `:match` computation (Finding 5) — design the `match-index` data structure before Phase 2b
4. **Scope understatement of route-id migration** (Finding 2) — budget accordingly

Addressing these before implementation starts will substantially reduce
iteration during the implementation phase.
