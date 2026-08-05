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

### Annotation Key Normalization

Annotation maps (loaded from EDN sidecar files, generated from kondo analysis,
or enriched from session data) use **string keys** for rule names — never symbols.
The normalization layer lives in `server/src/clara/server/tools/graph/annotations.clj`:

| Function | Purpose |
|---|---|
| `normalize-rule-name` | Normalize a single key to its canonical string form |
| `normalize-annotations` | Normalize all top-level keys to strings; returns a `sorted-map` |
| `get-annotation` | Canonical accessor — normalizes the lookup key, then `get`s |

**Rules:**
1. Every boundary that reads, writes, or receives annotations from outside must
   normalize: `load-sidecar`, `write-annotations!`, `generate-annotations-from-analysis`,
   `add-auto-detected-annotations`, `enrich-annotations-from-session`, `merge-annotations`.
2. Use `get-annotation` (never raw `get`) when the lookup key may be a symbol
   (e.g., from kondo analysis, backtick-quoted vars in tests, or EDN input).
3. The internal kondo analysis pipeline (`build-graph`, `transitive-reachability`,
   `productions-by-name`) uses symbols — conversion happens at the boundaries only.

---

## UI (`ui/`)

A SvelteKit 2 + Svelte 5 application. The `ui/Makefile` is the authoritative source for all quality commands. Use `make` for tests and checks, and `pnpm` for other package operations:

```bash
cd ui
make format check lint  # format + type-check + lint
make test               # unit + e2e tests
make test-e2e           # Playwright e2e only
pnpm run dev            # dev server (requires backend on :9999)
```

- Svelte engineering standards are documented in the
  [svelte-engineering skill](.agents/skills/svelte-engineering/SKILL.md).
- UI architecture is documented in `ui/docs/app-arch.md`.
- API types live in `ui/src/lib/types/api.ts`.

---

## Finding clara-rules Source Code

This project builds on the clara-rules engine. When you need to read, reference,
or modify clara-rules source code, resolve it in this order:

1. **`CLARA_HOME` env var (preferred)** — if set, it points to a local checkout
   of the clara-rules repo and is the authoritative source to use:
   ```bash
   echo "$CLARA_HOME"   # e.g. /Users/mrrodriguez/Projects/gateless/clara-rules
   ```
2. **Maven cache fallback** — if `CLARA_HOME` is unset, look in
   `~/.m2/repository` for clara-rules artifacts
   (`~/.m2/repository/com/cerner/clara-rules*`). Note: this yields jars, not
   source — check for `-sources.jar` files if you need actual source.
3. **Ask the user** — if neither is available, ask the user where their
   clara-rules checkout lives before guessing.

---

## Cross-Project Work

When making changes that span both projects:

1. Make and verify the server change first (`make test lint reflection-check`).
2. Then update the UI to match, verifying with `make format check lint`.
3. If the API contract changes, update `docs/explorer-graph-api.md`.
