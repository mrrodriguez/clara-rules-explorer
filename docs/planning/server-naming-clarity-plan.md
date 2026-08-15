# Server Naming Clarity Plan

Status: **Proposed** (not yet implemented)

Related: [`server-naming-clarity-problem-statement.md`](./server-naming-clarity-problem-statement.md)

Greenfield — no backwards-compatibility constraints. Every rename below is a
plain identifier change plus docstring/key updates; no behavior changes.

---

## 1. Goals and non-goals

**Goals**

- Replace the overloaded "snapshot" / "memory-snapshot" vocabulary with one
  canonical term: **memory-analysis**.
- Replace the misleading "session analysis" / bare "analysis" vocabulary in
  `clara.server.tools.graph.analyze` with **rule-source analysis**.
- Adopt a single builder convention (the `->thing` constructor idiom) at every
  public entry point, and put the "thing being updated" first in updater
  signatures (the Clojure idiom).
- Remove the near-duplicate entry points whose roles are indistinguishable by
  name (static annotation generation vs. memory-derived annotation detection).

**Non-goals**

- No behavior, output-shape (except renamed keys noted below), or performance
  changes.
- No rework of `clara.server.tools.graph.analyze.*` internal sub-namespaces
  beyond docstring cross-references.
- No UI redesign; only the mechanical API-type / route touch-ups required by the
  HTTP-surface decision in §5.

---

## 2. Canonical terminology

| Old term(s) | New term | Meaning |
| --- | --- | --- |
| snapshot, memory-snapshot | **memory-analysis** | The value produced by inspecting a live Clara session's working memory (`facts`, `fact-types`, matches, origins, ids). |
| session analysis, analysis (in `analyze` ns) | **rule-source analysis** | The merged clj-kondo analysis of the namespaces underlying a session/rulebase's rule sources (RHS forms synthesized + pruned). |
| rulebase analysis | **rulebase-analysis** | The static overlay of annotations onto a rulebase (dep-graph, rule/query summaries, fact-type index). Term unchanged; builder gets `->`. |
| annotations | **annotations** | Unchanged. |

Consequences that follow from these terms:

- The working-memory builder is **`->memory-analysis`**, not `session-snapshot`.
- The static kondo-analysis builder is **`->rule-source-analysis`**, not
  `analyze-session-rules`.
- `core/rulebase-analysis` keeps its term but becomes **`->rulebase-analysis`**
  to follow the ctor idiom and stop clashing with local bindings named
  `analysis`.

---

## 3. Naming conventions (the rules)

1. **Builders** — functions that construct a primary value from inputs — use the
   `->thing` ctor idiom (`->memory-analysis`, `->rulebase-analysis`,
   `->annotations-from-rule-source-analysis`).
2. **Updaters** — functions that transform an existing value — take that value
   as the **first** argument and use a verb phrase
   (`add-…`, `merge-…`, `update-…`). No more `(f extra-thing annotations)`
   where `annotations` is what gets updated.
3. **Accessors / views** use noun phrases (`rules-list`, `queries-list`,
   `rulebase-counts`, `rulebase-analysis-external-view`, `get-*`).
4. **No bare `build-*`** at public entry points. Private helpers may keep
   `build-*` or be swept to `->` in a follow-up (§7 step 4).
5. Terms "snapshot" and "session-analysis" must not appear in server identifiers,
   state keys, schema names, or docstrings after this change.

---

## 4. Namespace-by-namespace rename plan

### 4.1 `clara.server.tools.graph.analyze`

This namespace analyzes **rule sources** (the namespaces underlying a
session/rulebase), not "the session". Public entry points become:

