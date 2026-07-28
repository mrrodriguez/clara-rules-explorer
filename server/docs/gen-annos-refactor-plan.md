# gen-annos refactor — action plan

This doc is the action plan for the post-review refactor of the annotation
generation pipeline. The original review findings are kept below (§ Review)
for context; this top section tracks execution. Status is updated as chunks
land so work can stop/resume at any checkbox.

## Directives (from review discussion)

1. Perform the namespace split. If duplication appears across namespaces that
   are not closely related, create an `analyze.utils` ns to hold the shared
   pieces — no duplication, no mutual docstring cross-references.
2. Make the named abstractions real, with inner schemas so the structures are
   clearly identifiable (`AnalysisIndex`, `TracedArg`, `CallsiteEntry`,
   `CallsiteResolution`, ...).
3. Replace `:fact-constructor-match-fn` + `:fact-constructor-type-resolver-fn`
   with a single `:fact-constructors` vector API. **No backwards-compat shims** —
   the only consumer is the author.
4. Schema nits: where `s/Any` is unavoidable, comment why (open kondo maps,
   type-agnostic fact tokens); for open maps name the keys we care about;
   where a shape relates to `clara.server.graph.api`, replicate the relevant
   parts locally and docstring the relation — do not share schemas across the
   serialization boundary.
5. `s/validate` top-level schemas at the edges only
   (`GenerateAnnotationsOptions` in `generate-annotations-from-analysis`) —
   never in hot paths.
6. Address the performance concerns (see § Point 3 below).
7. Correctness observations: fix or document. Guiding principle: **favor
   promoting possibly-too-many insert-types over missing them entirely** and
   over inflating `resolved` status.

## Execution chunks

- [x] **Chunk 1 — format fix.** `make format`; `format-check` was failing on
  HEAD. → landed as `bfc45dd`.

- [x] **Chunk 2 — Index pass + performance + inner schemas.** → landed as `5287dde`.
  Benchmark: 800r×80k 3,260ms → 77ms; 1600r×160k ~21,400ms → 164ms (~linear now).
  Gates: format/lint/reflection/test all green (79 tests, 543 assertions).
  - New `analyze/utils.clj`: `fq-sym`, `var-usage-caller`/`callee`,
    `KondoVarUsage` schema. Dependency-free to avoid require cycles.
  - New `analyze/index.clj`: the Index pass. Boundary-fn sets move here;
    `AnalysisIndex` + `CtorUsageMatch` schemas; `build-analysis-index` builds
    once per run: call graph, `usages-by-caller` / `usages-by-callee`,
    `local-usages-by-name` / `locals-by-id`, memoized `reachable-set`,
    direct inserter/retractor sets, inserter/retractor type-maps (via
    by-caller index instead of full scans), ctor-callsite map, memoized
    `resolve-record-type`, memoized `read-ctor-form`.
  - `analyze.clj`: delete moved helpers; `extract-insert-types` resolves
    boundary usages via `usages-by-callee` lookup (kills the
    rules × all-usages quadratic term); `direct-callers` computed once;
    infer-ctx = `AnalysisIndex` + config.
  - `analyze/rhs.clj`: `find-local-binding` uses the locals indexes;
    `rhs-uses-binding?` uses `usages-by-caller`; `alias-usage-map` takes the
    by-caller index; new `TracedArg` / `CallsiteEntry` / `CallsiteResolution`
    schemas (docstring-only, not validated in hot paths).
  - Verify: `make format test lint reflection-check`; re-run REPL benchmark
    (expect ~linear; baseline: 800 rules × 80k usages = 3,260 ms).

- [x] **Chunk 3 — namespace split.** `analyze/rhs.clj` dissolves: → landed as the
  split commit after `5287dde`. Gates green (79 tests, 543 assertions).
  `CallsiteResolverContext` also moved from `analyze.clj` to `callsite.clj`.
  - `analyze/kondo.clj` — source reading at kondo positions
    (`source-text-at`, `read-boundary-args`, `read-init-form`, `read-ctor-form`).
  - `analyze/ctor.clj` — record/Java ctor resolution (`constructor-fn-name?`,
    `resolve-record-type`, `resolve-ctor-form`).
  - `analyze/callsite.clj` — boundary chain + constructor-of-interest
    resolution, `TracedArg`/`CallsiteEntry`/`CallsiteResolution`/`ViaChain`
    schemas.
  - `analyze/alias.clj` — var-alias chain machinery (`alias-usage-map` et al.).
  - Update requires in `analyze.clj`, `index.clj`, and tests.

