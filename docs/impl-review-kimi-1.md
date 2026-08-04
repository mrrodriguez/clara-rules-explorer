I have a complete picture now. All quality gates pass (server: 149 tests/1063 assertions, lint/format/reflection clean; UI: check/lint clean, 17 unit + 51 e2e green), and the server-side contract closely tracks the plan with strong test coverage. But the critical review surfaced real defects. Here they are, by severity.

---

## Bugs (verified at runtime)

### 1. `resolve-type` NPE on var-resolving symbols — kills the whole analysis build

`serialize.clj:22–32`. The parameter `ns-name` **shadows `clojure.core/ns-name`**, and the `var?` branch calls `(name (ns-name vns))`. Verified in the REPL:

```
(clara.server.tools.graph.serialize/resolve-type 'diverge.a 'Foo)
;; NPE: Cannot invoke "clojure.lang.Named.getName()" because "x" is null
```

`('diverge.a <namespace-obj>)` — symbol-as-function — returns `nil`, then `(name nil)` throws. Any fact-type symbol that resolves to a **var** (not a class) in the production's ns — entirely plausible via sidecar `:clara-rules/insert-types` (e.g. `my.ns/->Foo`) — NPEs the entire `rulebase-analysis`, taking down every endpoint. The test suite covers only the class-resolving branch (`serialize_test.clj:109`); the var branch has never executed. Fix is one token (`(name (clojure.core/ns-name vns))`), plus a test.

### 2. Session-fact `known: true` is hardcoded — and ids provably diverge from the analysis → dead links

`memory.clj:138–148` builds session-fact TypeReferences with `:known true` unconditionally, serializing with a **nil ns context**, while the analysis serializes per-production-ns. The demo data proves the divergence:

| Surface            | name                                     | id                 |
| ------------------ | ---------------------------------------- | ------------------ |
| session fact type  | `clojure.lang.Symbol`                    | `…Symbol-lmnfptrf` |
| analysis fact type | `"clojure.lang.Symbol"` _(string kind!)_ | `…Symbol-2dmvkwj9` |

`FactDetail.svelte` renders an unconditional "View fact type" link to `/fact-types/<session-id>` → **404** for every runtime-derived type (Symbol, PersistentVector — both present in the demo session). Root cause: `analyze.clj:939–950` (`enrich-annotations-from-session`) merges runtime-derived type _name strings_ into `:clara-rules/insert-types`, creating phantom string-kinded fact types (`"…FraudCheck"` vs `…FraudCheck` now both appear in the fact-type list as near-identical rows). That enrichment is pre-existing, but this branch made it visible and linkable without reconciliation. Worse, `memory_test.clj:138` pins the dishonest flag: `"session facts are always known"`. Either compute `known` honestly (session snapshot could consult the analysis known-set, or at minimum the UI should tolerate 404), or normalize string type names to classes at the enrichment boundary. The `explorer-graph-api.md` claim that session indexes use "the same id function as the analysis side" is technically true and practically misleading.

### 3. Unreachable empty state in the ancestors section

`FactTypeSummary.svelte:51` — `{#if factType.ancestors && factType.ancestors.length > 0}` wraps a `ProductionReferenceCategory` whose child snippet ("No ancestors — this type sits at the root of its hierarchy") renders **only when `items` is empty**. Dead code. And it matters: underived keywords, strings, and tuples all have `ancestors: []`, so the common case for the plan's "primary" type kind shows _no hierarchy section at all_ — either delete the snippet or drop the length guard.

---

## Testing gaps

### 4. The two headline features have zero end-to-end coverage

