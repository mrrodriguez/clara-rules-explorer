# clara-explorer.nvim

Neovim navigation over a live Conjure nREPL running the Clara Rules Explorer
server. Point at a fact type in a `defrule`/`defquery` and jump to its
producer (LHS) or consumer (RHS), using the dependency graph the server has
already computed.

This is a port of the Emacs/CIDER integration
(`editor/emacs/clara-explorer.el`) to the same shared contract:
`clara.server.graph.client/navigate`. The server-side semantics are unchanged;
this plugin is structural navigation + transport + UX glue in Lua.

No machine-specific paths, home directories, or ports are hard-coded anywhere
in the shipped files.

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

- **Neovim 0.10+** (developed and tested on 0.12).
- `conjure` — nREPL transport.
- `nvim-treesitter` with the `clojure` parser — structural skeleton.

These are consumer-facing Neovim plugins (installed via lazy.nvim/AstroNvim),
not mise/brew packages.

## Install (lazy.nvim / AstroNvim)

```lua
-- ~/.config/nvim/lua/plugins/clara-explorer.lua
return {
  "Olical/conjure",
  { "nvim-treesitter/nvim-treesitter", opts = { ensure_installed = { "clojure" } } },
  -- local checkout, unpublished — replace with your checkout location
  { dir = "~/src/clara-rules-explorer/editor/neovim" },
}
```

AstroNvim users add the same spec to their `lua/plugins/` directory (the
`astronvim-dotfiles` layout); the plugin loads automatically and registers the
four user commands.

## REPL bootstrap

The plugin assumes your Conjure REPL is already running the explorer server:

```clojure
(require '[clara.server.graph.server :as server]
         '[clara.server.graph.client :as client])

(def explorer-system (server/start! {:session my-session :port 9999}))
(client/register! explorer-system)
```

`client/register!` is optional — `navigate` falls back to
`server/get-current-system` (the most recently started server).

## Workflow

| What changed                                             | Fix                                    |
| -------------------------------------------------------- | -------------------------------------- |
| Rule source on disk / annotations                        | `:ClaraExplorerRefresh`                |
| Rules re-evaluated in the REPL (session stale)           | rebuild the session, then `:ClaraExplorerSwapSession` |
| `clara.server.graph.*` source changed (namespace stale)  | `(require 'clara.server.graph.client :reload)` in the REPL, or restart it |

`:ClaraExplorerSwapSession` prompts for a single Clojure expression that
yields the rebuilt session (e.g. a var bound to
`(clara.rules/mk-session ...)`). The last expression is remembered per buffer;
`:ClaraExplorerSwapSession!` re-prompts.

## Development

```bash
cd editor/neovim
mise install   # dev tools: stylua + selene (or bring your own on PATH)
make test      # plenary.nvim, headless (Tiers 1 + 2; tree-sitter cases skip without the parser)
make format    # stylua
make lint      # selene (std = "neovim", vendored neovim.yml)
make clean
```

`stylua`/`selene` are dev-only tooling managed by mise via
`editor/neovim/.mise.toml`; if you don't use mise, install them yourself
(`brew install stylua selene`). See
`docs/planning/explorer-server-neovim-plan.md` and
`docs/planning/explorer-server-neovim-roadmap.md` for the full plan and
testing tiers.
