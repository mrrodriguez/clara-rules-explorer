# Plan: analyze/serialize/memory defects

Source: `server/docs/analyze-defects-todo.md` (6 defects, originally noted on
another branch). This plan reconciles those notes with the current layout on
branch `add-fact-type-hierarchy-api-details` and sequences the work into
milestones that can land independently.

## Status: ✅ All 5 milestones complete (review-passed)

- [x] Milestone 1 — Analysis pipeline correctness (defects 1+2)
- [x] Milestone 2 — `condition->form` boolean groups (defect 6)
- [x] Milestone 3 — `deterministic-fact-str` canonicalization (defect 4)
- [x] Milestone 4 — Detection-map layering semantics (defect 5)
- [x] Milestone 5 — Rulebase-only server support (defect 3)

Final verification: **176 tests, 1259 assertions, 0 failures.**
Format, lint, and reflection all clean (0 warnings).

Review round (kimi-1) addressed:
- Working-memory opt-out enforcement plumbed through to `api/app` → handlers.
- 409 path now covered by automated tests in `session_api_test.clj`.
- `:working-memory?` → `:working-memory-available` in JSON (kebab-case convention).
- `:test` condition fixture corrected to real compiled shape.
- Session handler return schemas updated for 200 | 409.

Review round (kimi-2) addressed:
- 409 cause attribution fixed (raw flag + dynamic capability check).
- Static config resolved once at router construction (no per-request flag
  branching); only session capability stays dynamic.
- Flag renamed to `:working-memory-enabled` (start! option + CLI flag).
- Opt-out test added; summary flag is effective-state; all four
  `GetSession*Response` schemas carry 409; `main.clj` passthrough.

## Reconciliation with current branch state

All six defects are **still present** on this branch. File layout matches the
todo doc; only line numbers have drifted:

| Todo reference | Current location |
|---|---|
| `synth/build-require-clauses` | `analyze/synth.clj:51` (bad clause at :66) |
| `callsite/assign-callsite-ids` | `annotations/callsite.clj:114` (`distinct?` at :139) |
| `server/start!`, `api/get-snapshot` | `graph/server.clj:46`, `graph/api.clj:317` |
| `memory/deterministic-fact-str` | `memory.clj:11` (`sorted-set` at :21) |
| `merge/fold-detection-key` etc. | `annotations/merge.clj:162/174/242` |
| `serialize/condition->form` | `serialize.clj:205` (todo said 124–142) |

Other drift worth noting:

- `core/get-rulebase` (`core.clj:15-16`) currently uses
  `(instance? LocalSession ...)`, and `api/enriched-annotations`
  (`api.clj:271`) does the same. Defect 3's suggested predicate uses
  `satisfies? eng/ISession`. Introducing the predicate is a chance to unify
  all three sites (see Milestone 5).
- The working-memory surface on this branch is **6 routes**: five under
  `/session` (fact-types ×2, facts/:id, rules/:id, queries/:id) plus
  `/session-snapshot` (`api.clj:491-506`). Matches the todo count.
- Existing test coverage is exactly as the todo's "test gap" sections
  suspect:
  - `analyze_test.clj` has `test-analyze-session-rules--reconstructed-ns-fallback`
    but it never refers anything, so the bad `:refer` shape survives.
  - `memory_test.clj` has `test-stable-deterministic-fact-ids` over flat facts
    only.
  - `annotations_merge_test.clj` covers callsite deep-merge, but never a
    detection map arriving from a *second* layer.
  - `serialize_test.clj` covers `serialize-lhs`, never `serialize-lhs-form`.

## Relationships between defects

```
(1) refer clause ──coupled──> (2) empty callsite ids
      fixing 1 alone turns a silent wrong answer into the crash in 2;
      they must land together.

(4) deterministic-fact-str ──affects──> all /session* routes
(3) rulebase-only server ──touches──> the same get-snapshot path
      4 and 3 are independent fixes, but both live in the snapshot/request
      path; 3's guard should be written knowing 4 exists (a rulebase-only
      server never snapshots, so 4 is moot there).

(5) detection-map merge      — independent, semantic change; consumers are
                               fluid, so no audit — just summarize the delta
(6) condition->form          — independent, self-contained
```

Ordering rationale: silent wrong answers and hard crashes in existing behavior
first (1+2, 6, 4), then the semantics change (5), then the additive feature
work (3), which is the only milestone that crosses into the API contract and
possibly the UI.

---

## Milestone 1 — Analysis pipeline correctness (defects 1 + 2, coupled) ✅

**Severity: high (1), medium (2). Land together.**

### 1a. Fix `build-require-clauses` refer shape

- `src/clara/server/tools/graph/analyze/synth.clj:66`
- Applied the fix: `(into [target :refer] ...)` → `[target :refer (vec (sort (map first kvs)))]`
- Also made the `:refer-clojure` clause a list (via `concat`) rather than a vector.

