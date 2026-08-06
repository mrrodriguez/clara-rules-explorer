# Review: analyze-defects implementation (kimi-1)

**Scope:** `git diff main` on branch `analyze-defect-fixing` (commits
`30c6c4c` "impl M1, M2" and `260bd09` "impl M3-M5"), reviewed against
`docs/planning/analyze-defects-plan.md` and `server/docs/analyze-defects-todo.md`.

**Method:** full diff read of all 13 changed files, plus live verification in
an nREPL against **real compiled productions and real fact shapes** (records,
Class-typed conditions, tuples, sets of maps) rather than only the test
fixtures — per the standing nuance that fact types and ancestors are
extensible: keywords, vector tuples, plain maps, Java classes / Clojure
records / deftypes, string literals, etc. Tests are representative examples,
not the contract.

**Verdict:** Milestones 1–4 ship as-is. Milestone 5's rulebase-only path works
and is verified, but the `:working-memory? false` opt-out is advertised
without being enforced, and the 409 path has no automated coverage. Fix those
two before the cross-project follow-through (API doc + UI types) lands.

---

## Verified working (REPL-confirmed, not just test-trusted)

### M1 — Analysis pipeline (defects 1 + 2)

- `synth/build-require-clauses` now emits `[target :refer [a b c]]` with the
  referred symbols in their own vector; `:refer-clojure` is a list via
  `concat`. Round-trip test with a `clojure.set`-referring namespace and the
  list-shape assertion are present in `analyze_test.clj`.
- `callsite/assign-callsite-ids` guards `(empty? ids)` before
  `(apply distinct? ids)`; `(assign-callsite-ids []) => []` is tested.

### M2 — `condition->form` boolean groups (defect 6)

- Dispatches on `schema/condition-type`, mirroring `extract-lhs-fact-types`.
- Exercised live against real compiled productions, where `:type` is a
  **Class** (`user.Temp2`), not the `'my.ns/Foo` symbols the tests use:
  - record-typed leaf: `[user.Temp2 (> v 100)]` — Class renders cleanly;
  - `:not` group: `[:not [user.Temp2 (< v ?v)]]` — operator and nested
    condition survive;
  - accumulator `:from` recursion and single-group LHS covered by tests.
- Per-branch tests (`:fact`, `:test`, `:not`, `:or`, `:and`, `:exists`,
  single-group LHS, accumulator `:from` with nested group) plus the
  fact-type invariant all present in `serialize_test.clj`.

### M3 — `deterministic-fact-str` canonicalization (defect 4)

Exercised live against the full fact contract:

- records, **sets of records**, Class-valued fields, tuple vectors,
  mixed keyword/string keys, a record as the top-level fact — all
  canonicalize without comparator errors;
- value-deterministic: two separately-built but equal records produce
  identical strings; map key order and set element order do not affect
  output;
- `::map` / `::set` markers keep `#{1 2}` vs `[1 2]` vs `{1 2}` distinct.

### M4 — Detection-map layering semantics (defect 5)

- `merge-detection-maps` merges non-callsite keys from both sides (incoming
  wins); `fold-detection-key`'s no-callsites branch merges instead of
  replacing. Both repro routes have direct tests in
  `annotations_merge_test.clj`, including provenance assertions.
- `contributing` (used for provenance in the new branch) is pre-existing,
  already used by the other fold paths — consistent.

### M5 — Rulebase-only server (defect 3, partial)

Verified live via `api/app`:

- rulebase-only: **all** `/session/*` routes + `/session-snapshot` return
  409 with `{"error": "...", "reason": "no-working-memory"}` — uniform,
  machine-readable, distinct from 404;
- rulebase routes (`/v1/rulebase-summary`, `/v1/rules`, `/v1/annotations`)
  return 200;
- `RulebaseSummary` carries `:working-memory?` — `false` for a rulebase,
  `true` for a live session;
- `instance? LocalSession` → `satisfies? eng/ISession` migration is clean in
  both `core/get-rulebase` and `api/enriched-annotations`; the
  `working-memory-available?` predicate is the single source of truth.

---

## Findings (ordered by severity)

### 1. HIGH — `:working-memory? false` is a documented lie, not just a deferred feature

The plan doc's deviation note is honest, but the **code** is not:

- `server/start!`'s docstring promises "When false, working-memory routes
  return 409 even when a live session is provided";
