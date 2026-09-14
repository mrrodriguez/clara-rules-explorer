# Composed artifact persist — Progress

Plan: `composed-artifact-persist-plan.md` · Scope: `server/` (Clojure)

Progress tracker for the implementation. Checked = done + verified.

## Phase 1 — Flatten helper + orchestrator

- [x] `compose/->standard-role-layers` in `compose.clj` (per-role flattening, `:from-layer` stripped)
- [x] `flow/compose-persist!` in `flow.clj` (write layers → merged annotations → analysis parts → digest → manifest)
- [x] `ComposePersistOptions` / `ComposePersistResult` schemas in `artifacts/schema.clj`
- [x] New requires wired (`compose`/`registry`/`manifest` in `flow.clj`; `clojure.walk` in `compose.clj`)

## Phase 2 — Tests

- [x] `compose_test.clj` — `->standard-role-layers` folds the two checked-in units per role and strips derived `:from-layer`
- [x] New `compose_persist_test.clj` — `flow/compose-persist!` writes a composed unit; directory shape, `:slim` survival, manifest provenance
- [x] `bb_report_test.clj` — run `annotations_report.bb` against a composed dir unchanged (`summary`/`consumers`/`edges`/`layers`)

## Phase 3 — CLI wrapper

- [x] `dev/compose_artifacts.clj` — reads an inline EDN opts map (or an `.edn` file path) and calls `flow/compose-persist!`

## Phase 4 — Docs

- [x] `server/docs/persisted-artifacts.md` — short "Composing into a unit" note

## Final gates

- [x] `make format-check`
- [x] `make lint`
- [x] `make reflection-check`
- [x] `make test` (full suite green)

## Log

- 2026-09-14: Progress doc created. Starting Phase 1.
- 2026-09-14: Implemented `compose/->standard-role-layers` + `flow/compose-persist!`; wired schemas; added `compose-persist-test` and the bb composed-unit test; added `dev/compose_artifacts.clj` and the docs note. Full gates green (383 tests, 2346 assertions, 0 failures).
