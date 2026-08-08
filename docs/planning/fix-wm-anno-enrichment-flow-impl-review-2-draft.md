# Implementation Review 2 — WM Annotation Enrichment Flow

**Scope:** The two commits since the final plan: `4743f0d` "impl 1" and
`162e0c3` "no need for canon anno str fn". `4743f0d` was reviewed in
`fix-wm-anno-enrichment-flow-impl-review-1.md` and its fix round (all
findings resolved). This review focuses on `162e0c3`, which lands the
Phase 2 tests plus a **last-minute architectural reversal**: deletion of
`canonical-type-str`.

**Verified independently:** `make test` → **196 tests, 1375 assertions,
0 failures, 0 errors**; format-check clean; lint clean.

## Post-publication correction (after design-owner rebuttal)

Two findings are revised after the design owner pointed at the progress
doc's "Plan deviation: `canonical-type-str` deleted, `resolve-type` used
instead" section, which this review's N1/N3 missed (a truncated read of the
doc diff — the section exists and is accurate):

- **N1 is WITHDRAWN as a defect and reclassified as a justified plan
  deviation** (see revised N1 below). The kind-conflation argument is
  correct, and the Class-vs-string convergence case `canonical-type-str`
  existed for does not occur in this pipeline.
- **N3 is reduced** to a counts nit (claimed 197/1383 vs actual 196/1375).

N4, N5, N2/N6 stand as written.

## Verdict

The five new Phase 2 tests are genuine, well-structured, and pass. The
`canonical-type-str` deletion is a **documented, technically justified
deviation** from the plan (see revised N1) — the plan should be amended to
match. The remaining real issues are tracking/coverage: Phase 2 declared
complete with D1/D2/D7 unimplemented and unlisted (N4), the `:reuse`-reload
and file-backed reload tests still absent (N5), and the stale verification
counts (N3).

---

## N1. `canonical-type-str` deletion — WITHDRAWN as defect; justified deviation, plan amendment owed

_Original finding claimed this was an undocumented, technically-wrong
reversal. Both parts were wrong._

The deviation is documented in the progress doc with rationale, and the
rationale survives scrutiny:

1. **Kind conflation is the real bug risk.** `canonical-type-str` mapped
   keyword `:thing` and string `"thing"` both to `"thing"`. With Clara's
   pluggable fact-type-fn those are distinct, legitimately coexisting types
   — the demo rules already use keyword types (`::income-document`) and
   compound vector types (`[:loan/status "verified"]`). `resolve-type`'s
   quotes/colon/wrappers are not display artifacts; they are kind tags.
   Dismissing it as "display-only" was this review's error.

2. **The case `canonical-type-str` uniquely handled — Class vs
   class-name string — does not occur.** Evidence from the actual pipeline:

   - Generated (kondo) layer emits **symbols**
     (`clara...loan_app_rules.ApplicationOutcome`) and **keywords**
     (`:loan-doc-rules/document-check-input`) — see
     `test-resources/.../loan-doc-rules-annotations.edn`; no strings.
   - Rule `:props` use unqualified **symbols** (`ApplicationOutcome`),
     keywords, and compound vectors — no strings.
   - Session-derived raw types are Classes, keywords, compound vectors.
   - The only strings in annotation values are display-formatted outputs of
     the memory layer, which never serve as a comparison base in the new
     architecture (server enriches against `{}`; D1's base is static
     layers only).

3. **The convergence that IS needed — symbol ↔ Class — works under
   `resolve-type`** via two independent paths: import-aware `ns-resolve`
   (unqualified `ApplicationOutcome` in its own rule ns), and the `pr-str`
   fallback for unresolved symbols (a fully-qualified class symbol prints
   as its name, which equals `.getName`).

So `canonical-type-str`'s distinctive capability guarded a case that never
happens, at the cost of conflating cases that do. The deletion is the right
call.

**Residual actions (small):**

- Amend plan-final §4 / principle 2 / test case 6 to record this decision,
  so the authoritative doc stops mandating a deleted function.
