# offline-report-hierarchy-plan — progress

Status of [`offline-report-hierarchy-plan.md`](offline-report-hierarchy-plan.md).

## Checklist

- [x] §1 defect reproduced: `producers :loan-app/application-outcome` → `0 producer rule(s)` over `composed/loan-app-plus-disposition`; `consumers <record>` omitted the two notice rules.
- [x] Read `fact-types.edn` lazily; build ancestor map + transpose (descendants).
- [x] `producers` closes over descendants; `consumers` over ancestors; both print the exact / via-hierarchy split.
- [x] Resolve the substring argument to known fact-type names first; report ambiguous resolution.
- [x] Print `:unit` where present (`consumers` + `rule`, from `production-index.edn`).
- [x] Tests per §4, including the `edges` cross-check.
- [x] Run `make test` (babashka present) and `make format-check` / `make lint` / `make reflection-check`.

## Decisions / notes made while implementing

- `fact-types.edn` ancestors are already transitively closed (they are Clojure's `ancestors`, and compose re-closes across units), so one `get` per name is the full closure; the transpose is likewise complete. No re-closure in the script.
- Annotation types are canonicalized for matching via a new `type->name` (keyword keeps its leading colon) because `fact-types.edn` / `production-index.edn` key keyword fact types as `":foo/bar"`, whereas the existing `type->str` strips the colon for display. `type->str` stays for `types` / `curated`.
- §3: `rule` reads `production-index.edn` for `:unit` (annotations do not carry `:unit`); `consumers` already reads that part.
- Resolution against `fact-types.edn` means the first test's synthetic `analysis` needed a `:fact-types` section (it previously had none), and its keyword-fact strings were re-spelled with the leading colon (`":a/one"` etc.) to match the real on-disk convention.
- Follow-up: `find-key-name` now prints `Resolved <input> -> <fq-key>` when an unqualified rule name resolves to a single candidate (exact matches stay silent; ambiguous names already list their fully-qualified options).

## Verification

- `make test` — 399 tests, 2463 assertions, 0 failures / 0 errors.
- `make format-check` — clean.
- `make lint` — 0 errors, 0 warnings.
- `make reflection-check` — passed.

### Output over the checked-in composed unit

```
$ bb bin/annotations_report.bb test-resources/rules-annos/composed/loan-app-plus-disposition \
     producers :loan-app/application-outcome
3 producer rule(s) for :loan-app/application-outcome
  (0 exact, 3 via descendants)

via descendant clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
  clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?
    inserts: clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
  clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?
    inserts: clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
  clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?
    inserts: clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
```

```
$ bb bin/annotations_report.bb test-resources/rules-annos/composed/loan-app-plus-disposition \
     consumers clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
5 consumer rule(s) with clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome on the LHS
  (3 exact, 2 via ancestors)

exact
  clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved-args-demo
    unit: loan-app-ruleset
    lhs-types: clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
  ...
via ancestor :loan-app/application-outcome
  clara.server.tools.graph.rules.loan-outcome-notices/notice-approved-app
    unit: loan-disposition-ruleset
    lhs-types: :loan-app/application-outcome
  clara.server.tools.graph.rules.loan-outcome-notices/notice-denied-app
    unit: loan-disposition-ruleset
    lhs-types: :loan-app/application-outcome
```
