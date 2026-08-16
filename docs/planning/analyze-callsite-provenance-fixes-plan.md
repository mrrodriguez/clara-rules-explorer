# Callsite provenance (`:via`) fixes — Plan

Status: **Phase 1 implemented (committed as `8f34105`). Phase 2 (naming) and Phase 3 (UI) pending.**

Related: [`analyze-callsite-provenance-fixes-problem-statement.md`](./analyze-callsite-provenance-fixes-problem-statement.md)

Progress: [`analyze-callsite-provenance-fixes-progress.md`](./analyze-callsite-provenance-fixes-progress.md)

Scope: `analyze.callsite` / `analyze/extract-insert-types` — the `:via` chain on
callsites of `:clara-rules/dynamic-insert-types-detected` (and the retract twin).

---

## Phases

1. **Phase 1 — provenance (DONE, committed `8f34105`):** `:boundary-in-var` +
   `:rule-path` on every `:via`; dropped-constructor provenance carried into
   the boundary entry.  §1–§5 describe this phase.

2. **Phase 2 — naming (pending):** rename the two path keys to say exactly
   what they are: `:rule-to-boundary-path` and
   `:boundary-to-constructor-path`.  §6.

3. **Phase 3 — UI (pending, final):** render the *full* provenance chain in the
   UI — `rule-to-boundary-path` first, then the boundary fn, then
   `boundary-to-constructor-path` — with the shared anchor shown once.  §7.

---

## 1. Summary of the fix

Two new optional `ViaChain` keys, and the plumbing to populate them:

- **`:boundary-in-var`** — the var the boundary call is written in
  (`u/fq-sym (:from usage) (:from-var usage)`). Exact, no graph walk, available
  to both resolution passes for every callsite. It is what `(first :callstack)`
  already implies, stated as its own key.
