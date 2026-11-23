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
| `:ClaraExplorerShowProducerProvenance` | open the upstream provenance tree for the fact type at point        |
| `:ClaraExplorerShowConsumerProvenance` | open the downstream provenance tree for the fact type at point      |
| `:ClaraExplorerRefresh`             | re-derive annotations and re-warm the analysis                        |
| `:ClaraExplorerSwapSession`         | swap in a rebuilt session (`!` re-prompts)                            |
| `:ClaraExplorerToggleTransport`     | toggle between the nREPL and babashka transports                      |
| `:ClaraExplorerTransportStatus`     | open the effective + configured transport (and the explorer-server probe) in a scratch buffer |
| `:ClaraExplorerLastError`           | open the full last nREPL error (stack trace + code) in a scratch buffer |
| `:ClaraExplorerSelectUnit`          | re-prompt for the babashka transport's registry unit                  |
| `:ClaraExplorerToggleDebug`         | toggle debug logging for the nREPL transport                          |
| `:ClaraExplorerDebugLog`            | open the debug log in a scratch buffer                                |

Direct jump when exactly one candidate; `vim.ui.select` picker when more than
one (delegates to Telescope/snacks/fzf-lua if you have a `ui-select`
integration configured).

### Fact type provenance

The shared contract (see
[Fact type provenance (shared)](./explorer-editor-navigation.md#fact-type-provenance-shared))
is rendered by `lua/clara-explorer/provenance.lua`:
`:ClaraExplorerShowProducerProvenance` and `:ClaraExplorerShowConsumerProvenance`
open a tree in a regular split, and `<Tab>` expands one more production level
with a fresh backend call. Both directions accept a cursor inside a `defquery`.

The view is thin. It gathers the cursor context, builds the operation input,
calls `client/fact-type-provenance` (nREPL) or `--provenance` (bb), caches the
children it is given, and renders the contract's fields — `:type-short`,
`:matches[].short`, and `:bridge?` all arrive from the backend, so no fact-type
name is shortened in Lua.

**Keys** (buffer-local, each also on a `<Plug>` target so it can be rebound):

| Key | `<Plug>` | Action |
| --- | --- | --- |
| `<Tab>` | `<Plug>(ClaraExplorerProvenanceToggle)` | expand / collapse a production row |
| `<CR>` | `<Plug>(ClaraExplorerProvenanceVisit)` | jump to the production on the row |
| `R` | `<Plug>(ClaraExplorerProvenanceRefresh)` | re-run the root call |
| `q` | `<Plug>(ClaraExplorerProvenanceClose)` | close the view |

`g` is deliberately unbound (it is a prefix in several setups); `<Plug>` is the
customization point. A row whose node is gone asks for `R`, a cached error row
retries on `<Tab>`, and an error response becomes a `:message` row instead of
breaking the buffer.

**Settings**:

- **`g:clara_explorer_provenance_depth`** — `0` (default: the root's immediate
  production layer), a non-negative integer, or `"full"` (sent as `:depth
  :full`, bounded by the backend's `:cycle?` / `:seen?`).
- **`g:clara_explorer_production_macros`** — table of unqualified macro name →
  `"rule"` or `"query"`, default `{ defrule = "rule", defquery = "query" }`.
  Setting it **replaces** the defaults. It drives both the cursor-context
  recognition (`structural.lua`) and the definition-site search (`jump.lua`).
  The macro must take the production name as its first argument, after any
  metadata. The whole-symbol fallback search is unchanged and stays last.
- **`g:clara_explorer_source_roots`** — directories that hold checkouts, used
  by the jump when the REPL cannot answer and no buffer visits the namespace.
  Unset means the parent of the REPL's project directory (`user.dir` from the
  JVM, falling back to the origin buffer's project root). A list replaces that.
  The `ns` form of a candidate decides whether it is used, so a compiled
  `.cljc` copy is rejected.
- **`g:clara_explorer_source_roots_skip_directories`** — directory names the
  source-root walk never enters; default `{ "node_modules", "target", "out" }`
  (dot-directories are always skipped). A list replaces the defaults.

The `:depth` setting, the macros, and the source roots are read per call, so a
change applies without reloading the plugin. `:ClaraExplorerRefresh` drops the
cached REPL project directory.

**Jumping from the view.** `<CR>` locates before it displays anything, in the
same order as Emacs: the loaded var's `:file`/`:line` from the REPL (which
reaches a namespace loaded from a checkout that is not on the classpath), then
an open buffer whose own `ns` form names the namespace, then the source roots.
Only a reported line that really holds the production is trusted; otherwise the
file is searched. The window already showing the file wins, else the previous
window, so the provenance split stays in place. A failed jump changes no window
and pushes no jump-list entry, and it names the remedy (load it, open it, or add
a source root). Every REPL request made from the view runs with the origin
buffer current (`nvim_buf_call`), the Lua counterpart of Emacs's
`clara-explorer--call-with-repl`: Conjure resolves its client from the current
buffer, and a `nofile` view is not a Clojure buffer.

## Requirements

- **Neovim 0.10+** (developed and tested on 0.12; `vim.ui.select` needs 0.9+,
  the tree-sitter API needs 0.10+).
- **`conjure`** — nREPL transport (`eval-str`, `def-str`, `current_ns`).
- **`nvim-treesitter`** with the **`clojure` parser** — the structural skeleton
  (`enclosing_production`); navigation degrades gracefully (returns `nil`) when
  the parser is absent, and the structural tests skip.

These are consumer-facing Neovim plugins, installed via lazy.nvim /
AstroNvim — not mise/brew.

## Babashka transport (offline artifacts)

Navigation queries default to the `"auto"` transport: nREPL
(`client/navigate` over Conjure) when the connected session has a running
explorer system, otherwise the babashka transport (shelling out to
`bb editor-client.bb` over the persisted artifact set — no Clara session is
loaded and no Jetty server is started on the repl). The connected repl is
still required in both modes: it resolves aliased/`::` symbols to
fully-qualified form and maps namespaces to source files. Set
`vim.g.clara_explorer_transport = "bb"` (or `"nrepl"`) to force one, or
toggle it with `:ClaraExplorerToggleTransport`.

- **`g:clara_explorer_transport`** — `"auto"` (default), `"nrepl"`, or `"bb"`.
- **`g:clara_explorer_registry_root`** — the registry root (the `rules-annos/`
  tree). When unset, `CLARA_RULES_EXPLORER_REGISTRY` is read from the
  environment.
- **`g:clara_explorer_bb_script`** — path to `editor-client.bb`. When unset,
  the `editor-client.bb` symlink shipped beside `conjure.lua` is used (the
  plugin build step must materialize that repo-relative symlink into the
  plugin-local directory before release).

The bb transport resolves aliased/`::` symbols to fully-qualified form over
`eval-str` before the query, exactly like the nREPL transport, then shells out
for the navigation itself. `:ClaraExplorerSelectUnit` prompts for the
single-unit registry selection and caches it; `:ClaraExplorerRefresh` and
`:ClaraExplorerSwapSession` are no-ops in bb mode (re-persist the artifacts to
pick up changes).

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
`CLARA_RULES_EXPLORER_HOME` environment variable (the repo root). The spec derives
the plugin directory as `$CLARA_RULES_EXPLORER_HOME/editor/neovim`. If the variable
is unset, the plugin is skipped and a warning is emitted — no hard failure.
The variable is read once at Neovim startup; change it and restart.

```sh
# per machine — the repo root of your clara-rules-explorer checkout
export CLARA_RULES_EXPLORER_HOME="$HOME/Projects/clara-rules-explorer"
```

```lua
-- ~/.config/nvim/lua/plugins/clara-explorer.lua
local plugins = {
  "Olical/conjure",
  { "nvim-treesitter/nvim-treesitter", opts = { ensure_installed = { "clojure" } } },
}

local clara_root = vim.env.CLARA_RULES_EXPLORER_HOME
if clara_root then
  plugins[#plugins + 1] = {
    dir = clara_root .. "/editor/neovim",
    init = function()
      -- Map keys strictly in Clojure buffers so they don't clash elsewhere
      vim.api.nvim_create_autocmd("FileType", {
        pattern = "clojure",
        callback = function(args)
          -- Note: gp and gc are standard Vim bindings (paste/comment). 
          -- If you use those, consider changing these to <localleader> mappings like ",gp".
          vim.keymap.set("n", "gp", "<Cmd>ClaraExplorerNavigateProducer<CR>", { buffer = args.buf, desc = "Navigate to Producer (Clara)" })
          vim.keymap.set("n", "gc", "<Cmd>ClaraExplorerNavigateConsumer<CR>", { buffer = args.buf, desc = "Navigate to Consumer (Clara)" })
        end,
      })
    end
  }
else
  vim.notify("CLARA_RULES_EXPLORER_HOME is not set — clara-explorer not loaded", vim.log.levels.WARN)
end

return plugins
```

AstroNvim users add the same spec to their `lua/plugins/` directory; the
plugin loads automatically and registers the four user commands. The plugin
does not hard-code any machine-specific paths, home directories, or ports —
the checkout location comes from `CLARA_RULES_EXPLORER_HOME`.

## Architecture

```
lua/clara-explorer/
├── init.lua       (commands, dispatcher, context gather)
├── structural.lua (tree-sitter skeleton + the production-macro table)
├── token.lua      (Clara token resolution — port of the elisp heuristics)
├── edn.lua        (EDN subset reader — the only module that knows the wire format)
├── conjure.lua    (eval-str wrapper, error surfacing, async plumbing, bb transport)
├── picker.lua     (vim.ui.select)
├── provenance.lua (the fact type provenance view: rendering, toggling, caching)
└── jump.lua       (locate → show: var metadata, open buffers, source roots)
```

- **`edn.lua`** is dependency-free and parses the closed response grammars
  (`NavigateResponse`, the provenance node tree) into plain Lua tables (keyword
  keys/values → strings, `nil` → absent key). It is written and tested in
  isolation. It only decodes; the provenance input is serialized by
  `conjure.provenance_input`, whose nested `:node` / `:input` maps a flat entry
  list cannot express.
- **`token.lua`** is a self-contained byte-based sexp-walker port of the Emacs
  `forward-sexp`/`down-list` heuristics — no parser, no `vim` dependency, so
  its tests run without tree-sitter.
- **`structural.lua`** is the only parser-dependent module; side detection
  lives in `token.lua` (it depends on the props-type heuristic).
- **`jump.lua`** is split into *locate* (REPL var metadata → open buffer → source
  roots, no display) and *show* (jump-list push, window choice, line-or-search),
  so a failed jump leaves every window and the jump list untouched.
- **`provenance.lua`** keeps its state buffer-local and keyed by the **full tree
  path** (alternating fact-type and production names), so the same production
  under two sibling fact types toggles independently.

## Testing

`make -C editor/neovim test` runs the plenary suite headless
(`nvim --headless --noplugin -u test/minimal_init.lua`). Same three-tier model
as Emacs:

| Tier | Scope                          | Files              | Deps                                        |
| ---- | ------------------------------ | ------------------ | ------------------------------------------- |
| 1    | pure-Lua unit                  | `edn_spec.lua`, `token_spec.lua`, `structural_spec.lua` | none (structural skips without the clojure parser) |
| 2    | real modules, mocked transport | `transport_spec.lua`, `bb_transport_spec.lua`, `jump_spec.lua`, `provenance_spec.lua` | stubbed `conjure.eval`/`vim.system`, `vim.ui.select`, `provenance.request`, `vim.cmd.edit`, `vim.fn.search`; scratch fixtures under `target/` |
| 3    | live nREPL integration         | (future)           | headless `server` + a live Conjure nREPL    |

- `token_spec.lua` shares the Emacs fixture corpus (the same rule snippets),
  so the two editors are tested against the same tokens.
- `transport_spec.lua` asserts the Clojure payload is built correctly, EDN is
  parsed, 0/1/N dispatch works, the jump path is invoked with the right
  target, and the `cb` error path surfaces nREPL errors.

On an nREPL error the full stack trace is appended to the Conjure log buffer
(with `; (err) ` prefixes) and stored for `:ClaraExplorerLastError`; the
`:ClaraExplorerTransportStatus` command opens the effective transport plus
the explorer-server probe result in a scratch buffer. Plain stderr output
(e.g. the INFO logs `client/navigate` writes) is not treated as a navigation
error, so it cannot swallow the `value` message that follows.
- `jump_spec.lua` asserts the macro-driven `(<macro> NAME)` head regex and the
  whole-symbol fallback (including punctuation-bearing names like `my-thing?`),
  the macro list replacing the defaults (an empty list matches no head), the
  `ns`-form parser, and the locate order: the var's `:file`/`:line` off the
  classpath, a drifted line being dropped in favour of a search, an open buffer
  as the fallback, and the source roots at any depth (shallowest first,
  `target`/`out`/dot-directories skipped, a wrong `ns` form rejected).
- `provenance_spec.lua` mirrors the plan's P matrix: contract labels and markers
  (`via …` only when `:bridge?`, no Lua shortening), path-keyed sibling
  toggling, a pre-expanded `:depth 1` response needing no fetch, the `<Tab>`
  request shape (`:ancestors` minus self, nothing for cycle/leaf/outputs-unknown
  rows, `:seen?` fetching on demand), error rows at the root and on expansion
  (with a cached error retrying), the collapse/re-expand cache, stale rows, and
  a `defquery` cursor.
- Tier 3 is reserved for a future `make test-integration`; not required for CI.

```bash
cd editor/neovim
make check   # format-check + lint + test (one-command gate)
make format  # stylua (apply)
make lint    # selene (std = "neovim")
make clean
```
