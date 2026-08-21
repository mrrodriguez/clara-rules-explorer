# Editor Navigation (Emacs)

Jump between Clara productions from a `.clj` buffer connected to a live CIDER
REPL running the explorer server. Point at a fact type in a `defrule`/
`defquery` and:

- `M-x clara-explorer-navigate-producer` — jump from an **LHS** fact type to
  the production that inserts (or retracts) a fact satisfying it.
- `M-x clara-explorer-navigate-consumer` — jump from an **RHS** fact type to
  the downstream productions whose LHS consumes it.
- `M-x clara-explorer-refresh` — re-derive annotations and re-warm the analysis
  after editing rule files without re-evaluating.
- `M-x clara-explorer-swap-session` — swap in a rebuilt session after
  re-evaluating rules in the REPL.

All semantics live in `clara.server.graph.client/navigate` (Clojure); the
editor file is thin transport + UX glue. No absolute paths or ports are
hard-coded.

## REPL bootstrap

Start the server in your CIDER REPL and register it:

```clojure
(require '[clara.server.graph.server :as server]
         '[clara.server.graph.client :as client])

(def explorer-system
  (server/start! {:session my-session :port 9999}))
(client/register! explorer-system)
```

`client/register!` is optional — `navigate` falls back to
`server/get-current-system` (the most recently started server).

## Install

**Spike (no config change):** open `editor/emacs/clara-explorer.el`,
`M-x eval-buffer`, then call the commands with `M-x`.

**Durable (Spacemacs):** add the private layer and set its checkout variable:

```elisp
;; dotspacemacs-configuration-layers
'((clara-explorer :variables clara-explorer-root "~/src/clara-rules-explorer"))
```

The layer skeleton lives at `editor/emacs/spacemacs-layer/` and adds the
`editor/emacs` directory to `load-path` from that variable. No absolute paths
or ports are hard-coded anywhere in the shipped files.

**Evil:** `C-o` (`evil-jump-backward`) / `C-i` work for every navigation —
see *Jump history* below.

## Fact types and LHS structure

`navigate` resolves the token under point to a kind-explicit type string
(`core/extract-lhs-fact-types` contract) — class name, `keyword`, `pr-str`'d
`string`/tuple/map. The editor mirrors that contract:

* **Plain / record / class** — `[Application ...]` or `[?v <- Application]` → `Application`
* **Keyword** — `[?d <- ::supporting-document]` → `::supporting-document` (fully-qualified via `*ns*`)
* **String** — `[?x <- "my-string"]` or `(r/insert! "my-string")` → `"my-string"` (quoted)
* **Vector tuple** — `[:loan/status "verified"]`, `[:my-thing]`, `[:my-thing :qual]` → `pr-str`'d vector. Singletons like `[:my-thing]` are supported — the earlier `space`-required heuristic was removed.
* **Props `{:clara-rules/insert-types [T] :clara-rules/retract-types [T]}`** — the optional rule map (second form after the name) is parsed; point inside its `[T]` vector is treated as an **RHS** producer fact type (same `insert-types`/`retract-types` the server advertises). `[:my-thing]`, `[:my-thing :qual]`, `::kw` and `"str"` all work, and `side` is forced to `:rhs` so `navigate-consumer` finds its consumers (the `no fact type found under cursor in .../insert-income-document` error is now fixed).

* **Accumulator `:from`** — the LHS extractor is `case :fact → :type / :accumulator → :from / :and/:or/:not/:exists → rest` (`core.clj`). The editor walks the condition vector:
  * `[?acc <- (acc/all) :from [:my-thing ...]]` → `:my-thing`
  * `[?acc <- (acc/all) :from [[:my-thing]]]` → `[:my-thing]`
  * `[?acc <- (acc/all) :from [[:my-thing] [this] (= ?x ...)]]` → `[:my-thing]`
  * `[?acc <- (acc/all) :from [[:my-thing :qual]]]` → `[:my-thing :qual]`
  * `[:my-thing [this] (= ?x (:x this))]` inside the `:from` vector still resolves to `:my-thing` even when point is on `[this]` or a constraint — any point inside the condition returns its fact type.

Implementation in `editor/emacs/clara-explorer.el`:

