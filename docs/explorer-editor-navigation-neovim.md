# Editor Navigation — Neovim (Conjure)

The Neovim client for the shared editor-navigation feature. See
[Editor Navigation (shared)](./explorer-editor-navigation.md) for the contract,
REPL bootstrap, fact-type resolution, jump semantics, and refresh/swap
workflow — those are identical across editors. This page is the
Neovim-specific surface: install, requirements, and testing.

The client is `editor/neovim/` (a clean Lua plugin).

## Commands

| Command                             | Action                                                                |
| ----------------------------------- | --------------------------------------------------------------------- |
| `:ClaraExplorerNavigateProducer`    | jump from an **LHS** fact type to the production that inserts it       |
| `:ClaraExplorerNavigateConsumer`    | jump from an **RHS** fact type to the downstream productions (or the global consumers when outside a rule) |
| `:ClaraExplorerRefresh`             | re-derive annotations and re-warm the analysis                        |
| `:ClaraExplorerSwapSession`         | swap in a rebuilt session (`!` re-prompts)                            |

Direct jump when exactly one candidate; `vim.ui.select` picker when more than
one (delegates to Telescope/snacks/fzf-lua if you have a `ui-select`
integration configured).

## Requirements

- **Neovim 0.10+** (developed and tested on 0.12; `vim.ui.select` needs 0.9+,
  the tree-sitter API needs 0.10+).
- **`conjure`** — nREPL transport (`eval-str`, `def-str`, `current_ns`).
- **`nvim-treesitter`** with the **`clojure` parser** — the structural skeleton
  (`enclosing_production`); navigation degrades gracefully (returns `nil`) when
  the parser is absent, and the structural tests skip.

These are consumer-facing Neovim plugins, installed via lazy.nvim /
AstroNvim — not mise/brew.

## Development dependencies

`make format` and `make lint` need two dev-only Lua tools (end users do **not**
need them):

- **`stylua`** — Lua formatter.
- **`selene`** — Lua linter (`std = "neovim"`).

The project manages them with **mise** — `cd editor/neovim && mise install`
reads `editor/neovim/.mise.toml` (`stylua` + `aqua:Kampfkarren/selene`) and
installs pinned copies. If you don't use mise, bring your own `stylua` and
`selene` on `PATH` instead (e.g. `brew install stylua selene`); the Makefile
just invokes `stylua`/`selene`. The selene `neovim` std is vendored as
`editor/neovim/neovim.yml` (selene does not bundle it) — no download needed.

## Install (lazy.nvim / AstroNvim)

The plugin is loaded from a local checkout located via the
`CLARA_HOME_EXPLORER` environment variable (the repo root). The spec derives
the plugin directory as `$CLARA_HOME_EXPLORER/editor/neovim`. If the variable
is unset, the plugin is skipped and a warning is emitted — no hard failure.
The variable is read once at Neovim startup; change it and restart.

```sh
# per machine — the repo root of your clara-rules-explorer checkout
export CLARA_HOME_EXPLORER="$HOME/Projects/clara-rules-explorer"
```

```lua
-- ~/.config/nvim/lua/plugins/clara-explorer.lua
local plugins = {
  "Olical/conjure",
  { "nvim-treesitter/nvim-treesitter", opts = { ensure_installed = { "clojure" } } },
}

local clara_root = vim.env.CLARA_HOME_EXPLORER
if clara_root then
  plugins[#plugins + 1] = { dir = clara_root .. "/editor/neovim" }
else
  vim.notify("CLARA_HOME_EXPLORER is not set — clara-explorer not loaded", vim.log.levels.WARN)
end

return plugins
```

AstroNvim users add the same spec to their `lua/plugins/` directory; the
plugin loads automatically and registers the four user commands. The plugin
does not hard-code any machine-specific paths, home directories, or ports —
the checkout location comes from `CLARA_HOME_EXPLORER`.

## Architecture

```
lua/clara-explorer/
├── init.lua       (commands, dispatcher, context gather)
├── structural.lua (tree-sitter skeleton: enclosing defrule/defquery)
├── token.lua      (Clara token resolution — port of the elisp heuristics)
├── edn.lua        (EDN subset reader — the only module that knows the wire format)
├── conjure.lua    (eval-str wrapper, error surfacing, async plumbing)
├── picker.lua     (vim.ui.select)
└── jump.lua       (def-str / resource / regex fallback + jump-list push)
```

- **`edn.lua`** is dependency-free and parses the closed `NavigateResponse`
  grammar into plain Lua tables (keyword keys/values → strings, `nil` → absent
  key). It is written and tested in isolation.
- **`token.lua`** is a self-contained byte-based sexp-walker port of the Emacs
  `forward-sexp`/`down-list` heuristics — no parser, no `vim` dependency, so
  its tests run without tree-sitter.
- **`structural.lua`** is the only parser-dependent module; side detection
  lives in `token.lua` (it depends on the props-type heuristic).

## Testing

`make -C editor/neovim test` runs the plenary suite headless
(`nvim --headless --noplugin -u test/minimal_init.lua`). Same three-tier model
as Emacs:

| Tier | Scope                          | Files              | Deps                                        |
| ---- | ------------------------------ | ------------------ | ------------------------------------------- |
| 1    | pure-Lua unit                  | `edn_spec.lua`, `token_spec.lua`, `structural_spec.lua` | none (structural skips without the clojure parser) |
| 2    | real modules, mocked transport | `transport_spec.lua`, `jump_spec.lua` | stubbed `conjure.eval`, `vim.ui.select`, `jump.jump`, `vim.cmd.edit`, `vim.fn.search` |
| 3    | live nREPL integration         | (future)           | headless `server` + a live Conjure nREPL    |

- `token_spec.lua` shares the Emacs fixture corpus (the same rule snippets),
  so the two editors are tested against the same tokens.
- `transport_spec.lua` asserts the Clojure payload is built correctly, EDN is
  parsed, 0/1/N dispatch works, the jump path is invoked with the right
  target, and the `cb` error path surfaces nREPL errors.
- `jump_spec.lua` asserts the var-vs-non-var jump dispatch, the
  `(defrule|defquery NAME)` and whole-symbol fallback regexes (including
  punctuation-bearing names like `my-thing?`), and `file:`/`jar:` resource
  resolution.
- Tier 3 is reserved for a future `make test-integration`; not required for CI.

```bash
cd editor/neovim
make check   # format-check + lint + test (one-command gate)
make format  # stylua (apply)
make lint    # selene (std = "neovim")
make clean
```
