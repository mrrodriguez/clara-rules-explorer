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
- [ ] UI — types/components/key-helper/tests (after server review)

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

## Remaining (after review)

UI work per plan §5–§6: types, `ActivityCategory` discriminator, key helper,
multi-binding rendering, and the §7 UI/Playwright tests.
