# Editor Navigation — Emacs (CIDER)

The Emacs client for the shared editor-navigation feature. See
[Editor Navigation (shared)](./explorer-editor-navigation.md) for the contract,
REPL bootstrap, fact-type resolution, jump semantics, and refresh/swap
workflow — those are identical across editors. This page is the Emacs-specific
surface: install, jump history, debugging, and testing.

The client is a single file, `editor/emacs/clara-explorer.el`.

## Install

**Spike (no config change):** open `editor/emacs/clara-explorer.el`,
`M-x eval-buffer`, then call the commands with `M-x`.

**Durable (Spacemacs layer — unpublished, installed from a local directory):**
Spacemacs discovers a layer by its directory name, so the layer is
`editor/spacemacs/clara-explorer/` (the directory name _is_ the layer
symbol). Two equivalent ways to install it without publishing anything:

```elisp
;; A: point Spacemacs at the repo's layer parent dir (live — no copy).
;; add-to-list keeps any other layer paths you already have; a bare `setq'
;; would replace them. Trailing slash required.
;; ~/.spacemacs
(add-to-list 'dotspacemacs-configuration-layer-path
             "~/src/clara-rules-explorer/editor/spacemacs/")

;; dotspacemacs-configuration-layers
'((clara-explorer :variables clara-explorer-root "~/src/clara-rules-explorer"))
```

```sh
# B: copy (or symlink) the layer dir into Spacemacs' private layers dir
mkdir -p ~/.spacemacs.d/layers
cp -R ~/src/clara-rules-explorer/editor/spacemacs/clara-explorer ~/.spacemacs.d/layers/
# or, to keep edits live:
#   ln -s ~/src/clara-rules-explorer/editor/spacemacs/clara-explorer \
#         ~/.spacemacs.d/layers/clara-explorer
```

Then reload with `SPC f e R` (or `M-m f e R`). Either way the layer declares
the `cider`/`parseedn`/`clojure-mode` dependencies and loads `clara-explorer.el`
from the checkout via the `clara-explorer-root` variable. No absolute paths or
ports are hard-coded anywhere in the shipped files.

**Plain package (no Spacemacs):** the same `editor/emacs/clara-explorer.el` is
already package-shaped (`Package-Requires`/`Version` headers). Two options:

```sh
# build a package tarball, then in Emacs: M-x package-install-file RET dist/clara-explorer-0.1.0.tar RET
cd editor/emacs && make package
```

```elisp
;; or load straight from the checkout with use-package
(use-package clara-explorer
  :load-path "~/src/clara-rules-explorer/editor/emacs"
  :commands (clara-explorer-navigate-producer
             clara-explorer-navigate-consumer
             clara-explorer-refresh
             clara-explorer-swap-session))
```

`package-install-file` resolves the `cider`/`parseedn`/`clojure-mode`
dependencies from your configured archives (MELPA), so it needs MELPA added
the same way any Emacs package does.

**Evil:** `C-o` (`evil-jump-backward`) / `C-i` work for every navigation —
see _Jump history_ below.

## Emacs implementation notes

Two details are Emacs-specific (the shared semantics live in the common doc):

- **EDN transport strips text properties.** `substring-no-properties` before
  `prin1-to-string`; otherwise Emacs prints `#("ns/rule" 0 4 (face ...))`
  which is invalid EDN for `client/navigate`.
- **Targets are `vector`s from `parseedn`.** `choose-target`/`choose-or-jump`
  coerce `(append vec nil)` so `length`/`mapcar`/`nth` never signal
  `wrong-type-argument listp`.

## Jump history (evil, xref)

Every navigation pushes the _origin_ onto both jump lists **before** the jump,
so `C-o` (`evil-jump-backward`) / `C-i` and `M-.` / `M-*` (`xref-pop-marker-stack`)
work regardless of path:

- `var? t` — `cider-find-var` (which itself pushes `xref`) + explicit
  `evil-set-jump`/`xref-push-marker-stack`
