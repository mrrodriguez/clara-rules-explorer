# Context Management & Protection

**MANDATORY:** To prevent context window flooding, you MUST NOT use `run_shell_command` for any command expected to produce more than 20 lines of output (e.g., full builds or verbose linting).

Instead, you MUST use the `context-mode` sandbox tools:

1.  **Batch Execution:** Use `mcp_context-mode_ctx_batch_execute` for running tests or builds. Always provide specific `queries` to extract only the relevant failures or summaries.
2.  **Surgical Analysis:** Use `mcp_context-mode_ctx_execute` with `language: "shell"` for interactive troubleshooting where you only need a specific answer.

**Rationale:** These tools keep raw data in the sandbox and only return indexed summaries or specific answers to the context window.

# Server State Architecture

Server state is consolidated into a single `state-atom` per system instance.
The authoritative shape is the `ServerState` schema in
`clara.server.graph.server` (`s/defschema`) — the single source of truth; do
not enumerate its keys here or in docstrings.

**Atom discipline:** all mutation entry points are operator-driven (REPL, CLI,
tests) — never HTTP, so swaps are effectively single-threaded. Transitions are
pure functions over state values (`transition-start`, `transition-swap`,
`transition-reload`). If a concurrent mutation path is ever introduced,
serialize with a lock rather than designing around CAS retries.

- Do NOT add new per-domain atoms. New state keys go into the existing
  consolidated state map.
- Mutate via `swap!` — never `(reset! a (f @a))` (race against concurrent
  swaps).
- Use the return value of `swap!` rather than dereferencing after mutation
  (the value may have changed again).
- Side effects (Jetty start/stop, cache warming, println diagnostics) stay
  OUTSIDE `swap!` — consume the value returned by `swap!`, never a follow-up
  deref.

# Local Development & Overrides

This project builds on the **gateless fork** of clara-rules — `com.github.gateless/clara-rules`
in `deps.edn` — **not** the upstream `com.cerner/clara-rules` (Cerner, now Oracle). The public
Maven release of the gateless fork is used by default. To develop against a local checkout
of the gateless fork, use the `CLARA_HOME` environment variable (managed via `direnv`).

1.  **Terminal:** Use the `clj-local` helper function:
    ```bash
    clj-local -M:test
    ```
2.  **Emacs/CIDER:** CIDER will automatically detect `CLARA_HOME` via `.dir-locals.el` and inject the necessary `-Sdeps` override during `jack-in`.

# Testing Procedures

To ensure changes are correctly verified and to maintain development velocity, you MUST follow these steps in order:

1.  **MANDATORY: Formatting:** After every code change, run `make format` to
    auto-format all source files via cljfmt. CI's `format-check` will fail if
    this is skipped, so formatters that do not guarantee cljfmt compatibility
    (e.g. clojure-lsp) must be verified against it.

2.  **MANDATORY: Iterative Feedback (REPL):** If an nREPL server is available (check with `clj-nrepl-eval --discover-ports`), you MUST use `clj-nrepl-eval` for quick feedback on individual tests. This is the fastest way to work and avoids the overhead of starting a new JVM.

    ```bash
    clj-nrepl-eval -p <PORT> <<'EOF'
    (require '[clara.server.tools.graph.core-test] :reload)
    (clojure.test/run-tests 'clara.server.tools.graph.core-test)
    EOF
    ```

3.  **Targeted Test (CLI):** If no REPL is available, use `clojure.test` directly from the CLI.

    ```bash
    clojure -M:test -e "(require '[clojure.test :as t] '<namespace>) (let [result (t/run-tests '<namespace>)] (System/exit (+ (:fail result) (:error result))))"
    ```

    _Example:_ `clojure -M:test -e "(require '[clojure.test :as t] 'clara.server.tools.graph.core-test) (t/run-tests 'clara.server.tools.graph.core-test)"`

4.  **Full Suite Verification:** Run the entire project test suite.
    ```bash
    make test
    ```

# Test Matchers (matcher-combinators)

For assertions over nested maps/vectors where you only care about some keys,
use `matcher-combinators` instead of hand-building full `=` expectations.

```clojure
(:require [matcher-combinators.test :refer [match?]])

(is (match? {:callsites [{:source-str "(->fact :t m)"
                          :status :full}]
             :resolution :full}
            actual-detection))
```

Two rules make the common case terse — **do not reach for
`matcher-combinators.matchers` (`m/embeds`, `m/equals`, …) unless you need
non-default semantics**:

- **Maps match as a subset by default** (`embeds` semantics): every expected
  key/value must be present, extra keys in `actual` are allowed.  This is how
  the example above ignores `:via` / `:callsite-id` without naming them.
- **Sequences/vectors match exactly by default** (`equals` semantics): same
  length and order.  A one-element vector `[{…}]` asserts exactly one
  callsite, not "at least one".

`match?` is `clojure.test/is`-integrated (assert-expr), not a plain fn — always
use it inside `(is (match? expected actual))`, never call it standalone.  Only
pull in `matcher-combinators.matchers` when you genuinely need `embeds` on a
*sequence* (order-agnostic subset), `prefix`, `regex`, `in-any-order`,
`set-equals`, etc.

# Linting Procedures

To ensure code quality and adherence to Clojure standards, use `clj-kondo`:

1.  **Targeted Linting:** Lint specific files or directories for quick feedback during development.

    ```bash
    clojure -M:lint --lint <file-or-dir>
    ```

    _Example:_ `clojure -M:lint --lint src/clara/server/tools/graph/`

2.  **Full Project Linting:** Run the full project linting.
    ```bash
    make lint
    ```

# Schema Libraries

