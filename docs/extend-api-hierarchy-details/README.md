# Extend API with Fact Type Hierarchy Details

Design plan for surfacing type-hierarchy information in the explorer API so that
upstream/downstream relationships that cross type-hierarchy boundaries are
self-explanatory without requiring the user to cross-reference fact-type views.

---

## Problem Statement

The analysis graph (`build-dep-graph`) already respects Clara's `ancestors-fn`
hierarchy: when Rule X inserts type `A` and Rule Y's LHS reads type `B` (where
`A` is a descendant of `B` via `ancestors-fn`), the dep-graph correctly links
Rule X → Rule Y.  However, the API that serves this information to the UI
does not convey any hierarchy context:

- **Fact-type views** (`/v1/fact-types`, `/v1/analysis`) show usage counters
  but nothing about what ancestors a type has.  You can't tell that
  `MarkerRecord` satisfies `IScanMarker` from the API.

- **Production-dependency links** (`:upstream` / `:downstream`) carry only
  `{name, ns, type}` — no indication of *which* produced type satisfies
  *which* required type.  When Rule Y requires type `B` and its upstream Rule X
  produces type `A` (a descendant of `B`), neither the upstream entry on Y nor
  the downstream entry on X makes this visible.  The user sees two unrelated
  types and has to manually correlate fact-type pages to understand the link.

**Goal:** Make the API self-explanatory so that from any production or
fact-type view, the user can see how types relate through the hierarchy
without cross-referencing.

---

## Files

| File | Contents |
|------|----------|
| [`01-system-context.md`](./01-system-context.md) | System context — `ancestors-fn`, `downstream?`, data structures, raw vs. serialized types, heterogeneous type handling, kind-explicit serialization, route IDs (`:id`), `TypeReference` shape |
| [`02-phase-1-fact-type-hierarchy.md`](./02-phase-1-fact-type-hierarchy.md) | Phase 1 design — ancestors threading, memoized ancestors-fn, `:ancestors` + `:known` fields, ghost types, detail vs. list endpoints, files to modify, API compatibility, test cases |
| [`03-phase-2-type-bridge.md`](./03-phase-2-type-bridge.md) | Phase 2 design — `matching-type-pairs`, dep-graph unchanged, `:match` serialization on upstream/downstream, deduplication, symmetry, files to modify, API compatibility, test cases |
| [`04-edge-cases.md`](./04-edge-cases.md) | Edge cases checklist covering both phases |
| [`05-phase-3-ui-integration.md`](./05-phase-3-ui-integration.md) | Phase 3 (future) — UI integration impact summary |
| [`06-docs-schema-principles.md`](./06-docs-schema-principles.md) | Documentation & schema principles — binding at implementation time |

---

## Implementation Order