| Current | Proposed | Kind / notes |
| --- | --- | --- |
| `build-analysis-from-namespaces` | `->rule-source-analysis-from-namespaces` | Builder. Generic: starting namespaces → merged kondo analysis. |
| `analyze-session-rules` | `->rule-source-analysis` | Builder. Session/rulebase → rule-source analysis (synthesizes RHS snippets). |
| `generate-annotations-from-analysis` | `->annotations-from-rule-source-analysis` | Builder. Static annotations from a rule-source analysis. |
| `add-auto-detected-annotations` | `add-memory-derived-insert-type-detections` | Updater. **Signature flips** to `[annotations memory-analysis]`. Adds `:clara-rules/dynamic-insert-types-detected` entries only. |
| `enrich-annotations-from-session` | `merge-memory-derived-insert-types` | Updater. **Signature flips** to `[annotations session]`. Runs the "add detections" step, dedups against props, then promotes truly-new types into `:clara-rules/insert-types`. |
| `enrich-annotations-from-session*` | `merge-memory-derived-insert-types*` | Updater variant. Returns `{:annotations … :memory-analysis …}` (was `:snapshot`). |
| `extract-session-rule-names` | `extract-rule-names` | Accessor over a session **or rulebase**. |
| `extract-session-namespaces` | `extract-rule-namespaces` | Accessor over a session **or rulebase**. |
| `->memory-layer` | `->memory-layer` (keep) | Already ctor idiom. Docstring updated to reference renamed fns. |

Private helpers renamed for vocabulary/consistency:

| Current | Proposed |
| --- | --- |
| `session-rules-by-ns` | `rulebase-rules-by-ns` |
| `session-rule-fq-names` | `all-rule-fq-names` |
| `rule->session-raw-types` | `rule->memory-derived-raw-types` |

**Why the two memory-derived functions differ** — both update `annotations`
(first argument), but at different depths:

- `add-memory-derived-insert-type-detections` writes only the detection
  channel (`:clara-rules/dynamic-insert-types-detected` with
  `:fact-instance-derived-types` + `:resolution :partial`). It never touches
  `:clara-rules/insert-types` and never consults rule `:props`.
- `merge-memory-derived-insert-types` is the full pipeline: it runs the
  "add detections" step, then dedups the memory-derived types against both
  declared `:insert-types` and rule `:props`, and promotes truly-new types
  into `:clara-rules/insert-types`.

The verb pair is now "add" (record detections) vs "merge" (promote into
declared insert-types), so neither reads as a pure query.

Option keys and schemas:

- `GenerateAnnotationsOptions` → `RuleSourceAnnotationsOptions`.
- Its `:analysis` key → **`:rule-source-analysis`** (all call sites updated).
- `analyze-session-rules`'s `:session-or-rulebase` key is unchanged.
- `build-analysis-from-namespaces`'s `:initial-analysis` →
  **`:initial-rule-source-analysis`** (and `:processed-namespaces` docstring
  updated; the value semantics are unchanged).
- Namespaced result key `::combined-sources` is unchanged.

Namespace docstring: retitle "Session-based analysis" → "Rule-source analysis".

### 4.2 `clara.server.tools.graph.core`

| Current | Proposed | Kind / notes |
| --- | --- | --- |
| `rulebase-analysis` | `->rulebase-analysis` | Builder. Term kept; ctor idiom. |
| `rulebase-analysis*` (private) | `->rulebase-analysis*` | Impl. |
| `build-type-analysis-map` | `->type-analysis-map` | Builder (public). |
| `build-production-id-index` | `->production-id-index` | Builder (public). |
| `build-dep-graph` | `->dep-graph` | Builder (public). |
| `rulebase-summary` | `rulebase-counts` | Accessor. Returns the `:rule-count` / `:query-count` / `:fact-type-count` map. |
| `analysis-result` | `rulebase-analysis-external-view` | View/projection. Strips `:fact-type-id-index`, `:production-id-index`, `:merged-annotations`. |
| `rules-list` | `rules-list` (keep) | Accessor. |
| `queries-list` | `queries-list` (keep) | Accessor. |
| `extract-ancestors-fn` | `extract-ancestors-fn` (keep) | Accessor. |
| `extract-lhs-fact-types` | `extract-lhs-fact-types` (keep) | Pure extract. |

Private `build-*` helpers (`build-consumers-by-type`, `build-production-map`,
`build-production-summary-map`, `build-rule-summary-map`,
`build-query-summary-map`, `build-production-annotation-map`) are an optional
consistency sweep to `->…` (§7 step 4); not required for the public surface.

