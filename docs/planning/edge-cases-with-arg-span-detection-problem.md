# Edge cases in argument-span detection

Status: **deferred** — one enabling change (per-argument source spans) still
outstanding. Problem 2 is fully open; Problem 1's original *mis-attribution* has
since been replaced by a conservative refusal, so only its "resolve the argument
correctly" half remains. Runnable probes live in
[`docs/planning/probes/`](probes/) and are kept in sync with the current code.

## 1. Summary

Two correctness gaps in the constructor-of-interest callsite ownership path
share one root cause: boundary-call arguments are read as **bare forms**, their
source positions discarded, and every downstream step scopes its work from the
**whole boundary-call span** rather than the argument's own span.

1. **Shadowed in-call names are refused, not resolved.** When a name is bound
   twice inside one boundary call, the argument that unambiguously names the
   *outer* binding cannot be resolved at all: the whole-call span makes the
   name ambiguous, so `:resolve-local` answers
   `{:kind :unknown :reason :shadowed}`. The plan originally recorded the
   opposite defect (resolving to the *inner* binding); that function is gone,
   but the argument still does not resolve to the right type.
2. **Multi-argument ownership collapses to the first argument.**
   `clara.explorer.analyze.callsite/find-owning-boundary-arg` (via
   `arg-span-set`) attributes every constructor reachable from a multi-argument
   boundary call to its *first* argument, because all arguments share one span
   set seeded from the same boundary usage.

Both are confirmed by the probes in `docs/planning/probes/`:

- `find-local-binding-shadowing-probe.clj` — whole-call lookup yields two local
  ids (refused); an argument-scoped lookup yields exactly the outer id.
- `multi-arg-ownership-probe.clj` — identical span sets for both arguments;
  both constructors attribute to `idx 0`.

## 2. Root cause

`clara.explorer.analyze.kondo/read-boundary-args` reads the boundary call text
and returns `(rest form)` — the argument forms as data, with no `[row col]`
positions. `clara.explorer.analyze.callsite/trace-boundary-args` then hands
`trace-local-form` the whole boundary usage span (`usage->span`), and
`arg-span-set` seeds its fixpoint from the arg's `:usage`. The argument's own
source region is never captured, so nothing downstream can scope local-binding
lookup or span-set expansion to one argument.

One enabling change fixes both: **capture each boundary argument's source span
when reading the call.**

Nothing in the current code retains per-argument positions:

| Piece | Current state |
| --- | --- |
| `kondo/read-boundary-args` | returns `(rest form)` — forms only |
| `callsite/TracedArg` | `{:idx :usage :arg :alias-context :traced :traced-span}` — no `:arg-span` |
| `callsite/trace-boundary-args` | passes `(usage->span usage)` to `trace-local-form` for every arg |
| `callsite/arg-span-set` | destructures only `:usage`; seeds from `(usage->span usage)` |

## 3. Problem 1 — shadowed names are refused

The relevant lookup is now `find-local-binding-ids` (returns the *set* of kondo
local ids a name resolves to inside a span) plus `find-unique-local-binding`
(returns the binding only when that set has one element).
`->scoped-resolver` — the `:resolve-local` implementation — turns more than one
id into `{:kind :unknown :reason :shadowed}`. The behaviour is deliberate and
pinned by `clara.explorer.analyze-test/test-resolve-local-boundary-context`
plus the fixture
`clara.explorer.test.rules.analyze-test-rules/rule-resolve-local-shadowed`.

That replaced the older, worse failure: `find-local-binding` used to return the
*first* local usage in the span, so an argument naming the outer binding
resolved to the shadowing inner binding. Refusing was the right stopgap (a wrong
type is worse than no type), not the fix.

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

