# Fix WM Annotation Enrichment Flow — Progress Tracking

Plan: `docs/planning/fix-wm-anno-enrichment-flow-plan-final.md`
Started: 2026-08-08

## Phase 1: Consolidate Server State ✅ COMPLETE

### 1. `server/src/clara/server/tools/graph/annotations.clj` ✅
- [x] Added `fq-name->namespace` — extracts namespace from FQ rule name string
- [x] Added `canonical-type-str` — 2-arity with optional `prod-ns` for symbol resolution
- [x] Updated `new-types` to accept `rule-ns` and use `canonical-type-str`
- [x] Updated `dimension-delta` to thread `rule-ns`
- [x] Updated `rule-delta` to thread `rule-ns`
- [x] Updated `annotations-delta` to derive `rule-ns` via `fq-name->namespace`

### 2. `server/src/clara/server/tools/graph/analyze.clj` ✅
- [x] Removed local `fq-name->namespace` (now in `annotations.clj`)
- [x] Removed `annot-type->str` helper
- [x] `add-auto-detected-annotations`: comparison via `ann/canonical-type-str`, display via `serialize/resolve-type`
- [x] `enrich-annotations-from-session`: comparison via `ann/canonical-type-str`, display via `serialize/resolve-type`

### 3. `server/src/clara/server/graph/cache.clj` ✅
- [x] `get-state` / `analysis` / `snapshot` / `warm!` take `(cache session annotations)` values, not atoms
- [x] Updated ns docstring

### 4. `server/src/clara/server/graph/server.clj` ✅
- [x] Removed 6 defonce atoms: `server-instance`, `session-atom`, `annotations-atom`, `config-atom`, `cache-atom`, `analyze-cache-atom`
- [x] Added `default-system` defonce atom
- [x] Added `require-system` guard
- [x] Added pure transitions: `transition-start`, `transition-swap`, `transition-reload`
- [x] `transition-start` creates temp atom for kondo cache, commits `@tmp` into state
- [x] `transition-swap` reseeds analyze-cache on session identity change
- [x] `transition-reload` re-derives from stored `:annotations-spec`
- [x] Added `start-system!` — pure constructor (Jetty start, cache warm)
- [x] Updated `start!` — default-system management, same-port restart stops before bind
- [x] Updated `stop!` — 0-arity + 1-arity, resets default when same system
- [x] Updated `swap-session!` — 1-arity (default) + 2-arity
- [x] Added `reload-annotations!` — 0-arity + 1-arity
- [x] Removed `wrap-reload` and `reload-annotations!` (old defn)
- [x] `build-annotations` accepts analyze-cache tmp atom parameter

### 5. `server/src/clara/server/graph/api.clj` ✅
- [x] `app` receives single `state-atom` instead of `(session-atom annotations-atom)`
- [x] Every handler derefs `state-atom` once per request
- [x] Handlers pass `(session, annotations)` values to `cache/analysis`, `cache/snapshot`
- [x] `with-snapshot` derefs state-atom internally
- [x] Removed `POST /v1/annotations/reload` route
- [x] Removed `wrap-reload` wiring from `app`

### 6. `server/src/clara/server/graph/main.clj` ✅
- [x] Added `--annotations EDN` CLI option
- [x] Added `read-edn-single` — reads exactly one EDN form, trailing garbage errors
- [x] Added `parse-annotations-arg` — handles inline EDN, bare strings, file paths
- [x] Added `clojure.edn` require
- [x] Updated `run-explorer-server`:
  - `-l` and `--annotations` compose: `-l` supplies `:source` when spec lacks one
  - Spec `:source` wins over `-l` with warning
  - Bare `-l` (no `--annotations`) → `{:source (vec layers)}`
  - Passes `:annotations` to `start!` instead of `:layers`

### 7. `server/dev/clara/server/graph/demo_run.clj` ✅
- [x] Added `flag-present?` helper (handles `--flag` and `--flag=value` forms)
- [x] Injects `--annotations '{:enrichment :auto-detect}'` default
- [x] Injects `-p 9001` default
- [x] Both respects user-specified flags

### 8. `server/Makefile` ✅
- [x] Updated `demo-run` target with explicit `--annotations '{:enrichment :auto-detect}'` flag

### 9. `server/AGENTS.md` ✅
- [x] Added "Server State Architecture" section with atom discipline rules

### 10. `docs/explorer-graph-api.md` ✅
- [x] Removed `POST /v1/annotations/reload` section

