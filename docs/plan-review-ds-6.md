# Plan Review: Extend API with Fact Type Hierarchy Details

## Overall Assessment

This is a **strong, well-reasoned plan**. The core design instincts are right:
kind-explicit serialization as the single heterogeneity boundary, computing
`:match` at serialization time without touching the dep-graph shape,
deterministic topological sort for ancestor ordering, and a uniform `:id`
scheme that eliminates URL-encoding hazards across the board. The plan
anticipates many subtle failure modes (heterogeneous sorting, intransitive
comparators, cycle guards, ns-context-sensitive symbol resolution) and
addresses them explicitly.

That said, there are several gaps and a few simplifications worth considering
before implementation begins. The biggest risk is in the Phase 1g scoping
and the per-production ancestors-index construction semantics, detailed below.

---

## What the Plan Gets Right

1. **Kind-explicit serialization as the single heterogeneity boundary.**
   The invariant — raw values for all logic, strings only at the display
   boundary, ordering only post-serialization — is the correct architecture.
   Without this, mixed-kind hierarchies (keywords derived from classes via
   custom `ancestors-fn`) would produce subtle bugs at every comparison
   point.

2. **`:match` computed at serialization time, dep-graph untouched.**
   Avoiding changes to `build-dep-graph`'s internal shape is the right call:
   `rule-is-sink?`, `test-dep-graph-*`, `analysis.edn` consumers, and
   `annotations_report.bb` all depend on the current shape. Computing
   type-pair bridges lazily (only for actual edges, not the full O(n²)
   candidate space) is both cheaper and zero-risk.

3. **Deterministic topological sort with cycle guard.** The recognition that
   a comparator-based sort is intransitive for a partial order + string
   tie-break is subtle and correct. Kahn's algorithm restricted to the
   ancestor set with a lexicographic ready-queue is the right solution.

4. **Memoization of the ancestor set fn.** Clara already memoizes
   `fact-type->roots` (per-fact-type grouping), but the metadata-carried
   `ancestors-fn` itself is not memoized. Adding `->memoized-ancestors` (on
   the *set*, not the seq) eliminates both the repeated `(set …)` allocation
   in today's `downstream?` and redundant ancestor computation across the
   three consumers (dep-graph, `:ancestors` enrichment, `:match`).

5. **`known` flag as the primary noise filter.** The default
   `clojure.core/ancestors` produces 10–20 entries per `defrecord`, almost
   all JDK/CLJ ghosts. The `known` flag lets the UI render them as plain
   text rather than broken links, without server-side suppression (which can
   follow later).

