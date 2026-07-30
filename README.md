# Clara Rules Explorer

A tool for analyzing and navigating [Clara Rules](https://github.com/gateless/clara-rules)
rules, queries, and facts.

👉 **[Try the Live Interactive Demo](https://www.metasimple.org/clara-rules-explorer/)**

> [!WARNING]
> **Experimental Project:** This repository is currently in an exploratory,
> experimental stage. There are no deployed release versions, and it should not
> be considered production-ready. It is active research and development that
> may be developed into a releasable artifact in the future.

## Overview

Clara Rules Explorer provides visibility into both the static rulebase and
runtime session working memory of a Clara rules system. It consists of two
independent sub-projects:

- A Clojure **server** that analyzes Clara sessions and exposes a
  [Graph API](./docs/explorer-graph-api.md) over HTTP.
- A SvelteKit **UI** that provides an interactive browser-based explorer
  consuming that API.

### Key Features

- **Dependency Analysis:** Map the data flow for rule/query (production) chains
  through the Rete network.
- **Session State Visualization:** Inspect working memory, rule activation
  history, and fact instances at a point in time.
- **Fact Type Tracking:** Group and analyze facts by their origin
  (inserted-from) and usage (used-by).

## Project Layout

| Directory | Description |
|-----------|-------------|
| [`server/`](./server/) | Clojure HTTP server — graph analysis engine and REST API. See [server/README.md](./server/README.md). |
| [`ui/`](./ui/) | SvelteKit 2 + Svelte 5 web interface. See [ui/README.md](./ui/README.md). |
| [`docs/`](./docs/) | Cross-cutting documentation — API reference, demo setup, CI. |

## Getting Started

See the sub-project READMEs for detailed prerequisites and instructions:

- **[Server Quick Start](./server/README.md#quick-start)** — generate a demo
  session and start the API server.
- **[UI Quick Start](./ui/README.md#getting-started)** — install dependencies
  and launch the dev server.

## Documentation

- [Explorer Graph API](./docs/explorer-graph-api.md) — HTTP API endpoints,
  request/response shapes, and JSON schemas.
- [Static Demo Setup](./docs/static-demo-setup.md) — build and host a fully
  static demo on GitHub Pages.
- [GitHub Actions Setup](./docs/gha-setup.md) — CI workflow configuration.
- [UI Architecture](./ui/docs/app-arch.md) — UI component architecture and
  design decisions.
- Server internals: see [`server/docs/`](./server/docs/) for rule annotations,
  internal models, and analysis notes.

## License

Copyright (c) 2026 Michael Rodriguez / org.metasimple

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