- On the "display strings in a comparison base" edge: verified it
  **cannot arise from pipeline data**, so it needs no plan caveat beyond a
  one-liner. Display strings only ever appear in
  `:fact-instance-derived-types` (explicitly display-only per the
  `add-auto-detected-annotations` docstring: "Raw types stay objects
  throughout ... never demoted to strings ahead of the merge"), which is
  not a compared key. The compared keys (`:insert-types`, `:retract-types`)
  carry raw objects end-to-end: symbols/keywords from files (EDN
  round-trips symbols via `pr`), raw Classes/keywords from session
  enrichment. Only a hand-authored in-memory map with string class names
  could introduce them — caller error, not a system case.

**Merge-level vs delta-level canonicalization (originally flagged as an
inconsistency; on inspection it is deliberate and safe):** the two sites
have genuinely different requirements. Delta/novelty comparison needs
kind-aware + namespace-aware keys (`resolve-fn`) because it compares raw
session objects against layer values. Merge dedupe needs cheap,
namespace-less convergence (`type-str`) for cross-layer cleanup — and
given the no-strings invariant above, `type-str`'s string-passthrough is
inert on pipeline data, so "dedupe at merge but not at delta" is
unreachable. The one *reachable* asymmetry runs the other way and is
benign: `resolve-fn` converges an unqualified props symbol with its Class
(via rule-ns resolution) while `type-str` does not — so merge can keep two
representations of one type (e.g. `[ApplicationOutcome, #class
...ApplicationOutcome]`) for a props-only rule with no generated-callsite
derivation. Latent (no demo rule hits it; derivation's `:from-callsites`
replace semantics covers the props+generated case), data-quality only,
pre-existing. If it ever surfaces, the fix is threading rule-ns into
`merge-type-vec` (merge has the rule-name key, so the ns is available) —
not re-coupling the two canonicalizers. Empirical backstop: the demo
re-scrape should show one entry per logical type in `insert-types` (the
current scrape does).

## N2. Dependency inversion: `annotations.clj` → `serialize.clj`

The deletion required annotations.clj (the low-level library) to require
serialize.clj (the boundary display serializer). Plan §4/D7 intended the
opposite direction — serialize reimplemented on a shared resolver living in
annotations. No cycle today (serialize requires only clara.rules internals),
but the inversion entrenches the comparison/display coupling.

## N3. Progress doc — deviation section accurate (missed on first pass); counts nit remains

_Corrected:_ the doc's "Plan deviation: `canonical-type-str` deleted,
`resolve-type` used instead" section and the updated Code-changes/test-list
entries accurately describe the committed code. This review's original N3
(claims of describing nonexistent code) was based on a truncated read of
the doc diff and is retracted except:

- Line 154 claims "197 tests, 1383 assertions" — actual run:
  **196 / 1375**. Second consecutive progress report whose verification
  numbers don't reproduce (off by exactly one test, consistent with the
  deleted `test-canonical-type-str-resolution`).

## N4. Tracking gap repeats: Phase 2 "complete" with D1/D2/D7 unimplemented

The Phase 2 section declares "✅ COMPLETE" and its "Remaining (from plan)"
lists only the demo re-scrape. But:

- **D1** (delta memory layer, plan §1b): server.clj:126 still
  `enrich-annotations-from-session session {}` — full layer appended last,
  no `->memory-layer`, no tombstone clearing.
- **D2** (per-file source layers, plan §1a): still a single
  `{:id :source}` wrapper around `coerce-to-bare-annotations`.
- **D7** (resolver unification, plan §4): serialize.clj untouched — and N1
  moved further from it.

These were explicitly listed under "Deferred to Phase 2" in the review-fix
section; the Phase 2 section then completed _without_ them and dropped them
from "Remaining". Same fall-through pattern as review 1's headline finding.
If the intent is to ship without D1/D2/D7, that needs an explicit plan
amendment; the demo verification should then specifically check the
`:no-output-types` sink-classification risk D1 was designed to fix.

## N5. Test coverage gaps vs the plan

- Plan case 4 specified a **file-backed** reload test (temp EDN, modify,
  reload, assert re-read). The implemented `test-reload-after-swap-with-spec`
  uses `{:enrichment :auto-detect}` only — file re-read on reload is
  uncovered. The plan's "session untouched by reload" assertion is also
  absent.
- The `:reuse`-reload regression test (B1 fix verification, recommended last
  round) is still absent: `test-swap-session-reuse` covers swap, but nothing
  stores a `{:enrichment :reuse}` spec and then calls `reload-annotations!`.
- Plan case 6 claimed but missing (see N3).

## N6. Minor doc inaccuracies introduced in the commit

- `type-str` docstring: "A Class, its .getName string, and an unqualified
  symbol all converge to the same string" — false: `'AuditTrail` →
  `"AuditTrail"` vs `AuditTrail` class → `"my.ns.AuditTrail"`.
- The new `new-types` docstring presents string/Class non-convergence purely
  as a feature ("never collide") without noting the trade-off.

## What is solid in `162e0c3`

- The five new tests are well-built: `start-system!` isolation with
  `finally`-stop on a dedicated port, direct state-atom assertions plus HTTP
  cross-check, rulebase-only (no-WM) scenario, and reload semantics matching
  plan cases 3–4's spec-nil behavior.
- `test-build-annotations-unknown-enrichment` correctly observes that schema
  validation catches the typo before the case-throw (honest comment).
- Suite, format, lint all green (verified independently).

## Recommended actions (ordered, revised)

1. **N1 — amend plan-final** §4 / principle 2 / test case 6 to record the
   `resolve-type`-as-comparator decision (rationale already written in the
   progress doc's deviation section — promote it), including the
   display-strings-as-base edge note. No code change.
2. **N3 — fix the verification counts** (196/1375, not 197/1383).
3. **N4 — resolve D1/D2/D7 explicitly**: implement, or amend the plan and
   re-scope Phase 2; either way keep them tracked until then. (With N1
   accepted, D7's "unify in annotations" direction is obsolete — serialize
   is now the kind-aware canonicalizer; the plan amendment should say so,
   which also resolves N2.)
4. **N5 — add the `:reuse`-reload test** (one deftest: store `{:enrichment
:reuse}` spec, reload, assert identical annotations) and, if file-backed
   reload remains planned, the temp-EDN reload test.
5. **N6** — docstring fixes (the `type-str` convergence overclaim; the
   `new-types` docstring is fine once the plan amendment lands).
6. Then proceed to the demo re-scrape verification.
