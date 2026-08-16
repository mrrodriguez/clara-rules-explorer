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
- `docs/explorer-graph-api.md` **was** updated for the new routes and the
  `memory-analysis` terminology.
- `session-snapshot-from-analysis` was removed (per decision §8.2); callers use
  `(->memory-analysis session known-set)`. This surfaced a latent rulebase
  guard: `cache/->state` now wraps the fresh build in
  `(when (core/working-memory-available? session) …)`, matching the old
  `session-snapshot-from-analysis` nil-for-rulebase behavior.
- Grep-gate note: `rulebase-summary` still appears as the **route/handler**
  names (`handle-get-rulebase-summary`, `["/rulebase-summary"]`) in `api.clj`,
  which are intentional keeps — only the core fn became `get-rulebase-counts`.

## Remaining (deferred until after review)

- `ui/src/lib/api.ts` — `/analysis` → `/rulebase-analysis`.
- `ui/src/lib/types/api.ts` — comment updates (no wire-shape change).

---

## Complete rename mapping

For updating external consumers. Builder functions use the `->thing` ctor
idiom; view/accessor functions use `get-*`.

### `clara.server.tools.graph.core`

| Old | New | Notes |
| --- | --- | --- |
| `rulebase-analysis` | `->rulebase-analysis` | builder; arities `[session-or-rulebase annotations]` and `[session-or-rulebase annotations opts]` |
| `rulebase-analysis*` (private) | `->rulebase-analysis*` | impl |
| `build-type-analysis-map` | `->type-analysis-map` | builder |
| `build-production-id-index` | `->production-id-index` | builder |
| `build-dep-graph` | `->dep-graph` | builder |
| `build-consumers-by-type` (private) | `->consumers-by-type` | builder |
| `build-production-map` (private) | `->production-map` | builder |
| `build-production-summary-map` (private) | `->production-summary-map` | builder |
| `build-rule-summary-map` (private) | `->rule-summary-map` | builder |
| `build-query-summary-map` (private) | `->query-summary-map` | builder |
| `build-production-annotation-map` (private) | `->production-annotation-map` | builder |
| `rulebase-summary` | `get-rulebase-counts` | accessor |
| `rules-list` | `get-rules-list` | accessor |
| `queries-list` | `get-queries-list` | accessor |
| `analysis-result` | `get-rulebase-analysis-external-view` | accessor |

### `clara.server.tools.graph.memory`

| Old | New | Notes |
| --- | --- | --- |
| `session-snapshot` | `->memory-analysis` | builder; arities `[session]` and `[session known-set]` |
| `session-snapshot-from-analysis` | **removed** | use `(->memory-analysis session (-> analysis :fact-types keys set))`; guard with `working-memory-available?` for rulebase-only inputs |
| `update-snapshot-known-set` | `update-memory-analysis-known-set` | updater |
| `get-session-rule-activity` | `get-rule-activity` | accessor |
| `get-session-query-activity` | `get-query-activity` | accessor |
| `build-id-map` (private) | `->id-map` | builder |
| `build-used-by-index` (private) | `->used-by-index` | builder |
| `build-origin-map` (private) | `->origin-map` | builder |
| `build-fact-table` (private) | `->fact-table` | builder |
| `build-fact-type-index` (private) | `->fact-type-index` | builder |
| `build-id-name-index` (private) | `->id-name-index` | builder |
| `build-rule-match-index` (private) | `->rule-match-index` | builder |
| `build-query-match-index` (private) | `->query-match-index` | builder |

### `clara.server.tools.graph.fact-types`

| Old | New | Notes |
| --- | --- | --- |
| `build-ancestors-index` | `->ancestors-index` | builder |
| `build-fact-type-summary-map` | `->fact-type-summary-map` | builder |
| `build-fact-type-id-index` | `->fact-type-id-index` | builder |
| `fact-types-list` | `get-fact-types-list` | accessor |
| `session-fact-types-summary` | `get-session-fact-types-summary` | accessor |
| `raw-type-ns` | `get-raw-type-ns` | accessor |
| `known-type-names` | `get-known-type-names` | accessor |

