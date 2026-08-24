# Explorer Server ↔ Neovim (Conjure) Navigation Plan

Status: **Planned** (revised — transport reverted to EDN; structural navigation
and testing expanded; Conjure API corrected)

Related:

- Shared server contract (implemented, no changes planned):
  `server/src/clara/server/graph/client.clj`
- Emacs plan / roadmap (the model this ports):
  `docs/planning/explorer-server-emacs-plan.md`,
  `docs/planning/explorer-server-emacs-roadmap.md`
- Emacs testing tiers this mirrors:
  `docs/planning/explorer-server-emacs-testing.md`

Goal: from a `.clj` buffer connected to a live Conjure REPL that is running the
explorer server (`clara.server.graph.server/start!`), point at a fact type in a
`defrule`/`defquery` and jump to the producer (LHS) or consumer (RHS) of that
type, using the dependency graph the server has already computed.

This is a **port** of the Emacs/CIDER integration to Neovim. The server-side
semantics already exist and are proven; this plan is the Lua client + its tests.

---

## 1. Goals and non-goals

**Goals**

- Four user commands (Neovim user commands / Lua functions):
  1. **Navigate to producer** (`:ClaraExplorerNavigateProducer`)
  2. **Navigate to consumer** (`:ClaraExplorerNavigateConsumer`)
  3. **Refresh analysis** (`:ClaraExplorerRefresh`)
  4. **Swap session** (`:ClaraExplorerSwapSession`)
- Reuse the existing `clara.server.graph.client/navigate` Clojure API **as-is**
  — same input map, same EDN output, same error map. No server changes.
- Direct jump when exactly one candidate; a picker when more than one.
- **Machine-agnostic**: no hard-coded paths, home directories, or ports anywhere
  in shipped Lua. Gate: `grep -R "~/Projects\|/Users/" editor/` is empty.
- Ship a clean Lua plugin (`editor/neovim/lua/clara-explorer/…`) that is tested
  with `plenary.nvim` and formatted/linted with `stylua` + `selene`.

**Non-goals**

- No changes to the existing HTTP API or the `client/navigate` contract.
- No LSP integration in the first cut.
- No file-watch / hot-reload of analysis.
- Not implementing — this document is the plan only.

---

## 2. Contract and transport decision (EDN, not JSON)

`client/navigate` is the shared contract. It is **pure EDN in, EDN out** and was
deliberately specced that way so both editors parse the same thing:

```clojure
;; input  (NavigateInput)
{:production "ns/rule" | nil   ; nil = global path (§5.3)
 :side       :lhs | :rhs | nil
 :caller-ns  "…"
 :token      "…"}

;; output (NavigateResult)
{:direction  :producer | :consumer | :type
 :production "ns/rule" | nil
 :type       "kind.explicit.Type"
 :targets    [{:name "ns/rule" :ns "ns" :type "rule"|"query"
               :via  :insert | :retract
               :source {:var? true|false :file "classpath-relative"|nil
                        :line n|nil :column n|nil}}]}

;; or {:error "…"}
```

The result is a **closed shape**: only strings, keywords, integers, booleans,
`nil`, vectors, and maps. No symbols, sets, ratios, or tagged literals ever
cross the wire.

**Decision: parse EDN on the Lua side; do not introduce JSON.**

Rationale:

1. **Contract parity.** The Emacs plan §12 and roadmap Phase 2 both state the
   neovim client "evals the same form and parses the same EDN". `client/navigate`
   is the single shared contract; both editors parse the identical bytes, and the
   same server tests cover both.
2. **No double encoding.** The Conjure `on-result` callback receives
   `resp.value`, the `pr-str` of the eval result. If we eval
   `(jsonista.core/write-value-as-string (navigate …))`, the result is a Clojure
   *string*, so `resp.value` arrives re-quoted and re-escaped; we would have to
   strip outer quotes and unescape `\"`/`\\`/`\n`/`\uXXXX` before
   `vim.json.decode`. That trades "write an EDN reader" for "write a
   Clojure-string un-escaper + trust jsonista's nil/keyword mapping" — not
   simpler, and it is a correctness-critical step spanning the whole payload.
