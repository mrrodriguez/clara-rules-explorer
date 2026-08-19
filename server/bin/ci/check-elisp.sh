#!/bin/bash
set -euo pipefail

# Byte-compile the explorer elisp against stubbed cider/parseedn/clojure-mode
# APIs so syntax errors, unknown variables, and bad arities are caught without
# a live Emacs + CIDER session.  Fails on any compiler diagnostic.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
EL="$ROOT/editor/emacs/clara-explorer.el"
OUT="$ROOT/server/target/elisp-check"

mkdir -p "$OUT"

if ! command -v emacs >/dev/null 2>&1; then
  echo "elisp check: emacs not found on PATH — skipping" >&2
  exit 0
fi

LOG="$OUT/compile.log"
: > "$LOG"

# The compiled .elc is written into server/target/elisp-check/ (not next to
# the source) so `make clean` (`rm -rf target`) removes it.
emacs -Q --batch \
  --eval "(progn (require 'cl-lib) (provide 'cider) (provide 'parseedn) (provide 'clojure-mode))" \
  --eval "(progn
            (mapc (lambda (s) (autoload s \"cider\"))
                  '(cider-connected-p cider-current-repl cider-current-ns
                    cider-symbol-at-point cider-find-var cider-find-ns
                    cider-nrepl-sync-request:eval))
            (autoload 'parseedn-read-str \"parseedn\")
            (autoload 'nrepl-dict-get \"nrepl-dict\"))" \
  --eval "(setq byte-compile-error-on-warn nil
                 byte-compile-dest-file-function
                 (lambda (f) (expand-file-name (concat (file-name-nondirectory f) \"c\") \"$OUT\")))" \
  -f batch-byte-compile "$EL" > "$LOG" 2>&1 || true

if grep -Eq "Error|Warning" "$LOG"; then
  echo "elisp check FAILED — see $LOG" >&2
  cat "$LOG" >&2
  exit 1
fi

echo "elisp check passed (no byte-compile diagnostics)"