- **No e2e test renders the ancestors section** (ghost styling, known-ancestor links). The three new e2e files cover tooltips, clipboard copy, and fact detail — mostly the _unplanned_ scope (see §8).
- **No e2e test opens the `:match` popover**; `DependencyItemTooltip.e2e.ts` asserts only the trigger button's tooltip text.
- The demo dataset makes these untestable: across all 44 `:match` entries in the scraped data, **0 are hierarchy bridges** (producer ≠ consumer) and **0 carry `via: retract`**, and no demo fact type has a single `known: true` ancestor (GivenDocument shows 15 ghosts). `loan-hierarchy-rules.clj` — the fixture built precisely to the plan's keyword-majority realism spec — is loaded only in server unit tests, not in `demo_setup.clj`. Until the demo session includes it, the features this branch exists to ship are invisible in the running app.
- `SessionFactDetail.e2e.ts` asserts `toHaveURL(/\/fact-types\//)` after clicking the type link but never asserts the target page resolved — a 200-vs-"not found" assertion would have caught §2.

Server-side, by contrast, coverage is genuinely strong: every plan test-case item (1g, 2g, edge-case checklist) maps to a real test, including the cross-field consistency sweep and collision asserts.

---

## Design deviations

### 5. `:match` rows aren't linked, despite the design making them linkable

Design 2b: match values are TypeReferences "so match rows are **directly linkable** with no lookup." `ProductionReferenceLink.svelte` renders them as plain `QualifiedName` text — the `id`s are fetched, serialized, and then thrown away. Either link them or acknowledge the deviation.

### 6. Loud total failure on serialization divergence

The divergence assert works as designed (verified: `"Type serialization divergence: Date serializes as both java.util.Date and symbol[Date]"`), but the consequence is that one bad sidecar symbol → every analysis endpoint 500s. Meanwhile `->known-type-names` silently includes _both_ serializations — the two consumers of the same serialization logic disagree on failure mode. A localized degradation (mark the type, log, continue) would fit an explorer tool better than a total outage.

---

## Standards / maintainability

7. **Three parallel copies of the list-item markup.** `ProductionReferenceLink` stopped using `ReferenceListItem` and inlined its own copy (with `.list-group-item` styles) to add the popover button; `FactTypeReferenceLink`'s ghost branch inlines a third copy. These will drift. Extending `ReferenceListItem` with an actions snippet keeps one source of truth.
8. **`ProductionReferenceCategory` now renders two unrelated entity types**, discriminated by the runtime check `'type' in item` (fragile — `SessionFact` also has a `type` key), and its name no longer reflects its content (violates the svelte-engineering precision-and-semantics rule). A `ReferenceCategory` with an explicit item-kind prop would be cleaner.
9. **`->memoized-ancestors` catches `Throwable`** (core.clj:40) — swallows `OutOfMemoryError`/`StackOverflowError` as "empty ancestors". `Exception` suffices.
10. **Ancestor serialization uses first-registered ns context with no divergence assert** (unlike the type itself), and `type-analysis-map` is a hash map beyond 8 entries, so "first" is hash-order, not load-order — ancestor symbol serialization is arbitrarily chosen. Corner-of-a-corner, but inconsistent with §6's loudness one level down.
11. **Minor doc drift**: `get-analysis-state` docstring mentions only the fact-type reverse index (there are two now); the 404 message in `fact-types/[id]/+page.svelte` now displays the opaque slug+hash id instead of a name.
12. **Scope creep & repo hygiene**: `CopyableTitle`, `Popover.svelte`, `tooltip.ts`, and two of the three e2e files are unrelated to the plan (good work — but unplanned). Five `docs/plan-review-*.md` agent-session artifacts (~950 lines) and a one-line `docs/todo.md` got committed, which sits oddly next to the plan's own "this material is ephemeral" principle.

---

## What's solid

The core architecture — raw-types-internally / kind-explicit-strings-at-the-boundary, upfront known-set, per-ns-context match serialization, id-collision asserts, dep-graph shape preservation, the `:via :retract` fast-follow — is faithfully implemented and well tested. The `serialize-match`/`matching-type-pairs` symmetry and cross-field consistency tests are exactly right.

**Suggested priority:** fix §1 (one line + test), fix §3 (delete or unguard), decide §2 (honest `known` vs. enrichment normalization), then add `loan-hierarchy-rules` to the demo session so §4's e2e tests can exist. Want me to make these changes?
