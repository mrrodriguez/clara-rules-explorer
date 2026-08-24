# Server Naming Inconsistencies — Design Doc

Status: **Draft** · Scope: `server/src` (Clojure) · Related: `server/docs/rule-annotations.md` clean-up (private `read-layer` → `->layer`, `props-layer` → `->props-layer`)

## 1. Goal

Align `server/src` public API to consistent Clojure naming so names convey semantics and arity without reading bodies. Fix two recurring violations patterns we just cleaned:

* **Noun-named builders** that create/coerce a value (should be `->` builder).
* **Duplicate-ish families** where 2–3 `defn` sound identical (coerce/unwrap/normalize variants, `*` helpers).

No behavior change — renames/privatizations with alias/shim where breaking.

## 2. Conventions (project-local)

We follow idiomatic Clojure + `clojure-engineering` skill, scoped to this repo:

* **Builder/coercion:** `->thing` — builds or coerces to `Thing` (e.g. `->layer`, `->props-layer`, `->memory-layer`). Single `->` entry per concept; private `normalize-*` does pure transform, `->` adds `s/validate`.
* **Getter/extraction:** `get-*` — pure extraction from a larger value when no better domain verb exists (e.g. `get-annotations`, `get-provenance`). Prefer domain verb (`annotations` is vague) but `get-` is the fallback per this plan.
* **Predicate:** `?` suffix (`layer?`, `merged-annotations?`).
* **Side effect:** `!` suffix (`write-layer!`, `swap-session!`, `reload-annotations!`).
* **Private helper:** `defn-` + `*` for variant returning richer shape (e.g. `->resolved-annotations*` → private or `->resolved-annotations-with-memory` — see §4.2). Never expose a helper that a single public entry can cover.
* **Noun is OK** only for data (`def`/`defrecord` holding a value) or when name *is* the domain verb (`merge-layers`, `rebase-layer` — verbs). Bare noun `layer`/`annotations` as a *function* is a violation (lexical shadow with `let [layer …]`).

Star (`*`) is reserved for private variant; public `*` is tech debt.

## 3. Inventory (current `server/src`, public `defn` only — 25 files scanned)

### 3.1 Noun builders → `->` (action required)

| Current | File | Should be | Why |
|---|---|---|---|
| `merge/normalize-layer` (public) | `merge.clj:77` | `defn- normalize-layer` | Now internal to `->layer`; public encourages bypass of `s/validate`. Make private. |
| `merge/annotations` | `merge.clj:582` | `get-annotations` | Pure extraction `(:annotations merged)`; noun with no verb. |
| `merge/provenance` | `merge.clj:588` | `get-provenance` | Same; 2-arity `get-provenance` is prefix-consistent (`get-annotation` already exists in `annotations.clj`). |
| `api/router` | `api.clj:490` | `->router` or `make-router` | Builds Ring router (takes `state-atom`, `cache`). Noun builder. |
| `api/app` | `api.clj:545` | `->app` / `make-app` | Builds Ring handler (`[handler cache]`). Noun builder. |
| `core/->rulebase-analysis` already `->`; its view `get-rulebase-analysis-external-view` is `get-` — consistent, keep. | | | |

Already fixed in this pass: `merge/layer` (removed) → `->layer`, `merge/read-layer` (now `defn-`), `merge/props-layer` → `->props-layer`. Keep as reference.

`utils/remove-nil-vals`/`sort-by-key` are verbs → keep. `default-form-printer` is a `def` value, noun OK.

### 3.2 Duplicate-ish families (same base name, different semantics)

**A. Unwrap/coerce family (`merge`):**
* `annotations` (unwrap `MergedAnnotations` → bare map)
* `->bare-annotations` (unwrap-or-passthrough)
* `coerce-to-bare-annotations` (props-layer + merge → bare, with `session`)

Three spellings of “to bare”. Callers pick by guess. See §5.1.

