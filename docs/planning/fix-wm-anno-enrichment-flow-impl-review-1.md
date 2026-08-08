# Implementation Review 1 — WM Annotation Enrichment Flow

**Scope:** Review of the implementation reported in
`fix-wm-anno-enrichment-flow-plan-progress.md` ("Phase 1 complete") against
the authoritative plan `fix-wm-anno-enrichment-flow-plan-final.md`.
**Method:** Full diff review of all 14 changed files (vs commit `c8fcc2c`),
cross-checked against the plan section by section; test suite re-run
independently.

## Verdict

The Phase 1 state consolidation is implemented faithfully and well — the
transitions, system handle, default-system facade, and read-only HTTP
contract all match the plan, and the suite passes (verified independently:
**191 tests, 1344 assertions, 0 failures, 0 errors**).

However, the review found **1 correctness bug**, **1 docstring that
misdescribes the code**, and — more importantly — the progress report's
"Remaining Phase 2 tasks" section is **incomplete**: it lists only tests and
demo verification, but the core Phase 2 *code* changes (plan §1a/§1b/§1c and
the §4 resolver unification) are **not implemented and not tracked**. The
delta-layer enrichment architecture — the heart of Phase 2 — does not exist
yet; the startup path still enriches the old way (against an empty base,
full layer appended last).

Note on framing: the progress report's Phase 1 checklist items 1–2
(`canonical-type-str`, analyze.clj comparison/display split) are actually
plan §4/§5 items — Phase 2 work landed early. That's fine, but they landed
*partially* (see findings 7–8).

---

## Bugs

### B1. `transition-reload` breaks `:reuse` reload semantics — returns nil annotations

`server.clj:203-214`:

```clojure
(defn- transition-reload
  [state]
  (let [tmp   (atom (:analyze-cache state))
        built (build-annotations (:session state)
                                 (:annotations-spec state)
                                 nil  ;; reload re-derives, doesn't reuse
                                 tmp)]
    ...))
```

Plan Path F is explicit: a stored `{:enrichment :reuse}` spec with no source
must return `current-annotations` — "reload of a stored `:reuse` spec is a
permanent no-op" and returns the *identical* reference (so the
identity-based request cache correctly does nothing). Passing `nil` as
`current-annotations` makes `build-annotations`' `:reuse` branch return
**nil**, so reload commits `{:annotations nil}` — wiping annotations that
the spec says to keep, and putting `nil` (not even `{}`) into the state map
that handlers pass to `cache/analysis`.

The inline comment ("reload re-derives, doesn't reuse") misreads the
contract: for `:reuse` there *is* nothing to re-derive from — the current
value is the derivation.

**Fix:** pass `(:annotations state)` as the third argument. One line. This
also needs the plan's reload test coverage (progress-report test cases 3–4
don't cover a `:reuse` spec).

### B2. `reload-annotations!` docstring describes code that doesn't exist

`server.clj:293-299` claims: "the memory layer re-derives via
`->memory-layer`". Nothing in the build path calls `->memory-layer` (grep:
the only reference in `src/` is this docstring). The memory layer is built
by `build-auto-detect-layers` via `enrich-annotations-from-session` against
`{}` (see D1). Either implement D1 or fix the docstring — right now the one
place a reader would learn the architecture actively misleads them.

---

## Plan deviations — unimplemented Phase 2 code, untracked in the progress report

### D1. Memory enrichment is not the delta layer (plan §1b, principle 4) — the core Phase 2 change

`server.clj:93-113` still builds the memory layer the pre-plan way:

```clojure
(and (#{:auto-detect-from-memory :auto-detect} enrichment) wm?)
(conj {:id :clara.tools.graph.analyze/memory
       :annotations (analyze/enrich-annotations-from-session session {})})
```

Per plan §1b this should be: merge static layers → compute base →
`analyze/->memory-layer {:session ... :annotations base}` → conj the *delta*
layer only when non-nil. Consequences of the current shape:

1. **Enrichment runs against an empty base**, so the memory layer re-claims
   every session-derived type — including ones the props/source/generated
   layers already declared. Because the memory layer is appended *last*, it
   wins `:from-layer` provenance for all of them. The plan's delta design
   exists precisely so the memory layer claims only what it added.
