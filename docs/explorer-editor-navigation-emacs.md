# Editor Navigation — Emacs (CIDER)

The Emacs client for the shared editor-navigation feature. See
[Editor Navigation (shared)](./explorer-editor-navigation.md) for the contract,
REPL bootstrap, fact-type resolution, jump semantics, and refresh/swap
workflow — those are identical across editors. This page is the Emacs-specific
surface: install, the provenance view, jump history, debugging, and testing.

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
             clara-explorer-show-producer-provenance
             clara-explorer-show-consumer-provenance
             clara-explorer-refresh
             clara-explorer-swap-session
             clara-explorer-toggle-transport
             clara-explorer-transport-status
             clara-explorer-select-unit))
```

`package-install-file` resolves the `cider`/`parseedn`/`clojure-mode`
dependencies from your configured archives (MELPA), so it needs MELPA added
the same way any Emacs package does.

**Evil:** `C-o` (`evil-jump-backward`) / `C-i` work for every navigation —
see _Jump history_ below.

## Fact type provenance view

`clara-explorer-show-producer-provenance` and
`clara-explorer-show-consumer-provenance` open a side window showing the
upstream or downstream reference tree of the fact type at point. The shared
contract (node kinds, depth, `:cycle?` / `:seen?`, `:type-short` / `:matches`
/ `:bridge?`, and the v1 no-polarity limitation) is documented in the
[shared navigation doc](./explorer-editor-navigation.md#fact-type-provenance-shared);
this section is the Emacs surface.

The view is built-in `tabulated-list-mode` — no `lsp-treemacs` and no extra
package:

| Key | Action |
| --- | ------ |
| `TAB` | expand/collapse the production on the row |
| `RET` / `<return>` | jump to the production (the same target machinery as navigate) |
| `g` | re-run the root call (refresh) |
| `q` | bury the view |

Only `TAB`, `RET`, and `<return>` are bound in the mode map; `g`/`q` come from
`special-mode`, so a setup that shadows `g` (evil's `g` prefix, for example)
can bind `clara-explorer-provenance-refresh` itself. The same production under
two sibling fact types toggles independently.

`RET` opens the production in the window that already shows its source file, if
one does; otherwise in the most recent other window.

`RET` asks the REPL the view was opened from, never the session of whatever
buffer the target window shows. It locates the production in this order:

1. The loaded var's `info` (file and line), whether or not the node says
   `:var? true`. This reaches a namespace loaded from a checkout outside the
   classpath, such as another repo linked to the session with sesman, where
   `cider/ns-path` has no answer.
2. The namespace's classpath resource, searched for the `defrule`/`defquery`
   form.
3. An already-open buffer whose `ns` form names the namespace.
4. The namespace's file under `clara-explorer-source-roots`, found from the
   name alone: any file at any depth whose path ends in `<ns path>.clj` (or
   `.cljc`). The ns path is the name munged as Clojure does (dots to `/`, dashes
   to `_`), and the file is used only when its own `ns` form names the
   namespace. Shallower matches are tried first. The walk skips dot-directories
   and the names in `clara-explorer-source-roots-skip-directories` (default
   `node_modules`, `target`, `out`), because a compiled copy of a `.cljc` file
   carries the same `ns` form as its source.

Steps 2–4 search the file for the production's defining form (see _Production
macros_ below), since only the REPL knows the line.

`clara-explorer-source-roots` defaults to `nil`, which means the parent
directory of the connected REPL's project. With checkouts kept side by side,
that finds a composed tree's other repos with no configuration. Set it to a list
of directories to search those instead; the list replaces the default. A root
can be a single checkout or a directory of them:

```elisp
(setq clara-explorer-source-roots
      '("~/src/team-a/" "~/src/team-b/"))
```

Like a CIDER jump, the opened file is pinned to that REPL when it lies outside
the REPL's project, so `cider-eval-buffer` there uses the same session without
linking it in sesman first.

Two defcustoms control the view:

- `clara-explorer-provenance-depth` — `0` (the root's immediate production
  layer), an integer `n` (inline `n` further levels), or `full` (`:depth
  :full`).
- `clara-explorer-provenance-window-params` — `display-buffer-in-side-window`
  arguments; `nil` uses the side-window defaults.

Backend and transport failures (an `{:error …}` answer or a non-zero babashka
exit) render as `:message` rows for both the root call and a `TAB` expansion,
so a failed request never breaks the buffer. `RET` reports why it cannot jump
when a registry/offline tree names a namespace that is not loaded in the
connected REPL, not on its classpath, and not under the source roots; load that
namespace, open its file, or add its checkout's parent directory to
`clara-explorer-source-roots`. A jump that fails displays nothing, selects no
window, and pushes no jump.

## Production macros

`clara-explorer-production-macros` lists the macros that define a production,
each mapped to its kind. The default is `(("defrule" . rule) ("defquery" .
query))`. Names are unqualified, and any alias prefix matches (`r/defrule`,
`clara.rules/defrule`). Setting the list replaces the defaults, so keep them if
you still use them:

```elisp
(setq clara-explorer-production-macros
      '(("defrule" . rule)
        ("defquery" . query)
        ("rule" . rule)))   ; e.g. :refer [defrule] :rename {defrule rule}
```

A listed macro must take the production name as its first argument, after any
metadata. The list is used in two places:

- **Cursor context**: which enclosing form is the production at point. A query
  has no RHS, so the kind matters.
- **Jump search**: which form is searched for when the REPL cannot say where a
  production is defined. Forms nested in a `let` or `do` are found too. A file
  with no listed head for the name falls back to the name's first whole-symbol
  occurrence, at the lowest precedence.

## Jump history (evil, xref)

Every navigation pushes the origin onto the evil and xref jump lists before it
jumps, so `C-o` (`evil-jump-backward`) / `C-i` and `M-.` / `M-*`
(`xref-pop-marker-stack`) return to where you were — for a provenance `RET` that
is the tree on screen.

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

Tests live in `editor/emacs/test/clara-explorer-test.el`. Three tiers, fastest
first:

| Tier                      | Command                                               | Deps                                                                    |
| ------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------- |
| 1 — stubbed unit          | `make test-tier1`                                     | stubbed `cider`/`parseedn`/`clojure-mode` (`test/test-helper.el`)       |
| 2 — real-deps unit        | `eldev test` (or `make test-unit` with Eldev on PATH) | real `cider`/`parseedn`/`clojure-mode` from `Eldev`                     |
| 3 — live nREPL (deferred) | `make test-integration` (future)                      | running `clara.explorer.server.main` JVM + `cider-connect-clj`          |

```bash
cd editor/emacs && make test-tier1   # Tier 1 only (stubbed, no Eldev required)
cd editor/emacs && make test         # Tier 1 via test-unit, or Tier 2 when Eldev is present
cd editor/emacs && eldev test        # Tier 2 explicitly
```

Tier 1 is the CI path and needs no network or JVM (currently 127 expected + 5
skipped); Tier 2 runs the same suite plus the `skip-unless` tests that only work
with the real libraries (132/132). The stub mechanics and the guards that gate
the Tier-2 tests are documented in `test/test-helper.el`.
