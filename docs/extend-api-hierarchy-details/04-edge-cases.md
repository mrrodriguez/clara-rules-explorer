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
  ≈3×10⁻⁶ collision probability per analysis at our scale (n ≈ 4k
  names) — and asserted unique at reverse-index build time (throws
  loudly, in tests and on analysis build, never silently mislinks).
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
- **`resolve-type` totality:** its kind branches are total over
  heterogeneous kinds for a valid, loaded `ns-name` (`pr-str`/`str`
  never throw for ordinary objects) — a hostile object with a throwing
  `toString` is pathological and out of scope.  Note the symbol
  branch's `(the-ns ns-name)` throws if `ns-name` names a
  non-existent namespace; every ns context used here is derived from a
  compiled production, so the namespace always exists.

---