whole-call span lookup (today's scoping):
  find-local-binding-ids -> #{1 2}
  find-unique-local-binding -> nil
  => refused (`:shadowed`)

argument-scoped span for the outer `x` (the proposed fix):
  find-local-binding-ids -> #{1}
  find-unique-local-binding -> id 1 at [5 9]  (outer)
```

End-to-end consequence today: arg 1 — the one naming the outer binding — is
refused, so it cannot contribute its fact type. With no constructor spec
matching `->fact` (the probe's setup) both arguments emit `:none` and the
dimension's `:resolution` is `:none`; with `->fact` configured as a constructor
of interest, the ctor path still owns arg 0 (its traced form is
`(->fact :inner)`), arg 1 falls through as `:none`, and the dimension lands on
`:partial`. Either way the outer binding's `:outer` type is lost.

Note the scope of the fix: `:shadowed` remains correct for a *genuinely*
ambiguous argument. `rule-resolve-local-shadowed` passes the outer symbol
`shadowed-type` and an inner `(let [shadowed-type …] …)` inside **one**
argument, so even per-argument spans cannot separate them — that case must keep
reporting `:shadowed`. Per-argument spans only disambiguate the case where the
name refers to a single binding *within the argument's own region*.

## 4. Problem 2 — multi-argument ownership collapse

`arg-span-set` destructures only `:usage` from its traced arg and seeds its
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
  spans    = [{:start [8 3] :end [8 26]}]        (the whole insert! call)
  var-syms = #{clara.rules/insert! probe.helpers/f1 probe.helpers/f2}

ctor-0 (in f1): arg 0 reaches? true   arg 1 reaches? true
                find-owning-boundary-arg -> idx 0
ctor-1 (in f2): arg 0 reaches? true   arg 1 reaches? true
                find-owning-boundary-arg -> idx 0

owned arg idxs: #{0}
left for the boundary pass: [[1 (f2 m)]]
boundary-pass callsite statuses: [:none]
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
- `trace-boundary-args` stores that span on each `TracedArg` (e.g.
  `:arg-span`), falling back to the whole boundary span for placeholder
  arguments (value uses, bare threaded steps) that have no source text.
- `trace-local-form` starts from `:arg-span` rather than the boundary span.
  Because the returned `:span` becomes the arg's `:traced-span`, this also
  scopes the `:resolve-local` hook handed to resolvers to the argument
  (`->callsite-resolver-context` derives `:resolve-local` from `traced-span`);
  no separate change is needed for resolver scoping.
- `arg-span-set` seeds from `:arg-span` instead of `:usage`, making span sets
  per-argument; `find-owning-boundary-arg` then attributes constructors to the
  argument that actually contains them.
- `find-local-binding-ids` / `find-unique-local-binding` need no change — they
  are already span-parameterized; only the span they receive changes.

Files touched by the eventual fix:

| File | Change |
|---|---|
| `explorer/src/clara/explorer/analyze/kondo.clj` | per-argument span capture in boundary-arg reading |
| `explorer/src/clara/explorer/analyze/callsite.clj` | `TracedArg` gains `:arg-span`; `trace-boundary-args`, `trace-local-form`, `arg-span-set` seed/scope from it |
| `explorer/src/clara/explorer/analyze.clj` | pass-through (no logic change expected) |
| `explorer/test/clara/explorer/analyze_test.clj` + `.../test/rules/analyze_test_rules.clj` | shadowed-arg + multi-arg ownership expectations |
| `docs/planning/probes/*.clj` | re-run; the shadowing probe's argument-scoped section should become the observed behaviour |

Interaction with
[`recover-args-from-thread-macros-plan.md`](recover-args-from-thread-macros-plan.md)
§2.3: a threaded step recovered by rewrite has no verbatim argument span; it
would need a synthesized span (like its synthesized `:source-str`) so the new
per-argument scoping still applies.

## 6. Verification

- Both probes flip to the correct answer once per-argument spans exist:
  - shadowing probe: the outer argument resolves to `id 1` (outer) and emits a
    `:full` callsite instead of `:none`;
  - ownership probe: `ctor-0` → `idx 0`, `ctor-1` → `idx 1`, nothing is left
    for the boundary pass, and each constructor's owning argument is the one
    that called its helper.
- New `analyze-test` cases:
  - a rule with an in-call shadowing `let` where the outer argument is *only*
    the outer symbol (as in the probe) resolves to the outer binding's type and
    the dimension reaches `:resolution :full`;
  - `rule-resolve-local-shadowed` keeps reporting `:shadowed` — its two usages
    sit in one argument, so ambiguity there is genuine;
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
- Callsite-id stability:
  `clara.explorer.annotations.callsite/assign-callsite-ids` keys on entry
  content, not the argument span, so ids should be unaffected — confirm during
  implementation.
- Should a genuinely ambiguous argument stay a hard `:shadowed` refusal, or
  should an ambiguous *span* degrade to `:unknown` without a reason? Current
  behaviour (hard refusal, tested) should be preserved unless there is a
  concrete reason to change it.
