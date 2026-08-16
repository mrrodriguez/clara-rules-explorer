# Callsite provenance (`:via`) fixes — Progress

Tracking implementation of [`analyze-callsite-provenance-fixes-plan.md`](./analyze-callsite-provenance-fixes-plan.md).

## Status

- [x] 1. `analyze/callsite.clj` — shared `shortest-call-path`, `rule-path-for`,
      `via-base`; boundary pass builds `:via` + merges dropped provenance;
      ctor pass adds `:boundary-in-var`/`:rule-path`; schemas updated.
- [x] 2. `analyze.clj` — `extract-insert-types` signature + ctx threading
      (`:rule-var`, `:rule-path-for`, `:dropped-ctor-provenance`).
- [x] 3. `serialize.clj` — stringify `:boundary-in-var` + `:rule-path`.
- [x] 4. `api.clj` — `ViaChain` schema (+ docstring caveat).
- [x] 5. `annotations/rebase.clj` — remap `:boundary-in-var` + `:rule-path`.
- [x] 6. `deps.edn` — add `nubank/matcher-combinators` to `:test`.
- [x] 7. `analyze_test.clj` — matcher-combinators conversion + new fixtures/tests.
- [x] 8. `rules/analyze_test_rules.clj` — new provenance fixture vars.
- [x] 9. Regenerate `loan-doc-rules-annotations.edn`.
- [x] 10. Merge/serialize test coverage.
- [x] 11. Docs (`rule-annotations.md`, `analyze-pipeline-concepts.md`).
- [x] 12. Server verification (`make format format-check lint reflection-check test`).

## Verification results

```bash
make format           # ok
make format-check     # All source files formatted correctly
make lint             # errors: 0, warnings: 0
make reflection-check # Reflection check passed (no warnings in project code)
make test             # Ran 229 tests containing 1554 assertions. 0 failures, 0 errors.
```

## Notes / decisions applied

- **matcher-combinators version:** the plan said 3.11.0, but the local
  `~/.m2/repository` is write-protected (macOS `com.apple.provenance` on the
  whole tree), so 3.11.0 could not be downloaded. 3.9.1 was already cached and
  has the same `match?` / `embeds` / `equals` API — used that instead (per
  instruction "use whatever version you have available"). Bump to 3.11.0 later.
- **`match?` is `is`-macro integrated** (matcher-combinators), not a plain fn —
  assertions read `(is (match? (resolved-detection …) actual))`.
- `resolved-detection` / `unresolved-detection` now return
  `matcher-combinators` matchers asserting only the keys each test cares about
  (`:via` / `:callsite-id` are covered by focused tests). This kept the existing
  19 call sites from churning on the new keys.
- New fixtures live in `rules/analyze_test_rules.clj` under a dedicated
  "Callsite `:via` provenance fixtures" section; six focused tests cover the
  Section-9 scenarios (direct RHS, two-hop `:rule-path`, dropped-ctor-in-helper,
  no-constructor boundary arg, ambiguous double-drop, two-path determinism).
- `loan-doc-rules-annotations.edn` was regenerated: boundary-path entries gain
  `:via`, ctor entries gain `:boundary-in-var`; `:callstack` unchanged.

## Remaining

- Bump `nubank/matcher-combinators` 3.9.1 → 3.11.0 once the local Maven cache is
  writable (or the dependency is otherwise obtainable).
- UI-side changes: none needed — the `:via` keys are additive and the API types
  already model `ViaChain` as an open optional-key map.
