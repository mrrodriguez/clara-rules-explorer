# Scoped merges and composed-unit provenance — progress

Status: **Done** · Tracks
[`scoped-merge-and-composed-provenance-plan.md`](scoped-merge-and-composed-provenance-plan.md)

## Checklist

### §1 Narrowing stops at the analysis

- [x] Add `registry/narrow-annotations` beside `narrow-analysis` (filter `:annotations` + `:provenance` by namespace part of rule-name keys; non-production keys stay)
- [x] Apply it per unit in `compose/fold-layers`, before the fold
- [x] Apply it per unit in `compose/->standard-role-layers`, before the fold
- [x] Record the per-unit `:namespaces` filter in the persisted role layer `:source`
- [x] Test: narrowed folded annotation count equals narrowed composed rule count
- [x] Test: `/v1/annotations` and `/v1/rules` agree in registry mode (server test)

### §2 A composed unit cannot say how stale it is

- [x] Enrich `compose-persist!` `:analysis-run :units` entries to `{:repo :branch :sha :created}`
- [x] Add the composed `:staleness` block (`review-when-any-source-sha-drifts` + per-source shas)
- [x] Regenerate the checked-in composed fixture manifest
- [x] Update `compose-persist-test` and golden test expectations

### §3 Read back what `persist!` wrote, and let an index name its question

- [x] Add `federate/read-index` / `federate/read-digest`
- [x] Add `:label` through `->index` (into `:scope`) and `persist!`
- [x] Surface `:scope` (incl. label) in `->digest`
- [x] Test: round-trip readers and label recording

### §4 `federate/diff`

- [x] Implement `federate/diff` (units, unit-edges, fact-types, entry-points, orphans, hierarchy)
- [x] Test: self-diff is empty in every key
- [x] Test: branch-variant swap reports only touched edges/types

## Log

- Implemented §1: `registry/narrow-annotations` (namespace-part key filter on `:annotations`/`:provenance`; keys without a namespace stay), applied per unit in both `compose` fold paths before folding, and recorded the per-unit filter in `->standard-role-layers`'s `:source :namespaces`.
- Implemented §2: `flow/compose-persist!` now writes `:analysis-run :units` entries enriched with `:sha`/`:created` (and `:branch` when present) from each source's `registry/unit-info`, and a `:staleness` block with policy `review-when-any-source-sha-drifts` + per-source shas. Regenerated `test-resources/rules-annos/` via `make regen-artifacts`; updated `regen-example-test`'s `normalize-manifest` to strip the nested environment fields (`:sha`/`:created` in `:analysis-run :units` and `:staleness :sources`) so the golden comparison stays generation-only.
- Implemented §3: `federate/read-index` / `read-digest`, optional `:label` through `->index` (into `:scope`) and `persist!` (recorded before write), and `->digest` now carries `:scope`.
- Implemented §4: `federate/diff` over two index values — units (added/removed/rebased), unit-edges (added/removed/`:via` changes), fact-types (producer/consumer changes), entry-points, orphans, and hierarchy conflicts.
- Added/updated tests in `compose_test`, `registry_server_test`, `compose_persist_test`, `federate_test`, `regen_example_test`.

## Verification

- `make test` — 399 tests, 2433 assertions, 0 failures / 0 errors
- `make format-check` — clean
- `make lint` — 0 errors / 0 warnings
- `make reflection-check` — passed
