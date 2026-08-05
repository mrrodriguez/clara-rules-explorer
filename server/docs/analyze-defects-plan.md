# Plan: analyze/serialize/memory defects

Source: `server/docs/analyze-defects-todo.md` (6 defects, originally noted on
another branch). This plan reconciles those notes with the current layout on
branch `add-fact-type-hierarchy-api-details` and sequences the work into
milestones that can land independently.

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

## Milestone 1 — Analysis pipeline correctness (defects 1 + 2, coupled)

**Severity: high (1), medium (2). Land together.**

### 1a. Fix `build-require-clauses` refer shape

- `src/clara/server/tools/graph/analyze/synth.clj:66`
- Apply the suggested fix:
  `(into [target :refer] ...)` → `[target :refer (vec (sort (map first kvs)))]`
- Also make the `:refer-clojure` clause a list, not a vector (the "adjacent,
  lower priority" item — cheap, same function's output contract).

### 1b. Fix `assign-callsite-ids` empty-vector crash

- `src/clara/server/tools/graph/annotations/callsite.clj:139`
- Guard the `distinct?` call: `(or (empty? ids) (apply distinct? ids) ...)`
  (or early-return `callsites` when empty).

### Tests

- `synth`: round-trip test — `reconstruct-ns-source` output must
  `read-string` + `eval` as a legal ns form; stronger variant asserts
  `ns-refers`/`ns-aliases`/`ns-imports` of the evaled ns match the original.
  Extend the existing `test-analyze-session-rules--reconstructed-ns-fallback`
  fixture with a namespace that refers something (e.g. `clojure.set`), which
  is the exact gap that let this through.
- `callsite`: `(assign-callsite-ids []) => []` next to the existing
  single/duplicate/collision cases (in `analyze_test.clj` around :506/:538,
  or a dedicated callsite test ns if one exists by then).

### Verify

`cd server && make test lint reflection-check format-check`

---

## Milestone 2 — `condition->form` boolean groups (defect 6)

**Severity: high. Self-contained; good candidate to land first if a quick win
is wanted.**

- `src/clara/server/tools/graph/serialize.clj:205` (`condition->form`),
  reached from `serialize-lhs-form` (:225).
- Replace the accumulator-or-leaf `cond->` chain with a `case` dispatch on
  `clara.rules.schema/condition-type`, mirroring `extract-lhs-fact-types`
  (`core.clj:16-29`) — suggested implementation in the todo doc is already
  verified against repros.
- Add `[clara.rules.schema :as schema]` to the ns `:require`.

### Tests

- New `serialize-lhs-form` coverage in `serialize_test.clj` (it has
  `test-serialize-lhs` but nothing for `-form`): one case per
  `condition-type` branch — `:not`, `:or`, `:and`, `:exists`, a group nested
  inside an accumulator's `:from`, and a rule whose entire LHS is one group.
  Assert each operator keyword survives into the rendered string.
- Add the cheap invariant from the todo: every fact type in
  `extract-lhs-fact-types` appears in `serialize-lhs-form`'s output for the
  same LHS.

### Verify

`make test lint reflection-check format-check`

---

## Milestone 3 — `deterministic-fact-str` canonicalization (defect 4)

**Severity: high. Independent.**

- `src/clara/server/tools/graph/memory.clj:11-21`
- Replace `sorted-map`/`sorted-set` canonicalization with the `pr-str`-ordered
  vector form from the todo (with `::map`/`::set` markers so `#{1 2}`, `[1 2]`,
  `{1 2}` don't collide). Return value is only a sort key, so changing the
  canonical representation is safe within a process.
- Note for reviewers: the sort key is not persisted (ids are per-snapshot), so
  no cross-version stability concern; confirm that before merging.

### Tests

- Extend `memory_test.clj` (near `test-stable-deterministic-fact-ids`, :190)
  with the four-shape table from the todo (set of maps, map keyed by map,
  mixed key types, vector of maps) plus two determinism assertions: same map
  in different key orders, same set in different element orders → identical
  strings.

### Verify

`make test lint reflection-check format-check`

---

## Milestone 4 — Detection-map layering semantics (defect 5)

**Severity: medium. Semantics change, but no consumer audit needed — all
consumers are fluid until the API stabilizes and these defects are resolved.
Do not contort the fix to preserve the old behavior; just record what
changed (see "Downstream summary" below).**

### Fix

- In `merge-detection-maps` (`annotations/merge.clj:174`): merge non-callsite
  keys from **both** sides (incoming layer `b` wins), per the todo's diff.
- In `fold-detection-key` (`annotations/merge.clj:242`): the no-callsites
  branch merges into an existing value instead of replacing it wholesale.
- Keep `normalize-detection-map` (first-fold) behavior as is; it already
  keeps non-callsite keys.

### Tests

- In `annotations_merge_test.clj`: both repro routes from the todo —
  (A) callsites survive an incoming derived-types-only map;
  (B) incoming derived types survive alongside callsites.
- Assert provenance still reports both layers as contributors.

### Downstream summary (deliverable)

The milestone ends with a short written summary of the semantic delta, for
fixing downstream layers (e.g. `gateless-rules-explorer`'s working-memory
layer, which currently works around this by withholding
`:fact-instance-derived-types` when the base has callsites — that workaround
becomes unnecessary). Cover:

- old vs. new behavior of the no-callsites branch (replace → merge);
- old vs. new winner for non-callsite keys in a deep merge (accumulator-only
  → both sides, incoming wins);
- which downstream workarounds can now be deleted.

### Verify

`make test lint reflection-check format-check`

---

## Milestone 5 — Rulebase-only server support + explicit no-working-memory mode (defect 3)

**Severity: medium. Changes the API contract — do it last. Consumer contracts
are fluid; design the API the way it should be, then document it.**

Scope is broader than the todo's defect 3: in addition to accepting a
rulebase, `start!` gains an explicit option to disable working-memory
analysis even when handed a live session. So working-memory availability
becomes a **server configuration**, not something inferred from the input
type alone.

### Step 1 — capability predicate + config option

- Add `working-memory-available?` (e.g. in `tools/graph/core.clj` or
  `memory.clj`): `(satisfies? eng/ISession x)`.
- `start!` (`server.clj:46`) accepts an option such as
  `:working-memory? false` (name TBD at implementation). Effective
  availability = `(and (:working-memory? opts true) (working-memory-available? session-or-rulebase))`.
- Store the resolved flag alongside `session-atom` (e.g. in the same config
  map threaded to handlers) so `get-snapshot` and route setup can read it
  without re-deriving.
- Consider migrating the two existing `instance? LocalSession` checks
  (`core.clj:16`, `api.clj:271`) to the predicate in the same pass, or
  deliberately leave them and note why.

### Step 2 — uniform early failure

- Guard in `api/get-snapshot` (`api.clj:317`) so all six working-memory
  routes inherit one behavior: return
  `{:status 409 :body {:error ... :reason :no-working-memory}}`
  (409 stays distinguishable from the existing 404 "fact not found").
  Alternative: omit the `/session*` routes at router construction when
  disabled — the explicit 409 is preferred since "disabled by configuration"
  should not look like "not found". Decide finally at implementation.

### Step 3 — startup + docs + capability advertisement

- `start!`: log once at startup when working-memory routes are disabled, and
  *why* (rulebase input vs. explicit opt-out). Update the docstring: it
  should document both accepting a rulebase and the new option.
- Advertise the capability: a `:working-memory?` flag on an existing
  summary/meta response (rulebase-summary is the natural home — schema at
  `api.clj:22`).

### Tests

- New suite (likely in `session_api_test.clj` or `integration_test.clj`),
  three configurations:
  1. rulebase only → rulebase routes 200, working-memory routes return the
     chosen status, not 500;
  2. session + `:working-memory? false` → same behavior as (1);
  3. session + default opts → unchanged current behavior (regression guard).

### Cross-project follow-through (per `AGENTS.md`)

- Update `docs/explorer-graph-api.md` with the new status/reason, the
  `:working-memory?` flag, and the new `start!` option.
- Update `ui/src/lib/types/api.ts` to match the final contract, then verify
  via the UI Makefile: `cd ui && make format check lint` (and `make test` if
  UI behavior changes beyond types).

---

## Suggested commit/PR granularity

Each milestone is one independently reviewable PR, in order 2 → 3 → 1 → 4 → 5
(if quick wins first) or 1 → 2 → 3 → 4 → 5 (if severity-first). The only hard
ordering constraint is **1a and 1b in the same PR**.

Every PR runs `cd server && make test format-check lint reflection-check`
before merge; UI-touching PRs (Milestone 5 only) additionally run
`cd ui && make format check lint` (see `ui/Makefile`, which mirrors the
server Makefile's target layout). Only Milestone 5 touches the API contract
and the UI; since
consumer contracts are fluid until the API stabilizes, Milestones 4 and 5
optimize for the right end-state API, not backward compatibility — each ends
with a written summary of the delta for downstream fixes.
