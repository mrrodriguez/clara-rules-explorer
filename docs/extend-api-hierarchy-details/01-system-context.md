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
| vector / sequential (tuple) | `(pr-str x)` | `[:a 1]` | **changed** (was `(str x)`) |
| arbitrary object | `(str x)` (`.toString()`) | — | unchanged |

Correction on the tuple row: tuples are serialized today by the
catch-all `:else (str x)` branch, NOT `pr-str`.  For tuples whose
elements are all non-strings (`[:a 1]`) the two agree, but for
string-bearing tuples `(str [:loan/status "verified"])` yields
`[:loan/status verified]` (element quotes dropped) while `(pr-str …)`
yields `[:loan/status "verified"]`.  The move to `pr-str` is therefore
a visible rename for exactly the tuple form our fixtures use — and a
consistency win, since `pr-str` is recursively kind-explicit (keyword
elements keep their colons, string elements keep their quotes).
Implementation must split the current `:else` branch into `string?` /
`sequential?` / catch-all.

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

### LHS condition `:type` values bypass `resolve-type` today

`serialize-condition` only rewrites `:constraints` / `:args`; the `:type`
value passes to the JSON encoder raw.  Classes happen to encode as their
`.getName` string (consistent with the table above), but a keyword `:type`
encodes colon-stripped and a tuple `:type` encodes as a JSON array — not a
string at all.  Nothing breaks today because every other field is equally
colon-stripped; after the kind-explicit rename, raw-encoded condition
types would diverge in spelling from every other type surface
(`:name` / `:lhs-types` / `:ancestors` / `:match`).

Fix (lands with Phase 1a/1e): `serialize-lhs` gains the production's
ns-name and each condition's `:type` becomes a `TypeReference`
(kind-explicit `name` + `id` + `known`).  This changes the
`LhsCondition` payload shape for `:type` (schema value tightens from
`s/Any` to `TypeReference`); add a test pinning condition-`:type`
`:name` == the corresponding `:lhs-types` entry `:name` for every kind.

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
  entries.  Honest math at our scale: with n ≈ 4k names the collision
  probability per analysis is ≈ n²/2⁴² ≈ 3×10⁻⁶ — small, and a
  collision surfaces as a loud build-time failure (uniqueness is
  asserted, below), never silent corruption.  10 base36 chars (~52
  bits) would push it to ≈2×10⁻⁸ for two more characters; 8 is the
  readability/safety trade-off chosen here.
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
- **Reverse indexes are internal.**  Handlers need them, API consumers
  do not: keep them out of the public `/v1/analysis` payload (store
  them alongside the analysis in the cache atom, or under a
  clearly-internal key).  `main.clj --generate-analysis` ppprints the
  full analysis map to `analysis.edn` — decide deliberately whether
  the indexes are dumped (harmless, small) or excluded.
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
  rule/query details, fact-type `:used-by-rules` / `:used-by-queries` /
  `:inserted-by-rules` / `:retracted-by-rules` lists, and
  `inserted-from`/`used-by` on session facts) so every production
  hyperlink in the UI is id-based.

### `TypeReference` — one shape for every linkable type reference

Breaking-contract decision (alpha — all callers get fixed; no
backward-compatibility shims, no client-side name→id lookup tables):
anywhere the API emits a fact type that the UI may hyperlink, the value
is a `TypeReference` object, never a bare string:

```json
{"name": ":my.ns/child", "id": "my.ns.child-x7k9p2m4", "known": true}
```

- **`name`** — kind-explicit serialized type string (display).
- **`id`** — deterministic route id (linkage).
- **`known`** — `true` iff the type appears in the analysis
  `fact-types` map.  `known: false` entries render as plain text,
  never a link (ghost ids 404 by design — ids on ghosts exist for
  shape uniformity only and are not a supported linking surface).

`TypeReference` replaces bare type-name strings in: fact-type
`:ancestors` entries, rule/query `:lhs-types` / `:insert-types` /
`:retract-types`, LHS condition `:type`, dynamic-callsite
`:resolved-types` / `:fact-type`, and the `:match` pairs (Phase 2).
`known` is always `true` for `:lhs-types` / `:insert-types` /
`:retract-types` and `:match` entries (such types are in the map by
construction); it distinguishes ghosts only in `:ancestors` and
callsite `:resolved-types`.  Uniform shape beats per-field minimalism:
one schema, one UI prop type, no kind dispatch.

Likewise, production references are ALWAYS `ProductionDep`
(`{name, ns, type, id}`), never bare strings: fact-type
`:used-by-rules` / `:used-by-queries` / `:inserted-by-rules` /
`:retracted-by-rules` upgrade from `[s/Str]` to `[ProductionDep]` (the
UI currently reconstructs refs client-side from bare names via `toRef`
— deleted).  There is no client-side name→id lookup table anywhere:
every linkable value carries its own id.  List payloads carry the same
reference shapes as detail payloads — the added weight (id + known
per entry) is accepted for a localhost explorer at 3k-rule scale;
slimming list payloads is a possible follow-up if profiling shows it
matters.

Implementation note — the `known` set is computed UPFRONT, not from the
finished fact-types map: it is the serialized union of all
`:consumed-types` ∪ `:produced-types` across the hoisted
`type-analysis-map`, which equals the future `fact-types` map keys by
construction.  Computing it once in `rulebase-analysis` before any
summaries are built lets `production-summary`, `serialize-match`, and
the ancestors enrichment all attach honest `known` flags without a
second pass.

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

