# Boundary-argument parsing: non-call usages of `insert!`

Status: **open**. Part 1 is a crash fix; Part 3 covers optional follow-ups.

## 1. Problem

Annotation generation aborts on a ruleset whose rules contain a boundary fn
(`insert!`, `insert-all!`, `retract!`, …) that is referenced but not called:

```
IllegalArgumentException: Don't know how to create ISeq from: clojure.lang.Symbol
  at clojure.core/rest
  at clara.explorer.analyze.kondo/read-boundary-args
  at clara.explorer.analyze.callsite/trace-boundary-args
  at clara.explorer.analyze/extract-insert-types
  at clara.explorer.analyze/infer-annotation-for-var
  ...
```

Nothing catches the exception on its way up, so one such usage stops the whole
run, and the caller sees only the bare message.

### Cause

`clara.explorer.analyze.kondo/read-boundary-args` reads the source text under
a kondo `:var-usage` span, then takes `rest` of the result to get the argument
forms:

```clojure
(some-> (read-string-in-ns from call-str) rest)
```

`clara.explorer.analyze.kondo/read-string-in-ns` catches read errors, but the
`rest` call runs outside that `try`. The code assumes the span always covers a
call list like `(insert! x)`. kondo reports a `:var-usage` for *every*
reference to the var, though, and for a reference that isn't a call the span
covers just the symbol. The text reads as the symbol `insert!`, and
`(rest 'insert!)` throws.

The span covers only the symbol in two shapes:

| Shape | Example | kondo `:arity` | Text under span |
|---|---|---|---|
| Value use | `(run! insert! facts)`, `(map insert! xs)`, `(partial insert! …)` | absent | `insert!` |
| Threaded step, bare | `(-> m (->fact :t) insert!)` | `1` | `insert!` |
| Threaded step, parenthesized | `(->> m (->fact :t) (insert!))` | `1` | `(insert!)` |
| Direct call | `(insert! x)` | `1` | `(insert! x)` |

The first two rows hit the crash. The third doesn't crash, but `rest` of
`(insert!)` is empty, so it silently yields no arguments. Probe used to build
the table:

```clojure
;; t.clj, linted with: clj-kondo --lint t.clj --config '{:output {:format :edn} :analysis true}'
(ns t (:require [clara.rules :refer [insert!]]))
(defn a [xs] (run! insert! xs))
(defn b [x]  (-> x (assoc :a 1) insert!))
(defn c [x]  (->> x (merge {}) insert!))
(defn d [x]  (->> x (merge {}) (insert!)))
(defn e [x]  (insert! x))
```

## 2. Fix: no call list, no arguments

```clojure
(defn read-boundary-args
  [{:keys [row end-row col end-col from filename] :as _usage} get-lines]
  (let [lines (get-lines from filename)
        call-str (source-text-at lines row col end-row end-col)]
    (when call-str
      (let [form (read-string-in-ns from call-str)]
        (when (seq? form)
          (rest form))))))
```

Add regression tests in `analyze_test.clj` (or the kondo reader tests) for
`(run! insert! xs)` and `(-> x (->fact :t) insert!)`. Both should analyze
without throwing.

### Why skipping a non-`seq?` form is correct

- **The function only promises argument forms *from the source text*.** A
  usage that isn't a call has no argument forms in its span. Returning
  nothing for it is accurate, not a guess. The caller,
  `clara.explorer.analyze.callsite/trace-boundary-args`, already treats nil as
  "no arguments" (`(or … '())`).
- **There is nothing static to resolve anyway.** In `(run! insert! facts)`
  the values `insert!` receives come from the elements of `facts`, at runtime.
  No form in the source text is "the argument". (Tracing `facts` back to
  whatever built the collection is the job of the general locals tracing, not
  the boundary reader.)