**B. Star helpers:**
* `server/->resolved-annotations` vs `server/->resolved-annotations*` (latter returns `{:annotations :memory-analysis}`)
* `analyze/merge-memory-derived-insert-types` vs `analyze/merge-memory-derived-insert-types*`

`*` is private convention; both are public → confusing.

**C. Normalize family:**
* `annotations/normalize-annotations` vs `annotations/normalize-rule-name` vs `merge/normalize-layer` – `normalize-*` in two namespaces, easy to `require` wrong one. `merge/normalize-layer` should be private per §3.1, reducing surface.

**D. Rule-source analysis family (`analyze`):**
* `->rule-source-analysis`
* `->rule-source-analysis-from-namespaces`
* `->annotations-from-rule-source-analysis`

Long overlap on `rule-source-analysis` base.

**E. Call-site derive/assign:**
* `callsite/derive-ids-in-rule-annotation` / `derive-callsite-ids` / `assign-callsite-ids` / `aggregate-resolution` – `derive` vs `assign` overlap.

**F. Core views:**
* `core/->rulebase-analysis` (primary) vs `core/get-rulebase-analysis-external-view` vs `core/->type-analysis-map`/`->dep-graph` – all views of same `FullAnalysis`.

### 3.3 Getters missing `get-` prefix

* `merge/annotations`, `merge/provenance` → `get-*` per above.
* `memory/get-rule-activity`, `memory/get-query-activity`, `memory/get-node-elements` already `get-` – consistent, keep.
* `core/get-rulebase-counts`, `get-rules-list`, etc. already `get-` – keep.

No change for `get-annotation` (already `get-`).

### 3.4 Public but redundant (can be private)

* `merge/normalize-layer` – see §3.1.
* `merge/fold-layer` – public (`defn`) but only used via `merge-layers`; `derive-rule-annotation`, `merge-*` helpers, etc. are already private – make `fold-layer` private for consistency.
* `graph/server: normalize-annotations-spec`, `annotations-spec?`, `->source-layer`, `->static-layers`, `->auto-detect-annotations` – already private (`defn-`), good; keep.
* `report/lint-layer-structure`, `annotation-callsites` – already private, good.

`merge/annotations-delta->layer` is public `->` builder – keep.

## 4. Design decisions

### 4.1 Getters: `get-*` fallback

When no better domain verb exists, use `get-*`. `annotations` → `get-annotations` (extracts `:annotations` from `MergedAnnotations`); `provenance` → `get-provenance`. Keeps symmetry with existing `annotations/get-annotation` and `memory/get-*`.

### 4.2 Star helpers

Public `*` is banned. Two options per pair:

* Make helper private (`defn- ->resolved-annotations*`) and keep public `->resolved-annotations` as sole entry.
* Or rename public helper to explicit `with-*` (`->resolved-annotations-with-memory`, `merge-memory-derived-insert-types-with-*`). Prefer private; `*` was internal `{:annotations :memory-analysis}` plumbing for cache warming.

Applies to `server/->resolved-annotations*` and `analyze/merge-memory-derived-insert-types*`.

### 4.3 Coerce family

Collapse to one public entry + private helpers. Preferred:

* `coerce-to-bare-annotations` stays public (most general: bare | Merged | `[Layer]` | path → bare, needs `session`).
* `->bare-annotations` becomes an alias or is inlined (unwrap-or-passthrough) – keep as thin `defn` delegating to `get-annotations` or deprecate. Docstring should point to `coerce-*` as canonical when `session` may be needed.
* `get-annotations` is the leaf unwrap.

This removes “which unwrap do I call?” and mirrors `->layer` single-entry pattern we just adopted.

### 4.4 Builder `->` vs `make-` vs `build-`

Use `->thing` for coercion/building a domain value (`->layer`, `->props-layer`). Use `make-`/`build-` only for side-effecting constructors (`start-system!` already uses `start!`/`stop!`). `api/router`/`app` building Ring artefacts fits `->router`/`->app` (or `make-router` if team prefers `make-` for Ring – pick one, be consistent).

### 4.5 Docs