- [ ] **Chunk 4 — `:fact-constructors` vector API.**
  `[{:match-fn (fn [fq-var-sym] -> truthy/nil)
    :type-resolver-fn (fn [ConstructorTypeResolverContext] -> {:resolved-types [...]})}]`
  - `build-constructor-callsite-map` pairs each matched usage with its spec
    (first matching spec in vector order wins — document precedence).
  - `resolve-ctor-callsite` uses the matched spec's resolver.
  - Remove the paired-options validation + old options entirely; update
    `GenerateAnnotationsOptions`, tests, `rule-annotations.md`,
    `extensible-fact-constructors-plan.md`.

- [ ] **Chunk 5 — schema nits + edge validation.**
  - `s/validate GenerateAnnotationsOptions` at the top of
    `generate-annotations-from-analysis`.
  - `:fact-type-spec-fn` input schema `s/Keyword` → `s/Any` (fact types can be
    class-name symbols/strings).
  - Comment every unavoidable `s/Any` (kondo open maps, type-agnostic tokens,
    full productions — name the keys of interest, relate to api.clj schemas
    by docstring).

- [ ] **Chunk 6 — correctness items (fix or document).**
  - **Fix:** `arg-reaches-ctor?` rule 3 identity matching — carry the innermost
    `:locals` binding through `trace-arg-form` (`TracedArg` gains
    `:traced-binding`); match ctor-usage by init-form start position instead
    of form value equality. Pinning test for the
    `(let [f (->fact :t m)] (insert! f) (insert! (->fact :t m)))` case.
  - **Document:** nested ctor calls `(insert! (->fact :a (->fact :b)))`
    promote both types — accepted over-promotion per directive 7.
  - **Document:** `:source-str`/`:ns-name-sym`/`:filename` on constructor
    callsites describe the *constructor* form (possibly in a helper ns),
    discriminated by the presence of `:constructor-sym`.
  - **Remove:** dead `:resolution-method` key (no producer) from
    `api.clj`, `serialize.clj`, `ui/src/lib/types/api.ts`.

- [ ] **Chunk 7 — final gates + docs.** `make format format-check test lint
  reflection-check`; UI `pnpm run format && pnpm run check && pnpm run lint`
  (if UI touched); confirm docs match shipped behavior.

---

# Review: Extensible fact-constructor resolution (`9240212`..HEAD, 6 commits)

_(The review below is the basis for the chunks above; kept verbatim.)_

I now have a complete picture. Here's the review.

---

## Verification status

| Gate                    | Result                                                                                                                                                                                                                                                                                  |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `make test`             | ✅ 79 tests, 543 assertions, 0 failures                                                                                                                                                                                                                                                 |
| `make lint`             | ✅ 0 errors, 0 warnings                                                                                                                                                                                                                                                                 |
| `make reflection-check` | ✅ clean                                                                                                                                                                                                                                                                                |
| `make format-check`     | ❌ **fails on HEAD** — `src/clara/server/tools/graph/analyze/rhs.clj` (misaligned map in `owning-arg`, bad indent in `resolve-constructor-callsites`). Working tree is clean, so commit `46682bd` itself is unformatted — CI would go red. Fix with `make format` before anything else. |

The implementation quality is otherwise high: the ownership/precedence semantics (constructor path owns its args, never double-reports, never claims non-inserted constructors) are genuinely subtle, and the test suite (`test-constructor-resolver-overrules-callsite-resolver`, `test-constructor-only-counts-on-an-insert-path`) pins them down well, including the honest-`:partial`/`:none` resolution accounting. The docs (`rule-annotations.md`, plan doc) accurately describe the shipped behavior.

---

## Point 1 — `analyze.rhs` is misnamed; it has ~5 concerns

You're right. Inventory of what's actually in `rhs.clj` (794 lines):

| Lines   | Concern                                     | Contents                                                                                                                                                                 |
| ------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 44–104  | **Kondo source reading**                    | `source-text-at`, `read-boundary-args`, `read-init-form`, (`read-ctor-form` at 587)                                                                                      |
| 107–183 | **Record/Java ctor type resolution**        | `constructor-fn-name?`, `resolve-record-type`, `resolve-ctor-form` — used by the _static_ path in `analyze.clj` too, so it's not even RHS-specific                       |
| 186–367 | **Boundary callsite chain**                 | locals tracing (`find-local-binding`, `trace-arg-form`), `resolver-context`, `apply-resolver`, `resolve-traced-arg`, `trace-boundary-args`, `resolve-boundary-callsites` |
| 369–541 | **Var-alias chains** (`:fact-type-spec-fn`) | `lhs-var-bindings`, `rhs-uses-binding?`, `alias-usage-map` + 4 schemas — this is _LHS_ introspection + synthetic usage injection, the opposite of "rhs"                  |
| 543–794 | **Constructor-of-interest callsites**       | `usage-encloses?`, `shortest-call-path`, `owning-arg`, `resolve-constructor-callsites`, `ViaChain` schemas                                                               |

