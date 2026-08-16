# Server Naming Clarity — Progress

Tracking implementation of [`server-naming-clarity-plan.md`](./server-naming-clarity-plan.md).

## Status

- [x] 1. `memory.clj` + `core.clj` sweep (foundation)
- [x] 2. `cache.clj` renames
- [x] 3. `analyze.clj` renames (incl. sig flips + option keys)
- [x] 4. `server.clj` renames (incl. state key + schema)
- [x] 5. `api.clj` renames (handlers, cache/core accessors, routes)
- [x] 6. `main.clj` + `dev/regen_fixture.clj`
- [x] 7. Docstring cross-references (annotations, callsite, index)
- [x] 8. Test files (analyze, core, memory, cache, server, source_sink, api, session_api, integration, perf)
- [x] 9. Server verification (`make format-check lint reflection-check test`)
- [x] 10. Grep gate clean (no old identifiers in `server/src`)

## Verification results

```bash
make format-check    # All source files formatted correctly
make lint            # errors: 0, warnings: 0
make reflection-check # Reflection check passed (no warnings in project code)
make test            # Ran 221 tests containing 1500 assertions. 0 failures, 0 errors.
```

## Notes / decisions applied

- **UI-side changes are intentionally NOT done yet** (per instruction to stop
  before `ui/`). The UI still calls the old `/analysis` route and will need the
  one-fetch-path update (`ui/src/lib/api.ts` → `/rulebase-analysis`) plus the
  `ui/src/lib/types/api.ts` comment touch-ups when we resume.
- `docs/explorer-graph-api.md` **was** updated for the new routes
  (`/v1/analysis` → `/v1/rulebase-analysis`, `/v1/session-snapshot` →
  `/v1/memory-analysis`) and the `memory-analysis` terminology.
- `session-snapshot-from-analysis` was removed (per decision §8.2); callers use
  `(->memory-analysis session known-set)`. This surfaced a latent rulebase
  guard: `cache/->state` now wraps the fresh build in
  `(when (core/working-memory-available? session) …)`, matching the old
  `session-snapshot-from-analysis` nil-for-rulebase behavior.
- Grep-gate note: the plan's `rulebase-summary` pattern still matches
  `handle-get-rulebase-summary` and the `["/rulebase-summary"]` route in
  `api.clj`, which are **intentional keeps** (the route name is unchanged; only
  the `core/rulebase-summary` fn became `core/rulebase-counts`).

## Remaining (deferred until after review)

- `ui/src/lib/api.ts` — `/analysis` → `/rulebase-analysis`.
- `ui/src/lib/types/api.ts` — comment updates (no wire-shape change).
