# Rulebase-Analysis `ns-deps` — Plan

Status: **Draft (decisions locked §3)** · Scope: `server/` (Clojure) · Related: `analyze/synth.clj`, `analyze.clj`, `core.clj`, `docs/explorer-graph-api.md`

## 1. Goal

Add a top-level `:ns-deps` key to `core/->rulebase-analysis` that maps each
production-owning namespace (rules and queries alike) to its static
dependencies, reusing the logic currently embedded in
`analyze.synth/reconstruct-ns-source`. While there, fix the known inaccuracy
in `synth/->import-clauses` (currently `build-import-clauses`) /
`synth/->unmapped-default-imports` (currently `unmapped-default-imports`)
(fuzzy
`"java.lang."` prefix match vs. the authoritative
`clojure.lang.RT/DEFAULT_IMPORTS` set).

Locked shape (decisions §3):

```clojure
{<ns-name-sym> {:require   [{:ns-name-sym <ns-name-sym> :refers [<refer-sym> ...]} ...]
                :aliases   [{:ns-name-sym <ns-name-sym> :alias-sym <alias-sym>} ...]
                :imports   [<fq-class-name-sym> ...]
                :refer-clojure {:excludes [<refer-sym> ...]
                                :renames  {<local-sym> <core-var-sym>}}
                :unmapped-default-imports [<simple-sym> ...]}}
```

Non-goals: no source *recreation* of the ns (that's `synth`'s job and stays
there — `synth` internals delegate to the new leaf ns with no output change);
no behavior change in the annotation pipeline; no UI work in this pass
(API contract update only, §8).

## 2. Current state (inventory)

### 2.1 `analyze.synth` — the logic to reuse

File: `server/src/clara/server/tools/graph/analyze/synth.clj`.

| Fn | Input | Output today | Reusable core |
|----|-------|--------------|---------------|
| `core-deviations` | live `Namespace` obj | `{:excluded [...] :renamed {...}}` vs `clojure.core` defaults | Yes — the `:refer-clojure` data source |
| `build-require-clauses` | live `Namespace` obj | sorted vector of `:require` clause vectors (`[target :as a]`, `[target :refer [...]]`), `clojure.core` refers removed | Yes — but returns *syntax*, not *data* (see §4) |
| `build-import-clauses` | live `Namespace` obj | sorted vector of `:import` clause vectors grouped by package, `java.lang.*` removed by **prefix check** | Yes — but exclusion predicate is the bug (see §6) |
| `unmapped-default-imports` | live `Namespace` obj | sorted vector of `RT/DEFAULT_IMPORTS` keys missing from `ns-imports` (key-set difference — already exact) | Yes, as-is |
| `reconstruct-ns-source` | `ns-sym` | full `(ns ...)` + `(ns-unmap ...)` source string | Stays; becomes a *consumer* of the decomposed fns, behavior unchanged |

Private helpers `var-ns-name` / `var-name` (var-meta readers) support
`core-deviations` and move with it (renamed `get-var-ns-name` /
`get-var-name` and `->core-deviations` respectively — see §4).

Reflection-relevant interop (must keep working under
`*warn-on-reflection* true`, enforced by `make reflection-check`):
`(.getName ^Class (val %))` in `build-import-clauses`,
`(.getPackageName c)` with `^Class` hint. Any decomposition must preserve
the `^Class` hints on the same expressions.

### 2.2 Callers today

- `analyze/->rule-source-analysis` (`analyze.clj:816`) builds
  `ns-source-map` via `synth/synthesize-ns-source` (which calls
  `reconstruct-ns-source` only on the no-classpath-source path), then
  `->rule-source-analysis-from-namespaces` analyzes the combined sources.
  This is the **annotations-gen** piece — out of scope except as a consumer
  that must keep passing after the refactor.
- `core/->rulebase-analysis` (`core.clj:434`, impl `->rulebase-analysis*`
  at `:372`) builds `{:rules :queries :fact-types :nodes :dep-graph
  :unresolved :merged-annotations}` plus id indexes. **`:ns-deps` attaches
  here.** `core` currently does *not* depend on `analyze` (and `analyze`
  requires `core/extract-ancestors-fn`) — see §7 for the cycle constraint.