* `clara-explorer--enclosing-production` — `syntax-ppss` string-start jump so point inside `"verified"` in `[:loan/status "verified"]` still finds `r/defrule`.
* `clara-explorer--top-level-=>` — `forward-list` from `form-start` (not from `r/`), `condition-case` on `)` so `() =>` scans and `r/defquery` (no `=>`) correctly returns `nil`.
* `clara-explorer--type-bounds-in-condition` — handles `?var <-`, `(acc/...) :from [Type ...]`, `:and/:or/:not/:exists` groups.
* `clara-explorer--lhs-type-at-point` — scans top-level LHS `[...]` between `name` and `=>`; if `orig` inside the condition, returns its fact type (vector or keyword/string). Handles docstrings (`r/defrule foo "doc" [A] =>`).
* `clara-explorer--vector-fact-at-point` / `clara-explorer--string-at-point` — fallback for `RHS` and global (`insert! [:loan/status "verified"]`, `defn` bodies) where LHS structure does not apply.

**EDN transport** strips Emacs text properties (`fontified`, `face`, `cider-*`) via `substring-no-properties` before `prin1-to-string`; otherwise Emacs prints `#("ns/rule" 0 4 (face ...))` which is invalid EDN for `client/navigate`.

**Targets** are `vector`s from `parseedn`; `choose-target`/`choose-or-jump` coerce `(append vec nil)` so `length`/`mapcar`/`nth` never signal `wrong-type-argument listp`.

## Jump history (evil, xref)

Every navigation pushes the *origin* onto both jump lists **before** the jump, so `C-o` (`evil-jump-backward`) / `C-i` and `M-.` / `M-*` (`xref-pop-marker-stack`) work regardless of path:

* `var? t` — `cider-find-var` (which itself pushes `xref`) + explicit `evil-set-jump`/`xref-push-marker-stack`
* `var? nil` — fallback `cider-find-ns` + `re-search-forward` for `(defrule|defquery name)` — previously had **no** push, now also goes through `clara-explorer--push-jump`

`clara-explorer--push-jump`:
```elisp
(when (fboundp 'evil-set-jump) (evil-set-jump))
(xref-push-marker-stack)
```

## Debugging

Enable verbose logging to `*Messages*`:

```elisp
;; via init
(setq clara-explorer-debug t)
;; or
M-x customize-variable RET clara-explorer-debug RET
```

With `clara-explorer-debug` non-nil, every navigation logs:

```
clara-explorer[debug]: goto: name="my.ns/my-rule" var?=t source={:var? t ...}
clara-explorer[debug]: push evil jump at foo.clj:12
clara-explorer[debug]: push xref marker at foo.clj:12
clara-explorer[debug]: goto: cider-find-var "my.ns/my-rule"

clara-explorer[debug]: goto: name="my.ns/other-rule" var?=nil ...
clara-explorer[debug]: push evil jump at foo.clj:12
clara-explorer[debug]: goto: fallback path
clara-explorer[debug]: fallback: name="my.ns/other-rule" ns="my.ns" rule-name="other-rule"
clara-explorer[debug]: fallback: cider-find-ns "my.ns"
clara-explorer[debug]: fallback: search "other-rule" -> found at 1
```

*If you see `fallback:` you are on the `var? nil` path* (non-var production, or tuple/string type whose var has no `:file`/`:line`). If `var?=t` but `C-o` still fails, check `evil-jump-list` (`M-: (evil-jump-list)`) and `*Messages*` for `push` lines — the origin should appear before `cider-find-*`.

Other useful checks:

* `M-: (featurep 'cider)` / `(featurep 'parseedn)` — both must be `t` (Spacemacs layer ensures it).
* `M-x cider-current-ns` — buffer ns used for `::` resolution and `caller-ns` in `client/navigate`.
* `*nrepl-messages*` / `*Messages*` — `nREPL error:` with `Caused by:` is printed on `client/navigate` `CompilerException`.

## Testing (unit)

The 5 accumulator cases above plus plain/record/keyword/string/tuple/docstring are covered by ERT tests in `editor/emacs/test/clara-explorer-test.el` (see `docs/planning/explorer-server-emacs-testing.md` for the `ERT` vs `Buttercup`/`Eldev` rationale).

Three tiers, fastest first:

