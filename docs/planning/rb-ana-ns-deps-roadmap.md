# Rulebase-Analysis `ns-deps` — Roadmap

Plan: `rb-ana-ns-deps-plan.md` (decisions locked §3) · Scope: `server/` (Clojure)

Progress tracker for the implementation. Checked = done + verified.

## Phase 0 — Setup & confirmation

- [x] Roadmap created (`rb-ana-ns-deps-roadmap.md`), plan read
- [x] §10.1 Confirm `RT/DEFAULT_IMPORTS` shape at REPL (keys/vals) + pick non-default `java.lang` class for regression test

## Phase 1 — Leaf ns + synth delegation (§10.2)

- [x] Create leaf ns `clara.server.tools.graph.ns-deps` with schemas (`NsRequireEntry`, `NsAliasEntry`, `NsDepEntry`)
- [x] Move five `->ns-…` data fns into leaf (not copied): `->ns-required`, `->ns-aliases`, `->ns-imports`, `->ns-refer-clojure`, `->ns-unmapped-default-imports`
- [x] Fix default-import predicate (exact key-set lookup, §6), keep `^Class` hints
- [x] Rename four `synth` fns per §4 (`->require-clauses`, `->import-clauses`, `->core-deviations`, `->unmapped-default-imports`) + `get-var-ns-name` / `get-var-name`, delegating to leaf
- [x] Parity tests (plan §9.1 data-syntax parity, §9.2 reconstructed-source stability) — new `ns-deps-test`, 5 tests / 28 assertions
- [x] `make test lint reflection-check` green (277 tests, 1783 assertions, 0 failures; lint 0 warnings; reflection clean; format-check clean)

## Phase 2 — Header parser + `->ns-deps` (§10.3)

- [x] Header parser in leaf ns (§5: `:require` prefix lists / `:as` / `:refer` / `:rename`, both `:import` shapes, `:refer-clojure` `:exclude`/`:rename`/`:only`)
- [x] `->ns-deps-entry` / `->ns-deps` with `{:ns-syms :base-source-fn}` hook, missing-everywhere → empty entry + `tap>` `:clara-rules/ns-deps-missing`
- [x] Tests §9.4 (header parsing via injected `:base-source-fn`), §9.5 (source-vs-runtime agreement on `loan-doc-rules`), §9.6 (missing-everywhere + tap)
- [x] `make test lint reflection-check` green (280 tests, 1801 assertions; lint/format/reflection clean)

## Phase 3 — Wire into `core` + external view (§10.4)

- [x] `core/->rulebase-analysis*` assocs `:ns-deps` (sorted-map) over production ns set (rules + queries via private `->production-ns-syms` over `get-production-ns-name-sym` — no `analyze` dep, no cycle)
- [x] `get-rulebase-analysis-external-view` retains `:ns-deps` (pass-through; JSON-serializable check via jsonista round-trip)
- [x] Test §9.7 (`->rulebase-analysis` integration, `core_test` `match?` still pass — full suite green)
- [x] `DEFAULT_IMPORTS` regression test (§9.3: fake ns importing verified non-default `java.lang` class — done in Phase 1)

## Phase 4 — Docs (§10.5)

- [x] `docs/explorer-graph-api.md` contract update (locked keys §3/§8)
- [x] UI types follow-up — **deferred by decision**: no UI consumer yet (endpoint unused by current pages), so no `ns-deps` type added to `ui/src/lib/types/api.ts`

## Phase 5 — Final gates (§10.6)

- [x] Final `make test format-check lint reflection-check` all green (281 tests, 1808 assertions, 0 failures; format/lint/reflection clean)

## Phase 6 — Review follow-ups

- [x] Amend §3 D1: `:refer :all` / bare `:use` expand to the required ns's live `ns-publics` (homogeneous `:refers` vector — no schema union); specs that refer nothing (bare `:require`, `:refer []`, `:only []`) produce no `:require` entry. `:use` ≡ `:refer :all`, `:use` + `:only` ≡ `:require` + `:refer`
- [x] §6: default-import exclusion compares the fully-qualified class (`RT/DEFAULT_IMPORTS` value), not just the simple-name key
- [x] Query-only fixture `rules/loan-doc-queries.clj` (separate ns from the rules); `core_test` coverage proving `:ns-deps` spans query and rule namespaces; included in the `integration_test` `run-loan-app-rules` session
- [x] `docs/explorer-graph-api.md` updated for refer-all expansion and full-class import comparison
- [x] `make test format-check lint reflection-check` green (285 tests, 1821 assertions, 0 failures)

