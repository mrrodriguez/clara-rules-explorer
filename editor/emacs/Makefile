.PHONY: test test-unit check-elisp lint clean

SHELL := /bin/bash
ROOT := $(shell cd ../.. && pwd)
EL := $(ROOT)/editor/emacs/clara-explorer.el
TEST_DIR := $(ROOT)/editor/emacs/test
OUT := $(ROOT)/server/target/elisp-check

# Prefer eldev if available (resolves cider/parseedn/clojure-mode for you),
# otherwise fall back to plain emacs batch with stubbed deps (same as check-elisp).
test: test-unit

test-unit:
	@if command -v eldev >/dev/null 2>&1; then \
	  echo "==> eldev test (ERT, unit)"; \
	  eldev test --verbose; \
	else \
	  echo "==> eldev not found — falling back to plain emacs ERT (stubbed cider/parseedn)"; \
	  emacs -Q --batch \
	    --eval "(progn (require 'cl-lib) (provide 'cider) (provide 'parseedn) (provide 'clojure-mode))" \
	    --eval "(progn (add-to-list 'load-path \"$(ROOT)/editor/emacs\") (add-to-list 'load-path \"$(TEST_DIR)\"))" \
	    -l ert -l clara-explorer -l test-helper -l clara-explorer-test \
	    -f ert-run-tests-batch-and-exit; \
	fi

# Byte-compile against stubbed APIs — catches syntax/arity errors without cider installed.
check-elisp:
	@$(ROOT)/server/bin/ci/check-elisp.sh

lint: check-elisp

clean:
	rm -rf $(OUT) .eldev
