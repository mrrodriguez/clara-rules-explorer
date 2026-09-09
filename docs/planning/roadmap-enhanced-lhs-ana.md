# Roadmap — Enhanced LHS Analysis (Accumulator Details & Bindings)

Companion to [`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md). Tracks
concrete work done and the next increments.

Status: **accumulator info + leaf binding augmentation shipped; group-level
(`:or` / `:exists`) binding info deferred.**

---

## Done

### 1. Conditions analysis namespace

Added `server/src/clara/server/tools/graph/conditions.clj`:

- `accumulator-info` — evaluates an accumulator form in the production's
  namespace (throws on failure) and returns
  `{:form <raw-form> :some-initial-value? bool}`.
- `analyze-lhs-bindings` — the compiler-equivalent binding walk
  (`sort-conditions` + `condition-to-node` + `:exists` expansion +
  disjunction handling), reimplementing the topological sort with origin
  tags so records map back to raw LHS positions. Returns per-conjunction
  `:used-bindings` / `:join-bindings` / `:new-bindings` /
  `:join-filter-join-bindings` / `:ancestor-bindings` / `:all-bindings`
  (plus `:result-binding` / `:fact-binding` where present).
- `augment-lhs` — enriches accumulator conditions and merges per-leaf binding
  info back into the original LHS tree. Group vectors stay vectors; only their
  nested leaf maps are augmented.

### 2. Conditions analysis wired into core analysis

- `core/production-summary` runs `conditions/augment-lhs` on each production's
  LHS before `serialize/serialize-lhs`.
- The `->rulebase-analysis` output therefore carries accumulator info
  (`:form` / `:some-initial-value?`) and per-leaf binding info
  (`:used-bindings` / `:binding-keys` / `:new-bindings` /
  `:ancestor-bindings` / `:all-bindings`) pre-serialization — direct
  consumers of the analysis get it without any HTTP/serialize step.

### 3. Serialization is pure

- `serialize/serialize-condition` renders the accumulator `:form` via
  `*form-printer*` + `str/trim-newline`. It performs **no eval**.

### 4. API schema + UI

- `server/src/clara/server/graph/api.clj`: `AccumulatorInfo` schema;
  `LhsCondition :accumulator` typed as `AccumulatorInfo` (was `s/Any`).
- `ui/src/lib/types/api.ts`: `AccumulatorInfo` interface; `accumulator?:`
  updated from `string[]`.
- `ui/src/lib/components/rulebase/LhsCondition.svelte`: renders
  `leaf.accumulator.form` and an `Initial Value` badge when
  `some-initial-value?` is true.

### 5. Tests

- Added `server/test/clara/server/tools/graph/conditions_test.clj` (accumulator
  info, enrich, origin-tagged bindings, `augment-lhs`, `:not` leaf
  augmentation).
- Updated `server/test/clara/server/tools/graph/serialize_test.clj`
  accumulator case to the accumulator-info map input.

---

## Verified

Server (`cd server`):

- `make test` → 251 tests / 1651 assertions, 0 failures / 0 errors.
- `make format-check lint reflection-check` → all pass.

UI (`cd ui`):

- `TMPDIR="$PWD/target/tmp" make format check lint` → all pass.
  (Default run hits a pre-existing missing `/private/tmp/claude` temp dir;
  the override under `ui/target` works around it.)

---

## Current state / next increments

1. **Leaf binding augmentation is wired** for fact/test/accumulator leaves and
   `:not` nested leaves. `:or` / `:exists` group leaves are left
   unaugmented this pass.
2. **Decide whether/when to homogenize LHS entries into maps** — group vectors
   are the remaining awkward consumer-API shape, left as-is for now.
3. **Optionally expose the binding keys in the API schema + UI** — they are
   currently carried through the catch-all keys, not explicitly typed/rendered.
4. **Optionally precompute/cache accumulator eval** if `->rulebase-analysis`
   purity or repeated-eval cost becomes a concern.
5. **Optionally build the compiled-node mapper (Option C)** only if eval purity
   becomes a blocker or node-id exposure is wanted.
