## Phase 3 (Future) — UI Integration

Once the server API is extended, the UI can use the new fields:

- **Fact-type detail view:** Render the `:ancestors` chain (already
  hierarchy-ordered by the server).  Types with `known: true` hyperlink
  via their `id`; ghost types (`known: false`) render as plain text —
  no link element at all.
- **Grouping with nullable `:ns`:** list views group by server-provided
  `:ns`, which is `null` for string/tuple types — use an explicit
  fallback group label (e.g. "(no namespace)") rather than letting
  `null` keys scatter through grouping/sorting logic.
- **SvelteKit `load` functions** pass the `[id]` route param through
  verbatim — no `decodeURIComponent`, no `fromRouteId`, no
  manipulation; the param is already the exact API id.
- **URL-safety is solved by `:id`:** all fact-type, rule, and query
  links use the server-issued id directly — no `encodeURIComponent`, no
  `toRouteId`/`fromRouteId` (deleted), no last-dot heuristic anywhere.
  Ancestor entries with `known: true` hyperlink via their embedded
  `id`; upstream/downstream entries via theirs.  Kind-explicit
  spellings remain a display clarity win — a keyword type reads as
  `:my.ns/child` everywhere it is *shown*, while URLs stay clean.
- **Every type link consumes `TypeReference` directly.**  Today
  `FactTypeReferenceLink`, `ConditionFactType`, and
  `DynamicCallsiteList` hyperlink bare type-name strings via
  `factPath(name)`; after the contract change they receive
  `{name, id, known}` and link `known: true` entries via the embedded
  `id` — no lookup table, no name parsing.  `FactTypeSummary`'s
  client-side `toRef` mapping of `used-by-rules` name strings is
  deleted; usage lists arrive as `ProductionDep`.
- **Rule detail view:** In the upstream/downstream sections, show each dep
  entry with the `:match` details inline (e.g., "Rule X produces
  `MarkerRecord` → satisfies `IScanMarker`").
- **Graph visualization:** Edges could carry type-bridge labels.

This phase is scoped separately and not detailed here.

### UI Impact Summary (for scoping Phase 3)

Breaking (must land together):

- Fact-type `:name` format changes to kind-explicit forms everywhere it
  is displayed, and every linkable type reference becomes a structured
  `TypeReference` object (`:lhs-types`/`:insert-types`/`:retract-types`,
  condition `:type`, callsite `:resolved-types`/`:fact-type`, session
  fact `:type`); fact-type usage lists become `[ProductionDep]`.
- Route-id migration: all URL construction uses `:id`;
  `toRouteId`/`fromRouteId`/`splitQualifiedName` deleted; SvelteKit
  `load` functions pass `[id]` params through verbatim; `entries()`
  generators and `bin/scrape-demo-data.js` use server-issued ids;
  demo data regenerated (`pnpm scrape:demo`); all type-link components
  consume `TypeReference`/`ProductionDep` shapes directly (no
  name→id lookup table anywhere).
- Grouping switches to server-provided `:ns` (nullable — needs a
  fallback group label).

Additive (can trail):

- `:ancestors` section on the fact-type detail view (hierarchy-ordered,
  `known` entries linked via `id`, ghosts as plain text).
- `:match` rows on rule/query upstream/downstream entries.
- `:id` fields on list payloads (needed by the breaking link changes —
  effectively lands with them).

---

