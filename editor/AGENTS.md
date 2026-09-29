# Editor integrations

Two editor clients speak the same EDN `navigate` contract to the explorer.
Each has its own `Makefile`, which is the authoritative source for its quality
commands.

| Directory       | Editor        | Language   | Tests                                 |
| --------------- | ------------- | ---------- | ------------------------------------- |
| `emacs/`        | Emacs + CIDER | Emacs Lisp | `make test` (see the tier split below) |
| `neovim/`       | Neovim+Conjure | Lua       | `make test`                           |

The shared Clojure-side contract lives in
`explorer/src/clara/explorer/server/navigate.clj` (the pure body) and
`explorer/src/clara/explorer/server/schema.clj` (the shapes). The editor
resolve-form template is a single source of truth at
`explorer/resources/clara/explorer/server/editor-resolve-form.clj`, symlinked beside
each editor transport. Never hand-edit a symlinked copy; edit the resource and
keep every copy byte-identical (a JVM sync test enforces this).

## Emacs (`emacs/`)

```bash
cd editor/emacs
make test          # auto-tier: eldev (Tier 2) when installed, else Tier 1
make test-tier1    # Tier 1 — plain `emacs -Q --batch`, stubbed cider/parseedn
make check-elisp   # byte-compile against stubbed APIs (also `make lint`)
```

**Tier split — do not report only `make test`.** `make test` selects the Eldev
Tier 2 when `eldev` is on `PATH` (real `cider`/`parseedn`/`clojure-mode`). CI
and machines without `eldev` fall back to Tier 1, which stubs those deps. Both
tiers must be green:

```bash
make test        # Tier 2 (or Tier 1 fallback)
make test-tier1  # Tier 1 explicitly — the CI/no-eldev path
make check-elisp # byte-compile
```

The Tier 1 stubs live in `test/test-helper.el`. The `(autoload …)` forms there
make symbols `fboundp` without loading the real library, so stub guards use
`test-helper--real-fn-p` (checks for a non-autoload function), not bare
`fboundp`.

## Neovim (`neovim/`)

```bash
cd editor/neovim
make test          # headless plenary suite (all spec files)
make format-check  # stylua
make lint          # selene
make check         # format-check + lint + test
```

`stylua`/`selene` are required for `format-check`/`lint`; if they are not
installed, say so rather than skipping silently.

## Working on the shared resolve form

The editor resolve form is generated as a string by
`clara.explorer.server.tokens/editor-token-resolve-form`, which slurps the
resource `clara/explorer/server/editor-resolve-form.clj`. The Emacs/Neovim copies
are repo-relative symlinks into `explorer/resources/...`. A package/plugin build
must materialize the symlinked file into the package-local directory before
release.
