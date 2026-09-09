# Review 2 — Enhanced LHS Analysis (review response + LHS homogenization)

Companion to [`roadmap-enhanced-lhs-ana-review-1.md`](./roadmap-enhanced-lhs-ana-review-1.md).
Reviews the branch response to review 1 (`7374b78` "review response pass 1"
and its predecessors `7ecf7b9` / `f35b013` / `58dbe1f`) **and** the follow-up
`42346ee` "unify the normalized lhs across rulebase ana and anno ana" (LHS
homogenization to an all-maps shape). This document folds in the former
review-3 and supersedes it.

Verdict: **review-1 is fully accounted for, and the LHS homogenization is
correct and well-executed.** The closed API schema validates against real
rulebases, the serialized group shape is sound, all consumers are consistently
updated, and every quality gate passes. Remaining items: three small follow-ups
from the response pass that were never acted on (R2-1/R2-2/R2-3), plus three
new items from the homogenization (R2-5/R2-6/R2-7). None block the happy path.

---

## 1. Review-1 disposition

| Finding | Severity | Status |
| --- | --- | --- |
| C1 — `sort-tagged` drops the unsatisfiable guard (infinite loop) | HIGH | **Resolved.** Guard added (`conditions.clj` `sort-tagged`) + `test-analyze-lhs-bindings--unsatisfiable`. |
| C2 — compound negation silently drops info / diverges from compiled network | MEDIUM | **Resolved.** `compound-negation?` defers explicitly (`attach-path` nil); `path-index` throws on duplicate attach-path; `test-augment-lhs--compound-negation-unaugmented` pins the group untouched. |
| C3 — non-deterministic `:exists` result-binding | LOW | **Resolved.** Deterministic `:?__exists__<origin>__<i>` + `test-analyze-lhs-bindings--exists-deterministic`. |
| C4 — misleading `:prod-ns nil` accumulator-eval message | LOW | **Resolved.** `accumulator-info` distinguishes "not derivable" from "not loaded". |
| S1 — path construction duplicated across three fns | MEDIUM | **Resolved.** `group-child-paths` is the single child-path encoder, shared by `flatten-tagged` / `attach-path` / `walk-augment`. |
| S2 — two names for one value (`:join-bindings` vs `:binding-keys`) | LOW | **Resolved.** Compiler key read exactly once in `conjunction-binding`; the record and everything downstream use `:binding-keys` only. |
| U1/U3 — wire shape + UI treatment of binding info | MEDIUM/LOW | **Resolved.** One nested `:bindings` map (`LhsBindingInfo`: `binding-keys` / `new-bindings` / optional `join-filter-join-bindings`); `used-bindings`/`ancestor-bindings`/`all-bindings` dropped from the wire; collapsible "Show bindings" UI (fixed group order, badges) + a UI test. |
| U2 — no internal (pre-serialization) schema | LOW/MEDIUM | **Resolved.** `AccumulatorInfo` + `LhsBindingRecord` `s/defschema`s, `s/defn` on `accumulator-info` and `conjunction-binding`, gated to tests via `schema.test/validate-schemas`. |
| D1 — docs staleness (test counts, wire shape) | LOW | **Resolved.** Roadmap + plan updated through the homogenization commit, including the group-vector schema concern. |

### Note: internal schemas are correctly test-only (no production cost)

Review-1's caveat — "validate the small leaf shapes, but don't pay the cost in
the hot path" — was handled the right way. In `prismatic/schema` 1.4.1,
`s/defn` does **not** validate by default (`s/set-fn-validation!` defaults off;
verified in a REPL: an `s/defn` with a wrong return type runs clean until
`schema.test/validate-schemas` turns validation on). So `conjunction-binding`'s
`s/defn` and the API handlers validate **only under the test fixture**, not in
production builds. This is worth recording here because it is easy to misread
the presence of `:- Schema` as "enforced at runtime in prod" — it is not.

---

## 2. The LHS homogenization (`42346ee`)

- `conditions/normalize-lhs` converts every LHS entry to a map. Group vectors
  become `{:condition-type :and|:or|:not|:exists :children […] :raw-condition
  <original>}`; accumulator maps get their `:from` subtree normalized and
  retain their raw form under `:raw-condition`.
