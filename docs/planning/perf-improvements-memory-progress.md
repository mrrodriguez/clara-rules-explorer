# Performance Improvements — Progress Log

Tracking implementation of
[`perf-improvements-memory-plan.md`](./perf-improvements-memory-plan.md).

Order of work (priority order):

1. **P0 — Fix 1 + Fix 2:** Schwartzian transform in `sort-facts` + decorate-sort-undecorate in `deterministic-fact-str`/`canonicalize`. (targets the ~23s `sort-facts`)
2. **Perf harness:** bulk-fact load option (supports measuring Fix 3/4 on memory-heavy snapshots).
3. **P1 — Fix 3:** identity-memoized `prune-fns`, scoped to the snapshot callstack only (no cross-call retention).
4. **P1 — Fix 4:** `enrich-annotations-from-session` fast path (pre-normalized `production-annotation`, dedup `rule->session-raw-types`).

Stopping points for review: after each step completes + tests/checks pass + a timing measurement.

Scratch/measurement scripts live under `server/target/tmp/` (gitignored via the root `target` pattern).

---

## Status

- [x] **Fix 1** — Schwartzian transform in `sort-facts`
- [x] **Fix 2** — decorate-sort-undecorate in `deterministic-fact-str`
- [x] **Fix 3** — identity-memoized `prune-fns` (callstack-scoped)
- [x] **Fix 4** — `enrich-annotations-from-session` fast path + boundary normalization hoist

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

---

## Step 2 — Perf harness: bulk-fact load option ✅

**Files:**
- `server/test/clara/server/tools/graph/rules/perf_gen_helpers.clj`
- `server/test/clara/server/tools/graph/perf_test.clj`

### Changes

- Added `bulk-fact` — a heavier, nested map fact (set + nested maps + vectors +
  a function value) with `{:type :bulk/fact}` metadata, so `deterministic-fact-str`
  (canonicalization/sorting) and `prune-fns` both do real work per fact.
- Added `r/defquery all-bulk-facts` matching `:bulk/fact`, so inserted bulk facts
  are retained in working memory (a query-only alpha node is sufficient — verified).
- `build-chain-session` now includes `(var all-bulk-facts)`.
- `run-rules` gains a 2-arity: `(run-rules n-chain n-bulk-facts)` inserts
  `n-bulk-facts` via `r/insert-all` before `fire-rules`; returns `:bulk-fact-count`.
- `perf-test/run-session!` gains a 2-arity mirroring `run-rules`.
- Added `perf-test/run-session-snapshot!` to time `memory/session-snapshot`
  directly (the 24s observation) without the enrich path.

### Verification

- `(perf-test/run-session! 10 500)` → 511 facts in snapshot
  (500 `:bulk/fact` + 10 chain steps + 1 seed) — bulk facts retained via the query.
- `make test` → **202 tests, 1422 assertions, 0 failures, 0 errors**.
- `make lint` → 0 errors, 0 warnings.
- `cljfmt check` on both edited files → clean (ran `cljfmt fix` once for the new code).

### Stopping point

Perf harness is ready to measure memory-heavy snapshots. **Awaiting review before Fix 3.**

---

## Step 3 — Fix 3: identity-memoized `prune-fns` (callstack-scoped) ✅

**Files:**
- `server/src/clara/server/tools/graph/serialize.clj`
- `server/src/clara/server/tools/graph/memory.clj`

### Changes

- `serialize.clj`: split `prune-fns` into a private recursive core `prune-fns*`
  that takes a `^java.util.IdentityHashMap` memo and recurses through it, plus:
  - `prune-fns` — unchanged public one-shot wrapper (fresh memo per call).
  - `memoizing-prune-fns` — returns a `(fn [x] -> pruned)` memoized by object
    identity within the returned fn's scope.
- `memory.clj`: `session-snapshot` creates `prune-fn (serialize/memoizing-prune-fns)`
  once and threads it through `sort-facts` → `deterministic-fact-str`
  (single arity, `[fact prune-fn]`), `build-fact-table`,
  `explanations->fact-match-data`, `build-rule-match-index`, and
  `build-query-match-index`.

**Memo lifetime (per your requirement):** the memo lives only in
`session-snapshot`'s `let` bindings — no `defonce`/atom/global state. It is
released when `session-snapshot` returns, so repeated enrich/snapshot calls
share nothing and cannot leak.

### Verification

- `make test` → **202 tests, 1422 assertions, 0 failures, 0 errors**.
- `make lint` → 0 errors, 0 warnings; `make reflection-check` → clean;
  `cljfmt check` on both edited files → clean.
- `deterministic-fact-str` is a single arity `[fact prune-fn]` (fact first — no
  default-overload). `memory_test`'s determinism tests were updated to pass
  `serialize/prune-fns` explicitly.

### Timing

- `session-snapshot` (5 chain + 4000 heavy bulk facts): **~297 ms**
  (end-to-end, after Fix 1+2+3).
- `prune-fns` 3 passes over 5000 shared nested facts:
  - no shared memo: ~79.5 ms
  - shared memo:    ~19.8 ms (**~4× faster**) — quantifies the dedup.

### Stopping point

Fix 3 is complete and green. **Awaiting review before Fix 4**
(`enrich-annotations-from-session` fast path).

---

## Step 4 — Fix 4: `enrich-annotations-from-session` fast path + normalization hoist ✅

**Files:**
- `server/src/clara/server/tools/graph/annotations.clj`
- `server/src/clara/server/tools/graph/core.clj`
- `server/src/clara/server/tools/graph/analyze.clj` (no change needed — already called `production-annotation`)
- `server/test/clara/server/tools/graph/annotations_test.clj`

### Changes

- `production-annotation` now **assumes a normalized (string-keyed) annotations
  map** — no per-call `every?` scan.
- Deleted the `production-annotation-normalizing` variant.
- Hoisted normalization to the `rulebase-analysis*` boundary in `core.clj`:
  `coerce-annotations-arg` output is normalized **once** (guarded by
  `(every? (comp string? key) …)`) before the two O(P) loops.
- `core.clj`'s two call sites (`build-production-annotation-map`, `production-summary`)
  now use the fast `production-annotation`.
- Removed the now-invalid symbol-keyed `production-annotation` test case
  (behavior moved to `rulebase-analysis`'s boundary normalization, covered by
  `normalize-annotations` tests).

This removes the O(P×A) `every?` scan from **three** O(P) loops: the two in
`rulebase-analysis` and the one in `enrich-annotations-from-session`.

### Verification

- `make test` → **202 tests, 1421 assertions, 0 failures, 0 errors**.
- `make lint` → 0 errors, 0 warnings; `make reflection-check` → clean;
  `cljfmt check` on edited files → clean.
- Boundary normalization verified end-to-end: a **symbol-keyed bare map** passed
  to `rulebase-analysis` still resolves (`rule found: true`, insert-types resolved).

### Timing (post-fix, 2000-chain rulebase, 2002 annotations)

- `rulebase-analysis`: **~317 ms**
- `enrich-annotations-from-session` (empty base): **~69 ms**

(Both paths no longer re-scan all annotation keys per production.)

### Stopping point

All four fixes are complete. **Awaiting final review.**