- **The heuristic fallback still runs for these rules.**
  `clara.explorer.analyze/compute-heuristic-fallback-callsites` credits
  record-ctor scan types to any direct-inserter var that had *no*
  boundary argument handled by the constructor or boundary paths. A var whose
  only usage is a value use has no handled arguments, so its scan types are
  still credited, labeled `:via {:source :record-ctor-scan}`.
- **One unreadable usage shouldn't fail every other rule.** Every other read
  failure in `clara.explorer.analyze.kondo` already degrades to nil and leaves
  the callsite unresolved. A non-call usage should degrade the same way.

### What skipping costs

A skipped usage contributes no `TracedArg`, so it produces **no callsite at
all**, not even an unresolved one. When the fallback scan finds nothing
(e.g. the fact is a map built by a constructor fn rather than a record), the
insert disappears from the annotation. The rule's `:resolution` can then
read `:full` even though one of its inserts was never explained. Part 3.1
addresses this.

## 3. Follow-ups

### 3.1 Emit an unresolved callsite for an argument-less usage (recommended)

When `read-boundary-args` returns nothing for a usage, emit a single
placeholder `TracedArg` whose `:arg` is the usage's own symbol. It falls
through every resolver and lands as an unresolved callsite. That makes the
rule's `:resolution` honest (`:partial`/`:none` instead of `:full`) and shows
the insert in the UI as "unexplained" rather than hiding it. Callsite ids stay
stable, because the source text (`insert!`) and position are deterministic.

This is cheap, covers every row of the table above, and fixes the
over-reporting no matter how common the idiom is in a given codebase.

### 3.2 Recover arguments through threading macros (deferred)

For the threaded shapes, the argument *is* in the source, just not inside the
usage span:

```clojure
(->> m (merge defaults) (->fact :t) insert!)
;; ≡ (insert! (->fact :t (merge defaults m)))
```

If we recovered `(->fact :t (merge defaults m))` as the argument, the existing
constructor-of-interest path would resolve it exactly like a direct
`(insert! (->fact :t …))`, with a literal type and full resolution. So the
payoff per usage is real.

What it would take:

1. **Find the enclosing threading form.** kondo also reports `:var-usages`
   for `clojure.core/->` and `->>`, with spans. Pick the innermost one in the
   same `:from-var` whose span contains the boundary usage's span. `:arity 1`
   with a symbol-only span tells this case apart from a value use.
2. **Rewrite rather than macroexpand.** Read the threading form and thread it
   by hand up to and including the boundary step (`->` makes the value each step's
   first argument, `->>` its last). `macroexpand` would work for
   `->`/`->>` themselves, but a general `macroexpand-all` would also expand
   unrelated user macros in a live namespace. A hand-rolled rewrite of just
   these two macros is small and has no side effects.
3. **Trace locals against the threading form's span**, not the symbol's,
   so `clara.explorer.analyze.callsite/trace-local-form` can follow locals the
   threaded steps refer to.
4. **Source text.** The recovered argument is a *synthesized* form. Its
   `:source-str` is the printed rewrite, not text that appears in the file.
   Ids stay deterministic, but editor "jump to argument" would land on the
   boundary symbol rather than on a matching text span.

Where it stops being worth it:

- `some->`/`some->>`/`cond->`/`as->` expand through gensym'd `let` bindings
  that have no kondo locals, so the traced argument would be an opaque local.
  Supporting them means modeling each macro's binding structure.
- Nested threading (`(-> x (->> (f y)) insert!)`) needs the rewrite applied
  recursively.
- `doto` passes the *same* value to each step, so it needs its own rule as
  well.

**Recommendation:** support only `->` and `->>`, and only once 3.1 is in
place and unresolved threaded callsites show up often enough in real
annotations to matter. 3.1 already makes them visible and counts them against
`:resolution`. 3.2 only upgrades them from unresolved to resolved. Threading
straight into a boundary fn is uncommon next to direct
`(insert! (->fact …))` calls, so 3.2 is an incremental resolution gain, not a
correctness fix, and it can wait until the data shows it's needed.