`server/docs/rule-annotations.md` must list only public API; we already removed `read-layer`/`layer` row. Keep `write-layer!`, `->layer`, `->props-layer`, `merge-layers`, `derive-conclusions`, `get-annotations`, `get-provenance`. Any new private (`normalize-layer`, `fold-layer`, `lint-layer-structure`) stays out of docs.

## 5. Phased plan (no flag day)

**Phase 0 — aliases + deprecation (non-breaking, lands first):**

1. `merge.clj`: `defn- normalize-layer` (make private, keep alias `defn normalize-layer` → `defn-` + `def ^:deprecated normalize-layer` shim if needed or just private – check external usage: only internal + `report.clj` uses `normalize-layer` via `s/validate` + `normalize-layer` – that caller is `report/validate-layers` doing `s/validate Layer (normalize-layer %)` – update that single call to `->layer` and make `normalize-layer` private).
2. Add `def ^:deprecated get-annotations`/`get-provenance` shims pointing to old `annotations`/`provenance`; keep old `defn annotations`/`provenance` as deprecated aliases for one release, docs point to new.
3. `api.clj`: add `->router`/`->app` with bodies moved from `router`/`app`; keep `router`/`app` as deprecated wrappers.
4. No rename of star helpers yet – add `defn-` copies, keep public `*` with `^:deprecated`.

Verification: `make -C server lint reflection-check test` – deprecated calls still pass.

**Phase 1 — consolidate coerce family + star helpers (still non-breaking):**

5. Keep `coerce-to-bare-annotations` canonical; rewrite its body already `->>` threaded (done). Make `->bare-annotations` a one-liner delegating to `get-annotations`; add docstring `DEPRECATED – use get-annotations or coerce-to-bare-annotations`.
6. Make `server/->resolved-annotations*` and `analyze/merge-memory-derived-insert-types*` private (`defn-`); keep public deprecated wrappers that log once via `clojure.tools.logging/warn`.

**Phase 2 — remove deprecated (breaking, minor version):**

7. Delete `merge/annotations`, `merge/provenance`, `merge/normalize-layer` public, `api/router`, `api/app`, `*` wrappers. Update all internal call sites (already `->`).
8. Rename long `analyze` names only if churn justified: `->rule-source-analysis-from-namespaces` → `->rule-source-analysis` with dispatch on `include-ns-prefixes`? Prefer keep – rename is noisy and these are distinct arities (namespaces vs session). Document in namespace docstring instead.

**Phase 3 — optional polish:**

9. Consider `report/unresolved-report` → `get-unresolved-report` / `->unresolved-report` – `unresolved-report` is noun report builder; if we apply `get-` rule strictly, rename to `get-unresolved-report`. Low priority, already verb-ish `report`.
10. `annotations/dedupe-by` vs `utils/dedupe-by` duplicate – keep both? `annotations.merge/dedupe-by` is public generic, `utils` has no dedupe. Not a rename, just note.

## 6. Verification

* `make -C server format-check lint reflection-check test` must stay green each phase. Added vector-of-Layers test (`test-swap-session-vector-of-layers`) pins `->source-layer`/`->layer` path.
* `grep -R "defn \(props-layer\|layer\|read-layer\)" server/src` should return 0 public noun builders after Phase 2.
* `server/docs/rule-annotations.md` table must list only public `->`/`get-`/`write-!`/`merge-` entries; CI `grep "\`read-layer\`"` on docs should be 0.

## 7. Open questions

* Keep `annotations`/`provenance` as `get-annotations`/`get-provenance` vs domain `merged-annotations`/`merged-provenance`? `get-` wins per this plan’s rule where no better name exists, but `merged-annotations` might be clearer than bare `annotations`.
* `api/router`/`app` – `->router` vs `make-router` – `make-` is more common for Ring in this repo (`make` elsewhere is `->` for data). Pick `->` for consistency with `->layer` family, or `make-` if team prefers side-effect distinction.
* `test-props-layer` vs `test-->props-layer` – test names keep noun for readability; not a blocker.