- `find-ns-resource` / `ns->resource-base` live in `analyze.clj`
  (`:324/:331`). `core` has no equivalent; the ns-header-parse path (§5)
  needs one (or needs `core` to call into a leaf ns that owns it).

### 2.3 Tests that pin current behavior

- `analyze_test.clj:716-800` — reconstructed-ns fallback, `:refer` round
  trip, `:refer-clojure` list shape, `(declare …)` emission, `:fact-constructors`
  via declared helpers.
- `analyze_test.clj:840-1060` — `:ns-var-defs-fn` hook family.
- `core_test.clj` — `->rulebase-analysis` shape (loan fixtures); any new
  top-level key must not break `match?`-style assertions there (check before
  finalizing — most use `get-in`, safe, but verify).
- Fixture ns with real `:require` + `:import`: `rules/loan_doc_rules.clj`
  (requires `clara.rules`, `helpers`, `loan-app-facts`,
  `clara.rules.accumulators`; imports six `loan_app_facts` classes).

## 3. `:ns-deps` shape — DECIDED

All four shape questions are resolved; the schema below is locked.

- **D1 — aliases: separate `:aliases` vec (DECIDED).** Aliases live in their
  own top-level entry `[{:ns-name-sym … :alias-sym …} …]`, derived from
  `ns-aliases` on the runtime path and from `:as` specs on the header-parse
  path — *not* folded into `:require` entries. Rationale: aliasing and
  referring are independent axes (a ns can be aliased without any refers and
  vice versa); keeping them separate means neither side needs nil/optional
  placeholders, and each vec has one uniform element shape. `:require`
  entries stay `{:ns-name-sym … :refers […]}` where `:refers` is the sorted
  symbol vector of referred vars; a `:refer :all` / bare `:use` spec is
  expanded to the required namespace's public vars, so the vector stays
  homogeneous. A spec that refers nothing (bare `:require`, `:refer []`)
  produces no entry.
- **D2 — `:refer-clojure` is a nested map (DECIDED):**
  `{:excludes [<refer-sym> …] :renames {<local-sym> <core-var-sym>}}` — full
  parity with `->core-deviations`' (currently `core-deviations`)
  `{:excluded :renamed}`, just kebabed under
  one top key. Both halves always present (empty vec / empty map when no
  deviation), so consumers never nil-check.
- **D3 — `:unmapped-default-imports` included (DECIDED):**
  `[<simple-sym> …]`, empty when none. The data is already computed for
  `reconstruct-ns-source`; discarding it would force a later consumer to
  re-derive it.
- **D4 — `:imports` are FQ class-name symbols (DECIDED):** sorted flat vector
  of fully-qualified class-name symbols, e.g.
  `[clara.server.tools.graph.rules.loan_app_facts.Application …]`.
  Symbol (not string) matches how kondo `java-class-usages` and fact-type
  tokens flow and is consistent with `:ns-name-sym` keys. Flat, not
  package-grouped — grouping by package is a *syntax* concern for `:import`
  clauses, not a data concern.

Locked schema (`s/defschema`, schema.core following `synth.clj` conventions;
edge-only `s/validate` per repo practice):

```clojure
(s/defschema NsRequireEntry
  {:ns-name-sym s/Symbol
   :refers [s/Symbol]})                          ; refer-all expands to publics

(s/defschema NsAliasEntry
  {:ns-name-sym s/Symbol
   :alias-sym s/Symbol})

(s/defschema NsDepEntry
  {:require [NsRequireEntry]                     ; sorted by (str :ns-name-sym)
   :aliases [NsAliasEntry]                       ; sorted by (str :alias-sym)
   :imports [s/Symbol]                           ; FQ class-name syms, sorted
   :refer-clojure {:excludes [s/Symbol]          ; sorted
                   :renames {s/Symbol s/Symbol}}
   :unmapped-default-imports [s/Symbol]})        ; simple-name syms, sorted

{ns-name-sym NsDepEntry, ...}  ; sorted-map by ns-name-sym, mirroring ann/normalize-annotations
```

Empty collections (never nil) for absent sections, so consumers can
`seq`-check uniformly. Entry key order:
`:require :aliases :imports :refer-clojure :unmapped-default-imports`.

