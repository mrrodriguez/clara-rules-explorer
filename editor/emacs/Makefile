.PHONY: test test-unit test-tier1 eldev-prepare eldev-test check-elisp lint clean

SHELL := /bin/bash
ROOT := $(shell cd ../.. && pwd)
EL := $(ROOT)/editor/emacs/clara-explorer.el
TEST_DIR := $(ROOT)/editor/emacs/test
OUT := $(ROOT)/server/target/elisp-check
ELDEV_CACHE := $(ROOT)/editor/emacs/.eldev

# Prefer eldev if available (resolves cider/parseedn/clojure-mode for you),
# otherwise fall back to plain emacs batch with stubbed deps (same as check-elisp).
test: test-unit

# Tier 1 (stubbed) — always works, no network, <1s. Used by CI.
test-tier1:
	@echo "==> Tier 1 — plain emacs ERT (stubbed cider/parseedn)"
	@emacs -Q --batch \
	  --eval "(progn (require 'cl-lib) (provide 'cider) (provide 'parseedn) (provide 'clojure-mode))" \
	  --eval "(progn (add-to-list 'load-path \"$(ROOT)/editor/emacs\") (add-to-list 'load-path \"$(TEST_DIR)\"))" \
	  -l ert -l clara-explorer -l test-helper -l clara-explorer-test \
	  -f ert-run-tests-batch-and-exit

# Eldev cache prep — idempotent, network required once. Populates .eldev/
# from Eldev + Package-Requires. Re-run after Eldev or Package-Requires changes.
eldev-prepare:
	@if command -v eldev >/dev/null 2>&1; then \
	  echo "==> eldev prepare (resolves cider/parseedn/clojure-mode into .eldev/)"; \
	  eldev prepare; \
	else \
	  echo "eldev not found — skipping prepare (use Tier 1: make test-tier1)"; \
	  echo "  hint: install eldev 1.11.2 pinned: curl -fsSL https://raw.githubusercontent.com/emacs-eldev/eldev/1.11.2/webinstall/eldev | sh -s -- 1.11.2"; \
	fi

# Tier 2 explicit — fails if eldev not installed (use eldev-prepare first).
eldev-test: eldev-prepare
	@if command -v eldev >/dev/null 2>&1; then \
	  echo "==> eldev test (Tier 2 — real deps, 0 skipped)"; \
	  eldev test; \
	else \
	  echo "eldev not found — cannot run Tier 2"; exit 1; \
	fi

# Auto-tier: Tier 2 if eldev present, else Tier 1 fallback (what you saw).
test-unit:
	@if command -v eldev >/dev/null 2>&1; then \
	  echo "==> eldev detected — running Tier 2 (eldev test)"; \
	  $(MAKE) eldev-test; \
	else \
	  echo "==> eldev not found — falling back to Tier 1 (stubbed cider/parseedn)"; \
	  $(MAKE) test-tier1; \
	fi

# Byte-compile against stubbed APIs — catches syntax/arity errors without cider installed.
check-elisp:
	@$(ROOT)/server/bin/ci/check-elisp.sh

lint: check-elisp

clean:
	rm -rf $(OUT) $(ELDEV_CACHE) .eldev
	@echo "cleaned $(OUT) and $(ELDEV_CACHE) (eldev cache)"
