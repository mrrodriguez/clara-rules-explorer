# QA Review and Action Items

This document presents a comprehensive Quality Assurance (QA) review of the recent changes in `clara.server.graph.main` and `clara.server.tools.graph.analyze`, analyzing test coverage, edge cases, documentation alignment, and proposals for extending the smoke test.

---

## 1. Codebase & Test Coverage Audit

### A. CLI Server Entry Point (`server/src/clara/server/graph/main.clj`)

The recent changes introduced:
1. REST/API server option refactoring (`run-explorer-server`, `validate-server-options`, `load-session-state`).
2. Support for generating annotations statically from paths via the `-g` / `--generate-annotations` flag.
3. Custom deserializer integration via the `--load-session-state-fn` flag.

#### Test Coverage Gaps & Edge Cases
* **`-main` and CLI Flag Parsing:** The `-main` function itself is never invoked in `main_test.clj`. There is no verification that passing valid options behaves correctly (e.g., calls the server or generator) or that passing invalid options exits with the correct status code.
* **Port Validation Errors:** The `:port` option contains a validator: `:validate [#(< 0 % 65536) "Port must be between 1 and 65535"]`. This validation logic is not tested. Passing an out-of-range port or non-integer string should be verified to fail gracefully with status code `1` and print the usage details.
* **`-g` CLI Execution:** The static annotation generation path through the CLI is not tested. The test suite should verify that `--generate-annotations` correctly invokes `run-generate-annotations`, writes the expected output format to stdout, and exits with `0`.
* **Custom Deserializer Resolution Errors:** If `--load-session-state-fn` is passed with a symbol that cannot be resolved (e.g., typo in namespace), it throws `IllegalArgumentException` inside `load-session-state`. Since this exception is not caught in `-main`, the JVM crashes with a stack trace. This should be explicitly caught and handled gracefully in `-main` to output a clean user error.

---

### B. Rulebase Analyzer (`server/src/clara/server/tools/graph/analyze.clj`)

The recent changes introduced:
1. Static index-based constructor resolution (`record-constructor-index`, `record-class-index`) with fallback to JVM `Class/forName` checks.
2. LHS usage sanitization to remove condition matching nodes and constructors from the rule's RHS call graph.
3. Transitive dependency analysis for CLI path linting.

#### Test Coverage Gaps & Edge Cases
* **LHS Sanitization with `=>` Operator Collisions:**
  `find-arrow-pos` locates the RHS split point using `str/index-of line "=>"`.
  * **The Bug:** If a rule contains the substring `=>` on its LHS (e.g., inside a comment like `;; => holds true` or a string literal like `[Fact (= ?x "=>")]`), `find-arrow-pos` will match that first occurrence instead of the rule's actual RHS separator arrow.
  * **The Effect:** The LHS/RHS split index will be incorrectly shifted to the left, which will misclassify LHS conditions as RHS side-effects, polluting the call-graph and returning incorrect annotation outputs.
  * **Proposed Solution (Remove LHS Sanitization Complexity):**
    After evaluating the edge cases and the complexity of distinguishing LHS usages from RHS usages, we determined that defending against LHS usages polluting the call graph provides no meaningful gain. It is highly unlikely for an LHS condition to transitively call an insert or retract function (e.g., an accumulator function doesn't call insert). For LHS constructors (e.g., `Accumulator`), identifying them as reachable is acceptable and accurate. Therefore, the entire LHS sanitization logic (`find-arrow-pos`, `sanitize-analysis`, etc.) has been removed completely, drastically simplifying `analyze.clj` and avoiding any arrow parsing bugs natively.
  * **Status:** Implemented. The LHS filtering logic was removed, and assertions in `analyze_test.clj` were updated to tolerate valid LHS-reachable types (like `clara.rules.engine.Accumulator`). The obsolete `rule-lhs-arrow-collision` test was deleted.
* **Namespace Filter Combinations:** `build-analysis-from-namespaces` allows filtering via both `:include-ns-prefixes` and `:exclude-ns-prefixes`. The tests cover `:include-ns-prefixes` but do not verify the combined effect or precedence when both include and exclude prefixes are provided.
* **Missing Source Files Fallback:** If a namespace is loaded on the classpath but its source file is missing (e.g., it is a library compiled inside a JAR without source), `get-source` returns `nil`. When source is unavailable, LHS sanitization is bypassed (all usages are kept). This fallback path is not covered by any unit tests.

---

## 2. Documentation Alignment Audit

We compared the codebase implementation with `README.md`, `server/README.md`, and `docs/rule-annotations.md`:

### A. Root `README.md` Quick Start
* **Discrepancy:** The Quick Start instructions state:
  ```bash
  cd server
  clj -M:dev -m clara.server.graph.main
  ```
  However, running this command without parameters fails immediately with:
  `Error: Either --session or --generate-annotations is required.`
* **Fix:** Update the instructions to guide the user to run `clojure -M:demo-setup` first, then run `clojure -M:demo-run -s demo-data/session.bin` or include the `-s` flag in the quickstart command.

### B. `server/README.md` CLI Options Table
* **Discrepancy:** The table of CLI flags in `server/README.md` completely omits the `-g` / `--generate-annotations` flag.
* **Fix:** Add the `-g` flag and its details to the options table in `server/README.md`.

### C. `server/README.md` Test Execution
* **Discrepancy:** Under the "Running Tests" section, the document says:
  ```bash
  clojure -M:test
  ```
  However, `:test` in `deps.edn` only sets up paths and dependencies. Running `clojure -M:test` will not start the test runner.
* **Fix:** Correct this to run `clojure -M:test:run-tests` or instruct the user to run `make test`.

---

## 3. Smoke Test Automation & Extension

### Current State
`server/test/clara/server/graph/smoke_test.clj` is currently a developer scratchpad containing helper functions and a manual `(comment ...)` block for REPL execution. It is **not** executed by `make test` or CI.

### Extension Plan
We should turn `smoke_test.clj` into an automated integration test suite that verifies the HTTP API end-to-end.

1. **Automate Server Lifecycle:** Implement a `deftest` that programmatically starts the explorer server on a free/dynamic port and shuts it down in a `try-finally` or `use-fixtures` block.
2. **Execute REST Assertions:** Use `clj-http.client` to perform HTTP queries against:
   * `/v1/rulebase-summary` (verifying dashboard counts)
   * `/v1/rules` (checking rule list parsing)
   * `/v1/session/fact-types` (verifying fact-type tracking in working memory)
   * `/v1/session-snapshot` (checking full working memory serialization)
3. **Register in Test Runner:** Ensure the namespace is loaded and executed as part of `make test`.

---

## 4. Summary of Planned Actions

Based on this QA review, the following actions are proposed:

| Action Item | Target Component | Description |
|---|---|---|
| **1. Fix Main CLI Docs** | `README.md`, `server/README.md` | Align the quick-start commands, CLI tables, and test runner instructions. |
| **2. Add CLI Options Tests** | `main_test.clj` | Add unit tests for invalid CLI inputs, port validation, and `--generate-annotations` (`-g`). |
| **3. LHS Arrow Collision Test** | `analyze_test.clj` | Add a rule containing `=>` in comments/strings on the LHS to verify/diagnose the LHS parser. |
| **4. Automate Smoke Test** | `smoke_test.clj` | Wrap the manual smoke test into a `deftest` with HTTP client assertions, and register it in the automated suite. |
