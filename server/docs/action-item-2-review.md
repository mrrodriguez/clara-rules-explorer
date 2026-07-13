# Action Item 2 — code review ⚠️ OBSOLETE

> **This review is obsolete.** The code it reviewed (commit `945a406`) has been
> completely replaced. All the functions discussed below — `find-arrow-pos`,
> `sanitize-analysis`, `lhs-usage?`, `usage->fact-type`, `is-class-candidate?`,
> `extract-constructors-from-form`, `constructor->fact-type` — were removed in
> commits `9846d5e` and `510484d` (2026-07-12). LHS stripping is now done
> structurally at the clj-kondo hook level (`strip_lhs.clj_kondo`), eliminating
> the entire bug class this review flagged (text-scan `=>` splitting,
> casing-gated constructor detection, accidental accumulator leakage).
>
> This file is kept for historical context only.

---

Review of commit `945a40624a0c8387278e97f53b840ec5f7a80535`
("action item 2 for analysis fixes"), which addresses item 2 of
`analyze-action-items.md` (resolution-based constructor detection so custom fact
builders like `facts.model.core/->fact` reach
`:clara-rules/dynamic-insert-types-detected` instead of being misread as record
constructors).

Source under review: `src/clara/server/tools/graph/analyze.clj`.
Tests under review: `test/clara/server/tools/graph/analyze_test.clj`,
`test/clara/server/tools/graph/rules/analyze_test_rules.clj`.

Findings below are backed by live REPL experiments run against the real
`:test` classpath (records loaded, parity with `make test`). Baseline suite:
**42 tests, 331 assertions, 0 failures**.

---

## Verdict

The commit is **functionally correct** for its stated goal and the suite is
green. But it is broader than "resolution-based constructor detection," and the
extra machinery — especially `sanitize-analysis` — is **fragile and only
implicitly/accidentally tested**. One concrete correctness bug and several
coverage gaps are documented below.

---

## Finding 1 — `sanitize-analysis` is load-bearing, but its coverage is accidental

`sanitize-analysis` / `lhs-usage?` / `find-arrow-pos` (`analyze.clj:334-363`)
exist to stop LHS constructs (accumulators, fact-type patterns) from polluting
the RHS-derived `:insert-types` / `:retract-types`. This is a real concern: the
clj-kondo analysis includes LHS var/class usages, and we only care about the RHS.

**Evidence** (real classpath, `loan_doc_rules/collect-app-id-card-given-docs`):

| sanitize | `:clara-rules/insert-types` |
|---|---|
| enabled  | `[AllIdCardGivenDocuments]` |
| disabled | `[clara.rules.engine.Accumulator AllIdCardGivenDocuments]` |

The `(acc/all)` LHS accumulator leaks `clara.rules.engine.Accumulator`. Because
the existing tests assert **exact** vectors, they *do* fail without sanitize — so
the feature is covered, but **only as a side effect** of unrelated assertions.

