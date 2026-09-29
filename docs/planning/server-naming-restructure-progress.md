# Server naming restructure — progress

Scope: `docs/planning/server-naming-restructure-plan.md` (§1–§10).
Tracker lives alongside the plan; consumer migration guide will land as
`docs/planning/explorer-renaming-migration.md` (§8).

## Status legend
- [ ] todo · [~] in-progress · [x] done

## 0. Baseline
- [x] Plan read (§1–§10, LEN 37908)
- [x] Inventory: `server/src` 53 files, `server/test` ~49 files, `server/dev` 9 files, `server/resources` + `server/test-resources` surveyed
- [x] `git status` clean, no `explorer/` dir yet
- [x] `server/bin`: annotations_report.bb, bb_shared_smoke_test.bb, bootstrap.bb, ci, clj-local, editor_client.bb (+ layout.cljc per plan §6.2 — verify)

## 1. Mechanical rename (§9.1) — DONE 2026-09-29
- [x] `git mv server explorer` + package paths per §4.1–§4.4 (src 53, test 49, dev 9, resources)
- [x] HTTP bucket `graph/*` → `server/*`, `server.clj` → `serve.clj`; test `server_test.clj` → `serve_test.clj` (§5.1)
- [x] Artifacts / analysis / vendor buckets; test `rules.*` → `test.rules.*`; scripts → `dev.*`
- [x] Follow-up (owner): `user` back to `dev/user.clj` (classpath root — `user` never nests); demo/setup/hierarchy runners → `clara.explorer.dev.server.*` at `dev/clara/explorer/dev/server/` (dev-side only); `deps.edn` main-opts + `integration_test` updated; lint + 409 tests green
- [x] Deleted empty `tools/graph/shared/` + `test_crazy_ns_$_name/`; fixed `empty-kondo-config` fixture layout
- [x] `(ns …)` + requires rewritten longest-prefix-first (319+706+317+113+2 refs)
- [x] `::conditions/normalized` → `:clara.explorer.internal/normalized` (§5.7); dropped now-unused `conditions` aliases
- [x] `deps.edn`, `Makefile`, `check-reflection.sh`, kondo config, `bin/*.bb` (§6.1–§6.3)
- [x] Follow-ups: rules path-strings `clara/explorer/rules/` → `clara/explorer/test/rules/`; `client_test` `repo-root` → `explorer/`; editor symlinks retargeted
- [x] Docstrings/comments (§6) — code comments + `explorer/docs/*` + root docs + `ui/README.md` migrated (§6.6; a few stragglers corrected in §8)

## 2. Regenerate derived data (§9.2, §7.5)
- [x] `make regen-fixture` + `make regen-artifacts`
- [ ] Curated `agent-annotations.edn` migrated via `rebase-layer` (never regenerated)

## 3. Verify server (§9.3)
- [x] `make test` in `explorer/`: 409 tests, 2562 assertions, 0F/0E
- [x] `make lint reflection-check format-check bb-smoke-test` in `explorer/` (all green; `main_test.clj` reformatted)

## 4. Editors (§9.4)
- [x] Emacs + Neovim ns strings (§6.4) + 4 symlink retargets
- [x] Emacs: Tier 2 (eldev) 105/105, Tier 1 105/105, check-elisp pass (fixed 80-col docstring)
- [x] Neovim: plenary 6/6 pass; `stylua`/`selene` NOT installed — format-check/lint not runnable

## 5. UI (§9.5)
- [x] `ui/src` strings, `ui/Makefile` `SERVER_DIR`, e2e backend scripts, 8 e2e specs (12 refs)
- [x] `make demo-setup` + `make demo-scrape`: demo JSON regenerated (1784 new-ns refs, 0 old)
- [x] `make format check lint` clean; `test-unit` 34/34 (repo-local TMPDIR in sandbox)
- [ ] `test-e2e`: no local Playwright browsers — specs updated, demo data fresh, CI runs them

## 6. Docs + CI (§9.6)
- [x] Root `README.md`/`AGENTS.md`, `docs/explorer-*.md`, `explorer/docs/*`, `explorer/README`+`AGENTS.md`, `editor/AGENTS.md`, `ui/docs/ui-preamble.md` migrated (a few misses corrected in §8)
- [x] `.github/workflows/server.yml` → `explorer.yml` ("Explorer CI"); `ui.yml` working-dirs → `explorer/`
- [x] `explorer/.gitignore` gains `demo-data/` (matches long-standing "gitignored" claim)
- [ ] Plan doc itself intentionally untouched: its old→new tables ARE the rename record

## 7. Migration guide (§8)
- [x] `docs/planning/explorer-renaming-migration.md`: summary, require table, entry points, regen-vs-rebase + `rebase-layer` example + skew caveat, marker change, sidecars, editors, checklist
- [x] No in-repo `agent-annotations.edn` (checked) — curated-rebase applies to consumers only

