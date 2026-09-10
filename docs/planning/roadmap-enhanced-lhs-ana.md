# Roadmap — Enhanced LHS Analysis (Accumulator Details & Bindings)

Companion to [`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md) and
[`roadmap-enhanced-lhs-ana-review-1.md`](./roadmap-enhanced-lhs-ana-review-1.md).
Tracks concrete work done and the next increments.

Status: **accumulator info + full binding augmentation shipped (leaves, nested
leaves, and groups); LHS entries homogenized into maps and normalized once at
the top of the analysis.** Group-level (`:or` / `:exists` / negation) binding
info is attached per
[`enhanced-lhs-ana-group-bindings-problem.md`](./enhanced-lhs-ana-group-bindings-problem.md)
(children first in their own scope, group as the union; no synthetic
`:?__exists__…` binding ever surfaced). Review-2 feedback (single accumulator
eval, `:join-filter-join-bindings` gating, idempotent normalization, clearer
malformed-shape error, docstring alignment) is incorporated.

---

## Done

### 1. Normalized LHS shape + structural walkers (`conditions`)

- `conditions/normalize-lhs` converts a production's raw Clara LHS into the
  homogeneous shape used throughout the analysis: every entry is a map; boolean
  group vectors become `{:condition-type … :children […]}`; accumulator `:from`
  subtrees are normalized recursively. Group and accumulator nodes retain their
  raw form under `:raw-condition` so the compiler-coupled walk reads it directly
  (no reverse conversion).
- `conditions/get-raw-lhs` / `get-raw-condition` recover the raw Clara LHS from
  the retained `:raw-condition` values (only the binding walk and the
  `:lhs-form` string need raw).
- `conditions/extract-lhs-fact-types` (whole LHS, distinct, traversal order) and
  the private `extract-condition-fact-types` (single subtree) replace the old
  `core/extract-lhs-fact-types` and `analyze.alias/subtree-fact-types`.
- `conditions/extract-var-bindings` replaces `analyze.alias/lhs-var-bindings`.

### 2. Normalization done once, early (`core`)

- `core/->rulebase-analysis*` normalizes every production's `:lhs` once, up
  front, before fact-type extraction, binding augmentation, or serialization.
- `->type-analysis-map` and `production-summary` consume the already-normalized
  LHS — no ad-hoc re-normalization in consumers.
- `production-summary` reconstructs `:lhs-form` from the raw LHS retained by
  normalization (`conditions/get-raw-lhs`), while `:lhs` and `:lhs-types` come
  from the normalized enriched LHS.

### 3. Binding analysis (`conditions`)

- `analyze-lhs-bindings` now accepts a normalized LHS; it reads raw forms via
  `get-raw-lhs` only for the compiler-coupled walk (`sort-conditions` +
  `condition-to-node` + `:exists` expansion).
- `augment-lhs` enriches an already-normalized LHS (accumulator info + per-leaf
  `:bindings`) and retains the internal `:raw-condition` / `::normalized` keys
  for in-memory consumers; `enrich-accumulators` skips `:raw-condition`
  subtrees (no re-evaluation), and `strip-internal-keys` removes the internal
  keys at the serialization boundary.
- Review-1 hardening retained: unsatisfiable-input guard, explicit
  compound-negation deferral, deterministic `:exists` placeholders, shared path
  helper, `:join-bindings` → `:binding-keys`, test-time-only `s/defn` schemas.

### 4. Serialization + API schema

- `serialize/serialize-condition` consumes the normalized shape (group entries
  keep `:condition-type` and recurse `:children`; accumulator entries recurse
  `:from`; leaf value conversion is unchanged).
- `api/LhsCondition` is a strict recursive schema: leaf keys + `:condition-type`
  (`:and`/`:or`/`:not`/`:exists`) + `:children`; the old `s/Keyword s/Any`
  catch-all is gone, so group entries actually validate.

### 5. Analyze flow + memory (`analyze`, `memory`)

- `analyze/->annotations-from-rule-source-analysis` normalizes productions and
  query LHS once; `build-fallback-type-filter` and `alias-usage-map` consume the
  normalized LHS via the shared `conditions` walkers.
- `analyze.alias` no longer owns its own LHS walkers (`subtree-fact-types`,
  `rulebase-fact-types`, `lhs-var-bindings` are gone); it delegates to
  `conditions/extract-var-bindings`.
- `memory/get-fact-type-order` normalizes + uses `conditions/extract-lhs-fact-types`.

### 6. UI

- `api.ts`: `LhsElement` is a closed map type — `condition-type?` / `children?`
  plus the leaf keys; the `[key: string]: unknown` catch-all is removed.
- `LhsCondition.svelte`: group detection is `children` presence (no
  `Array.isArray` / `condition[0]`), the `unknown[]` union and the JSON-blob
  fallback are gone, and `bindings` still renders in its dedicated collapsible
  element.

### 7. Tests

- `conditions_test.clj`: `normalize-lhs` (structure + raw retention via
  `get-raw-lhs`), `augment-lhs` on normalized input, `:not` group, compound
  negation, unsatisfiable throw, deterministic `:exists`, accumulator info.
- `core_test.clj` / `serialize_test.clj` / `analyze_test.clj`: updated to the
  shared `conditions` walkers and the normalized group shape.
- `ui/.../LhsCondition.svelte.test.ts`: bindings summary + group rendering.

### 8. Review-2 hardening (`R2-1`–`R2-7`)

- Accumulators are evaluated once per condition: `enrich-accumulators` walks
  only `:from` / `:children` and never descends into the retained
  `:raw-condition` subtrees (R2-5).
- `:join-filter-join-bindings` is gated on `not-empty` and only emitted for
  non-equality unifications that reference an upstream binding; empty variants
  are omitted (R2-1), with a new `augment-lhs` test covering the non-empty and
  omitted cases (R2-2).
- `normalize-lhs` is idempotent — `normalize-condition` tags group/accumulator
  nodes with a namespaced `::normalized` marker and short-circuits on it
  (R2-6).
- `normalize-condition` throws a clear `ex-info` for a malformed group vector
  whose head is not a keyword/symbol (R2-7).
- `analyze-lhs-bindings` `:attach-path` docstring now lists compound negations
  alongside `:or` / `:exists` (R2-3).

### 9. Group-level binding info (`conditions`, `api`, UI)

- `conditions/analyze-node` recurses over groups in their own scope (`:or`
  branches independent, `:and` sequential, nothing escapes negations or
  `:exists`); every group's `:bindings` is the componentwise union of its
  children's, and `merge-bindings-into-tree` attaches group summaries as well
  as leaves (top-level `:and` groups union their flattened children).
- Compound negations walk the inner `negation-expr` (what the compiler's helper
  production actually builds — never the De Morgan expansion); the group's
  `:binding-keys` equal `variables-as-keywords ∩ ancestor-bindings`.
- `api/LhsCondition` gains optional `:bindings-deferred` (transitional safety
  net; emitted nowhere — every node carries `:bindings`); `LhsBindingInfo`
  docstring records the derived-union contract.
- `LhsCondition.svelte` renders group `bindings` and surfaces
  `bindings-deferred`; `api.ts` closes the shape with the optional key.
- `conditions_test.clj`: flat/nested/identical/asymmetric `:or`, two-group
  isolation, `:exists` group==child with no synthetic binding, compound-negation
  sub-scope + `vars ∩ ancestor` check, no-leak outward, group-union structural
  property, and the no-node-without-`:bindings` invariant.

### 10. Internal LHS keys serialized, stripped at the external-view boundary

- `serialize/serialize-condition` serializes each retained `:raw-condition`
  recursively as a condition (raw group vectors stay vectors; a raw
  accumulator form becomes a string) and passes the `::normalized` marker
  through, so the serialized `:lhs` stays fully serialized while retaining the
  internal keys for in-memory consumers of `->rulebase-analysis`.
- `core/get-production-external-view` (and
  `core/get-rulebase-analysis-external-view`) strip `:raw-condition` /
  `::normalized` from the serialized `:lhs` at the API boundary, so they are
  never externalized.


Server (`cd server`):

- `make test` → all pass (accumulator eval, binding augmentation, LHS
  normalization, serialization, and analyze-flow cases are covered).
- `make format-check lint reflection-check` → all pass.

UI (`cd ui`):

- `TMPDIR="$PWD/target/tmp" make format check lint` → all pass.
- `make test-unit` / `make test-e2e` → all pass (bindings summary and group
  rendering covered in the unit suite).

(Demo-data regeneration is intentionally deferred.)

---

## Current state / next increments

1. **Binding augmentation is shipped for every node** — fact/test/accumulator
   leaves, nested leaves, and `:or` / `:exists` / negation groups — under one
   nested `:bindings` map per node; a group's is the union of its children's.
2. **LHS entries are homogeneous maps** end-to-end; normalization is
   idempotent, and the serialized `:lhs` retains the internal `:raw-condition`
   / `::normalized` keys, stripped only at the external-view boundary.
3. **Regenerate demo data** when the static demo next needs to reflect the new
   wire shape (deferred; it is already stale for accumulator/bindings).
4. **Optionally precompute/cache accumulator eval** if `->rulebase-analysis`
   purity or repeated-eval cost becomes a concern.
5. **Optionally build the compiled-node mapper (Option C)** only if eval purity
   becomes a blocker or node-id exposure is wanted.

---

## Open questions / concerns

1. **Group-level binding exposure.** Shipped (see §9 above).
2. **Accumulator eval caching.** Where it should live if introduced.
3. **`raw-condition` lifecycle.** It is an internal key on normalized nodes,
   serialized recursively into the in-memory `:lhs` and stripped at the
   external-view boundary (`core/get-production-external-view`); normalization
   is idempotent.