- **`:rule-path`** — `[ViaEntry …]` from the rule var to `:boundary-in-var`
  (both inclusive), from a `shortest-call-path` BFS. Omitted when the two vars
  are equal (boundary call in the rule's own RHS).

`:callstack` keeps its meaning (boundary-holding var → … → constructor) so its
invariant survives and `:rule-path`'s last entry joins onto it.

Additionally, the boundary pass stops throwing away provenance it already has
in hand for arguments the constructor pass *dropped* (unresolvable
constructor-of-interest): the dropped `:constructor-sym` + `:callstack` are
carried into the boundary entry for that argument, unless two dropped
constructors own the same argument (then identity is ambiguous and only the
boundary-side keys are emitted).

---

## 2. Design decisions

1. **Every boundary-pass entry gains `:via`.** `resolve-boundary-callsites`
   builds `:via {:boundary-var-name-sym … :boundary-in-var … [:rule-path …]}`
   from the boundary `usage` it already has. This also covers arguments that
   never matched a constructor at all (`(insert-all! facts)`).

2. **Rule var enters at `extract-insert-types`.** Its signature gains the rule
   var as the first argument; it assocs `:rule-var` and a memoized
   `:rule-path-for` into `ctx` before calling either pass.
   `callsite/rule-path-for` memoizes per `(rule-var, boundary-in-var)` pair.

3. **`shortest-call-path` becomes shared** (public) in `analyze.callsite`
   instead of private to the constructor pass. `rule-path-for` wraps it to
   return `[ViaEntry …]` and nil when rule == boundary-in-var or unreachable.

4. **Dropped-constructor provenance.** `resolve-constructor-callsites` returns
   `:dropped-ctor-provenance` — `{idx -> {:constructor-sym … :callstack …}}` for
   entries it dropped because the type-resolver returned no type.
   `resolve-boundary-callsites` merges those two keys into the matching
   boundary entry. Ambiguity (two dropped constructors owning one `:idx`) is
   resolved by *omitting* the idx from the map, so the boundary entry keeps only
   boundary-side keys.

5. **Constructor-pass `:via` also gains the two keys.** `resolve-ctor-callsite`
   computes `:boundary-in-var` as `(first call-path)` and `:rule-path` from the
   same memoized helper; `:callstack` is unchanged.

6. **Heuristic record-ctor scan is untouched.** Its `:via {:source
   :record-ctor-scan}` shape (optionally + `:boundary-var-name-sym`) is out of
   scope for this change; it is not one of the two resolution passes.

---

## 3. File checklist

### Server source (required)

- `server/src/clara/server/tools/graph/analyze/callsite.clj`
  - `shortest-call-path`: `defn-` → `defn` (shared).
  - Add `rule-path-for` (memoized) and a `via-base` helper.
  - `resolve-boundary-callsites`: build `:via` for every entry; merge
    `:dropped-ctor-provenance` (`:constructor-sym` + `:via :callstack`).
  - `resolve-ctor-callsite`: add `:boundary-in-var` + `:rule-path` to `:via`.
  - `resolve-ctor-usage-for-inserter`: return owned/dropped result (not just
    the pair) so dropped provenance is preserved.
  - `resolve-constructor-callsites`: accumulate and return
    `:dropped-ctor-provenance`.
  - `ViaChain` schema: add `:boundary-in-var` (Symbol) + `:rule-path`
    ([ViaEntry]); `CallsiteResolution` schema: add
    `:dropped-ctor-provenance`.
  - Docstrings: state that `:rule-path` and `:callstack` are shortest paths
    through a var-level call graph, not observed call paths.

- `server/src/clara/server/tools/graph/analyze.clj`
  - `extract-insert-types`: signature `[var-name reachable target-fns ctx]`;
    assoc `:rule-var` + `:rule-path-for`; thread `:dropped-ctor-provenance`
    into the boundary pass.
  - `infer-annotation-for-var`: pass `var-name` to both
    `extract-insert-types` calls.

- `server/src/clara/server/tools/graph/serialize.clj`
  - `serialize-dynamic-callsite`: stringify `:boundary-in-var` and
    `:rule-path` (each `:var-name-sym`) the way `:callstack` is stringified.

- `server/src/clara/server/graph/api.clj`
  - `ViaChain` schema: add `:boundary-in-var` (Str) + `:rule-path` ([ViaEntry]);
    docstring notes the shortest-path caveat.

- `server/src/clara/server/tools/graph/annotations/rebase.clj`
  - `rebase-callsite`: remap `:via :boundary-in-var` and `:via :rule-path`
    entries alongside the existing `:boundary-var-name-sym` / `:callstack`
    handling.

### Server tests

- `server/deps.edn`
  - Add `nubank/matcher-combinators {:mvn/version "3.11.0"}` to the `:test`
    alias (used for focused partial-map matching so the new `:via` keys don't
    churn every full-equality assertion).

- `server/test/clara/server/tools/graph/analyze_test.clj`
  - Require `matcher-combinators.test` / `matcher-combinators.matchers`.
  - Convert `resolved-detection` / `unresolved-detection` to matcher-combinators
    matchers (assert only the keys that matter; `:via`/`:callsite-id` get their
    own focused tests). Update the ~19 call sites from `is (= …)` to
    `is (match? …)`.
  - Update alias-callsite exact-equality assertions to partial matches and add
    a focused `:via` assertion.
  - Add new fixtures (in `rules/analyze_test_rules.clj`) and tests for the six
    Section-9 scenarios (RHS boundary, two-hop, dropped-ctor-in-helper,
    no-constructor boundary arg, ambiguous double-drop, two-path determinism).

- `server/test/clara/server/tools/graph/rules/analyze_test_rules.clj`
  - New fixture vars for the provenance scenarios (parameterized insert helper,
    parameter-only insert helper, ambiguous two-constructor helper, shared
    boundary var reached by two paths).

- `server/test-resources/.../loan-doc-rules-annotations.edn`
  - Regenerate via `make regen-fixture` (boundary entries gain `:via`,
    ctor entries gain `:boundary-in-var`).

- `server/test/clara/server/tools/graph/annotations_merge_test.clj`
  - Extend the rebase test fixture to carry `:boundary-in-var` +
    `:rule-path` and assert they remap.

- `server/test/clara/server/tools/graph/serialize_test.clj`
  - Add coverage for `serialize-dynamic-callsite` stringifying
    `:boundary-in-var` and `:rule-path`.

### Docs

- `server/docs/rule-annotations.md`
  - Update the `ViaChain` section and the detection-map structure example to
    describe `:boundary-in-var` + `:rule-path` and the shortest-path caveat;
    note the boundary path now emits `:via`.
- `server/docs/analyze-pipeline-concepts.md`
  - Update the two-passes description (§2.6 / §6) to mention the new keys and
    the dropped-constructor provenance carry-over.

---

## 4. Execution order (Phase 1 — done)

1. `callsite.clj` core changes (shared path fn, via-base, rule-path-for, both
   passes, schemas).
2. `analyze.clj` plumbing (signature + ctx threading).
3. `serialize.clj` + `api.clj` + `rebase.clj`.
4. `deps.edn` + test helper conversion + new fixtures/tests.
5. Regenerate `loan-doc-rules-annotations.edn`.
6. Verify from `server/`:
   ```bash
   make format
   make format-check
   make lint
   make reflection-check
   make test
   ```

---

## 5. Consequences (accepted)

- Attaching `:constructor-sym` to a previously bare boundary entry changes its
  `callsite-id` (`callsite-basis` = `[ns-name-sym constructor-sym source-str]`).
  Curated overlays recorded against the old ids quarantine as stale and
  regenerate. `:via` itself stays outside the basis, so gap B alone churns
  nothing.
- Consumers grouping callsites by `:constructor-sym` now see unresolved
  constructor calls move out of the "no constructor" bucket — which is the point.

---

## 6. Phase 2 — Naming: `:rule-to-boundary-path` / `:boundary-to-constructor-path`

### Decision

Rename the two `ViaChain` path keys so each states its span and direction:

| Current | New | Span (both ends inclusive) |
| --- | --- | --- |
| `:rule-path` | `:rule-to-boundary-path` | rule var → … → boundary-holding var |
| `:callstack` | `:boundary-to-constructor-path` | boundary-holding var → … → constructor |

`(last :rule-to-boundary-path)` == `(first :boundary-to-constructor-path)` ==
`:boundary-in-var` — the shared join.  Both remain shortest paths through the
var-level call graph (not observed runtime paths); the schema docstrings keep
that caveat, just under the new names.

### Boundary keys stay as-is (decided)

`clara.rules/insert!` *is* a var name — a var that names a function — so
`:boundary-var-name-sym` is already consistent with the `:var-name-sym` entries
inside the two paths (which also name functions/constructors).  No rename.
`:boundary-in-var` also stays: it names the var the boundary call is written
in, and the two new path names give it context as the shared join point.

### Server rename surface

- `analyze/callsite.clj` — `ViaChain` schema keys; `via-base` emits
  `:rule-to-boundary-path`; `resolve-ctor-callsite` emits
  `:boundary-to-constructor-path`; `resolve-boundary-callsites` merges the
  dropped `:boundary-to-constructor-path`; `CallsiteResolution`
  `:dropped-ctor-provenance` value; rename `rule-path-for` →
  `rule-to-boundary-path-for` (and ctx key `:rule-path-for`); docstrings.
- `analyze.clj` — `:rule-path-for` ctx key + comment.
- `serialize.clj` — `serialize-dynamic-callsite` stringifies both renamed keys.
- `graph/api.clj` — serialized `ViaChain` schema (both keys; string/`[ViaEntry]`).
- `annotations/rebase.clj` — `rebase-callsite` remaps both renamed keys
  (and `rebase-via-path` docstring).
- `server/docs/rule-annotations.md` + `server/docs/analyze-pipeline-concepts.md`
  — prose, the detection-map example, and the `ViaChain` section.
- `server/test/clara/server/tools/graph/analyze_test.clj` — ~15 assertions over
  `(:callstack (:via cs))` / `(:rule-path (:via cs))`.
- `server/test/clara/server/tools/graph/annotations_merge_test.clj` —
  `generated-callsite` fixture and the rebase test.
- `server/test/clara/server/tools/graph/serialize_test.clj` —
  `test-serialize-dynamic-callsite-via`.
- `server/test-resources/.../loan-doc-rules-annotations.edn` — regenerate via
  `make regen-fixture` (the ctor entry's `:callstack` → new key).

### Not affected

- `callsite-id` — `:via` is outside the basis, so no id churn.
- Merge semantics — detection maps merge on `:callsite-id`; `:via` is opaque.
- The heuristic `:via {:source :record-ctor-scan}` shape.

### Verification (Phase 2)

```bash
cd server && make format format-check lint reflection-check test
```

---

## 7. Phase 3 — UI: render the full provenance chain

### Goal

Show the *entire* chain in `DynamicCallsiteList`'s "Provenance chain", not just
the constructor half.  Order is `rule-to-boundary-path` first, then the boundary
fn, then `boundary-to-constructor-path`, with the shared `:boundary-in-var`
rendered once.

### Files

- `ui/src/lib/types/api.ts` — extend `ViaChain`:
  ```ts
  export interface ViaChain {
    'boundary-var-name-sym'?: string;
    'boundary-in-var'?: string;
    'rule-to-boundary-path'?: ViaEntry[];
    'boundary-to-constructor-path'?: ViaEntry[];
    source?: string;
  }
  ```
- `ui/src/lib/components/rulebase/DynamicCallsiteList.svelte` — rewrite
  `buildViaEntries(via)` to compose the full ordered chain:
  1. `rule-to-boundary-path` — first entry label `rule`, the rest `caller`.
  2. boundary fn (`boundary-var-name-sym`) — label `boundary`.
  3. `boundary-to-constructor-path` — **skip its first entry when
     `rule-to-boundary-path` is present** (that entry is `:boundary-in-var`,
     already shown as the path tail); otherwise include all.  Last entry label
     `constructor`, the rest `caller`.

  This keeps today's rendering byte-for-byte for the RHS case (no
  `rule-to-boundary-path` → identical to now) and adds the rule-side hops when a
  helper holds the boundary call.

### Testing (Phase 3)

- Extract the chain-composition into a pure function and unit-test the three
  shapes: RHS-only, helper+constructor (both paths), helper-without-constructor
  (`rule-to-boundary-path` only).
- **Demo/e2e data (decided):** extract `collect-app-doc-check-input`'s inline
  `insert!` into a new `insert-document-check-input!` helper (one hop), keeping
  the `->document-check-input` → `helpers/->fact` builder/constructor chain
  intact — so the demo exercises the *full* chain (both
  `:rule-to-boundary-path` and `:boundary-to-constructor-path`):

  ```clojure
  (defn insert-document-check-input! [data]
    (r/insert! (->document-check-input data)))

  (r/defrule collect-app-doc-check-input
    [Application (= ?app-id app-id)]
    [AllGivenDocuments (= ?app-id app-id) (= ?given-docs docs)]
    [AllRequiredDocuments (= ?app-id app-id) (= ?required-docs docs)]
    =>
    (let [given-doc-types (into #{} (map :doc-type) ?given-docs)]
      (insert-document-check-input! {:app-id ?app-id ...})))
  ```

  Churn: update `analyze_test.clj` (the loan-doc ctor
  `:boundary-to-constructor-path`/`:rule-to-boundary-path` assertion + the
  dynamic-rules `collect-app-doc-check-input` source-str), regenerate
  `loan-doc-rules-annotations.edn` (`make regen-fixture`), the demo session
  (`make demo-setup`), and the UI static demo data (`pnpm run scrape:demo`).
  Graph/edge tests are unaffected — the rule still inserts
  `:loan-doc-rules/document-check-input`.

### Verification (Phase 3)

```bash
cd ui && make format check lint && make test
```
