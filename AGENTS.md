# Agent Instructions

This repo contains two independent projects in separate directories with
different languages, toolchains, and engineering standards. Keep them
organized separately — do not mix tooling or conventions between them.

## Project Layout

| Directory | Language | Toolchain | Test runner |
|-----------|----------|-----------|-------------|
| `server/` | Clojure (tools.deps) | `make` | `make test` |
| `ui/`     | TypeScript + Svelte 5 | `pnpm`  | `pnpm run test` |

---

## Server (`server/`)

The `server/Makefile` is the authoritative source for all quality commands.
**Always use the Makefile targets** rather than composing `clojure -M` alias
combinations yourself:

```bash
cd server
make test             # run the full test suite
make format           # auto-format all source files (cljfmt)
make format-check     # verify formatting is correct (for CI)
make lint             # clj-kondo static analysis across src, test, dev
make reflection-check # *warn-on-reflection* true on all sources
make clean            # remove target and .cpcache
```

- The `:run-tests` alias does **not** include test dependencies on its own.
  The Makefile correctly combines aliases: `clojure -M:test:run-tests`.
- Clojure engineering standards are documented in the
  [clojure-engineering skill](.agents/skills/clojure-engineering/SKILL.md).
- The server API is defined in `server/src/clara/server/graph/api.clj`.
- Demo rules live under `server/test/clara/server/tools/graph/rules/`.

---

## UI (`ui/`)

A SvelteKit 2 + Svelte 5 application. Use `pnpm` for all package operations:

```bash
cd ui
pnpm run format && pnpm run check && pnpm run lint   # format + type-check + lint
pnpm run test                                         # unit + e2e tests
pnpm run test:e2e                                     # Playwright e2e only
pnpm run dev                                          # dev server (requires backend on :9999)
```

- Svelte engineering standards are documented in the
  [svelte-engineering skill](.agents/skills/svelte-engineering/SKILL.md).
- UI architecture is documented in `ui/docs/app-arch.md`.
- API types live in `ui/src/lib/types/api.ts`.

---

## Cross-Project Work

When making changes that span both projects:

1. Make and verify the server change first (`make test lint reflection-check`).
2. Then update the UI to match, verifying with `pnpm run format && pnpm run check && pnpm run lint`.
3. If the API contract changes, update `docs/explorer-graph-api.md`.
