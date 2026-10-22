# Review 2: `callsite-resolver-updates` — refer-all re-attribution + local scope for resolvers

Second pass over the branch (8 commits over `113677f`, through `3f66d06 rev 2 fixes`), against
[`defect-refer-all-misattributes-callee.md`](./defect-refer-all-misattributes-callee.md) /
[`…-progress.md`](./defect-refer-all-misattributes-callee-progress.md) and
[`provide-local-scope-for-callsite-resolver-plan.md`](./provide-local-scope-for-callsite-resolver-plan.md) /
[`…-progress.md`](./provide-local-scope-for-callsite-resolver-progress.md). Review 1 findings are
addressed. This pass found three correctness gaps, and probes confirmed all three (see "Probes" at
the end).

## A. Correctness gaps (fix before merge)

### A1. Refer re-attribution rewrites *correct* qualified usages — verified

`refer/re-attribute-usage` rewrites when "`:to` is a referred ns that does not refer `:name`." That
condition does not imply the usage was a bare referred symbol. Example:

```clojure
(:require [clara.rules :as r :refer [defrule]]
          [my.facts :refer :all])      ; my.facts happens to define insert!
(r/insert! x)                          ; kondo: {:to clara.rules :name insert! :alias r} — correct
```

`refer-sets` gives `clara.rules → #{defrule}` and `my.facts → #{insert! …}`. The usage gets
rewritten to `my.facts/insert!`, so **boundary detection silently loses the insert**. The probe
reproduces this. A facts ns that wraps `insert!` is a realistic layout.

**Fix / simplification.** Only bare usages can be misattributed. kondo already shows which usages
are bare without any source read: a bare usage has no `:alias`, and `(- :name-end-col :name-col)`
equals `(count (name sym))`. Qualified tokens are longer, because they include `ns/`. For a bare
usage, the truth is simply `(get (ns-refers from) name)` → that var's ns. This one change:

- fixes the false positive,
- removes the candidate scan and the `ns-deps/->ns-required` dependency,
- fixes the "residual corner" (loser ns exporting only the colliding name), because `ns-refers` is
  the runtime winner by construction. Both residual-corner caveats in the progress doc and the
  test that pins the wrong answer can go.

A smaller fix is to also require `(nil? (find-var to/name))`, since in loaded code a qualified
usage always names a real var. That fixes A1 but keeps the corner case.

### A2. `:resolve-local` is scoped to the wrong span for traced args — verified

`->callsite-resolver-context` scopes to `(usage->span usage)`, the `insert!` call. The resolver,
however, receives the *traced* `:arg-form`, which `trace-local-form` may have pulled from a binding
outside that span:

```clojure
(let [fact-type :t/a
      f (make-fact fact-type {:x ?x})]
  (r/insert! f))
;; resolver gets :arg-form (make-fact fact-type {:x ?x}); (resolve-local 'fact-type) => nil
```

A resolver cannot resolve locals in the form it was handed. Fix: have `trace-local-form` return the
span of the form it stops on. That is `kondo/init-form-span` for a list init too, not only for a
symbol init. Carry it on `TracedArg` (e.g. `:traced-span`) and scope the resolver context to it.

### A3. The documented recursion contract cannot work

The plan (§2.3) and `rule-annotations.md` both say: "A resolver recurses by calling
`:resolve-local` again on symbols in a returned `:init-form`; the explorer caps the total depth."
But an init form sits outside the callsite span by definition, so every such lookup returns nil.
`test-resolve-local-boundary-context` actually asserts this (`lt-inner` → `[nil]`). The plan's §1
motivating example (`(type->fact-type ?kind)` → `type->fact-type`) is therefore unreachable. It
fails on the second hop, not because of open question §5.1.

As a result, the budget guards a recursion that cannot happen. Its only effect is an arbitrary nil
after 8 lookups, which looks the same as "shadowed / not found" and is undocumented.

**Fix.** Return a `:let-init` `LocalBinding` with its own `:resolve-local`, scoped to that
binding's `init-form-span` and sharing the parent's budget atom. Lookups stay "scoped to the form
you are looking at", which keeps the shadowing guarantee precise. The budget then really caps chain
depth. Alternatively, the closure can accumulate init spans, but a name that means different
bindings in different spans then degrades to nil. Either way, add an end-to-end test of the §1
shape.

## B. Concept gaps

