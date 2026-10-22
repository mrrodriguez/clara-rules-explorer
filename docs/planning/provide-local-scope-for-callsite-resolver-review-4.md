# Review 4: `callsite-resolver-updates` — after `7cbbb92 rev 4 fixes`

Fourth pass. Checks `7cbbb92 rev 4 fixes` against
[`provide-local-scope-for-callsite-resolver-review-3.md`](./provide-local-scope-for-callsite-resolver-review-3.md),
[`defect-refer-all-misattributes-callee-progress.md`](./defect-refer-all-misattributes-callee-progress.md),
[`provide-local-scope-for-callsite-resolver-progress.md`](./provide-local-scope-for-callsite-resolver-progress.md),
and the Clojure engineering standards.

**Bottom line:** R1 (`doseq`/`for`/`loop` ownership expansion), R2, C2–C5, and D from Review 3 all
landed cleanly. However, the C1 change to `destructured-stack?` introduced a **classification
regression** for unrecognized binding macros inside single-arity `defn`/`fn` bodies (R1 below).
Everything else is a small schema, source-reading, or `/clojure-engineering` hygiene item, laid out
below as a concrete fix plan.

## Review-3 disposition

| Item | Status |
|---|---|
| R1 `doseq`/`for`/`loop` ownership in `arg-span-set` | ✅ `:seq-binding`/`:loop` carry internal `:source-span`; `arg-span-set` expands them; `->scoped-resolver` strips `:source-span`. Pinned by `rule-ctor-doseq-source`, `rule-ctor-for-source`, `rule-ctor-loop-source`. |
| R2 refer re-attribution ordering / doc | ✅ Progress doc updated to match the final `cond->` step (see D3 below on removing the `true` clause). |
| C1 multi-arity destructured param | ⚠️ `fn-param-binding?` now checks `= 3` and `destructured-stack?` skips `(second stack)` when `(= \( (second stack))`, which fixes multi-arity destructured params — **but** skipping `(second stack)` without checking `(not past-depth2-close?)` regresses unrecognized binding macros inside single-arity `defn`/`fn` bodies. See **R1**. |
| C2 `past-depth2-close?` on depth-2 branches | ⚠️ Added to `:let-init`, `:seq-binding`, and `:loop`, but omitted on `:destructured` (line 285). Combined with C1, that causes **R1**. |
| C3 `trace-local-form` span pairing | ✅ Stops tracing when `init-span` is nil instead of pairing `init-form` with the boundary span. |
| C4 `bare-usage?` hook-derived docstring | ✅ Documented in `refer/bare-usage?`. |
| C5 single `classify-local-binding` path | ✅ Fallback removed; `:classify-local-binding` required on both context schemas (see **S1** on its return schema). |
| D docs nits | ✅ `rule-annotations.md`, `analyze-clj-kondo-notes.md`, and `defect-refer-all-misattributes-callee-progress.md` updated. |
| P1 bounded init reads | ❌ Still open (tracked as non-blocking in progress doc; plan in **P1** below). |

---

## R. Must fix

### R1. Unrecognized binding forms (`with-open`, `binding`, `when-first`, …) in a single-arity `defn`/`fn` body misclassify as `:destructured`

#### Problem

In `7cbbb92`, `callsite/destructured-stack?` was updated for Review 3 C1 to skip a `(` at
`(second stack)` when `macro-name` is in `fn-param-macro-syms`:

```clojure
(defn- destructured-stack?
  [macro-name {:keys [stack]}]
  (let [inner (if (and (contains? fn-param-macro-syms macro-name)
                       (= \( (second stack)))
                (subvec stack 2)
                (subvec stack 1))]
    (and (seq inner) (every? #{\[ \{} inner))))
```

Meanwhile, in `callsite/classify-binding`, the `:let-init`, `:param`, `:seq-binding`, and `:loop`
branches all check `(not past-depth2-close?)`, **but the `:destructured` branch at line 285 does
not**:

```clojure
(and stack (> n 2) (destructured-stack? macro-name scan))
{:kind :destructured}
```

Consider an unrecognized binding form at the top level of a single-arity `defn` helper body (the
exact prefix shape tested in `kondo-test/test-scan-brackets-depth2-close`):

