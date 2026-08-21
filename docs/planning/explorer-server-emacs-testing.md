# Emacs Lisp Testing Options for clara-explorer.el

Testing Emacs Lisp code that depends on external processes (like a Clojure server) and active CIDER connections requires a multi-tiered approach. Below is a breakdown of the testing strategies, frameworks, and dependency management options available.

## 1. Testing Frameworks

### ERT (Emacs Lisp Regression Testing)
* **What it is:** The built-in testing framework for Emacs. Tests are defined using `ert-deftest`.
* **Pros:** Built into Emacs, zero external dependencies to install for the framework itself, standard `assert` macros (`should`, `should-not`, `should-error`).
* **Cons:** Less expressive than BDD frameworks when it comes to mocking and spying.

### Buttercup
* **What it is:** A behavior-driven development (BDD) framework for Emacs Lisp (similar to Jasmine or Mocha).
* **Pros:** Extremely readable syntax (`describe`, `it`, `expect`), and very importantly, comes with built-in mocking and spying (`spy-on`, `spy-and-return-value`).
* **Cons:** An external dependency.

**Recommendation:** Start with **ERT** for simplicity. If you decide to go heavily into mocking CIDER connections instead of live integration testing, **Buttercup** makes mocking significantly easier. *Status: ERT adopted — `editor/emacs/test/clara-explorer-test.el` uses `ert-deftest` + `cl-letf` mocking; Buttercup not needed with the current stub/tier split.*

## 2. Dependency Management & Test Runners

To run tests in an automated CI environment, you need a way to install `clara-explorer.el`'s dependencies (`cider`, `parseedn`, `clojure-mode`) into a headless Emacs instance before running the tests.

* **Eldev (Elisp Development Tool):** The modern standard. It requires very little configuration (an `Eldev` file) and can automatically fetch package dependencies and run ERT tests in batch mode.
* **Cask / Eask:** The traditional standard (Cask) and its modern successor (Eask). You define dependencies in a `Cask`/`Eask` file.
* **Makem.sh / Raw Scripts:** A shell script that manually runs `(package-initialize)` and installs missing packages before running tests.

**Recommendation:** Use **Eldev**. It is actively maintained and handles dependency downloading and batch test execution gracefully. *Status: adopted — `editor/emacs/Eldev` declares `cider "1.12"` / `parseedn "1.2"` / `clojure-mode "5.18"`; `editor/emacs/Makefile#test-unit` prefers `eldev test` when available, falling back to plain `emacs -Q --batch` with stubbed deps.*

## 3. Testing Strategies for CIDER and the Server

Because `clara-explorer.el` relies heavily on CIDER (`cider-nrepl-sync-request:eval`) to communicate with a running Clara server, we have two distinct testing approaches:

### Approach A: Pure Unit Testing (Mocking CIDER)
Test the Emacs Lisp code in isolation without a running server or a real CIDER connection.
* **How:** You mock `cider-connected-p` to return `t`, and `cider-nrepl-sync-request:eval` to return predefined EDN responses based on the Clojure code passed in. You can use `cl-letf` in ERT, or `spy-on` in Buttercup.
* **What it tests:**
  - Structural navigation (`clara-explorer--enclosing-production`, `clara-explorer--side-at-point`).
  - EDN parsing (`clara-explorer--eval-edn`).
  - Correct formatting of the Clojure payload (`clara-explorer--navigate-code`).
* **Pros:** Very fast, zero external setup, highly reliable in CI.
* **Cons:** Does not guarantee that the Clojure code payload will actually work against the real server.

*Implementation split (2026-05):* Approach A is now two tiers via `editor/emacs/test/test-helper.el`:

| Tier | Command | Deps | Coverage |
| --- | --- | --- | --- |
| **A1 — stubbed unit** | `make test-unit` w/o Eldev (`emacs -Q --batch`) | `test-helper.el` `provide`s stubs + `with-clara-buffer` minimal `clojure-mode` + tiny `nrepl-dict-get`/`parseedn-read-str` stubs; `server/bin/ci/check-elisp.sh` byte-compiles against same stubs | 35+ ERT: LHS accumulator cases, `enclosing-production` alias-agnostic, `syntax-ppss` string jump, `props` insert/retract, EDN `substring-no-properties` stripping, vector-target coercion |
| **A2 — real-deps unit** | `eldev test` (or `make test-unit` with Eldev on PATH) | real `cider`/`parseedn`/`clojure-mode` (Eldev); `test-helper.el` guards become no-ops; `with-clara-buffer` calls real `clojure-mode`; `nrepl-dict-get`/`parseedn-read-str` are real | A1 + 5 `skip-unless` tests gated on `test-helper--parseedn-real-p` / `test-helper--real-deps-p`: `clara-explorer--edn-map` → `parseedn-read-str` round-trip (hash-table contract, vectors → `vector`), `clara-explorer--eval-edn`+`nrepl-dict` wiring against real `parseedn` (mocked transport, real parse), `cider-symbol-at-point` keyword `::`/`:kw` in `clojure-mode`, `syntax-ppss` reader-macros/comments |