### 1b. Fix `assign-callsite-ids` empty-vector crash

- `src/clara/server/tools/graph/annotations/callsite.clj:139`
- Added `(empty? ids)` guard before `(apply distinct? ids)` — empty input
  was calling `(distinct?)` with 0 args, triggering an ArityException.

### Tests

- `synth`: round-trip test with `clojure.set` refers (the exact gap),
  `:refer-clojure` list-form assertion. Extended
  `test-analyze-session-rules--reconstructed-ns-fallback`.
- `callsite`: `test-assign-callsite-ids--empty` — `(assign-callsite-ids []) => []`

**Deviation from plan:** The `:refer-clojure` fix uses `(concat (list :refer-clojure) ...)`
rather than the plan's `list*` / `cond->` approach. Both produce a seq that
`pr-str` renders as `(:refer-clojure ...)` — functionally identical.

### Verify

`make test lint reflection-check format-check`

---

## Milestone 2 — `condition->form` boolean groups (defect 6) ✅

**Severity: high. Self-contained.**

- `src/clara/server/tools/graph/serialize.clj:205` (`condition->form`)
- Replaced the accumulator-or-leaf `cond->` chain with a `case` dispatch on
  `clara.rules.schema/condition-type`, mirroring `extract-lhs-fact-types`.
- Added `[clara.rules.schema :as schema]` to the ns `:require`.

### Tests

- New `serialize-lhs-form` coverage in `serialize_test.clj`: one case per
  `condition-type` branch — `:fact`, `:test`, `:not`, `:or`, `:and`,
  `:exists`, single-group LHS, accumulator `:from` with nested group.
- Invariant: every fact type in `extract-lhs-fact-types` appears in
  `serialize-lhs-form`'s output.

**No deviations.**

### Verify

`make test lint reflection-check format-check`

---

## Milestone 3 — `deterministic-fact-str` canonicalization (defect 4) ✅

**Severity: high. Independent.**

