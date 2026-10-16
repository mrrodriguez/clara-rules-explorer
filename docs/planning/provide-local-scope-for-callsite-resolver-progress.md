# Progress: Give callsite and constructor resolvers a sound view of local scope

Tracks work against
[`provide-local-scope-for-callsite-resolver-plan.md`](./provide-local-scope-for-callsite-resolver-plan.md).

## Status

Complete — all rollout steps implemented and verified.

## Investigation findings

- clj-kondo version on the classpath is `2026.08.04` (plan verified against
  `2026.05.25`). Analysis output shape for `:locals` / `:local-usages` /
  `:var-usages` confirmed on both.
- `:locals` entry gives the binding symbol's own position as `:row`/`:col`
  (1-indexed inclusive) and `:end-col` (exclusive), plus `:scope-end-row`/
  `:scope-end-col`. No kind discriminator is present in the entry itself.
- Binding macro (`let`, `if-let`, `when-let`, `loop`, `for`, `doseq`,
  `dotimes`, `defn`, …) var-usages carry a span covering the whole macro form
  (`:row`/`:col` → `:end-row`/`:end-col`), so the innermost enclosing binding
  macro is found by span containment.
- `:local-usages` `:col`/`:end-col` spans the whole *call form* when a local is
  called as a function (head symbol), while `:name-col`/`:name-end-col` spans
  just the symbol; the existing start-position-in-span check still works.
- Destructured, `loop`, `for`, `doseq`, `dotimes`, `defn` param bindings all
  classify cleanly by bracket nesting depth relative to the enclosing macro
  form: direct LHS of a `let`-family binding vector sits at depth 2 (macro form
  `(` counts as depth 1); nested destructuring sits at depth ≥ 3; `fn`/`defn`
  params sit at depth 2; `loop`/`for`/`doseq`/`dotimes` direct bindings sit at
  depth 2.
- `clojure.tools.reader` is on the classpath transitively, but its indexing
  reader does not expose nested form positions without a custom recursive
  reader. A small bracket-depth scanner (strings / char literals / line
  comments / `#_`-discard aware) in `analyze.kondo` was chosen instead — it
  needs no new dependency and never reimplements destructuring semantics
  (kondo already reports which positions are bindings).

## Decisions

- **Reader choice (§5.2):** a source-text bracket-depth scanner in
  `clara.explorer.analyze.kondo`, not a new dependency and not kondo's internal
  parsed form. Position matching remains by source position (kondo `:locals`
  entries), never by name.
- **`:resolve-local` depth cap (§2.3, revised by review 2 §A3):** a
  per-context budget seeded with `max-resolution-depth`, shared by every nested
  resolver in the chain. Each successful resolution consumes one unit and the
  fn returns nil once exhausted. Recursion works because a `:let-init`
  `LocalBinding` carries its own `:resolve-local`, scoped to the binding's
  `:init-form-span` — lookups stay "scoped to the form you are looking at", so
  the budget really caps chain depth rather than guarding a recursion that
  could never happen.

## TODO / remaining

- [x] `analyze.kondo`: `source-text-for-span`, `bracket-depth-at`, skip helpers.
- [x] `analyze.callsite`: binding classification, `->resolve-local`,
      `trace-local-form` rewrite, context schemas.
- [x] `artifacts.schema`: `FactTypeResolverContext` gains optional `:resolve-local`.
- [x] Docs: `explorer/docs/rule-annotations.md` resolver context tables.
- [x] Fixtures in `analyze-test-rules` + tests in `analyze-test`.
- [x] `make format` / `make lint` / `make test` / `make reflection-check`.

## Log

- Read skill files, plan, and the four source files in scope.
- Confirmed baseline: `clojure -M:test:run-tests -n clara.explorer.analyze-test`
  → 56 tests, 417 assertions, 0 failures.
- Inspected clj-kondo analysis shapes with a scratch namespace under
  `explorer/target/tmp/`.
- Implemented `analyze.kondo` bracket-depth scanner (mutually recursive
  skip helpers declared up front) and `source-text-for-span`.
- Implemented `analyze.callsite` binding classification (`:let-init :param
  :destructured :loop :seq-binding :unknown`), `->resolve-local`, rewrote
  `trace-local-form` onto the classified lookup, and added `:resolve-local` to
  both resolver context schemas.
- Added optional `:resolve-local` to `artifacts.schema/FactTypeResolverContext`.
- Documented `:resolve-local` + `LocalBinding` in
  `explorer/docs/rule-annotations.md`.
- Added §4 fixtures and §2.4 fixtures to `analyze-test-rules`, plus
  `test-resolve-local-boundary-context`,
  `test-resolve-local-constructor-context`, and
  `test-trace-local-form-classified` to `analyze-test`.
- Fixed clj-kondo lint warnings in the new fixtures (loop without `recur`,
  misplaced docstring, unused binding).
- **Correction (post-review):** the bracket-depth scanner's `#_` handling was
  removed. Its `skip-form` skipped to end-of-string (a bug), and `#_` needs no
  special case at all for depth: a discarded form is one balanced form, so its
  brackets are net-zero; strings/chars/comments inside it are already skipped.
  Verified against `[x 1 #_ #_ z 3 y 2]` → `[x 1 y 2]` (both `z` and `3`
  discarded).