1. **`:destructured` is a catch-all.** Any binding at depth > 2 under a *recognized* macro gets
   `:destructured`. That includes `catch` (verified: `caught-e → {:kind :destructured}`),
   `with-open`, `letfn`, `as->`, `when-first`, `reify`/`proxy` methods, and multi-arity params.
   The same `catch` binding classifies `:unknown` in a rule RHS and `:destructured` in a `defn`.
   It depends on an unrelated outer form. Cheap fix: have the scanner return the open-bracket
   *stack* instead of a count. Classify `:destructured` only when every container below depth 2 is
   `[`/`{`, since destructuring never goes through `(`. Treat the fn-family arity `(` explicitly,
   so multi-arity params become `:param`. Everything else is `:unknown`.
2. **`arg-span-set` still follows unclassified bindings** through `kondo/init-form-span`. That is
   the §1.1 misread the plan called a prerequisite to fix. It may be a deliberate over-approximation
   for ownership, but nothing says so. Plan §5.2(b) ("tighten `read-init-form` for non-resolver
   callers") is unanswered in the progress doc. Decide and record it.
3. **§5.1 (exact vs possible types) is undecided, but the user docs already invite it.** The
   literal-map fixture says the resolver "does the runtime-key lookup itself." `rule-annotations.md`
   should carry the completeness rule: report every possible value, and leave the callsite
   unresolved if any branch or default is not literal.
4. **`:init-form` is not canonicalized.** `:arg-form` goes through
   `utils/canonicalize-minted-names`, but `:init-form` does not, so `#(…)` reader gensyms reach
   resolvers nondeterministically.
5. Hook-generated locals (kondo hooks with `:derived-location`) were never probed. Bracket depth at
   a derived position is meaningless. It is probably safe (`:unknown`), but it is unpinned.

## C. Docs gaps

- `explorer/docs/rule-annotations.md` has the false recursion sentence (A3), and its nil
  explanation omits budget exhaustion. Line 355 ("Locals tracing … to its binding's init form") is
  still unconditional.
- `explorer/docs/analyze-pipeline-concepts.md` §2.7 step 2 and the §2.8 chain ("Local symbol? →
  trace to init form") are not updated for `:let-init`-only tracing.
- `explorer/docs/analyze-clj-kondo-notes.md` "Locals for callsite resolution" says nothing about
  classification, the "kondo `:locals` has no kind discriminator" finding, or the scanner. It is
  also the natural home for the cold-cache `:refer :all` misattribution, which is a kondo
  behaviour the analyzer now compensates for. Neither is documented outside `docs/planning/`.
- `analyze.callsite` ns docstring, step 3, still describes unconditional tracing.
- The `resolve-ctor-matches-for-inserter` docstring env list is missing `:local-usages-by-name`.
- The `analyze.clj` comment "(see clara.explorer.analyze.refer)" should use the in-scope alias:
  `` `refer/re-attribute-refer-all-usages` ``.
- Defect progress: open question 3 (how common ≥2 refer-alls are) is unanswered. The `:rename`
  "verified" claim is not pinned by a test.
- Local-scope progress, "Decisions": "mirroring `trace-local-form`'s cap" is inaccurate given A3.

## D. Engineering-guideline violations

| Where | Issue |
|---|---|
| `refer/refer-sets` | Bare-noun fn name, **shadowed by locals twice** (`re-attribute-usage` param, `if-let` in `re-attribute-refer-all-usages`). This is exactly the case the guideline cites. → `->refer-sets` (moot if A1's rewrite lands). |
| `callsite/relative-pos`, `binding-macro-usage-enclosing`, `kondo/source-text-for-span`, `kondo/bracket-depth-at` | Noun names → `->relative-pos`, `find-enclosing-binding-macro-usage`, `read-source-text-for-span`, `->bracket-depth-at` (or `measure-…`). |
| `binding-macro-usage-enclosing [ctx filename row col]` | 4 positional args. Pass the binding. `classify-binding` also rebuilds a one-key map from the `ctx` it already holds; just pass `ctx`. |
| `(declare span-contains-pos?)` + `within-span?` | `within-span?` ≡ `span-contains-pos?` (same half-open check). Move the span helpers above "locals tracing" and delete one; the `declare` goes away. |
| `trace-local-form` | 5-level nested `if-let`/`if`/`let`/`if`/`if-let`. Flatten to destructure + `cond`. |
| `->resolve-local` | `(seq (filter …))` then `(into #{} (keep :id) …)` → one `(into #{} (comp (filter …) (keep :id)) …)`. |
| `analyze.clj` `cond->` | A `true` test clause; use a plain `->` step (or see E2). |
| `kondo/skip-char-literal` | `^char` hints on `(.charAt ^String …)` results, which are already inferred primitive. The guideline only allows hints for confirmed warnings. |
| `refer.clj` docstrings | Continuation margins still mixed (2- vs 3-space; review 1 #7). The `ns_deps/->ns-required` reflow also changed its margin away from the rest of that file. |
| `refer_test` | Five copies of `create-ns` / `binding` / `eval` boilerplate → a `define-fake-ns!` helper. |

## E. Simplifications

1. **Refer:** direct `ns-refers` lookup for bare usages (A1).
2. **Order:** run re-attribution before `alias/alias-usage-map`, which currently groups the
   *uncorrected* `:var-usages`. It is harmless today, because alias only matches `?sym` by `:name`.
   Moving it keeps "every consumer sees corrected usages" true by construction and drops the
   `true` clause.
3. **One local lookup:** `find-local-binding` (first match) and `->resolve-local` (unique id) use
   different rules. Plan §2.4 said `trace-local-form` uses "the same lookup". Extract
   `find-unique-local-binding`, then layer classification and the budget on top.
4. **Scanner:** `bracket-depth-at` only needs the prefix from the macro start to the binding.
   Extract just that text, and `pos->offset` plus full-span materialization disappear.

## F. Performance

`classify-binding` now runs on **every `trace-local-form` hop**. Before, a hop was one index lookup
plus `read-init-form`. Now each call adds:

- `binding-macro-usage-enclosing`: an O(N) filter over *all* var-usages in the file, plus a sort;
- materializing the whole enclosing form's text (an entire `defn` for params) and two linear scans;
- `read-init-form`, which joins the **rest of the file** into a string (pre-existing). Resolver
  lookups now hit this path too.

It is also repeated: `trace-boundary-args` runs per rule over the reachable boundary usages, so a
helper shared by N rules is re-classified N times. Recommendations, roughly by payoff:

1. Memoize classification by `[filename local-id]` for the lifetime of one analysis index. kondo
   ids are stable within a run.
2. `var-usages-by-filename` is already `[row col]`-sorted (`first-usage-index-at-or-after`).
   Binary-search to the binding position and walk backwards: the first containing binding macro
   is the innermost. O(log n + k), no sort.
3. Bound `read-init-form` / `init-form-span` by the enclosing macro's end instead of end-of-file.
   `classify-binding` already has that span.
4. Small items: build `->callsite-resolver-context` only when `:callsite-resolver-fn` is set
   (pre-existing `pr-str`/canonicalize waste). In `resolve-ctor-usage-for-inserter`, build
   `resolve-local` inside `(when owner …)`.
5. The refer pass is negligible: O(usages) plus one `ns-refers` per analyzed ns.

Before merging, measure `->annotations-from-rule-source-analysis` wall time on a real rulebase,
`main` vs branch.

## G. Test gaps

- Refer: a qualified `alias/name` usage where a refer-all'd ns exports the same name must stay put
  (A1). Pin `:refer :all :rename`.
- `:resolve-local` on a traced arg (A2). Two-hop recursion through `:init-form` (A3, after the fix).
- Classification: `catch`, `with-open`, `letfn`, named-`fn` self binding (depth 1 → `:unknown`).
- `kondo/bracket-depth-at` has **no unit tests**. It is a public fn, and the `#_` claim was
  verified by hand only. Pin brackets in strings, `\(` / `\newline` char literals, `;` comments,
  `#_ #_`, and regex literals.

## Probes

Scratch files are under `explorer/target/tmp/` (removed by `make clean`):

- `refer_probe.clj`: `[{:to probe.rules :name insert! :alias r}]` came out as
  `{:to probe.facts …}` (A1). The same probe confirms kondo's `:alias` and
  `:name-col`/`:name-end-col` tell bare, aliased and fully-qualified usages apart.
- `scope_probe.clj` + `probe/scope_rules.clj`: `:arg-form (make-fact fact-type {:x ?x})`,
  `fact-type → nil` (A2). `caught-e → {:kind :destructured}` (B1).
