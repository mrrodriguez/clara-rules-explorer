# Roadmap — Enhanced LHS Analysis (Accumulator Details & Bindings)

Companion to [`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md) and
[`roadmap-enhanced-lhs-ana-review-1.md`](./roadmap-enhanced-lhs-ana-review-1.md).
Tracks concrete work done and the next increments.

Status: **accumulator info + leaf binding augmentation shipped; LHS entries
homogenized into maps and normalized once at the top of the analysis.**
Group-level (`:or` / `:exists`) and compound-negation binding info remain
deferred (explicitly, not silently).

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
  `:bindings`) and strips the internal `:raw-condition` key in `walk-augment`.
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

---

## Verified

Server (`cd server`):

- `make test` → **254 tests / 1655 assertions**, 0 failures / 0 errors.
- `make format-check lint reflection-check` → all pass.

UI (`cd ui`):

- `TMPDIR="$PWD/target/tmp" make format check lint` → all pass.
- `make test-unit` → **32 passed**.
- `make test-e2e` → **65 passed**.

(Demo-data regeneration is intentionally deferred.)

---

## Current state / next increments

1. **Leaf binding augmentation is shipped** for fact/test/accumulator leaves and
   simple `:not` leaves, under one nested `:bindings` map per leaf.
2. **Group-level binding info is deferred** for `:or` / `:exists` group leaves
   and compound negations — analyzed for ancestor propagation but left
   unaugmented, explicitly.
3. **LHS entries are homogeneous maps** end-to-end (raw retained only as
   `:raw-condition` for the binding walk and `:lhs-form`).
4. **Regenerate demo data** when the static demo next needs to reflect the new
   wire shape (deferred; it is already stale for accumulator/bindings).
5. **Optionally precompute/cache accumulator eval** if `->rulebase-analysis`
   purity or repeated-eval cost becomes a concern.
6. **Optionally build the compiled-node mapper (Option C)** only if eval purity
   becomes a blocker or node-id exposure is wanted.

---

## Open questions / concerns

1. **Group-level binding exposure.** Whether/when to attach binding info to
   `:or` / `:exists` / compound-negation leaves (requires more of the
   compiler's extraction/DNF bookkeeping).
2. **Accumulator eval caching.** Where it should live if introduced.
3. **`raw-condition` lifecycle.** It is an internal key on normalized nodes,
   stripped by `augment-lhs`; any future consumer that walks a normalized-but-
   not-yet-augmented LHS must be aware it is present (it is harmless to the
   structural walkers).
