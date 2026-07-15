# Clara Rules Explorer - Server

A standalone HTTP server that exposes the [Clara Rules Explorer Graph API](../docs/explorer-graph-api.md). This project provides a REST API to inspect the structure and runtime state of a Clara rules session, wrapping core graph analysis modules in a Ring/Jetty web server.

## Overview

The graph analysis engine lives in `clara.server.tools.graph.*` and is pure data analysis over a Clara `session` or `rulebase`. This project:

- Provides a self-contained artifact for rule inspection.
- Serves as the backend for the [Clara Rules Explorer UI](../ui/).
- Supports static rulebase analysis and live session state inspection.

## Why a standalone repository?

The explorer tools were originally part of the main `clara-rules` repository. They were extracted into this standalone repository to:

- **Decouple Lifecycle**: Allow the explorer API and UI to evolve independently of the core rules engine.
- **Minimize Dependencies**: Keep the core `clara-rules` library free of web-related dependencies like Ring, Reitit, and Jetty.
- **Simplify Distribution**: Provide a focused, ready-to-run tool for any Clara project without requiring it to be a subproject of the main library.

## Quick Start

### Programmatic API

```clojure
(require '[clara.rules :as r]
         '[clara.server.graph.server :as server])

;; Create a session
(def session (-> (r/mk-session 'my.rules.ns)
                 (r/insert (->MyFact {:value 42}))
                 (r/fire-rules)))

;; Start the server
(server/start! {:session session
                :port 9999
                :annotations-file "annotations.edn"}) ;; optional

;; ... inspect at http://localhost:9999/v1/rulebase-summary

;; Stop when done
(server/stop!)
```

### CLI Entry Point

The server provides a `-main` entry point:

```bash
# Run the explorer server
clojure -M -m clara.server.graph.main -s path/to/session.bin [-a path/to/annotations.edn] [-p 9999] [--load-session-state-fn my.namespace/my-fn]

# Generate sidecar annotations statically from source paths
clojure -M -m clara.server.graph.main -g path/to/rules.clj,path/to/other_rules.clj
```

| Flag                        | Required | Description                                                                         |
| --------------------------- | -------- | ----------------------------------------------------------------------------------- |
| `-s` / `--session`          | Yes (unless `-g` is given) | Path to a serialized Clara session file (Fressian format).                  |
| `-g` / `--generate-annotations` | Yes (unless `-s` is given) | Generate annotations EDN for Clojure source paths (comma-separated).       |
| `-f` / `--facts`            | No       | Path to the serialized facts file. Defaults to `<session-path>.facts` when omitted. |
| `-a` / `--annotations`      | No       | Path to an EDN sidecar file with rule metadata annotations.                         |
| `-p` / `--port`             | No       | Server port (default: `9999`).                                                      |
| `--load-session-state-fn`   | No       | Fully qualified symbol naming a function to deserialize the session state.          |

By default, the server uses Fressian deserialization to load the session state from disk. If you want to use your own deserializer (e.g. Nippy, transit, or a custom Fressian setup), you can pass a fully qualified symbol to `--load-session-state-fn`. This function must accept two arguments (`session-path` and `facts-path`) and return the deserialized Clara session.

Example:
```bash
clojure -M -m clara.server.graph.main -s path/to/session.bin --load-session-state-fn my.namespace/load-session
```

When `--session` is provided, the server uses `clara.rules.durability` to deserialize the session from disk. The session is expected to have been serialized with `{:with-rulebase? true}` so that the compiled rulebase is embedded — this is required for static rulebase analysis.

The `--annotations` file follows the [sidecar EDN format](./docs/rule-annotations.md), keyed by rule FQ-name, to declare insert/retract types and notes for dependency graph construction.

## API Endpoints

All endpoints are served under `/v1/`. The full endpoint reference — including
request/response shapes, field descriptions, and JSON schemas — is documented in
[`docs/explorer-graph-api.md`](../docs/explorer-graph-api.md).

## Demo Workflow

A quick end-to-end demo using the loan application rules:

```bash
# 1. Serialize the demo session
clojure -M:demo-setup

# 2. Start the explorer server (annotations auto-loaded by demo-run)
clojure -M:demo-run -s demo-data/session.bin
```

Once the server is running, explore the API with `curl` — see the
[Graph API reference](../docs/explorer-graph-api.md) for example requests and
response shapes.

## Running Tests

Run the full project suite via CLI:

```bash
clojure -M:test:run-tests
# Or using the Makefile:
make test
```

For iterative development, you can run targeted tests or use an nREPL-based workflow as described in [AGENTS.md](AGENTS.md).

## Project Structure

```
server/
├── deps.edn                         # Dependencies (Ring, Reitit, Jetty, JSON)
├── dev/clara/server/graph/
│   ├── demo_setup.clj               # Demo session serialization
│   └── demo_run.clj                 # Demo server entry point
├── src/clara/server/
│   ├── graph/                       # API and Server logic
│   └── tools/graph/                 # Core analysis engine
├── test/clara/server/
│   ├── graph/                       # API and Integration tests
│   └── tools/graph/                 # Analysis engine tests
└── test-resources/                  # Test data (annotations, etc.)
```

### Demo & Test Rules

The rule definitions used for demos and tests (e.g., `loan_app_rules.clj`, `loan_doc_rules.clj`) are located in `test/clara/server/tools/graph/rules/`. These provide a self-contained environment for exploring the API's capabilities.

## Key Dependencies

| Dependency                              | Purpose                         |
| --------------------------------------- | ------------------------------- |
| `ring/ring-jetty-adapter`               | HTTP server                     |
| `metosin/reitit`                        | Routing                         |
| `metosin/muuntaja` + `metosin/jsonista` | JSON request/response coercion  |
| `prismatic/schema`                      | Response body schema validation |
| `com.github.gateless/clara-rules`       | Core rules engine               |
| `org.clojure/tools.cli`                 | CLI flag parsing                |

In local development, `clara-rules` can be resolved via `:local/root` by setting the `CLARA_HOME` environment variable.
