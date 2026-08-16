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

## Phase 2 — Naming (`:rule-to-boundary-path` / `:boundary-to-constructor-path`)

- [x] 13. Keep `:boundary-var-name-sym` / `:boundary-in-var` as-is (decided — both are var names).
- [x] 14. `analyze/callsite.clj` — schema + emitters + `rule-to-boundary-path-for` rename.
- [x] 15. `analyze.clj` — ctx key rename.
- [x] 16. `serialize.clj` + `graph/api.clj` + `annotations/rebase.clj`.
- [x] 17. Server tests (`analyze_test`, `annotations_merge_test`, `serialize_test`).
- [x] 18. Regenerate `loan-doc-rules-annotations.edn`.
- [x] 19. Server docs (`rule-annotations.md`, `analyze-pipeline-concepts.md`).
- [x] 20. Server verification.

## Phase 3 — UI (final)

- [ ] 21. `ui/src/lib/types/api.ts` — `ViaChain` gains both renamed path keys.
- [ ] 22. `ui/.../DynamicCallsiteList.svelte` — full ordered chain (rule path → boundary → constructor path), shared anchor once.
- [ ] 23. UI unit tests (RHS-only / helper+ctor / helper-no-ctor) + loan-doc helper-insert extraction for e2e.
- [ ] 24. UI verification (`make format check lint test`).

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

- **Phase 3 (UI)** — types + full-chain rendering + loan-doc helper-insert
  extraction for e2e; not started.
- Bump `nubank/matcher-combinators` 3.9.1 → 3.11.0 once the local Maven cache is
  writable (or the dependency is otherwise obtainable).
