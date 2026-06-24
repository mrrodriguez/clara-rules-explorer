---
name: svelte-engineering
description: Svelte 5 and SvelteKit engineering standards including reactivity patterns with runes, styling conventions, TypeScript usage, and verification workflows. Use when developing Svelte applications to ensure consistent code quality and maintainability.
---

# Svelte Engineering Standards

This skill defines the engineering standards and best practices for developing Svelte 5 / SvelteKit applications in this repository.

## Description

This skill provides guidance and enforcement of Svelte 5 and SvelteKit development standards, including reactivity patterns, styling conventions, TypeScript usage, and verification workflows. It ensures consistent code quality and maintainability across Svelte-based frontend applications by enforcing modern Svelte practices and proper TypeScript integration.

## Core Mandates

### 0. Environment & Tooling
- **Node 24+:** The project targets Node 24. Ensure all dependencies and scripts are compatible.
- **Package Manager:** Use `pnpm` exclusively. **DO NOT** use `npm` or `yarn`.
- **No Workarounds:** Do not disable ESLint rules or TypeScript checks (e.g., `// @ts-ignore`, `eslint-disable`) without a critical reason and prior discussion.

### 1. Svelte 5 Runes
- **Reactivity:** Use Svelte 5 runes (`$state`, `$derived`, `$derived.by`, `$props`, `$effect`) for all reactivity. Avoid the legacy Svelte 4 `$:` syntax or `writable` stores for local/component state.
- **Props:** Use the `$props()` rune for component properties.
- **Effects:** Use `$effect` sparingly and only for side effects that cannot be handled through derived state or event handlers.
- **Best Practices:** 
  - Prefer `$state` for local component state
  - Use `$derived` for simple computed values.
  - Use `$derived.by` to consolidate complex, multi-step derivations into a single reactive object. This reduces "rune noise" and groups related ephemeral state.
  - Use `$props()` for component inputs with built-in validation
  - Use `$effect` for side effects like API calls, subscriptions, or DOM manipulation
  - Avoid `$effect` in loops or high-frequency rendering scenarios
  - Clean up side effects in `$effect` using cleanup functions

### 2. Styling & Component Architecture
- **Framework:** Use Bootstrap 5 classes for layout and components. Prefer Vanilla CSS (or SCSS) for custom styles. Avoid utility-first frameworks unless explicitly requested.
- **Avoid Utility Bloat:** Do not repeat large strings of Bootstrap (or other utility) classes in multiple places. If a specific class combination represents a reusable UI pattern, extract it into a dedicated Svelte component.
- **Avoid Large `<style>` Blocks (Code Smell):** Huge `<style>` sections within a component are a major red flag. They often indicate that you are violating the project's utility class framework by re-implementing Bootstrap features as custom CSS, or that your component has grown too complex.
  - **Do NOT** copy framework utility classes into a custom scoped CSS class (e.g., creating `.my-label` that just applies `font-weight: bold; color: gray;`).
  - Instead, use Svelte `{#snippet}` blocks to encapsulate structural elements and apply native utility classes directly to those elements. This keeps the DOM readable without bloating the CSS or breaking theme alignment.
- **Composable Layouts:** When extracting components (e.g., Cards, List Items), **do not** include layout-specific classes (e.g., `col-md-6`, `p-4`, `mb-3`) in the component's root element. Instead, let the parent control the positioning and sizing within its own grid or layout context.
- **Data-Driven Redundancy:** If you are repeating a UI structure multiple times with uniform layout (e.g., a row of cards), define a configuration array in the `<script>` block and use an `{#each}` loop. This centralizes the layout standard and makes the template declarative.
- **Semantic Props:** Avoid passing raw CSS classes or style-specific strings as props (e.g., `badgeClass`, `color="primary"`). Instead, pass semantic intent or state (e.g., `active={true}`, `status="success"`) and let the component handle the styling internally. This prevents "style leakage" and ensures consistent design choices.
- **Consolidated Derived State:** Avoid creating long chains of individual `$derived` variables for properties that are logically related and ephemeral. Use `$derived.by` to return a single object containing all necessary properties for the template.
- **Scoped CSS:** Prefer Svelte's scoped `<style>` blocks for component-specific styling. Use semantic CSS selectors where appropriate.
- **Component Composition:** Use Svelte 5 `snippets` (`{#snippet ...}`) for internal markup reuse and `render` props for flexible component composition. Use snippets when the internal structure varies or when giving a semantic name to a local template section.
- **Semantic Naming:** Avoid overly general names for narrow use-cases. 
  - **Feature-Specific:** If a component is specifically designed for a single feature or page (e.g., the dashboard), prefix it accordingly (e.g., `DashboardSummaryCard.svelte`).
  - **Global/Application-Wide:** Use the `Global` prefix for top-level, application-wide layout components (e.g., `GlobalNavbar.svelte`, `GlobalSidebar.svelte`). This clearly distinguishes them from general-purpose UI primitives in the component library.
  - **Precision & Semantics:** Ensure names accurately reflect the entity they represent. Distinguish clearly between **Types** and **Instances**. For example, use `FactTypeReferenceLink` for a link to a fact type definition, rather than `FactReferenceLink`, which might imply a link to a specific fact instance.