- `var? nil` — fallback `cider-find-ns` + `re-search-forward` for
  `(defrule|defquery name)` — previously had **no** push, now also goes
  through `clara-explorer--push-jump`

```elisp
;; clara-explorer--push-jump
(when (fboundp 'evil-set-jump) (evil-set-jump))
(xref-push-marker-stack)
```

## Debugging

Enable verbose logging to `*Messages*`:

```elisp
(setq clara-explorer-debug t)   ;; or M-x customize-variable RET clara-explorer-debug
```

With `clara-explorer-debug` non-nil, every navigation logs:

```
clara-explorer[debug]: goto: name="my.ns/my-rule" var?=t source={:var? t ...}
clara-explorer[debug]: push evil jump at foo.clj:12
clara-explorer[debug]: push xref marker at foo.clj:12
clara-explorer[debug]: goto: cider-find-var "my.ns/my-rule"
```

_If you see `fallback:` you are on the `var? nil` path_ (non-var production,
or tuple/string type whose var has no `:file`/`:line`). If `var?=t` but `C-o`
still fails, check `evil-jump-list` (`M-: (evil-jump-list)`) and `*Messages*`
for `push` lines.

Other useful checks:

- `M-: (featurep 'cider)` / `(featurep 'parseedn)` — both must be `t`.
- `M-x cider-current-ns` — buffer ns used for `::` resolution and `caller-ns`.
- `*nrepl-messages*` / `*Messages*` — `nREPL error:` with `Caused by:` is
  printed on `client/navigate` `CompilerException`.

## Testing

Tests live in `editor/emacs/test/clara-explorer-test.el` (see
`docs/planning/explorer-server-emacs-testing.md` for the `ERT` vs
`Buttercup`/`Eldev` rationale). Three tiers, fastest first:

| Tier                      | Command                                               | Deps                                                                                                        | What it proves                                                                                                                                                                                                                                                                                                                    |
| ------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 — stubbed unit          | `make test-unit` (no Eldev)                           | stubbed `cider`/`parseedn`/`clojure-mode` via `test/test-helper.el` + `with-clara-buffer` minimal syntax    | structural nav, EDN `substring-no-properties` stripping, vector-target coercion — `35+ passed, 0 unexpected` in `<1s`, no network/JVM                                                                                                                                                                                            |
| 2 — real-deps unit        | `eldev test` (or `make test-unit` with Eldev on PATH) | real `cider "1.12"` / `parseedn "1.2"` / `clojure-mode "5.18"` from `Eldev`; `test-helper.el` becomes no-op | Tier 1 + round-trip `clara-explorer--edn-map` → `parseedn-read-str` hash-table, `clara-explorer--eval-edn`+`nrepl-dict` wiring against real `parseedn`, `cider-symbol-at-point` keyword handling and real `clojure-mode` `syntax-ppss` — extra `skip-unless` tests run only here |
| 3 — live nREPL (deferred) | `make test-integration` (future)                      | running `clara.server.graph` JVM + `cider-connect-clj`                                                      | full `client/navigate` payload against `loan-app-rules`/`analyze-test-rules` (ctor, `:via :retract`, global `{:production nil}`) — deferred; tracked in `docs/planning/explorer-server-emacs-testing.md` |

```bash
cd editor/emacs && make test-unit   # Tier 1 (stubbed) or Tier 2 if eldev present
cd editor/emacs && eldev test         # Tier 2 explicitly
```

`test-helper.el` provides `(provide 'cider)` etc. and autoloads `cider-*`/
`parseedn-read-str` plus a tiny `nrepl-dict-get`/`parseedn-read-str` stub so
`M-x eval-buffer` and `make -C editor/emacs check-elisp` byte-compile pass
without a live REPL. Under Eldev the real packages win (guards are
`unless (featurep ...)` / `unless (fboundp ...)` and `autoloadp` checks), and
`test-helper--parseedn-real-p` / `test-helper--real-deps-p` gate the Tier-2
tests via `skip-unless`.
