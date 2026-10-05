# mem-inspect improvements — progress

Tracks implementation of
[`mem-inspect-improvements-plan.md`](./mem-inspect-improvements-plan.md). Keep
this file updated alongside code changes.

Status: **done** — all changes landed and verified.

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
- [x] §4.2 stale-insertion filtering
- [x] §4.1 split `used-by` → `:supports-insertions-of` + `:in-results-of`; `:used-by`
      removed entirely (not kept as a deprecated alias)
- [x] §5 tests in `memory_test.clj`
- [x] §4.4 API schemas (`api.clj`) + `docs/explorer-graph-api.md`
- [x] UI types + pages + `scrape-demo-data.js` + regenerated demo data
- [x] `make test lint format-check reflection-check` green (explorer)
- [x] `make format check lint` green (ui) + unit tests green

## Verification

- Explorer: `make test` 462 tests / 3104 assertions, 0 failures; `make lint`,
  `make format-check`, `make reflection-check` all clean.
- UI: `make format check lint` clean; `make test-unit` 34 passed.
- Demo data regenerated via `demo-setup` + `demo-run` + `scrape:demo`;
  `session.json` now carries the new relation keys, `rulebase.json` unchanged.

## Decisions & notes

- **`:used-by` removed entirely** (not kept as a deprecated alias): the two keys
  `:supports-insertions-of` and `:in-results-of` fully supersede it, so there is
  no transitional surface.  The top-level memory-analysis `:used-by` index was
  removed too.
- Query production deps previously carried `:ns ""` (clara-rules' `Query`
  schema has no `:ns-name`).  Fixed: `->production-dep` now derives the
  namespace from the production name via the shared
  `serialize/production-ns-name-sym` (also delegated to by
  `core/get-production-ns-name-sym`), so session query deps carry the same
  `:ns` as the static analysis side.  Demo data churn from this is accepted —
  correctness over byte-stability.
- §4.2 "retained" = `->fact-retained-pred`: a fact is kept when held by a
  join/negation node's element memory **or** accepted by no alpha node (presence
  undecidable).  Only facts absent from element memory yet alpha-acceptable are
  dropped (retracted).  This single predicate drives both the `:all-facts`
  filter and the `:supports-insertions-of` token filter, so a logical insertion
  of a type no condition reads (e.g. the fixture `Marker`) still counts as
  retained — it was not retracted, just never observed by any alpha node.
  Accumulate-node memory read is deferred (§4.3 follow-up), so an insertion
  recorded only via an accumulator condition is out of scope for the
  stale-filter for now (no such case in current fixtures).
- Node relations skip facts the fact table cannot describe (nil / ISystemFact):
  `get-id` returns nil and the relation accumulator drops them.
