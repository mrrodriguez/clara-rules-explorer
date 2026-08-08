# Design Review 3 — fix-wm-anno-enrichment-flow-plan (Revision 4)

Reviewer: kimi. Scope: the Revision-4 plan itself, cross-checked against the
current `hot-swap-session-data` branch code (`server.clj`, `api.clj`,
`cache.clj`, `main.clj`, `annotations.clj`, `analyze.clj`,
`annotations/merge.clj`, `serialize.clj`, tests, dev scripts) and against a
live REPL where runtime behavior mattered (findings B4, B5).

**Overall assessment:** the two-phase restructure is the right call and the
core Phase 2 fix (memory delta computed against the accumulated static base
instead of `{}`) is sound and matches the `->memory-layer` machinery from
eb78335. The spec-in-state invariant genuinely closes review-1 issue 1.
However, the **default-system lifecycle semantics are internally
contradictory and break both the REPL restart workflow and the plan's own
new test #2**, and the headline claim for `canonical-type-str` is
**demonstrably false for unqualified symbols** (REPL-verified below). Both
need resolution before implementation; the rest is pinning-down work.

---

## A. Blocking findings

### B1. `start!`'s unconditional "stop the previous default's Jetty" contradicts the multi-instance design and breaks the plan's own test #2

Default-system semantics (review-2 disposition #5) say:

> `start!` builds the new system fully, **stops the previous default
> system's Jetty**, then registers the new system as the default.

But Phase 2 test #2 (`test-start-auto-detect-enrichment`) is specified as:

> Uses the 2-arity/system-handle form so it **doesn't disturb the shared
> fixture server**.

These cannot both be true. Test #2 calls `start!` to obtain an isolated
system (it must — `start!` is the only system constructor in the plan). The
moment it does, the *previous* default — the shared fixture server — has its
Jetty stopped. Which arity the test uses *after* construction is irrelevant;
the damage happens inside `start!` itself. Under cognitect test-runner all
namespaces share one JVM, so every later HTTP assertion against the fixture
server fails.

More broadly: unconditional stop makes it impossible to run two systems
concurrently via `start!`, which is the entire point of the multi-instance
design. The unconditional stop made sense when all state was global (two
servers would serve the same atoms anyway — one had to die). Phase 1 removes
that rationale but keeps the behavior.

Note also that review-2 issue 5's rejected `:register-as-default?` flag was
exactly the mechanism for this; the accepted substitute ("capture the
`start!` return value, use 2-arity forms") does not address the stop.

**Recommendation — pick one, state it explicitly:**
(a) stop the previous default's Jetty **only on port collision** (preserves
    the REPL restart workflow, allows concurrent systems on distinct ports);
(b) split construction from registration: `start-system!` (pure constructor,
    never touches the default) + `start!` (= `start-system!` + register as
    default + manage old default); tests use `start-system!`;
(c) reinstate the flag.

Option (a) is the smallest delta from the plan as written and covers both
callers; (b) is the cleanest API.

### B2. Jetty stop/start ordering breaks same-port restart (regression vs. current code)

The `start!` sketch (§1d) binds `server (jetty/run-jetty handler ...)` in the
`let`, *then* has the comment "stop previous default-system's Jetty, if
any". The disposition text ("builds the new system fully, stops the previous
default system's Jetty, then registers") confirms the new Jetty starts
before the old one stops.

Same-port restart is the dominant workflow: REPL re-`start!` on 9999,
`make demo-run` on 9001, the test fixture re-starting on `*port*`. With the
old Jetty still bound, `run-jetty` throws `BindException`. The **current**
code stops the old server *before* `run-jetty`
(`(when-let [server @server-instance] (Server/.stop server))` precedes
`jetty/run-jetty`) — the plan as written is a regression.

This interacts with B1: "stop before bind, but only on port collision"
satisfies both.

### B3. Path F's "auto-detect modes re-run (kondo + ->memory-layer)" is false about kondo — and no kondo-invalidation gesture exists

Path F says reload re-runs "kondo + `->memory-layer`". But the reseed rule
is session-identity-based **only**, and `transition-reload` carries
`:analyze-cache` over. Kondo analyses are keyed by `ns-sym`
(`get-or-analyze-ns-analysis`), so on reload the generated layer is rebuilt
from **cached** per-ns analyses. Only `->memory-layer` actually re-runs.

The behavior is arguably *correct* — the session's compiled rulebase hasn't
changed, and re-analyzing new on-disk source against a stale compiled
session would be incoherent — but then:

1. Path F's parenthetical is wrong and should say so: the generated layer
   refreshes from cache; only file-backed *annotation* sources and the
   memory layer re-read/re-derive.
2. There is **no operator gesture that invalidates kondo analysis** short of
   constructing a new session object. If a rule namespace is edited and
   re-loaded at the REPL while the same session object lives, both reload
   and annotations-only swaps silently reuse stale analysis. That may be an
   acceptable documented limitation (the session is stale anyway), but it is
   currently unstated — and review-2 disposition #2's "the reseed rule is
   session-identity-based only" makes it a designed-in limitation that
   deserves one explicit sentence.

### B4. `canonical-type-str`'s central claim is false for unqualified symbols — `ns-resolve` does not consult namespace imports (REPL-verified)

The plan's docstring for `canonical-type-str` claims:

> When `prod-ns` is given, symbols are resolved in that namespace first, so
> a rule's `AuditTrail` symbol and the `my.ns.AuditTrail` Class compare
> equal.

Verified against a live REPL with this repo's own rule namespaces loaded:

```clojure
(contains? (ns-imports 'clara.server.tools.graph.rules.loan-doc-rules)
           'AuditTrail)                                  ;; => true
(binding [*ns* (the-ns 'clara.server.tools.graph.rules.loan-doc-rules)]
  (resolve 'AuditTrail))
;; => clara.server.tools.graph.rules.loan_doc_rules.AuditTrail   (the Class)

(ns-resolve 'clara.server.tools.graph.rules.loan-doc-rules 'AuditTrail)
;; => nil            <-- ns-resolve does NOT check ns-imports

(ns-resolve 'clara.server.tools.graph.rules.loan-doc-rules
            'clara.server.tools.graph.rules.loan_doc_rules.AuditTrail)
;; => the Class      <-- qualified symbols resolve via the class-name fallback
```

So `canonical-type-str` as specified resolves **qualified** class-name
symbols (EDN sidecar form — verified: the demo sidecar stores qualified
symbols) but returns `nil` for the **unqualified** form and falls through to
`(str t)` = `"AuditTrail"`, which never equals the Class's
`"…loan_doc_rules.AuditTrail"`. The unqualified form is exactly what a rule
author writes in `:props {:clara-rules/insert-types [AuditTrail]}` — the
case the ns-aware canonicalization exists for. The same latent gap exists in
`serialize/resolve-type` (renders `symbol[AuditTrail]`) and
`annotations/resolve-type-locally` today, so this is not a regression — but
the plan's stated goal ("a Class, its `.getName` string, and its source
symbol all canonicalize identically") is not achieved by the shown
implementation, and issue-#6's resolution rests on that claim.

**Recommendation:** resolve with import awareness —
`(some-> (find-ns prod-ns) (.getMapping t))` or
`(binding [*ns* (the-ns prod-ns)] (resolve t))` — instead of `ns-resolve`,
and add tests covering (i) unqualified record-class symbols in `:props`,
(ii) qualified symbols from EDN, (iii) namespaces not loaded in the server
JVM (plain `main -s session.bin` deserializes; `find-ns` → nil → degrade to
`str`, which still matches Class `getName` for qualified symbols — worth
pinning in a test).

Related duplication hazard: after this change the codebase has **three**
type resolvers with subtly different semantics and defensiveness —
`annotations/resolve-type-locally` (try/catch-guarded, returns Class /
**deref'd var**), `serialize/resolve-type` (unguarded, display strings), and
`canonical-type-str` (unguarded, canonical strings, var → qualified-name
string). They will drift. Implement `canonical-type-str` as "resolve via one
shared helper, then `type-str`", and decide one try/catch policy
(`resolve-type-locally` guards `ns-resolve`; the other two don't).

### B5. `transition-start` discards the startup kondo analysis — seed vs. committed value are conflated

The postcondition table gives `transition-start`'s `:analyze-cache` as
"fresh `{}` seed", and Path A shows the 3-arity
`(build-annotations s spec {})` — which per §1c "uses a fresh throwaway
cache". So for `start!` with `:auto-detect*`, every namespace is kondo-
analyzed during startup and the result is **thrown away**; the first
`reload-annotations!` (or annotations-only swap) re-analyzes all namespaces
even though the session never changed. That is precisely the waste the
cache exists to avoid, and it's inconsistent with §1c's "transitions use the
4-arity to thread the cache".

`transition-start` should do exactly what `transition-swap` does with seed
`{}`: thread a tmp atom and commit `@tmp`. The postcondition table should
distinguish *seed* from *committed value* (start: seed `{}`, commit `@tmp`;
swap: seed per the identity rule, commit `@tmp`; reload: seed = carry-over,
commit `@tmp`).

---

## B. Semantic gaps / unspecified behavior

### G1. `AnnotationsArg` is a tautological pred — the "fail fast at the boundary" claim (issue C) is overstated

```clojure
(s/pred (fn [x] (or (nil? x)
                    (and (map? x) (or (contains? x :source) (contains? x :enrichment)))
                    (ann.merge/merged-annotations? x)
                    (vector? x) (string? x) (instance? File x)
                    (map? x))) ...)   ;; <-- makes every earlier map clause redundant
```

Any map passes. A typo'd spec like `{:soruce "x.edn"}` validates, is
normalized by `build-annotations` to `{:source {:soruce "x.edn"}}`, passes
through `->bare-annotations`, and lands in the state as an annotations map
containing a bogus rule named `":soruce"`. Silent garbage — the failure mode
issue C claims to eliminate. (`{:enrichment :auto-dectect}` does get caught,
but only by the `case`-throw downstream, not at the boundary.)

At minimum, when the arg is a map containing `:source` or `:enrichment`,
validate it against the existing `AnnotationsSpec` schema (which already
constrains `:enrichment` to the enum — giving the typo a schema error
instead of the `case`-throw). The bare-map-vs-spec-map ambiguity is inherent
to the design; document it.

### G2. "Reload always commits a fresh `:annotations` reference" is false for two designed paths

The claim underpins "the post-swap `warm!` always does real work"
(review-2 issue 7). Counterexamples:

- `{:enrichment :reuse}` with no source returns `current-annotations` —
  the *identical* reference. Reload neither invalidates the identity cache
  nor warms anything. A stored `:reuse` spec makes reload a permanent
  no-op — probably the coherent reading of "keep what you have", but it
  must be stated, since principle 3 says reload "reproduces (and refreshes)
  the current state".
- Bare in-memory maps and `MergedAnnotations` sources pass through
  `->bare-annotations` unchanged — same reference again. Defensible
  (nothing could have changed), but again contradicts the blanket claim.

Scope the claim to file-backed and auto-detect specs.

### G3. `{:source [paths...]}` flattens per-file layer provenance (behavior change vs. old `:layers` startup)

Old `start!` folded `[props, file1, file2, …]` in **one** `merge-layers`
call: per-key `:provenance` and per-callsite `:from-layer` attributed to
each file's layer. The new `build-static-layers` routes the whole source
through `coerce-to-bare-annotations`, producing **one** `:id :source` layer
whose inner provenance map is discarded — the outer merge attributes
everything the files contributed to `:source`. (`:from-layer` on callsites
survives, since it's stamped into the annotations themselves; top-level
per-key provenance does not.)

There is also a duplicate-id interplay: `merge-layers` **throws** on
repeated layer `:id`s. Two files both carrying `:id
:clara.tools.graph.analyze/generated` (plausible: two generated sidecars)
threw under old `start!` but are tolerated by the coerce path (ids erased
inside the inner merge). If `build-static-layers` instead conj'd each file
as its own layer (`(map ann.merge/->layer …)` on vector sources) — which
fixes provenance and removes the double `props-layer` fold — the dup-id
strictness returns. Pick the semantics deliberately and document the choice;
either way, multi-file startup behavior changes vs. the old `:layers` path
and the plan doesn't mention it.

(The double props fold — once inside `coerce-to-bare-annotations`, once as
the base of `build-static-layers` — is value-idempotent but smelly; per-file
layers eliminate it.)

### G4. Missing `-l` file tolerance is dropped silently

`run-explorer-server` currently warns-and-skips missing layer files
(`(filter file-exists?) layer` plus a per-file warning). The new composition
snippet uses raw `(vec layer)`; a missing file now throws `ex-info` from
`read-layer` inside `transition-start`. Fail-fast at startup is arguably
better, but it is an unannounced CLI behavior change, and the
"all `-l` files missing" case degrades from "run with props-only" to a
startup crash. Decide and document.

### G5. `stop!` 1-arity on the default system leaves a dangling default

`stop!` 0-arity resets `default-system` to nil; the 1-arity's effect on
`default-system` when stopping the system that *is* the default is
unspecified. As written, the default would keep pointing at a stopped
system whose `state-atom` is still live — 1-arity `swap-session!` would then
succeed (swap + warm!) against a dead server, and the promised "no explorer
system started" fail-fast would not fire. Specify:
1-arity `stop!` resets the default when `(identical? system @default-system)`.

### G6. The promised fail-fast on unstarted systems is not in the sketches

`([opts] (swap-session! @default-system opts))` with a nil default NPEs at
`(swap! (:state-atom nil) …)`. The plan promises a clear
"no explorer system started" error — add the explicit check to the sketches
(both `swap-session!` and `reload-annotations!`), since exactly this class
of "docstring promises, code doesn't" gap produced round-1 issue B.

---

## C. Minor issues / documentation drift

| # | Item |
|---|---|
| M1 | `swap-session!` sketch passes raw `opts` (including `:warm-cache?`) to `transition-swap`, contradicting the contract's "`:warm-cache?` … never reaches the transition". Use `(select-keys opts [:session :annotations])` to make the contract real. |
| M2 | "kaocha runs namespaces serially" — the repo uses **cognitect test-runner** (`server/deps.edn` `:run-tests` alias). The serial conclusion still holds (single JVM, sequential namespaces); cite the right tool. |
| M3 | Path A/D diagrams call `build-auto-detect-annotations(s, nil, mode)` — 3-arity; the new signature is 4-arity `[session analyze-cache source enrichment]`. Exactly the class of drift prior rounds were burned by. |
| M4 | "Pure transitions" framing: transitions perform I/O inside `swap!` — file re-reads (reload's whole purpose), `materialize-bundled-kondo-config!` (note the `!`), and the WM-unavailable `println`. The println also contradicts the plan's own "`println` diagnostics stay outside the `swap!`" rule. Restate as "value-deterministic, retry-safe-enough under the single-threaded ruling; reads allowed, non-idempotent writes (Jetty, warm!) stay in shells". |
| M5 | `demo_run.clj` flag detection `(some #{"--annotations"} args)` misses the `--annotations=…` (and `-l=…`, `-p=…`) equals forms → double injection. Benign under tools.cli last-wins, but sloppy. |
| M6 | `parse-annotations-arg` uses `edn/read-string`, which reads only the first form — trailing garbage in an inline spec or spec file is silently ignored. Consider `edn/read` on a `PushbackReader` + EOF check. |
| M7 | `start!` sketch passes the raw `config` (not the `s/validate`d value) to `transition-start`, despite the transition's docstring saying it receives a validated `StartOpts`. Pass the validated map. |
| M8 | "Atoms after consolidation: three" — three *kinds*; 2N+1 instances for N systems. Say that. |
| M9 | "Existing `server_test` / `integration_test` pass unchanged" is immediately followed by a rewritten `start-server!` helper. Reword to "pass with only the helper update shown". |

---

## D. What the plan gets right (verified against the code)

- **The core Phase 2 fix is sound.** `->memory-layer` (eb78335) computes
  `annotations-delta` of enrichment-over-base; `dimension-delta` emits only
  new types plus the detection key, and `rule-delta`'s
  `:no-output-types` tombstone handling is already correct. Computing the
  delta against the accumulated static base (props + source + generated)
  instead of `{}` eliminates both the false "truly new" positives and the
  provenance re-claiming. The layer-merge path preserves the generated
  layer's `:callsites` alongside the memory layer's
  `:fact-instance-derived-types` (per `fold-detection-key` semantics).
- **Spec-in-state invariant** (`:annotations-spec` always the spec, written
  only by transitions) cleanly closes review-1 issue 1; `transition-reload`
  from a nil spec → `{}` is consistent with documented session-only-swap
  semantics.
- **The tmp-atom `:analyze-cache` design matches `analyze-session-rules`'
  actual protocol** — verified `get-or-analyze-ns-analysis` does idempotent
  `swap! assoc` per ns and defaults to a fresh atom. Committing `@tmp` keeps
  the state map a pure value.
- **Identity-based reseed is strictly better than today** — current
  `swap-session!` resets `analyze-cache-atom` on *any* `:session` key, even
  the identical session; the new rule avoids pointless re-analysis.
- **`cache.clj` value-in signatures + single per-request deref** eliminate
  the mismatched-pair hazard by construction; `build-state` is already pure
  in `(session annotations)`.
- **The removal surface for HTTP reload is accurate** — verified
  `wrap-reload`, the `post-annotations-reload` block in
  `integration_test.clj` (~line 224–289), `docs/explorer-graph-api.md` line
  494, and that the UI never calls the endpoint.
- `props-layer` accepts a raw rulebase (verified) — no special-casing
  needed, as disposition #2 states.
- The `case`-throw for unknown enrichment is genuinely reachable (the
  `AnnotationsArg` pred admits the typo'd map — see G1) and worth having.

---

## E. Recommended dispositions

| Finding | Severity | Suggested action |
|---|---|---|
| B1 | Blocking | Adopt "stop previous default only on port collision" (or split `start-system!`/`start!`); restate test #2 accordingly. |
| B2 | Blocking | Stop old default's Jetty **before** `run-jetty`; fold into B1's rule. |
| B3 | Blocking (docs) or design | Correct Path F; add one sentence stating kondo results refresh only on session identity change, and that no explicit invalidation gesture exists (or add one to `reload-annotations!`). |
| B4 | Blocking | Replace `ns-resolve` with import-aware resolution (`getMapping`/`resolve`); unify the three resolvers; add the three test cases listed. |
| B5 | Blocking | `transition-start` threads tmp atom and commits `@tmp`; table distinguishes seed vs. committed. |
| G1 | High | Validate spec-shaped maps against `AnnotationsSpec` at the boundary; document the bare-map ambiguity. |
| G2 | Medium | Scope the "fresh reference" claim; state `:reuse`-spec reload semantics. |
| G3 | Medium | Decide per-file layers vs. coerce; document provenance/dup-id consequences. |
| G4 | Medium | Decide warn-skip vs. fail-fast for missing `-l` files; document. |
| G5 | Medium | 1-arity `stop!` clears the default when stopping the default system. |
| G6 | Low | Show the nil-default guard in the sketches. |
| M1–M9 | Low | Fix in place. |