### 3. State Management
- **Global State:** Use the `src/lib/state/appState.svelte.ts` singleton for global UI state (theme, sidebar, etc.).
- **Local State:** Use Svelte runes (`$state`, `$derived`, etc.) within individual components for localized state.
- **Separation of Concerns:** Distinguish between **Reactive State** (`.svelte.ts` files using `$state`) and **Static Configuration** (`.ts` files with plain objects/constants).

### 4. TypeScript & Reactivity Patterns
- **Strict Typing:** Maintain `strict: true` in `tsconfig.json`. Every component and utility must be properly typed. Avoid `any` at all costs.
- **Separation of State and Configuration:** Distinguish between **Reactive State** and **Static Configuration**.
  - **Reactive State (`.svelte.ts`)**: Variables that change over time and trigger UI updates (e.g., `uiTheme`, `isSidebarOpen`). These belong in state classes using `$state`.
  - **Static Configuration (`.ts`)**: Constant objects, lookup tables, or metadata that never change (e.g., `NAV_CONFIG`, `CONTEXTUAL_MENU_CONFIG`). These MUST be moved to a `src/lib/constants.ts` or similar file to keep state files lean.
  - **UI Types:** Place UI-specific types (e.g., `ContextualMenuType`) in `src/lib/types/ui.ts` to prevent circular dependencies between state and constants.
- **Exported Prop Types:** Export an interface for component props (e.g., `export interface MyComponentProps`) so that parent components can use it for type-safe configuration arrays.
- **Reactive Configs:** When defining configuration arrays that depend on reactive props (like `data`), wrap the array in a `$derived` rune. This avoids "state referenced locally" warnings and ensures the UI stays in sync with incoming data.
  - *Correct:* `const cards = $derived<CardProps[]>([...])`
  - *Incorrect:* `const cards: CardProps[] = [...]` (Only captures initial value)
- **Best Practices:**
  - Use TypeScript's built-in Svelte types for component events and slots.
  - Define types for component state and props early.
  - Use generics for reusable component patterns.

### 5. Verification Workflow (MANDATORY)
After making any change to the UI code, you MUST run the following commands in the `ui/` directory and ensure they pass with **zero errors and zero warnings**:

```bash
cd ui && pnpm run format && pnpm run check && pnpm run lint
```

- **`pnpm run format`**: Formats the code using Prettier to ensure style consistency.
- **`pnpm run check`**: Runs `svelte-check` for TypeScript and template validation.
- **`pnpm run lint`**: Runs a **whitelisted** scan of `src/`, `static/`, and root configs via Prettier and ESLint.

If either command fails or produces warnings, you MUST fix them before completing the task.

For changes with UI impact (layout, styling, user interactions), also run the E2E tests:

```bash
cd ui && pnpm run test:e2e
```

- **Best Practices:**
  - Run `pnpm run check` regularly during development to catch issues early
  - Run `pnpm run lint` to ensure code style consistency
  - Fix all TypeScript errors before merging code
  - Address all ESLint warnings to maintain code quality

### 6. Documentation & Learning (Personal)
The user is learning Svelte 5 by drawing analogies to React. This journey is documented in `ui/docs/svelte-overview.md`.
- **Proactive Documentation:** When you encounter or implement a Svelte concept that is not yet covered (or could be better explained) in `ui/docs/svelte-overview.md`, you MUST propose an update to that file to capture the learning for the user.
- **Skill Maintenance:** You MUST treat this `SKILL.md` file as a living document. Whenever you establish a new best practice or architectural rule during development, you must update this file to ensure the standard is captured for future turns.
- **Analogy Consistency:** Maintain the "React Developer" perspective when updating the overview.

### 7. Performance & Optimization
- **Component Rendering:** Avoid unnecessary re-renders by properly using `$derived` and `$state` values
- **Event Handling:** Use Svelte's built-in event delegation for better performance
- **Data Fetching:** Prefer SvelteKit's server-side rendering and data fetching capabilities for improved performance
- **Memory Management:** Clean up subscriptions and effects properly to avoid memory leaks
- **Bundle Optimization:** Minimize the use of heavy dependencies and consider code splitting for large applications

