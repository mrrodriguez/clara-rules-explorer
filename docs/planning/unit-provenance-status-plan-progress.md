# Unit provenance: status — progress

Plan: `docs/planning/unit-provenance-status-plan.md` (proposal).
Scope: `explorer/` manifest provenance + offline bb report. No new manifest keys; one new `annotations_report.bb` subcommand.

## Phases

- [x] **P0 — scaffolding**: this progress file; confirm bb-safe constraints (`clojure.java.shell` only for `shared.git`/`shared.status`, + `layout` + `clojure.edn`)
- [x] **P1 — `shared.git`** (`explorer/src/clara/explorer/artifacts/shared/git.clj`, new): git reads with remote-branch resolution for detached checkouts (plan §2.1)
- [x] **P2 — `manifest.clj` delegates**: `get-git-info` keeps schema-checked signature, delegates to `shared.git` (plan §2.3)
- [x] **P3 — `shared.status`** (`explorer/src/clara/explorer/artifacts/shared/status.clj`, new): `unit-status` returning the §3.3 map (source / composed / aggregate-by-kind cases)
- [x] **P4 — `layout.cljc` mapping**: lift unit → directory mapping if not already shared (plan §3.4); `store/get-out-dir` inversion + `--root` default stripping
- [x] **P5 — bb `status` subcommand**: flag table (`--file`, `--checkout`, `--ref`, `--root`, `--edn`); `status` row in `subcommands`/`help`; per-subcommand flag rejection (plan §3.1, §3.5)
- [ ] **P6 — docs**: `explorer/docs/persisted-artifacts.md` provenance section (`:source :branch` semantics + `status` usage)
- [x] **P7 — tests**: detached resolution (temp git repo, no network); `unit-status` over fixtures + temp edited copies; bb report `status` text/`--edn`/`help` cases
- [x] **P8 — acceptance + quality gates**: worktree-detach records `:branch "main"`; `status` verdict matches manifest policy; `make test lint reflection-check format-check`

## Detailed checklist

### P1 `shared.git`
- [ ] `git` helper (dir + args → trimmed stdout or nil on non-zero exit), bb-safe (`clojure.java.shell` only)
- [ ] `get-git-info` equivalent returning `{:remote :sha :sha-short :branch :working-tree}` with detached resolution:
  - [ ] attached → checkout branch as today
  - [ ] detached + `refs/remotes/origin/HEAD` points at HEAD → that branch sans `origin/`
  - [ ] else first sorted `for-each-ref --points-at HEAD` under `refs/remotes/origin/` sans `origin/`
  - [ ] else nil (never `"HEAD"`; local branches ignored)
- [ ] nil for missing dir / non-repo (sha read fails)
- [ ] `*warn-on-reflection* true`; no schema dep (bb-safe)

### P2 manifest delegation
- [ ] `manifest/get-git-info` delegates, same `schema/GitInfo` signature and docstring guarantees
- [ ] existing manifest tests still pass

### P3 `shared.status`
- [ ] manifest read is caller's job (bb reads file, JVM passes map); `unit-status` takes `{manifest, checkout-sha?, ...}` — decide exact opts shape
- [ ] source-unit checks: `remote-mismatch` (normalized remote compare, skips sha), `sha-drift`, `generated-dirty`, `checkout-dirty` (informational), `age-exceeded` (`:updated` vs `:max-age-days`)
- [ ] no-`--checkout` path: sha-dependent checks skipped, report notes sha not compared
- [ ] composed-unit: per-source compare against source manifests under `--root`; `current` / `sha-drift` / `missing`; unit current iff all sources current; `--checkout` rejected
- [ ] aggregate without `:staleness :sources`: kind + source provenance, no verdict
- [ ] result map exactly per §3.3 (`:dir :repo :label :kind :source :updated :staleness :compared :verdict :reasons :sources`)
- [ ] host seam: pure fn returning map; host appends `:reasons` before rendering
- [ ] remote normalization: scheme, `.git` suffix, trailing slash
- [ ] date handling: `:updated "YYYY-MM-DD"` vs policy `:max-age-days`

### P4 layout mapping
- [ ] audit: does unit → directory mapping live only in JVM (`store/get-out-dir`) today? (yes — uses `schema`, `io/file`)
- [ ] lift pure path-join + inverse (strip `:repo` / `branches/<label>`) into `layout.cljc` with no deps
- [ ] `store` + `status` + discovery share it

### P5 bb subcommand
- [ ] flag-table parsing replacing positional `[target cmd arg]` + `--file`; strip known flags + values first
- [ ] `status [--checkout PATH [--ref REF]] [--root PATH] [--edn]`; reads `rules-inspect-manifest.edn` only
- [ ] text report: aligned lines from result map (unit/kind/source/verdict per §3.3 example)
- [ ] `--edn` prints result map; `help`/`subcommands` gain `status` row + flags

### P6 docs
- [ ] `:source :branch` = remote branch, nil when none points at commit; manifest top-level `:branch` unaffected (artifact-dir label)
- [ ] how to check a unit with `status`

### P7 tests
- [ ] detached resolution: commit + `update-ref refs/remotes/origin/main` + `symbolic-ref refs/remotes/origin/HEAD`; `checkout --detach` → `"main"`; worktree `--detach` → same; detached == attached `:source` maps; local-only branch → nil; nothing points → nil
- [ ] `unit-status`: source current at sha / `sha-drift` +1 commit / `remote-mismatch`; composed current / one source edited → `sha-drift` / one source removed → `missing`; `--root` default stripping; `--checkout` on composed rejected
- [ ] bb: `status` text + `--edn` + `help` row

