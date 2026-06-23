# UI Application Architecture

This document describes the current architecture of the Clara Rules explorer UI — a Svelte 5 / SvelteKit application. It covers the application shell, routing structure, component organization, state management, and the API connective points to the [Clojure Explorer Graph API](../../docs/explorer-graph-api.md).

---

## Application Shell

The root layout (`src/routes/+layout.svelte`) provides the global chrome:

```
┌──────────────────────────────────────────────┐
│  GlobalNavbar                                │
├────────┬─────────────────────────────────────┤
│        │                                     │
│ Global │  <main> content area                │
│ Sidebar│  (route outlet via {@render        │
│        │   children()})                       │
│ (+     │                                     │
│ Flyout)│                                     │
│        │                                     │
└────────┴─────────────────────────────────────┘
```

**GlobalNavbar** — top bar with app title, theme toggle, and sidebar collapse control.

**GlobalSidebar** — left sidebar with hardcoded main navigation items:

| Label | Route | Icon |
|-------|-------|------|
| Dashboard | `/` | `bi-speedometer2` |
| Rules | `/rules` | `bi-list-check` |
| Queries | `/queries` | `bi-search` |
| Fact Types | `/fact-types` | `bi-database-fill` |
| Session | `/session` | `bi-play-circle-fill` |

The sidebar supports a mini mode (collapsed to icon-only at 64px) and full mode (200px).

**GlobalSidebarFlyout** — toggled from the sidebar, slides out adjacent to expose contextual navigation (upstream/downstream dependencies, input/insert/retract fact types). Contextual menu configuration is defined in `$lib/constants.ts` (`CONTEXTUAL_MENU_CONFIG`) and wired through `appState.contextualNav`.

**Layout constraints:** Bootstrap 5 flexbox. Sidebar width transitions are CSS-animated. The `<main>` area fills remaining width with `overflow-auto`.

---

## Routing Structure

Routes follow SvelteKit's file-system based routing. The general pattern for list domains (rules, queries, fact-types) is a **two-column layout**: a `+layout.svelte` provides a left sidebar list and a `<slot>` for the right content area (preview or detail).

```
/                           Dashboard (+page.svelte)
├── rules/                  Rules domain
│   ├── +layout.svelte      Two-column: RuleList (left) + preview (right)
│   ├── +layout.ts          Loader: fetches rules list
│   ├── +page.svelte        Default view: "Select a rule" hint
│   ├── RuleList.svelte     Left column — list of rule names
│   └── [id]/               Rule detail
│       ├── +layout.ts      Loader: fetches specific rule
│       ├── +page.svelte    Summary preview
│       ├── RuleSummary.svelte
│       └── full/           Full detail view
│           ├── +page@.svelte  Escapes parent layout
│           └── +page.ts
├── queries/                Queries domain (same pattern as rules)
│   ├── +layout.svelte
│   ├── +layout.ts
│   ├── +page.svelte
│   ├── QueryList.svelte
│   └── [id]/
│       ├── +layout.ts
│       ├── +page.svelte
│       ├── QuerySummary.svelte
│       └── full/
│           ├── +page@.svelte
│           └── +page.ts
├── fact-types/             Fact Types domain
│   ├── +layout.svelte
│   ├── +layout.ts
│   ├── +page.svelte
│   ├── FactTypeList.svelte
│   └── [id]/
│       ├── +page.svelte
│       ├── +page.ts
│       └── FactTypeSummary.svelte
└── session/                Session memory explorer
    ├── +layout.svelte      Split-pane: SessionNav (left) + outlet (right)
    ├── +page.svelte        Default: "Select a fact type" hint
    ├── SessionNav.svelte   Left column — fact types grouped as "Memory"
    ├── fact-types/[typeName]/
    │   ├── +page.svelte    Instances of a fact type
    │   ├── FactGroup.svelte
    │   └── SessionSectionHeader.svelte
    └── facts/[id]/
        ├── +page.svelte    Individual fact detail
        └── FactDetail.svelte
```

**SvelteKit layout escape (`@`):** The `full/` pages under `rules/[id]/full/` and `queries/[id]/full/` use `+page@.svelte` to break out of the parent `+layout.svelte` and render the full summary as a standalone page (using the root layout only).

---

## Component Organization

Components follow domain colocation principles:

| Directory | Purpose | Examples |
|-----------|---------|----------|
| `$lib/components/ui/` | Global UI primitives | `Badge`, `CodeBlock`, `Breadcrumbs`, `CopyButton`, `EmptySelectionHint`, `QualifiedName`, `CollapseToggleButton` |
| `$lib/components/shell/` | Application chrome | `GlobalNavbar`, `GlobalSidebar`, `GlobalSidebarFlyout`, `SidebarItem` |
| `$lib/components/nav/` | Navigation patterns | `NavigationListItem`, `ReferenceListItem`, `FilterableNavList` |
| `$lib/components/rulebase/` | Shared rulebase domain abstractions | `LhsCondition`, `DependencyRow`, `SessionActivityBlock`, `SessionActivityRow`, `ProductionReferenceLink`, `FactTypeReferenceLink`, `SourceSinkIndicators`, `RulebaseComponentTypeBadge`, `ProductionReferenceCategory`, etc. |
| Route colocated | Single-route components | `routes/rules/RuleList.svelte`, `routes/queries/QueryList.svelte`, `routes/session/SessionNav.svelte`, `routes/session/facts/[id]/FactDetail.svelte`, etc. |

