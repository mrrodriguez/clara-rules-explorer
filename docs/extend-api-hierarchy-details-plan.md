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

## System Context

### Where `ancestors-fn` lives

```clojure
;; In build-dep-graph (core.clj):
(let [{:keys [ancestors-fn]} (meta get-alphas-fn)] ...)
```

`get-alphas-fn` is a component of the compiled rulebase.  Its metadata carries
`:ancestors-fn` and `:fact-type-fn`:

- The meta `:ancestors-fn` is a *wrapped* fn that returns an empty set for
  internal system facts (`ISystemFact`) and otherwise delegates to the
  session's ancestors-fn.
- The session's ancestors-fn **defaults to `clojure.core/ancestors`** — it is
  non-nil for every real session (`clara.rules.compiler/create-ancestors-fn`).
  For Java classes, `clojure.core/ancestors` returns the *transitive closure*
  of all superclasses **and all interfaces**.  For a `defrecord` fact type
  that is ~10–20 entries, almost all of them ghosts (`java.lang.Object`,
  `clojure.lang.*` interfaces, `Serializable`).  This noise is why the
  `known` flag (Phase 1) is central to the design.
- A hand-built rulebase (unit tests) may lack the meta entirely; only then is
  `ancestors-fn` actually nil.  Precedent for the defensive fallback exists in
  `analyze.clj/build-fallback-type-filter`:
  `(or (-> rulebase :get-alphas-fn meta :ancestors-fn) ancestors)`.

### How `downstream?` works today

```clojure
(defn- downstream? [ancestors-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (and ancestors-fn
           (contains? (set (ancestors-fn inserter-type)) reader-type))))
```

Two cases: (a) direct type match, or (b) the inserter's type has the reader's
type among its ancestors.  This boolean check is used to build the dep-graph
but the *specific pair* that caused a match is not retained.  It also rebuilds
`(set (ancestors-fn ...))` on every call inside an O(rules² × types²) loop —
a memoization target (Phase 1a).

### Current data structures

**ProductionDep** (on `:upstream` / `:downstream`):
```json
{"name": "my.ns/rule-x", "ns": "my.ns", "type": "rule"}
```

**FactTypeSummary** (on `/v1/fact-types` and `:fact-types` in analysis):
```json
{
  "name": "my.ns.MarkerRecord",
  "used-by-rules": ["my.ns/consume-marker"],
  "used-by-queries": [],
  "inserted-by-rules": ["my.ns/insert-marker"],
  "retracted-by-rules": []
}
```

### Where raw vs. serialized types live

- `build-dep-graph`'s internal `type-analysis-map` holds **raw** types
  (`java.lang.Class`, keywords, occasionally symbols):
  `{:consumed-types [...] :produced-types [...]}` per production name.
  `:produced-types` is `(into insert-types retract-types)` — note it
  includes retracts (see "Edge Cases").
- `production-summary` **serializes** types to strings via
  `serialize/serialize-fact-type`, partial'd with **that production's own
  ns-name** (core.clj: `serialize-fact-type (partial
  serialize/serialize-fact-type p-ns-name)`).  The ns-name matters for
  resolving unresolved symbols; queries have no `:ns-name` and use the
  `get-production-ns-name-sym` derivation.
- `build-fact-type-summary-map` consumes those serialized summaries — its
  keys and values are strings.  Raw types are not available at that layer
  and must be passed in (Phase 1a).

### Heterogeneous type values & determinism (design invariant)

Fact types are **not** always Classes.  They can be keywords (with
`derive` hierarchies), strings, symbols, vectors ("tuple" types via a
custom `fact-type-fn`), or arbitrary objects.  Likewise a custom
`ancestors-fn` may return a mix of kinds (e.g. a keyword type whose
ancestors include a string).  (`derive` itself only accepts ns-qualified
keywords/symbols/Classes, so mixed-kind hierarchies arise only through a
custom `ancestors-fn`.)

**Invariant: all logic on raw values; strings only at the display
boundary.**

- Matching (`downstream?`, `matching-type-pairs`), dedup (`distinct`),
  memoization keys, and `known`-set membership all operate on **raw**
  values, where `=` and set containment are kind-correct: `:foo` ≠
  `"foo"` ≠ `'foo`.  No false edges and no cross-kind dedup.  This
  invariant is what makes heterogeneous types safe; every phase below
  must preserve it.
- Serialization (`resolve-type`) is the *only* raw→string step, applied
  at the very end for display and API output.
- **All ordering is applied post-serialization, on strings.**  Raw kinds
  do not share a `Comparable` contract (`sort-by` on mixed
  Class/keyword/vector values throws `ClassCastException`).

### Kind-explicit serialization (the only heterogeneity handling)

Beyond the raw-logic/string-display invariant above, the design has **no**
kind-specific handling anywhere.  The single point of sensitivity is the
serialization function, which is extended so the representation is
self-describing in JSON — then kinds need no further attention.

`serialize/resolve-type` gains kind-explicit branches:

| Raw kind | Serialized form | Example | Change? |
|---|---|---|---|
| Class | `.getName` | `my.ns.MarkerRecord` | unchanged |
| keyword | `(str x)` — colon preserved | `:my.ns/child` | **changed** (colon was stripped) |
| string | `(pr-str x)` — quotes visible | `"foo"` | **changed** (was bare) |
| symbol, unresolved | `symbol[<value>]` | `symbol[my.ns/foo]` | **changed** (was bare) |
| symbol, resolved via ns | resolved class/var name | `my.ns.MarkerRecord` | unchanged |
| vector / sequential (tuple) | `(pr-str x)` | `[:a 1]` | effectively unchanged |
| arbitrary object | `(str x)` (`.toString()`) | — | unchanged |

Why this is worth the change:

- **Collisions effectively eliminated.**  A keyword, string, and symbol
  can never serialize identically, so the `known` check can't conflate
  kinds and no collision-handling machinery is needed anywhere else.
  Raw values are already deduped by sets; serialized output inherits that.
- **Self-explanatory API.**  A consumer reading `"ancestors"` or
  `"match"` can tell the kind of each type at a glance — keyword types
  are the common case in our rulesets, so the visible colon is a clarity
  win in the UI as well.

API stability posture: **the API is alpha and shaped at-will** — there are
no backward-compatibility constraints.  The rename of keyword/string
fact-type names (colon, quotes) is intentional; class names are unchanged.
The serialization table above must be documented in
`explorer-graph-api.md`.

### Route IDs (`:id`) — the URL strategy, for fact types AND productions