- `conditions/extract-lhs-fact-types` / `extract-var-bindings` are structural
  walkers over the normalized shape, replacing the removed
  `core/extract-lhs-fact-types` and `alias/lhs-var-bindings` /
  `alias/rulebase-fact-types` (no stale call sites remain).
- `analyze-lhs-bindings` reads the raw form back via `get-raw-lhs` only for the
  compiler-coupled walk; `augment-lhs` expects an already-normalized LHS and
  strips `:raw-condition` in `walk-augment` once consumed.
- `core.clj` normalizes productions once at the top of
  `->rulebase-analysis*`; `analyze.clj` and `memory.clj` normalize their own
  inputs; `serialize-condition` recurses `:from` / `:children` explicitly
  instead of `prewalk`.
- `api/LhsCondition` is now a **closed** recursive schema (leaf keys +
  `:condition-type` enum + `:children`; the `s/Keyword s/Any` catch-all is
  gone), mirrored by a closed `LhsElement` in `api.ts` and a
  `children`-based group render in `LhsCondition.svelte`.

---

## 3. Findings

### 3.1 Still open from the response pass

#### R2-1 — `binding-summary` emits an empty `:join-filter-join-bindings []` (LOW)

`binding-summary` gates the field on presence, not content:

```clojure
(contains? record :join-filter-join-bindings)
(assoc :join-filter-join-bindings
       (sort-bindings (:join-filter-join-bindings record)))
```

`conjunction-binding` attaches `:join-filter-join-bindings` whenever the
compiler's node has it, and the compiler attaches it as `#{}` (empty but
present) whenever the condition has **non-equality unifications** — even when
none of those unifications reference an upstream binding. So a rule like
`[?b <- B (> ?b 10)]` (with `?a` bound earlier) serializes:

```clojure
:bindings {:binding-keys []
           :new-bindings []
           :join-filter-join-bindings []}   ; ← empty, but present
```

This is benign — the UI filters empty groups and `LhsBindingInfo` accepts `[]`
— but it violates the stated contract ("present only for non-equality joins")
and is untidy on the wire.

**Suggested change:** gate on content with `not-empty` (nil-puns on the
original set rather than converting to a seq):

```clojure
(when-let [jf (not-empty (:join-filter-join-bindings record))]
  (assoc ... :join-filter-join-bindings (sort-bindings jf)))
```

and tighten the `binding-summary` / `LhsBindingInfo` docstrings from
"non-equality joins" to "non-equality unifications that reference an upstream
binding".

#### R2-2 — No test covers the `join-filter-join-bindings` wire field (LOW/MEDIUM)

We deliberately promoted `join-filter-join-bindings` to a first-class wire
field, but no test exercises the non-equality-unification path that produces
it (neither the non-empty case nor the empty-vector case from R2-1). The
`conditions_test.clj` suite covers fact / accumulator / fact-binding / simple
`:not` / compound-negation / `:exists`, but not a non-equality join.

**Suggested change:** add one `augment-lhs` test with a non-equality join
(e.g. the two-condition LHS in the review-1 U1 walkthrough) asserting
`:bindings` contains `:join-filter-join-bindings [:?a]`, and (after R2-1) a
case asserting the empty variant is **omitted**.

#### R2-3 — `analyze-lhs-bindings` docstring is stale on `:attach-path` (LOW)

The docstring says `:attach-path` is "nil for `:or` / `:exists` groups", but
`attach-path` also returns nil for compound negations (the `augment-lhs`
docstring correctly lists all three). Align the two descriptions.

### 3.2 Resolved by the homogenization

#### R2-4 — (Resolved by `42346ee`) group vectors violated `LhsCondition`

Previously `LhsCondition` was a map schema, but a real LHS could contain group
vectors (`[:not …]`, `[:or …]`, …). The homogenization resolved this: groups
are now `{:condition-type … :children […]}` maps, and `LhsCondition` became a
closed recursive schema with the catch-all dropped. Verified against both demo
rulebases (see §4).

### 3.3 New from the homogenization

#### R2-5 — `enrich-accumulators` re-evaluates each accumulator once per nesting level (LOW/MEDIUM)