3. **Lossless and future-proof.** If the contract ever adds a symbol/set/ratio,
   an EDN reader can be extended; a JSON round-trip silently corrupts it.
4. **No jsonista in the editor path.** Eval the existing function directly;
   nothing extra to get right.

**Why hand-roll it: there is no viable off-the-shelf Lua EDN parser.** Neovim's
Clojure support delegates parsing to the JVM rather than reimplementing it in
Lua — Conjure evals code over nREPL and displays the raw `pr-str` value (its
`conjure.client.clojure.nrepl.parse` module only strips `^meta`/comments/shebang,
it does not read EDN), and clojure-lsp is a GraalVM binary that parses EDN
internally but exposes it only over LSP. The only Lua EDN library on GitHub
(`raystubbs/edn.lua`, ~10 stars, no license) is self-described as "very dumb,
low-effort" and unmaintained — not something we can vendor. So the reader is a
first-class, isolated unit we write and test ourselves (§5.1).

The EDN reader is **not** a general parser. It targets the closed
`NavigateResponse` grammar (§4.3) and is a small, pure, unit-tested module.

---

## 3. Architecture

```
editor (Lua)                              Clojure (shared core, already built)
──────────────                            ─────────────────────────────────
find enclosing defrule/defquery  ──┐
detect LHS vs RHS                 │
grab fact-type token at point     │   clara.server.graph.client/navigate
gather caller-ns                 │     (token + side + production|caller-ns) -> EDN
resolve -> EVAL over Conjure ─────┼──▶  (async; value via on-result, errors via cb)
edn.lua parse <- pr-str EDN       │
vim.ui.select picker / jump  ◀────┘   jump: Conjure def-str (var) · resource (non-var)
:ClaraExplorerRefresh ──────────────▶   server/reload-annotations!
:ClaraExplorerSwapSession ──────────▶   server/swap-session!
```

The Clojure form evaluated per navigation:

```clojure
(do
  (require 'clara.server.graph.client)
  (clara.server.graph.client/navigate
    {:production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
     :side :rhs
     :caller-ns "clara.server.tools.graph.rules.loan-app-rules"
     :token "map->ApplicationOutcome"}))
```

`:production nil` + `:caller-ns` (no `:side`) selects the global path (§5.3),
matching the Emacs `--context` semantics.

---

## 4. Structural navigation

### 4.1 Engine choice

There are several ways to find the enclosing form in Neovim:

| Engine | Notes |
| --- | --- |
| **Tree-sitter** (`nvim-treesitter` clojure parser) | Modern, already installed via the AstroNvim clojure pack (`ensure_installed = {"clojure"}`). Good for the *skeleton*: ancestor `list_lit` with a `defrule`/`defquery` head, top-level `=>`, node text at point. |
| `vim.fn.searchpairpos` + `synID` sexp-walker | The Lua analog of the elisp `forward-sexp`/`syntax-ppss`. Dependency-free; a mechanical port of the proven Emacs logic. |
| `nvim-paredit` / `nvim-parinfer` internals | Already in the user's stack, but their APIs are editing-oriented, not a navigation library — not a stable seam. |
| clojure-lsp | Excluded (non-goal); it cannot express producer/consumer semantics anyway. |

**Decision:** use **Tree-sitter for the structural skeleton** (enclosing form,
`=>` position, node text) because it is already installed, is less code than a
sexp-walker, and is testable once the parser is present. Isolate it behind one
module (`lua/clara-explorer/structural.lua`) so a `searchpairpos` port can
substitute without touching transport or UI. This is **not** the only option —
it is the pragmatic one, and it is deliberately swappable.

### 4.2 Enclosing production

