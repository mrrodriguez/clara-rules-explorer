# Registry unit diff — progress

Tracks `docs/planning/registry-unit-diff-plan.md`. Updated as parts land.

## Part A — canonicalize digest-suffixed names (§2.1–2.5)

- [x] `utils.clj`: `reader-gensym-name` → `minted-name` with digest-suffix shape; `canonicalize-gensyms` → `canonicalize-minted-names` (§2.4)
- [x] Call sites updated: `serialize.clj`, `analyze/callsite.clj` (3 spots)
- [x] `utils_test`: digest shape table + counter/idempotence/identity cases (§2.5)
- [x] `analyze_test`: digest-suffixed local yields stable `:source-str` / `:callsite-id` (§2.5)
- [x] Fixture: `def-digest-fact` in `test.rules.helpers`, used once in `loan-doc-rules` (§2.5)
- [x] Regenerated checked-in example; diff is exactly the fixture's new rule (§4.1)
- [x] Cleanup (§2.6): byte-level caveat dropped from `persisted-artifacts.md`, `normalize-gensyms` dropped from golden test, verified from fresh process + polluted-counter JVM

## Part B — `diff` over two units (§3)

- [ ] `artifacts.shared.diff`: `read-unit`, `diff`, `->text` (§3.2–3.5)
- [ ] `annotations_report.bb <before> diff <after>` subcommand (`--edn`, `--rule`) (§3.1, §3.5)
- [ ] `shared/diff_test`: one per tag + scope/edges/fact-types/shape-skew/empty-against-itself (§3.8)
- [ ] Checked-in example assertions: variant no-diff, composed `:scope`/`:unit` (§3.8)
- [ ] `bb_report_test`: subcommand end to end (§3.8)
- [ ] Docs: `persisted-artifacts.md` §"Querying it offline" (§3.9)

## Notes

- 2026-10-03: plan landed as proposal (commit `66eedf9`); work starts at Part A.
  `canonicalize-gensyms` has no callers outside `explorer/`, so the §2.4 rename
  needs no compatibility alias.
- 2026-10-03: Part A complete. `make test` 442 tests / 2876 assertions green;
  `format-check`, `lint`, `reflection-check` clean. Regen diff verified as
  exactly the fixture's new rule + derived counters + volatile manifest
  fields; byte-identical regeneration confirmed across three process states
  (`make regen-artifacts`, test JVM, polluted-counter JVM). Ripple: three
  absolute-count assertions bumped (`compose_test` 16→17, `federate_test`
  38→39 fact types, 6→7 orphans for the unconsumed `:digest-doc-meta` type).