### 8. SvelteKit Specific Practices
- **Routing:** Use SvelteKit's file-system based routing consistently and take advantage of dynamic routes
- **Server-Side Rendering:** Utilize SvelteKit's SSR capabilities for better performance and SEO
- **API Routes:** Implement API routes in `src/routes/api/` with proper error handling
- **Middleware:** Use SvelteKit's middleware for cross-cutting concerns like authentication
- **Static Assets:** Place static assets in `static/` directory and use the `asset` helper for dynamic asset references

### 9. Testing
- **Unit Tests:** Write unit tests for components using Vitest and the Svelte testing library.
- **End-to-End Tests:** Use Playwright for comprehensive end-to-end testing. E2E tests are located in `tests/` and should match the `*.e2e.ts` file pattern.
- **Test Coverage:** Aim for high test coverage, especially for business logic and complex components.
- **Mocking:** Use appropriate mocking strategies for API calls and external dependencies.

### 10. Accessibility
- **Semantic HTML:** Use proper semantic HTML elements for better accessibility
- **ARIA Attributes:** Implement appropriate ARIA attributes where needed
- **Keyboard Navigation:** Ensure components are fully keyboard accessible
- **Screen Reader Support:** Test with screen readers to ensure proper reading order and labeling

### 11. Security
- **XSS Prevention:** Svelte's default templating prevents XSS, but be careful with user-provided content
- **Input Sanitization:** Sanitize user input when processing it
- **Security Headers:** Configure appropriate security headers in SvelteKit
- **Authentication:** Implement proper authentication and authorization patterns

### 12. Deployment & CI/CD
- **Build Optimization:** Use SvelteKit's built-in optimizations for production builds
- **Environment Variables:** Use proper environment variables for different deployment environments
- **Monitoring:** Implement basic application monitoring and error tracking
- **Versioning:** Follow semantic versioning for package releases
- **Deployment:** Use appropriate adapters for your target deployment environment

## Project Structure (UI)

- `src/lib/components/`: Shared components organized by domain subdirectories (see below).
- `src/lib/state/`: Centralized reactive state using `.svelte.ts` files.
- `src/routes/`: SvelteKit's file-system based router.

### Component Organization & Colocation

Components in `src/lib/components/` are organized into **domain subdirectories** — NOT a flat list. Each subdirectory represents a distinct logical boundary:

```
$lib/components/
├── ui/          # Truly global UI primitives (Badge, CodeBlock, Breadcrumbs, etc.)
├── shell/       # Application layout chrome (GlobalNavbar, GlobalSidebar, etc.)
├── nav/         # Navigation patterns (FilterableNavList, NavigationListItem, etc.)
└── rulebase/    # Shared rulebase domain abstractions (LhsCondition, DependencyRow, etc.)
```

**Route Colocation (SvelteKit Convention):** Components used by **only one route group** MUST be colocated in that route's directory, NOT in `$lib/components/`. This follows SvelteKit's explicit recommendation:

> "Only put components in `$lib/components` if they're shared across multiple routes."

```
src/routes/
├── rules/
│   ├── RuleList.svelte          ← colocated (only used by rules/+layout)
│   └── [id]/RuleSummary.svelte  ← colocated (only used within rules/[id]/)
├── queries/
│   ├── QueryList.svelte         ← colocated
│   └── [id]/QuerySummary.svelte ← colocated
├── fact-types/
│   ├── FactTypeList.svelte      ← colocated
│   └── [id]/FactTypeSummary.svelte ← colocated
├── session/
│   ├── SessionNav.svelte        ← colocated
│   ├── fact-types/[typeName]/
│   │   ├── FactGroup.svelte     ← colocated
│   │   └── SessionSectionHeader.svelte ← colocated
│   └── facts/[id]/FactDetail.svelte ← colocated
└── DashboardSummaryCard.svelte   ← colocated (only used by home +page)
```

**Import Paths:**
- Within `$lib/components/`, use full `$lib/components/<subdir>/Component.svelte` paths (never relative `./` imports between components).
- Route files import colocated components with `./` relative paths.
- Route files import shared components with `$lib/components/<subdir>/` paths.

**Naming Conventions:**
- New subdirectories should represent clear domain boundaries, not arbitrary groupings.
- A component belongs in `$lib/components/` only if it is shared by **two or more route groups**.
- When in doubt, colocate with the route — it's easier to promote to `$lib/components/` later than to untangle incorrectly shared abstractions.