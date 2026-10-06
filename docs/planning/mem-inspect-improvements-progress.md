# mem-inspect improvements — progress

Tracks implementation of
[`mem-inspect-improvements-plan.md`](./mem-inspect-improvements-plan.md). Keep
this file updated alongside code changes.

Status: **in progress** — contract settled, implementation done (accumulators
included) except demo regen (on hold by request).

## Order (from plan §7)

1. §4.3 node-memory relations (additive) — includes accumulate nodes
2. §4.1 split/rename `used-by` — `:used-by` removed entirely (no deprecated alias)
3. §4.2 drop stale insertion records (independent) — retention includes accum-memory
4. §2 fixture + accumulator fixture + §5 tests land with whichever change they cover
5. §4.4 API + docs
6. UI consumers

## Checklist

- [x] Read plan, `memory.clj`, vendored `inspect.clj`, `api.clj`, UI consumers,
      clara-rules engine/memory/compiler internals
- [x] §2 fixture: `memory-relations-test-rules`
- [x] Accumulator fixture: `accumulator-relations-test-rules` (both accumulate
      node types)
- [x] §4.3 node-memory relations in `clara.explorer.memory.inspect` (join,
      negation, and accumulate nodes)
- [x] §4.2 stale-insertion filtering (scoped to insertion-record facts; retention
      reads join/negation element memory and accumulate-node memory)
- [x] §4.1 split `used-by` → `:supports-insertions-of` + `:supports-results-of`;
      `:used-by` removed entirely (not kept as a deprecated alias)
- [x] §5 tests in `inspect_test.clj` + `memory_test.clj`
- [x] §4.4 API schemas (`api.clj`) + `docs/explorer-graph-api.md`
- [x] UI types + pages + `scrape-demo-data.js`
- [ ] Demo data regen — **deferred** (on hold; contracts had to settle first)
- [x] `make test lint format-check reflection-check` green (explorer)
- [x] `make format check lint` green (ui) + unit tests green

## Verification

- Explorer: `make test` 479 tests / 3217 assertions, 0 failures; `make lint`,
  `make format-check`, `make reflection-check` all clean.
- UI: `make format check lint` clean; `make test-unit` 34 passed.
- Demo data NOT regenerated in this round — the committed `session.json` still
  carries the intermediate `in-results-of` / `blocks` keys (and none of the
  final `supports-results-of` / `blocks-condition-of`) and must be re-scraped
  once the contract is final.

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
- §4.2 "retained" = `->fact-retained-pred`: a fact is kept when held by
  beta-node memory — join/negation element memory **or** accumulate-node memory
  (accumulator `:from` inputs) — or accepted by no alpha node (presence
  undecidable).  `->beta-memory-facts` unions alpha-memory facts with
  accumulate-node facts, and `->wrapped-all-facts` unions that with the
  insertion-record fact set; the retraction filter then drops only
  insertion-record facts that fail the predicate.  The same predicate drives
  the `:supports-insertions-of` token filter, so a logical insertion of a type
  no condition reads (e.g. the fixture `Marker`) counts as retained.
- Accumulators are first-class inspection inputs now.  `->beta-node-relations`
  folds accumulate-node facts into `:matches-condition-of` for both
  `AccumulateNode` and `AccumulateWithJoinFilterNode` (candidate facts,
  condition-local).  `->rule-insertion-supports` expands accumulator conditions
  to their `:from` inputs via `token->facts`, matching the
  `condition-match->facts` expansion `->query-result-supports` already did.  A new
  `accumulator-relations-test-rules` fixture covers an inserted fact read only
  by an accumulator `:from` condition.
- Node relations skip facts the fact table cannot describe (nil / ISystemFact):
  `get-id` returns nil and the relation accumulator drops them.
- **Vendor removed, new boundary `clara.explorer.memory.inspect`.**  The
  vendored `clara.explorer.vendor.tools.inspect` was deleted.  A new
  `clara.explorer.memory.inspect` ns owns working-memory semantics only, keyed
  by clara-rules' production/query records and fact wrappers, with **no**
  explorer index info (ProductionDep, route-id, ordering, known-set), so it
  could move upstream.  Its public surface is the nine functions
  `clara.explorer.memory` actually consumes — `get-all-facts`,
  `get-root-facts`, `get-insertions`, `get-rule-matches`, `get-query-matches`,
  `->rule-insertion-supports`, `->query-result-supports`, `->beta-node-relations`,
  and `condition-match->facts` — plus the `Explanation` schema.  Node taxonomy, node
  memory readers (including accumulate-node reads), retention, and
  token→`Explanation` conversion are private helpers to be promoted if a future
  caller needs them.  `clara.explorer.memory` now only maps that raw view to the
  explorer API shape and no longer imports any engine node types.
