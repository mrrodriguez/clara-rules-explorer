# Canonicalize reader gensyms — progress

Tracks execution of `canonicalize-gensyms-plan.md`. Keep in sync as work lands.

## Status

- [x] Read plan and survey call sites
- [x] Add `clara.explorer.utils/canonicalize-gensyms`
- [x] Canonicalize the reading path at callsite emission
- [x] Route `*form-printer*` call sites through `serialize/print-form`
- [x] Unit tests for `canonicalize-gensyms`
- [x] Counter-independence regression tests
- [x] In-memory ↔ persisted callsite-id alignment test
- [x] Update pinned printed-form expectations (none needed beyond existing regex)
- [x] `make test lint reflection-check format-check` green
- [x] Consumer-impact note in `explorer-renaming-migration.md`

## Deviation from the plan (recorded)

The plan §3.2/§4 put canonicalization in the `analyze/kondo.clj` readers
(`read-boundary-args`, `read-ctor-form`, `read-init-form`). That broke local
tracing: `analyze.callsite/trace-local-form` follows a gensym local
(`resolved__NNN__auto__`) to its binding init by matching the *source* name
against kondo's `:locals`/`:local-usages`, and a canonicalized
`resolved__0__auto__` no longer matches. `test-callsite-resolver-fn` caught this
(resolver stopped seeing `(var extract-doc-meta)`).

Instead, canonicalization happens at callsite emission in
`analyze/callsite.clj`, after tracing:

- `resolve-boundary-callsites` — `:source-str (pr-str (canonicalize arg))`
- `resolve-ctor-callsite` — canonicalize `ctor-form` before `:source-str` and
  the resolver `:arg-form`
- `->callsite-resolver-context` — canonicalize the traced `:arg-form`

`analyze/kondo.clj` is unchanged. Readers keep returning raw forms so kondo
positions and local-binding lookups stay valid; persisted text is canonical at
the one place it becomes a string.

Callsite ids stay aligned with persistence because canonicalization happens
*before* `ann.callsite/assign-callsite-ids` runs in `analyze/extract-insert-types`:
the in-memory `:source-str` is already canonical when the id is hashed, and the
same canonical `:source-str` is what `ann.merge/->layer` writes (and what
`derive-callsite-ids` re-hashes on read). Locked in by
`canonicalized-source-str-id-aligns-on-read` in `annotations_merge_test.clj`.

## Notes

- Confirmed reader outputs (probe in `explorer/target/tmp/gensym_probe.clj`):
  positional `p1__13#` / `p2__14#`, rest `rest__17#`, auto `x__5__auto__`,
  default `(gensym)` `G__20`.
- `<ord>` counter is 0-based (first appearance → `0`), shared across all four
  patterns within a single `canonicalize-gensyms` call.
- No test rule source uses `#(…)` today; the auto-gensym assertions in
  `analyze_test.clj` use `#"resolved__\d+__auto__"` and keep matching the
  canonical `resolved__0__auto__`.
- Golden regen test (`regen_example_test.clj`) already normalizes
  `__\d+__auto__` and callsite hashes, so it stayed green.

## Verification

- `make test` — 416 tests, 2588 assertions, 0 failures/errors.
- `make lint` — 0 warnings.
- `make reflection-check` — passed.
- `make format-check` — clean.

## Follow-up: stale checked-in fixtures (2026-09-30)

Symptom: the UI rule-full view showed a raw gensym in "Dynamic Insert
Callsites" (`resolved__44987__auto__`) next to a canonical `:rhs-form`
(`resolved__0__auto__`) for
`loan-doc-rules/extract-doc-meta-rule`. This was not a summary-vs-full
divergence — both fields come from one `GET /v1/rules/:id` response.
It was stale-layer vs live-form in that response:

- `:rhs-form` is serialized live from the Clara production through
  `serialize/print-form` (canonical).
- The demo backend (`make demo-run -l …loan-doc-rules-annotations.edn`)
  serves that fixture as its `:clara.rules.analyze/generated` layer, and
  `serve/->static-layers` skips live generation when a source layer
  already carries that id — so the served `:source-str` was 100% the
  fixture's pre-canonicalization snapshot (`resolved__44987__auto__`,
  old callsite-id `…032ed471:0`).
- The `test-resources/rules-annos/` examples had the same staleness
  (`resolved__49087__auto__` in both `:rhs-form` and `:source-str` —
  consistently old, but raw).

Fix (no code change — the documented one-time churn from plan §6):
`make regen-fixture` + `make regen-artifacts`. Both now emit
`resolved__0__auto__` with the stable callsite-id `…8a786e23:0`
(identical across the two regens, confirming counter-independence).
After regen the demo composition serves a single canonical callsite,
consistent with `:rhs-form` (verified by merging props + fixture and
serving through `core/->rulebase-analysis`).

- `make test` — 417 tests, 2624 assertions, 0 failures/errors.
- `make lint` / `make reflection-check` / `make format-check` — clean.