### `clara.server.tools.graph.analyze`

| Old | New | Notes |
| --- | --- | --- |
| `build-analysis-from-namespaces` | `->rule-source-analysis-from-namespaces` | builder; option `:initial-analysis` → `:initial-rule-source-analysis` |
| `analyze-session-rules` | `->rule-source-analysis` | builder |
| `generate-annotations-from-analysis` | `->annotations-from-rule-source-analysis` | builder; option `:analysis` → `:rule-source-analysis` |
| `add-auto-detected-annotations` | `add-memory-derived-insert-type-detections` | updater; **signature flipped** to `[annotations memory-analysis]` |
| `enrich-annotations-from-session` | `merge-memory-derived-insert-types` | updater; **signature flipped** to `[annotations session]` |
| `enrich-annotations-from-session*` | `merge-memory-derived-insert-types*` | updater; **signature flipped** to `[annotations session]`; returns `{:annotations … :memory-analysis …}` (was `:snapshot`) |
| `extract-session-rule-names` | `extract-rule-names` | accessor |
| `extract-session-namespaces` | `extract-rule-namespaces` | accessor |
| `session-rules-by-ns` (private) | `rulebase-rules-by-ns` | |
| `session-rule-fq-names` (private) | `all-rule-fq-names` | |
| `rule->session-raw-types` (private) | `rule->memory-derived-raw-types` | |
| `GenerateAnnotationsOptions` (schema) | `RuleSourceAnnotationsOptions` | |

### `clara.server.graph.cache`

| Old | New | Notes |
| --- | --- | --- |
| `create` | `->cache` | builder |
| `build-state` (private) | `->state` | builder |
| `analysis` | `get-rulebase-analysis` | accessor |
| `snapshot` | `get-memory-analysis` | accessor |

State map keys: `:analysis` → `:rulebase-analysis`, `:snapshot` →
`:memory-analysis`.

### `clara.server.graph.server`

| Old | New | Notes |
| --- | --- | --- |
| `build-annotations` | `->resolved-annotations` | builder |
| `build-annotations*` | `->resolved-annotations*` | builder; returns `{:annotations … :memory-analysis …}` |
| `build-static-layers` (private) | `->static-layers` | builder |
| `build-auto-detect-annotations` (private) | `->auto-detect-annotations` | builder |
| `MemorySnapshot` (schema) | `MemoryAnalysis` | |
| `:memory-snapshot` (state key) | `:memory-analysis` | |

### `clara.server.graph.api`

| Old | New | Notes |
| --- | --- | --- |
| `handle-get-analysis` (private) | `handle-get-rulebase-analysis` | |
| `handle-get-session-snapshot` (private) | `handle-get-memory-analysis` | |
| `with-snapshot` (private) | `with-memory-analysis` | |

### HTTP routes (server contract)

| Old | New |
| --- | --- |
| `GET /v1/analysis` | `GET /v1/rulebase-analysis` |
| `GET /v1/session-snapshot` | `GET /v1/memory-analysis` |

### `clara.server.tools.graph.perf-test` (test harness)

| Old | New |
| --- | --- |
| `run-session!` | `run-rules!` |
| `run-session-snapshot!` | `run-memory-analysis!` |
| `run-analyze-session-rules!` | `run-rule-source-analysis!` |
| `run-analysis!` | `run-rulebase-analysis!` |
| `run-generate-annotations-from-analysis!` | `run-annotations-from-rule-source-analysis!` |
| `run-enrich-annotations-from-session!` | `run-merge-memory-derived-insert-types!` |

State keys: `:session-snapshot` → `:memory-analysis`,
`:session-rules-analysis` → `:rule-source-analysis`,
`:analysis` → `:rulebase-analysis`,
`:memory-enriched-annotations` → `:memory-derived-annotations`.