> **Checkpoint log** (updated as work lands; each marked item is verified with
> `make test` + `make lint` + `make format-check` + `make reflection-check`):
>
> - **M1 — Phase 1a complete (2026-08-04):** kind-explicit `resolve-type`,
>   `route-id`, `serialize-type-ref`, `serialize-lhs` threading,
>   `extract-ancestors-fn` shared accessor (core + analyze),
>   `->memoized-ancestors`, hoisted `type-analysis-map` (+`:ns-name`), upfront
>   known set, serialized ancestors index (divergence asserted, hierarchy
>   ordered, cycle-guarded), `downstream?` on the memoized set fn, and the
>   `:ancestors` enrichment on fact-type entries (the index's only consumer —
>   folded in so no dead code).  Tests updated for kind-explicit renames; new
>   serialize tests.
> - **M2 — Phase 1b + server tests (1d items 1–10, 12) complete (2026-08-04):**
>   `[TypeReference]` on `:lhs-types` / `:insert-types` / `:retract-types` and
>   LHS condition `:type`; callsite `:resolved-types` / `:fact-type` →
>   `TypeReference`; fact-type usage lists → `[ProductionDep]`; fact-type `:ns`
>   (raw-kind-aware, from the index build); `loan_hierarchy_rules.clj` fixture
>   (keyword derive hierarchy incl. a ghost `::base-document`, tuple types,
>   `LoanApplication` record, `find-map-facts` interface query); new tests:
>   behavior, derive transitivity + `known` flags, interface-ancestor
>   `known: true`, missing-meta fallback, nil-returning fn, exact-count
>   memoization, mixed-kind hierarchy, intransitive + cyclic ordering,
>   condition-`:type`/`:lhs-types` consistency, symbol ns-resolution parity,
>   fact-type `:ns`.  Notable finding: `mk-session` parses a trailing options
>   *map* as a rule source (silently dropped) — options must be keyword args;
>   also `clojure.core/type` already honors `with-meta :type`, so the default
>   fact-type-fn types keyword/tuple facts.
> - **M3 — Phase 1c + 1d item 11 complete (2026-08-04):** `:id` on fact-type
>   entries (route-id of the serialized name) + list payload;
>   `FactTypeDetail` schema split (list item + required `:ancestors`;
>   `FactTypeListItem` no longer carries the optional `:ancestors`);
>   `core/build-fact-type-id-index` (id→name, uniqueness asserted, built in
>   `api/get-analysis-state` and kept in the cache atom — never in the
>   `/v1/analysis` payload); `handle-get-fact-type` resolves **id-only** via
>   the index (name-based URLs now 404); route param renamed `:id`; api_test
>   + smoke_test switched to id-based lookups; new router tests (class /
>   keyword / tuple ids round-trip, name lookups 404, index resolves every id
>   back to its name, colliding fixture throws).  **136 tests / 827
>   assertions green, lint/format/reflection clean.**
>
> - **M4 — Phase 1g complete (2026-08-04):** server-side production route ids.
>   `:id` on rule/query list + detail entries (route-id of the production
>   name), on every `ProductionDep` (upstream/downstream via
>   `serialize-production-dep`, fact-type usage lists), and on session refs
>   (`inserted-from`/`used-by` and `FactTypeRoleGroup` in memory.clj);
>   `core/build-production-id-index` (uniqueness asserted, cached in
>   `get-analysis-state`, never in the `/v1/analysis` payload); per-snapshot
>   id→name indexes (`:rule-id-index`/`:query-id-index`/`:fact-type-id-index`)
>   built in `session-snapshot` (no analysis-cache dependency); all rule /
>   query / session detail handlers resolve **id-only**; **`fq-name-from-param`
>   deleted** (same change); routes renamed `/:id`; session `SessionFact`
>   `:type` → `TypeReference`, fact-type `:ns` on session types + facts
>   (raw-type-aware via `core/raw-type-ns`); `:fact-instance-derived-types`
>   stays `[s/Str]` (extracts `:name` from session TypeReferences); tests
>   updated: api/session/smoke → id-based lookups, memory_test →
>   TypeReference shapes, new `build-production-id-index` tests.  **137 tests /
>   855 assertions green, lint/format/reflection clean.**  Server-side
>   id-addressing is now complete for fact types, rules, queries, and session
>   resources; nothing name-based remains in the API contract.
>
> - **M5 — Phase 2 complete (2026-08-04):** `:match` type-bridge info on
>   `:upstream`/`:downstream` deps.  `matching-type-pairs` (raw
>   (produced, consumed) pairs for actual edges only — `:dep-graph` shape
>   untouched); `serialize-match` (each end serialized in its own production's
>   ns context, sorted by producer then consumer `:name`); threaded
>   `type-analysis-map` + `ancestors-set-fn` through
>   `rulebase-analysis` → summary builders → `production-summary` →
>   `get-production-deps-summary`; `TypeBridgeMatch` + `ProductionDep`
>   `(s/optional-key :match)` schemas (symmetric semantics documented);
>   tests: direct, single hierarchy jump, multi-type (2 sorted entries),
>   direct+hierarchy coexistence, dedup (duplicate declarations collapse),
>   symmetry (upstream ≡ downstream), and a cross-field consistency sweep
>   over loan-doc + hierarchy + match fixtures (producer-type :name ∈
>   producer's insert/retract types, consumer-type :name ∈ consumer's
>   lhs-types, queries included); dep-graph regression pinned by existing
>   tests.  **143 tests / 1010 assertions green, lint/format/reflection
>   clean.**  Server-side contract for the hierarchy plan is now complete.
>   Remaining: docs (M6), fast-follow `"via": "retract"`, UI (deferred).
>
> - **M6 — docs complete (2026-08-04):** `explorer-graph-api.md` fully
>   rewritten for the new contract — common shapes section (`TypeReference`,
>   `ProductionDep`, `TypeBridgeMatch`/`:match` with symmetric semantics,
>   kind-explicit serialization table, `:id` scheme), id-based routes for all
>   rule/query/fact-type/session endpoints, `:ancestors` + `:ns` documented,
>   analysis note corrected (`:dep-graph` IS in the payload; reverse indexes
>   are not), stale `:annotation-sources` field and the dot-separated-URL
>   content-negotiation text removed (name-based URLs 404), stray typo
>   fixed.  Docs-hygiene pass: removed plan references (fixture ns docstring,
>   test section comments), de-narrated the `get-analysis-state` docstring
>   (no "previously"), verified schema docstrings state purpose/semantics
>   rather than enumerating shapes and that no code cites the plan or API
>   doc for its own contract.  **143 tests / 1010 assertions green,
>   lint/format/reflection clean.**
>
> **State after M5:** the server API fully implements the plan's contract
> (kind-explicit types, `TypeReference` everywhere, id-based routes, `:id` /
> `:ns` / `:ancestors` / `:match`).  Remaining server work: `explorer-graph-api.md`
> rewrite (M6) and the `"via": "retract"` fast-follow; the UI (1e/1h/2e/3) is
> deferred until the svelte-engineering skill is loaded.