**Gap:** No test states the intent ("LHS accumulators/patterns must not pollute
RHS types"), and the leaked value (`clara.rules.engine.Accumulator`) is named
nowhere. A maintainer refactoring `sanitize-analysis` gets a cryptic failure with
no signpost. This nuance needs a dedicated, self-documenting test.

---

## Finding 2 — `find-arrow-pos` is fragile and has a real correctness bug

`find-arrow-pos` (`analyze.clj:334-341`) splits LHS/RHS by a **textual
`str/index-of "=>"` scan** over the rule's source lines. This is the genuinely
heavy-handed part.

**Reproduced bug:** a `=>` in the rule's **docstring** mis-places the boundary,
and the accumulator leaks straight back into the result:

```clojure
(r/defrule leaky-doc
  "maps A => B in the docstring"          ; <- textual "=>" latched here
  [?fs <- (acc/all) :from [Foo (= ?x x)]] ; LHS now counted as RHS
  =>
  (r/insert! (map->Bar {:y ?x})))
;; => :insert-types [clara.rules.engine.Accumulator t.doc.Bar]   (WRONG)
```

The same failure mode applies to `=>` in an LHS string literal or constraint.

**Secondary issue:** `find-arrow-pos` uses unguarded `(nth lines (dec r))` —
unlike the careful bounds-checking in `extract-form-from-source`
(`analyze.clj:274-299`) — so a row past end-of-source throws
`IndexOutOfBounds`, with no `try`/`catch` around the call site.

**Better approach:** Action Item 1 already **bundles the clara-rules clj-kondo
hook**, which expands `defrule` and *knows* the LHS/RHS boundary structurally. A
structural split (hook output, or a rewrite-clj scan for the `=>` *token* rather
than the substring) would eliminate this entire bug class. None of the current
test rules stress this — they all put `=>` alone on a clean line with no other
`=>` in the form.

---

## Finding 3 — "casing is not the gate" is only half-implemented

The design explicitly rejects PascalCase gating: a `(deftype bar [b])` → `->bar`
must still be recognized as a real type. Tested with a lowercase `deftype`:

| RHS constructor | `:insert-types` | |
|---|---|---|
| `(->bar ?x)`  | `[t.case.bar]` | ✅ works (via `build-constructors`) |
| `(bar. ?x)`   | `[]`           | ❌ **missed** |

`usage->fact-type` still gates on `(java-class? class-str)` (uppercase last
segment) as `is-class-candidate?` (`analyze.clj:124-125`) *before* the
index/resolution check runs. So the Java-constructor path still violates the very
principle AI2 is built on. No test uses a lowercase type, so this is invisible.

**Fix:** make `usage->fact-type` casing-independent (rely on the static class
index / runtime resolution, as `build-constructors` does), or consciously
document that lowercase Java-style constructors are unsupported.

---

## Finding 4 — test coverage gaps for the newly introduced behavior

The new `rule-fact-builder-call` test is good and on-target for `->fact`, but the
added tests do not cover the rest of what the commit introduced:

1. **No test isolates sanitize.** Add a focused rule with an LHS accumulator
   **and** an LHS fact-type pattern, asserting neither
   `clara.rules.engine.Accumulator` nor the LHS pattern class appears in
   `:insert-types`/`:retract-types`. Add a docstring-with-`=>` variant (currently
   produces a wrong result — Finding 2).
2. **No lowercase `deftype` test** via both `->bar` and `(bar.)` — would pin the
   casing principle and expose the `usage->fact-type` asymmetry (Finding 3).
3. **Static-index vs runtime-resolution redundancy is untested in isolation.**
   In-JVM the classes are always loaded, so `resolvable-fact-class` masks the
   static path — the exact CLI `-g` scenario AI2 targets. An in-memory-source
   test referencing a record defined only in another in-memory ns (never a loaded
   class) would exercise the static index as the *sole* signal.
4. **`default-exclude-ns-prefixes`** (`analyze.clj:444-456`) silently changes
   `analyze-session-rules` behavior (now excludes `clojure.*` etc. even when
   `include-ns-prefixes` is nil), with no assertion covering it.

---

## Recommendations

Two things are worth changing, not just testing:

1. Replace `find-arrow-pos`'s text scan with a **structural** LHS/RHS split
   (leverage the bundled hook or a rewrite-clj token match) and add
   bounds-safety. Removes the docstring/string-literal bug class entirely.
2. Make the Java-constructor path (`usage->fact-type`) casing-independent to
   match `build-constructors`, or document the limitation.

Then add the four targeted tests above so the nuance is captured explicitly
rather than riding on exact-match accumulator assertions.

---

## Verification notes

- Findings 1–3 reproduced live against the `:test` classpath (real record
  classes loaded). Finding 1 also cross-checked against `loan_doc_rules.clj`.
- Baseline `clojure -M:test:run-tests`: 42 tests / 331 assertions / 0 failures.
