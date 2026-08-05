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

The app will be available at `http://localhost:5173`.

### Quality & Testing

We enforce strict linting and type-checking.

```bash
# Run all checks (Lint, Format, Types)
pnpm check

# Fix linting/formatting issues
pnpm lint
pnpm format

# Unit & Component Tests (Vitest)
pnpm test:unit

# End-to-End Tests (Playwright)
pnpm test:e2e
```

### End-to-End Tests

E2E tests run against **live explorer backends** (not the static demo data):
Playwright's `webServer` config starts each backend and a SvelteKit dev
server whose `/v1` proxy targets it (`API_PROXY_TARGET`, see
`vite.config.ts`).  There are two Playwright projects:

| Project   | Backend session                        | Backend port | Frontend port | Tests                                   |
|-----------|----------------------------------------|--------------|---------------|-----------------------------------------|
| `loan-app`  | loan-doc-rules + loan-app-rules        | `9101`       | `4173`        | `tests/*.e2e.ts` (except `Hierarchy*.e2e.ts`) |
| `hierarchy` | loan-hierarchy-rules (keyword hierarchy, tuple fact types) | `9201` | `4174` | `tests/Hierarchy*.e2e.ts`               |

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

Detailed engineering standards, including Svelte 5 patterns and Bootstrap SSR handling, are documented in [GEMINI.md](./GEMINI.md).