### 4.3 `clara.server.tools.graph.memory`

| Current | Proposed | Kind / notes |
| --- | --- | --- |
| `session-snapshot` | `->memory-analysis` | Builder. Arities: `[session]` and `[session known-set]`. |
| `session-snapshot-from-analysis` | **removed** | Thin convenience. Callers compute `(-> analysis :fact-types keys set)` and call `(->memory-analysis session known-set)`. |
| `update-snapshot-known-set` | `update-memory-analysis-known-set` | Updater. First arg is the memory-analysis. |
| `get-session-rule-activity` | `get-rule-activity` | Accessor over a memory-analysis. |
| `get-session-query-activity` | `get-query-activity` | Accessor over a memory-analysis. |
| `get-node-elements` / `get-node-tokens` | (keep) | Operate on a live session, not a memory-analysis. |

Namespace docstring: "snapshotting" → "analyzing" working memory.

### 4.4 `clara.server.graph.cache`

| Current | Proposed | Kind / notes |
| --- | --- | --- |
| `create` | `->cache` | Builder (ctor idiom). |
| `build-state` (private) | `->state` | Builder (private). |
| `get-state` (private) | `get-state` (keep) | Accessor. |
| `analysis` | `get-rulebase-analysis` | Accessor. Aligns with `get-state`. |
| `snapshot` | `get-memory-analysis` | Accessor. Aligns with `get-state`. |
| `warm!` | `warm!` (keep) | Side-effect. |

State-map keys become `:rulebase-analysis` and `:memory-analysis` (was
`:analysis` / `:snapshot`). Namespace docstring: "session-snapshot caching" →
"memory-analysis caching".

### 4.5 `clara.server.graph.server`

| Current | Proposed | Kind / notes |
| --- | --- | --- |
| `build-annotations` | `->resolved-annotations` | Builder. Resolves a bare annotations map from a spec. |
| `build-annotations*` | `->resolved-annotations*` | Builder variant. Returns `{:annotations … :memory-analysis …}`. |
| `build-static-layers` (private) | `->static-layers` | Builder (private). |
| `build-auto-detect-annotations` (private) | `->auto-detect-annotations` | Builder (private). |
| `->source-layer` | `->source-layer` (keep) | Already ctor idiom. |

Schema / state keys:

- `MemorySnapshot` schema → **`MemoryAnalysis`** (docstring references
  `->memory-analysis`).
- `ServerState` key `:memory-snapshot` → **`:memory-analysis`**.
- `transition-start` / `transition-swap` / `transition-reload` emit
  `:memory-analysis` instead of `:memory-snapshot`.
- All docstrings referencing `build-annotations` / `session-snapshot` updated.

### 4.6 `clara.server.tools.graph.perf-test` (test harness)

This namespace is a step-runner over the renamed entry points. Each step fn and
its state key are renamed to say exactly which analysis it produces.

| Current fn | Proposed fn | Times |
| --- | --- | --- |
| `run-session!` | `run-rules!` | fires the generated rule chain (`pgh/run-rules`) |
| `run-session-snapshot!` | `run-memory-analysis!` | `memory/->memory-analysis` |
| `run-analyze-session-rules!` | `run-rule-source-analysis!` | `analyze/->rule-source-analysis` |
| `run-analysis!` | `run-rulebase-analysis!` | `core/->rulebase-analysis` |
| `run-generate-annotations-from-analysis!` | `run-annotations-from-rule-source-analysis!` | `analyze/->annotations-from-rule-source-analysis` |
| `run-enrich-annotations-from-session!` | `run-merge-memory-derived-insert-types!` | `analyze/merge-memory-derived-insert-types` |

`state-atom` keys:

| Current key | New key |
| --- | --- |
| `:session-snapshot` | `:memory-analysis` |
| `:session-rules-analysis` | `:rule-source-analysis` |
| `:analysis` | `:rulebase-analysis` |
| `:memory-enriched-annotations` | `:memory-derived-annotations` |
| `:run-rules-result` | (keep) |
| `:annotations` | (keep — the static annotations) |

---

