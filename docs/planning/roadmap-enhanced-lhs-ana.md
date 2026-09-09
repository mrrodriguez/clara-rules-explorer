# Roadmap — Enhanced LHS Analysis (Accumulator Details & Bindings)

Companion to [`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md) and
[`roadmap-enhanced-lhs-ana-review-1.md`](./roadmap-enhanced-lhs-ana-review-1.md).
Tracks concrete work done and the next increments.

Status: **accumulator info + leaf binding augmentation shipped with a
consolidated `:bindings` wire shape. Review 1 feedback incorporated.**
Group-level (`:or` / `:exists`) and compound-negation binding info remain
deferred (explicitly, not silently).

---

## Done

### 1. Conditions analysis namespace

Added `server/src/clara/server/tools/graph/conditions.clj`:

- `accumulator-info` — evaluates an accumulator form in the production's
  namespace (throws on failure) and returns
  `{:form <raw-form> :some-initial-value? bool}`. Declares `AccumulatorInfo`
  as its `s/defn` output schema (validated only under
  `schema.test/validate-schemas` in tests); distinguishes a non-derivable
  production namespace from a non-loaded one.
- `analyze-lhs-bindings` — the compiler-equivalent binding walk
  (`sort-conditions` + `condition-to-node` + `:exists` expansion +
  disjunction handling), reimplementing the topological sort with origin tags
  so records map back to raw LHS positions. Returns per-conjunction
  `:used-bindings` / `:binding-keys` / `:new-bindings` /
  `:join-filter-join-bindings` / `:ancestor-bindings` / `:all-bindings`
  (plus `:result-binding` / `:fact-binding` where present). Each record is
  produced by `conjunction-binding`, which declares `LhsBindingRecord` as its
  `s/defn` output schema (validated only under
  `schema.test/validate-schemas` in tests).
- `augment-lhs` — enriches accumulator conditions and merges a single nested
  `:bindings` summary back into leaf maps in the original LHS tree. Group
  vectors stay vectors; only their nested leaf maps are augmented.

Review 1 hardening in this namespace:

- **C1 (HIGH):** `sort-tagged` now mirrors the compiler's unsatisfiable-input
  guard and throws an `ex-info` instead of looping forever.
- **C2:** compound negations (`[:not [:and/:or/:not ...]]`) are detected in
  `attach-path` and deferred explicitly (attach-path `nil`); duplicate
  attach-paths in `path-index` throw instead of silently overwriting.
- **C3:** `:exists` expansion uses a deterministic `:result-binding` derived
  from the origin path + condition index (no `gensym`).
- **S1:** `group-child-paths` is the single place that encodes child path
  indexing, shared by `flatten-tagged`, `attach-path`, and `walk-augment`.
- **S2:** the compiler's transient `:join-bindings` key is read exactly once in
  `conjunction-binding`; the record and downstream code use `:binding-keys`
  only.

### 2. Conditions analysis wired into core analysis

- `core/production-summary` runs `conditions/augment-lhs` on each production's
  LHS before `serialize/serialize-lhs`.
- The `->rulebase-analysis` output therefore carries accumulator info
  (`:form` / `:some-initial-value?`) and per-leaf `:bindings`
  (`:binding-keys` / `:new-bindings`, plus `:join-filter-join-bindings` when
  present) pre-serialization — direct consumers of the analysis get it without
  any HTTP/serialize step.

### 3. Serialization is pure

- `serialize/serialize-condition` renders the accumulator `:form` via
  `*form-printer*` + `str/trim-newline`. It performs **no eval**.
- The nested `:bindings` map passes through `serialize-condition` / `prune-fns`
  untouched; its keyword vectors become strings only at JSON encoding.

### 4. API schema + UI

- `server/src/clara/server/graph/api.clj`: `AccumulatorInfo` schema;
  `LhsBindingInfo` schema (`:binding-keys` / `:new-bindings` /
  optional `:join-filter-join-bindings`); `LhsCondition :bindings` typed as
  `LhsBindingInfo`.
- `ui/src/lib/types/api.ts`: `AccumulatorInfo` + `LhsBindingInfo` interfaces;
  `bindings?: LhsBindingInfo` on `LhsElement`.
- `ui/src/lib/components/rulebase/LhsCondition.svelte`: `bindings` is an
  ignored key (no JSON-blob fallback); a collapsible "Show bindings" element
  renders the groups in fixed order (new → joins → join filter) as chips.

### 5. Tests

- `server/test/clara/server/tools/graph/conditions_test.clj`: accumulator info,
  enrich, origin-tagged bindings, `augment-lhs`, `:not` leaf augmentation, the
  unsatisfiable-input throw, compound-negation deferral, and deterministic
  `:exists` expansion.
- `server/test/clara/server/tools/graph/serialize_test.clj`: accumulator case
  updated to the accumulator-info map input.
- `ui/src/lib/components/rulebase/LhsCondition.svelte.test.ts`: collapsible
  bindings summary renders in fixed group order, and is omitted when no groups
  are present.

---

## Verified

Server (`cd server`):

- `make test` → **253 tests / 1648 assertions**, 0 failures / 0 errors.
- `make format-check lint reflection-check` → all pass.

UI (`cd ui`):

- `TMPDIR="$PWD/target/tmp" make format check lint` → all pass.
- `make test-unit` → **31 passed**.
- `make test-e2e` → **65 passed**.

---

## Current state / next increments

1. **Leaf binding augmentation is shipped** for fact/test/accumulator leaves and
   simple `:not` leaves, under one nested `:bindings` map per leaf.
2. **Group-level binding info is deferred** for `:or` / `:exists` group leaves
   and compound negations. These are still analyzed for ancestor-bindings
   propagation, but their nested leaves are left unaugmented — explicitly, with
   no silent drop.
3. **Decide whether/when to homogenize LHS entries into maps** — group vectors
   remain the awkward consumer-API shape, left as-is for now.
4. **Optionally precompute/cache accumulator eval** if `->rulebase-analysis`
   purity or repeated-eval cost becomes a concern.
5. **Optionally build the compiled-node mapper (Option C)** only if eval purity
   becomes a blocker or node-id exposure is wanted.

---

## Open questions / concerns

1. **Group-vector schema modelling (pre-existing).** `LhsCondition` is a map
   schema, but a rule LHS may contain group vectors (`[:not ...]`, `[:or ...]`,
   `[:and ...]`, `[:exists ...]`). Production `s/defn` handlers do not validate
   by default, and the UI handles the vectors, so this has not surfaced in the
   running app; under `schema.test/validate-schemas` a detail fetch of a
   group-bearing rule would fail. This predates the binding work and is the
   "homogenize LHS entries" decision above.
2. **Where accumulator-eval caching should live** if/when introduced.
3. **Whether/when to expose binding info for `:or` / `:exists` / compound
   negations** — doing so requires replicating more of the compiler's
   extraction/DNF bookkeeping.
