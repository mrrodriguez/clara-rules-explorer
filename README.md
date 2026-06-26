# Clara Rules Explorer

A tool for analyzing and navigating [Clara Rules](https://github.com/gateless/clara-rules)
rules, queries, and facts.

👉 **[Try the Live Interactive Demo](https://www.metasimple.org/clara-rules-explorer/)**

> [!WARNING] > **Experimental Project:** This repository is currently in an exploratory,
> experimental stage. There are no deployed release versions, and it should not
> be considered production-ready. It is active research and development that
> may be developed into a releasable artifact in the future.

## Overview

Clara Rules Explorer provides both visibility for both the static rulebase and
runtime session working memory. It consists of a graph analysis HTTP API server
and a modern web interface UI.

### Key Features

- **Dependency Analysis:** Map the dependencies and data flow for rule/query
  (aka. production) chains.
- **Session State Visualization:** Inspect the current state of a session,
  including working memory and rule activation history.
- **Fact Type Tracking:** Group and analyze facts by their origin
  (inserted-from) and usage (used-by).

## Project Structure

The repository is organized as follows:

- [**`server/`**](./server/): A Clojure-based HTTP server that exposes the
  [Graph API](./docs/explorer-graph-api.md). It uses `clara.server.tools.graph.*`
  to perform data analysis over Clara sessions or rulebases.
- [**`ui/`**](./ui/): A high-performance web interface built with SvelteKit 2
  and Svelte 5. It interacts with the Server API to provide a rich, interactive
  experience.
- [**`docs/`**](./docs/): Technical documentation, including API references and
  architecture overviews.

## Getting Started

### Prerequisites

- **Server:** Java 11+ and [Clojure CLI tools](https://clojure.org/guides/install_clojure).
- **UI:** [Node.js](https://nodejs.org/) (v24+) and `pnpm`.
- **Optional (Recommended for development):**
  - [direnv](https://direnv.net/) - Automates environment and path configuration on directory change.
  - [GNU make](https://www.gnu.org/software/make/) (v4+) - Used to run unified build, lint, and test targets. On macOS, this can be installed via Homebrew (`brew install make`).

> [!NOTE]
> The environment configurations in this repo are designed to work automatically if you use `direnv`. However, you can still build and run the project manually without it, provided you have the correct versions of the prerequisites (including GNU `make` v4+ and `pnpm`) installed globally.

### Quick Start

1.  **Start the Server:**
    Navigate to the `server/` directory and run the development server:

    ```bash
    cd server
    clj -M:dev -m clara.server.graph.main
    ```

    _See [server/README.md](./server/README.md) for more details on configuration and API endpoints._

2.  **Start the UI:**
    In a new terminal, navigate to the `ui/` directory:
    ```bash
    cd ui
    pnpm install
    pnpm dev
    ```
    The UI will be available at `http://localhost:5173`.

## Documentation

- [Explorer Graph API](./docs/explorer-graph-api.md)
- [Explorer API Reference](./docs/explorer-api-reference.md)
- [UI Architecture](./ui/docs/app-arch.md)

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