## Log

- 2026-09-12: Roadmap created. Starting Phase 0 (§10.1 REPL confirmation).
- 2026-09-12: §10.1 done via live REPL (port 59994). `RT/DEFAULT_IMPORTS` keys are simple-name syms (96 entries), vals are `java.lang.Class`. Plan's example `ProcessBuilder` IS default — regression test must use a verified non-default instead: `java.lang.StackWalker` / `java.lang.Module` (both importable, absent from DEFAULT_IMPORTS, confirmed live).
- 2026-09-12: Test reorganization (user review): test files mirror source namespaces. New `test/.../graph/analyze/synth_test.clj` (`analyze.synth-test`, self-contained `fake.synth-*` fixtures — no cross-file ordering dependence) holds the clause and `reconstruct-ns-source` tests (renamed `test-reconstructed-source-stability` → `test-reconstruct-ns-source`, de-historicized descriptions); `ns-deps-test` keeps only leaf-ns subjects (zero `synth/` references); the `:ns-deps` wiring test moved to `core_test.clj` as `test-rulebase-analysis-ns-deps` next to its external-view neighbor.
- 2026-09-12: Follow-up fix (user review): `->core-deviations` removed outright (key-remap pass-through with a single caller) — `reconstruct-ns-source` destructures `->ns-refer-clojure` directly. Rename round-trip coverage lives in `test-reconstructed-source-stability` ("rename ns survives a round trip with the mapping intact"). `test-default-imports-regression` renamed to `test-ns-imports-excludes-only-default-imports` (tests named for behavior, not history).
- 2026-09-12: Earlier follow-up (user review): `->core-deviations` no longer resolves rename syms to Vars — pure symbol-to-symbol delegation. `reconstruct-ns-source` inverts renames to the `{orig-simple local-simple}` shape the `ns` spec accepts (`:rename {map my-map}`); the old `{local #'Var}` output never re-evaled (Var literal + wrong direction — REPL-proven) and is now covered by a rename round-trip test asserting the live mapping. History-flavored docstrings reworded to state current behavior (`->core-deviations`, `->ns-imports`, `->ns-refer-clojure`, `apply-rename`, `->rulebase-analysis` purity note).
- 2026-09-12: Review follow-ups (user review). (1) `:refer :all` / bare `:use` expand to the required ns's live `ns-publics`, so `:refers` stays a homogeneous sorted symbol vector (the earlier `:all` union was rejected in favour of expansion). `:use` ≡ `:refer :all`; `:use` + `:only` ≡ `:require` + `:refer` (equivalence test). Specs that refer nothing (`:refer []`, bare `:require`, `:only []`) emit no entry. (2) `->ns-imports` excludes a default import only when the class equals `RT/DEFAULT_IMPORTS`'s class for that simple name (full-class comparison, so a colliding simple name from another package is preserved). (3) New `rules/loan-doc-queries.clj` query-only ns, `core_test` coverage, and inclusion in the `integration_test` `run-loan-app-rules` session. UI support explicitly deferred (no consumer).
- 2026-09-12: Phase 2 done. Parser rules pinned by REPL probes: lone `:rename` refers nothing; `:refer`+`:rename` substitutes the local. `:refer :all` / bare `:use` yield empty-`:refers` entries (documented); `:refer-clojure :only` becomes the live-core complement so source/runtime agree. Test-file incident: two `(str ...)` header fixtures missed their closing paren (header-text `))` vs code `)`) — caught by cljfmt/reader, fixed.
- 2026-09-12: Phase 1 done. Decisions: `->ns-refer-clojure` returns sym-valued `:renames` (schema-clean, JSON-safe); `synth/->core-deviations` resolves back to Vars so `reconstruct-ns-source` stays byte-identical. Pre-existing limitation noted: reconstructed `:rename` with Var literals (`#'...`) does not re-eval — true before and after the move, out of scope. Divergence noted for §9.5: runtime imports include the ns's own record classes (auto-imported), source-parsed headers won't.
