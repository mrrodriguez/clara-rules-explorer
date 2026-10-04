# Unit diff improvements — progress

Tracks implementation of [`unit-diff-improvements-plan.md`](unit-diff-improvements-plan.md).

Status: **done**.

## Part A — production identity is name, not unit

- [x] Remove `:unit` from `compared-fields` (`diff.clj`)
- [x] Add `diff-units` — `:units` key compared from each manifest's `:analysis-run :units`
- [x] Wire `:units` into `diff`; `empty-diff?` accounts for it
- [x] `->text`: `units:` count line + `changed units:` section (composed sides only)
- [x] Tests: variant-address composed units, added/removed repo, ruleset-unit unchanged, example minus `[unit]`

## Part B — fact types a scope-only namespace brings with it

- [x] Add hierarchy helpers (`ancestors-of`, `descendants-of`, `production-touches-type?`, `scope-touched?`)
- [x] Partition `diff-fact-types` `:added`/`:removed` by the shared-production rule
- [x] `:scope :fact-types` vector; `->text` `scope fact-types:` section + `scope:` count line
- [x] Tests: promo.overrides example, ancestor match, descendant insert, removed type, both-touched stays

## Docs

- [x] `explorer/docs/persisted-artifacts.md` `diff` paragraph (Part A + Part B + limit)

## Verification

- [x] `make test` (explorer) — 458 tests, 2994 assertions, 0 failures / 0 errors
- [x] `make lint` (explorer) — 0 errors, 0 warnings
- [x] `make format-check` (explorer) — clean
- [x] `make reflection-check` (explorer) — passed
- [x] End-to-end `bb annotations_report.bb … diff …` smoke checks (composed + variant + `--rule`)

## Notes

- `fact-types-test` gained a shared production so its added/removed types are
  "touched by a shared production" and stay in `:fact-types` under the new
  Part-B rule (the old no-production fixture would now classify them as scope).
- The `scope:` count line now ends in `…, N fact-types` (Part B), which is a
  small text change to ruleset-unit output; the "reads exactly as today"
  promise in Part A governs only the `units:` line/section, which is gated on a
  composed side.