- **Review 1 feedback**
  ([`provide-local-scope-for-callsite-resolver-review-1.md`](./provide-local-scope-for-callsite-resolver-review-1.md)):
  hardened `binding-macro-usage-enclosing` to match on `:name` *and*
  `clojure.core` `:to`; corrected the `binding-macro-syms` / `classify-binding`
  docstrings; pinned the previously-unexercised classification paths with new
  fixtures (`dotimes`, `if-let`/`when-let`/`if-some`/`when-some`, `for`/`doseq`
  `:let` modifiers, multi-arity `defn` params) and a `->resolve-local` budget
  test (`test-resolve-local-budget`).

## Review 2

Second pass ([`…-review-2.md`](./provide-local-scope-for-callsite-resolver-review-2.md))
found three correctness gaps, all fixed:

- **A2 — traced-arg scope.** `trace-local-form` now returns the span of the form
  it stops on (`:traced-span` on `TracedArg`), and `:resolve-local` scopes to it,
  so a resolver can see locals referenced in a traced init that sits outside the
  boundary call's span.
- **A3 — recursion contract.** A `:let-init` `LocalBinding` carries its own
  `:resolve-local`, scoped to its `:init-form-span` and sharing the parent's
  budget atom, so the documented two-hop recursion actually works; the budget
  now caps chain depth.
- **B1 — `:destructured` catch-all.** The scanner returns the open-bracket stack
  plus a depth-2 `[`-closed flag; `:destructured` requires every container below
  the macro form to be `[`/`{`, fn-family arity `(` is treated explicitly
  (multi-arity params are `:param`), and `catch`/`with-open`/`letfn`/… are
  `:unknown`.

Decisions recorded:

- **§5.2(b) (`arg-span-set`):** `arg-span-set` classifies each reached binding
  and expands `:let-init` → `:init-form-span`, plus `:seq-binding`/`:loop` →
  `:source-span` (the collection/initial value).  For *ownership* the
  seq/loop source span is a sound over-approximation; the §1.1 misread only
  applies to fn params and nested destructuring, which stay unexpanded.
- **§5.1 (exact-vs-possible types)** stays undecided for the *explorer* (no
  "one of" marker is added); the completeness rule is now documented in
  `rule-annotations.md` for caller resolvers.
- **`:init-form` canonicalization:** `classify-binding` canonicalizes minted
  names in `:init-form` the same way `:arg-form` is, so reader gensyms do not
  reach resolvers nondeterministically.
- **Hook-generated locals** (`:derived-location`) remain unprobed; they are
  expected to classify `:unknown` (no enclosing bracket to measure), but no test
  pins that yet.

## Review 3

Third pass ([`…-review-3.md`](./provide-local-scope-for-callsite-resolver-review-3.md))
found one verified recall regression (R1) and several nits, now addressed:

- **R1 — seq/loop ownership.** `arg-span-set` expanding only `:let-init` threw
  away direct `doseq`/`for`/`loop` sources. `classify-binding` now attaches an
  internal `:source-span` (the next-form span) to direct `:seq-binding`/`:loop`
  bindings, and `arg-span-set` expands it — for *ownership* that span is a sound
  over-approximation (the §1.1 misread applies only to fn params and nested
  destructuring). Pinned by `rule-ctor-doseq-source`, `rule-ctor-for-source`,
  `rule-ctor-loop-source`.
- **C1** — `fn-param-binding?` checks `= 3` (not `>= 3`) and `destructured-stack?`
  skips the fn-family arity `(`, so a destructured multi-arity param classifies
  `:destructured`, matching single-arity. Pinned by
  `rule-resolve-local-multi-arity-destructured`.
- **C2** — the `:let-init`/`:seq-binding`/`:loop` depth-2 branches also require
  `(not past-depth2-close?)`, so the never-over-report invariant holds for
  hook/`:lint-as`-derived bindings too.
- **C3** — `trace-local-form` stops tracing when an init form reads but its span
  does not, instead of pairing the init form with the boundary span.
- **C4** — `bare-usage?` docstring notes the benign hook-derived case.
- **C5** — `classify-local-binding` no longer has a fallback path;
  `:classify-local-binding` is a required key of both per-run ctx schemas.
- **R2** — re-attribution stays a final `cond->` `true` step (after alias-usage
  injection, which is unaffected: synthetic usages carry no `:name-col`); the
  defect progress doc now says so.

Remaining (non-blocking perf recommendations): P1 (bound `read-init-form` /
`init-form-span` reads by the enclosing macro's end rather than end-of-file),
P3 (`bare-usage?` runs once per usage in `->refers-by-from` and again in
`re-attribute-usage`), and P4 (time
`->annotations-from-rule-source-analysis` on a real rulebase, `main` vs
branch).

## Verification (all green)

- `make format-check` → all files formatted correctly.
- `make lint` → errors 0, warnings 0.
- `make test` → 511 tests, 3308 assertions, 0 failures / 0 errors.
- `make reflection-check` → no warnings in project code.
