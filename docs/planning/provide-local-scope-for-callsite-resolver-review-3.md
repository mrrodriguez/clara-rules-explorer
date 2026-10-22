# Review 3: `callsite-resolver-updates` — after `7db1af0 rev 3 fixes`

Third pass. It checks `rev 3 fixes` against
[`…-review-2.md`](./provide-local-scope-for-callsite-resolver-review-2.md) and
re-reviews the branch as a whole.

**Bottom line:** Most of review 2 landed, and landed well. The refer rewrite is now small and
correct, the A2/A3 resolver scoping is right, the classification hardening is right, and the
scanner has tests. One change in this commit causes a **verified recall regression** in the
constructor pass (R1), and the progress doc says one thing was done that was not (R2). Everything
else is small.

## Review-2 disposition

| Item | Status |
|---|---|
| A1 refer false positive | ✅ Bare-usage check plus a direct `ns-refers` lookup. The candidate scan, the `ns-deps` dependency, and the corner case are all gone. Tests cover aliased, fully-qualified, `:rename`, and collision cases. |
| A2 traced-arg scope | ✅ `TracedArg :traced-span`; the resolver context scopes to it. Pinned by `test-resolve-local-traced-arg-scope`. |
| A3 recursion contract | ✅ Nested `:resolve-local` on `:let-init` bindings, with a shared budget. Pinned by `test-resolve-local-recursion`. |
| B1 `:destructured` catch-all | ✅ Bracket stack plus a header/body flag. Multi-arity params now classify `:param`, and `catch`/`with-open`/`letfn` classify `:unknown`. These tests do guard the fix: rule snippets are synthesized as `(def tag (fn [] rhs))`, so before this commit an RHS `catch` sat under a recognized `fn` and classified `:destructured`. |
| B2 `arg-span-set` | ⚠️ Decided, but the decision went too far. See **R1**. |
| B3 completeness rule | ✅ Documented in `rule-annotations.md`. |
| B4 `:init-form` canonicalization | ✅ |
| B5 hook-derived locals | ⏸ Recorded as unprobed. That is acceptable. |
| C docs | ✅ All items, apart from R2 and the nits under D. |
| D guideline table | ✅ Every row except the `true` `cond->` clause (R2). |
| E1 / E3 / E4 | ✅ `find-unique-local-binding` is now the single lookup. The scanner reads only the prefix up to the binding. |
| E2 ordering | ❌ Not done, though the progress doc says it was (R2). |
| F1 / F2 / F4 | ✅ The memoized classifier is per index, the innermost-macro search is a binary search plus a backward walk, and the resolver context and `resolve-local` are only built when needed. |
| F3 bounded init reads | ❌ Not done. See P1. |
| G tests | ✅ Apart from R1's missing fixture. |

## R. Must fix

### R1. `arg-span-set` no longer follows `doseq`/`for`/`loop` sources: constructor-pass recall regression (verified)

`arg-span-set` now expands only `:let-init` spans. That throws away a case that was correct.
For a **direct** `doseq`/`for`/`loop` binding (`:seq-binding` / `:loop` at stack depth 2), the form
after the symbol *is* the collection or initial value, and the values bound to the symbol flow out
of it. For an *ownership* question ("does this constructor's value reach this argument?") that span
is a sound over-approximation. It is not the §1.1 misread. The misread only applies to fn params
and nested destructuring.

Probe (`explorer/target/tmp/doseq_probe.clj`, with `helpers/->fact` registered):

```clojure
(defn build-facts [x] [(helpers/->fact :probe/doseq-out {:x x})])
(r/defrule doseq-ctor-rule [?x <- :probe/in] => (doseq [f (build-facts ?x)] (r/insert! f)))
```

| | `insert-types` | callsite |
|---|---|---|
| `HEAD~1` (rev 2) | `[:probe/doseq-out]` | `:full`, `:constructor-sym helpers/->fact` |
| `HEAD` (rev 3) | `nil` | `:none`, `"f"` |

`(doseq [f (helper …)] (insert! f))` is one of the most common RHS shapes, so this loses real
constructor attributions. No existing fixture exercises it, which is why the suite stayed green.

**Fix.** Keep `trace-local-form` and `:resolve-local` as they are: a seq binding's *value* is not
its coll, so they must not trace it. Give the ownership expansion its own rule instead:

- Have `classify-binding` also attach a `:source-span` (the next-form span) for direct
  `:seq-binding` and `:loop` bindings. `:let-init` keeps `:init-form-span`. Whether `:source-span`
  should reach resolvers through `LocalBinding` is a separate decision. It can stay internal.
- `arg-span-set` expands `:let-init` → `:init-form-span` and `:seq-binding`/`:loop` →
  `:source-span`. It still skips `:param`, `:destructured`, and `:unknown`.
- Add fixtures for `doseq`, `for` (a bound local inserted via `insert-all!` *outside* the `for`
  span), and `loop`, each reaching a constructor only through the collection or init.
- Correct the progress doc's §5.2(b) decision text and the `arg-span-set` docstring ("a … loop/seq
  binding has no statically-known init form to expand"). That reasoning holds for *values*, not for
  *ownership*.

### R2. Re-attribution still runs after the alias pass, and the progress doc says otherwise