`normalize-condition` retains `:raw-condition` (which contains the raw
`:accumulator` form) on **both** accumulator nodes and their ancestor group
nodes. `enrich-accumulators` uses `walk/prewalk`, so it descends into every
retained raw copy and re-evaluates `:accumulator` on each. Verified with a
counter instrumented via `with-redefs`:

- flat accumulator → `accumulator-info` called **2** times (expected 1);
- accumulator inside an `:or` group → called **3** times (expected 1).

The extra enriched copies are discarded by `walk-augment` (`dissoc
:raw-condition`), so the output is correct — but each accumulator constructor
is `eval`'d more than once (wasted work; a non-pure constructor would fire
multiple times).

**Suggested change:** `:raw-condition` is fully consumed by
`analyze-lhs-bindings` before enriching, so strip it first:

```clojure
(let [raw-consumed (walk/prewalk (fn [x] (if (map? x) (dissoc x :raw-condition) x)) lhs)
      enriched (enrich-accumulators raw-consumed prod-ns)]
  ...)
```

or have `enrich-accumulators` skip `:raw-condition` subtrees. Either removes
the redundant evals.

#### R2-6 — `normalize-lhs` is not idempotent for accumulator nodes (LOW)

Re-normalizing an already-normalized LHS wraps the normalized accumulator node
as its own `:raw-condition`, breaking the `get-raw-lhs` round-trip. Verified:

```clojure
(= raw (conditions/get-raw-lhs (conditions/normalize-lhs raw)))           ; true
(= raw (conditions/get-raw-lhs (conditions/normalize-lhs (conditions/normalize-lhs raw)))) ; false
```

Today this is unreachable — `core.clj`, `analyze.clj`, and `memory.clj` each
normalize exactly once from the raw rulebase — so it is a latent footgun for
future callers, not a live bug.

**Suggested change (either):**

- make `normalize-condition` detect an already-normalized node and return it
  unchanged (e.g. a node with `:condition-type`+`:children`, or a map already
  carrying `:raw-condition`); or
- document "normalize exactly once" on `normalize-lhs` and assert it in
  `augment-lhs` (which must receive a *raw* LHS).

#### R2-7 — `normalize-condition` throws a confusing error on malformed group vectors (LOW)

A vector whose head is not a keyword/symbol (e.g. `[{…}]` from a misplaced
nest) hits `(keyword (name op))` on a map and throws
`ClassCastException: PersistentArrayMap cannot be cast to Named`. Clara never
produces such LHS, so this is garbage-input robustness only — but a clear
`ex-info` ("unsupported LHS condition shape") would be friendlier than a raw
cast error.

---

## 4. Verification performed

Server (`cd server`):

- `make test` → **254 tests / 1655 assertions, 0 failures / 0 errors**.
- `make format-check` → clean; `make lint` → 0 errors / 0 warnings.
- `make reflection-check` → no warnings in project code.

UI (`cd ui`, `TMPDIR="$PWD/target/tmp"`):

- `make format check lint` → all pass.
- `make test-unit` → **32 passed** (includes the group-rendering test).

REPL (nREPL, `CLARA_HOME` checkout):

- `->rulebase-analysis` validates against the closed `api/LhsCondition` for
  **both** demo rulebases: `loan-doc-rules` (12 rules / 1 query) and
  `loan-app-rules` (4 rules / 1 query, which contains `:not` groups).
- A `:not` group serializes to
  `{:condition-type :not :children [{:type … :constraints … :bindings …}]}`
  with the nested leaf still augmented under `:bindings`.
- `normalize-lhs` → `get-raw-lhs` round-trips exactly once.
- Non-equality join cases: non-empty and empty `:join-filter-join-bindings`
  both reproduced (basis for R2-1 / R2-2).
- Confirmed `s/defn` does not validate by default in `prismatic/schema` 1.4.1
  (basis for the §1 note).

---

## 5. Suggested changes — prioritized

1. **(MEDIUM)** R2-5 — stop re-evaluating accumulators: strip `:raw-condition`
   before `enrich-accumulators` (or skip it in the walk).
2. **(LOW)** R2-1 — gate `:join-filter-join-bindings` with `not-empty`; add the
   R2-2 non-equality-join test.
3. **(LOW)** R2-6 — make `normalize-lhs` idempotent (or document + assert
   "normalize once").
4. **(LOW)** R2-3 + R2-7 — docstring fix and a clearer malformed-shape error.
