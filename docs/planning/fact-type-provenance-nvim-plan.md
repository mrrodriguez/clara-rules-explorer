# Plan: fact type provenance in Neovim

Status: **implemented — see
[`fact-type-provenance-nvim-progress.md`](./fact-type-provenance-nvim-progress.md)**
for what landed, the deviations, and the verification runs. The backend contract
and the Emacs client are on the `fact-type-provenance` branch
([`fact-type-provenance-progress.md`](./fact-type-provenance-progress.md), the
source of truth for semantics). This plan ported what Emacs settled to
`editor/neovim/`, so the two editors behave the same. No Clojure change was
needed.

## 1. Scope

Four pieces, each already working in Emacs:

| # | Piece | Emacs reference |
| --- | --- | --- |
| A | Provenance view: a tree for `fact-type-provenance`, both directions | progress §5, `clara-explorer-provenance-mode` |
| B | Jump semantics: locate before display, var line first, no side effects on failure | progress §7.2, §7.3 |
| C | Configurable production macros | progress log item 16, `clara-explorer-production-macros` |
| D | Source roots: find an unloaded namespace's file from its name | progress log item 17, `clara-explorer-source-roots` |

Also ported: the cursor fallback for logical wrappers (point on `:not`,
`:exists`, `:and`, `:or`, or the wrapper's opening bracket resolves the first
child condition's type), which Emacs gained in progress step 7.

Out of scope: any backend change, LSP, and REPL-free token resolution (progress
§10).

## 2. Neovim today

Read from `editor/neovim/` before writing this plan:

- **Commands** (`plugin/clara-explorer.vim`): navigate producer/consumer,
  refresh, swap session, transport toggle/status, select unit, debug log. No
  provenance command.
- **Transports** (`lua/clara-explorer/conjure.lua`): nREPL builds a
  `client/navigate` form (`navigate_code`); bb shells out with
  `vim.system({"bb", script, selection, input})` in `bb_eval`. That call
  hardcodes the argument list, so it cannot pass `--provenance` yet.
  `navigate_entries` encodes a flat map only, but a provenance input nests
  `:node`/`:input`.
- **Cursor context** (`init.lua` `M.context`): tree-sitter finds the enclosing
  production (`structural.lua`), and `token.lua` finds the side and token. Its
  `side` is structural, which is what provenance needs. Navigate then sends that
  same field as the traversal direction.
- **Hardcoded macros**: `structural.lua` compares the head to `"defrule"` /
  `"defquery"` and derives the kind from it. `jump.lua`'s `PRODUCTION_HEAD` is
  `\v\(([^ \t\n()]*/)?def(rule|query)…`. That is already an optional alias plus
  an exact name, the form Emacs tightened to in item 16.
- **Wrapper heads**: `token.lua`'s `wrapper_inner_type_bounds` returns `nil`
  when point is before the first child condition (on `:not` or the bracket).
- **Jump** (`jump.lua`):
  - It pushes the jump list *before* anything is found, so a failed jump still
    leaves an entry.
  - Var-backed targets go to Conjure's `def-str`. Other targets eval one form
    (`SOURCE_LOC_FORM`) that returns a URL: the classpath resource first, then
    the loaded var's `:file`. No line comes back, so the file is always searched.
  - `open_resource` already refuses `""`, so the dired-style failure from
    progress §7.3 cannot happen here. A nil URL reports "cannot resolve
    namespace".
  - It opens with `:edit` in the *current* window.
- **Sessions**: Conjure has one active connection, not sesman's per-buffer
  links, so the "No linked CIDER sessions" failure has no direct counterpart.
  The routing problem it came from still applies, though (§3.2).

## 3. Semantics to port

### 3.1 Provenance view (A)

Contract, depth, cycle, and label rules are in
`docs/explorer-editor-navigation.md` ("Fact type provenance (shared)") and
progress §2. The Lua client follows the thin-editor rule (progress §3):

- It may gather context, build the input, call the operation, cache children,
  and render contract fields.
- It never computes closures, bridges, cycles, or short names. `:type-short`,
  `:matches[].short`, and `:bridge?` arrive from the backend.

**Commands**: `:ClaraExplorerShowProducerProvenance` and
`:ClaraExplorerShowConsumerProvenance`. Both accept a cursor inside a `defquery`;
only consumer *navigate* refuses queries.

**Root input** (one backend call, no separate navigate):

```clojure
{:node {:kind :cursor
        :input {:production "ns/rule" :side :lhs :caller-ns "ns" :token "fq-token"}}
 :direction :producer   ; or :consumer
 :depth 0               ; or n, or :full
 :ancestors []}
```

`:input :side` is the structural side from `token.side_at_point`, and is omitted
when there is none. The traversal direction is the top-level `:direction`, and
nothing else. Navigate's habit of reusing `side` as the direction must not leak
into this input.

**Expansion** (`<Tab>` on a production row):

```clojure
{:node {:kind :production :name "ns/rule"}
 :direction D :depth 0
 :ancestors [<production names on the path, minus the row itself>]}
```

**Transport**:

- nREPL: `client/fact-type-provenance`.
- bb: `editor-client.bb --provenance '<selection>' '<input>'`.

**View state** (buffer-local, the Emacs model):

- direction, root input, and the origin context (§3.2);
- `expanded` and `children` tables keyed by the **full tree path** (alternating
  fact-type and production names), so the same production under two sibling
  fact types toggles independently;
- a line → path map rebuilt on every render.

Any returned `:production` node that already has non-empty `:children` (at
`:depth > 0` or `:full`) seeds both tables on receipt, so it renders expanded
with no fetch.

**Toggle rules** (identical to Emacs):

- `:cycle?`, `:message`, `:outputs :unknown`, and `:children []` rows do not
  toggle.
- A `:seen?` row expands on demand at `:depth 0`.
- A collapsed row re-expands from the cache. A cached message row retries the
  fetch.
- Fact-type rows stay expanded.

**Errors**: `{:error …}`, an eval failure, or a non-zero bb exit becomes a
`:message` row, for the root call and for an expansion alike. A failed request
never breaks the buffer.

**Labels** (Emacs test E2 is the reference):

- `:type-short` with a dimmed `:type`;
- the production's unqualified name (the suffix after `:ns` + `/`) with a dimmed
  ns;
- `@unit`;
- `(retract)` on producer edges, and an insert/retract aside on consumer
  children;
- the markers `↻ cycle`, `↑ shown above` (`:seen?`, hidden while that row is
  expanded), `(external)`, `(query)`, `(no outputs)`, and `(outputs unknown)`;
- `via <shorts>` only when `:bridge?`.

Use highlight groups linked to standard ones (`Comment` for the dimmed parts) so
colorschemes apply.

**Depth**: `vim.g.clara_explorer_provenance_depth`, set to `0` (default), an
integer, or `"full"`. `"full"` is sent as `:depth :full`.

**Keys**, buffer-local:

| Key | Action |
| --- | --- |
| `<Tab>` | toggle the production row |
| `<CR>` | jump to the production (§3.2) |
| `R` | re-run the root call |
| `q` | close the view |

Also expose `<Plug>` mappings so users can rebind. A row whose path has no node
(stale) asks for a refresh.

**Window**: a regular split (decided; not a floating window). The buffer is `buftype=nofile`, `bufhidden=wipe`,
`swapfile=false`, `modifiable=false` between renders, with its own filetype
(`clara-provenance`).

### 3.2 Jump semantics (B)

Emacs's lookup, in order (`clara-explorer--locate-production`), all **before**
any window changes:

1. **The loaded var's file and line** from the REPL, for every node, not only
   `:var? true`. This reaches a namespace loaded from a checkout off the
   classpath.
2. **The namespace's classpath resource**. An empty answer is refused, never
   opened.
3. **A buffer already open** whose `ns` form names the namespace.
4. **The source roots** (§3.4).

Steps 2–4 search for the defining form (§3.3), falling back to the first
whole-symbol occurrence at the lowest precedence. A line from step 1 is trusted
only when the rule name is on it. Otherwise the file is searched, because an
edited buffer drifts from the loaded var's line.

Only once a file is found does the jump push the jump list, choose the window,
and show the buffer. Window choice: the window already showing that file, else
the previous window (`wincmd p`). That keeps the provenance split in place. A
failure changes no window, pushes nothing, and notifies why, naming the remedy
(load it, open it, or add a source root).

Neovim changes to get there:

- Have `SOURCE_LOC_FORM` return `{:file … :line …}` with the **var metadata
  first** (absolute `:file` as is, a classpath-relative one through `resource`),
  then the `.clj`/`.cljc` resource with no line. Today the order is reversed and
  there is no line.
- Split `goto_fallback` into a pure *locate* step (async callback with
  `{path, line}` or a reason) and a *show* step. Move `push_jump` into *show*.
- **Route every REPL eval through the view's origin buffer.** `eval_edn` captures
  `bufnr` only for its callbacks. Conjure's `eval-str` picks its client from the
  current buffer, so an eval issued from the provenance buffer (not a Clojure
  filetype) may find no client. Run the eval inside
  `vim.api.nvim_buf_call(origin_bufnr, …)`. This is the Lua counterpart of
  Emacs's `clara-explorer--call-with-repl`. Verify the failure exists first, with
  a test that evals from a `nofile` buffer.
- Var-backed targets in the provenance view also go through locate (step 1)
  rather than `def-str`. Navigate's picker path may keep `def-str`, since it
  runs from the source buffer.
- No pinning step: Conjure has no per-buffer session to pin.

### 3.3 Production macros (C)

`vim.g.clara_explorer_production_macros`: a table mapping an unqualified macro
name to `"rule"` or `"query"`. The default is
`{ defrule = "rule", defquery = "query" }`. Setting it replaces the defaults. A
listed macro must take the production name as its first argument, after any
metadata.

It drives both:

- `structural.lua`: whether a head is a production, and the kind. The kind feeds
  the "queries have no RHS" guard.
- `jump.lua`: `PRODUCTION_HEAD`, built per call from the names (optional alias
  plus exact name, as today). Escape the names for the regex: build the
  alternation inside `\V` segments, or escape very-magic metacharacters.

The search is not limited to top-level forms, so a `defrule` inside a `let` or
`do` is still found. Keep it that way.

### 3.4 Source roots (D)

`vim.g.clara_explorer_source_roots`: a list of directories that hold checkouts.
Unset means the parent of the REPL's project directory. A list replaces that.

Lookup:

- Munge the namespace (dots to `/`, dashes to `_`).
- Walk each root at any depth for files whose path ends in `<ns path>.clj` or
  `.cljc`, shallowest first. `vim.fs.dir(root, {depth = math.huge, skip = …})`
  or `vim.fs.find` with a predicate both work. There is no depth limit: rulesets
  can sit several directories into a repo, and a root can be one checkout or a
  directory of them.
- Never enter dot-directories or the names in
  `vim.g.clara_explorer_source_roots_skip_directories` (default `node_modules`,
  `target`, `out`). A compiled copy of a `.cljc` file has the same `ns` form as
  its source, so the `ns` check alone cannot reject it.
- Accept the first candidate whose own `ns` form names the namespace.
- Cost: Emacs measured about 0.6s warm across 131 sibling checkouts. It is paid
  once per namespace, because the open-buffer step answers afterwards.

Neovim specifics:

- **The REPL's project directory.** Conjure exposes no `nrepl-project-dir`.
  Recommended: eval `(System/getProperty "user.dir")` once per connection
  (through the origin buffer, §3.2) and cache it. Fall back to
  `vim.fs.root(origin_bufnr, {"deps.edn", "project.clj", "bb.edn", ".git"})`
  when the REPL cannot answer. Decide in step 1 (§6).
- **The `ns`-form check needs a Lua helper.** Neovim has no `cider-get-ns-name`.
  Read the first lines with `vim.fn.readfile(path, "", 200)` and parse the name
  after `(ns`, skipping `^meta`. Do this without opening a buffer, so a rejected
  candidate leaves nothing behind. The same helper answers lookup step 3, by
  scanning loaded buffers.

## 4. Work breakdown, in dependency order

| # | Step | Where | Done when |
| --- | --- | --- | --- |
| 1 | Decide §6's open questions | this doc | each has an answer recorded here |
| 2 | Production macros | `structural.lua`, `jump.lua`, `init.lua` | C-specs green; the default list behaves exactly as today |
| 3 | Wrapper-head fallback | `token.lua` | W-specs green |
| 4 | Origin-buffer eval routing | `conjure.lua` | R-spec shows an eval from a `nofile` buffer reaches the Clojure client |
| 5 | Locate/show split, var line first, source roots | `jump.lua` (+ a small `ns_form_name` helper) | J-specs green |
| 6 | Provenance transport: nested EDN input, nREPL form, bb `--provenance` | `conjure.lua` | T-specs green |
| 7 | Provenance view and both commands | new `lua/clara-explorer/provenance.lua`, `plugin/clara-explorer.vim`, `init.lua` | P-specs green |
| 8 | Docs | `docs/explorer-editor-navigation-neovim.md`, `editor/AGENTS.md`, the shared doc's Neovim notes | "not yet rendered" section replaced; settings documented |
| 9 | Verification | `editor/neovim` | §7 green, plus a manual smoke on a composed registry |

## 5. Test matrix (plenary, `editor/neovim/test/clara-explorer/`)

Each row mirrors an Emacs test, named in parentheses, so a semantic change in
one editor shows up as a diverging pair.

### C — production macros

| # | Case | Expected |
| --- | --- | --- |
| C1 | Default list | `defrule`/`defquery` (aliased too) recognized; `g/defdata` not |
| C2 | Custom list | `{ defdata = "rule", rule = "rule" }` recognizes those and **not** `r/defrule` (`fallback-regexp-follows-the-production-macros-test`) |
| C3 | Kind from the list | a custom query macro yields `kind = "query"` (`enclosing-production-follows-the-production-macros-test`) |
| C4 | Nested forms | `(let [x 1] (r/defrule my-rule …))` and `(do (defquery …))` match |
| C5 | Empty list | the head search matches nothing; the whole-symbol fallback still finds the name |

### W — wrapper heads

| # | Case | Expected |
| --- | --- | --- |
| W1 | Point on `:not` / `:and` | the first child condition's type |
| W2 | Point on the wrapper's `[` | the same |

### R — eval routing

| # | Case | Expected |
| --- | --- | --- |
| R1 | Eval from a `nofile` view buffer | runs with the origin buffer current |

### J — jump

| # | Case | Expected |
| --- | --- | --- |
| J1 | Loaded var off the classpath | the var's `:file` and `:line` win over an empty resource; lands on the line (`provenance-visit-jumps-to-a-loaded-var-off-the-classpath-test`) |
| J2 | Drifted line | the rule is not on the reported line, so the file is searched (`…-reported-line-drifted-test`) |
| J3 | REPL fails, buffer open | the open buffer whose `ns` form matches is used (`…-uses-an-open-source-buffer-…`) |
| J4 | Source roots | found from the name alone at any depth (`modules/rules/src/main/clojure` too), shallowest first; `target`/`out`/dot-directories skipped; a wrong `ns` form is rejected; a root can be one checkout (`source-roots-*`) |
| J5 | Default root | parent of the REPL project directory; a configured list replaces it |
| J6 | Not found | no window change, no jump-list entry, a notify naming the remedy (`provenance-visit-changes-nothing-…`) |
| J7 | Window choice | the window already showing the file wins over the previous one (`provenance-navigation-window-prefers-…`) |
| J8 | Push order | the jump-list push precedes the window change (`provenance-visit-pushes-the-jump-…`) |

### T — transport

| # | Case | Expected |
| --- | --- | --- |
| T1 | Nested input | the root and expansion inputs encode to EDN maps the backend accepts (round-trip through `edn.lua`) |
| T2 | bb | `--provenance` is passed before selection and input; exit and EDN errors become message nodes |
| T3 | nREPL | the form calls `client/fact-type-provenance` |
| T4 | Structural side | `:input :side` is the cursor's side, never the direction |

### P — view

| # | Case | Expected |
| --- | --- | --- |
| P1 | Render recorded EDN (R2-shaped) | labels as §3.1, including `via :loan-app/application-outcome`; no fact-type shortening in Lua (E2) |
| P2 | Sibling paths | the same production under two fact types toggles independently (E3) |
| P3 | Pre-expanded depth | a `:depth 1` response renders expanded with no fetch (E4) |
| P4 | `<Tab>` request shape | `:ancestors` = path production names minus self; nothing is sent for cycle, message, outputs-unknown, or leaf rows; a `:seen?` row fetches (E5) |
| P5 | Errors | `{:error …}` and bb non-zero exit become message rows, at the root and on expansion (E6) |
| P6 | Collapse then expand | the second expansion reads the cache (`provenance-collapse-then-expand-uses-the-cache-test`) |
| P7 | Stale row | `<CR>` on a row with no node asks for a refresh |
| P8 | `defquery` cursor | provenance works inside a query (L3) |

## 6. Open decisions (step 1)

1. **REPL project directory** for the default source root: `user.dir` via eval
   (recommended: it is the REPL's own answer, matching Emacs's
   `nrepl-project-dir`) or `vim.fs.root` of the origin buffer only.
2. ~~View container~~ — **decided: a regular split window**, not a floating
   window.
3. **Refresh key**: `R` (recommended; `g` collides with many `g`-prefixed
   mappings) or a user-chosen `<Plug>` only.
4. **Navigate picker jumps**: keep `def-str` for var-backed targets (recommended
   for now; that runs from the source buffer), or route them through the new
   locate path too, for one jump implementation.

## 7. Verification

```bash
cd editor/neovim
make test          # headless plenary suite
make format-check  # stylua
make lint          # selene
```

If `stylua`/`selene` are missing, say so rather than skipping (`editor/AGENTS.md`).
Manual smoke: open a composed registry tree from a repo whose sibling checkouts
sit beside it, and `<CR>` into another repo's production both with and without
its namespace loaded.
