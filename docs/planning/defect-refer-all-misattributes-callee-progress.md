# `:refer :all` callee misattribution — progress

Tracks the fix for
[`defect-refer-all-misattributes-callee.md`](./defect-refer-all-misattributes-callee.md).
Keep this file updated alongside code changes.

Status: **done** — re-attribution implemented, tested, all explorer quality
gates green.

## Root cause (answering defect §5 open question 1)

The fault is in how the analyzer configures clj-kondo, not in clj-kondo itself
per se. `analyze-source-code` lints from stdin with an explicit `:config-dir`
(the bundled clara-rules import) and no `.clj-kondo/.cache` for the referred
libraries. With nothing known about what `:refer :all` brings in, kondo has no
resolution and attributes every bare name to the **first** refer-all'd
namespace in the `:require`. Confirmed with a direct
`analyze/analyze-source-code` run: with `[fx.a :refer :all] [fx.b :refer :all]`,
both `insert!` (real `fx.a` var) and `->fact` (real `fx.b` var) came back as
`{:to fx.a …}`.

An explicit `:refer [->fact]` is attributed correctly (defect table row 3), so
only the refer-all path needed correction — no cache/source injection
(open question 2) is required.

## Fix

New namespace `clara.explorer.analyze.refer`
(`explorer/src/clara/explorer/analyze/refer.clj`):

- `re-attribute-refer-all-usages` — rewrites `:to` on kondo `:var-usages`
  entries misattributed under `:refer :all`. For each usage it consults the
  **live** `:from` namespace's refer table (`ns-refers` via
  `ns-deps/->ns-required`, so `:refer :all` is already expanded to concrete
  vars and each local symbol resolves to its winning var — runtime semantics,
  not kondo's guess). A usage whose `:to` namespace is referred but does not
  provide `:name` is rewritten to the single other referred namespace that
  does; when none qualify it is left untouched.
- Wired into `clara.explorer.analyze/->annotations-from-rule-source-analysis`
  right before `index/->analysis-index`, after the `require` loop that loads
  the analyzed namespaces and after alias-usage injection. This is the single
  point where boundary detection and constructor-of-interest matching key on
  the resolved callee.

The correction runs on the merged `:var-usages` only; `::combined-sources` and
source-reading are untouched.

## Why live `ns-refers` (not parsed headers)

Defect §5 suggested using the `ns-deps` refer-all expansion. The live
`ns-refers` table is the better source here:

- It matches runtime resolution exactly — when the same name is exported by
  two refer-all'd namespaces, Clojure resolves to one winner; the parsed
  header would report "several qualify" and leave kondo's (wrong) first pick.
- It needs no classpath source for the referred namespaces, only that the
  `:from` namespace is loaded — which `->annotations-from-rule-source-analysis`
  already guarantees for rule-owning namespaces (it `require`s every
  `:var-definitions` ns, and the session load has already loaded the rule ns).

## Tests

- Unit: `explorer/test/clara/explorer/analyze/refer_test.clj` — fake target
  namespaces (`fake.refer.a` = `insert!`, `fake.refer.b` = `->fact`) and
  consumer namespaces referring both wholesale in each order. Covers both
  defect table rows, the already-correct case, unknown-namespace keyword
  `:to`, unresolvable names, and unloaded `:from`.
- Integration fixture: `explorer/test/clara/explorer/test/rules/refer_all_rules.clj`
  — a real rule ns with `[clara.rules :refer :all]`
  `[clara.explorer.test.rules.helpers :refer :all]` and
  `(insert! (->fact :refer-all/out …))`.
- Integration test: `test-refer-all-callee-reattribution` in
  `explorer/test/clara/explorer/analyze_test.clj` — asserts the `->fact`
  callsite resolves with `:constructor-sym
  clara.explorer.test.rules.helpers/->fact` (never `clara.rules/->fact`),
  `:insert-types [:refer-all/out]`, `:resolution :full`, and the via-chain
  boundary is `clara.rules/insert!`.

## Verification

- `make test` — 491 tests / 3240 assertions, 0 failures, 0 errors.
- `make lint` — 0 errors, 0 warnings.
- `make format-check` — clean.
- `make reflection-check` — no warnings.

## Notes / limitations

- The fixture namespace disables the project-wide `:refer-all` lint via its ns
  attr-map (`{:clj-kondo/config '{:linters {:refer-all {:level :off}}}}`)
  because it intentionally exercises `:refer :all`.
- `:refer :all :rename` needs no correction: kondo records the rename pair in
  `:referred-vars` independently of the cache (verified — a renamed usage
  resolves to `{:to fx.b :name ->fact}` with no cache), so re-attribution is a
  no-op there. The one residual corner case is a renamed local whose *resolved*
  name collides with another refer-all'd namespace's public (pathological); the
  candidate lookup keys on kondo's resolved `:name` while `ns-refers` keys on
  the local symbol, so such a collision could in principle be rewritten. Out of
  scope for this defect.
- Boundary-side impact (defect §4 second row, `insert!` misattributed when the
  facts ns is listed first) is covered by the unit test at the re-attribution
  layer; an end-to-end fixture for that order was not added since the
  first-listed-ns-is-`clara.rules` case is the realistic one.
