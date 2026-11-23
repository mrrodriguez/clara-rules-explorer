# Progress: fact type provenance in Neovim

Companion to [`fact-type-provenance-nvim-plan.md`](./fact-type-provenance-nvim-plan.md)
(the plan) and [`fact-type-provenance-progress.md`](./fact-type-provenance-progress.md)
(the backend + Emacs semantics, which stay the source of truth). This file is
the checkpoint log for the Neovim port: decisions taken, what landed, the
deviations from the plan, and the verification runs. Update in place as work
lands; do not turn it into a diary.

Branch: `fact-type-provenance`. Scope: `editor/neovim/` plus docs. No Clojure
change was needed — the contract was already complete, so every edge case
resolved in Lua.

**Status: steps 1–9 done; `make check` green (221 tests), real bb smoke green.**

## 1. Step-1 decisions (plan §6)

| # | Question | Decision |
| --- | --- | --- |
| 1 | REPL project directory for the default source root | Eval `(System/getProperty "user.dir")` through the origin buffer once and cache it; fall back to `vim.fs.root(origin_bufnr, {"deps.edn","project.clj","bb.edn",".git"})` when the REPL cannot answer. The default root is that directory's parent, matching Emacs's `nrepl-project-dir`. The cache is dropped by `:ClaraExplorerRefresh`. |
| 2 | View container | Regular split window (already decided in the plan). |
| 3 | Refresh key | `R`, plus a `<Plug>` target per key. `g` is left unbound: it is a prefix in several common setups, and `<Plug>` is the escape hatch. |
| 4 | Navigate picker jumps for var-backed targets | Keep `def-str` on the navigate path (it runs from the source buffer, where Conjure resolves its client). The provenance view routes **every** `<CR>` through the new locate/show path. |

## 2. What landed, per plan step

| # | Step | Where | Result |
| --- | --- | --- | --- |
| 1 | Decisions | this doc §1 | done |
| 2 | Production macros | `structural.lua` (owns the table + `production_kind`), `jump.lua` (head regex built per call) | C1–C5 green |
| 3 | Wrapper-head fallback | `token.lua` `wrapper_inner_type_bounds` | W1–W2 green |
| 4 | Origin-buffer eval routing | `conjure.eval_edn` takes `origin_bufnr`, runs `eval-str` inside `nvim_buf_call` | R1 green |
| 5 | Locate/show split, var line first, source roots | `jump.lua` (`locate`, `show`, `ns_name_from_text`, `source_root_candidates`, `project_dir`) | J1–J8 green |
| 6 | Provenance transport | `conjure.provenance_input` / `provenance_code` (nested EDN), `bb_eval` takes `{flag = "--provenance"}` | T1–T4 green |
| 7 | Provenance view + both commands | `lua/clara-explorer/provenance.lua`, `plugin/clara-explorer.vim`, `init.show_provenance` | P1–P8 green |
| 8 | Docs | `explorer-editor-navigation-neovim.md`, `explorer-editor-navigation.md`, `editor/AGENTS.md` | done |
| 9 | Verification | §4 below | green |

Test matrix coverage (plan §5), by file:

- **C1–C5** — `structural_spec.lua` (C1–C5 for cursor context) and `jump_spec.lua`
  (C5 and C2 for the definition-site regex: an empty list matches no head).
- **W1–W2** — `token_spec.lua`.
- **R1** — `transport_spec.lua` (`conjure.eval_edn origin routing`).
- **J1–J8** — `jump_spec.lua`.
- **T1–T2 / T3–T4** — `transport_spec.lua` + `bb_transport_spec.lua` (flag order).
- **P1–P8** — `provenance_spec.lua`.

## 3. Deviations and notes

