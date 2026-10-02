# Edge cases in argument-span detection

Status: **deferred** — problem-design statement captured for a later fix.
Runnable probes live in [`docs/planning/probes/`](probes/).

## 1. Summary

Two correctness gaps in the constructor-of-interest callsite ownership path
share one root cause: boundary-call arguments are read as **bare forms**, their
source positions discarded, and every downstream step scopes its work from the
**whole boundary-call span** rather than the argument's own span.

1. `clara.explorer.analyze.callsite/find-local-binding` resolves a symbol
   argument by the *first* matching local usage in the call span — wrong when
   the symbol is shadowed earlier in the same call.
2. `clara.explorer.analyze.callsite/find-owning-boundary-arg` (via
   `arg-span-set`) attributes constructors to the *first* argument of a
   multi-argument boundary call, because every argument shares one span set
   seeded from the same boundary usage.

Both are confirmed by the probes in `docs/planning/probes/`:

- `find-local-binding-shadowing-probe.clj`
- `multi-arg-ownership-probe.clj`

## 2. Root cause

`clara.explorer.analyze.kondo/read-boundary-args` reads the boundary call text
and returns `(rest form)` — the argument forms as data, with no `[row col]`
positions. `trace-boundary-args` then hands `trace-local-form` the whole
boundary usage span (`usage->span`), and `arg-span-set` seeds from the same
`:usage`. The argument's own source region is never captured, so nothing
downstream can scope local-binding lookup or span-set expansion to one
argument.

One enabling change fixes both: **capture each boundary argument's source span
when reading the call.**

## 3. Problem 1 — `find-local-binding` order dependence

`find-local-binding` is given a symbol and a span and returns the binding of the
*first* local usage of that symbol inside the span. On the first hop the span
is the entire boundary call, so when the same symbol is shadowed earlier in the
call, the first usage belongs to the wrong binding.

Probe source (abridged):

```clojure
(let [x (->fact :outer)]
  (insert! (let [x (->fact :inner)] x)   ; inner (shadowing) binding, id 2
           x))                            ; outer argument, id 1
```

Observed (`find-local-binding-shadowing-probe.clj`):

```
boundary usage span: {:start [6 5] :end [7 16]}
x :locals bindings:   id=1 at [5 9]   (outer)
                      id=2 at [6 20]  (inner)
x :local-usages in span (source order):
                      id=2 at [6 39]  (inner body)
                      id=1 at [7 14]  (outer argument)
find-local-binding -> id=2 (inner)     ; wrong for the outer argument
last-in-span would -> id=1 (outer)     ; right here, wrong in the mirror
```

Neither `first` nor `last` is universally correct. The correct binding for an
argument at position `P` is the one whose `:id` appears on the usage at `P`;
`find-local-binding` has no position to key on.

## 4. Problem 2 — multi-argument ownership collapse

`arg-span-set` destructures only `:usage` from its traced-arg and seeds its
fixpoint from the boundary usage span, so every argument of the same boundary
call computes an identical span set (and identical `:var-syms`). Consequently
`find-owning-boundary-arg`'s `some` returns the first argument for every
constructor reachable from that call — including constructors reached only
through a *different* argument.

Probe source (abridged):

```clojure
(defn f1 [m] (->fact :a m))
(defn f2 [m] (->fact :b m))

(defn r [m]
  (insert! (f1 m) (f2 m)))
```

Observed (`multi-arg-ownership-probe.clj`):

```
arg-span-set idx=0 and idx=1 are identical:
  var-syms = #{clara.rules/insert! probe.helpers/f1 probe.helpers/f2}

ctor-0 (in f1): arg 0 reaches? true   arg 1 reaches? true
                find-owning-boundary-arg -> idx 0
ctor-1 (in f2): arg 0 reaches? true   arg 1 reaches? true
                find-owning-boundary-arg -> idx 0
```

Effect on output: each constructor usage still emits its own resolved callsite
with a correct `:via`, so the rule's fact-type set is still right. But arg 1 is
not marked `:owned`, falls through to `resolve-boundary-callsites`, and
typically lands as an extra `:none` callsite — dragging the dimension's
`:resolution` toward `:partial` for an insert that was actually fully resolved.

## 5. Design direction

The single enabling change is to retain per-argument source spans:

- `clara.explorer.analyze.kondo/read-boundary-args` (or a sibling) reads the
  call form-by-form with position tracking — reusing the
  `read-one-form-char-count` / `advance-pos-by-count` machinery already in that
  namespace — and returns each argument form together with its start/end
  `[row col]` span.
- `trace-boundary-args` stores that span on each `TracedArg`
  (e.g. `:arg-span`), falling back to the whole boundary span for placeholder
  arguments (value uses, bare threaded steps) that have no source text.
- `trace-local-form` resolves against `:arg-span`; `find-local-binding` matches
  the usage *at that position* rather than the first usage in the call span.
- `arg-span-set` seeds from `:arg-span` instead of `:usage`, making span sets
  per-argument; `find-owning-boundary-arg` then attributes constructors to the
  argument that actually contains them.

Files touched by the eventual fix:

| File | Change |
|---|---|
| `explorer/src/clara/explorer/analyze/kondo.clj` | per-argument span capture in boundary-arg reading |
| `explorer/src/clara/explorer/analyze/callsite.clj` | `TracedArg` gains `:arg-span`; `trace-boundary-args`, `trace-local-form`, `find-local-binding`, `arg-span-set` seed/scope from it |
| `explorer/src/clara/explorer/analyze.clj` | pass-through (no logic change expected) |
| `explorer/test/clara/explorer/analyze_test.clj` | shadowed-arg + multi-arg ownership expectations |

Interaction with
[`recover-args-from-thread-macros-plan.md`](recover-args-from-thread-macros-plan.md)
§2.3: a threaded step recovered by rewrite has no verbatim argument span; it
would need a synthesized span (like its synthesized `:source-str`) so the new
per-argument scoping still applies.

## 6. Verification

- Both probes flip to the correct answer once per-argument spans exist:
  - shadowing probe: the outer argument resolves to `id 1`;
  - ownership probe: `ctor-0` → `idx 0`, `ctor-1` → `idx 1`, and each
    constructor's owning argument is the one that called its helper.
- New `analyze-test` cases:
  - a rule with an in-call shadowing `let` resolves the argument to the
    *outer* binding's type;
  - a two-argument boundary call where each argument reaches a different
    constructor emits two `:full` callsites (no spurious `:none`), with
    `:resolution :full`.
- Existing `make test lint reflection-check format-check` stay green; the
  regenerated annotation fixture is byte-identical where inputs are unchanged.

## 7. Open questions

- Exact reading strategy for per-argument spans (track the reader's char
  offset per form vs. post-hoc offset math over the call string).
- Placeholder arguments (value use / bare threaded step): keep the whole-call
  span, or synthesize a span from the rewrite?
- Callsite-id stability: `assign-callsite-ids` keys on entry content, not the
  argument span, so ids should be unaffected — confirm during implementation.