- `src/clara/server/tools/graph/memory.clj:11-21`
- Replaced `sorted-map`/`sorted-set` canonicalization with `pr-str`-ordered
  vector form using `::map`/`::set` markers (so `#{1 2}`, `[1 2]`, `{1 2}`
  don't collide).

### Tests

- Extended `memory_test.clj` with `test-deterministic-fact-str--shapes`:
  set of maps, map keyed by map, mixed key types, vector of maps (regression),
  plus two determinism assertions (map key order, set element order).

**No deviations.**

### Verify

`make test lint reflection-check format-check`

---

## Milestone 4 — Detection-map layering semantics (defect 5) ✅

**Severity: medium. Semantics change.**

### Fix

- In `merge-detection-maps` (`annotations/merge.clj:174`): non-callsite keys
  now merge from **both** sides (incoming layer `b` wins), not just accumulator
  `a`.
- In `fold-detection-key` (`annotations/merge.clj:242`): the no-callsites
  branch now merges into an existing value instead of replacing it wholesale.
- `normalize-detection-map` (first-fold) behavior kept as is.

### Tests

- In `annotations_merge_test.clj`: both repro routes —
  (A) callsites survive an incoming derived-types-only map;
  (B) incoming derived types survive alongside callsites.
- Provenance reports both layers as contributors for both routes.

### Downstream summary

| Aspect | Old behavior | New behavior |
|---|---|---|
| No-callsites branch (`fold-detection-key`) | Replaced wholesale — a derived-types-only layer wiped callsites | Merges into existing value — callsites survive |
| Non-callsite keys in deep merge (`merge-detection-maps`) | Only accumulator (`a`) keys survived | Both sides survive, incoming (`b`) wins on conflict |
| `gateless-rules-explorer` workaround | Had to withhold `:fact-instance-derived-types` when base had callsites | Workaround no longer necessary — both callsites and derived types survive in the merged output |

**No deviations.**

### Verify

`make test lint reflection-check format-check`

---

## Milestone 5 — Rulebase-only server support + explicit no-working-memory mode (defect 3) ✅

**Severity: medium. Changes the API contract.**

### Step 1 — capability predicate + config option

- Added `working-memory-available?` to `tools/graph/core.clj`:
  `(satisfies? eng/ISession x)`.
- Migrated both `instance? LocalSession` checks (`core.clj:16`, `api.clj:271`)
  to the new predicate. Removed the now-unused `LocalSession` import.
- `start!` (`server.clj:46`) accepts `:working-memory-enabled false` option
  (default `true`).  Named without `?` for CLI ergonomics; also exposed as
  `--working-memory-enabled BOOL` in `main.clj`'s CLI options and forwarded
  by `run-explorer-server`.

### Step 2 — uniform early failure

- Added `with-snapshot` helper in `api.clj` that wraps `get-snapshot`:
  returns 409 `:rulebase-input` when the session is a rulebase.  Session
  capability is checked per request (the session atom can be hot-swapped);
  the static config flag is resolved once at router construction — when
  `:working-memory-enabled` is false, `router` binds all 7 working-memory
  routes (6 `/session/*` + `/session-snapshot`) to a fixed 409
  `:disabled-by-config` handler instead of branching per request.

### Step 3 — startup + docs + capability advertisement

- `start!` logs at startup when working-memory routes are disabled, with the
  reason (rulebase input vs. explicit opt-out).
- `RulebaseSummary` schema now includes `:working-memory-available` boolean.
- `start!` docstring updated to document rulebase acceptance and the new option.

**Deviation from original plan:** `:working-memory?` → `:working-memory-available`
in the JSON API for consistency with existing kebab-case convention.

### Review-driven fixes (kimi-1)

- `:working-memory? false` enforcement: `api/app` now accepts
  `working-memory-enabled?`, threaded through `router` to `with-snapshot`.
  When disabled, `with-snapshot` returns 409 with `:reason :disabled-by-config`
  (distinct from `:rulebase-input`).
- `:test` condition fixture corrected to real compiled shape (`{:constraints ...}`
  without `:type`).
- Session handler return schemas updated to `(s/cond-pre (s/eq 200) (s/eq 409))`.

### Review-driven fixes (kimi-2)

kimi-2 found that the kimi-1 flag threading conflated the two 409 causes and
left gaps; fixed directly:

- **Cause attribution fixed (was HIGH).** `start!` had passed
  `(and wm-available? working-memory?)` to `api/app`, so rulebase input took
  the `:disabled-by-config` branch — the API said "disabled by configuration"
  while the startup log said "started with a rulebase", and `:rulebase-input`
  was unreachable via the shipped path. Now `start!` passes the **raw**
  config flag, and the two causes are attributed precisely (dynamic
  capability check → `:rulebase-input`; static config flag →
  `:disabled-by-config`).
- **Flag renamed:** `:working-memory?` → `:working-memory-enabled`
  (start! option, CLI flag, log/error messages) — predicate-style `?` names
  are awkward in CLI/config maps.
- **Opt-out test added (was MEDIUM).** New `test-working-memory-opt-out-409`:
  live session + flag `false` → all six working-memory routes 409 with
  `"disabled-by-config"`, rulebase routes still 200.
- **Schemas completed (was partial).** All four `GetSession*Response`
  `s/conditional` schemas now carry a 409 clause (`no-working-memory-body`:
  `{:error s/Str :reason s/Keyword}`), matching the inline
  `handle-get-session-fact-types` schema from kimi-1.
- **Summary flag semantic decided (was open).** `:working-memory-available`
  in `RulebaseSummary` is now the **effective state** —
  `(and flag (working-memory-available? session))` — i.e. "working-memory
  routes are served", which is what a client needs to branch without
  probing. A live session with `:working-memory-enabled false` reports
  `false`.
- **`main.clj` passthrough added (was missing).** `run-explorer-server`
  forwards `:working-memory-enabled` from its options to `start!` (nil-safe:
  `start!`'s `:or` default of `true` applies when absent), and a
  `--working-memory-enabled BOOL` CLI flag was added.

### Tests

- `session_api_test.clj`: `test-rulebase-only-409` — session routes return
  409 with `"rulebase-input"` reason, rulebase routes return 200. (Flag
  `true` + rulebase matches the shipped `start!` wiring.)
- `session_api_test.clj`: `test-working-memory-opt-out-409` — live session +
  flag `false` → 409 with `"disabled-by-config"` on all six working-memory
  routes.
- `session_api_test.clj`: `test-rulebase-summary-working-memory-flag` —
  `:working-memory-available` is `false` for rulebase, `true` for live
  session, `false` for live session + opt-out (effective state).
- All existing session handler tests (live session) continue to pass
  (regression guard).

### Pending cross-project follow-through

- [ ] Update `docs/explorer-graph-api.md` with the new `409` status, the two
  `:reason` values (`rulebase-input`, `disabled-by-config`), the
  `:working-memory-available` flag in `RulebaseSummary` (**effective state**
  semantics — false means working-memory routes 409), and the new
  `:working-memory-enabled` start! option / CLI flag.
- [ ] Update `ui/src/lib/types/api.ts` to match the updated
  `RulebaseSummary` contract (add `workingMemoryAvailable: boolean`).

### Verify

`make test lint reflection-check format-check`

---

## Suggested commit/PR granularity

Each milestone is one independently reviewable PR. The implementation order
was severity-first: **1 → 2 → 3 → 4 → 5**. The only hard ordering constraint
(1a and 1b in the same PR) was satisfied.

Every PR runs `cd server && make test format-check lint reflection-check`
before merge; UI-touching PRs (Milestone 5 cross-project follow-through) will
additionally run `cd ui && make format check lint`.