## Decisions log
- `shared.git` exposes `ref-sha` / `remote-url` / `clean-tree?` as the comparison seam `status` uses (bb + JVM resolve refs one way). Missing dir / non-repo → nil via git exit code, no `io` needed (keeps bb-safe).
- `for-each-ref` candidates strip to nil for `origin/HEAD` (symref listing as `HEAD` is not a branch).
- `sha-reasons` order: `checkout-not-a-repo` (nothing readable) → `remote-mismatch` (decisive) → `ref-unresolvable` → `sha-drift`. `checkout-dirty` is informational (`:informational true`, verdict-neutral).
- No-checkout source verdict is `:unknown` with explicit `{:check :sha-not-compared}` reason, so the report can say the sha was not compared.
- Composed sources iterate `:staleness :sources` (authoritative set); locator prefers `:analysis-run :units` entry, falls back to parsing `repo[@branch]` on the last `@`.
- P4 minimal: `layout` owns pure `unit-dir` join + `default-root` strip + `branches-subdir`; `store/get-out-dir` validates then delegates (same strings on unix); discovery's dir→ref inverse stays in `registry.clj` (different direction), reading the shared constant via `store`.
- Sandbox blocks writes under project-scoped `.git/` paths but allows them under the system temp dir, so `git_test` temp repos live there (default `Files/createTempDirectory`) and run green here too. `git -c` flags must precede the subcommand (`git -c k=v commit`, not `git commit -c k=v`) — caught by the suite itself.
- Keep test-introduced binding macros out: `with-temp-root`/`with-repo` as macros trip clj-kondo (`Unresolved symbol` at the binding site); plain fns taking `(fn [x] …)` thunks are lint-clean with no config. `with-checkout` (no new bindings, body analyzed in enclosing scope) is fine as a macro.
- Paren discipline for test `testing` blocks: an extra `)` closes the outer `let` early and surfaces far away as `Unable to resolve symbol`; when lost, trace form depth per line (script in session) rather than eyeballing.

## Test report

- `layout-test` + `shared.status-test`: 6 tests / 62 assertions pass (JVM).
- `bb-report-test` (incl. new `bb-report-status-test` + `help` row): 99 assertions pass (JVM drives bb; hermetic manifests + one live-remote-mismatch case).
- `store-test` + `schema-test` + `registry-test` + `compose-persist-test`: 119 assertions pass — `store/get-out-dir` delegation behavior-identical.
- `shared.git-test`: 2 tests / 56 assertions pass (JVM, real temp repos — incl. `worktree add --detach` → `"main"`, sorted-first tie-break, local-only→nil, nothing→nil, never-`HEAD`, missing/non-repo→nil, own-checkout≠`HEAD`). Temp dirs come from the system temp dir, where `.git` writes work (only project-scoped `.git` writes are sandbox-denied). A `repo-fixture-works?` probe skips loudly where repos can't be created at all.
- Gates, final: `make test` 427 tests / 2785 assertions / 0 failures; `lint` 0 errors 0 warnings; `reflection-check` pass; `format-check` pass; `bb-smoke-test` pass (incl. new `shared.git`/`shared.status`).
- Acceptance (§7): worktree-detach case asserts `:branch "main"` (in-suite); live CLI demo — source unit `current`+policy line against own checkout, composed `current`→`sha-drift` after editing one source manifest, `--root` defaulted in both.

## Follow-up: whitespace-robust tests + owned column width (2026-10-01)

- `bb-report-status-test` no longer pins column padding: new `collapse-whitespace` helper folds every whitespace run to one space, assertions pin words (`"verdict stale | remote-mismatch"`, `"kind source unit"`). `--edn` assertions were already structural (parsed map) and are unchanged.
- `annotations_report.bb` column spacing now has a single owner: `field-width` + `print-field` (`format "%-10s %s"`, per the `format`-over-`str` standard) for `status` rows — output byte-identical — and data-driven `status-usage-rows` + `print-flag-row` (computed hanging indent) for `help`, replacing hand-counted continuation spaces. Fixed a self-inflicted `""`-inside-docstring reader break while there.
- Gates re-run: `bb-report-test` ns 3 tests / 125 assertions pass; full `make test` 427 / 2785 pass; `lint` 0/0; `format-check` pass; live `status`/`--edn`/`help` render verified.

## Session log
- 2026-10-01: progress file created; starting P1. Audited `manifest.clj` (`get-git-info`), `schema/GitInfo` (already nil-able `:branch`), `store/get-out-dir` (JVM-only, schema+io), `annotations_report.bb` arg parsing (positional + `--file`), `bootstrap.bb` (bb classpath = `src` + schema), shared-ns bb-safe pattern (`:clara-rules-explorer/bb-loaded` + stdlib-only).
- 2026-10-01: P5 done — verified live: source `unknown` (no checkout) / `current`+informational checkout-dirty against own repo; `--edn`; composed current→drift via `--root` defaulting; rejections (`--file` on status, `--checkout` on summary, `--ref` w/o `--checkout`, unknown flag/subcommand); `help` row. Plan example output shape reproduced.
- 2026-10-01: P1 done — `shared.git` verified under bb against live repo (attached → branch, missing → nil) + 5 stub scenarios (attached/default/other-ref-sorted-first/nothing/HEAD-symref-only). P2 done (manifest delegates). P3 done — 14 stub scenarios green (current/drift/remote-mismatch/generated-dirty/no-checkout/age/checkout-dirty/ref-unresolvable/not-a-repo/composed ×3/aggregate-no-sources/composed+checkout-throws/default-root). P4 done — `layout/unit-dir` + `default-root` + `branches-subdir`; `store` delegates. Fixed two self-inflicted paren bugs (`layout` ns form, `composed-unit-status` — JVM reader counts forms to isolate).