Kind-explicit names are not URI-friendly (`:my.ns/child`, `"foo"`,
`[:a 1]`), and production names are worse than they look: Clojure rule
and query names freely contain `?`, `!`, `*`, `+` and friends
(`my.ns/verify-docs?` — `?` in URLs is a demonstrated problem in the
UI today).  Percent-encoding produces unreadable URLs and depends on
the UI's fragile last-dot `toRouteId`/`fromRouteId` heuristic.

**Decision: every fact type, rule, and query gains a server-issued,
deterministic `:id` used for ALL URL linkage — no fallbacks, no legacy
name-based resolution.**  The UI never encodes, decodes, or parses a
name again; `fq-name-from-param` (server) and `toRouteId`/
`fromRouteId` (UI) are deleted outright.

One uniform function, no kind dispatch:

```
id(s)  = slug(s) + "-" + hash8(s)
slug(s) = every char outside [A-Za-z0-9.-] replaced by "-",
          runs of "-" collapsed, leading/trailing "-" trimmed,
          truncated to 60 chars (deterministic)
hash8(s) = first 8 base36 chars of SHA-1(s)     ;; ~41 bits
```

Applied to the canonical serialized name (fact types) or the
production `:name` string (rules/queries):

| Name | `:id` |
|---|---|
| `my.ns.MarkerRecord` (class) | `my.ns.MarkerRecord-a1b2c3d4` |
| `:my.ns/child` (keyword) | `my.ns.child-x7k9p2m4` |
| `"foo"` (string) | `foo-z9y8x7q2` |
| `[:loan/status "verified"]` (tuple) | `loan.status.verified-k4x9p2m8` |
| `my.ns/verify-docs?` (rule) | `my.ns.verify-docs-q2w8e5r4` |
| `my.ns/app-by-id` (query) | `my.ns.app-by-id-n3m7b5v9` |

Design rules:

- **Uniform — no special cases.**  Classes get the same slug+hash
  treatment as every other kind.  One code path, one interface, no
  kind dispatch in the id function.
- **The slug is the FULL sanitized name, not a short prefix.**
  `:my.nsed/keyword-name` → `my.nsed.keyword-name-<hash>` keeps full
  fidelity, so entries don't collapse onto shared namespace prefixes.
  Sanitization can be aggressively lossy (any char outside
  `[A-Za-z0-9.-]` → `-`) because readability is the slug's only job —
  uniqueness is the hash's job.  The 60-char cap only affects
  pathological names; the hash still distinguishes them.
- **8 base36 chars (~41 bits) of hash** — birthday-safe to ~1.5M
  entries, three orders of magnitude above our 3k-rule scale (~10⁻⁹
  collision chance per analysis).  Longer hashes buy nothing and hurt
  readability.
- **Stability across analysis runs comes from determinism, not from
  the index.**  `id = f(name)` depends only on the name, so re-running
  the analysis with new types added never changes existing ids — old
  URLs keep working.  The only cross-run risk is a *new* name
  colliding with an existing id; see the next rule for how that surfaces.
- **Uniqueness is asserted per analysis.**  The reverse index (id →
  name) is built once per `rulebase-analysis` and cached with it.  If
  two names ever map to the same id, index construction throws — a
  loud build-time alarm, never silent link corruption.  Session
  reloads rebuild the cache and the index; existing ids are
  regenerated identically.
