# Performance — Rulebase Analysis & Lifecycle: Progress Log

Companion to [`perf-rulebase-analysis-plan.md`](./perf-rulebase-analysis-plan.md).

Status: **Fix A complete (plus follow-up refinements). Fix B — NOT started. Fix C — NOT started.**

---

## Decisions (Fix A)

- `update-snapshot-known-set` (not `snapshot-with-known-set`) — the re-stamp
  helper in `memory.clj`.
- `enrich-annotations-from-session*` returns `{:annotations … :snapshot …}`;
  `enrich-annotations-from-session` is a thin `:annotations` wrapper (existing
  tests/callers unchanged).
- `build-state`'s input key is `:memory-snapshot` (not "prebuilt").
- `build-auto-detect-annotations` is the composition root: it calls
  `enrich-annotations-from-session*` directly and returns the tuple;
  `->memory-layer` is refactored to `[base enriched]` (delta→layer only, still
  returns just a Layer).
- `build-annotations` keeps its bare-map contract via a thin wrapper over a new
  `build-annotations*` (same `*`/wrapper pattern), so the five
  `build-annotations` call sites in `server_test.clj` stay untouched.

## Design correction discovered during implementation

The plan assumed `:known` lives only in `[:facts id :type :known]`. In fact the
flag is baked into **every** fact-entry copy, in four places:

1. `:facts` (id → fact entry)
2. `:rule-matches` (`:matches` + `:inserted-facts`)
3. `:query-matches` (`:matches`)
4. `:fact-types` (`:inserted-from[*] :facts` + `:used-by[*] :facts` role grouping)

`update-snapshot-known-set` re-stamps all four, and is verified **byte-equal**
to a freshly-built `session-snapshot-from-analysis`.

## Checklist

- [x] `memory.clj`: add `update-snapshot-known-set` (all four fact-entry locations)
- [x] `analyze.clj`: `enrich-annotations-from-session*` + wrapper; `->memory-layer [base enriched]`
- [x] `server.clj`: `build-auto-detect-annotations` tuple; `build-annotations*` + wrapper; transitions store `:memory-snapshot`; shell passes it to `cache/warm!`
- [x] `cache.clj`: `build-state` accepts `:memory-snapshot`; `get-state`/`warm!` threading
- [x] Tests: existing suite green + new `test-update-snapshot-known-set` + `test-enrich-annotations-from-session*--tuple`
- [x] Verify: `make test`, `make lint`, `make reflection-check`, `cljfmt check`

## Verification

- `make test` → **204 tests, 1426 assertions, 0 failures, 0 errors**.
- `make lint` → 0 errors, 0 warnings; `make reflection-check` → clean;
  `cljfmt check` on all edited files → clean.
- `test-update-snapshot-known-set`: re-stamped snapshot is byte-equal to a
  freshly-built `session-snapshot-from-analysis`.
- `test-enrich-annotations-from-session*--tuple`: `:annotations` equals the thin
  wrapper's result; `:snapshot` equals `memory/session-snapshot`.

## Timing (5 chain + 4000 heavy bulk facts, 4006 facts)

- `session-snapshot` (enrichment phase, 1-arity): **~299 ms**
- `update-snapshot-known-set` (cache reuse): **~2.9 ms**

The second full snapshot is eliminated — the cache build now pays ~3 ms to
re-stamp `:known` instead of ~299 ms to re-inspect + re-sort + re-index.

## Files changed

- `server/src/clara/server/tools/graph/memory.clj` — `update-snapshot-known-set`
- `server/src/clara/server/tools/graph/analyze.clj` — `enrich-annotations-from-session*` + wrapper, `->memory-layer [base enriched]`
- `server/src/clara/server/graph/server.clj` — `build-auto-detect-annotations` tuple, `build-annotations*` + wrapper, `:memory-snapshot` state threading
- `server/src/clara/server/graph/cache.clj` — `build-state` `:memory-snapshot`, `get-state`/`warm!` arity
- `server/test/clara/server/tools/graph/memory_test.clj` — `test-update-snapshot-known-set`
- `server/test/clara/server/tools/graph/analyze_test.clj` — `test-enrich-annotations-from-session*--tuple`

---

## Fix A follow-up refinements (post-implementation)

These landed after Fix A's core, driven by review feedback.  All still under the
Fix A umbrella — **Fix B (clj-kondo cache) has not been started.**

### First-class `ServerState` schema

- `server.clj` ns docstring no longer enumerates the state map by hand; it now
  points to the `ServerState` schema as the single source of truth.
- New schemas: `ServerState` (+ `BareAnnotations`, `MemorySnapshot` helpers).
- Nil-able state keys are **optional keys** (`(s/optional-key …)`), not
  `(s/maybe …)` values: `:annotations-spec` and `:memory-snapshot` are omitted
  from the state map when nil.
- The three transitions are `s/defn`-annotated (`:- ServerState`; swap/reload
  also take `state :- ServerState`), so `st/validate-schemas` checks the
  contract at test time.

### `remove-nil-vals` → `clara.server.tools.graph.utils`

- New ns `clara.server.tools.graph.utils` holds `remove-nil-vals` (moved out of
  `serialize.clj`) for broad reuse.
- `build-auto-detect-annotations` / `build-annotations*` / the transitions now
  wrap their return maps in `utils/remove-nil-vals` so optional keys are omitted
  rather than present-as-nil.
- Callers updated: `serialize.clj` (internal), `core.clj`.

### Schema instrumentation in every test namespace

- `schema.test/validate-schemas` **is** the global instrumentation (no
  `schema.test/instrument` exists in schema 1.4.1).  `:once` fixtures added to
  the nine test namespaces that were missing it (`perf_test.clj` skipped — it
  has no `deftest`).
- Surfaced and fixed a latent schema bug: `handle-get-session-fact-types` used a
  broken inline `s/conditional` on `:body` (predicates read `:status` off a
  value that has none).  Replaced with a proper whole-response
  `GetSessionFactTypesResponse` schema matching the other session handlers.
- `server/AGENTS.md` "Schema Validation" section strengthened to state this
  requirement and the `validate-schemas`-is-the-instrumentation fact.

### Files changed in the refinements

- `server/src/clara/server/tools/graph/utils.clj` (new) — `remove-nil-vals`
- `server/src/clara/server/tools/graph/serialize.clj` — `remove-nil-vals` moved out
- `server/src/clara/server/tools/graph/core.clj` — use `utils/remove-nil-vals`
- `server/src/clara/server/graph/server.clj` — `ServerState` schema, optional keys, `s/defn` transitions, `remove-nil-vals`
- `server/src/clara/server/graph/api.clj` — `GetSessionFactTypesResponse` fix
- nine test namespaces — `st/validate-schemas` `:once` fixture
- `server/AGENTS.md` — schema validation rule

---

## Fix B (content-addressed clj-kondo cache) — NOT started

No work done.  Plan section §2 of `perf-rulebase-analysis-plan.md` is the
source of truth for the approach when picked up.

## Fix C (`build-dep-graph` investigation) — NOT started

No work done.  Plan section §3 (measure-first gate) applies.
