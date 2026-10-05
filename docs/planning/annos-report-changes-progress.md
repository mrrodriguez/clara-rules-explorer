# Composition-first cross-unit queries — progress

Tracks [`annos-report-changes-plan.md`](annos-report-changes-plan.md).

Status: **done**.

## Part A: record every declared hierarchy edge at analysis

- [x] `core/extract-ancestors-fn` gains a declared-tags source (global hierarchy `:parents`)
- [x] `->ancestors-index` registers declared tags
- [x] `known` set keeps "used by a production" meaning
- [x] Tests (declared tag registered + not known; override; memoization isolated)

## Part B: composed manifest records coverage

- [x] `flow/compose-persist!` writes `:coverage {:unknown-namespaces …}` into the manifest
- [x] `:coverage` is an open map (host keys via `:manifest-fn`)
- [x] Manifest schema admits `:coverage` (`CoverageBlock`)
- [x] Tests (`compose_persist_test`)

## Part C: report subcommands over a composed unit

- [x] `units` (composed units only)
- [x] `unit-edges [<unit>]` (composed units only)
- [x] `entry-points [<unit>]` (any unit), shared with `diff` via `cross-unit`
- [x] Remove `digest` subcommand + helpers + `:`-stripping preprocessing + tests + help row
- [x] Tests (`bb_report_test`, `cross_unit_test`)

## Part D: diff

- [x] `entry-points` section (`:added` / `:resolved` / `:scope`)
- [x] `--rule NAME` becomes `--production NAME` (old flag removed, not aliased)
- [x] Tests (`diff_test`, `bb_report_test`)

## Part E: remove federation

- [x] Delete `artifacts/federate.clj` + `federate_test.clj`
- [x] `shared.selection`: drop `:hierarchy-conflicts` / `->ancestor-conflicts`
- [x] `layout`: remove `:registry-index` / `:registry-digest`
- [x] Reword docstrings that name `federate`
- [x] Delete `annos-report-enhancements-plan.md` + `annos-report-enhancements-progress.md`

## Docs

- [x] `explorer/docs/registry-architecture.md`
- [x] `explorer/docs/persisted-artifacts.md`

## Verification

- [x] `make test` — 454 tests, 3014 assertions, 0 failures
- [x] `make lint` — 0 errors, 0 warnings
- [x] `make format-check` — all formatted
- [x] `make reflection-check` — no warnings
- [x] `make bb-smoke-test` — all 13 bb-loaded namespaces require
- [x] Smoke over a real composition (`units`, `unit-edges`, `unit-edges <repo>`, `entry-points`, `diff --production`)