Walk up from the cursor's node; find the nearest `list_lit` whose first child is
a symbol matching `defrule`/`defquery` **ignoring namespace aliases**
(`r/defrule` is the common case — `loan_app_rules.clj` uses it throughout).
Extract:

- `name` — the second child (production name), unqualified.
- `kind` — `"rule"` or `"query"` from the head symbol.
- `form_range` — the `list_lit` node range (for side detection + token scoping).

Namespace comes from the buffer, not the tree (§4.5). Outside any
`defrule`/`defquery`, `name = nil` → global path.

### 4.3 LHS vs RHS

Within the enclosing `list_lit`, find the top-level `=>` symbol (depth 1, not
nested inside an inner list/vector — the attr map or a condition). If the cursor
is before `=>`, it is `:lhs`; after, `:rhs`. Queries have no `=>` → always
`:lhs`.

**Props vectors are `:rhs`.** If the cursor is inside the attr map's
`:clara-rules/insert-types` or `:clara-rules/retract-types` (or their
`::`-qualified forms) vector, that is RHS material — the same rule the Emacs
`clara-explorer--props-type-at-point` implements.

### 4.4 Token at point (the hard part — port the Emacs heuristics)

`vim.fn.expand("<cword>")` is **not** acceptable: it depends on the buffer's
`iskeyword`, which is filetype-fragile for `map->Foo`, `Foo.`, `::kw`, and
`laf/map->Foo`. Port the Emacs token logic instead:

1. **Symbol reader.** A small `symbol-at-point` using the same character class
   the elisp already uses for tokens — `A-Za-z0-9._:/!?*+<>-` — with a look-back
   to the token start. Deterministic, testable, no parser dependency.
2. **LHS resolution** (`:lhs`, `clara-explorer--lhs-type-at-point` / `--type-bounds-in-condition-at-point`):
   - skip the optional docstring and attr map after the name;
   - iterate the LHS conditions (top-level vectors between the name and `=>`);
   - for the condition containing the cursor, resolve the fact type:
     - skip a leading `?var <-` binding (`loan_app_rules.clj:86`);
     - accumulator `(acc/all) … :from [Type …]` → the first element of `:from`
       (`loan_app_rules.clj:59-61`);
     - keyword-led wrapper `[:not|:and|:or|:exists …]` → recurse into the inner
       condition that contains the cursor (`loan_app_rules.clj:32,57-58`);
     - plain condition `[Type …]` → first element.
3. **RHS resolution** (`:rhs`): the token is a fact constructor
   (`map->Foo`, `->Foo`, `Foo.`, `Foo/new`, `new Foo`) or a user-defined ctor /
   helper fn reached by the RHS. Send the raw token text; the server resolves it
   against the production's `:insert-types` ∪ `:retract-types` and the serialized
   `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected` callsite
   linkage (§7.4 of the Emacs plan — already implemented, no Lua work).
4. **Props vector** (`{:clara-rules/insert-types [Foo]}` / `:retract-types`):
   the element at point is the token.
5. **String literal** at point → the string is the token (fact-type by name).
6. **Docstring** token: same inner-token scan the elisp does
   (`clara-explorer--docstring-token-at-point`), using the symbol character
   class above.

These cases are the actual complexity of the Emacs implementation (~300 lines);
they must be enumerated and ported, not waved at with "use `<cword>`".

### 4.5 Context

One function gathers `{production, kind, side, caller_ns, token}`:

- `caller_ns` — `require("conjure.extract").context()` (or
  `vim.b["conjure#context"]`); this is the live-buffer namespace, exactly what
  `cider-current-ns` provides on the Emacs side.
- `production` — `caller_ns .. "/" .. name` when inside a form, else `nil`.
- `side` — `:lhs`/`:rhs`/`nil` (§4.3).
- `token` — §4.4.

---

## 5. Transport (Conjure eval)

