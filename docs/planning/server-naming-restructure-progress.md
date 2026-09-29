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
- [x] Artifacts / analysis / vendor buckets; test `rules.*` → `test.rules.*`; dev runners → `server.*`, scripts → `dev.*`, `user` stays
- [x] Deleted empty `tools/graph/shared/` + `test_crazy_ns_$_name/`; fixed `empty-kondo-config` fixture layout
- [x] `(ns …)` + requires rewritten longest-prefix-first (319+706+317+113+2 refs)
- [x] `::conditions/normalized` → `:clara-rules-explorer/normalized` (§5.7); dropped now-unused `conditions` aliases
- [x] `deps.edn`, `Makefile`, `check-reflection.sh`, kondo config, `bin/*.bb` (§6.1–§6.3)
- [x] Follow-ups: rules path-strings `clara/explorer/rules/` → `clara/explorer/test/rules/`; `client_test` `repo-root` → `explorer/`; editor symlinks retargeted
- [~] Docstrings/comments (§6) — code comments done; `explorer/docs/*` + root docs pending §6.6
- [ ] Move package paths `explorer/{src,test,dev,resources}/clara/server/…` → `explorer/{src,test,dev,resources}/clara/explorer/…` per §4.1–§4.4 bucket map
  - [ ] HTTP bucket: `graph/*` → `server/*`, `server.clj` → `serve.clj` (§5.1)
  - [ ] Artifacts bucket: `tools/graph/artifacts/**` → `artifacts/**` (§4.1)
  - [ ] Analysis bucket: `tools/graph/*` → top-level `explorer/*`, no `graph` segment (§5.5)
  - [ ] Vendor: `vendor/tools/inspect.clj` (§4.1)
  - [ ] Test mirror + `rules.*` → `test.rules.*` (§4.2)
  - [ ] Dev: demo/setup/hierarchy → `server.*`, kondo-sync + regen/compose scripts → `dev.*`, `user` stays (§4.3)
  - [ ] Resources + test-resources paths (§4.4)
  - [ ] Delete empty `tools/graph/shared/` + `test_crazy_ns_$_name/` (§2, §6.7)
- [ ] Rewrite `(ns …)` + require/refer/alias symbols (longest-prefix-first)
- [ ] `::conditions/normalized` → `:clara-rules-explorer/normalized` (§5.7, §7.3)
- [ ] Docstrings/comments (§6)
- [ ] `deps.edn`, `Makefile`, `bin/ci/check-reflection.sh`, `.clj-kondo/config.edn`, `bin/*.bb`, symlinks, CI working-dirs (§6.1–§6.3)

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
- [x] Root `README.md`/`AGENTS.md`, `docs/explorer-*.md`, `explorer/docs/*`, `explorer/README`+`AGENTS.md`, `editor/AGENTS.md`, `ui/docs/ui-preamble.md` migrated
- [x] `.github/workflows/server.yml` → `explorer.yml` ("Explorer CI"); `ui.yml` working-dirs → `explorer/`
- [x] `explorer/.gitignore` gains `demo-data/` (matches long-standing "gitignored" claim)
- [ ] Plan doc itself intentionally untouched: its old→new tables ARE the rename record

## 7. Migration guide (§8)
- [x] `docs/planning/explorer-renaming-migration.md`: summary, require table, entry points, regen-vs-rebase + `rebase-layer` example + skew caveat, marker change, sidecars, editors, checklist
- [x] No in-repo `agent-annotations.edn` (checked) — curated-rebase applies to consumers only

## Verification gate (§9)
- [x] `grep -r clara\.server` clean except plan/progress/migration history + gitignored build outputs
- [x] `make test` (explorer) 409/2562 green; lint, reflection-check, format-check, bb-smoke-test green
- [x] Emacs Tier1+Tier2 105/105 + check-elisp; Neovim plenary 6/6 (`stylua`/`selene` missing locally)
- [x] UI unit 34/34; e2e runnable in CI only (no local browsers)

## Log
- 2026-09-29: tracker created; baseline done; §9.1 `git mv` started.
- 2026-09-29: §9.1 code moves + ns rewrite + marker change done; API compiles; fixtures + artifacts regenerated. Suite 409 tests 34F+9E → fixed path-strings, repo-root, symlinks; re-running.
- 2026-09-29: suite green (409/2562, 0F/0E). All sections done except e2e-local. Stray demo backend on :9001 needs killing outside sandbox (`lsof -ti:9001 | xargs kill`).
- 2026-09-29: suite green (409/2562, 0F/0E). Fixed along the way: serve_test filename, rules path-strings + prefix, stale kondo .cache, synth ordering expectation, client-test *ns* binding. Running lint/reflection/format/bb gates.
