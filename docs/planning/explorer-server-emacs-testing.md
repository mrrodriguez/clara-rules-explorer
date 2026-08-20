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

**Recommendation:** Start with **ERT** for simplicity. If you decide to go heavily into mocking CIDER connections instead of live integration testing, **Buttercup** makes mocking significantly easier.

## 2. Dependency Management & Test Runners

To run tests in an automated CI environment, you need a way to install `clara-explorer.el`'s dependencies (`cider`, `parseedn`, `clojure-mode`) into a headless Emacs instance before running the tests.

* **Eldev (Elisp Development Tool):** The modern standard. It requires very little configuration (an `Eldev` file) and can automatically fetch package dependencies and run ERT tests in batch mode.
* **Cask / Eask:** The traditional standard (Cask) and its modern successor (Eask). You define dependencies in a `Cask`/`Eask` file.
* **Makem.sh / Raw Scripts:** A shell script that manually runs `(package-initialize)` and installs missing packages before running tests.

**Recommendation:** Use **Eldev**. It is actively maintained and handles dependency downloading and batch test execution gracefully.

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

### Approach B: Integration Testing (Live Server + CIDER REPL)
Test the full end-to-end flow using a headless Emacs instance connected to a real headless Clojure server.
* **How:**
  1. Have a script (e.g., in a `Makefile`) start the Clara explorer server with an nREPL port (e.g., `make run-nrepl-for-tests`).
  2. The script blocks until the nREPL port is open.
  3. The script launches Emacs in batch mode (`eldev test` or `emacs --batch`).
  4. In a special setup hook for the tests, Emacs calls `(cider-connect-clj '(:host "localhost" :port 9999))`.
  5. The tests wait for `cider-connected-p` to become true.
  6. The tests run, invoking real CIDER evaluation requests to the real server.
  7. The test teardown closes the CIDER connection, and the wrapper script kills the Clojure server.
* **What it tests:** The entire pipeline, from buffer point position to Clojure graph traversal and back.
* **Pros:** True verification of the system. CIDER integration is notoriously tricky to mock perfectly.
* **Cons:** Slower, more complex to orchestrate in CI, and can be flaky if connection timeouts occur.

## 4. Proposed Path Forward

**Phase 1: Local End-to-End Integration (Makefile Driven)**
Because the primary risk and complexity in `clara-explorer.el` lies in its communication with the active Clojure server (and properly handling the EDN responses), we will prioritize integration tests over pure, isolated unit tests.
1. Introduce **Eldev** to handle Elisp dependency resolution (`cider`, `parseedn`) and to run the test suite in a headless batch Emacs instance.
2. Update the `server/Makefile` (or add an `editor/emacs/Makefile`) with a target that:
   - Starts a local headless Clojure server process and waits for it to be ready.
   - Triggers `eldev test` (or an equivalent Emacs batch execution).
   - The test setup hook connects CIDER to the running local server via `cider-connect-clj`.
   - Cleans up the background server process on exit.
3. Write **ERT** tests that perform real navigation commands (like `clara-explorer-navigate-producer`) against the live server and assert on the results.

**Phase 2: CI Automation**
1. Once the local integration test scripts are proven stable and flake-free on developers' machines, incorporate them into the CI pipeline.
