# Locals-Expand Analysis Progress

Tracks implementation of `locals-expand-ana-plan.md`.

- [x] Step 1: init-span reader in `analyze.kondo` (`init-form-span`)
- [x] Step 2: position indexes in `analyze.index` (`var-usages-by-filename`, `local-usages-by-filename`; range queries subsume exact lookup)
- [x] Step 3: fixpoint expansion in `analyze.callsite` (per-`TracedArg` region set)
- [x] Step 4: ownership collapsed to the span set as the single mechanism — R1′/R2′ replace legacy R1/R2/R3; `usage-encloses?`, `sibling-usages` plumbing, and the dead `:traced-binding` field removed
- [x] Step 7: generic boundary chain multi-hop local tracing — `find-local-binding` takes an explicit span, `trace-local-form` threads the current span via `init-form-span`; `usage->span` shared with `arg-span-set`
- [x] Nil-filename guard in `usages-by-filename` (schema fix; full suite green)
- [x] Fixture rules in `analyze-test-rules`: L9/L10/L11 (span-set shapes) + L12 (shadowed same-named local) + J3 (`rule-local-multi-hop-ctor`)
- [x] Tests in `analyze-test`: `test-constructor-locals-expansion` (per-arg attribution, `:full`, no dupes, shadowing + unreached negatives) and `test-dynamic-insert-types-detected` (multi-hop generic chain)
- [x] Docstring fixes: cross-ns references now resolve — `index/…` (in scope) in `analyze.callsite`, fully-qualified `clara.server.tools.graph.analyze.callsite/arg-span-set` in `analyze.index` (no `callsite` alias there)
- [x] Verification: `make test lint reflection-check format-check` in `server/` — 400 tests, 0 failures

## Notes

- Step 5 (record/Java boundary-chain upgrade for the constructor-of-interest pass) is the remaining follow-up.
- `usages-by-filename` uses `->>`; index keys `by-file` → `by-filename`; new `u/KondoLocalUsage` schema (`:name`/`:id` optional — real kondo entries can omit them).
