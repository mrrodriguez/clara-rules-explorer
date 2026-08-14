# Working-memory production matches — fact identity in `:matches` — Progress Log

Tracking implementation of
[`wm-fact-matches-uniqueness-fixes-plan.md`](./wm-fact-matches-uniqueness-fixes-plan.md).

Order of work (server first, then UI):

1. **Server — `explanations->fact-match-data`:** group by fact id, `distinct` +
   sort bindings, emit `{:fact :bindings}`.
2. **Server — schema:** `FactMatch`; `ProductionActivity` `:matches` retyped.
3. **Server — tests** (§7): Case A / Case B / combined, distinct ids, `:data`
   parity, ordering stability, queries parity.
4. **Server — docs:** `docs/explorer-graph-api.md` response shapes.
5. **UI — types + components** (later, after server review).
6. **UI — key helper** (later).
7. **UI — tests** (later).

Stopping points for review: server side complete + tests/checks pass → review
before UI work.

---

## Status

- [x] Server — `explanations->fact-match-data` group-and-collect
- [x] Server — `FactMatch` / `ProductionActivity` schema
- [x] Server — `update-snapshot-known-set` retypes `:matches`
- [x] Server — tests (memory + session API)
- [x] Server — docs (`explorer-graph-api.md`)
- [x] UI — types (`FactMatch`) + `ActivityCategory` discriminator
- [x] UI — `SessionActivityRow` multi-binding rendering + list keying
- [x] UI — key helper (`stableKeys`) applied to server-supplied keyed lists
- [x] UI — tests (unit + component + Playwright)
- [x] UI — demo data re-scraped to the new response shape

---

## Step 1 — Server implementation ✅

### Files

- `server/src/clara/server/tools/graph/memory.clj` — `explanations->fact-match-data`
  rewritten as group-by-fact-id → `distinct` bindings → sort; `update-snapshot-known-set`
  gained `stamp-match`/`stamp-matches` for the `{:fact :bindings}` shape.
- `server/src/clara/server/graph/api.clj` — added `FactMatch` schema; `ProductionActivity`
  `:matches` retyped to `[FactMatch]`. `:bindings` is `[{s/Keyword s/Any}]` (pruned
  Clara binding maps — keyword-keyed, matching `Explanation`'s own `{s/Keyword s/Any}`).
- `server/test/clara/server/tools/graph/rules/match_uniqueness_test_rules.clj` (new) —
  Case A (`overlapping-conditions`), Case B (`pairwise`), combined (`combined`),
  and a query mirror (`find-pairs`).
- `server/test/clara/server/tools/graph/memory_test.clj` — updated the two old-shape
  tests (`test-rule-query-activity`, `test-multi-fact-match-flattening`) and added five
  new tests.
- `server/test/clara/server/graph/session_api_test.clj` — added FactMatch shape and
  `:data`-parity API tests.
- `docs/explorer-graph-api.md` — updated `/v1/session/rules/:id`, `/v1/session/queries/:id`,
  and the `/v1/session-snapshot` note.

### Key implementation note

Rules must actually `insert!` something for their activations to appear in
`rule-matches` (that index is keyed off the engine's production insertions), so
each fixture rule inserts a `Marker` fact no other rule consumes. Queries need
no RHS — `query-matches` is keyed off query tokens directly.

### Verification

- `make test` → **213 tests, 1470 assertions, 0 failures, 0 errors**.
- `make lint` → 0 errors, 0 warnings.
- `make reflection-check` → passed (no warnings).
- `make format-check` → all source files formatted correctly.

### Stopping point

Server side complete and green. **Awaiting review before UI work.**

---

## Step 2 — UI implementation ✅

### Files

- `ui/src/lib/types/api.ts` — added `FactMatch` (`{ fact, bindings }`);
  `SessionProductionActivityResponse.matches` retyped `FactMatch[] | null`.
- `ui/src/lib/keys.ts` (new) — `stableKeys(items, keyFn)` render-key backstop:
  appends a deterministic `__dup-N` ordinal on collision, leaves duplicate-free
  keys unchanged. Applied to the keyed `{#each}` blocks over server-supplied
  data: `SessionActivityList`, `FactGroup`, `fact-types/[id]/+page`,
  `ReferenceCategory`, `QuerySummary`, `GroupedFilterableNavList`,
  `DynamicCallsiteList`.
- `ui/src/lib/components/rulebase/SessionActivityBlock.svelte` —
  `ActivityCategory` is now a discriminated union (`'facts'` → `SessionFact[]`,
  `'matches'` → `FactMatch[]`).
- `ui/src/lib/components/rulebase/SessionActivityList.svelte` — keys match rows
  on `item.fact.id` and fact rows on `item.id`, both through `stableKeys`.
- `ui/src/lib/components/rulebase/SessionActivityRow.svelte` — match rows render
  the wrapped fact (id, type, origins badge) plus one expandable block per
  binding set, labelled by ordinal when there is more than one; a single-binding
  row keeps the pre-change shape.
- `ui/src/lib/components/rulebase/SessionProductionActivity.svelte` —
  "Active Matches" category now typed `'matches'`.

### UI tests

- `ui/src/lib/keys.test.ts` — distinct keys on duplicates, duplicate-free keys
  unchanged, deterministic ordinals.
- `ui/src/lib/components/rulebase/SessionActivityRow.svelte.test.ts` — a
  `FactMatch` with several binding sets renders one row with one labelled
  expandable block per binding; single binding renders without an ordinal.
- `ui/src/lib/components/rulebase/SessionActivityBlock.svelte.test.ts` — a
  `SessionFact[]` category and a `FactMatch[]` category render side by side;
  empty state renders its text.
- `ui/tests/hierarchy/MatchUniqueness.e2e.ts` — navigates to the `pairwise`
  full view (multi-activation Case B) by client-side navigation and by direct
  load, asserts the multi-binding row renders and no page errors occur.

### Server fixture for the Playwright test

Neither e2e session had a multi-activation rule, so
`server/dev/clara/server/graph/hierarchy_run.clj` now also loads
`match-uniqueness-test-rules` and inserts one Config × three Items — `pairwise`
activates three times and its Config fact appears in `:matches` once with three
binding sets.

### Demo data

`ui/static/demo-data` re-scraped from the updated backend
(`make demo-scrape`) so the demo-mode build matches the new `:matches` shape.
(AuditTrail timestamps drift on each scrape — expected.)

### Verification

- `cd ui && make format check lint` → 0 errors, 0 warnings.
- `pnpm exec vitest --passWithNoTests --run` → **27 tests passed** (node + browser).
- `pnpm exec playwright test --project=hierarchy` → **6 passed**.
- `pnpm exec playwright test --project=loan-app` → **52 passed**.
- `cd server && make lint` → 0 errors, 0 warnings; `make format-check` → clean.

### Remaining

None — UI work complete, awaiting review.
