# Performance Improvements — Progress Log

Tracking implementation of
[`perf-improvements-memory-plan.md`](./perf-improvements-memory-plan.md).

Order of work (priority order):

1. **P0 — Fix 1 + Fix 2:** Schwartzian transform in `sort-facts` + decorate-sort-undecorate in `deterministic-fact-str`/`canonicalize`. (targets the ~23s `sort-facts`)
2. **P1 — Fix 3:** identity-memoized `prune-fns`, scoped to the snapshot callstack only (no cross-call retention).
3. **P1 — Fix 4:** `enrich-annotations-from-session` fast path (pre-normalized `production-annotation`, dedup `rule->session-raw-types`).

Stopping points for review: after each P0/P1 step completes + tests/checks pass + a timing measurement.

---

## Status

- [x] **Fix 1** — Schwartzian transform in `sort-facts`
- [x] **Fix 2** — decorate-sort-undecorate in `deterministic-fact-str`
- [ ] **Fix 3** — identity-memoized `prune-fns` (callstack-scoped)
- [ ] **Fix 4** — `enrich-annotations-from-session` fast path

---

## Step 1 — Fix 1 + Fix 2 (`sort-facts` / `deterministic-fact-str`) ✅

**File:** `server/src/clara/server/tools/graph/memory.clj`

### Changes

1. `sort-facts` — decorate-sort-undecorate: compute each fact's `[type-order type-str fact-str]` key once, sort `[key fact]` pairs, strip keys. Replaces `sort-by` whose keyfn ran O(n log n) times.
2. `deterministic-fact-str` — extracted a private `sort-by-pr-str` helper that computes `pr-str` once per element (was `(sort-by pr-str …)` recomputing O(k log k) times), and `canonicalize` now uses it for both the map and set branches.

### Verification

- **Tests:** `make test` → **202 tests, 1422 assertions, 0 failures, 0 errors** (includes `memory-test` determinism/ID-stability tests).
- **Lint:** `make lint` → 0 errors, 0 warnings.
- **Reflection:** `make reflection-check` → passed, no warnings.
- **Format:** `cljfmt check src/clara/server/tools/graph/memory.clj` → clean.
  - ⚠️ `make format-check` fails on `test/clara/server/tools/graph/serialize_test.clj`
    (a `binding` indentation nit) — **pre-existing, unrelated to this change**
    (file not touched; `git status` shows only `memory.clj` modified).

### Timing (old vs new `sort-facts`, synthetic nested-map facts)

| n     | old      | new      | speedup |
|-------|----------|----------|---------|
| 500   | 159.3 ms | 21.5 ms  | ~7.4×   |
| 1000  | 268.8 ms | 38.9 ms  | ~6.9×   |
| 2000  | 532.3 ms | 78.0 ms  | ~6.8×   |

- **Output order verified identical** (old vs new) at each n.
- The ~7× here is on moderate nested facts; on the real 23s workload (larger facts)
  the same O(n log n) → O(n) reduction applies, so `sort-facts` should fall from
  ~23s to the low single-digit seconds (re-measure on the real snapshot to confirm).

### Stopping point

Fix 1 + Fix 2 are complete and green. **Awaiting review before Fix 3.**