Suggested split, with a one-way dependency direction (no cycles):

```
analyze/kondo.clj      ;; source-text-at, read-*-form, and the kondo usage helpers
                       ;; (absorbs var-usage-caller/callee from analyze.clj)
analyze/ctor.clj       ;; constructor-fn-name?, resolve-record-type, resolve-ctor-form
                       ;; (shared by static + dynamic paths)
analyze/callsite.clj   ;; TracedArg, boundary chain + constructor-of-interest resolution,
                       ;; resolver contexts, resolution-status, CallsiteResolution schema
analyze/alias.clj      ;; var-alias chain machinery (alias-usage-map et al.)
analyze/rhs.clj        ;; dissolves — or stays as the thin "boundary capture" pass
```

Also note the duplication this creates/tolerates today: `fq-sym` (rhs.clj:547) and `fq-name-sym` (analyze.clj:68) are the same function under two names; `ViaEntry`/`ViaChain` exist twice (rhs.clj symbols vs api.clj strings — that one is a legitimate boundary pair, but they should cross-reference each other in docstrings).

## Point 2 — Clearer abstractions: name the passes, schema the intermediates

The code already _has_ an implicit pipeline; it's just not named, and the stages communicate through a 15-key untyped `ctx` map (`build-infer-ctx`, analyze.clj:612) plus docstring-described return shapes. Making the passes explicit is the single biggest readability win:

1. **Acquire** — `analyze-session-rules` / `build-analysis-from-namespaces` → merged kondo analysis + `::combined-sources`. (Already clean.)
2. **Index** _(missing)_ — build **once**: call graph, `usages-by-caller`, `usages-by-callee`, locals indexes, memoized reachability, direct inserters/retractors, `inserter-type-map`, `constructor-callsite-map`. This is what `ctx` wants to be — give it a name (`AnalysisIndex`) and a schema. This is also where the performance fixes land (Point 3).
3. **Capture** — per rule: locate boundary usages → `trace-boundary-args` → `[TracedArg]`. `TracedArg` is currently docstring-only; schema it.
4. **Resolve** — an _ordered_ chain of callsite strategies, each `(fn [index traced-args] → CallsiteResolution)`. `resolve-constructor-callsites` and `resolve-boundary-callsites` already converge on the same return shape (`:callsites`/`:resolved-types`/`:resolution` + `:owned-arg-idxs`) — that shape _is_ the abstraction; promote it from docstring to `CallsiteResolution` schema.
5. **Assemble** — `infer-annotation-for-var`: promote types, classify `:full/:partial/:none`, build the annotation map. (Already clean.)
6. **Serialize** — symbol→string boundary (serialize.clj). (Already clean.)

**Resolver API unification.** There are now four loosely-coupled function options, two of which must be provided as a pair (enforced by a runtime `boolean`-compare `ex-info` in `generate-annotations-from-analysis`). The paired-validation smell says they want to be one value:

```clojure
:fact-constructors [{:match-fn         (fn [fq-var-sym] -> truthy/nil)
                     :type-resolver-fn (fn [ConstructorTypeResolverContext] -> {:resolved-types [...]})}]
```

A vector makes precedence explicit and generalizes to multiple constructors (today's single global match-fn can't express "two different constructors with different resolvers" — a real-world ruleset will have both `->fact` and, say, a `-as-fact` variant). The old paired options can be kept as a deprecated shim that expands into a one-element vector.

**Schema nits:**

- `GenerateAnnotationsOptions` (analyze.clj:644) is defined but **never enforced** — no `s/validate` anywhere. That's fine if it's deliberately documentation-as-schema (your stated preference), but say so in its docstring, or wire `s/validate` in dev/test only.
- `:fact-type-spec-fn`'s schema is `(s/=> s/Any s/Keyword)` — the _input_ is a fact type, which can also be a class-name symbol or string, not just a keyword. Should be `(s/=> s/Any s/Any)`.
- `ConstructorTypeResolverContext` `:rule s/Any` — same "avoid `:any`" standard you hold for Malli; even `s/Any` with a comment naming the production keys would do, or reuse the production shape from api.clj.

## Point 3 — Performance: measured, and it's quadratic

I benchmarked `generate-annotations-from-analysis` on the live nREPL with synthetic sessions (rules each inserting via shared helpers, plus unrelated-namespace usage noise to simulate a large merged multi-ns analysis):

| Rules | Total var-usages | Time     |
| ----- | ---------------- | -------- |
| 100   | 5k               | 56 ms    |
| 200   | 20k              | 205 ms   |
| 400   | 40k              | 798 ms   |
| 800   | 80k              | 3,260 ms |