6. **Symbol serialization in per-production ns context.** Both
   `production-summary` and the ancestors index serialize each type with the
   production's own `:ns-name`. This is correct — a symbol `foo` in ns-a
   resolves differently than `foo` in ns-b — and the cross-field consistency
   invariant (`:match` `producer-type` == producer's `:insert-types`) is
   pinned by test.

---

## Gaps and Concerns

### 1. Per-production ancestors-index: "last-wins" is a correctness hazard

The plan builds `{serialized-name → [serialized-ancestors …]}` where the
serialized name of a raw type depends on the production's ns context:

> "Merge all productions into one `{serialized-name [serialized-ancestor
> …]}` map; last-wins on key collision is acceptable."

**Problem:** The same raw type `Foo` appearing in two productions (ns-a and
ns-b) may serialize to **different strings** if symbol resolution differs
across namespaces. The "last-wins" semantics mean:

- The displayed ancestors for `Foo` depend on which production was processed
  last (non-deterministic relative to load order).
- If the two serializations differ, one name won't match the fact-types map
  keys (which are also per-production serialized). The `known` check in the
  second pass would silently return `false` for that entry.

**How likely is this?** For classes (`.getName`), keywords (`(str x)`), and
tuples (`(pr-str x)`), the serialization is ns-independent — same raw type
always serializes identically. The only divergence case is unresolved
symbols (where `resolve-type` falls back to `(str x)` when ns-resolve
fails, or resolves to different values in different ns contexts). For
resolved symbols, `resolve-type` returns the resolved class name, which is
ns-independent.

**Mitigation:** Instead of a single merged map keyed by serialized name,
**key the ancestors index by raw type**. Use `(into {} (map (juxt identity
ancestors-fn)) raw-types)` — raw types have value equality for all
supported kinds (Classes, keywords, symbols, strings, vectors). Then in the
second pass, serialize the ancestor entries in the *fact-type's own* ns
context, or in a canonical context derived from the raw type itself (e.g.,
keyword namespace, class package). This eliminates the collision problem
entirely.

**Alternative (simpler):** Since the divergence is only theoretical for
unresolved symbols (which are rare — LHS types are compiler-resolved, and
sidecar annotations are the only non-resolved source), document the
assumption explicitly and assert equality at build time: if two productions
serialize the same raw type differently, throw with a clear message. This
turns the silent correctness hazard into a loud build failure.

### 2. `:ns` on fact types: which raw type wins?

The plan says `:ns` is computed from the "raw type during the index build,
where the kind is known." But the index is built per-production and merged
with last-wins. A fact type that appears in multiple productions (the
common case) may carry a different raw-type representation depending on
which production's index entry "won."

However, for the common type kinds (Class, keyword, tuple), the `:ns`
derivation is deterministic regardless of production context:
- Class → `.getPackageName` is always the same
- Keyword `:my.ns/foo` → `(namespace :my.ns/foo)` is always `"my.ns"`
- String/tuple → always `null`

So `:ns` is actually idempotent across productions for the same raw type.
The plan's current approach works, but the reasoning should be made
explicit: `:ns` is a property of the *type*, not the *production context*.

### 3. Ancestors second pass: chicken-and-egg with `known`

The plan's two-pass approach requires the fact-types map to be complete
before computing `known` flags, but the ancestors index must be built
before `build-fact-type-summary-map` runs. The plan addresses this:

1. Build ancestors index (serialized ancestors per serialized type name)
2. Build fact-types map (existing logic)
3. Second pass: enrich each fact-type entry with `:ancestors` and compute
   `known` by membership in fact-types map keys.

This works. However, there's a subtlety: after step 2, the fact-types map
keys are serialized with each production's own ns context (by
`production-summary`). The ancestors index keys are also serialized with
per-production ns context. If these diverge (see concern #1), the `known`
check silently misses entries.

**Fix:** Build the ancestors index keyed by raw type (see #1). In the
second pass, look up the raw type, serialize ancestors in a canonical
context, and check `known` against a set of canonical serialized names
(built from the fact-types map keys — which should be canonicalized
similarly). Or, more simply: key the fact-types map by raw type internally,
and serialize names only at the API boundary. But this would be a larger
refactor of `build-fact-type-summary-map`.

**Recommendation:** Accept the minor risk (unresolved symbols are rare) and
add an assertion in the second pass: if a raw type's serialized name from
the index doesn't appear as a fact-types key, log a warning or throw during
test. The runtime behavior (unknown fact-type → `known: false`) is safe
anyway.

### 4. Phase 1g: session endpoint id-resolution path needs detailing

The plan correctly identifies Phase 1g as the largest work item (~30-40%).
One area that needs more detail:

- **Session endpoints have separate data sources.** The static analysis
  endpoints resolve through the analysis cache and its reverse indexes.
  Session endpoints (`/v1/session/rules/:id`, `/v1/session/fact-types/:id`)
  resolve through the snapshot, which is keyed by serialized name, not id.
  The plan mentions "session fact-type ids are computed from their
  serialized names with the same function" but doesn't detail how session
  handlers resolve ids. Each session handler would need to:
  1. Receive an `:id` param
  2. Look up the canonical name from the analysis's reverse index
  3. Use that name to look up session data from the snapshot

  This means session handlers now depend on the analysis cache, which they
  don't currently. That's a cross-cutting dependency worth calling out.

### 5. `serialize-match` helper: design is sparse

The plan says to add a `serialize-match` sub-helper in `serialize.clj` but
doesn't specify its contract. Based on the text, it would:

```clojure
(defn serialize-match
  [raw-pairs producer-ns consumer-ns]
  (->> raw-pairs
       (map (fn [{:keys [producer-type consumer-type]}]
              {:producer-type (resolve-type producer-ns producer-type)
               :consumer-type (resolve-type consumer-ns consumer-type)}))
       (sort-by (juxt :producer-type :consumer-type))))