```clojure
(defn insert-with-open! [m]
  (with-open [r (java.io.StringReader. "")]
    (r/insert! (make-local-fact r m))))
```

1. `with-open` is not in `binding-macro-syms`, so `find-enclosing-binding-macro-usage` returns the
   enclosing `defn` usage (`macro-name` = `'defn`).
2. `kondo/->bracket-scan` from `(defn` up to `r` yields
   `{:stack [\( \( \[] :past-depth2-close? true}`:
   - `[m]` opened and closed at depth 2, setting `:past-depth2-close? true`;
   - `(with-open` opened `(` at depth 2 (`(second stack)` is `\(`);
   - `[r` opened `[` at depth 3 (`(nth stack 2)` is `\[`).
3. `fn-param-binding?` returns `false` because `past-depth2-close?` is `true`.
4. Line 285 evaluates `(destructured-stack? 'defn scan)`:
   - `(contains? fn-param-macro-syms 'defn)` is `true`;
   - `(= \( (second stack))` is `true` — mistaking `(with-open`'s `(` for a multi-arity arity list;
   - `inner` is `(subvec stack 2)` = `[\[]`, which satisfies `(and (seq inner) (every? #{\[ \{} inner))`;
   - `r` classifies as **`:destructured` instead of `:unknown`**.

**Why `rule-resolve-local-with-open` did not catch this:** Clara compiles a rule's `:rhs` with an
outer `(do …)`, so `synth/synthesize-ns-source` emits
`(def __clara_explorer_rule_N__ (fn [] (do (with-open [r …]))))`. That extra `(do …)` pushes
`with-open`'s `(` to depth 3 (`(nth stack 2)`), hiding the bug in rule RHSes while leaving `defn`
helpers exposed.

#### Fix plan

Apply both guards so the invariant holds both by header/body boundary and by container depth:

1. **Header/body boundary (`past-depth2-close?`):** Across all recognized binding macros, once a
   depth-2 `]` has closed (`past-depth2-close?` is `true`), the scanner has exited the
   binding/params vector and is inside the body. No binding in the body can belong to that macro's
   header — including `:destructured`. Guard `(and stack (not past-depth2-close?))` once at the top
   of `classify-binding` (or add `(not past-depth2-close?)` to the `:destructured` branch).
2. **Container depth in `destructured-stack?`:** In both single-arity (`(subvec stack 1)`) and
   multi-arity (`(subvec stack 2)`), `(first inner)` is the binding/params vector itself (`\[`), and
   `(rest inner)` are the destructuring containers nested inside it. A destructured binding must sit
   inside at least one `[` or `{` *within* the binding/params vector:
   ```clojure
   (defn- destructured-stack?
     [macro-name {:keys [stack past-depth2-close?]}]
     (when-not past-depth2-close?
       (let [inner (if (and (contains? fn-param-macro-syms macro-name)
                            (= \( (second stack)))
                     (subvec stack 2)
                     (subvec stack 1))]
         (and (> (count inner) 1)
              (= \[ (first inner))
              (every? #{\[ \{} inner)))))
   ```
3. **Test:** Add a `defn` helper fixture in `analyze_test_rules.clj` whose body wraps the boundary
   call in `(with-open [r (java.io.StringReader. "")] …)` (no enclosing `let` or `(do …)`), and
   assert in `test-resolve-local-unrecognized-bindings` that `r` classifies as `{:kind :unknown}`.

---

## S. Schema & source-lookup fixes

### S1. `:classify-local-binding` return schema in `BoundaryCallsiteCtx` / `ConstructorCallsiteCtx`

#### Problem

`BoundaryCallsiteCtx` (line 706) and `ConstructorCallsiteCtx` (line 729) declare:

```clojure
:classify-local-binding (s/=> LocalBinding s/Symbol s/Any)
```

`LocalBinding` is a closed map schema with `:kind` and optional `:init-form`, `:init-form-span`, and
`:resolve-local`. However, `->local-binding-classifier` returns a map that also carries an internal
`:source-span` key on `:seq-binding` and `:loop` (which `->scoped-resolver` strips before returning
a `LocalBinding` to callers). Prismatic's `s/=>` does not validate fn return values unless wrapped
in `s/fn`, so the mismatch does not fail tests, but the schema declaration contradicts
`->local-binding-classifier`'s docstring.