**Import conventions:**

- Within `$lib/components/`, use full `$lib/components/<subdir>/Component.svelte` paths (no relative `./` imports).
- Route files import colocated components with `./` relative paths.
- Route files import shared components with `$lib/components/<subdir>/` paths.

---

## State Management

### Global Reactive State

`$lib/state/appState.svelte.ts` (`.svelte.ts` file with `$state` runes) holds:

- `uiTheme` — light/dark theme toggle
- `isSidebarOpen` / `isSidebarMini` — sidebar visibility and collapsed state
- `activeContextualMenu` — which flyout menu is open
- `contextualNav` — reactive navigation items (upstream/downstream rules, input/insert/retract fact types) populated by detail page loaders

### Static Configuration

`$lib/constants.ts` — immutable configuration objects:

- `CONTEXTUAL_MENU_CONFIG` — defines the sidebar flyout menu items (upstream, downstream, input types, insert types, retract types)

### Route-Level Data Loading

SvelteKit loader functions (`+page.ts`, `+layout.ts`) fetch data via the API client and pass it as page/layout data. This keeps data fetching at the route boundary.

---

## API Connective Points

The UI consumes the [Clara Rules Explorer Graph API](../../docs/explorer-graph-api.md) through `$lib/api.ts`. All endpoints are proxied via Vite (`/v1` → backend server).

### Phase 1 — Rulebase Analysis (Static)

These endpoints analyze the compiled Rete network and do not require a running session with facts.

| API Endpoint | API Client Function | Consumer(s) |
|--------------|-------------------|-------------|
| `GET /v1/rulebase-summary` | `fetchRulebaseSummary()` | Dashboard (`+page.svelte`) — renders `RulebaseSummary` (rule/query/fact-type counts) |
| `GET /v1/rules` | `fetchRulesList()` | `rules/+layout.ts` → `RuleList.svelte` — renders lightweight `RuleListItem[]` |
| `GET /v1/rules/:fq-name` | `fetchRule()` | `rules/[id]/+layout.ts` → `RuleSummary.svelte` — full rule detail (LHS, RHS, props, deps) |
| `GET /v1/queries` | `fetchQueriesList()` | `queries/+layout.ts` → `QueryList.svelte` — renders lightweight `QueryListItem[]` |
| `GET /v1/queries/:fq-name` | `fetchQuery()` | `queries/[id]/+layout.ts` → `QuerySummary.svelte` — full query detail (LHS, params, deps) |
| `GET /v1/fact-types` | `fetchFactTypesList()` | `fact-types/+layout.ts` → `FactTypeList.svelte` — renders `FactTypeSummary[]` |
| `GET /v1/fact-types/:fq-name` | `fetchFactType()` | `fact-types/[id]/+page.ts` → `FactTypeSummary.svelte` — per-type usage (used-by, inserted-by rules/queries) |

**FQ-name encoding:** The `toUrlId()` utility in `$lib/utils.ts` converts `ns/name` to `ns.name` for URL segments. The backend accepts dot-separated FQ names and resolves the final dot as the `/` separator.

### Phase 2 — Session State (Working Memory)

These endpoints require a running session with inserted facts and return point-in-time snapshots.

| API Endpoint | API Client Function | Consumer(s) |
|--------------|-------------------|-------------|
| `GET /v1/session/fact-types` | `fetchSessionFactTypes()` | `SessionNav.svelte` — renders memory navigation with instance counts |
| `GET /v1/session/fact-types/:fq-name` | `fetchSessionFactTypeInstances()` | `session/fact-types/[typeName]/+page.svelte` — fact instances grouped by origin |
| `GET /v1/session/facts/:id` | `fetchSessionFactDetail()` | `session/facts/[id]/+page.svelte` → `FactDetail.svelte` — raw data, origin, impact |
| `GET /v1/session/rules/:fq-name` | `fetchSessionRuleActivity()` | `rules/[id]/full/` — matches + inserted facts for a rule |
| `GET /v1/session/queries/:fq-name` | `fetchSessionQueryActivity()` | `queries/[id]/full/` — current query matches |

### Cross-Domain Linking

The Phase 1 (static) and Phase 2 (session) views are cross-linked:

- **Rule/Query full view** → fetches session activity on-demand to show which facts matched or were inserted.
- **Session fact detail** → links to the inserting rule's Phase 1 summary via `ProductionReferenceLink`.
- **Fact type summary** → links to the session memory view for that type.

---

## Type Definitions

API response shapes are defined in `$lib/types/api.ts`. Key interfaces:

- **Phase 1:** `RulebaseSummary`, `RuleListItem`, `QueryListItem`, `RuleSummary`, `QuerySummary`, `FactTypeSummary`, `ProductionReference`
- **Phase 2:** `SessionFactTypesResponse`, `SessionFactTypeInfo`, `SessionFact`, `SessionFactGroup`, `SessionFactTypeInstancesResponse`, `SessionProductionActivityResponse`

UI-specific types are in `$lib/types/ui.ts` (e.g., `ContextualMenuType`).

**Deprecated:** The monolithic `Analysis` interface and `fetchAnalysis()` function are no longer used. Pages consume streamlined per-resource endpoints instead.

---

## Verification

After any UI change, run (from `ui/`):

```bash
pnpm run format && pnpm run check && pnpm run lint
```

All three commands must pass with zero errors and zero warnings. For changes with UI impact, also run:

```bash
pnpm run test:e2e
```
