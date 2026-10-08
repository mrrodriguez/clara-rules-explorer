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
- **`:resolve-local` depth cap (§2.3):** a per-resolver-context budget seeded
  with `max-resolution-depth`; each successful resolution consumes one unit and
  the fn returns nil once exhausted. This caps the *total* local-hop budget a
  resolver can spend from one context, mirroring `trace-local-form`'s cap for
  the automatic chain.

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

## Verification (all green)

- `make format-check` → all files formatted correctly.
- `make lint` → errors 0, warnings 0.
- `make test` → 495 tests, 3267 assertions, 0 failures / 0 errors.
- `make reflection-check` → no warnings in project code.