Doubling both dimensions → **4× time** (linear in each: 200r×80k = 828 ms, 800r×20k = 769 ms). At a realistic 2,000 rules × ~200k usages you're looking at **~20 s**, before ctor options. Isolating the hot spot (400-rule scenario):

- `extract-insert-types` per-rule usage scans: **2,176 ms** ← dominant
- `build-inserter-type-map` (insert): 82 ms
- `var-reachability` × 400 rules: 1 ms

Root causes, in priority order:

1. **`extract-insert-types` (analyze.clj:260–330) scans the entire `:var-usages` vector per rule per direction** — `(some direct-target-call? usages)` then `(filter direct-target-call? usages)`, ×2 directions ×N rules. **Fix:** build `usages-by-callee` once (`group-by var-usage-callee`); boundary usages for a direction become `(mapcat by-callee target-fns)` filtered by reachable caller. That turns the dominant term from O(rules × all-usages) into O(rules × boundary-usages). This alone should erase ~90% of the measured time.
2. **`build-inserter-type-map` / `build-constructor-callsite-map` (analyze.clj:199–251) filter all var-usages per direct caller** — O(direct-callers × usages), ×3 maps. **Fix:** iterate only the usages of callers in each subtree via a by-caller index. Note `usages-by-container` is already built in `build-infer-ctx` — but _after_ these maps are computed, and not shared with them. The Index pass (Point 2) fixes the ordering.
3. **`direct-callers` is computed twice** for insert and retract — once in `generate-annotations-from-analysis` (as arguments) and again inside `build-infer-ctx` (analyze.clj:620–621).
4. **`find-local-binding` (rhs.clj:190) linear-scans `:local-usages` and `:locals` per boundary argument.** Index once by `[filename id]` and `[filename name]`.
5. **`rhs-uses-binding?` (rhs.clj:407) linear-scans all var-usages per LHS binding.** The by-caller index already answers it: only usages where `from-var` is the rule's snippet var.
6. **`resolve-record-type` (rhs.clj:123) does `find-ns`/`ns-resolve`/class-load per candidate**, repeatedly across map builds — memoize per `[ns-sym class-sym]`.
7. **`read-ctor-form` re-parses the same ctor form once per rule** that reaches a shared helper's ctor-usage — cache by usage identity (row/col/filename).
8. Reachability itself is fine today (1 ms) but is recomputed in four independent places (`precompute-reachability` ×3 map builders, `var-reachability` per rule, per-rule memos in `alias-context-for-fn`). A single memoized reachability in the Index collapses all of them and bounds the worst case as rule counts grow.

All of these are pure refactors — the current test suite is strong enough to protect them.

## Minor correctness observations

- **`arg-reaches-ctor?` rule 3 uses value equality** (`(= traced ctor-form)`, rhs.clj:654–665). Two textually-identical ctor forms in one rule — `(let [f (->fact :t m)] (insert! f) (insert! (->fact :t m)))` — cross-attribute: the inline ctor matches the _first_ insert's traced local (candidates are position-sorted), so insert #1 is claimed twice and insert #2 falls through to the boundary path. The docstring insists on usage-identity for rule 1 but rule 3 can't honor it because `trace-arg-form` discards the binding position. Carrying the locals binding through the trace and matching binding-span ↔ ctor-usage-span would make rule 3 identity-based too. Worth at least a documented limitation + a pinning test.
- **Nested ctor calls** — `(insert! (->fact :a (->fact :b)))`: both usages enclose, both claim the same arg idx, both types get promoted. False positive for `:b`. Probably rare, currently undocumented.
- `:source-str` on constructor callsites is the ctor form _from the helper's source_ (`(->fact :demo/tagged {:id id})` — note the helper-local `id`), whereas boundary callsites show the rule-RHS form. Different in kind; the UI renders both as "the callsite". Consider a `resolution-method`-style discriminator (the schema still has `:resolution-method`, now unused by this path — dead key?).
- Cosmetic: the file ends with `));; Schemas` — a mangled section banner (part of what cljfmt flags).

## Suggested sequencing

1. `make format` + commit (unblock CI) — trivial.
2. **Index pass** (perf, point 3.1–3.5): pure refactor, ~90% runtime reduction at scale, tests protect.
3. **Namespace split** (point 1): mechanical move + alias updates; do it right after 2 while the index boundary is fresh — the Index is exactly what `callsite.clj` should receive.
4. **`:fact-constructors` vector API** (point 2): additive, deprecate the paired options; independent of 2–3.
5. Rule-3 identity matching + nested-ctor semantics: small, test-driven, whenever.

Nice work on the semantics and the test discipline — the gaps are structural (one namespace doing five jobs, unnamed pipeline stages, per-rule full scans), all of which are fixable without touching the resolution semantics the tests now pin down.
