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
clojure -M -m clara.server.graph.main -s path/to/session.bin [-a path/to/annotations.edn] [-p 9999]
```

| Flag                   | Required | Description                                                                         |
| ---------------------- | -------- | ----------------------------------------------------------------------------------- |
| `-s` / `--session`     | **Yes**  | Path to a serialized Clara session file (Fressian format).                          |
| `-f` / `--facts`       | No       | Path to the serialized facts file. Defaults to `<session-path>.facts` when omitted. |
| `-a` / `--annotations` | No       | Path to an EDN sidecar file with rule metadata annotations.                         |
| `-p` / `--port`        | No       | Server port (default: `9999`).                                                      |

When `--session` is provided, the server uses `clara.rules.durability` to deserialize the session from disk. The session is expected to have been serialized with `{:with-rulebase? true}` so that the compiled rulebase is embedded — this is required for static rulebase analysis.

The `--annotations` file follows the [sidecar EDN format](../docs/explorer-graph-api.md#path-b--sidecar-edn-file), keyed by rule FQ-name, to declare insert/retract types and notes for dependency graph construction.

## API Endpoints

All endpoints are served under `/v1/`. See [explorer-graph-api.md](../docs/explorer-graph-api.md) for details.

### Rulebase Analysis (static)

| Method | Path                      | Description                          |
| ------ | ------------------------- | ------------------------------------ |
| `GET`  | `/v1/rulebase-summary`    | High-level dashboard counts          |
| `GET`  | `/v1/analysis`            | Full static analysis of the rulebase |
| `GET`  | `/v1/rules`               | List all rules                       |
| `GET`  | `/v1/rules/:fq-name`      | Single rule detail                   |
| `GET`  | `/v1/queries`             | List all queries                     |
| `GET`  | `/v1/queries/:fq-name`    | Single query detail                  |
| `GET`  | `/v1/fact-types`          | List all fact types                  |
| `GET`  | `/v1/fact-types/:fq-name` | Single fact type detail              |

### Session State (requires working memory)

| Method | Path                              | Description                                      |
| ------ | --------------------------------- | ------------------------------------------------ |
| `GET`  | `/v1/session/fact-types`          | Fact types present in working memory with counts |
| `GET`  | `/v1/session/fact-types/:fq-name` | Detailed fact type view with role groupings      |
| `GET`  | `/v1/session/facts/:id`           | Single fact instance                             |
| `GET`  | `/v1/session/rules/:fq-name`      | Rule activity (matches + insertions)             |
| `GET`  | `/v1/session/queries/:fq-name`    | Query activity (matches)                         |

### Annotations

| Method | Path                     | Description                   |
| ------ | ------------------------ | ----------------------------- |
| `GET`  | `/v1/annotations`        | Current annotation map        |
| `POST` | `/v1/annotations/reload` | Reload sidecar file from disk |

### Other

| Method | Path                   | Description                                         |
| ------ | ---------------------- | --------------------------------------------------- |
| `GET`  | `/v1/session-snapshot` | Full point-in-time working memory snapshot          |

## Demo Workflow

A quick end-to-end demo using the loan application rules. The `demo-setup` alias
serializes a pre-built session with working memory, and `main` serves it via the
explorer API.

```bash
# 1. Serialize the demo session
clojure -M:demo-setup

# 2. Start the explorer server (annotations auto-loaded by demo-run)
clojure -M:demo-run -s demo-data/session.bin
```
# 3. Explore the API

## Dashboard
curl -s http://localhost:9999/v1/rulebase-summary | jq

## Rulebase Analysis (static — no session required)

### All rules and rule detail
curl -s http://localhost:9999/v1/rules | jq '[.rules[] | {name, ns, "lhs-types", "insert-types"}]'
curl -s http://localhost:9999/v1/rules/clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved \
  | jq '{name, lhs, "rhs-form", "insert-types", upstream, downstream}'

### All queries and query detail
curl -s http://localhost:9999/v1/queries | jq
curl -s http://localhost:9999/v1/queries/clara.server.tools.graph.rules.loan-app-rules.find-app-outcome \
  | jq '{name, params, lhs, upstream}'

### Fact types and fact type detail
curl -s http://localhost:9999/v1/fact-types | jq '[.["fact-types"][] | {name, "used-by-rules", "inserted-by-rules"}]'
curl -s http://localhost:9999/v1/fact-types/clara.server.tools.graph.rules.loan_app_facts.Application | jq

## Session State (requires working memory)

### Fact types present in working memory with counts
curl -s http://localhost:9999/v1/session/fact-types | jq

### Single fact type drilldown — role groupings (inserted-from / used-by)
curl -s http://localhost:9999/v1/session/fact-types/clara.server.tools.graph.rules.loan_app_facts.DocumentCheck | jq

### Full working memory snapshot
curl -s http://localhost:9999/v1/session-snapshot | jq '{"total-facts": (.["facts"] | length), "fact-types": (.["fact-types"] | keys)}'

### Rule activity — all matches and insertions for a rule
curl -s http://localhost:9999/v1/session/rules/clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved \
  | jq '{"match-count": (.matches | length), "inserted-count": (.["inserted-facts"] | length)}'
```

## Running Tests

Run the full project suite via CLI:

```bash
clojure -M:test
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
