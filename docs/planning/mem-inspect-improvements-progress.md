# mem-inspect improvements — progress

Tracks implementation of
[`mem-inspect-improvements-plan.md`](./mem-inspect-improvements-plan.md). Keep
this file updated alongside code changes.

Status: **in progress** — contract settled, implementation done except demo regen
(on hold by request).

## Order (from plan §7)

1. §4.3 node-memory relations (additive) — can land first
2. §4.1 split/rename `used-by` — `:used-by` removed entirely (no deprecated alias)
3. §4.2 drop stale insertion records (independent)
4. §2 fixture + §5 tests land with whichever change they cover
5. §4.4 API + docs
6. UI consumers

## Checklist

- [x] Read plan, `memory.clj`, vendored `inspect.clj`, `api.clj`, UI consumers,
      clara-rules engine/memory/compiler internals
- [x] §2 fixture: `memory-relations-test-rules`
- [x] §4.3 node-memory relations in `memory.clj`
- [x] §4.2 stale-insertion filtering (scoped to insertion-record facts)
- [x] §4.1 split `used-by` → `:supports-insertions-of` + `:supports-results-of`;
      `:used-by` removed entirely (not kept as a deprecated alias)
- [x] §5 tests in `memory_test.clj`
- [x] §4.4 API schemas (`api.clj`) + `docs/explorer-graph-api.md`
- [x] UI types + pages + `scrape-demo-data.js`
- [ ] Demo data regen — **deferred** (on hold; contracts had to settle first)
- [x] `make test lint format-check reflection-check` green (explorer)
- [x] `make format check lint` green (ui) + unit tests green

## Verification

- Explorer: `make test` 462 tests / 3093 assertions, 0 failures; `make lint`,
  `make format-check`, `make reflection-check` all clean.
- UI: `make format check lint` clean; `make test-unit` 34 passed.
- Demo data NOT regenerated in this round — the committed `session.json` still
  carries the old `in-results-of` / `blocks` keys and must be re-scraped once
  the contract is final.

## Decisions & notes

- **`:used-by` removed entirely** (not kept as a deprecated alias).  The two
  keys `:supports-insertions-of` and `:supports-results-of` supersede it; the
  top-level memory-analysis `:used-by` index was removed too.
- **Final relation keys** (fact → productions): `:supports-insertions-of`
  (rules), `:supports-results-of` (queries), `:matches-condition-of`,
  `:blocks-condition-of`, `:blocking-candidate-of` (all `ProductionDep[]`).
  "condition" is singular (existential: "matches a condition of"); a fact
  matching multiple conditions still yields the production once (deduped at
  production level).  `blocks` was renamed to `blocks-condition-of` to match
  the `-of` pattern; the query key was renamed `in-results-of` →
  `supports-results-of` because what is reported is the result's *support*
  (facts in the result token's `:matches` + accumulator inputs), not the
  binding values `clara.rules/query` returns.
- Query production deps previously carried `:ns ""` (clara-rules' `Query`
  schema has no `:ns-name`).  Fixed: `->production-dep` now derives the
  namespace from the production name via the shared
  `serialize/production-ns-name-sym` (also delegated to by
  `core/get-production-ns-name-sym`), so session query deps carry the same
  `:ns` as the static analysis side.  Demo data churn from this is accepted —
  correctness over byte-stability.
- §4.2 "retained" = `->fact-retained-pred`: a fact is kept when held by a
  join/negation node's element memory **or** accepted by no alpha node (presence
  undecidable).  This predicate is applied **only to facts named by an
  insertion record** (`filter-retracted-facts` now takes the insertion-record
  fact set), so a root fact read only by an accumulator condition (e.g.
  `RequiredDocument` under `acc/all`) is never dropped as "retracted" — it is
  not in element memory but is in accum-memory.  The same predicate still
  drives the `:supports-insertions-of` token filter, so a logical insertion of
  a type no condition reads (e.g. the fixture `Marker`) counts as retained.
  Accumulate-node facts are still not folded into `:matches-condition-of`
  (§4.3 follow-up, pending).
- Node relations skip facts the fact table cannot describe (nil / ISystemFact):
  `get-id` returns nil and the relation accumulator drops them.