`defect-refer-all-misattributes-callee-progress.md` says re-attribution is "Wired … **before** the
alias-usage pass and the index". But
[analyze.clj:L795-L806](../../explorer/src/clara/explorer/analyze.clj#L795-L806) still runs
`alias/alias-usage-map` on the uncorrected `:var-usages`, and the `cond->` still has the
`true (update :var-usages refer/re-attribute-refer-all-usages)` clause. Either move it, as review 2
E2 suggested, or fix the doc. Moving it is two lines and removes the `true` clause:

```clojure
rule-source-analysis (update rule-source-analysis :var-usages refer/re-attribute-refer-all-usages)
alias-by-rule (when fact-type-spec-fn (alias/alias-usage-map … (:var-usages rule-source-analysis) …))
rule-source-analysis (cond-> rule-source-analysis (seq alias-by-rule) (update :var-usages into …))
```

Synthetic alias usages have no `:name-col`, so they are never bare and are unaffected either way.

## C. Concepts / correctness nits

1. **`fn-param-binding?` checks `(>= (count stack) 3)` where it should check `(= 3 …)`.** A
   destructured param in a multi-arity fn (`([{:keys [a]}] …)`, stack `( ( [ { [`) classifies
   `:param`. The same param in a single-arity fn classifies `:destructured`. Both are label-only and
   safe, but they are inconsistent, and `= 3` makes them agree.
2. **The let-family, `loop`, and seq checks don't use `past-depth2-close?`.** The fn-family check
   does. A kondo binding at stack depth 2 *after* the binding vector has closed is not a
   binding-vector LHS. Today that can only happen through hook/`:lint-as` shapes, but `:let-init` is
   the one kind that hands out a value. Adding `(not past-depth2-close?)` to that branch costs
   nothing and makes the "never over-report" invariant in the `classify-binding` docstring hold by
   construction.
3. **`trace-local-form` falls back to `(or init-span span)`.** When the init form reads but its span
   does not, the traced form is paired with the *boundary* span. A2 then silently comes back for
   that callsite. Both come from the same reader, so this is rare, but the honest fallback is to
   stop tracing (`{:form arg-form :span span}`). The pairing then always holds.
4. **`bare-usage?` and hook-derived usages.** A var-usage emitted by a kondo hook carries the macro
   call's position. If `:name` happens to have the same length as the macro token (e.g. `defrule`
   and `insert!` are both 7 chars), the usage looks bare and is rewritten from `ns-refers`. The
   result is still the runtime truth for that bare name, so this is benign *unless* the hook emitted
   a qualified symbol whose bare name the ns refers from elsewhere. Very unlikely. Worth one
   sentence in the `bare-usage?` docstring, or a guard on `:derived-location` if kondo marks such
   usages.
5. **`classify-local-binding` has two code paths.** It uses the memoized classifier when the context
   has one, and falls back to `classify-binding` otherwise. Every production context now carries the
   classifier, so the fallback only covers tests or direct callers. Prefer making
   `:classify-local-binding` a required key of the per-run context (`BoundaryCallsiteCtx` /
   `ConstructorCallsiteCtx`) and dropping the fallback. Then no caller can get uncached
   classification by accident.

## D. Docs nits

- The `:resolve-local` row in `rule-annotations.md` still says "in the callsite's lexical scope".
  It is now the *traced argument form's* span, which matters to anyone reading A2.
- The `rule-annotations.md` `LocalBinding` text documents `:init-form-span` as resolver-visible.
  Fine, but say that it is the `{:filename :start :end}` shape (`callsite/SourceSpan`) and only
  meaningful for comparison or for nested `:resolve-local`.
- The `analyze-clj-kondo-notes.md` span-set paragraph ("every reached `:let-init` binding … `for`
  closure is still attributed") should change with R1.
- Defect progress: "one `ns-refers` lookup per bare usage". In fact it is one `ns-refers` *map* per
  distinct `:from` ns and one `get` per usage, which is better. Make the text match.

## P. Performance

The classifier memo, the binary search, and the lazy resolver context together address review 2's
main concerns. What remains:

1. **Unbounded init reads (F3, still open).** `kondo/read-init-form`, `kondo/init-form-start`, and
   `kondo/init-form-span` each `str/join` the *rest of the file* from the binding row. Each
   `:let-init` classification now runs `read-init-form` **and** `init-form-span` eagerly. That is
   about three end-of-file joins per let-init binding, and `init-form-start` already scans the same
   text. Memoization makes it once per binding instead of once per hop. On large rule files with
   many lets it is still the dominant cost of classification. Bounding the tail by the enclosing
   macro usage's `:end-row` (already in hand in `classify-binding`) makes it O(form size).
2. **Cache shape.** The cache is `(or (get @cache k) (… (swap! cache assoc k lb)))`, so a nil-valued
   entry would be recomputed. `classify-binding` never returns nil, so this is fine. Note it, or use
   `(if-let [e (find @cache k)] (val e) …)`, so a future nil-returning classifier does not lose the
   memo silently.
3. **Refer pass.** `bare-usage?` runs twice per usage (in `->refers-by-from` and in
   `re-attribute-usage`). That is trivial, and only worth fixing if you touch it anyway.
4. **Still recommended before merge:** time `->annotations-from-rule-source-analysis` on a real
   rulebase, `main` vs branch. The fixtures are too small to show the classification cost.

## G. Test gaps

- R1: `doseq` / `for` / `loop` ownership fixtures, as described above.
- C1: a multi-arity destructured param (pin whichever label you choose).
- An `insert-all!` over a `for` whose binding source is outside the arg span, to pin the R1 fix for
  `for` specifically. The existing L10 fixture has the constructor in the `for` *body*, so it is
  covered by `:let-init` and does not exercise this.

## Verification

Quality gates on `7db1af0`: `make lint` 0 errors / 0 warnings, `make reflection-check` passed,
`make format-check` clean, `make test` 510 tests / 3298 assertions, 0 failures.

Probes in `explorer/target/tmp/` (removed by `make clean`): `doseq_probe.clj` +
`probe/doseq_rules.clj` (R1, run against `HEAD` and a temporary `HEAD~1` worktree).
