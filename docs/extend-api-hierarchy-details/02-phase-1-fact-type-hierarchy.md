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
     flag is computed against the upfront known set (serialized
     `:consumed-types` ∪ `:produced-types` union — equals the future
     fact-types map keys by construction; see "TypeReference" in
     System Context).
4. Pass this index and the known set into
   `build-fact-type-summary-map`, which attaches `:ancestors` entries
   (`{:name ... :id ... :known ...}`) in the SAME pass that builds the
   usage map — no second pass needed once the known set is computed
   upfront.

### 1b. New field: `:ancestors`

Add to each fact-type entry:

```json
{
  "name": "my.ns.MarkerRecord",
  "ancestors": [
    {"name": "my.ns.IScanMarker", "id": "my.ns.IScanMarker-b2c4d6e8", "known": true},
    {"name": "java.lang.Object", "id": "java.lang.Object-f4g6h8j1", "known": false}
  ],
  "used-by-rules": [...],
  ...
}
```

Each ancestor entry is a `TypeReference` (see System Context):

- **`name`** — serialized type string (kind-explicit, e.g.
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
entries can never bridge a dependency edge (a type only creates an
edge if some production consumes it, which makes it `known`).  The
converse does not hold: a type that is only ever inserted/retracted —
never on an LHS — is `known: true` yet bridges nothing.  The UI
renders `known` ancestors first/prominently.  If
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
needs them to build fact-type links from list views and to group by
namespace.  The **detail**
endpoint (`handle-get-fact-type`) serves the full entry from the
analysis map and gets `:ancestors` for free once it is in
`build-fact-type-summary-map`.

Consequence: `FactTypeListItem` gains only `:id` and `:ns`; only the
detail schema gains `:ancestors`.  (Production list entries already
carry `:ns`.)  Note the usage lists (`:used-by-rules` etc.) upgrade to
`[ProductionDep]` in BOTH list and detail payloads — see
"TypeReference" in System Context for the uniform-reference contract
and the accepted list-payload weight trade-off.

### 1e. Files to modify

| File | Change |
|------|--------|
| `server/src/clara/server/tools/graph/core.clj` | `rulebase-analysis`: extract ancestors-fn (with `analyze.clj`-style fallback), build memoized ancestor-set fn, hoist `type-analysis-map`, compute the upfront known set (serialized `:consumed-types` ∪ `:produced-types` union), build serialized ancestors index, build BOTH id reverse indexes — fact types and productions (uniqueness asserted). `production-summary`: `:lhs-types` / `:insert-types` / `:retract-types` become `[TypeReference]` (name + id + known, honest membership check against the known set); `:id` on productions. `build-fact-type-summary-map`: accept the index + known set, enrich each entry with `:id` + `:ancestors` (single pass); extract type `:name`s from refs when aggregating; usage lists (`:used-by-rules` etc.) become `[ProductionDep]`. `fact-types-list`/`rules-list`/`queries-list`: add `:id` to select-keys. `get-production-deps-summary`: `:id` on `ProductionDep` entries. |
| `server/src/clara/server/tools/graph/serialize.clj` | Extend `resolve-type` to kind-explicit serialization (keyword colon, string quotes, `symbol[...]` marker, tuples via `pr-str` — split the catch-all `:else` into `string?`/`sequential?`/catch-all; see System Context). `serialize-lhs`/`serialize-condition` gain the production ns-name and serialize condition `:type` as a `TypeReference`. Add the uniform `route-id` fn (slug + 8-char base36 SHA-1 suffix, 60-char slug cap — used for fact types AND production names). Add `serialize-type-ref` (raw type + ns-name + known set → `{:name ... :id ... :known ...}`) — the single helper behind `:ancestors` entries, `:lhs-types` / `:insert-types` / `:retract-types`, condition `:type`, callsite `:resolved-types`, and Phase 2 `serialize-match`. |
| `server/src/clara/server/graph/api.clj` | Add `TypeReference` schema (`name`/`id`/`known`). Introduce a `FactTypeDetail` schema (list item + `(s/optional-key :ancestors)`) — `GetFactTypeResponse` currently reuses `FactTypeListItem` as its body, so a distinct detail shape does not exist yet. Add `:id` and `:ns` (nullable) to `FactTypeListItem` + `FactTypeDetail`; add `:id` to `RuleListItem`, `QueryListItem`, detail entries, `ProductionDep`, `SessionFactTypeItem`, `SessionFactTypeDetail`, and `FactTypeRoleGroup`; add `:ns` (nullable) to `SessionFactTypeItem`/`SessionFactTypeDetail` (SessionNav groups session fact types by namespace today via `splitQualifiedName`). Type-reference fields upgrade: `:lhs-types` / `:insert-types` / `:retract-types` → `[TypeReference]` on `RuleListItem`/`QueryListItem`; `LhsCondition` `:type` → `TypeReference`; `DynamicCallsiteEntry` `:resolved-types` → `[TypeReference]`, `:fact-type` → `TypeReference`; fact-type usage lists → `[ProductionDep]`; `SessionFact` `:type` → `TypeReference`. All detail handlers (rules, queries, fact-types, session variants) resolve id-only via the reverse indexes; delete `fq-name-from-param` and any test exercising it. Router-level tests for id-based lookups, including session variants (`session_api_test.clj`). |
| `server/src/clara/server/tools/graph/memory.clj` | Session-side production refs are built here, NOT via `serialize/serialize-production-dep`: `build-origin-map` / `build-used-by-index` (`inserted-from`/`used-by` on session facts) and `group-instances-by-role` (`FactTypeRoleGroup`) gain `:id`. Session fact `:type` and the fact-type index keys switch to kind-explicit serialization (currently `(serialize-fact-type nil …)` — nil ns context; unresolved-symbol divergence vs. the analysis side is the accepted limitation flagged in "Two separate indexes"). Build the per-snapshot id→name indexes (rules, queries, fact-types) in `session-snapshot` for the session handlers. |
| `server/test/clara/server/tools/graph/core_test.clj` | Tests verifying `:ancestors` shape with `:known` flag, hierarchy ordering + determinism, default-ancestors noise behavior, and missing-meta case. |
| `ui/src/lib/types/api.ts` | Add `TypeReference` interface (`name`/`id`/`known`). Add `id: string` and `ns: string \| null` to `FactTypeListItem` + detail type; add `id` to rule/query/`ProductionReference` and session fact-type/role-group types. Type-reference fields become `TypeReference` (`lhs-types`/`insert-types`/`retract-types`, condition `type`, callsite `resolved-types`/`fact-type`, session fact `type`); fact-type usage lists become `ProductionReference[]`. Add `ancestors?: TypeReference[]` to the detail fact-type type. |
| `ui/src/lib/utils.ts`, `ui/src/lib/api.ts` | Link builders and API fetchers pass server-issued ids verbatim (no `encodeURIComponent`/`toRouteId`); `toRouteId`/`fromRouteId`/`splitQualifiedName` deleted (Phase 1h). |
| `ui/bin/scrape-demo-data.js` + `ui/static/demo-data/**` | The UI is fully prerendered from demo data. The scrape script's own `toUrlId` and name-based detail-fetch URLs switch to the server-issued `:id` from list payloads; regenerate demo data with `pnpm scrape:demo` after the server change. |
| `ui/src/routes/**/+page.server.ts` | `entries()` generators (rules, queries, fact-types, session) emit the payload's `id` field verbatim — ids are URL-safe by construction. Rename `session/fact-types/[typeName]` → `[id]` for consistency. |
| `docs/explorer-graph-api.md` | Document the `TypeReference` shape (`name`/`id`/`known`) and every field that adopts it, the `[ProductionDep]` usage lists, the kind-explicit serialization table, and the `:id` scheme. |

### 1f. API compatibility

Alpha API: breaking reshapes need no migration shims — all callers get
fixed in the same change.  New optional `:ancestors` key on fact-type
**detail** objects; `:id` / `:ns` added broadly.  Breaking reshapes:
type-reference fields (`:lhs-types`, `:insert-types`, `:retract-types`,
condition `:type`, callsite `:resolved-types` / `:fact-type`, session
fact `:type`) go from bare strings to `TypeReference` objects;
fact-type usage lists go from `[s/Str]` to `[ProductionDep]`;
keyword/string/tuple type *names* gain kind-explicit spellings (see
System Context).

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
   `symbol[my.ns/foo]`; vector type → `[:a 1]`; string-bearing tuple
   → `[:loan/status "verified"]` (element quotes preserved by
   `pr-str`); class unchanged.  A keyword and a same-spelled string
   type serialize differently (no collision).  An LHS condition
   `:type` `:name` string-equals the corresponding `:lhs-types` entry
   `:name` for every kind (classes included — regression guard for the
   `serialize-lhs` change).
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