- **Two separate indexes:** fact types and productions live in
  different namespaces of ids (their routes are per-resource:
  `/v1/rules/:id`, `/v1/queries/:id`, `/v1/fact-types/:id`), so a
  cross-index collision is meaningless.  Session endpoints
  (`/v1/session/rules/:id`, `/v1/session/queries/:id`,
  `/v1/session/fact-types/:id`) resolve ids too — but session data
  lives in the snapshot, not the analysis.  To avoid coupling session
  handlers to the analysis cache, each cached snapshot builds its own
  small id → name index at snapshot-cache time (same id function over
  the snapshot's serialized names), and session handlers resolve
  id → name → snapshot entry.  Note: snapshot type-name serialization
  must use the same kind-explicit forms for ids to align with the
  analysis-side ids — verify at implementation time.
- **Resolution is id-only.**  Handlers look up `:id` in the reverse
  index and 404 otherwise.  No canonical-name fallback, no
  `fq-name-from-param`, no last-dot heuristic anywhere.
- **Id generation is memoized** (name → id) for the duration of the
  analysis build: SHA-1 + slug work would otherwise repeat per
  occurrence — the same production name appears in thousands of
  `ProductionDep` entries across rule/query summaries.
- **Ancestor entries carry `id`** (see 1b): `known: true` ancestors
  are directly linkable without the UI building a name→id map.  Ghost
  (`known: false`) entries carry an id **for shape uniformity only** —
  the UI must render them as plain text, never as a (disabled or live)
  link element.  The ids are not a supported linking surface.  Cost is
  negligible: id generation is memoized and the unique ghost names
  (`java.lang.Object`, `clojure.lang.*` interfaces) are few and shared
  across all fact types — dozens of hashes total, not thousands.
- **`ProductionDep` entries carry `id`** (upstream/downstream on
  rule/query details, and `inserted-from`/`used-by` on session facts)
  so every production hyperlink in the UI is id-based.
- **`:match` stays display-slim** (strings only, Phase 2).  If linking
  from match rows is wanted later, the UI can use the fact-types list
  endpoint (which includes `id`) as a name→id lookup table — or we
  embed `producer-type-id`/`consumer-type-id` then.  No decision forced.

Router behavior (verified against the project's reitit): an unencoded
literal `/` splits segments and 404s; the ids above contain only
`[A-Za-z0-9.-]`, so they always route as plain single segments.

`api_test.clj` currently exercises only plain class names through the
router — Phase 1 adds router-level tests for id-based lookups across
rules, queries, and fact types, and removes tests that exercise
`fq-name-from-param`.

Remaining accepted limitation (no handling, documentation only):
**arbitrary objects** without a stable `toString`/`print-method` (default
includes the identity hash) produce non-deterministic output across runs.
Custom `fact-type-fn` type values must print stably.  Our types are in
practice Classes, keywords, and vector tuples of keywords/basic literals
— all covered above.

Memoization on heterogeneous keys is safe: `memoize` keys on `=`/hash —
value-equal for keywords, strings, Classes, vectors; identity-equal
arbitrary objects merely miss the cache (harmless).  Clara's own
`wrapped-ancestors-fn` calls `(isa? fact-type ISystemFact)`, which
returns false (no throw) for non-class tags.

---

## Phase 1 — Fact Type Hierarchy Representation

**Goal:** Every fact-type summary shows its ancestors with enough context to
distinguish "real" types from ghost types (ancestors that only appear in
the hierarchy, never in a production's LHS/insert/retract).

### 1a. Thread raw types and a memoized ancestors-fn through the analysis

`build-fact-type-summary-map` works on serialized strings, so `ancestors-fn`
cannot be applied at that layer.  Raw types are threaded through from
`rulebase-analysis`:

1. **Hoist the type-analysis-map out of `build-dep-graph`.**  Compute
   `type-analysis-map` once in `rulebase-analysis` and pass it to
   `build-dep-graph`.  Entries gain the production's ns-name:
   `{:consumed-types [...] :produced-types [...] :ns-name <sym>}`, using
   the same `get-production-ns-name-sym` derivation `production-summary`
   uses (queries have no `:ns-name`).  This is a pure refactor — same
   data, computed in the caller instead of a `letfn`.  (Phase 2 reuses it
   to compute `:match` pairs at serialization time.)
2. **Memoize the ancestor *set*, once, in `rulebase-analysis`:**
   ```clojure
   (defn- ->memoized-ancestors
     "Returns a memoized fn of raw-type → set of ancestor raw types.
     Never returns nil; tolerant of ancestors-fn returning nil."
     [ancestors-fn]
     (memoize (fn [t] (set (ancestors-fn t)))))
   ```
   Clara's own memoization lives inside `fact-type->roots`, **not** on the
   meta-carried fn, so this memoization is not redundant.  One memoized fn
   serves all three consumers: `downstream?` inside `build-dep-graph`
   (simplified to `(contains? (ancestors-set-fn t) reader-type)` in the
   same change), Phase 1's `:ancestors` enrichment, and Phase 2's
   `matching-type-pairs`.  Memoizing the *set* (not the seq) also removes
   the per-call `(set …)` allocation in today's `downstream?`.
3. **Build a serialized-name → ancestors-strings index** in
   `rulebase-analysis`:
   - **Per production**, pair each raw type in its `:consumed-types` ∪
     `:produced-types` with its serialized name, using **that
     production's own ns-name** — exactly as `production-summary` does.
     Serializing with `nil` ns-name would diverge for unresolved symbols
     (index lookups would silently miss → `:ancestors` absent), so the
     index must be built in per-production ns context.  Merge all
     productions into one `{serialized-name [serialized-ancestor …]}`
     map, merging entries across productions.
   - **Divergence is asserted, not silently merged.**  The same raw
     type must serialize to the same string under every production's
     ns context; if two productions ever disagree, index construction
     throws with a clear message.  Divergence is only possible for
     *unresolved symbols* (Classes, keywords, strings, tuples serialize
     ns-independently), and a symbol resolving differently in two
     namespaces is arguably two different types — so a loud build
     failure is the correct behavior, never a silent `known: false`.
   - Serialization is memoized by `(raw-type × ns-name)` pair — the
     same type repeats across many productions, and `resolve-type` on
     symbols pays an `ns-resolve` per call otherwise.  Startup cost
     scales with productions × types and is paid once during cache
     warm-up (`warm-analysis-cache!`); the request path is unaffected.
   - For each raw type, serialize its raw ancestors (from the memoized
     set fn) with the same ns context, then **hierarchy-order them**
     (deterministic topological sort, lexicographic tie-break — see
     1b).  No post-serialization dedup is needed: raw ancestors come
     from a set and kind-explicit serialization (see System Context)
     keeps distinct raw values distinct as strings.
   - The index value is plain serialized ancestor strings; the `known`
     flag is computed later, in the second pass (step 4), against the
     completed fact-types map keys.
4. Pass this index into `build-fact-type-summary-map`, which attaches
   `:ancestors` to each entry in a **second pass** after the usage map is
   complete (only then is the full set of "known" types known), setting
   `known` by membership of each serialized ancestor string in the
   fact-types map keys, and `id` via the memoized route-id fn.

### 1b. New field: `:ancestors`

Add to each fact-type entry:

```json
{
  "name": "my.ns.MarkerRecord",
  "ancestors": [
    {"type": "my.ns.IScanMarker", "id": "my.ns.IScanMarker-b2c4d6e8", "known": true},
    {"type": "java.lang.Object", "id": "java.lang.Object-f4g6h8j1", "known": false}
  ],
  "used-by-rules": [...],
  ...
}
```

Each ancestor entry is an object:

- **`type`** — serialized type string (kind-explicit, e.g.
  `:my.ns/child`).
- **`id`** — the deterministic route id (see "Route IDs" in System
  Context).  Present on all entries for a uniform shape; the UI
  hyperlinks only `known: true` entries (ghost ids 404 by design).
- **`known`** — `true` if this ancestor type appears anywhere in the
  `fact-types` map (i.e., some production uses it on its LHS or it is
  inserted/retracted), `false` if it is a "ghost type" — only visible as
  an ancestor of another type.  This lets the UI decide whether to render
  it as a hyperlink or plain text.

The fact-type entry itself also gains:

- **`id`** (route id scheme above), included in both the detail payload
  and the list payload (`FactTypeListItem` — small and needed by the UI
  to build links from the list view).
- **`ns`** (nullable) — best-effort namespace/package for grouping,
  computed from the **raw type** during the index build, where the kind
  is known: keyword/symbol → `(namespace x)`, class → `.getPackageName`,
  string/tuple/other → `null`.  It is a property of the *type*, not of
  any production context — idempotent across productions for every
  kind, so the merged index cannot corrupt it.  Grouping metadata, never
  identity.  It exists so the UI never parses names — today
  `FactTypeList.svelte` and `SessionNav.svelte` reconstruct a
  "namespace" with `splitQualifiedName` last-dot/slash heuristics.
  Rule/query entries and `ProductionDep` already carry `:ns` + `:name`
  separately, so with this field the no-parsing guarantee covers every
  payload the UI consumes.

**The `known` flag is the primary noise filter.**  Because Clara's default
ancestors-fn is `clojure.core/ancestors`, every record type carries a long
tail of ghost JDK/CLJ interfaces.  This is expected: `known: false`
entries are exactly the entries that can never bridge a dependency edge
(a type only creates an edge if some production consumes it, which makes
it `known`).  The UI renders `known` ancestors first/prominently.  If
payload or noise proves problematic in practice, a follow-up can add
server-side suppression of `java.lang.Object` / `clojure.lang.*` ghosts —
deliberately out of Phase 1 to keep the semantics simple and faithful.

Additional rules:

- `ancestors` is **hierarchy-ordered via a deterministic topological
  sort** — more specific (deeper) ancestors first, per the problem
  statement's "ideally in hierarchy ordering".  A comparator-based sort
  is **not** usable here: fusing the hierarchy partial order with a
  string tie-break into one comparator is intransitive (given A≺B by
  hierarchy and B<C, C<A by strings, the comparator asserts B<C and
  C<A yet A<B), which TimSort can detect as
  `IllegalArgumentException: Comparison method violates its general
  contract!` — and, more insidiously, silently mis-orders when it
  doesn't throw.  Implementation is Kahn's algorithm restricted to the
  ancestor set, tie-broken lexicographically:
  1. Ready = nodes with no remaining descendants in the set (nothing
     left that must be emitted before them).
  2. Emit the lexicographically smallest ready node (by serialized
     string), remove it, repeat.
  3. **Cycle guard:** if no node is ready but nodes remain (a
     pathological custom `ancestors-fn` with mutual ancestry), emit the
     lexicographically smallest remaining node and continue.
  Cost is O(k²) memoized ancestor-set lookups per type with k ≤ ~20 —
  negligible.  (UI-layer reordering/filtering based on `known` remains
  a Phase 3 concern.)
- The concrete type itself (the `name` key) is NOT included in the
  `ancestors` array.
- `ancestors` is the direct result of `(ancestors-fn type)` — not a
  recursive closure (though the default ancestors-fn is already
  transitive).  Each ancestor may itself appear as a fact type in the map
  with its own `ancestors` field, so the full hierarchy is reconstructible.
- When the rulebase has no `:ancestors-fn` meta (hand-built rulebases in
  tests) or the fn returns nil, `ancestors` is `[]`.

### 1c. Fact types with no recorded usage ("ghost types")

Fact types that appear ONLY as ancestors of other types are NOT added to
the `fact-types` map.  They appear only in `ancestors` arrays with
`"known": false`.  This gives the UI enough information to render them
as plain text rather than broken links.  If this proves insufficient, a
follow-up can compute transitive closure.

### 1d. List endpoint stays lightweight

`:ancestors` is **detail-only** — it is the heaviest field this plan
adds (10–20 entries per type with the default ancestors-fn), and the
list endpoint stays lightweight for the same reason `rules-list` omits
`:upstream`/`:downstream` (payload weight at 3k+ rules).  **`:id` and
`:ns` ARE included in the list payload** — they are small, and the UI
needs them to build fact-type links from list views, to use the list as
a name→id lookup table, and to group by namespace.  The **detail**
endpoint (`handle-get-fact-type`) serves the full entry from the
analysis map and gets `:ancestors` for free once it is in
`build-fact-type-summary-map`.

Consequence: `FactTypeListItem` gains only `:id` and `:ns`; only the
detail schema gains `:ancestors`.  (Production list entries already
carry `:ns`.)

### 1e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `rulebase-analysis`: extract ancestors-fn (with `analyze.clj`-style fallback), build memoized ancestor-set fn, hoist `type-analysis-map`, build serialized ancestors index, build BOTH id reverse indexes — fact types and productions (uniqueness asserted). `build-fact-type-summary-map`: accept the index, enrich each entry with `:id` + `:ancestors` in a second pass. `fact-types-list`/`rules-list`/`queries-list`: add `:id` to select-keys. `production-summary` + `get-production-deps-summary`: `:id` on productions and `ProductionDep` entries. |
| `server/src/clara/server/tools/graph/serialize.clj` | Extend `resolve-type` to kind-explicit serialization (keyword colon, string quotes, `symbol[...]` marker; see System Context). Add the uniform `route-id` fn (slug + 8-char base36 SHA-1 suffix, 60-char slug cap — used for fact types AND production names). Helper to serialize one raw ancestor type into `{:type ... :id ... :known ...}` shape (known flag applied by caller). |
| `server/src/clara/server/graph/api.clj` | Add `AncestorEntry` schema (`type`/`id`/`known`). Add `(s/optional-key :ancestors)` to `GetFactTypeResponse`. Add `:id` and `:ns` (nullable) to `FactTypeListItem` + detail entry; add `:id` to `RuleListItem`, `QueryListItem`, detail entries, and `ProductionDep`. All detail handlers (rules, queries, fact-types, session variants) resolve id-only via the reverse indexes; delete `fq-name-from-param` and any test exercising it. Router-level tests for id-based lookups. |
| `server/test/clara/server/tools/graph/core_test.clj` | Tests verifying `:ancestors` shape with `:known` flag, hierarchy ordering + determinism, default-ancestors noise behavior, and missing-meta case. |
| `ui/src/lib/types/api.ts` | Add `AncestorEntry` interface (`type`/`id`/`known`). Add `id: string` and `ns: string \| null` to `FactTypeListItem` + detail type; add `id` to rule/query/`ProductionReference` types. Add `ancestors?: AncestorEntry[]` to the detail fact-type type. |
| `docs/explorer-graph-api.md` | Document the `:ancestors` field with `type`/`id`/`known` keys on the detail view, the kind-explicit serialization table, and the `:id` scheme. |

### 1f. API compatibility

Alpha API: additive changes need no justification.  New optional
`:ancestors` key on fact-type **detail** objects.  Existing keys and the
list payload otherwise unchanged (keyword/string *values* in existing
fields gain the new spellings — see System Context).

### 1g. Test cases

1. **Default ancestors-fn (no user hierarchy):** a `defrecord` fact type
   yields a non-empty `:ancestors` containing `java.lang.Object` and
   `clojure.lang.*` interfaces, all `known: false`; an ancestor that some
   rule has on its LHS is `known: true`.
2. **Missing meta (hand-built rulebase):** no `:ancestors-fn` in
   `get-alphas-fn` meta → falls back to `clojure.core/ancestors`,
   matching `analyze.clj`'s fallback convention.
3. **Clojure `derive` hierarchy:**
   ```clojure
   (derive ::child ::parent)
   (derive ::parent ::grandparent)
   ```
   → `::child` ancestors include both `::parent` and `::grandparent`
   (transitive), `known` reflecting usage.
4. **Java interface hierarchy:** `MarkerRecord` implements `IScanMarker`,
   a rule has `IScanMarker` on its LHS →
   `{:type "my.ns.IScanMarker" :known true}` present, JDK ghosts
   `known: false`.
5. **Hierarchy ordering + determinism:** a descendant appears before
   its own ancestor (e.g. `::parent` before `::grandparent` for
   `::child`); incomparable siblings order lexicographically; output
   stable across calls even though `clojure.core/ancestors` returns an
   unordered set; an intransitivity fixture (A≺B by hierarchy, B<C<A
   by strings) orders A,B,C without throwing; a mutually-ancestral
   custom `ancestors-fn` fixture terminates via the cycle guard.
6. **Memoization:** ancestors-fn invoked at most once per unique raw type
   across the whole analysis (fact-type enrichment + dep-graph build).
7. **nil/throwing ancestors-fn:** a user ancestors-fn returning nil yields
   `[]` for that type (no NPE).  Optionally guard with try/catch as
   `build-fallback-type-filter` does.
8. **Mixed-kind hierarchy (custom ancestors-fn):** keyword type
   `::child` with ancestors-fn returning `["string-parent" ::kw-parent]`
   → serialized kind-explicitly (`"\"string-parent\""`,
   `":my.ns/kw-parent"`), ordered, `known` flags computed per entry.
9. **Kind-explicit serialization:** keyword → `:my.ns/child` (colon
   preserved); string type → `"foo"` (quotes); unresolved symbol →
   `symbol[my.ns/foo]`; vector type → `[:a 1]`; class unchanged.  A
   keyword and a same-spelled string type serialize differently (no
   collision).
10. **Symbol ns-resolution parity:** a fact type originating from an
    unresolved symbol still gets `:ancestors` (index built in
    per-production ns context — guards against the nil-ns serialization
    divergence).
11. **Route ids:** the id function is deterministic per name (stable
    across calls and analysis runs); all kinds — classes included — get
    the uniform slug + 8-char hash suffix; slugs survive names with
    `?`, spaces, quotes, brackets; 60-char truncation still
    distinguishes ids via the hash; each reverse index resolves every
    id back to its name and asserts uniqueness (colliding fixture
    throws).
12. **Fact-type `:ns`:** keyword type → its namespace string; class →
    package name; string/tuple → `null`; list + detail payloads both
    carry it.

---

## Phase 2 — Type-Bridge Info on Production Dep Links

**Goal:** Each `:upstream` / `:downstream` entry on a rule/query summary
carries enough information to show *which* produced type satisfies *which*
required type, and whether the match is direct or through hierarchy.

### 2a. Dep-graph shape is unchanged — pairs are computed at serialization time

The internal dep-graph keeps its current shape (name → `{:upstream #{…}
:downstream #{…}}` sets of names).  Changing it is unnecessary and
harmful:

- `rule-is-sink?` (core.clj) filters `:downstream` entries as bare
  production names; a richer edge value would break it.
- `test-dep-graph-full`, `test-dep-graph-hierarchy`,
  `annotations_report.bb`, and any `analysis.edn` consumer read the
  current shape.

The `:match` info is a pure function of data that already exists:
`(matching-type-pairs ancestors-set-fn produced-types consumed-types)`
for an adjacent (producer, consumer) pair.  It is computed where it is
serialized — in `get-production-deps-summary` — using the hoisted
`type-analysis-map` and the memoized ancestor-set fn from Phase 1a.

```clojure
(defn- matching-type-pairs
  "All (produced, consumed) raw-type pairs linking producer to consumer.
  Direct matches included (producer-type = consumer-type)."
  [ancestors-set-fn produced-types consumed-types]
  (->> (for [pt produced-types
             ct consumed-types
             :when (downstream? ancestors-set-fn pt ct)]
         {:producer-type pt :consumer-type ct})
       (distinct)))

;; `downstream?` — the memoized fn already returns a set:
(defn- downstream? [ancestors-set-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (contains? (ancestors-set-fn inserter-type) reader-type)))
```

Properties of this approach:

- **Zero breakage.**  `:dep-graph`, `rule-is-source?`, `rule-is-sink?`,
  tests, `analysis.edn`, and external tooling are all untouched.
- **Cheaper.**  Pairs are computed only for actual edges, not for every
  candidate pair in the O(n²) loop.  (`build-dep-graph` keeps its current
  boolean `some-type-consumed?` check, backed by the memoized set fn.)
- **No internal/external naming divergence.**  The serialized keys are
  produced directly; internal naming stays aligned with
  `internal-analysis-models.md` (`:produced-types` / `:consumed-types`).

Consequence: the `analysis.edn` dump's `:dep-graph` does not carry
type-pair info.  If external tooling later wants it, add a separate
top-level `:dep-bridges` key to the analysis output — additive, no shape
change.  Out of scope here.

Note on `analysis.edn` shape: the dump ppprints the full analysis map,
so rule/query/fact-type summaries in it DO gain the new fields
(`:id`, `:ns`, `:ancestors`, and `:match` on dep entries after
Phase 2) — additive only; the `:dep-graph` key itself is unchanged.
EDN consumers reading specific keys are unaffected.

### 2b. Serialize type-bridge info on `:upstream` / `:downstream`

**Current `ProductionDep`:**
```json
{"name": "my.ns/producer-rule", "ns": "my.ns", "type": "rule"}
```

**Enrichment — symmetric model:**

Both upstream and downstream entries use the same two keys with consistent
semantics.  Whether you see an upstream or downstream entry is determined by
context (the parent key), so the UI inherently knows how to interpret
`producer-type` / `consumer-type` without ambiguity or union types:

- `producer-type` — the concrete type the inserting rule produces.
- `consumer-type` — the concrete type the consuming rule's LHS requires.

Upstream entry (on consumer Rule Y):
```json
{
  "name": "my.ns/producer-rule",
  "ns": "my.ns",
  "type": "rule",
  "match": [
    {"producer-type": "my.ns.MarkerRecord", "consumer-type": "my.ns.IScanMarker"}
  ]
}
```

Downstream entry (on producer Rule X):
```json
{
  "name": "my.ns/consumer-rule",
  "ns": "my.ns",
  "type": "query",
  "match": [
    {"producer-type": "my.ns.MarkerRecord", "consumer-type": "my.ns.IScanMarker"}
  ]
}
```

When `producer-type` equals `consumer-type` (direct match, no hierarchy),
both are still present.  This avoids client-side special-casing.

`match` is an array because a single pair of productions may be linked by
multiple type pairs (e.g., producer inserts types A and C, consumer reads
B and D where A descends from B and C descends from D).

The `match` array is **deterministically ordered**: sorted lexicographically
by `producer-type`, then `consumer-type`, applied after `resolve-type`
converts raw types to comparable strings (raw types may be Classes or
keywords — neither consistently implements `Comparable`; `sort-by` on them
would throw `ClassCastException`).

### 2c. Update `get-production-deps-summary`

This function currently takes `[dep-graph production-name production-map]`
and serializes the name sets.  It also receives the `type-analysis-map`
and the memoized ancestor-set fn.  For each adjacent production name in
`:upstream` / `:downstream`:

1. Look up both ends' `:produced-types` / `:consumed-types` **and
   `:ns-name`** in `type-analysis-map` (upstream name is the producer,
   current production is the consumer — and vice versa for
   `:downstream`).
2. Compute `matching-type-pairs`, serialize each pair's raw types with
   `resolve-type` — **each end in its own ns context**:
   `(resolve-type producer-ns pt)` and `(resolve-type consumer-ns ct)`.
   Symbols reach `:produced-types` via EDN sidecar annotations, so
   serializing both ends with the current production's ns would
   misresolve the far end's symbols (e.g. producer's `foo` rendered
   `symbol[ns.b/foo]` instead of `symbol[ns.a/foo]`) and diverge from
   the `:insert-types` string on the producer's own summary.  Sort
   lexicographically, attach as `:match` on the serialized
   `ProductionDep`.

**Cross-field consistency invariant:** `:match`'s `producer-type` must
string-equal the corresponding entry in the producer's own
`:insert-types`/`:retract-types`, and `consumer-type` must string-equal
the corresponding entry in the consumer's `:lhs-types` — guaranteed by
using each end's own `:ns-name` (the same context `production-summary`
uses) and pinned in a test.

The internal keys `:producer-type` / `:consumer-type` are already
kebab-case, so serialization is just `resolve-type` on the values — no
rename layer.  `production-summary` passes the two new args through.
The full threading chain is: `rulebase-analysis` →
`build-rule-summary-map` / `build-query-summary-map` →
`build-production-summary-map` → `production-summary` →
`get-production-deps-summary` (alongside the existing `dep-graph` and
`production-map` args).

**Deduplication:** the dedup key is the raw `(producer-type,
consumer-type)` pair — structural, not hierarchy-path-based.  `distinct`
handles a rule inserting the same type in multiple `insert!` calls, a
consumer matching the same type in multiple LHS conditions, and
multi-path ancestry (`A → C` appears once even when `A` also descends
from an intermediate `Mid`; if the consumer reads both `Mid` and `C`,
those are two distinct, correct pairs).

### 2d. Function decomposition

1. Hoist `type-analysis` / `type-analysis-map` from `build-dep-graph` into
   `rulebase-analysis` (pure refactor, Phase 1a).
2. `->memoized-ancestors` helper (Phase 1a).
3. `matching-type-pairs` helper (with `distinct`).
4. `get-production-deps-summary` gains the two args and a
   `serialize-match` sub-helper in `serialize.clj`:
   ```clojure
   (defn serialize-match
     "Serializes raw {:producer-type ... :consumer-type ...} pairs
     (matching-type-pairs output), each end in its own ns context,
     sorted lexicographically."
     [raw-pairs producer-ns consumer-ns]
     (->> raw-pairs
          (map (fn [{:keys [producer-type consumer-type]}]
                 {:producer-type (resolve-type producer-ns producer-type)
                  :consumer-type (resolve-type consumer-ns consumer-type)}))
          (sort-by (juxt :producer-type :consumer-type))
          (vec)))
   ```

### 2e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | Hoist `type-analysis-map`. `get-production-deps-summary`: compute and attach `:match`. `production-summary` / `build-production-summary-map`: thread new args. `build-dep-graph`: use memoized ancestor-set fn (behavior unchanged). |
| `server/src/clara/server/tools/graph/serialize.clj` | `serialize-match` helper: raw pair → sorted `producer-type`/`consumer-type` string maps. |
| `server/src/clara/server/graph/api.clj` | Extend `ProductionDep` schema with `(s/optional-key :match)` array of `{producer-type, consumer-type}`. Schema docstring states the symmetric semantics: identical shape and meaning on upstream and downstream entries — `producer-type` is what the producing rule inserts, `consumer-type` is what the consuming rule's LHS requires. |
| `server/test/clara/server/tools/graph/core_test.clj` | Add tests for `:match` on dep links (direct, hierarchy, multi-type, dedup, symmetric both-directions). Existing dep-graph shape tests stay green unchanged — confirming zero internal breakage. |
| `ui/src/lib/types/api.ts` | Extend `ProductionReference` with `match?: TypeBridgeMatch[]`. Add `TypeBridgeMatch` interface. |
| `docs/explorer-graph-api.md` | Document `:match` field on upstream/downstream entries. |

### 2f. API compatibility

Additive at **both** levels: the API gains an optional `:match` key, and
the internal `:dep-graph` shape (consumed by `analysis.edn` tooling) is
untouched.  `:match` is always present when the pair links via at least
one type pair (direct matches included), which normalizes the consumer's
parsing path.

### 2g. Test cases

1. **Direct match (no hierarchy):**
   Rule A inserts `Foo`, Rule B requires `Foo` →
   both directions: `match: [{"producer-type": "Foo", "consumer-type": "Foo"}]`

2. **Single hierarchy jump:**
   Rule A inserts `MarkerRecord`, Rule B requires `IScanMarker` (interface) →
   both directions: `match: [{"producer-type": "MarkerRecord", "consumer-type": "IScanMarker"}]`

3. **Multi-type bridge:**
   Rule A inserts `[A, C]`, Rule B requires `[B, D]` where A→B and C→D via
   hierarchy → both directions: two match entries, sorted.

4. **Both direct and hierarchy matches:**
   Rule A inserts `[Foo, MarkerRecord]`, Rule B requires
   `[Foo, IScanMarker]` → two entries.

5. **Deduplication:** Rule A calls `(insert! Foo-1) (insert! Foo-2)`, Rule B
   has two LHS conditions matching `Foo` → one entry for `Foo`.

6. **Symmetry:** the `match` array on Y's upstream entry for X is identical
   to the one on X's downstream entry for Y.

7. **Cross-field consistency:** `:match` `producer-type` equals the
   string in the producer's own `:insert-types`; `consumer-type` equals
   the string in the consumer's own `:lhs-types` — including a case
   where a symbol insert-type comes from a sidecar annotation in a
   different ns than the consumer.
8. **Regression:** `test-dep-graph-full`, `test-dep-graph-hierarchy`,
   `rule-is-source?`/`rule-is-sink?` behavior all unchanged (dep-graph shape
   untouched).

---

## Edge Cases Checklist (both phases)

- **Default-ancestors noise:** every record type has 10–20 ghost ancestors.
  Expected; the `known` flag is the filter signal; possible follow-up
  suppression of `java.lang.Object` / `clojure.lang.*`.
- **`ancestors-fn` never nil in real sessions;** nil only for hand-built
  rulebases.  Use the `analyze.clj`-style fallback
  `(or meta-ancestors-fn ancestors)` for consistency.
- **User ancestors-fn returning nil or throwing:** treat as `[]` / guard
  (precedent: `build-fallback-type-filter` wraps in try/catch).
- **Retract types are in `:produced-types`** (pre-existing:
  `(into insert-types retract-types)`).  A dep edge created by a *retract*
  carries a `:match` whose `producer-type` is the retracted type —
  "producer" wording is imperfect there, but the coupling is real (a
  retraction can invalidate downstream joins) and matches the existing edge
  semantics.  Documented as a known limitation; the fix — a
  `"via": "retract"` flag on such `:match` entries — is a named
  fast-follow task (see Implementation Order), kept out of Phase 2
  proper to keep the initial contract minimal.
- **Self-edges excluded** (`(not= p-name1 p-name2)`): a rule that inserts
  and reads the same type gets no edge and no `:match` — pre-existing,
  unchanged.
- **Keyword hierarchies:** `derive`-based ancestors are transitive;
  keywords without `derive` have none.  Hierarchy ordering puts deeper
  ancestors first, lexicographic tie-break via deterministic
  topological sort (see 1b).
- **Sorting raw types throws:** ordering only ever applied post-
  serialization, on strings.
- **Symbol serialization of ancestors:** ancestors are compiler-resolved
  Classes or qualified keywords in practice; symbols are serialized in
  the per-production ns context of the index build (see 1a step 3).
- **Ancestors precomputation is eager and cheap:** one memoized
  ancestors-fn call + one O(k²) topological sort (k ≤ ~20) per fact
  type, at analysis build time — the analysis is cached
  (`warm-analysis-cache!`), so requests never pay it.  Trivial at 3k+
  rule scale.  Computed eagerly because the detail endpoint serves from
  the same analysis map.
- **Symbol resolution before ancestors:** verify at implementation time
  that raw types reaching the ancestors index are resolved (LHS types
  are compiler-resolved; sidecar-annotation symbols may not be — resolve
  class-backed symbols via the production's ns if found unresolved;
  `derive`-based symbol hierarchies work on the symbol itself).
- **`analyze.clj/build-fallback-type-filter`** has its own ancestors-fn
  extraction — the Phase 1a refactor extracts a single shared
  ancestors-fn accessor (meta extraction + `clojure.core/ancestors`
  fallback) used by BOTH `core.clj` and `analyze.clj`, so the dep-graph
  and the fallback type filter can never disagree on which types link.
- **Payload weight:** `:ancestors` on detail only (1d); `:match` only on
  detail views (list endpoints already omit `:upstream`/`:downstream`).
- **Queries as consumers:** a query's own summary has `:upstream`
  entries (its producers) and never `:downstream`; from a producing
  rule's view, the query appears in that rule's `:downstream`.
  `matching-type-pairs` handles queries identically (they have
  `:consumed-types`, empty `:produced-types`).
- **Default ancestors-fn on non-hierarchical types:**
  `(clojure.core/ancestors "foo")` → nil (same for vectors and
  underived keywords) — no throw, and `(set nil)` → `#{}`, so
  `->memoized-ancestors` is safe for every kind.
- **Heterogeneous type values:** handled exactly once, by kind-explicit
  serialization (System Context).  Raw values for all logic; strings
  only at the display boundary; ordering strictly post-serialization.
  Unstable `toString` on custom type objects is a documented, accepted
  limitation.
- **Kind-explicit serialization renames keyword/string fact types**
  (colon, quotes) — update `explorer-graph-api.md` with the
  serialization table and the `:id` scheme.  URLs never carry canonical
  names (see "Route IDs"), so the spellings' URI-unfriendliness is
  display-only.
- **Route-id collisions:** ~41 bits of hash (8 base36 chars) —
  birthday-safe to ~1.5M entries, ~10⁻⁹ per analysis at our scale —
  and asserted unique at reverse-index build time (throws loudly, in
  tests and on analysis build, never silently mislinks).
- **Id stability across runs:** ids are a pure function of the name
  alone, so adding new types/productions and re-running the analysis
  never changes existing ids; safe to bookmark.
- **Uniform id scheme:** no kind dispatch — classes, keywords, strings,
  tuples, and production names all get slug + hash suffix.  No
  canonical-name fallbacks; `fq-name-from-param` and
  `toRouteId`/`fromRouteId` are deleted, not deprecated.
- **Production names with special chars** (`?`, `!`, `*`): never reach
  a URL — rules, queries, and all `ProductionDep` entries link by
  `:id`.
- **Test fixtures:** existing demo rules
  (`server/test/clara/server/tools/graph/rules/`) are class-centric —
  but the realistic type universe for our rulesets is, in priority
  order: **plain keywords (majority case)**, vector tuples of
  keyword-led forms like `[:thing "value"]` (minor secondary), and
  class/record facts (minor, but must be supported — and they are
  arguably the most straightforward path anyway).  The new fixture
  reflects that emphasis and stays in the **same loan-application
  domain** as `loan_app_rules.clj` / `loan_doc_rules.clj` (a coherent
  theme makes the rules' relationships self-explanatory), with a
  distinct ns name to avoid collisions, e.g.
  `loan_hierarchy_rules.clj`:
  - **Primary: keyword-typed loan facts** with a `derive` hierarchy
    (e.g. `::income-document` → `::supporting-document` →
    `::loan-document`) — a rule inserting a derived keyword type, and a
    rule/query whose LHS reads the ancestor keyword type (mirrors the
    problem statement's Rule X / Rule Y scenario directly);
  - **Secondary: vector-tuple fact types** in `[:keyword "value"]` form
    (e.g. `[:loan/status "verified"]`, `[:document/flag
    "income-mismatch"]`) exercising insertion, LHS matching, and
    kind-explicit serialization;
  - **Minor mix-in: one class/record fact type** (e.g. a
    `LoanApplication` record) so a single session also covers the class
    path (default `clojure.core/ancestors`, interface ancestors,
    `known` ghosts) — deliberately a minor case, unlike the prior
    loan-app fixtures;
  - sessions built via `mk-session` with explicit `:ancestors-fn` /
    `:fact-type-fn` options as needed for keyword/tuple fact typing.
- **`resolve-type` totality:** its branches are total over heterogeneous
  kinds (`pr-str`/`str` never throw for ordinary objects) — a hostile
  object with a throwing `toString` is pathological and out of scope.

---

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
- **Rule detail view:** In the upstream/downstream sections, show each dep
  entry with the `:match` details inline (e.g., "Rule X produces
  `MarkerRecord` → satisfies `IScanMarker`").
- **Graph visualization:** Edges could carry type-bridge labels.

This phase is scoped separately and not detailed here.

### UI Impact Summary (for scoping Phase 3)

Breaking (must land together):

- Fact-type `:name` format changes to kind-explicit forms everywhere it
  is displayed.
- Route-id migration: all URL construction uses `:id`;
  `toRouteId`/`fromRouteId`/`splitQualifiedName` deleted; SvelteKit
  `load` functions pass `[id]` params through verbatim.
- Grouping switches to server-provided `:ns` (nullable — needs a
  fallback group label).

Additive (can trail):

- `:ancestors` section on the fact-type detail view (hierarchy-ordered,
  `known` entries linked via `id`, ghosts as plain text).
- `:match` rows on rule/query upstream/downstream entries.
- `:id` fields on list payloads (needed by the breaking link changes —
  effectively lands with them).

---

## Documentation & Schema Principles (binding at implementation time)

- **Schemas are the structural source of truth, not docstrings.**
  Response shapes (`AncestorEntry`, `:match`, `:id`, `:ns`, kind-explicit
  serialization forms) are expressed in the Prismatic schemas in
  `api.clj` with concise field-level docstrings.  Do NOT write large
  docstrings that enumerate data structures field-by-field — they go
  stale immediately and duplicate the schema.  A docstring states
  purpose and non-obvious semantics (e.g. "`known` distinguishes types
  linkable in this rulebase from hierarchy ghosts"); the schema states
  shape.
- **Docstrings describe the present, never the design process.**  No
  "previously", "used to", "now", "revised", or comparisons to
  replaced behavior.  Write what the code does and why, as if it had
  always worked that way.  (Rationale history lives in this plan and in
  git, not in code.)
- **Code never references this plan.**  This document is ephemeral
  design-phase material; no docstring or comment may cite it, its
  section numbers, or its bullet points.  `docs/explorer-graph-api.md`
  and `server/docs/internal-analysis-models.md` are maintained project
  docs and MAY be referenced — sparingly, and only from other docs or
  from code whose behavior the doc genuinely tracks.
- **Linking direction: docs → code, not code → docs.**  Implementation
  details are owned by the executable code (and its schemas); project
  docs cite namespaces/functions when they need precision.  A docstring
  should not point at a doc for its own contract — the contract is the
  code.  When updating `explorer-graph-api.md`, link/cite the
  implementing vars (e.g. `serialize/resolve-type`, `serialize/route-id`)
  rather than restating their logic.
- **Project docs are updated, not appended-as-history.**
  `explorer-graph-api.md` and `internal-analysis-models.md` describe
  the new state directly (serialization table, id scheme, `:ancestors`,
  `:match`), not a changelog of this refactor.

---

## Implementation Order

- [ ] **Phase 1a:** In `rulebase-analysis`: extract ancestors-fn via a single shared accessor used by both `core.clj` and `analyze.clj` (meta extraction + `clojure.core/ancestors` fallback); add `->memoized-ancestors`; hoist `type-analysis-map` (with `:ns-name`) out of `build-dep-graph`; build serialized ancestors index in per-production ns context (topologically ordered, divergence asserted, serialization memoized by raw-type × ns-name); simplify `downstream?` to consume the memoized set fn in the same change; verify raw types reaching the index are resolved. Extend `resolve-type` to kind-explicit serialization
- [ ] **Phase 1b:** Add `:ancestors` field (objects with `type`/`id`/`known`, deterministic topological order with lexicographic tie-break + cycle guard) to fact-type entries in `build-fact-type-summary-map` (second pass)
- [ ] **Phase 1c:** Add the uniform route-id fn (slug + 8-char base36 SHA-1 suffix, 60-char slug cap) to `serialize.clj`; add `:id` to fact-type entries (`FactTypeListItem` + detail) and to `AncestorEntry`; build fact-type id reverse index in `rulebase-analysis` (uniqueness asserted); `handle-get-fact-type` resolves id-only. Router-level tests for id-based lookups (keyword/tuple/string/class forms)
- [ ] **Phase 1d:** Add server tests for `:ancestors` (default-ancestors noise, `known` flag, ordering, missing-meta, nil-returning fn, memoization, mixed-kind hierarchy, kind-explicit serialization, symbol ns-resolution parity, route ids)
- [ ] **Phase 1f:** Rewrite `explorer-graph-api.md` for the new contract — flag: this is a full rewrite, not a targeted edit (serialization table, `:id` scheme + routes, `:ns`, `:ancestors`)
- [ ] **Phase 1e:** Update UI types (`AncestorEntry` with `type`/`id`/`known`, `id` on `FactTypeListItem`/`RuleListItem`/`QueryListItem`/details/`ProductionReference`, `ancestors?` on detail `FactTypeSummary` in `api.ts`)
- [ ] **Phase 1g:** Production route ids: `:id` on rule/query list + detail entries and every `ProductionDep`; production id reverse index; all rule/query/session detail handlers resolve id-only (session handlers via a per-snapshot id→name index built at snapshot-cache time — no analysis-cache dependency); delete `fq-name-from-param` server-side and its tests **in the same commit as the index implementation** (atomic revert if issues arise); update UI `factPath`/rule/query link builders to use `:id` and delete `toRouteId`/`fromRouteId`/`splitQualifiedName`. **Scope note: this is the largest single work item (~30–40% of the total)** — it touches every endpoint, every link builder, and all URL-constructing test fixtures. Budget accordingly
- [ ] **Phase 2a:** Add `matching-type-pairs` helper
- [ ] **Phase 2b:** Update `get-production-deps-summary` (+ `serialize-match` in `serialize.clj`) to attach `:match` with symmetric `producer-type`/`consumer-type` keys, each serialized in its own production's ns context, sorted post-serialization
- [ ] **Phase 2c:** Update `ProductionDep` schema in `api.clj` with optional `:match` array
- [ ] **Phase 2d:** Add server tests for `:match` (direct, hierarchy, multi-type, dedup, symmetry, cross-field consistency incl. sidecar symbol in foreign ns, dep-graph regression)
- [ ] **Phase 2f:** Update `explorer-graph-api.md` with the `:match` contract (symmetric shape + semantics, citing `ProductionDep` schema)
- [ ] **Phase 2e:** Update UI types (`ProductionReference`, `TypeBridgeMatch` in `api.ts`)
- [ ] **Docs hygiene pass:** verify the Documentation & Schema Principles — schemas carry the structural truth; no docstring enumerates shapes, narrates design history, or references this plan; project docs cite code (not vice versa) for impl details
- [ ] **Fast-follow (post-Phase 2, small):** `"via": "retract"` flag on `:match` entries whose bridge comes from a retract type, so the UI can distinguish retraction coupling from production
- [ ] **Phase 3:** UI integration (future, scoped separately)
