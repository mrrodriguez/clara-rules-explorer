# Canonicalize reader gensyms — progress

Tracks execution of `canonicalize-gensyms-plan.md`. Keep in sync as work lands.

## Status

- [x] Read plan and survey call sites
- [x] Add `clara.explorer.utils/canonicalize-gensyms`
- [x] Canonicalize the reading path at callsite emission
- [x] Route `*form-printer*` call sites through `serialize/print-form`
- [x] Unit tests for `canonicalize-gensyms`
- [x] Counter-independence regression tests
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

- `make test` — 415 tests, 2586 assertions, 0 failures/errors.
- `make lint` — 0 warnings.
- `make reflection-check` — passed.
- `make format-check` — clean.