## 8. Follow-up — layer-id keywords + missed doc refs
- [x] Renamed layer `:id` keywords `:clara.tools.graph.analyze/{generated,memory,unknown-fact-type}` → `:clara.explorer.analyze/*` (reverses plan §7.2 "keep stable"); fixtures + artifacts regenerated
- [x] Migration guide §5 expanded (layer-id rename; `:clara-rules-explorer/{normalized,bb-loaded}`; `:clara-rules/*` annotation keys)
- [x] Fixed missed `server/`/`clara.server`/`tools.graph` refs: `explorer/docs/analyze-clj-kondo-notes.md`, `internal-analysis-models.md`, `persisted-artifacts.md`, `explorer/README.md` tree, `utils.clj` docstring, root `README.md`/`AGENTS.md`, `editor/AGENTS.md`, `ui/README.md`, `ui/docs/ui-preamble.md`, root `.gitignore`
- [x] Cleaned stale regenerables: root `test-resources/` `.cache`, root `.clj-kondo/.cache`, `explorer/.clj-kondo/.cache`, root `target/`, root `.lsp/`, `explorer/.lsp/`, `explorer/.cpcache`

## 9. Follow-up — internal marker + slim block simplification
- [x] Renamed `:clara-rules-explorer/normalized` → `:clara.explorer.internal/normalized` (explicitly internal, namespace-independent); fixtures + artifacts regenerated
- [x] `slim`'s `:slim` block reduced to `{:written-by <fn sym>, :dropped <set>, :unknown-fact-types <set>}` — removed the `:references` prose and the `:recover` map; `:written-by` is now the producing function's fq var symbol (resolvable at the manifest sha)
- [x] Updated `rehydrate`, `shared.compose`, `parts`, `digest` `:more`, `annotations_report.bb`, tests, `persisted-artifacts.md`, migration guide, plan

## 10. Follow-up — drop digest `:more` + simplify `:method`
- [x] Removed `:more` orientation prose from `rulebase-analysis-digest.edn` (`digest.clj` `more-note` + `session-hint`) and `registry-digest.edn` (`federate.clj`) — structure outline belongs in docs/agent files, not artifacts
- [x] `rules-inspect-manifest.edn` `:analysis-run :method` is now `"clara.explorer.analyze/->rule-source-analysis"` (dropped the `"live-session (…)"` prefix)
- [x] Fixtures + artifacts regenerated; tests, lint, reflection-check, format-check, bb-smoke-test green

## Verification gate (§9)
- [x] `grep -r clara\.server` clean except plan/progress/migration history + gitignored build outputs
- [x] `make test` (explorer) 409/2562 green; lint, reflection-check, format-check, bb-smoke-test green
- [x] Emacs Tier1+Tier2 105/105 + check-elisp; Neovim plenary 6/6 (`stylua`/`selene` missing locally)
- [x] UI unit 34/34; e2e runnable in CI only (no local browsers)

## Log
- 2026-09-29: tracker created; baseline done; §9.1 `git mv` started.
- 2026-09-29: §9.1 code moves + ns rewrite + marker change done; API compiles; fixtures + artifacts regenerated. Suite 409 tests 34F+9E → fixed path-strings, repo-root, symlinks; re-running.
- 2026-09-29: suite green (409/2562, 0F/0E). All sections done except e2e-local. Stray demo backend on :9001 needs killing outside sandbox (`lsof -ti:9001 | xargs kill`).
- 2026-09-29: owner follow-up — `user` → `dev/user.clj`, demo runners → `clara.explorer.dev.server.*` (deviates from plan §4.3, which kept them in `server.*`); referencers updated; lint + suite green again.
- 2026-09-29: suite green (409/2562, 0F/0E). Fixed along the way: serve_test filename, rules path-strings + prefix, stale kondo .cache, synth ordering expectation, client-test *ns* binding. Running lint/reflection/format/bb gates.
- 2026-09-29: follow-up — layer-id keywords renamed (reverses §7.2 stable-id decision); missed doc refs fixed; stale kondo/lsp/target/cpcache cleaned; migration guide §5 expanded.
- 2026-09-29: follow-up — `:clara-rules-explorer/normalized` → `:clara.explorer.internal/normalized`; `:slim` block reduced to `{:written-by <fn sym> :dropped <set> :unknown-fact-types <set>}` (recovery/references prose removed, `:written-by` is a fq var symbol). Suite still 409/2562 green.
- 2026-09-29: follow-up — digest `:more` removed (rulebase-analysis-digest + registry-digest) and `session-hint` option dropped; `:analysis-run :method` simplified to `"clara.explorer.analyze/->rule-source-analysis"`. Suite 409/2561 green (one `:more` assertion removed).
