# Fix boundary-args parsing — progress

Tracks execution of `fix-boundary-args-parsing-problem.md`. Scope is the fix
parts (§2 + §3.1); §3.2 (threading-macro argument recovery) is deferred as a
new feature, not a fix.

## Status

- [x] Read plan and survey `read-boundary-args` / `trace-boundary-args` callers
- [x] §2: `read-boundary-args` returns nil for non-call usages (no `rest` on symbols)
- [x] Regression tests: `(run! insert! xs)` and `(-> x (…) insert!)` analyze without throwing
- [x] §3.1: emit unresolved placeholder callsite for argument-less usages
- [x] `make test lint reflection-check format-check` green

## Notes

- §3.2 deferred: threading-macro rewrite (`->`/`->>` hand-rolled rewrite,
  span retargeting, synthesized `:source-str`) is an incremental resolution
  gain, not a correctness fix. Revisit once §3.1 makes unresolved threaded
  callsites visible and the data shows it matters.

## Verification

- `make test` — 418 tests, 2639 assertions, 0 failures/errors.
- `make lint` — 0 warnings.
- `make reflection-check` — passed.
- `make format-check` — all files formatted correctly.
- `make regen-fixture` — re-ran, `git status test-resources/` clean (byte-identical,
  no regen needed).

## Deferred (§3.2, not started)

Threading-macro argument recovery (`->`/`->>` hand-rolled rewrite, span
retargeting, synthesized `:source-str`) is untouched. The new
`rule-boundary-threaded-bare` fixture (`(-> m (->fact :t) insert!)`) lands as
an unresolved `insert!` placeholder today — the exact callsite §3.2 would
later upgrade to resolved.
