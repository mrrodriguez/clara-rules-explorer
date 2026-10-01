# Recover boundary arguments through threading macros

Status: **deferred**. Extracted from
`fix-boundary-args-parsing-problem.md` §3.2, which was deferred out of
`fix-boundary-args-parsing-problem-progress.md` (§2 + §3.1 shipped; this is a
new feature, not a fix). Do not start until §3.1 data shows unresolved threaded
callsites matter in real annotations.

## 1. Problem

For the threaded shapes, the argument *is* in the source, just not inside the
boundary usage span, so `read-boundary-args` has nothing to return:

```clojure
(->> m (merge defaults) (->fact :t) insert!)
;; ≡ (insert! (->fact :t (merge defaults m)))
```

After §3.1, these usages no longer crash and no longer vanish: they emit a
placeholder `TracedArg` whose `:arg` is the usage's own symbol, which falls
through every resolver and lands as an unresolved callsite. The rule's
`:resolution` is honest (`:partial`/`:none`), and the UI shows the insert as
"unexplained" — but a direct `(insert! (->fact :t …))` written next to it would
resolve fully with a literal type. This plan upgrades the threaded cases from
unresolved to resolved.

Current behavior per shape (after §2 + §3.1):

| Shape | Example | Today |
|---|---|---|
| Value use | `(run! insert! facts)` | unresolved placeholder (correct — nothing static to recover) |
| Threaded step, bare | `(-> m (->fact :t) insert!)` | unresolved placeholder (recoverable — see below) |
| Threaded step, parenthesized | `(->> m (->fact :t) (insert!))` | unresolved placeholder (recoverable — `(insert!)` has empty `rest`) |
| Direct call | `(insert! x)` | resolved as before |

The existing fixture `rule-boundary-threaded-bare`
(`(-> m (->fact :t) insert!)`) lands as an unresolved `insert!` placeholder
today — that is the exact callsite this plan would later upgrade to resolved.
Its expectations must be updated when this ships.

## 2. Design

### 2.1 Find the enclosing threading form

kondo also reports `:var-usages` for `clojure.core/->` and `->>` with spans.
Pick the innermost one in the same `:from-var` whose span contains the boundary
usage's span.

`:arity 1` with a symbol-only span tells the threaded-bare case apart from a
value use: a value use has no `:arity` (see the table in the parent doc §1),
while `(-> m … insert!)` reports the boundary usage with `:arity 1`. The
parenthesized `(insert!)` shape is already a `seq?` with empty `rest` — same
handling once the enclosing form is found.

### 2.2 Rewrite rather than macroexpand

Read the threading form and thread it by hand up to and including the boundary
step: `->` makes the accumulated value each step's first argument, `->>`
makes it the last. `macroexpand` would work for `->`/`->>` themselves, but a
general `macroexpand-all` would also expand unrelated user macros in a live
namespace. A hand-rolled rewrite of just these two macros is small and has no
side effects.

Example: `(->> m (merge defaults) (->fact :t) insert!)` rewrites the boundary
step's argument to `(->fact :t (merge defaults m))`, which the existing
constructor-of-interest path then resolves exactly like a direct
`(insert! (->fact :t …))` — literal type, full resolution.

### 2.3 Trace locals against the threading form's span

`clara.explorer.analyze.callsite/trace-local-form` follows locals the threaded
steps refer to (e.g. `m`, `defaults`). It must run against the threading
form's span, not the boundary symbol's span, since the locals live in the
enclosing form. The recovered argument's kondo span / source location retargets
to the threading form for locals-tracing purposes.

### 2.4 Source text is synthesized

The recovered argument is a *synthesized* form. Its `:source-str` is the
printed rewrite, not text that appears in the file. Ids stay deterministic
(the rewrite of deterministic source is deterministic), but editor "jump to
argument" would land on the boundary symbol rather than on a matching text
span. Document this in the callsite's `:via` (e.g.
`:via {:source :threading-rewrite :macro ->>}`) so the UI can distinguish
verbatim from synthesized source.

## 3. Changes

| File | Change |
|---|---|
| `explorer/src/clara/explorer/analyze/kondo.clj` | find innermost enclosing `->`/`->>` usage span; read threading form; hand-rolled rewrite up to boundary step; return recovered arg form + threading-form span |
| `explorer/src/clara/explorer/analyze/callsite.clj` | `trace-boundary-args` (or its caller in `analyze.clj`) traces the recovered form against the threading-form span; labels synthesized callsites via `:via` |
| `explorer/test/clara/explorer/analyze_test.clj` | upgrade `rule-boundary-threaded-bare` expectation from unresolved placeholder to resolved; add `->>` + parenthesized `(insert!)` cases |
| `explorer/test/clara/explorer/test/rules/analyze_test_rules.clj` | threaded fixture rules if not already present |

## 4. Tests

- `(-> m (->fact :t) insert!)` resolves to the fact type with `:resolution :full`
  (same as direct `(insert! (->fact :t m))`).
- `(->> m (merge defaults) (->fact :t) insert!)` resolves through the rewrite,
  including a local (`defaults`) traced via the threading form's span.
- `(->> m (->fact :t) (insert!))` (parenthesized, empty `rest`) recovers the
  same way as the bare shape.
- Value uses (`(run! insert! facts)`, `(map insert! xs)`) still yield the §3.1
  unresolved placeholder — no enclosing threading form, no recovery attempted.
- Synthesized `:source-str` is deterministic across regenerations
  (byte-identical `git status test-resources/` after `make regen-fixture`).
- Existing `make test lint reflection-check format-check` green.

## 5. Consumer impact

- Regenerated layers change only where a threaded-bare boundary usage
  previously emitted an unresolved placeholder: that callsite becomes resolved
  (`:insert-types` gains the fact type, rule `:resolution` may move
  `:partial`/`:none` → `:full`).
- Curated overlay entries that patched a threaded insert become redundant;
  `clara.explorer.annotations.report`'s lint shows where the overlay now agrees
  with the generated layer.
- No callsite-id instability beyond the intended upgrade: verbatim callsites
  are untouched; synthesized ids are deterministic functions of the threading
  form's source.

## 6. Out of scope (deferred within deferred)

- `some->` / `some->>` / `cond->` / `as->`: expand through gensym'd `let`
  bindings with no kondo locals, so the traced argument would be an opaque
  local. Supporting them means modeling each macro's binding structure.
- Nested threading (`(-> x (->> (f y)) insert!)`): needs the rewrite applied
  recursively.
- `doto`: passes the *same* value to each step, so it needs its own rule.
- Value uses (`run!` / `map` / `partial`): nothing static to recover; the §3.1
  placeholder is the final behavior.

## 7. Acceptance

- The `rule-boundary-threaded-bare` fixture resolves fully with no overlay.
- `make test`, `make lint`, `make reflection-check`, `make format-check`
  green; `make regen-fixture` leaves `git status test-resources/` clean on a
  second run.
- Start condition (the reason this is deferred): §3.1 has made unresolved
  threaded callsites visible in real annotations, and they appear often enough
  that upgrading them moves aggregate `:resolution` — until then this stays an
  incremental gain, not a correctness fix.