- [x] **Phase 1a:** In `rulebase-analysis`: extract ancestors-fn via a single shared accessor used by both `core.clj` and `analyze.clj` (meta extraction + `clojure.core/ancestors` fallback); add `->memoized-ancestors`; hoist `type-analysis-map` (with `:ns-name`) out of `build-dep-graph`; build serialized ancestors index in per-production ns context (topologically ordered, divergence asserted, serialization memoized by raw-type × ns-name); compute the upfront known set (serialized `:consumed-types` ∪ `:produced-types` union — equals the future fact-types map keys by construction); simplify `downstream?` to consume the memoized set fn in the same change; verify raw types reaching the index are resolved. Extend `resolve-type` to kind-explicit serialization (splitting the catch-all `:else` into `string?`/`sequential?`/catch-all — see the serialization table); add `serialize-type-ref` (raw type + ns-name + known set → `TypeReference`); thread the production ns-name into `serialize-lhs` so LHS condition `:type` values serialize as `TypeReference`s too
- [x] **Phase 1b:** Add `:ancestors` field (`TypeReference` entries, deterministic topological order with lexicographic tie-break + cycle guard) to fact-type entries in `build-fact-type-summary-map` (single pass, upfront known set); convert `:lhs-types`/`:insert-types`/`:retract-types` and LHS condition `:type` to `[TypeReference]`; convert fact-type usage lists (`:used-by-rules` etc.) to `[ProductionDep]`
- [x] **Phase 1c:** Add the uniform route-id fn (slug + 8-char base36 SHA-1 suffix, 60-char slug cap) to `serialize.clj`; add `:id` to fact-type entries (`FactTypeListItem` + new `FactTypeDetail`) and to `TypeReference`; build fact-type id reverse index (uniqueness asserted, internal — not in the `/v1/analysis` payload); `handle-get-fact-type` resolves id-only. Router-level tests for id-based lookups (keyword/tuple/string/class forms)
- [x] **Phase 1d:** Add server tests for `:ancestors` (default-ancestors noise, `known` flag, ordering, missing-meta, nil-returning fn, memoization, mixed-kind hierarchy, kind-explicit serialization incl. string-bearing tuples, condition-`:type`/`:lhs-types` consistency, symbol ns-resolution parity, route ids)
- [ ] **Phase 1e:** Update UI types (`TypeReference` with `name`/`id`/`known`, `id` on `FactTypeListItem`/`RuleListItem`/`QueryListItem`/details/`ProductionReference`/session types, type-reference fields → `TypeReference`, usage lists → `ProductionReference[]`, `ancestors?` on detail `FactTypeSummary` in `api.ts`)
- [x] **Phase 1f:** Rewrite `explorer-graph-api.md` for the new contract — flag: this is a full rewrite, not a targeted edit (serialization table, `:id` scheme + routes, `:ns`, `:ancestors`)
- [x] **Phase 1g:** Server-side production route ids: `:id` on rule/query list + detail entries and every production ref (`ProductionDep` on rule/query details; `inserted-from`/`used-by` refs and `FactTypeRoleGroup` entries in `memory.clj`); production id reverse index; per-snapshot id→name indexes built in `session-snapshot` (no analysis-cache dependency); all rule/query/session detail handlers resolve id-only; delete `fq-name-from-param` server-side and its tests **in the same commit as the index implementation** (atomic revert if issues arise); update `session_api_test.clj` to id-based lookups
- [ ] **Phase 1h:** UI route-id migration: `utils.ts` link builders + `api.ts` fetchers use `:id` verbatim; delete `toRouteId`/`fromRouteId`/`splitQualifiedName`; `entries()` generators and `bin/scrape-demo-data.js` use server-issued ids; regenerate demo data (`pnpm scrape:demo`); rename `session/fact-types/[typeName]` → `[id]`; update all type-link callers (`FactTypeReferenceLink`/`ConditionFactType`/`DynamicCallsiteList`/`ProductionReferenceCategory`/`GlobalSidebarFlyout`/appState contextual nav) to consume `TypeReference`/`ProductionDep` directly; delete `FactTypeSummary`'s `toRef` mapping; update e2e URL fixtures. **Scope note: 1g+1h together are the largest work item (~30–40% of the total)** — every endpoint, every link builder, the prerender pipeline, and all URL-constructing test fixtures. Budget accordingly
- [x] **Phase 2a:** Add `matching-type-pairs` helper
- [x] **Phase 2b:** Update `get-production-deps-summary` (+ `serialize-match` in `serialize.clj`) to attach `:match` with symmetric `producer-type`/`consumer-type` `TypeReference` pairs, each serialized in its own production's ns context, sorted by `:name` post-serialization
- [x] **Phase 2c:** Update `ProductionDep` schema in `api.clj` with optional `:match` array
- [x] **Phase 2d:** Add server tests for `:match` (direct, hierarchy, multi-type, dedup, symmetry, cross-field consistency incl. sidecar symbol in foreign ns, dep-graph regression)
- [ ] **Phase 2e:** Update UI types (`ProductionReference`, `TypeBridgeMatch` in `api.ts`) — deferred with the UI phases (1e/1h); the server contract is complete
- [x] **Phase 2f:** Update `explorer-graph-api.md` with the `:match` contract (symmetric shape + semantics, citing `ProductionDep` schema) — landed in the M6 rewrite
- [x] **Docs hygiene pass:** verify the Documentation & Schema Principles — schemas carry the structural truth; no docstring enumerates shapes, narrates design history, or references this plan; project docs cite code (not vice versa) for impl details
- [ ] **Fast-follow (post-Phase 2, small):** `"via": "retract"` flag on `:match` entries whose bridge comes from a retract type, so the UI can distinguish retraction coupling from production
- [ ] **Phase 3:** UI integration (future, scoped separately)