- **Ancestors from node kinds, not path parity.** Emacs computes `<Tab>`'s
  `:ancestors` from the odd path indices, which only holds for a fact-type
  root. `provenance.production_ancestors` walks the path prefixes and keeps the
  components whose node is a `:production`, so it is correct for any root
  shape (the backend's `:production` roots included).
- **Hand-rolled source-root walk.** `vim.fs.dir(root, {depth = math.huge, skip = …})`
  does not recurse as documented on 0.12 in this sandbox, so the walk is a small
  recursion over non-recursive `vim.fs.dir`. It never follows symlinked
  directories (matching Emacs's `directory-files-recursively`) and skips
  dot-directories plus `g:clara_explorer_source_roots_skip_directories`.
- **`join_entries` takes varargs.** The first draft joined a table, and a
  table literal whose first entry is an omitted key (`:production` nil) made
  `ipairs` stop at the gap — silently dropping `:side`, `:caller-ns`, and
  `:token`. Caught by the T1/T4 specs; the encoder now uses `select("#", ...)`.
- **`provenance.view_state(bufnr)`** is exposed so the P matrix can assert on
  the path-keyed tables without scraping indentation.
- **Window choice** is `winnr("#")` (the previous window), the Neovim analogue of
  Emacs's MRU window, with the window already showing the file taking priority.
- **No live-nREPL reproduction of the R1 failure.** The spec pins that the eval
  runs with the origin buffer current; the original Conjure "no client from a
  `nofile` buffer" symptom was not reproduced against a live nREPL in this
  sandbox (no REPL available). The bb transport has no such distinction, so the
  routing matters only for nREPL sessions.
- **`:CR` smoke without a REPL.** The visit path was exercised end to end with
  `g:clara_explorer_source_roots` set and no Conjure connection: locate fell
  through the failing REPL step to the source roots, found
  `explorer/test/.../loan_app_rules.clj`, kept the provenance split (2 windows),
  and landed on `(r/defrule app-outcome-denied?`.

## 4. Verification

Baseline before any edit: 170 tests across 6 spec files, `format-check` clean,
`lint` 0/0.

After the work (all run from `editor/neovim`):

```
mise exec -- make check    # format-check + lint + test
```

- `format-check`: clean (stylua 2.5.2).
- `lint`: 0 errors / 0 warnings / 0 parse errors (selene 0.31.0).
- `test`: **221 tests, 0 failures, 0 errors** — `edn_spec` 21, `structural_spec`
  11, `token_spec` 52, `transport_spec` 44, `bb_transport_spec` 42,
  `provenance_spec` 20, `jump_spec` 31.

`stylua`/`selene` are installed through `mise` but are not on the default
`PATH` here, so the Makefile is invoked as `mise exec -- make …`. No install
or network access was needed.

Real-backend smoke (babashka needs a writable tools.deps cache in this sandbox —
`editor/AGENTS.md` documents the `CLJ_CONFIG` workaround):

1. The exact EDN strings `conjure.provenance_input` produces are accepted by
   `bb explorer/bin/editor-client.bb --provenance` for both a `:production` root
   and a `:cursor` root with a nested `:input`.
2. A headless Neovim run with `g:clara_explorer_transport = "bb"` and the
   composed `loan-app-ruleset` + `loan-disposition-ruleset` selection rendered
   the consumer tree at `:depth 0`, then a real `<Tab>` (`--provenance` fetch)
   expanded `notice-approved-app` into `ApprovalNotice` and
   `find-approval-notices` (7 → 9 rows), with `via :loan-app/application-outcome`,
   `@unit`, `(query)`, `(no outputs)`, `↻ cycle`, and `↑ shown above` all
   rendering as documented.
3. `<CR>` through locate + source roots (no REPL) opened the production's source
   file and searched to the `defrule` form.

Not covered by an automated test: a live nREPL session (tier 3), and therefore
the `def-str` navigate path and `nvim_buf_call` routing against a real Conjure
client.

## 5. Commands and settings (as shipped)

See `docs/explorer-editor-navigation-neovim.md` for the user-facing surface:
`:ClaraExplorerShowProducerProvenance` / `:ClaraExplorerShowConsumerProvenance`,
`<Tab>`/`<CR>`/`R`/`q` (each with a `<Plug>(ClaraExplorerProvenance…)`
target), `g:clara_explorer_provenance_depth`,
`g:clara_explorer_production_macros`, `g:clara_explorer_source_roots`, and
`g:clara_explorer_source_roots_skip_directories`.
