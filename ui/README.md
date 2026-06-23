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