#### Fix plan

Define a schema for the internal classifier return shape (or derive one from `LocalBinding`):

```clojure
(s/defschema ClassifiedBinding
  "Internal return shape of `classify-binding` / `->local-binding-classifier`:
   a `LocalBinding` (before `:resolve-local` is attached) plus the internal
   `:source-span` used by `arg-span-set` for `:seq-binding`/`:loop` ownership."
  (assoc LocalBinding (s/optional-key :source-span) SourceSpan))
```

Use `ClassifiedBinding` in `BoundaryCallsiteCtx` and `ConstructorCallsiteCtx`.

### S2. Pass `filename` to `get-lines` in `kondo/read-init-form`, `init-form-start`, and `init-form-span`

#### Problem

In `clara.explorer.analyze.kondo`, `read-boundary-args` (line 66), `read-ctor-form` (line 207), and
`->bracket-scan` (line 292) all call `(get-lines ns-sym filename)`. By contrast, `read-init-form`
(line 83), `init-form-start` (line 98), and `init-form-span` (line 191) call `(get-lines ns-sym nil)`,
discarding `(:filename binding)`.

In `analyze/->source-loader`, `filename` is the fallback when `ns-sym` is nil or has no classpath
resource (lines 562–565). Passing `nil` bypasses that fallback and prevents caching by `filename`
when `ns-sym` is absent.

#### Fix plan

Destructure `:filename` from the binding map in `read-init-form`, `init-form-start`, and
`init-form-span`, and pass `(get-lines ns-sym filename)` in all three functions (and forward
`:filename` in `init-form-span`'s call to `init-form-start`).

---

## P. Performance plan (carrying forward Review 3 P1)

### P1. Bound `read-init-form` and `init-form-span` by the enclosing macro's `:end-row`

#### Problem

For every `:let-init` binding, `classify-binding` eagerly calls both `kondo/read-init-form` and
`kondo/init-form-span`, and for every direct `:seq-binding`/`:loop` binding it calls
`kondo/init-form-span`. Currently:

- `read-init-form` joins `(drop row lines)` through the **end of the file**;
- `init-form-start` scans lines up to `(count lines)`;
- `init-form-span` calls `init-form-start`, then again joins `(drop sr lines)` through the **end of
  the file** and runs `LispReader/read` a second time.

Memoization (`->local-binding-classifier`) ensures this happens at most once per binding per run,
but on a large rule file with many `let` bindings each classification still allocates ~3
rest-of-file strings.

#### Fix plan

1. In `classify-binding`, `usage` (the enclosing binding-macro `:var-usage`, with `:end-row` /
   `:end-col`) is already in hand. An init/source form inside `usage` cannot extend past
   `(:end-row usage)`.
2. Allow `read-init-form`, `init-form-start`, and `init-form-span` to accept an optional `end-row`
   bound (or pass `usage` / `{:end-row (:end-row usage)}` from `classify-binding`), slicing `lines`
   with `(subvec (vec lines) ... (min (count lines) end-row))` instead of `(drop row lines)`.
3. Optionally, have `init-form-span` (or a combined helper) return both `{:init-form :init-form-span}`
   from a single `LispReader/read` pass over the bounded slice so `:let-init` classification parses
   the init form once instead of twice.

---

## D. `/clojure-engineering` standards cleanup plan

### D1. Qualify code references in docstrings with in-scope aliases or full namespaces

Per `/clojure-engineering` (*Docstrings & Comments → Qualified References*): reference vars and
namespaces with a qualifier that resolves in the file's own `:require` (an in-scope alias or the
fully-qualified name — never a partial namespace suffix that is not an alias in the file).

- **`explorer/src/clara/explorer/analyze/callsite.clj`:**
  - Line 35: `` `analyze.alias` `` → `` `clara.explorer.analyze.alias` ``
  - Lines 47–48: `` `analyze.kondo` `` / `` `analyze.ctor` `` → `` `clara.explorer.analyze.kondo` `` /
    `` `clara.explorer.analyze.ctor` `` (or use the in-scope aliases `kondo` / `ctor` when naming
    vars)
  - Line 487: `` `annotations.callsite/aggregate-resolution` `` →
    `` `clara.explorer.annotations.callsite/aggregate-resolution` ``
  - Lines 751, 1097: `` `analyze/extract-insert-types` `` →
    `` `clara.explorer.analyze/extract-insert-types` ``