2. **The `:clara-rules/no-output-types` tombstone clearing never runs.**
   That logic lives in `annotations/rule-delta`, which is only reachable via
   `annotations-delta` → `->memory-layer`. Without it, a rule that the
   generated/props layers assert produces no output but that *observably*
   inserted at runtime keeps the tombstone **and** gains insert-types — and
   `core/production-annotation` reads `:no-output-types` to suppress sink
   classification, so a rule proven to insert can still be reported as
   producing no output. This is a correctness gap, not provenance cosmetics;
   whether it bites the demo ruleset depends on what the kondo layer
   asserts for the dynamic-insert rules — verify at the demo re-scrape.

### D2. No per-file source layers (plan §1a)

`build-auto-detect-layers` was not renamed to `build-static-layers` and
still wraps the whole source in one `{:id :source}` layer around
`coerce-to-bare-annotations`. The plan's per-file `ann.merge/->layer`
mapping preserves per-file `:provenance`/per-callsite `:from-layer`
attribution, eliminates the double `props-layer` fold (coerce adds props
internally — so the current path folds props twice), and restores
`merge-layers` duplicate-`:id` strictness as deliberate fail-fast. The
generated layer is also a bare map literal rather than
`ann.merge/layer`-validated (minor; `merge-layers` likely coerces anyway).

### D3. Unknown `:enrichment` silently degrades (plan §1c)

`build-annotations` (`server.clj:126-167`) ends the `case` with a catch-all
default → `build-auto-detect-annotations`. A typo like
`{:enrichment :auto-dectect}` matches neither conj condition there and
silently builds props+source. The plan requires enumerating the three
auto-detect modes explicitly and throwing
`IllegalArgumentException` with an actionable message. As written, the
plan's test case 5 (`test-build-annotations-unknown-enrichment`) will fail.

### D4. No boundary validation of spec-shaped maps (plan §1c)

Any map containing `:source` or `:enrichment` should be `s/validate`d
against `AnnotationsSpec` inside `build-annotations` (the choke point both
entry paths flow through). Not implemented — and `AnnotationsArg`'s pred
still ends in the tautological `(map? x)` clause, so D3's typo sails through
the boundary schema too. The two fixes were designed to work together.

### D5. WM-unavailable warning doesn't cover `:auto-detect` (plan §1b)

`server.clj:120-121` warns only for `:auto-detect-from-memory`. Plan: warn
for **both** `:auto-detect-from-memory` and `:auto-detect` (a user asking
for `:auto-detect` on a session.bin load gets props+generated only, with no
indication the memory half was skipped).

### D6. `StartOpts` schema and `s/validate` in `start-system!` missing (plan §1d)

`start-system!` (`server.clj:221`) destructures `config` directly. There is
no `StartOpts` schema and no validation anywhere on the start path. Not a
regression — the old `start!` was a plain `defn` with no validation either —
but the plan specifies fail-fast boundary validation, and it's cheap.

### D7. Resolver unification not done (plan §4)

`annotations/resolve-type-locally` (ns-resolve-based, try/catch-guarded) and
`serialize/resolve-type` (unguarded fallback, `serialize.clj:25`) still exist
as separate resolvers; `serialize.clj` is untouched. The plan consolidates
all three resolvers behind one shared import-aware helper in
`annotations.clj` with one try/catch policy, reimplementing
`serialize/resolve-type` as display formatting on top of it. The new
`canonical-type-str` is a third resolver now, which is exactly the
proliferation the unification was meant to prevent.

### D8. `build-annotations` 3-arity not preserved (plan §1c)

Only the 4-arity exists. Harmless today (all callers are internal; the
transitions legitimately thread the tmp atom), but the plan's contract
promised the 3-arity for direct callers/tests — either add it or strike it
from the plan to avoid a future caller writing against the plan.

---

## Minor / cosmetic

- **M1. Warm-after-bind ordering** (`server.clj:240-243`): `run-jetty` is
  called before `cache/warm!`; plan orders warm first. Benign (a request in
  the gap builds on demand and `get-state` is atomic), but the plan's order
  is also the more defensive one.
- **M2. `transition-swap` docstring** (`server.clj:182-189`): claims "when
  both are absent the transition is a no-op". It isn't — both-absent would
  build with a nil spec and *clear* annotations. The shell guards this, so
  the path is unreachable, but the doc promises wrong semantics for a pure
  function that tests will call directly.
- **M3. Dead capture in `server_test.clj`** (fixture): `test-system` atom is
  reset with the started system but the `finally` calls 0-arity
  `(server/stop!)`, never the captured value. Either use
  `(server/stop! @test-system)` or drop the atom.