```

This is straightforward, but the plan could be more explicit about:
- Whether `raw-pairs` is the output of `matching-type-pairs` (vectors of
  `{:producer-type ... :consumer-type ...}` maps)
- Whether sorting is `sort-by (juxt :producer-type :consumer-type)` or a
  custom comparator (the former is correct since both are strings
  post-serialization)

### 6. Threading `type-analysis-map` through the call chain

The plan says `get-production-deps-summary` gains the `type-analysis-map`
and memoized ancestor-set fn. Currently:

```
rulebase-analysis
  → build-dep-graph (consumes type-analysis-map internally)
  → production-summary → get-production-deps-summary (doesn't have type-analysis-map)
  → build-production-summary-map → build-rule-summary-map / build-query-summary-map
```

To thread `type-analysis-map` through, the plan must pass it through
`production-summary` → `build-production-summary-map` →
`build-rule-summary-map` / `build-query-summary-map`. That's a 4-level
threading change — mechanically simple but scattered across many functions.
The plan acknowledges this ("thread them through `build-production-summary-map`
alongside `dep-graph`") but doesn't enumerate all the affected function
signatures.

### 7. Retract types produce misleading `:match` entries

The plan acknowledges this:

> "A dep edge created by a *retract* carries a `:match` whose
> `producer-type` is the retracted type — 'producer' wording is imperfect
> there, but the coupling is real (a retraction can invalidate downstream
> joins) and matches the existing edge semantics."

This is reasonable *machinery*-wise, but the UX implication is worse than
the plan admits: a user looking at a downstream entry that says "Rule X
produces `RetractedType` → satisfies `ConsumerType`" will assume Rule X
*inserts* that type. The mismatch between the `:match` label and the
rule's own `:retract-types` list is confusing. The plan mentions a future
`:match` entry flag (`via: "retract"`) — this should be prioritized as a
separate, small follow-up rather than deferred indefinitely.

### 8. Performance: per-production ancestors-index build cost

The plan estimates "negligible" cost for k≤20 ancestors per type. But the
per-production iteration is:

```
O(productions × types-per-production × (serialize-type + ancestors-fn-call + topological-sort))
```

At 3k rules, 5 types per production average, 15 ancestors per type, and
memoization eliminating redundant `ancestors-fn` calls (but NOT redundant
serialization — every production's types are serialized in that production's
ns context), we get:

- ~15,000 distinct (production, type) pairs
- Each type serialized once per production it appears in (memoization
  doesn't help here since the index is built per-production)
- Serialization is `resolve-type` (which includes `ns-resolve` for symbols
  — not free)
- Topological sort is O(k²) per unique raw type (~200 types × 15² = 45k
  comparisons)

This is all cache-warmed on server startup (`warm-analysis-cache!`), so
request latency is unaffected. Still, the plan should note that the startup
cost increases proportionally to the number of productions × types.

**Mitigation:** memoize the serialized-name → ancestors mapping *by raw
type* (not per-production serialized name). Since the same raw type in
different productions serializes identically for classes/keywords/tuples
(see concern #1), this eliminates redundant work without correctness risk.

### 9. Default ancestors-fn fallback divergence

The plan says to extract ancestors-fn from `(meta get-alphas-fn)` and
fallback to `clojure.core/ancestors`. But `analyze.clj` already has a
similar extraction in `build-fallback-type-filter`:

```clojure
ancestors-fn (or (-> rulebase :get-alphas-fn meta :ancestors-fn)
                 ancestors)
```

The plan says to "share the extraction/memoization utility if natural" but
this is a cross-namespace concern (`core.clj` vs `analyze.clj`). The
fallback must be identical in both places — otherwise the dep-graph and the
fallback type filter could disagree on which types link. Pull the
extraction into a single place (e.g., a public fn in `core.clj`).

### 10. Missing: `analysis.edn` shape impact

The plan says the dep-graph shape is unchanged, and `:match` is computed at
serialization time. But if `analysis.edn` dumps the full analysis map
(including rules/queries/fact-types with their new fields), then
`:ancestors` and `:id` (and potentially `:match` if seralized) will appear
in the EDN dump. The plan should clarify whether `analysis.edn` consumers
should expect these new fields, or whether they should be stripped before
dumping. If not specified, this is a tacit API change for external tooling.

---

## Simplification Opportunities

### A. Key the ancestors index by raw type, not serialized name

As discussed in concern #1, this eliminates the "last-wins" hazard and
makes the second-pass `known` check straightforward. The index becomes:

```clojure
;; {raw-type → {:ancestors #{raw-ancestor …} :ns <context-ns>}}
```

Serialization happens in the second pass, where each ancestor entry's
`type` and `id` are computed. The `known` check uses canonical serialized
names (derived from the raw type's own properties, not any specific
production's ns context).

### B. Compute `:id` only for `known: true` ancestor entries

Ghost entries (JDK/CLJ interfaces) will never be linkable. Computing SHA-1
hashes for `java.lang.Object`, `java.io.Serializable`, `clojure.lang.IObj`,
etc. — for every fact type — is wasted work. Instead:

- `known: true` entries: `{type, id, known: true}`
- `known: false` entries: `{type, known: false}` (no `id`)

The UI doesn't render ghost entries as links anyway. The plan argues for
"shape uniformity" but it's a weak justification against the noise of
meaningless ids in the payload. Worth benchmarking: if SHA-1 + slug is
cheap enough, uniformity wins. But at ~15 ghost ancestors per record-backed
fact type × potentially hundreds of fact types, that's thousands of
unnecessary SHA-1 hashes.

### C. Separate test fixtures per type kind

The plan proposes a single `loan_hierarchy_rules.clj` fixture covering
keywords, tuples, and records. For test isolation, consider:

- `loan_keyword_hierarchy_rules.clj` — keyword `derive` hierarchy
- `loan_tuple_fact_rules.clj` — tuple fact types
- Keep the existing `loan_app_rules.clj` for record/class coverage

This makes individual test failures easier to diagnose (a tuple test failure
won't cascade into keyword hierarchy assertions).

---

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Per-production ancestors-index key collision (concern #1) | **Medium** — silent incorrectness if unresolved symbols diverge | Key by raw type instead of serialized name; or assert equality |
| Session endpoint id resolution (concern #4) | **Medium** — session handlers need analysis-cache dependency | Detail the resolution path in implementation notes |
| Retract-type `:match` UX confusion (concern #7) | **Low** — documented limitation, future flag planned | Add `via: "retract"` as a small fast-follow |
| `analysis.edn` consumer breakage (concern #10) | **Low** — EDN consumers can ignore unknown keys | Document new fields; consider stripping from EDN dump |
| Performance at scale (concern #8) | **Low** — cached at startup, request path unaffected | Memoize by raw type (recommendation A) |
| `build-fallback-type-filter` divergence (concern #9) | **Low** — same fallback in both places if done right | Share extraction utility in `core.clj` |

---

## Summary

The plan is **ready for implementation with the following adjustments**:

1. **Fix the ancestors-index key collision hazard** (concern #1) by keying
   by raw type, not per-production serialized name. This is the only
   correctness risk I identified.

2. **Only compute `:id` for known ancestors** (recommendation B) — ghost
   entries don't need ids, and the payload savings add up across thousands
   of fact types.

3. **Detail the session endpoint id-resolution path** (concern #4) before
   implementing Phase 1g — the analysis-cache dependency in session handlers
   is a new cross-cutting concern.

4. **Plan for `via: "retract"` as a fast-follow** (concern #7) — the UX
   confusion from retract-type `:match` entries is real and a small flag
   addition fixes it.

The architecture is sound, the heterogeneity handling is correct, and the
implementation phases are well-sequenced. These adjustments are minor
relative to the plan's overall quality.