| Tier | Command | Deps | What it proves |
| --- | --- | --- | --- |
| 1 — stubbed unit | `make test-unit` (no Eldev) | stubbed `cider`/`parseedn`/`clojure-mode` via `test/test-helper.el` + `with-clara-buffer` minimal syntax | structural nav (`enclosing-production`, `side-at-point`, `lhs-type-at-point`, `vector-fact-at-point`), EDN `substring-no-properties` stripping, vector-target coercion — `35+ passed, 0 unexpected` in `<1s`, no network/JVM |
| 2 — real-deps unit | `eldev test` (or `make test-unit` with Eldev on PATH) | real `cider "1.12"` / `parseedn "1.2"` / `clojure-mode "5.18"` from `Eldev`; `test-helper.el` becomes no-op | Tier 1 + round-trip `clara-explorer--edn-map` → `parseedn-read-str` hash-table, `clara-explorer--eval-edn`+`nrepl-dict` wiring against real `parseedn`, `cider-symbol-at-point` keyword (`::kw`, `:kw`) handling and real `clojure-mode` `syntax-ppss` for `;` comments/reader macros — extra 5 `skip-unless` tests run only here |
| 3 — live nREPL (deferred) | `make test-integration` (future) | running `clara.server.graph` JVM + `cider-connect-clj` | full `client/navigate` payload against `loan-app-rules`/`analyze-test-rules` (ctor, `:via :retract`, global `{:production nil}`) — deferred; tracked in `docs/planning/explorer-server-emacs-testing.md` |

```bash
cd editor/emacs && make test-unit   # Tier 1 (stubbed) or Tier 2 if eldev present
cd editor/emacs && eldev test         # Tier 2 explicitly
```

`test-helper.el` provides `(provide 'cider)` etc. and autoloads `cider-*`/`parseedn-read-str` plus a tiny `nrepl-dict-get`/`parseedn-read-str` stub so `M-x eval-buffer` and `make check-elisp` byte-compile pass without a live REPL. Under Eldev the real packages win (guards are `unless (featurep ...)` / `unless (fboundp ...)` and `autoloadp` checks), and `test-helper--parseedn-real-p` / `test-helper--real-deps-p` gate the Tier-2 tests via `skip-unless`. `with-clara-buffer` prefers real `clojure-mode` when `fboundp`, falling back to `emacs-lisp-mode` + manual `{[}` syntax for Tier 1.

## Refresh workflow

| What changed | Fix |
| --- | --- |
| Rule source on disk / annotations | `M-x clara-explorer-refresh` |
| Rules re-evaluated in the REPL (session stale) | rebuild the session, then `M-x clara-explorer-swap-session` |
| `clara.server.graph.*` source changed (namespace stale) | `(require 'clara.server.graph.client :reload)` in the REPL, or restart it |

A plain `require` is a no-op for already-loaded namespaces, so after the
`clara.server.graph.client` / `server` sources change, a running REPL must
reload them (`:reload`) or be restarted — otherwise a freshly-evaluated form
can fail to compile with a `CompilerException` ("No such var …") against the
stale namespace.

## Swap the session

`M-x clara-explorer-swap-session` hot-swaps a rebuilt session into the
running server.  It prompts for a **single Clojure expression** that, when
evaluated in the REPL, yields the in-memory session (or rulebase) to swap in:

```clojure
(clara.server.graph.server/swap-session! {:session <expression>})
```

The simplest workflow: rebuild the session in the REPL and bind it to a var,
then pass that var as the expression.

```clojure
;; in the REPL, after re-evaluating the rules:
(def s2 (clara.rules/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                'clara.server.tools.graph.rules.loan-app-rules))
```

then `M-x clara-explorer-swap-session` and enter `s2`.  You can also inline
the whole form instead of referencing a var:

```clojure
(clara.rules/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                        'clara.server.tools.graph.rules.loan-app-rules)
```

The expression runs in the REPL's current namespace, so a bare var like `s2`
must be resolvable there (it is, when you `def` it in that namespace).  The
rule namespaces must already be loaded — they are once you have evaluated the
rule files.  The last expression is remembered per connection; `C-u M-x
clara-explorer-swap-session` re-prompts.

`swap-session!` with only `:session` re-derives annotations from rule `:props`
alone, dropping any sidecar / `:enrichment` annotations the server was
started with.  To keep those, call `server/swap-session!` directly with the
same `:annotations` options, or restart the server.