Conjure's programmatic eval API (verified against `lua/conjure/eval.lua` and
`doc/conjure.txt`):

```lua
local eval = require("conjure.eval")

eval["eval-str"]({
  code   = "(do (require 'clara.server.graph.client) (clara.server.graph.client/navigate {…}))",
  origin = "clara-explorer",
  -- success: value string (the pr-str EDN)
  ["on-result"] = function(value) … end,
  -- errors: called per nREPL message; inspect resp.err / resp.ex / resp["root-ex"]
  cb = function(resp) … end,
})
```

Notes (each is a correction to the previous draft):

- The function is `eval["eval-str"]`, **not** `eval.eval_str`.
- The callback key is `["on-result"]`, **not** `on_result`.
- `["on-result"]` fires **only** when there is a `value`. On a Clojure
  exception there is no value, so `["on-result"]` never fires; surface errors
  via the `cb` (full nREPL response) option, relaying `resp.err`/`resp.ex`/
  `resp["root-ex"]` like the elisp `--err-summary` path.
- **The eval is async.** Capture `bufnr`/`win` at call time (the user may switch
  buffers before the callback runs), and use them in the jump.
- Guard with `require("conjure.client.clojure.nrepl.server").connected?()`
  before evaling, mirroring `cider-connected-p`.

### 5.1 EDN subset reader (`lua/clara-explorer/edn.lua`) — an isolated unit

`edn.lua` is a **self-contained, dependency-free** module exposing exactly one
function, `edn.decode(s) -> table`, for the closed `NavigateResponse` grammar.
It depends on nothing but the Lua standard library — no Conjure, no Tree-sitter,
no `vim.json` — and it is the **only** module that knows the wire format; every
other module consumes its output as plain Lua tables. This isolation is
deliberate: the reader is written and tested on its own (`edn_spec.lua`), and if
a mature, licensed Lua EDN library ever appears, it is a drop-in swap behind
this one function without touching transport or UI.

- maps `{…}`, vectors `[…]`, keywords `:kw` (→ Lua string `"kw"`), strings
  `"…"` (with Clojure/EDN escapes: `\\` `\"` `\n` `\t` `\r` `\b` `\f`
  `\uXXXX`), integers (incl. negative), `true`, `false`, `nil`.
- commas are whitespace.
- keyword map keys → string keys (`:direction` → key `"direction"`,
  `:var?` → key `"var?"`); keyword values → strings (`:retract` →
  `"retract"`).
- EDN `nil` → Lua `nil`. **Documented consequence:** a map entry whose value is
  `nil` is indistinguishable from an absent key in the Lua table — which is
  semantically correct here (`:production nil`, `:source.file nil`, … all mean
  "absent"). This is asserted in tests, not accidental.

No symbols/sets/ratios/tagged literals are required (the contract excludes
them); the reader fails loudly on anything unexpected rather than guessing.

---

## 6. UI (picker + jump)

### 6.1 Picker

Use built-in `vim.ui.select` (Neovim 0.9+), the natural analog of the Emacs
`completing-read`. It delegates to whatever picker is configured (Telescope via
its `ui-select` integration, snacks, fzf-lua) with **no hard dependency** on any
of them.

- Entries: fully-qualified target name (`target.name`), with a ` (retract)`
  suffix when `target.via == "retract"`.
- `vim.ui.select(items, {prompt=…}, function(choice) jump(choice) end)`.
- 0 targets → `vim.notify("No <direction> of <type>")`. 1 → jump directly. N →
  picker.

### 6.2 Jump

**This is the part the previous draft got wrong.** `:source.file` is
**classpath-relative** (var metadata), so `vim.cmd.edit(file)` fails. Resolve
it:

1. **Var-backed targets (`:var? true`, the common case):** reuse Conjure's
   definition lookup — `require("conjure.client.clojure.nrepl.action")["def-str"]({code = target.name})`.
   It does an nREPL `info` op and resolves `file:`/`jar:` to absolute paths
   (including `zipfile://` for jar entries) — the exact analog of
   `cider-find-var`.