- **`explorer/src/clara/explorer/analyze.clj`:**
  - Line 638: `` `analyze.index`, `analyze.callsite` `` →
    `` `clara.explorer.analyze.index`, `clara.explorer.analyze.callsite` ``
  - Line 731: `` `analyze.callsite` `` → `` `clara.explorer.analyze.callsite` ``

### D2. Remove cross-file review/plan section labels from source and test comments

Per `/clojure-engineering` (*Docstrings & Comments → No Cross-File Section Numbers*): do not cite
ad-hoc section numbers or review item IDs (`A1`, `A2`, `R1`, `§1`, `§2.4`, `defect table row 1`)
inside code comments, docstrings, or `testing` strings; state the invariant directly.

- **`explorer/src/clara/explorer/analyze/callsite.clj`:**
  - Line 369: `;; form with the boundary span (which would silently restore A2).` → state that
    pairing the init form with the boundary span would scope `:resolve-local` to the boundary call
    instead of the init form.
- **`explorer/test/clara/explorer/test/rules/analyze_test_rules.clj`:**
  - Line 923: `;; §2.4 fixtures: the automatic locals-tracing chain` → remove `§2.4`.
  - Line 949: `;; Review-2 fixtures: ...` → describe the fixtures by behavior, not review round.
  - Line 955: `;; ... scope to the traced form's span (A2), not the` → remove `(A2)`.
- **`explorer/test/clara/explorer/analyze_test.clj`:**
  - Line 838: `"the §1 two-hop shape: ..."` → `"two-hop let chain: ..."`
  - Line 841: `"... not the boundary call's (A2)"` → remove `(A2)`.
  - Line 2070: `"... binding *sources* are owned (R1)"` → remove `(R1)`.
- **`explorer/test/clara/explorer/analyze/refer_test.clj`:**
  - Lines 72, 142, 149, 162: remove `(A1)`, `(defect table row 1)`, `(row 2)` labels; the prose
    already states the behavior directly.

### D3. Avoid bare-noun helper names in `refer_test.clj` and `true` clause in `analyze.clj` `cond->`

- **`explorer/test/clara/explorer/analyze/refer_test.clj` (lines 52–70, 121):**
  Rename the usage-builder helpers and accessor to follow the `->` / `get-` prefix rule:
  - `bare-usage` → `->bare-usage`
  - `aliased-usage` → `->aliased-usage`
  - `qualified-usage` → `->qualified-usage`
  - `collision-winner-sym` → `get-collision-winner-sym`
- **`explorer/src/clara/explorer/analyze.clj` (lines 799–806):**
  Replace the unconditional `true (update :var-usages refer/re-attribute-refer-all-usages)` inside
  `cond->` with a direct `update` (either before `alias-by-rule` or chaining `(-> rule-source-analysis (cond-> …) (update :var-usages refer/re-attribute-refer-all-usages))`).

---

## Execution checklist

1. [ ] Fix **R1** in `clara.explorer.analyze.callsite` (`classify-binding` + `destructured-stack?`)
       and add a `defn`-body `with-open` fixture/assertion in `analyze_test_rules.clj` and
       `analyze_test.clj`.
2. [ ] Fix **S1** (`ClassifiedBinding` schema in `callsite.clj`) and **S2** (`filename` passed to
       `get-lines` in `kondo.clj`).
3. [ ] Address **P1** (bound init-form reads by the enclosing macro usage's `:end-row` in
       `kondo.clj` / `callsite.clj`).
4. [ ] Clean up **D1**, **D2**, **D3** across `callsite.clj`, `analyze.clj`, `refer_test.clj`,
       `analyze_test.clj`, and `analyze_test_rules.clj`.
5. [ ] Run `make format`, `make lint`, `make reflection-check`, and `make test` in `explorer/`, and
       update [`provide-local-scope-for-callsite-resolver-progress.md`](./provide-local-scope-for-callsite-resolver-progress.md).