## 5. HTTP API and UI surface

Code renames in §4 force these handler/helper renames in
`clara.server.graph.api` (before the route change below):

| Current | Proposed |
| --- | --- |
| `handle-get-session-snapshot` | `handle-get-memory-analysis` |
| `with-snapshot` | `with-memory-analysis` |
| `(core/rulebase-summary …)` | `(core/rulebase-counts …)` |
| `(core/analysis-result …)` | `(core/rulebase-analysis-external-view …)` |
| `(cache/analysis …)` | `(cache/get-rulebase-analysis …)` |
| `(cache/snapshot …)` | `(cache/get-memory-analysis …)` |
| destructured `:memory-snapshot` | `:memory-analysis` |

**Accepted API-contract change:**

| Current route | Proposed route | Body |
| --- | --- | --- |
| `GET /v1/analysis` | `GET /v1/rulebase-analysis` | `rulebase-analysis-external-view` |
| `GET /v1/session-snapshot` | `GET /v1/memory-analysis` | memory-analysis minus `:fact-raw-types` |
| `GET /v1/rulebase-summary` | (keep) | `rulebase-counts` + `:working-memory-available` |
| `GET /v1/session/*` | (keep for now) | memory-analysis sub-views |

Rationale: `/v1/analysis` is ambiguous next to `/v1/memory-analysis`;
`/v1/session-snapshot` carries the exact "snapshot" word this plan removes.
The `/v1/session/*` subtree can stay as the product's "session memory
navigator" grouping, or be renamed to `/v1/memory/*` in a separate
product-level follow-up (larger UI change, deferred).

Files touched by the route change:

- `server/src/clara/server/graph/api.clj` — router paths.
- `docs/explorer-graph-api.md` — route table, terminology, endpoint sections,
  and the namespace→responsibility table (replace "snapshots" with
  "memory-analysis").
- `ui/src/lib/api.ts` — the `/analysis` fetch path (UI does not call
  `/session-snapshot` directly).
- `ui/src/lib/types/api.ts` — the `Analysis` interface comment ("Phase 2:
  Session Snapshot Interfaces" → "Memory Analysis Interfaces"; interface
  comments only — wire shape is unchanged).
- `server/test/clara/server/graph/api_test.clj`,
  `session_api_test.clj`, `integration_test.clj` — request paths.

Decision: **change them** — greenfield, and the UI impact is one fetch path.

---

## 6. Complete file checklist

### Server source (required)

- `server/src/clara/server/tools/graph/analyze.clj`
  - all §4.1 renames, option keys, schema rename, docstring retitle.
- `server/src/clara/server/tools/graph/core.clj`
  - all §4.2 renames + docstring cross-references.
- `server/src/clara/server/tools/graph/memory.clj`
  - all §4.3 renames; delete `session-snapshot-from-analysis`.
- `server/src/clara/server/graph/cache.clj`
  - all §4.4 renames; state keys; docstring.
- `server/src/clara/server/graph/server.clj`
  - all §4.5 renames; `MemorySnapshot` → `MemoryAnalysis`; `:memory-snapshot` →
    `:memory-analysis`; docstrings.
- `server/src/clara/server/graph/api.clj`
  - §5 handler/helper renames, `cache/*` accessor calls, `core/*` calls,
    destructuring, router paths (§5 route change).
- `server/src/clara/server/graph/main.clj`
  - `analyze/analyze-session-rules` → `->rule-source-analysis`;
    `analyze/generate-annotations-from-analysis` →
    `->annotations-from-rule-source-analysis` with `:rule-source-analysis` key;
    `core/rulebase-analysis` → `->rulebase-analysis`.
- `server/dev/regen_fixture.clj`
  - `analyze-session-rules` → `->rule-source-analysis`;
    `generate-annotations-from-analysis` →
    `->annotations-from-rule-source-analysis` (key `:rule-source-analysis`).

### Server source (docstring cross-references only)

- `server/src/clara/server/tools/graph/annotations.clj`
  - reference to `core/rulebase-analysis`.
- `server/src/clara/server/tools/graph/annotations/callsite.clj`
  - reference to `analyze/enrich-annotations-from-session`.
