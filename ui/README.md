# Clara Rules Explorer UI

A high-performance, modern web interface for exploring and interacting with the Clara Rules Rete network. Built with SvelteKit 2 and Svelte 5.

## Overview

This UI serves as the frontend for the Clara Rules explorer API. It provides:
- **Rete Network Inspection:** Deep-dive into Alpha and Beta nodes for rules and queries.
- **Dependency Graphs:** Visualizing the flow of facts and rule triggers.
- **Live Session Analysis:** Interacting with a running rules session on the server.

## Tech Stack

- **Framework:** SvelteKit (Svelte 5 Runes)
- **Language:** TypeScript (Strict)
- **Styling:** Bootstrap 5 (Sass) + Bootstrap Icons
- **Runtime:** Node 24+
- **Package Manager:** `pnpm` (managed via `corepack` and `direnv`)

## Getting Started

### Prerequisites

Ensure you have `direnv` and `corepack` enabled. When you enter the `ui/` directory, `direnv` will automatically configure your path to use the local Node 24 and pnpm settings.

```bash
cd ui
direnv allow
```

### Development

Start the development server with Hot Module Replacement (HMR):

```bash
pnpm dev
```

The app will be available at `http://localhost:5173`.  The dev server proxies
`/v1` to an explorer backend (default `http://localhost:9001`, override with
`API_PROXY_TARGET`), so you need one running — start it from the server
project with `make demo-run`.

### Quality & Testing

We enforce strict linting and type-checking.  All quality and test commands
are exposed through the project Makefile (mirroring `server/Makefile`), which
handles cross-project prerequisites automatically:

```bash
make check        # Type-check (svelte-check)
make lint         # Prettier check + ESLint
make format       # Auto-fix formatting (Prettier)
make format-check # Prettier check only
make test         # Unit tests + e2e tests (with prerequisites)
make test-unit    # Vitest unit/component tests
make test-e2e     # Playwright e2e tests (with prerequisites)
```

The underlying pnpm scripts run the same commands directly:

```bash
pnpm check
pnpm lint
pnpm format
pnpm run test:unit
pnpm run test:e2e
```

### End-to-End Tests

E2E tests run against **live explorer backends** (not the static demo data):
Playwright's `webServer` config starts each backend and a SvelteKit dev
server whose `/v1` proxy targets it (`API_PROXY_TARGET`, see
`vite.config.ts`).  There are two Playwright projects.

Two things must be in place before a bare `pnpm test:e2e` will pass —
`make test-e2e` (or `make test`) handles both automatically:

1. **Serialized demo session.**  The `loan-app` backend serves
   `server/demo-data/session.bin` (+ `.facts`), a serialized
   loan-doc-rules + loan-app-rules session.  That directory is gitignored, so
   a fresh clone has no session file and the backend exits immediately with
   `Error: session file not found` — the reason bare `pnpm test:e2e` fails.
   Generate it with the server's `make demo-setup` (or `make demo-setup` in
   `ui/`, which delegates):
   ```bash
   make -C ../server demo-setup
   ```
2. **Installed dependencies + Playwright browsers.**  `pnpm install` (the
   Makefile's `node_modules` target) and, once per machine,
   `pnpm exec playwright install chromium`.

| Project   | Backend session                        | Backend port | Frontend port | Tests                                   |
|-----------|----------------------------------------|--------------|---------------|-----------------------------------------|
| `loan-app`  | loan-doc-rules + loan-app-rules        | `9101`       | `4173`        | `tests/loan-app/*.e2e.ts`                   |
| `hierarchy` | loan-hierarchy-rules (keyword hierarchy, tuple fact types) | `9201` | `4174` | `tests/hierarchy/*.e2e.ts`     |

Backends are started by `ui/bin/ci/start-loan-app-backend.sh` (port `9101`)
and `ui/bin/ci/start-hierarchy-backend.sh` (port `9201`); `reuseExistingServer:
true` reuses an already-running backend (e.g. a local `make hierarchy-run`).
Ports `9101`/`9201` deliberately avoid the `9001` default used by local
REPL/integration-test helpers.

The static demo build (`pnpm build:demo`, hosted on GitHub Pages) is a
separate concern: it serves scraped `static/demo-data` from the loan-app-rules
session only, and is not used by the e2e suite.  See
[`docs/static-demo-setup.md`](../docs/static-demo-setup.md).


## Building for Production

To create an optimized production build:

```bash
pnpm build
```

You can preview the production build locally:

```bash
pnpm preview
```

## Engineering Standards

Detailed engineering standards — Svelte 5 runes patterns, styling
conventions, TypeScript usage, and verification workflows — are documented in
[`.agents/skills/svelte-engineering/SKILL.md`](../.agents/skills/svelte-engineering/SKILL.md)
(loaded as an agent skill per `AGENTS.md`).  The UI architecture — shell,
routing, component organization, state management, and API wiring — is
covered in [`docs/app-arch.md`](./docs/app-arch.md).