### 11. Tests ✅
- [x] `server_test.clj` — Updated `start-server!` to pass `:annotations` instead of `:layers`; re-added `io` require; fixture captures system for stop; fixed file path tests
- [x] `integration_test.clj` — Updated `start-server!` signature; replaced `post-annotations-reload` with in-memory `reload-annotations!`; always includes `{:source (vec layers)}` so props layer is folded in even for empty layers
- [x] `api_test.clj` — Updated `->handler` to produce single state atom; updated `test-v1-fact-type-id-lookups`; updated `test-session-snapshot-known-tracks-session-swap` to swap single atom
- [x] `session_api_test.clj` — Updated all `api/app` calls to single atom form

### Verification ✅
```
191 tests, 1344 assertions: 0 failures, 0 errors
Lint: 0 errors, 0 warnings
Reflection check: passed (no warnings)
Format: all files correct
```

---

## Review 1 Fixes ✅ COMPLETE

Addresses: `docs/planning/fix-wm-anno-enrichment-flow-impl-review-1.md`

### Bugs fixed
- [x] **B1**: `transition-reload` now passes `(:annotations state)` as `current-annotations` (not `nil`), so `:reuse` reload semantics work correctly
- [x] **B2**: `reload-annotations!` docstring no longer references `->memory-layer` (Phase 2 work)

### Plan deviations resolved
- [x] **D3**: Unknown `:enrichment` modes now throw `IllegalArgumentException` with actionable message; explicit enumeration in `case`
- [x] **D4**: Spec-shaped maps validated via `s/validate AnnotationsSpec` inside `build-annotations` (the choke point)
- [x] **D5**: WM-unavailable warning covers both `:auto-detect-from-memory` and `:auto-detect`
- [x] **D6**: Added `StartOpts` schema + `s/validate` in `start-system!`
- [x] **D8**: Added 3-arity `build-annotations` (defaults `analyze-cache-atom` to fresh atom)

### Minor items
- [x] **M1**: Cache warmed before Jetty bind (defensive ordering)
- [x] **M2**: `transition-swap` docstring no longer claims "both absent is a no-op" (it clears annotations)
- [x] **M3**: Fixture captures system in `let` binding, passes to `(server/stop! system)`
- [x] **M4**: `server_test.clj` helper aligned with `integration_test.clj` — uses `{:source (vec layers)}` for empty layers too
- [x] **M5**: AGENTS.md now includes "use `swap!` never `(reset! a (f @a))`" rule
- [x] **M6**: API docs note HTTP is read-only, mutation via in-memory API
- [x] **M7**: Per-layer info print restored in `run-explorer-server`

### Internal naming
- [x] Transition params renamed: `annotations` → `annotations-spec` in `transition-start` and `transition-swap` destructuring (resolves clash with `(:annotations state)`). Both `:annotations` and `:annotations-spec` accepted in the opts map. Public API keys (`SwapSessionOpts` `:annotations`, `StartOpts` `:annotations`) unchanged.

### Deferred to Phase 2
- **D1**: Memory enrichment as delta layer (plan §1b)
- **D2**: Per-file source layers (plan §1a)
- **D7**: Resolver unification (plan §4)
- **M8**: Pre-existing test-run WARN (nil type token in serialize, not from this change)

---

## Phase 2: WM Enrichment at Startup 🔲 NOT STARTED

Phase 2 makes `start!` carry WM enrichment through to the analysis. The state consolidation in Phase 1 means this is now a straightforward change to the enrichment flow.

### Remaining Phase 2 tasks (from plan):

1. **`server_test.clj`** — New test cases:
   - [ ] `test-build-annotations-auto-detect-with-memory` — direct transition test
   - [ ] `test-start-auto-detect-enrichment` — end-to-end with `start-system!`
   - [ ] `test-reload-after-session-only-swap`
   - [ ] `test-reload-after-swap-with-spec`
   - [ ] `test-build-annotations-unknown-enrichment`
   - [ ] `test-canonical-type-str-resolution`

2. **Demo re-scrape + verification:**
   - [ ] `cd server && make demo-setup && make demo-run`
   - [ ] `cd ui && pnpm run scrape:demo`
   - [ ] Verify static fact types include WM-derived types (`AuditTrail`, `ComplianceReview`, `compliance-review-result`)
   - [ ] Verify `AuditTrail` detail has ancestors + `inserted-by-rules`
   - [ ] Verify backward compat (`-l` without `--annotations`)
   - [ ] Verify reload/swap semantics
   - [ ] Verify demo-data git diff shows additions, not deletions

### Notes
- The `start-system!` + 2-arity mutation API is ready for test isolation in Phase 2
- Phase 2 enrichment tests can use `start-system!` without affecting the default system
- The `--annotations` CLI flag is already wired; demo-run injects `{:enrichment :auto-detect}` by default