- the startup log prints "Working-memory routes disabled by configuration
  (:working-memory? false)";
- but `api/app` takes no flag (arity confirmed: `[session-atom annotations-atom]`)
  and handlers serve working memory normally.

A user setting the flag gets a log line announcing disabled routes and then
200s. The plan required enforcement plus a test configuration for it; the
implementation accepted the option and stopped at the log line.

**Fix (small, ~15 lines):** thread an opts map into `api/app` and have
`get-snapshot` (or `with-snapshot`) consult it. Note the knock-on: the 409
error message "started with a rulebase, not a session" becomes wrong for the
opt-out case — make the message neutral or carry the actual reason
(`:rulebase-input` vs `:disabled-by-config`). Given the API-fluid mandate,
finishing the enforcement is preferable to stripping the docs.

### 2. MEDIUM — The 409 path has zero automated coverage; the plan's coverage claim is wrong

The plan's status section claims rulebase-only behavior is "implicitly
covered by main-test." Grep shows `main_test.clj` contains **no**
rulebase/working-memory/409 assertions at all. The plan mandated three test
configurations:

1. rulebase only → rulebase routes 200, session routes 409;
2. session + `:working-memory? false` → same as (1);
3. session + defaults → unchanged (regression guard).

None exist. The behavior was verified by hand for this review (config 1 and
3 pass), but there is no regression guard. Add the suite — at minimum
configs 1 and 3 now; config 2 lands with finding 1's fix.

### 3. LOW — The `:test` condition test fixture uses a shape that never occurs

Real compiled test conditions are `{:constraints [...]}` with **no `:type`
key** (REPL-confirmed: `[:test (> 1 0)]` compiles to
`{:constraints [(> 1 0)]}`). `test-serialize-lhs-form--test` uses
`{:type :test :constraints ...}`, which takes the `:fact` branch of
`condition-type` (truthy `:type`), not the `:test` else-branch. Both shapes
render acceptably, so nothing is broken — but per the representative-example
nuance, the fixture should use the real shape.

Related pre-existing observation: real test conditions render as
`[(> 1 0)]` with no `:test` marker, so the rendered form does not round-trip
to defrule syntax. That predates this branch — noted, no fix requested here.

### 4. LOW — `"working-memory?"` is the only JSON key containing `?`

Every existing API key is plain kebab-case (`'rule-count'` in
`ui/src/lib/types/api.ts`). `"working-memory?"` is legal JSON and works with
quoted TS access, but introduces a new convention. Since the contract is
fluid, decide before the UI follow-through: keep, or rename to
`working-memory` / `working-memory-available`.

### 5. LOW — `s/defn` return schemas on session handlers now lie

`handle-get-session-fact-types` and siblings declare
`:- {:status (s/eq 200) :body ...}` but can return 409. No fn-validation is
enabled anywhere in the project (grepped), so this is runtime-harmless, but
the declared contract should become a union (200 | 409) or the return
annotations dropped from those handlers.

---

## Nuance audit: fact-types / ancestors extensibility

The implementations hold up under the extensible-contract lens:

- `condition->form` conjs `:type` **opaquely** — no pattern-matching on
  whether the type is a Class, symbol, keyword, or tuple. Class values print
  by name via pprint, symbols/keywords print as-is.
- `deterministic-fact-str` treats all non-collection values opaquely;
  collection canonicalization is structural (map?/set?/sequential?), which
  correctly covers records (they are `map?`).
- `working-memory-available?` is a protocol capability check, not a concrete
  type check — future session implementations are not excluded.
- `extract-ancestors-fn` was untouched by this branch.

One parity note: a record fact and a plain map with identical entries
canonicalize to the **same sort key** (record class identity is erased).
The old `sorted-map` implementation had exactly the same property, so this
is a pre-existing characteristic of sort-key canonicalization, not a
regression. It only matters if two facts with identical content but
different runtime types coexist and their relative sort order must be
stable — not actionable now.

---

## Bottom line

- **M1–M4: ship.**
- **M5: rulebase-only path works and is verified — but either implement the
  `:working-memory? false` enforcement (finding 1) or stop advertising it,
  and add the route-status test suite (finding 2) before the cross-project
  follow-through (`docs/explorer-graph-api.md`, `ui/src/lib/types/api.ts`)
  lands.** Finding 4's key-name decision should be made at the same time,
  since the UI types will bake it in.