**Which namespaces?** Every production-owning ns — rules and queries alike
(DECIDED: any production counts). Queries participate in `dep-graph` and
fact-type analysis, so scoping to rules-only would be arbitrary. Reuse the
derivation logic: `analyze/extract-rule-namespaces` (fq-name → namespace) for
the string/symbol path plus `core/get-production-ns-name-sym` (prefers
`:ns-name`, falls back to fq-name namespace — needed because queries carry no
`:ns-name`). Sorted vector for determinism.

## 4. Decomposition design (reuse without copy-paste)

`->require-clauses` / `->import-clauses` (currently named `build-…`; renamed
per below) return *syntax* (clause vectors ready for `pr-str`). `ns-deps`
needs *data*. Introduce data-first `->ns-…` builder fns in the leaf ns; keep
the clause builders as thin syntax projections so `reconstruct-ns-source`
output is byte-identical (pinned by the round-trip tests in §2.3).

Naming (repo convention: builders take `->`, pure extractions take `get-`):
every new fn constructs a normalized data shape, so all take `->`. The existing `synth` fns are renamed
to comply in the same pass — `build-` verbs become `->`, and the pure-noun
fns are builders too (`->` + noun, same pattern as `core/->dep-graph` and
`core/->type-analysis-map`), not mere lookups:

| New fn (leaf ns) | Derived from | Returns (data) |
|--------|--------------|----------------|
| `->ns-required` | require-clause body (refer half) | sorted `[{:ns-name-sym … :refers […] } …]` — one entry per ns with ≥1 referred var (`clojure.core` excluded, as today) |
| `->ns-aliases` | require-clause body (alias half) | sorted `[{:ns-name-sym … :alias-sym …} …]` from `ns-aliases` |
| `->ns-imports` | import-clause body | sorted `[<fq-class-sym> …]` — flat, *after* default-import exclusion (fixed predicate, §6) |
| `->ns-refer-clojure` | `core-deviations` (already data) | `{:excludes [...] :renames {...}}` — same content as today's `{:excluded :renamed}`, kebabed to match the `NsDepEntry` shape directly |
| `->ns-unmapped-default-imports` | `unmapped-default-imports` (already data) | unchanged semantics |

Existing `synth` renames (same pass; tests updated to the new names — all
callers are in-repo, verified at implementation time; keep no deprecated
aliases unless an external consumer turns up):

| Current | Renamed | Why |
|---------|---------|-----|
| `build-require-clauses` | `->require-clauses` | `build-` verb → `->` builder |
| `build-import-clauses` | `->import-clauses` | `build-` verb → `->` builder |
| `core-deviations` | `->core-deviations` | pure-noun builder → `->` + noun (cf. `core/->dep-graph`) |
| `unmapped-default-imports` | `->unmapped-default-imports` | pure-noun builder → `->` + noun |
| `var-ns-name` / `var-name` (private) | `get-var-ns-name` / `get-var-name` | pure extractions from var metadata, not builders → `get-` |

Delegation after the move (implement once in the leaf, project in `synth`):

- `->require-clauses` = join of `->ns-required` + `->ns-aliases` into
  the existing `[target :as a]` / `[target :refer [...]]` clause shapes, same
  sort. Note the join: one required ns may yield *two* clauses (alias +
  refer) exactly as today — the data split does not change clause output.
- `->import-clauses` = package-grouping projection of `->ns-imports`
  (same package-grouped, package-sorted output as today).
- `->core-deviations` = delegate to `->ns-refer-clojure` with keys mapped
  back to `{:excluded :renamed}` (implement once, project once;
  record the direction in the implementing PR).
- `->unmapped-default-imports` = delegate to `->ns-unmapped-default-imports`
  (identical semantics; pure alias).
- `reconstruct-ns-source` unchanged apart from calling the same builders
  (no output change — the existing tests are the proof).
- `->ns-deps-entry` composes the five data fns into one `NsDepEntry`
  (runtime-object path).
- `->ns-deps` maps entries over the production ns set (§3) with the
  source-vs-runtime choice per ns (§5).

## 5. Source-vs-runtime strategy

Same rule as `synth/synthesize-ns-source`: prefer the original classpath
source when present, else the live `Namespace` object.

