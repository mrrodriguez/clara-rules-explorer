# Explorer Server ↔ Neovim Navigation — Roadmap

Status: **Phase 0 + Phase 1 implemented (local)** · Phase 2 (CI) is next, after
reviewing the local build/test · Plan:
`docs/planning/explorer-server-neovim-plan.md`

This is the executable checklist for the plan. Work top to bottom; every box
names its verification gate. The server contract (`clara.server.graph.client`)
is **done and unchanged** — every box here is Lua + tests.

Standing gates (every phase):

| Surface | Gate |
| ------- | ---- |
| Lua change | `make -C editor/neovim check` (format-check + lint + test) |
| Lua change | 85 tests: 16 edn + 49 token + 6 structural + 14 transport, via stylua 2.5.2 + selene 0.31.0 |
| Portability | `grep -R "~/Projects\|/Users/" editor/` empty; `grep -R "eval\.eval_str\|on_result" editor/neovim/` empty |
| API contract | none — no `client/navigate` or HTTP changes allowed in this work |

---

## Phase 0 — Spike: prove the semantics (done)

- [x] Plugin skeleton: `init.lua` + command registration + `minimal_init.lua`.
- [x] `edn.lua` (closed `NavigateResponse` grammar) + Tier-1 corpus.
- [x] `structural.lua` (tree-sitter enclosing form) + `token.lua` (§4 heuristics).
- [x] `conjure.lua` transport (§5) with `on-result` + `cb` error surfacing.
- [x] `picker.lua` + `jump.lua` (§6) — `def-str` for var targets, resource +
      regex fallback otherwise.
- [x] `:ClaraExplorerRefresh` / `:ClaraExplorerSwapSession` (§7).
- [ ] **Gate (manual, pending a live Conjure session):** producer/consumer/
      `not`/accumulator/retract/global/`::kw` cases over `loan-app-rules` +
      `analyze-test-rules` (see plan §10 Phase 0 gate).

## Phase 1 — Solidify (done, local)

- [x] Tier 1 + Tier 2 test suites (`edn_spec`, `token_spec`, `structural_spec`,
      `transport_spec`) sharing the Emacs fixture corpus.
- [x] `Makefile` (`format`/`format-check`/`lint`/`test`/`check`/`clean`) +
      `.stylua.toml`/`selene.toml` + vendored `neovim.yml` selene std.
- [x] `editor/neovim/.mise.toml` (stylua + selene, local dev only) + README +
      docs with Neovim version and dependency requirements.
- [x] **Gate:** `make -C editor/neovim check` (85 pass, 0 lint errors/warnings);
      `grep -R "~/Projects\|/Users/" editor/` empty;
      `grep -R "eval\.eval_str\|on_result" editor/neovim/` empty.

## Phase 2 — CI (next, after reviewing the local build/test)

- [ ] Review the local Phase 0 + Phase 1 output end-to-end (code, tests, docs,
      `make check`, manual Conjure acceptance if available).
- [ ] CI workflow `editor-neovim.yml` — install pinned Neovim (0.10+),
      `stylua`, `selene`, and plenary **directly** (no mise — mise is
      local-dev only); run `make check`; gate on Tiers 1–2.
- [ ] (Optional) install the clojure tree-sitter parser in CI so
      `structural_spec.lua` runs instead of skipping.

## Phase 3 — Optional extensions (only on evidence)

- [ ] Live nREPL integration (Tier 3, `make test-integration`).
- [ ] `searchpairpos`-based structural fallback behind `structural.lua`.
- [ ] LSP adapter — revisit only on evidence (Emacs plan §13).

---

## Implementation notes (decisions taken during the port)

- **Side detection lives in `token.lua`**, not `structural.lua`. The plan's
  §4.3 side rule (`props vectors → :rhs`) depends on `props_type_at_point`,
  which is token-domain logic (a byte-based sexp-walker port of the elisp
  `--props-type-at-point`). `structural.lua` stays the pure tree-sitter
  skeleton: `enclosing_production` (+ parser availability guard). This keeps
  `token_spec.lua` parser-free (Tier 1) and `structural_spec.lua` the only
  parser-gated suite.
- **`token.lua` is a self-contained byte-based sexp-walker** over the buffer
  text (0-indexed byte offsets), independent of tree-sitter and of `vim` — the
  faithful port of the Emacs `forward-sexp`/`down-list` heuristics.
- **Tree-sitter node shapes verified** against the installed grammar: head and
  name are `sym_lit` (unqualified via the trailing `sym_name` child), metadata
  on the name is a `meta_lit` child of that same `sym_lit`, so the production
  name is the *second* `sym_lit` child after the head.
- **Neovim version floor is 0.10** (`vim.treesitter.has_parser` API); the
  0.12 `vim.treesitter.language.get_lang` API is the fallback so both work.
  `vim.ui.select` needs 0.9+. Developed/tested on 0.12.4.
- **selene does not bundle the `neovim` std**, so we vendor a minimal
  `editor/neovim/neovim.yml` (`base: lua51` + `vim` global + busted test
  globals), matching the astrovim-dotfiles shape. `make lint` fails without it.
- **Dev tools via mise (local only)** — `editor/neovim/.mise.toml` pins
  `stylua` and `aqua:Kampfkarren/selene`; `cd editor/neovim && mise install`.
  Consumers who don't use mise bring their own `stylua`/`selene` on `PATH`.
  GitHub Actions must not use mise — CI installs its own pinned binaries
  directly.