Approach A now hides far fewer integration touch points than before: the real `clojure-mode` syntax table (not just `emacs-lisp-mode` + `{[}`), the real `parseedn` 20231203 hash-table contract, and the real `cider-symbol-at-point` keyword semantics are all exercised in Tier A2 without needing a JVM.

### Approach B: Integration Testing (Live Server + CIDER REPL) — DEFERRED
Test the full end-to-end flow using a headless Emacs instance connected to a real headless Clojure server.
* **How (deferred):**
  1. Have a script (e.g., in a `Makefile`) start the Clara explorer server with an nREPL port (e.g., `make run-nrepl-for-tests`).
  2. The script blocks until the nREPL port is open.
  3. The script launches Emacs in batch mode (`eldev test` or `emacs --batch`).
  4. In a special setup hook for the tests, Emacs calls `(cider-connect-clj '(:host "localhost" :port 9999))`.
  5. The tests wait for `cider-connected-p` to become true.
  6. The tests run, invoking real CIDER evaluation requests to the real server.
  7. The test teardown closes the CIDER connection, and the wrapper script kills the Clojure server.
* **What it tests:** The entire pipeline, from buffer point position to Clojure graph traversal and back (`client/navigate` ctor resolution, `:via :retract`, global `{:production nil :caller-ns}`).
* **Pros:** True verification of the system. CIDER integration is notoriously tricky to mock perfectly.
* **Cons:** Slower (JVM ~5–10s + nREPL port wait + async `cider-connect`), more complex to orchestrate in CI, and can be flaky if connection timeouts occur. Requires port management and process lifecycle.

*Status (2026-05):* **Deferred to the integration suite.** Tier A2 already covers the previously hidden `clojure-mode`/`parseedn`/`cider-symbol` surface at low cost (Eldev install only). Live-server verification is reserved for a future `editor/emacs/Makefile#test-integration` target once the Tier A2 suite is stable, per roadmap `docs/planning/explorer-server-emacs-roadmap.md`.

## 4. Path Forward (updated 2026-05)

**Phase 1 — DONE (local unit, tiers A1+A2):**
1. ✅ Eldev handles `cider`/`parseedn`/`clojure-mode` resolution (`editor/emacs/Eldev`).
2. ✅ `editor/emacs/Makefile` prefers `eldev test` and falls back to `emacs -Q --batch` with stubbed deps; `server/bin/ci/check-elisp.sh` byte-compiles against stubs.
3. ✅ `test/test-helper.el` provides tier-aware stubs (`provide` + `fboundp`/`autoloadp` guards + `nrepl-dict-get` stub + `test-helper--real-deps-p` / `test-helper--parseedn-real-p` helpers) and `test/clara-explorer-test.el` uses real `clojure-mode` in `with-clara-buffer` plus `skip-unless` Tier-2 tests for EDN round-trip, `eval-edn` wiring, and `cider-symbol-at-point`.

**Phase 2 — DEFERRED (live-server integration):**
1. Add `editor/emacs/Makefile#test-integration` that starts a headless Clojure server + waits for nREPL, runs `eldev test --integration` with `cider-connect-clj`, then tears down. Verify `clara-explorer-navigate-producer/consumer` end-to-end against `loan-app-rules`/`analyze-test-rules`.
2. Once stable locally and flake-free, gate CI on Tier A1+A2 only; run Tier B nightly or on `main` (port-sensitive, allow timeouts).

**Decision rationale (2026-05):** The previous assessment found Tier A1 was hiding `clojure-mode` syntax (`::`/`:` symbol constituents, `;` comments, reader macros), the `parseedn` hash-table/vector contract, and `cider-symbol-at-point` semantics. Tier A2 (real deps, mocked transport) restores that coverage for the cost of an `eldev` cache (~2–5 MB), without JVM/nREPL flakiness. Live-server (Tier B) remains valuable for `client/navigate` payload shape but is higher cost, so it is deferred per user decision.