2. **Non-var targets (`:var? false`):** eval
   `(some-> (clojure.java.io/resource "…file…") str)` to get a `file:`/`jar:`
   URL, then `vim.cmd.edit`. If `nil`, fall through to (3).
3. **Regex fallback (last resort):** open the namespace file (resolve
   `ns → path` via a `(clojure.java.io/resource (str (str/replace ns "." "/") ".clj"))`
   round-trip) and search for `(defrule|defquery NAME`.

Push the jump list before jumping (`vim.cmd.normal! m'` + `vim.fn.settagstack`),
mirroring `clara-explorer--push-jump` so `C-o` returns.

---

## 7. Refresh / swap-session

Mirror the Emacs §9.9 semantics and the §5.2 staleness contract exactly:

- `:ClaraExplorerRefresh` → eval
  `(do (require 'clara.server.graph.server) (clara.server.graph.server/reload-annotations!))`.
- `:ClaraExplorerSwapSession` → prompt for a session-rebuild expression, cache
  the last per connection, re-prompt on bang/prefix; wrap in
  `(server/swap-session! {:session <expr>})`.

No file-watch, no `BufWritePost` hook. The workflow (re-eval'd rules → rebuild +
swap; edited files without re-eval → refresh) is documented in the README, not
automated.

---

## 8. File structure and dependencies

```
editor/neovim/
├── Makefile                 (format, lint, test, clean)
├── .stylua.toml             (reuse the project config)
├── selene.toml              (std = "neovim")
├── lua/
│   └── clara-explorer/
│       ├── init.lua         (commands, dispatcher, context gather)
│       ├── structural.lua   (tree-sitter skeleton: enclosing form, side, node text)
│       ├── token.lua        (Clara token resolution — port of the elisp heuristics)
│       ├── edn.lua          (EDN subset reader)
│       ├── conjure.lua      (eval-str wrapper, error surfacing, async plumbing)
│       ├── picker.lua       (vim.ui.select)
│       └── jump.lua         (def-str / resource / regex fallback + jump-list push)
├── plugin/
│   └── clara-explorer.vim   (user command registration)
└── test/
    ├── minimal_init.lua
    └── clara-explorer/
        ├── edn_spec.lua
        ├── token_spec.lua
        ├── structural_spec.lua
        └── transport_spec.lua
```

**Runtime deps:** `conjure` (nREPL transport), `nvim-treesitter` (clojure
parser for the skeleton). **No Telescope hard dep** (`vim.ui.select` is
built-in). **Test/dev deps:** `plenary.nvim` (test harness), `stylua`
(formatter), `selene` (linter).

---

## 9. Testing strategy

Mirror the Emacs tier model (`explorer-server-emacs-testing.md`):

### Tier 1 — pure-Lua unit (fast, no network, no JVM, no parser required for most)

- `edn_spec.lua`: decode the closed grammar — maps, vectors, nested targets,
  keywords→string keys, string escapes (`\"`, `\\`, `\n`, `\uXXXX`), `nil`,
  commas, negative ints, `true`/`false`, and the "nil ≡ absent key" contract.
- `token_spec.lua`: the §4.4 heuristics over fixture Clojure buffers —
  accumulator `:from`, `:not`/`:and`/`:or`/`:exists` wrappers, fact binding
  `?x <-`, props vectors, docstring token, `map->Foo`/`Foo.`/`::kw`/`alias/x`
  symbol reads. Use the same fixtures the Emacs suite uses (`with-clara-buffer`
  snippets), so the two editors share a corpus.
- `structural_spec.lua`: enclosing production (alias-agnostic), `=>` side
  detection, query→`:lhs`. **Requires the clojure parser** — guard every case
  with `vim.treesitter.has_parser("clojure")` and `pending`/skip when absent.

