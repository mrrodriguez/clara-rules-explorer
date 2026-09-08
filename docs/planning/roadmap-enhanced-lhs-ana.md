# Roadmap — Enhanced LHS Analysis (Accumulator Details & Bindings)

Companion to [`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md). Tracks
concrete work done and the next increments.

Status: **accumulator info shipped; binding analyzer prototyped.**

---

## Done

### 1. Conditions analysis namespace

Added `server/src/clara/server/tools/graph/conditions.clj`:

- `accumulator-info` — evaluates an accumulator form in the production's
  namespace (throws on failure) and returns
  `{:form <raw-form> :some-initial-value? bool}`.
- `enrich-lhs` — prewalks a raw LHS and replaces each accumulator condition's
  `:accumulator` with its `accumulator-info` map.
- `analyze-lhs-bindings` — the compiler-equivalent binding walk
  (`sort-conditions` + `condition-to-node` + `:exists` expansion +
  disjunction handling). Returns per-conjunction
  `:used-bindings` / `:join-bindings` / `:new-bindings` /
  `:join-filter-join-bindings` / `:ancestor-bindings` / `:all-bindings`
  (plus `:result-binding` / `:fact-binding` where present).

### 2. Accumulator info wired into core analysis

- `core/production-summary` enriches each production's LHS via
  `conditions/enrich-lhs` before `serialize/serialize-lhs`.
- The `->rulebase-analysis` output therefore carries `:some-initial-value?`
  pre-serialization — direct consumers of the analysis get it without any
  HTTP/serialize step.

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

- Added `server/test/clara/server/tools/graph/conditions_test.clj`.
- Updated `server/test/clara/server/tools/graph/serialize_test.clj`
  accumulator case to the accumulator-info map input.

---

## Verified

Server (`cd server`):

- `make test` → 249 tests / 1635 assertions, 0 failures / 0 errors.
- `make format-check lint reflection-check` → all pass.

UI (`cd ui`):

- `TMPDIR="$PWD/target/tmp" make format check lint` → all pass.
  (Default run hits a pre-existing missing `/private/tmp/claude` temp dir;
  the override under `ui/target` works around it.)

---

## Current state / next increments

1. **Wire `conditions/analyze-lhs-bindings` into the analysis output.**
   Decide the additive shape for per-condition
   `:used-bindings` / `:join-bindings` / `:new-bindings` (and how to map the
   sorted/expanded records back to raw LHS conditions).
2. **Optionally precompute/cache accumulator eval** if `->rulebase-analysis`
   purity or repeated-eval cost becomes a concern.
3. **Optionally build the compiled-node mapper (Option C)** only if eval purity
   becomes a blocker or node-id exposure is wanted.
