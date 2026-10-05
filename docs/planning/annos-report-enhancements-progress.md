# `annotations_report.bb` enhancements — progress

> **Superseded.** See
> [`annos-report-changes-progress.md`](annos-report-changes-progress.md):
> Part A (`digest`) and its tests were removed there; Part B (`production`)
> landed and stands.

Tracks work against [`annos-report-enhancements-plan.md`](annos-report-enhancements-plan.md).

Status: **done** (with a deviation from the plan — see Part B).

## Part A: `digest` subcommand

- [x] `digest` reads `registry-digest.edn` in the target directory via `layout/artifact-files :registry-digest`
- [x] No key: `:summary` + per-key element counts
- [x] Keys: `select-keys`; accepts `coverage` / `:coverage`; unknown key fails listing present keys
- [x] `--edn` prints the selected value as EDN
- [x] Missing digest fails, naming the file and `federate/persist!` — and now
      names the dir that does have one (a federation output dir), vs a unit
- [x] Added to `subcommands` / `help`
- [x] Tests in `bb_report_test.clj`

The optional `coverage` text view was deferred per the plan — `digest coverage
hierarchy-conflicts` covers the named check, and no caller asked for more.

## Part B: one `production` subcommand (consolidated)

The plan proposed a separate `rule` (annotation) and `production` (analysis)
reader. We consolidated them into a single `production` subcommand — `rule` and
`production` name the same entity, and `production` is the general term that
also covers queries.

- [x] `diff/production-entry` extracted from `productions-of`
- [x] `diff/read-production` (bb-loadable) reads only the needed parts
- [x] `production <fq-name>`: name resolution over rules+queries, text output, `--part lhs|rhs|props`, `--edn`
- [x] `--annotations` prints the merged annotation (the old `rule` view); a production with no annotation says so
- [x] `--file` selects the annotation file for `--annotations`, and now includes
      `memory` (the runtime-proven delta) alongside auto/agent/merged
- [x] `--part` and `--annotations` are mutually exclusive
- [x] `rule` subcommand removed; help updated
- [x] Tests in `bb_report_test.clj`

## Docs

- [x] `explorer/docs/persisted-artifacts.md`: querying rows + writer guidance

## Verification

- [x] `make test` — 462 tests, 3055 assertions, 0 failures
- [x] `make lint` — 0 errors, 0 warnings
- [x] `make format-check` — all formatted
- [x] `make reflection-check` — no warnings
- [x] `make bb-smoke-test` — all 12 bb-loaded namespaces require
- [x] Smoke: real unit `production` on a rule, a query, `--annotations`, and
      `--file auto --annotations`; `digest` against a composed unit reports the
      federation-vs-unit distinction
