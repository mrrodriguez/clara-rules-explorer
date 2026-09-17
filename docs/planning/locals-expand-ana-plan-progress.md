# Locals-Expand Analysis Progress

Tracks implementation of `locals-expand-ana-plan.md` (first slice:
steps 1–4, `:fact-constructors` path only).

- [x] Step 1: init-span reader in `analyze.kondo` (`init-form-span`)
- [x] Step 2: position indexes in `analyze.index` (`var-usages-by-file`, `local-usages-by-file`; range queries subsume exact lookup)
- [x] Step 3: fixpoint expansion in `analyze.callsite` (per-`TracedArg` region set)
- [x] Step 4: generalize ownership (`arg-reaches-ctor?` R1′/R2′)
- [x] Nil-filename guard in `usages-by-file` (schema fix; full suite green)
- [x] Fixture rules mirroring both shapes in `analyze-test-rules` (L9/L10/L11)
- [x] Tests in `analyze-test` (`test-constructor-locals-expansion`: per-arg attribution, `:full`, no dupes, negatives)
- [x] Verification: `make test lint reflection-check format-check` in `server/` — 400 tests, 0 failures

## Notes

- Step 5 (record/Java boundary-chain upgrade) is an explicit follow-up,
  out of scope for this slice.
- Follow-up refactor: `usages-by-filename` uses `->>`; index keys renamed
  `by-file` → `by-filename`; new `u/KondoLocalUsage` schema (`:name`/`:id`
  optional — real kondo entries can omit them).