### Tier 2 — real-module integration, mocked transport (the Emacs "A2" analog)

Load the real `conjure.eval` / `vim.ui.select` / tree-sitter modules, but stub
`require("conjure.eval")["eval-str"]` to invoke the `["on-result"]`/`cb`
callbacks with canned `pr-str` EDN strings. Assert: the Clojure payload is built
correctly, the EDN is parsed, 0/1/N dispatch works, and the jump path is invoked
with the right target. Also stub the `cb` path to assert error surfacing.

### Tier 3 — live nREPL integration (deferred, like the Emacs Tier B)

Headless `server` + a live Conjure nREPL connection; run real
`client/navigate` calls (record ctor, `:via :retract`, global
`{:production nil}`). Reserved for a future `make test-integration` target; not
required for CI. **Contract parity:** these are the same `navigate` inputs the
Emacs Tier-3 suite would run — the point is one contract, two editors.

### Command + CI

```bash
cd editor/neovim && make test
# = nvim --headless --noplugin -u test/minimal_init.lua \
#     -c "lua require('plenary.test_harness').test_directory('test', { minimal_init = 'test/minimal_init.lua' })"
```

- `test/minimal_init.lua` bootstraps `plenary.nvim` (adds it to `runtimepath`)
  and, for Tier-1 tree-sitter cases, ensures the clojure parser is loadable
  (skip otherwise). It must **not** load the user's full AstroNvim config
  (`--noplugin`).
- `Makefile` targets: `format` (stylua), `format-check` (stylua --check),
  `lint` (selene, `std = "neovim"`), `test` (plenary busted), `check`
  (format-check + lint + test), `clean`.
- CI: add `editor/neovim` to a workflow (the repo has `server.yml`/`ui.yml` but
  no editor workflow yet). Install pinned Neovim (stable), `stylua`, `selene`,
  and plenary; run `make check`. Gate on Tiers 1–2 only; Tier 3 is
  local/nightly (port-sensitive, JVM + connection timing).

### GitHub Actions recipe (learned, to reuse)

`mise` is **local-dev only** — CI installs its own pinned binaries directly,
no mise. One command runs the whole gate: `make -C editor/neovim check`
(format-check + lint + test), with `PLENARY_DIR` pointing at a cloned
`plenary.nvim` (the test harness resolves it from that env var).

Pinned tool versions (match the local mise versions): stylua `2.5.2`, selene
`0.31.0`, Neovim `0.10+` (developed/tested on `0.12.4`). Release assets to
fetch on `ubuntu-latest`:

- Neovim `v0.10.4` → `nvim-linux64.tar.gz` (extracts to `nvim-linux64/bin/nvim`;
  newer releases rename the asset to `nvim-linux-x86_64.tar.gz`).
- stylua `v2.5.2` → `stylua-linux-x86_64.zip` (contains the `stylua` binary).
- selene `0.31.0` → `selene-0.31.0-linux.zip` (contains the `selene` binary).
- plenary.nvim → `git clone --depth 1 https://github.com/nvim-lua/plenary.nvim`,
  then set `PLENARY_DIR` to that checkout.

Add each extracted binary's dir to `$GITHUB_PATH`. The structural tests skip
cleanly when the clojure parser is absent; installing the parser in CI
(`nvim-treesitter` + `TSInstallSync clojure`, or compile the grammar and drop
`clojure.so` into `~/.local/share/nvim/site/parser/`) is an optional follow-up
for full structural coverage.

---

## 10. Roadmap / checklist

Server work is **done**; every box here is Lua + tests, verified top to bottom.

### Phase 0 — spike (prove the semantics manually)

- [ ] Plugin skeleton: `init.lua` + command registration + `minimal_init.lua`.
- [ ] `edn.lua` (Tier-1 corpus).
- [ ] `structural.lua` + `token.lua` (§4) against the demo rules.
- [ ] `conjure.lua` transport (§5) with `on-result` + `cb` error surfacing.
- [ ] `picker.lua` + `jump.lua` (§6) — `def-str` for var targets, resource +
      regex fallback otherwise.
