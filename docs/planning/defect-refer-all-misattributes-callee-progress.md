# `:refer :all` callee misattribution — progress

Tracks the fix for
[`defect-refer-all-misattributes-callee.md`](./defect-refer-all-misattributes-callee.md).
Keep this file updated alongside code changes.

Status: **done** — re-attribution implemented, tested, all explorer quality
gates green. Review 2 (see
[`provide-local-scope-for-callsite-resolver-review-2.md`](./provide-local-scope-for-callsite-resolver-review-2.md),
§A1) simplified the fix to a bare-usage `ns-refers` lookup.

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

Namespace `clara.explorer.analyze.refer`
(`explorer/src/clara/explorer/analyze/refer.clj`):

- `re-attribute-refer-all-usages` rewrites the `:to` namespace of kondo
  `:var-usages` entries misattributed under `:refer :all`. **Only bare usages
  can be misattributed**, and kondo shows which usages are bare without any
  source read: a bare usage has no `:alias`, and
  `(- :name-end-col :name-col)` equals `(count (name sym))` (qualified tokens
  are longer, because they include `ns/`). For a bare usage the truth is
  `(get (ns-refers from) name)` → that var's namespace, which is the runtime
  winner by construction.
- Qualified usages (aliased, fully-qualified) and `:rename`d usages (whose
  `:name` is the resolved original but whose span is the local symbol) are left
  untouched — kondo attributes them correctly, and only they could carry the
  false-positive a candidate scan would introduce.
- Wired into `clara.explorer.analyze/->annotations-from-rule-source-analysis`
  **before** the alias-usage pass and the index, so every consumer keys on the
  corrected callee.

## Why live `ns-refers` (not parsed headers)

Defect §5 suggested using the `ns-deps` refer-all expansion. The live
`ns-refers` table is the better source:

- It matches runtime resolution exactly — when the same name is exported by
  two refer-all'd namespaces, Clojure resolves to one winner; a parsed header
  would report "several qualify". The candidate set is provably 0-or-1.
- It needs no classpath source for the referred namespaces, only that the
  `:from` namespace is loaded — which `->annotations-from-rule-source-analysis`
  already guarantees for rule-owning namespaces (it `require`s every
  `:var-definitions` ns, and the session load has already loaded the rule ns).

The earlier candidate-scan approach (review 1) keyed on kondo's resolved
`:name`, which could in principle rewrite a *qualified* usage whose reported
`:to` happened not to refer the name (a facts ns that wraps `insert!`). The
bare-usage check removes that false positive entirely, and with it the
"residual corner" (a loser ns exporting only the colliding name) — `ns-refers`
is the runtime winner by construction, so there is no corner to document.

## Tests

- Unit: `explorer/test/clara/explorer/analyze/refer_test.clj` — fake target
  namespaces and consumer namespaces, built via a shared `define-fake-ns!`
  helper. Covers: bare usage rewritten to the second refer-all'd ns (both
  require orders), the already-correct case, an aliased `r/insert!` staying put
  even when a refer-all'd ns exports the same name (A1), a fully-qualified
  usage staying put, a bare unknown-namespace usage resolved to the live refer
  winner, the refer collision (rewritten to the runtime winner), an
  unresolvable name, an unloaded `:from`, and `:refer :all :rename` (a renamed
  usage is not bare, so it stays put).
- Integration fixture: `explorer/test/clara/explorer/test/rules/refer_all_rules.clj`
  — a real rule ns with `[clara.rules :as r]`,
  `[clara.explorer.test.rules.refer-all-decoy :refer :all]` (listed first, so
  clj-kondo attributes bare names from either refer-all'd ns to it) and
  `[clara.explorer.test.rules.helpers :refer :all]`, with
  `(r/insert! (->fact :refer-all/out {:x ?x}))`. `clara.rules` is aliased
  rather than refer-all'd so the fixture also lints cleanly in a cold-cache run
  (third-party `:refer :all` is unresolvable without a cache); the rationale
  lives in the fixture's ns docstring.
- Integration test: `test-refer-all-callee-reattribution` in
  `explorer/test/clara/explorer/analyze_test.clj` — asserts the `->fact`
  callsite resolves with `:constructor-sym
  clara.explorer.test.rules.helpers/->fact` (never `clara.rules/->fact`),
  `:insert-types [:refer-all/out]`, `:resolution :full`, and the via-chain
  boundary is `clara.rules/insert!`.

## Verification

- `make test` — 510 tests / 3298 assertions, 0 failures, 0 errors.
- `make lint` — 0 errors, 0 warnings.
- `make format-check` — clean.
- `make reflection-check` — no warnings.

## Notes / limitations

- The fixture namespace disables the project-wide `:refer-all` lint via its ns
  attr-map (`{:clj-kondo/config '{:linters {:refer-all {:level :off}}}}`)
  because it intentionally exercises `:refer :all`.
- Defect §5 open question 3 (how common ≥2 refer-alls are in a real rulebase)
  was not measured against a production rulebase. The answer no longer affects
  the approach: re-attribution is O(usages) with one `ns-refers` lookup per
  bare usage and no per-namespace candidate scan, so prevalence is irrelevant
  to cost.
- Boundary-side impact (defect §4 second row, `insert!` misattributed when the
  facts ns is listed first) is covered at the re-attribution layer; the
  integration fixture's first-listed refer-all'd ns is the decoy rather than
  `clara.rules` (which is aliased), so the second table row's end-to-end shape
  is exercised at the unit layer only.