- **M4. `server_test.clj` 1-arity helper semantics change**: passes
  `:annotations nil` (→ `{}`) where the old `[]` layers produced the
  props-merged annotations. `integration_test.clj` deliberately kept
  `{:source (vec layers)}` for exactly this reason ("props layer folded in
  even for empty layers"). Tests pass either way, but the two helpers now
  disagree on what "no layers" means — align them or comment the
  difference.
- **M5. `AGENTS.md`** gained a good "Server State Architecture" section
  (beyond what the plan asked), but the plan §9's explicit rule — "use
  `swap!`, never `(reset! a (f @a))`; use the return value of `swap!` rather
  than dereferencing afterwards" — is only half present (the
  return-value half made it; the reset!+@ race half didn't).
- **M6. `docs/explorer-graph-api.md`**: the reload section was removed, but
  plan §10 also asked for a short note that HTTP is read-only and mutation
  goes through the in-memory `swap-session!` / `reload-annotations!` API.
  Not added.
- **M7. `main.clj`**: the per-layer "Loading annotation layer: X" info print
  was dropped (only the missing-file warning survives). Cosmetic; restore if
  the startup narration was useful.
- **M8. Test-run WARN**: `WARN: serialize-type-ref received a nil-resolving
  type token: nil — dropping. prod-ns=` appears in the suite output
  (serialize.clj:84). `serialize.clj` is untouched by this change and the
  token is nil rather than mis-canonicalized, so this is almost certainly
  pre-existing — confirm against the pre-change suite and ticket separately
  if so.

---

## Progress report accuracy

- The Phase 1 checklist is **accurate about what changed** — every claimed
  item verifies against the diff.
- The "Remaining Phase 2 tasks" section is **materially incomplete**: it
  lists six new test cases and the demo verification, but omits D1–D5 and
  D7 — i.e., the Phase 2 *implementation* itself. As written, the report
  implies Phase 2 is "tests + scrape"; in fact the delta-layer enrichment
  flow, the per-file source layers, the fail-fast validation, and the
  resolver unification all remain to be built. Update the tracking doc
  before starting Phase 2 so these don't fall through.
- The claimed verification numbers reproduce exactly.

## What is solid (no action needed)

- State consolidation: single `state-atom` per system, correct state-map
  shape, ns docstring.
- `transition-start` (commits `@tmp` — startup kondo retained) and
  `transition-swap` (session-identity reseed rule, spec stored as-given).
- `start-system!`/`start!` split: pure constructor never touches the
  default; same-port stop-before-bind; different-port previous default keeps
  running. `stop!` identity check. `require-system` guard.
- `swap-session!`/`reload-annotations!` shells: `select-keys` boundary,
  warm from the `swap!` return value (no follow-up deref).
- `api.clj`: one deref per handler, coherent `(session, annotations)` pair,
  reload route and `wrap-reload` gone, ns docstring updated.
- `cache.clj`: values-in signature, docstring corrected.
- `annotations.clj`: `canonical-type-str` (getMapping-based, matches the
  plan verbatim), `fq-name->namespace` relocation, `rule-ns` threading
  through the whole delta chain.
- `analyze.clj`: comparison sites on `canonical-type-str`, display sites on
  `serialize/resolve-type`, `annot-type->str` removed.
- `main.clj`: `--annotations` option, `read-edn-single` (EOF-checked),
  `parse-annotations-arg` (bare-path handling), `-l`/`--annotations`
  composition with warn-skip preserved and spec-source-wins warning.
- `demo_run.clj`: `flag-present?` (both token forms), enrichment default
  injection. `Makefile` explicit flags.
- Test migrations: `api_test`/`session_api_test` single-atom shape,
  `integration_test` in-memory reload replacing the removed endpoint.

## Recommended actions (ordered)

1. **B1** — pass `(:annotations state)` in `transition-reload`; add a
   `:reuse`-spec reload test.
2. **D3 + D4** — explicit auto-detect enumeration + throw, and spec-shaped
   map validation in `build-annotations` (unlocks plan test case 5).
3. **D1** — implement the delta-layer flow (`build-static-layers` +
   `->memory-layer`), which also resolves D2's rename, D5's warning, and
   B2's docstring. This is the actual Phase 2 core.
4. **D6** — `StartOpts` + `s/validate` in `start-system!`.
5. **D7** — resolver unification.
6. Minor batch: M1–M7, D8 (add or de-plan).
7. Update the progress report's Phase 2 section to track D1–D8 as work
   items, then proceed to the six Phase 2 tests and the demo re-scrape
   verification (which should specifically check the `:no-output-types` /
   sink-classification interaction noted in D1).
