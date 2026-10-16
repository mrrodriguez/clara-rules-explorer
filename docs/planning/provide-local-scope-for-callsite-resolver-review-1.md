# Review: `callsite-resolver-updates` branch — refer-all re-attribution + local scope for resolvers

Review of the branch's changes relative to
[`defect-refer-all-misattributes-callee.md`](./defect-refer-all-misattributes-callee.md),
[`defect-refer-all-misattributes-callee-progress.md`](./defect-refer-all-misattributes-callee-progress.md),
[`provide-local-scope-for-callsite-resolver-plan.md`](./provide-local-scope-for-callsite-resolver-plan.md),
and
[`provide-local-scope-for-callsite-resolver-progress.md`](./provide-local-scope-for-callsite-resolver-progress.md)
(6 commits over `113677f`).

**Quality gates (re-verified locally):** `make format-check` ✓, `make lint`
0 errors / 0 warnings ✓, `make reflection-check` ✓, `make test` — 495
tests / 3267 assertions, 0 failures ✓ (matches the local-scope progress
doc's numbers).

## What's solid

- **Refer fix design** (`clara.explorer.analyze.refer`): using live
  `ns-refers` via `ns-deps/->ns-required` is the right call — it matches
  runtime resolution exactly, including the collision case (two refer-all'd
  namespaces exporting the same name → `ns-refers` records only the winner,
  so the candidate set is provably 0-or-1). This is *better* than giving
  kondo a cache, which was the defect doc's open question 2. Verified
  `index/->analysis-index` is called in exactly one place, immediately
  after re-attribution, so every downstream consumer (boundary detection,
  ctor matching, call graph) sees corrected usages. Emitted callsite
  entries never carry `:resolve-local` (fn values) — no persistence/EDN
  leak.
- **Local-scope classification**: checked clj-kondo upstream — `reg-local!`
  emits only `:name :str :id :row :col :end-row :end-col :scope-end-*
  :derived-location`; there is genuinely no kind discriminator, so the
  bracket-depth approach isn't failing to leverage an available kondo
  pattern. The `#_`-discard insight (net-zero depth) is correct, and the
  post-review correction is documented in the progress log. The scanner
  handles regex literals (via string skip), char literals, and comments
  correctly. The `trace-local-form` rewrite is strictly more sound:
  parameters, loop bindings (whose init is only the *first* iteration's
  value), and destructured symbols are no longer traced to bogus "init"
  forms.
- **Planning-doc hygiene**: no references to `docs/planning/*` anywhere
  outside `docs/planning/`; the deleted planning docs leave no dangling
  links; the emacs test-helper change removes a dangling reference to a
  nonexistent planning doc. ✓
- No cyclic-dependency hacks; `callsite/LocalBinding` is aliased from
  `analyze.callsite` into `artifacts.schema` rather than restated, per that
  ns's stated convention. The clj-kondo bump to `2026.08.04` also
  deduplicates the `:lint` alias pin.

## Findings

### Doc inconsistencies (should fix before merge)

1. **`defect-refer-all-misattributes-callee-progress.md` describes a
   fixture that no longer exists.** It says the integration fixture
   requires `[clara.rules :refer :all]` + `[helpers :refer :all]` with a
   bare `(insert! ...)`. The actual `refer_all_rules.clj` (after "rev 1
   fixes") uses `[clara.rules :as r]` and a `refer-all-decoy` namespace as
   the first refer-all'd ns, with `r/insert!`. The rationale lives only in
   the fixture's ns docstring ("third-party `:refer :all` is unresolvable
   in a cold-cache run"). The progress doc's fixture paragraph and its
   closing "the first-listed-ns-is-`clara.rules` case is the realistic one"
   note are stale. Its verification counts (491/3240) are also
   pre-local-scope.
2. **`provide-local-scope-for-callsite-resolver-plan.md` still says
   `Status: proposal`** while its progress doc says complete and the defect
   doc in the same directory was updated to "fixed". Treat both features
   the same way.
3. **`explorer/docs/rule-annotations.md` `:arg-form` row** still says
   "locals already traced to their init forms" — after the §2.4 behavior
   change, only `:let-init` (let-family) locals are traced; params /
   loop / destructured are not. A resolver author reading the table will
   assume all locals are chased. One clause would fix it.

### Correctness / brittleness

4. **`callsite/binding-macro-usage-enclosing` matches binding macros by
   `:name` only.** A user- or library-defined var named `for`, `loop`,
   `let`, `defn`, etc. whose usage span encloses the binding would
   misclassify. Cheap hardening: also require `(:to u)` ∈
   `#{clojure.core}` (all names in `binding-macro-syms` are core vars).
   Low-probability, but the failure mode is silent misclassification.
5. **`binding-macro-syms` docstring overclaims.** It says
   `binding`/`with-open`/`with-redefs` bindings "classify as `:unknown`" —
   true only when no *outer* recognized binding macro encloses them; nested
   inside a `let`, they classify `:destructured` (depth > 2). Both outcomes
   are safe (no `:init-form` is ever handed out), but the docstring should
   say what actually holds: they never classify `:let-init`.
6. **Docstring qualifier style:** `classify-binding` references
   `` `analyze.kondo/bracket-depth-at` `` — that qualifier resolves in no
   ns (the alias there is `kondo`). Per the docstring standards it should
   be `` `kondo/bracket-depth-at` ``. (The looser `analyze.kondo`
   *namespace* mentions elsewhere in the file are pre-existing; the
   var-qualified one is new.)
7. Minor cosmetic: `refer.clj` ns docstring paragraphs have inconsistent
   left margins (2- vs 3-space continuation).

### Test-coverage nuances worth pinning

The suite covers every §4 fixture category from the plan, but these
behaviors are implemented and unpinned:

- `dotimes` (in `seq-binding-macro-syms`, no fixture) and
  `if-let`/`when-let`/`if-some`/`when-some` (in `let-init-macro-syms`, no
  fixture — only plain `let` is exercised). A kondo regression changing
  either's usage spans would be invisible.
- `for`/`doseq` with a `:let` modifier → classified `:destructured` (init
  *is* per-iteration knowable; the conservative answer is fine, but pin it
  so the choice is deliberate).
- Multi-arity `defn`/`fn` params → `:destructured` (kind name is wrong-ish
  but safe; pin it).
- The `->resolve-local` budget: nothing asserts the 9th resolution from one
  context returns nil. Since this is the documented recursion guard, it
  deserves a direct unit test of `->resolve-local` (it's private — the
  fixture route with >8 lookups works too).
- Refer collision case: two refer-all'd namespaces exporting the *same*
  name — the runtime-winner rewrite is the design's cleverest property and
  is only implied by the docstring, never tested. (Residual pathological
  corner — the loser namespace exports *only* the colliding name, so it's
  absent from `refer-sets` and the usage passes through wrong — is
  undocumented; at minimum note it beside the existing `:rename` note in
  the progress doc.)
- The plan's §1 motivating pattern — local holding a literal map *looked up
  by a runtime key* in the argument — is covered only piecewise
  (literal-map init + local-as-head separately), never end-to-end.
  Acceptable given open question §5.1 (exact-vs-possible types) is
  deliberately unresolved, but the plan doc presents that example as the
  driver.

**Bottom line:** implementation is sound and well-tested; the two real
action items are the stale refer-fixture description in the progress doc
(finding 1) and the name-only binding-macro match (finding 4). Everything
else is small.