- **Classpath source available** (`find-ns-resource` hits `.clj`/`.cljc`):
  parse the `ns` header form. Read with `read-string {:read-cond :allow}` (for
  `.cljc`) and take the first form; it must be `(ns …)`. Walk its clauses:
  `:require` (handle prefix lists, `:as`, `:refer`, `:rename`, nested
  vectors — full `ns`-macro clause grammar; `:as` specs feed `:aliases`,
  `:refer` specs feed `:require`), `:import` (both `(package Class …)`-group
  and single-class shapes → flat FQ `:imports` syms), `:refer-clojure`
  (`:exclude` / `:rename` / `:only` → the `{:excludes :renames}` map). This
  preserves what the runtime cannot: source parsing also preserves *intent*
  (e.g. an `:as` alias never dereferenced still shows in source; runtime
  `ns-aliases` likewise still shows it — both work; source wins on
  fidelity for `:refer-clojure :only`, which the runtime path cannot
  distinguish from `:exclude` of everything else).
- **No classpath source** (jars without sources, `eval`'d code — the exact
  case `reconstruct-ns-source` exists for): use `->ns-deps-entry` on
  `(the-ns ns-sym)`. Guard with `(find-ns ns-sym)` — a rulebase ns with
  neither source nor live ns yields an empty entry (all sections empty) rather
  than throwing; report via `tap>` (`:event :clara-rules/ns-deps-missing`,
  mirroring `synth/var-def-line`'s tap-on-skip convention).

Determinism: sort `:require` entries by `(str ns-name-sym)`, `:aliases` by
`(str alias-sym)`, `:refers` / `:imports` / `:excludes` /
`:unmapped-default-imports` lexicographically — same comparators as the
current clause builders.

`.cljc` note: header parsing uses `:read-cond :allow` and takes the
`:clj` branch the way kondo effectively does for our `:lang :clj` analysis;
document the limitation (`.cljc` `:cljs`-only deps may leak in — acceptable
for a static-dependency view, and matches what `->rule-source-analysis`
analyzes).

## 6. `DEFAULT_IMPORTS` fix

Facts to confirm at implementation time via REPL (before changing the
predicate):

```clojure
(keys clojure.lang.RT/DEFAULT_IMPORTS)   ; expect simple-name syms: String Object ...
(type (val (first clojure.lang.RT/DEFAULT_IMPORTS)))  ; expect java.lang.Class
```

Current state: `->unmapped-default-imports` (currently
`unmapped-default-imports`) is already exact (key-set difference against
`RT/DEFAULT_IMPORTS` — no prefix logic). The bug is only in
`->import-clauses`' (currently `build-import-clauses`) exclusion:

```clojure
(remove #(.startsWith (.getName ^Class (val %)) "java.lang."))
```

This drops *every* `java.lang.*` import, but only `DEFAULT_IMPORTS` members
are automatic in a fresh `ns`. A non-default `java.lang` class (e.g.
`java.lang.ProcessBuilder`, if absent from `DEFAULT_IMPORTS` — verify) that a
rule ns explicitly imports would be silently dropped from both the
reconstructed source (latent `ClassNotFoundException` on re-eval — the
round-trip tests would catch it only if a fixture imported such a class,
which none currently does) and the new `ns-deps` data.

Fix: exclude exactly the default-import set, comparing the *class*
(package included), not the simple name alone. `ns-imports` maps
simple-name symbol → `Class`; `DEFAULT_IMPORTS` maps simple-name symbol →
`Class`. An import is default only when the two classes are identical:

```clojure
(remove (fn [[simple-name ^Class cls]]
          (when-let [^Class default-cls (get clojure.lang.RT/DEFAULT_IMPORTS simple-name)]
            (= default-cls cls)))
        (ns-imports nsobj))
```

A simple-name key-set check is *not* enough: it would drop an explicitly
imported class from another package that happens to share a simple name
with a default. Keep the `^Class` hints on the remaining `.getName` /
`.getPackageName` uses (needed for `*warn-on-reflection*`).

Consequence for `reconstruct-ns-source`: output changes *only* for nses that
import a non-default `java.lang` class — previously dropped, now emitted.
That's a correctness fix; existing tests (no such fixture) stay green. Add a
regression test with a fake ns importing `java.lang.ProcessBuilder` (or
whatever the REPL confirms is non-default): reconstructed source must contain
an `:import` clause naming it, and `ns-deps` must list its FQ symbol.

## 7. Placement + dependency direction (the cycle constraint) — DECIDED

`analyze.clj` requires `core` (`extract-ancestors-fn`); therefore `core`
**must not** require `analyze` or `analyze.synth`. DECIDED: shared leaf ns
(Option A from the draft — move, don't copy).

- **New leaf ns** `clara.server.tools.graph.ns-deps` (sibling of `core.clj`,
  *not* under `analyze/`): owns the five data fns (§4), the `ns`-header
  parser (§5), the `NsDepEntry`/`NsRequireEntry`/`NsAliasEntry` schemas, and
  `->ns-deps-entry` / `->ns-deps`. `analyze.synth` requires it (clause
  builders delegate; `synth.clj` keeps its public fns as-is for compat).
  `core` requires it for `->rulebase-analysis*`. No cycle: the leaf requires
  only `clojure.string`, `schema.core`, `clojure.tools.logging` (for the
  contained-exception path, if any) — never `analyze` or `core`.
- **Why this name/level.** The ns answers "what does namespace X depend on"
  for *any* consumer: `core/->rulebase-analysis` (static rulebase info) and
  `analyze`+`synth` (annotations-gen source reconstruction) both pull from
  it. Placing it at `tools.graph.ns-deps` — next to `core`, `fact-types`,
  `nodes` — marks it as a shared graph-domain primitive rather than an
  analysis-pipeline detail (which is what `analyze.*` means in this repo).
  Alternatives considered and rejected: `analyze.ns-deps` (wrong direction —
  `core` can't depend on `analyze.*`); duplicating ~30 lines in `core`
  (two exclusion predicates to drift — exactly how the §6 bug survives).
- Rejected: computing `ns-deps` in `analyze/->rule-source-analysis` and
  passing it into `->rulebase-analysis` as an extra arg — couples a pure
  rulebase-level view to the kondo pipeline (which callers may skip —
  `core_test` builds analyses without ever touching `analyze`), and changes
  `->rulebase-analysis` arity for all existing callers (`cache.clj`,
  `client.clj`, `api.clj`, tests).

`find-ns-resource`/`ns->resource-base` stay in `analyze`; the leaf ns gets its
own tiny `ns->resource-base` equivalent (or `core`'s call passes a
`base-source-fn` hook in the style of `synth/synthesize-ns-source`'s
`:base-source-fn` — `(fn [ns-sym] -> source-str-or-nil)` — keeping classpath
lookup injectable and the leaf free of `clojure.java.io` opinions).
Recommend the hook: `->rulebase-analysis` gains no new arity; `->ns-deps`
takes `{:ns-syms … :base-source-fn …}` with a default that does the
`io/resource` lookup. Tests inject fake sources without touching the
classpath.

`core/->rulebase-analysis*` calls `->ns-deps` over the production ns set and
assocs `:ns-deps` (sorted-map) into the analysis map alongside `:rules` etc.

Purity/caching note: `->rulebase-analysis` is documented pure and cached in
`cache.clj` keyed on `(rulebase, annotations)`. `:ns-deps` from *live* `Ns`
objects is stable in practice (ns aliases/refers don't change post-load) but
not strictly pure. Document that `ns-deps` reflects ns state at analysis time;
no cache-key change proposed (same posture as `extract-ancestors-fn`, which
also reads live rulebase metadata).

## 8. API + docs updates

- `core/get-rulebase-analysis-external-view`: keep `:ns-deps` (it's plain
  data — syms/vectors; ensure JSON-serializable — symbols serialize as
  strings via the existing JSON layer; confirm with the API serialization
  test).
- `docs/explorer-graph-api.md`: extend the `GET /v1/rulebase-analysis`
  response shape with the locked keys:
  `"ns-deps": { "<ns>": { "require": [{ "ns-name-sym": …, "refers": […] }],
  "aliases": [{ "ns-name-sym": …, "alias-sym": … }],
  "imports": […],
  "refer-clojure": { "excludes": […], "renames": {…} },
  "unmapped-default-imports": […] } }`.
- UI types (`ui/src/lib/types/api.ts`) are a follow-up, out of scope for this
  server-side plan (note in the implementing PR).

## 9. Verification

Follow `AGENTS.md`: all quality gates via `server/Makefile`:

```bash
cd server
make test             # full suite incl. new tests below
make format           # cljfmt (new leaf ns must be formatted)
make format-check     # CI gate
make lint             # clj-kondo over src/test/dev
make reflection-check # *warn-on-reflection* — the ^Class hints in moved code
```

New tests (server/test, following `analyze_test.clj` fake-ns patterns):

1. **Data-syntax parity:** for a representative set of live nses (at least
   `loan-doc-rules` + a fake ns with alias+refer+import+exclude+rename),
   `(->require-clauses nsobj)` / `(->import-clauses nsobj)` /
   `(->core-deviations nsobj)` / `(->unmapped-default-imports nsobj)`
   before vs. after the refactor are `=` (guards the §4 rename +
   delegation step).
2. **Reconstructed-source stability:** `reconstruct-ns-source` output for the
   same nses is string-identical before/after (except the intended §6 fix
   case).
3. **`DEFAULT_IMPORTS` regression:** fake ns importing a verified non-default
   `java.lang` class → reconstructed source contains the `:import`; `ns-deps`
   lists the FQ sym; `->unmapped-default-imports` unaffected.
4. **Header parsing:** fake classpath sources (injected via `:base-source-fn`,
   no live ns needed) covering prefix-list requires, `:as` (→ `:aliases`) /
   `:refer` (→ `:require`), both `:import` shapes (→ flat `:imports`),
   `:refer-clojure` `:exclude`/`:rename` (→ `{:excludes :renames}`) →
   expected `NsDepEntry`.
5. **Source-vs-runtime agreement:** for `loan-doc-rules` (has both source and
   live ns), parsed-header entry `=` runtime-derived entry (modulo documented
   divergences, if any — e.g. `:cljs`-only deps in `.cljc`; none expected for
   `.clj`).
6. **Missing-everywhere:** ns with neither source nor live ns → empty entry +
   `tap>` event (bind a tap in-test, as with `:clara-rules/var-def-skipped`).
7. **`->rulebase-analysis` integration:** loan-doc session analysis carries
   `:ns-deps` with expected keys; `get-rulebase-analysis-external-view`
   retains it; `match?` assertions in `core_test` still pass.

Also load the `clojure-engineering` skill's Malli reference **only if** the
leaf ns introduces Malli — it won't (schema.core, matching `synth.clj`).

## 10. Work breakdown (suggested order)

1. **Confirm `RT/DEFAULT_IMPORTS` shape** at the REPL (keys/vals, §6) +
   pick a non-default `java.lang` class for the regression test. (No code.)
2. **Create leaf ns** `tools.graph.ns-deps` with the three schemas +
   the five `->ns-…` data fns moved (not copied) out of `synth`, with the
   fixed default-import predicate; rename the four `synth` fns per §4 with
   `synth` delegating to the leaf. Run parity tests (9.1,
   9.2) + `make test lint reflection-check`.
3. **Header parser** in the leaf ns + `->ns-deps-entry` / `->ns-deps`
   (§5); tests 9.4–9.6.
4. **Wire into `core/->rulebase-analysis*`** + external view (§7–8); test 9.7.
5. **Docs**: `explorer-graph-api.md` contract update (keys already locked,
   §3/§8).
6. Final `make test format-check lint reflection-check`.

## 11. Resolved questions (record)

- **D1** — aliases as separate `:aliases [{:ns-name-sym :alias-sym}]` vec.
  Amended in review: `:refers` is always the sorted symbol vector — a
  refer-all spec expands to the required ns's `ns-publics`; specs that refer
  nothing produce no entry. `:imports` default-detection compares the
  fully-qualified class, not the simple name.
- **D2** — `:refer-clojure {:excludes […] :renames {…}}` nested map.
- **D3** — `:unmapped-default-imports` included.
- **D4** — `:imports` are FQ class-name symbols, flat sorted vector.
- **Scope** — every production-owning ns (rules + queries).
- **Placement** — shared leaf ns `tools.graph.ns-deps`; move (not copy)
  deviation / require+import logic out of `synth`, `synth` exposes the
  renamed `->` fns delegating to the leaf (no deprecated aliases; see §4).
- **Naming** — new data fns are `->ns-required`, `->ns-aliases`,
  `->ns-imports`, `->ns-refer-clojure`, `->ns-unmapped-default-imports`;
  existing `build-…` fns become `->require-clauses` / `->import-clauses`,
  pure-noun builders become `->core-deviations` /
  `->unmapped-default-imports`, and the private var-meta readers become
  `get-var-ns-name` / `get-var-name`.