- [ ] `:ClaraExplorerRefresh` / `:ClaraExplorerSwapSession` (§7).
- [ ] **Gate (manual):** in a Conjure-connected buffer over
      `clara.server.tools.graph.rules.loan-app-rules`:
      - `DocumentCheck` in `app-outcome-approved?` LHS → producer jumps to
        `app-has-all-required-docs`;
      - `map->ApplicationOutcome` in RHS → consumer picker/jump;
      - `ApplicationOutcome` inside `:not` → producer;
      - `DocumentCheck` inside accumulator `:from` → producer;
      - `DocumentCheck.` in `rule-retract-java-dot` RHS → consumer with
        `(retract)` suffix;
      - `make-document-check` helper → global consumer path;
      - `::kw` / `alias/x` / `Foo.` tokens resolve; no-match and
        not-inside-a-rule message cleanly.

### Phase 1 — solidify

- [ ] Tier 1 + Tier 2 test suites (shared fixture corpus with the Emacs suite).
- [ ] `Makefile` (`format`/`lint`/`test`/`clean`) + `.stylua.toml`/`selene.toml`.
- [ ] README install snippet (AstroNvim / lazy.nvim; no absolute paths).
- [ ] **Gate:** `make -C editor/neovim format lint test`;
      `grep -R "~/Projects\|/Users/" editor/` empty;
      `grep -R "eval.eval_str\|on_result" editor/neovim/` empty (correct API only).

### Phase 2 — optional (only on evidence)

- [ ] Live nREPL integration (Tier 3, `make test-integration`).
- [ ] `searchpairpos`-based structural fallback, if tree-sitter proves flaky on
      partial/invalid forms (swappable behind `structural.lua`).
- [ ] LSP adapter — revisit only if maintaining elisp + Lua costs more than a
      language-server bridge (Emacs plan §13).

---

## 11. Decisions (resolved)

1. **Transport is EDN over Conjure eval**, parsed by an isolated, dependency-free
   subset reader (`edn.lua`) — not JSON, not a general EDN parser.
   `client/navigate` is unchanged. (The JSON alternative — `print`ing jsonista
   output to the nREPL `out` channel and `vim.json.decode`-ing it, which avoids
   the `pr-str` double-encoding — was considered; it matches the ecosystem's
   "JVM parses, Lua consumes JSON" convention but was rejected in favor of EDN
   contract parity with the Emacs client and losslessness. It is the documented
   fallback if the reader ever becomes a burden.)
2. **Tree-sitter supplies only the structural skeleton** (enclosing form, `=>`,
   node text); the Clara-specific token resolution is an engine-agnostic port of
   the Emacs heuristics and is the bulk of the work.
3. **Picker is `vim.ui.select`** (built-in), no hard Telescope dependency.
4. **Jump reuses Conjure's `def-str`** (nREPL `info` op → absolute `file:`/`jar:`
   path) for var-backed targets; `(clojure.java.io/resource …)` + regex fallback
   otherwise.
5. **Conjure eval is async** — source `bufnr`/`win` captured at call time; errors
   surfaced via the `cb` (full-response) path.
6. **Machine-agnostic** — `grep -R "~/Projects\|/Users/" editor/` empty; refresh/
   swap-session mirror the Emacs staleness contract.

## 12. Open questions

- Picker ordering: fq-name sort (chosen, matches the server's deterministic
  `sort-by :name`) vs. load order. Start with fq name.
- Whether the clojure tree-sitter grammar's error recovery on partial forms is
  good enough in practice, or whether `searchpairpos` becomes the primary engine
  after the spike (Phase 2 fallback).
- Whether to fold `editor/neovim` into a top-level CI workflow or keep a
  dedicated `editor-neovim.yml` (server/ui have their own — recommend a separate
  file).