### Schema Validation

**All test namespaces MUST** enable `schema.test/validate-schemas` via a `:once`
fixture.  No exceptions — a test namespace exercising `s/defn` / `s/defschema`
code without it validates nothing at all.

```clojure
(ns my.ns-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)
```

`schema.test/validate-schemas` **is** the global schema instrumentation in this
schema version (there is no `schema.test/instrument` — do not hunt for it or
add a selective `instrument` fixture).  While active it:

- validates every `s/defn` function's **input** and **return** schemas at call
  time (wrong-arg and wrong-shape return values both throw), and
- validates every `s/defschema` definition is well-formed.

`s/defn` does **not** validate at runtime outside this fixture, so missing it
silently skips all schema checks.  When combining with other `:once` fixtures,
compose them in one form:
`(use-fixtures :once st/validate-schemas other-fixture)`.  The `:once` scope is
intentional — validation is global and cheap to leave on for a whole namespace.

# Clara Rules API

**`clara.rules/mk-session`** takes a single variadic sequence of sources and
options:

```clojure
;; Sources come first (productions, queries, hierarchies), followed by
;; key-value option pairs — NOT a map:
(r/mk-session production-1 production-2
              :cache false
              :activation-group-sort-fn my-sort-fn)
```

The function splits by looking for the first keyword: everything before it
is a source, everything after becomes `(apply hash-map ...)`.

**Runtime rule/query generation:** `clara.rules/defrule` and `defquery` are
compile-time macros. When rules or queries need to be generated at runtime
(e.g. in tests, performance harnesses, or dynamic rule loading), construct
the production maps directly as plain Clojure maps and pass them to
`mk-session` instead of trying to invoke the macros:

```clojure
;; DON'T — macros expand at compile time, can't be used to generate at runtime:
;; (defrule my-rule ...)   ; only works when written literally in source

;; DO — build production maps directly:
{:name    "my-ns/my-rule"          ;; string or symbol; the rule's fq name
 :ns-name 'my.ns                    ;; namespace symbol
 :doc     "what this rule does"
 :lhs     [{:type SomeFact :constraints []}]
 :rhs     '(clara.rules/insert! (->SomeOtherFact ...))}
```

The `:lhs` is a vector of condition maps (each with `:type` and
`:constraints`). The `:rhs` is a quoted s-expression — the same body you'd
write inside a `defrule` macro. Queries follow the same pattern with
`:params`, `:lhs`, and a `:type` of `:query`. See
`server/test/clara/server/tools/graph/rules/perf_gen_helpers.clj` for a
working example (`build-chain-rules`, `build-chain-session`).

When a single static query is needed alongside dynamically generated rules,
use `defquery` at compile time and reference it by var via `(var my-query)`
in the `mk-session` sources — both production maps and var references work
interchangeably.

# Documentation

## Annotations Guide

When working on annotation-related code — layer merging, type comparison,
delta computation, enrichment, sidecar EDN format, or provenance — consult
the definitive reference:

**`server/docs/rule-annotations.md`**

It covers the full annotation lifecycle: structure, sources (props / generated /
curated / memory), the layered merge model with provenance, type representation
(EDN, in-memory, comparison), and the derivation pass that promotes callsite
types into insert/retract-types.

Key namespaces:

| Namespace | Role |
|-----------|------|
| `clara.server.tools.graph.annotations` | Rule-name normalization, per-production lookup, delta computation |
| `clara.server.tools.graph.annotations.merge` | Layers, merging, provenance, derivation, coercion |
| `clara.server.tools.graph.annotations.callsite` | Callsite format, identity, id assignment |
| `clara.server.tools.graph.serialize` | Type serialization for JSON output (`resolve-type`, `serialize-type-ref`) |
| `clara.server.tools.graph.analyze` | Static analysis, session enrichment, `->memory-layer` |

## Docstrings

Prefer `s/defschema` over large annotated docstrings for describing the shape
of map arguments and return values.  A schema is compile-time verifiable,
self-documenting, and stays in sync with code changes.  Use docstrings for
*why*, not *what* — keep them concise (1-3 lines).  Reserve long-form
commentary for architecture docs in `docs/`.

**Never enumerate a data structure's keys in a docstring** (state map, options
map, request/response body, etc.).  When a schema exists, point to it as the
single source of truth (e.g. "an atom of
`clara.server.graph.server/ServerState`") instead of repeating its shape inline
— inline copies drift out of sync the moment the schema changes.  If no schema
exists and the shape is genuinely part of the contract, define one rather than
documenting the shape prose-style.

**Do not document a callee's behavior or mechanism in the caller's docstring.**
When `f` calls `g`, the docstring on `f` must not explain what `g` does or how
(e.g. "`g` omits nil keys via `remove-nil-vals`").  That belongs to `g`'s
docstring — if it needs one at all — and repeating it drifts out of sync the
moment `g` changes.  Nil-vs-missing-key is not worth calling out in a contract
between functions; schema optional keys already express it where it matters.

**When a docstring refers to a schema, wrap the reference in backticks with a
resolvable var name.**  Use the namespace alias where the current namespace
requires one (e.g. `` `ann.merge/Layer` ``); otherwise use the fully-qualified
name (e.g. `` `clara.server.graph.api/TypeReference` ``).  Inside the schema's
defining namespace, the unqualified backticked name resolves (e.g.
`` `TypeReference` `` in `clara.server.graph.api`).  Never write a bare prose
name ("TypeReference", "Layer") or an unresolvable shorthand (e.g.
`annotations/merge-layers` when only `ann.merge` is required).  This keeps
schema references navigable in every consumer, not just the UI.
