# GitHub Actions Setup Plan

This document outlines the plan for setting up GitHub Actions (GHA) for the `clara-rules-explorer` repository to ensure quality and automate testing before making the repository public.

## 1. Overview

We have implemented two primary workflows:
- **Server CI**: Handles Clojure testing and reflection checks.
- **UI CI**: Handles Node.js linting, type-checking, unit tests, and E2E tests (which require a running backend).

## 2. Server CI Workflow (`.github/workflows/server.yml`)

### Triggers
- Push/Pull Request targeting `main` branch.
- Specifically when changes occur in `server/**` or `.github/workflows/server.yml`.

### Jobs
- **Test**:
  - Sets up Java 21 and Clojure.
  - Caches Maven/Clojure dependencies.
  - Runs all Clojure tests using the `:run-tests` alias (Cognitect Labs test-runner).
- **Reflection Check**:
  - Checks for reflection warnings in core namespaces as a smoke test.

## 3. UI CI Workflow (`.github/workflows/ui.yml`)

### Triggers
- Push/Pull Request targeting `main` branch.
- Specifically when changes occur in `ui/**`, `server/**` (for E2E), or `.github/workflows/ui.yml`.

### Jobs
- **Lint & Type Check**:
  - Sets up Node.js (v24).
  - Sets up `pnpm`.
  - Runs `pnpm lint`, `pnpm check`, and `pnpm test:unit`.
- **E2E Tests**:
  - Requires both UI and Server.
  - **Steps**:
    1. Sets up Java/Clojure and Node/pnpm.
    2. Installs Playwright browsers.
    3. Builds the UI: `pnpm build`.
    4. Generates demo session in server: `clojure -M:demo-setup`.
    5. Starts server in background: `clojure -M:demo-run -s demo-data/session.bin` (defaults to port 9001).
    6. Waits for backend to be ready via `curl`.
    7. Runs Playwright tests: `pnpm test:e2e`.

## 4. Key Improvements Implemented

### Port Consistency
The server's `demo-run` main entry point now defaults to port `9001` (if not explicitly overridden), matching the `ui/vite.config.ts` proxy configuration. This ensures E2E tests work out of the box in both local and CI environments.

### Clojure Test Runner
Added the `:run-tests` alias to `server/deps.edn` using `cognitect-labs/test-runner` to allow running the full suite with a single command: `clojure -X:run-tests`.

## 5. Implementation Roadmap (Completed)

1. [x] Update `server/deps.edn` with `run-tests` alias.
2. [x] Synchronize port defaults between backend `demo-run` and UI proxy (9001).
3. [x] Create `.github/workflows/server.yml`.
4. [x] Create `.github/workflows/ui.yml`.
5. [x] Document finalized setup.