- `server/src/clara/server/tools/graph/analyze/index.clj`
  - reference to `generate-annotations-from-analysis`.
- `server/src/clara/server/tools/graph/analyze/callsite.clj`
  - references to `generate-annotations-from-analysis`.

### Server tests

- `server/test/clara/server/tools/graph/analyze_test.clj`
  - many call sites: `analyze-session-rules`, `generate-annotations-from-analysis`,
    `build-analysis-from-namespaces`, `add-auto-detected-annotations`,
    `enrich-annotations-from-session` / `-*`; test names updated to match.
- `server/test/clara/server/tools/graph/core_test.clj`
  - `core/rulebase-analysis` → `->rulebase-analysis`.
- `server/test/clara/server/tools/graph/memory_test.clj`
  - `session-snapshot` → `->memory-analysis`;
    `session-snapshot-from-analysis` → inline `->memory-analysis` with known-set;
    `core/rulebase-analysis` → `->rulebase-analysis`.
- `server/test/clara/server/tools/graph/source_sink_test.clj`
  - `core/rulebase-analysis` → `->rulebase-analysis`.
- `server/test/clara/server/graph/cache_test.clj`
  - `cache/snapshot` → `cache/get-memory-analysis`;
    `session-snapshot` / `session-snapshot-from-analysis` updates;
    `core/rulebase-analysis` → `->rulebase-analysis`.
- `server/test/clara/server/graph/server_test.clj`
  - `server/build-annotations` → `->resolved-annotations`.
- `server/test/clara/server/graph/api_test.clj`,
  `session_api_test.clj`, `integration_test.clj`
  - route path updates (§5 route change).
- `server/test/clara/server/tools/graph/perf_test.clj`
  - §4.6 renames (fns + state keys).

### Docs

- `docs/explorer-graph-api.md` — terminology + route changes (§5).
- `docs/planning/server-naming-clarity-problem-statement.md` — add a "Resolved
  by" pointer to this plan.

### UI (route change)

- `ui/src/lib/api.ts` — `/analysis` → `/rulebase-analysis`.
- `ui/src/lib/types/api.ts` — comment updates (no wire-shape change).

---

## 7. Execution order and verification

1. **Terminology-first sweep of `memory.clj` + `core.clj`** — the two value
   constructors everything else depends on. Update `memory`, `core`, `cache`,
   `api`, `server` in one pass so the tree compiles.
2. **`analyze.clj` renames** — the rule-source-analysis builders and the two
   memory-derived annotation functions (with the flipped signatures). Update
   `server.clj`, `main.clj`, `regen_fixture.clj`, `perf_test.clj`.
3. **HTTP route change** — `api.clj` router + UI fetch path + docs + affected
   tests.
4. **(Optional) private `build-*` → `->…` consistency sweep** in `core.clj` and
   `memory.clj`; not required for the public contract.
5. **Verification** (from `server/`):
   ```bash
   make format-check
   make lint
   make reflection-check
   make test
   ```
   Then, from `ui/` (route change):
   ```bash
   make format check lint
   make test
   ```
6. Grep gate — the following must return no hits in `server/src` after the
   change (outside comments being rewritten):
   ```bash
   grep -rn -E "session-snapshot|memory-snapshot|session-analysis|analyze-session-rules|generate-annotations-from-analysis|add-auto-detected-annotations|enrich-annotations-from-session|build-analysis-from-namespaces|rulebase-summary|analysis-result|build-annotations|build-state" server/src
   ```

---

## 8. Decisions (resolved)

1. **Generic namespace builder name** — `->rule-source-analysis-from-namespaces`
   accepted.
2. **`session-snapshot-from-analysis`** — removed; callers use
   `(->memory-analysis session known-set)`.
3. **`get-rule-activity` / `get-query-activity`** — renamed now (no longer
   optional).
4. **HTTP route changes** — accepted:
   `/analysis` → `/rulebase-analysis`,
   `/session-snapshot` → `/memory-analysis`.
5. **`cache/create` → `->cache`** — accepted.
