# Context Management & Protection

**MANDATORY:** To prevent context window flooding, you MUST NOT use `run_shell_command` for any command expected to produce more than 20 lines of output (e.g., full builds or verbose linting).

Instead, you MUST use the `context-mode` sandbox tools:

1.  **Batch Execution:** Use `mcp_context-mode_ctx_batch_execute` for running tests or builds. Always provide specific `queries` to extract only the relevant failures or summaries.
2.  **Surgical Analysis:** Use `mcp_context-mode_ctx_execute` with `language: "shell"` for interactive troubleshooting where you only need a specific answer.

**Rationale:** These tools keep raw data in the sandbox and only return indexed summaries or specific answers to the context window.

# Local Development & Overrides

This project defaults to the public Maven release of `clara-rules`. To develop against a local fork, use the `CLARA_HOME` environment variable (managed via `direnv`).

1.  **Terminal:** Use the `clj-local` helper function:
    ```bash
    clj-local -M:test
    ```
2.  **Emacs/CIDER:** CIDER will automatically detect `CLARA_HOME` via `.dir-locals.el` and inject the necessary `-Sdeps` override during `jack-in`.

# Testing Procedures

To ensure changes are correctly verified and to maintain development velocity, you MUST follow these steps in order:

1.  **MANDATORY: Iterative Feedback (REPL):** If an nREPL server is available (check with `clj-nrepl-eval --discover-ports`), you MUST use `clj-nrepl-eval` for quick feedback on individual tests. This is the fastest way to work and avoids the overhead of starting a new JVM.
    ```bash
    clj-nrepl-eval -p <PORT> <<'EOF'
    (require '[clara.server.tools.graph.core-test] :reload)
    (clojure.test/run-tests 'clara.server.tools.graph.core-test)
    EOF
    ```

2.  **Targeted Test (CLI):** If no REPL is available, use `clojure.test` directly from the CLI.
    ```bash
    clojure -M:test -e "(require '[clojure.test :as t] '<namespace>) (let [result (t/run-tests '<namespace>)] (System/exit (+ (:fail result) (:error result))))"
    ```
    *Example:* `clojure -M:test -e "(require '[clojure.test :as t] 'clara.server.tools.graph.core-test) (t/run-tests 'clara.server.tools.graph.core-test)"`

3.  **Full Suite Verification:** Run the entire project test suite.
    ```bash
    clojure -M:test
    ```

# Linting Procedures

To ensure code quality and adherence to Clojure standards, use `clj-kondo`:

1.  **Targeted Linting:** Lint specific files or directories for quick feedback during development.
    ```bash
    clojure -M:clj-kondo --lint <file-or-dir>
    ```
    *Example:* `clojure -M:clj-kondo --lint src/clara/server/tools/graph/`
