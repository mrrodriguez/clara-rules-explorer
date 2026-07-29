# Defect: spurious record-constructor types outrank constructor-of-interest resolution

_Filed 2026-07-28 against `2e1e716`. Status: **open**, not fixed. Found while integrating a
downstream consumer that registers `:fact-constructors` hooks for a runtime fact builder._

## Summary

`extract-insert-types` treats the record/Java-constructor scan as **authoritative when non-empty**
and short-circuits the entire dynamic path on it. That scan is a *speculative, name-shape-based
guess over a whole reachable subtree*; the constructor-of-interest path it suppresses is an
*explicit, caller-registered, provenance-carrying resolution*. Precedence is therefore inverted:
the weakest evidence silently overrides the strongest.

When the guess produces a false positive, the affected rule does not merely gain a junk type — it
**loses its real `:insert-types` entirely** and its `:clara-rules/dynamic-insert-types-detected`
record disappears, so nothing in the output signals that resolution was skipped. The annotation
looks confidently, cleanly wrong.

## Symptom

A rule whose RHS is a plain, directly-resolvable constructor-of-interest call:

```clojure
(defrule some-condition
  ...
  => (insert! (->fact :some.ns/some-condition {...})))   ; ->fact = registered :fact-constructors hook
```

Correct annotation (what the constructor path produces when it is allowed to run):

```edn
#:clara-rules{:insert-types [:some.ns/some-condition]
              :dynamic-insert-types-detected
              {:callsites [{:constructor-sym some.lib/->fact
                            :status :resolved
                            :resolved-types [:some.ns/some-condition]
                            :via {:boundary-var-name-sym clara.rules/insert!
                                  :callstack [{:var-name-sym …/some-condition}
                                              {:var-name-sym some.lib/->fact}]}}]
               :resolution :full}}
```

Observed instead, because an unrelated third-party record constructor is reachable somewhere in the
rule's subtree:

```edn
#:clara-rules{:insert-types [malli.core.Tag malli.core.Tags]}
```

No callsite record, no `:resolution`, no trace of the real type. The rule's genuine output type is
gone from the graph, and two types it never inserts are now in it.

## Mechanism

Three layers compose into the failure.

### 1. The candidate scan is name-shape based

`server/src/clara/server/tools/graph/analyze/ctor.clj:11` — `constructor-fn-name?` accepts *any*
callee whose name starts with `->` or `map->`. `resolve-record-type` (`ctor.clj:23`) then confirms
the candidate only by checking that a same-named class **loads**. Loading is a weak filter: it
confirms the symbol names a record ctor, not that the record is a fact.

### 2. The scan is subtree-wide and argument-blind

`server/src/clara/server/tools/graph/analyze/index.clj:157` — `build-inserter-type-map` unions the
resolvable record ctors found across a direct inserter's **entire transitive reachable subtree**:

```clojure
(let [subtree (reachable-set v)
      types (into #{} (comp (mapcat #(get usages-by-caller %))
                            (filter #(-> % :name name ctor/constructor-fn-name?))
                            (keep #(resolve-record-type (:to %) (:name %))))
                  subtree)] ...)
```

Its own docstring concedes the over-approximation: clj-kondo's flat `:var-usages` cannot tell an
argument expression apart from an independent call in the same body, so "a var's reachable subtree
may include constructors from unrelated RHS branches." Nothing here checks that the constructed
value ever flows to the boundary call.

`analyze.clj:136` — `extract-constructor-types-from-reachable` then unions those sets again across
**every** inserter var reachable from the rule. Two dilution steps, no data-flow check.

The practical reach is much wider than "the rule's own code": one validation/coercion helper deep in
a shared utility namespace is enough. In the observed case the chain was
`rule → domain helper → validation helper → malli.core/->Tag`, where `Tag` is an internal
parse-result record of a schema library that the rule never inserts and never sees.

### 3. Precedence: any hit short-circuits everything

`server/src/clara/server/tools/graph/analyze.clj:159` —

```clojure
(let [static-types (extract-constructor-types-from-reachable reachable inserter-type-map)]
  (if (seq static-types)
    {:static-types static-types :resolved-types #{} :dynamic-forms nil}   ; ← hard exit
    ,,, ; constructor-of-interest resolution, locals tracing, :callsite-resolver-fn
    ))
```

One resolvable `->X` anywhere in the subtree discards, unexamined:

- the caller-registered `:fact-constructors` match + `:type-resolver-fn` resolution,
- boundary-argument locals tracing,
- the optional `:callsite-resolver-fn`,
- and the `:callsites` / `:resolution` record that would otherwise document what happened.

The docstring's stated rule — *"statically-traceable record constructors win when present"* — is the
defect, stated as intent. It is a reasonable default only under the assumption that a reachable
record ctor is evidence about the boundary argument. It is not; it is evidence that *some record is
constructed somewhere downstream*.

This also inverts the guarantee the constructor-of-interest feature advertises. `extract-insert-types`
is careful, further down, to let the constructor path run *first* against boundary args and to mark
which args it "owns" so `:callsite-resolver-fn` cannot double-report them — precedence is modelled
deliberately there. The record-ctor scan bypasses that whole design by exiting before any of it runs.

## Why it is not caught by existing mitigations

- **`:clara-rules/no-output-types`** (the escape hatch named in `build-inserter-type-map`'s
  docstring) suppresses a rule that produces *nothing*. It cannot express "these two of my types are
  junk, the third is real," and here the real type is not in the output to keep.
- **`:exclude-ns-prefixes`** does not help. It controls which namespaces are *linted*; the offending
  edge (`helper → ->Tag`) lives in the analysis of the **calling** namespace, and
  `resolve-record-type` resolves the class through the **live runtime**, not through kondo data.
  Excluding the record's own namespace changes nothing.
- **Registering `:fact-constructors`** does not help, which is the sharpest point: the downstream
  consumer did exactly what the extension API asks for, and the resolution is discarded before the
  hook is consulted.

## Evidence

Measured on a real ruleset (3367 rules) with `:fact-constructors` hooks registered for a runtime
fact builder:

| | rules with a `malli.*` type in `:insert-types` | of those, **only** `malli.*` types |
|---|---|---|
| before a call-graph-widening config fix | 36 | 36 |
| after | 50 | 47 |

Two things to read here:

1. **36 rules were already fully mis-annotated before any change** — every one of them had its real
   types displaced, none had a mixed result. The defect is long-standing and silent, not an artifact
   of the change that surfaced it.
2. Widening the call graph (making previously-invisible macro-defined helper fns resolvable, which
   is otherwise a strict improvement) added 14 more. **9 of those demonstrably had correct,
   `:resolution :full` annotations immediately before** and regressed to `malli.*`-only. Improving
   graph completeness makes this defect worse, monotonically — every future fidelity gain in the
   call graph increases the blast radius.

Representative regression: a condition rule whose RHS is a single direct
`(insert! (->fact :calculations.conditions/paystub-monthly-variable-income {...}))` went from a
`:resolution :full` callsite carrying the correct keyword to
`:insert-types [malli.core.Tag malli.core.Tags]` with no callsite record at all.

## Impact

- **Silent.** A displaced type is indistinguishable in the output from a correctly resolved one.
  There is no `:resolution`, no `:status`, no note. Consumers reading `:insert-types` have no signal.
- **Graph-corrupting.** The rule is unlinked from its real downstream consumers and linked to
  fictitious `malli.core.Tag` producers/consumers, so fact-flow tracing silently loses edges.
- **Anti-correlated with progress.** The better the call graph gets, the more rules it hits.
- **Defeats the documented extension contract**, which is the reason a downstream consumer registers
  constructor hooks in the first place.

## Fix options

Ranked by directness. (1) is the minimal change that resolves the reported defect.

1. **Invert the precedence.** Run the constructor-of-interest path first; fall back to the
   record-ctor scan only for boundary arguments the constructor path did not own — reusing the
   `:owned-arg-idxs` mechanism already present below the short-circuit. Explicit registration should
   never lose to an unregistered heuristic.
2. **Stop short-circuiting; union and label.** Emit record-ctor-derived types as callsites with a
   distinguishable status (e.g. `:status :heuristic`, or `:via {:source :record-ctor-scan}`) so they
   are visible and filterable rather than silently authoritative. Preserves today's recall, removes
   the silence.
3. **Constrain the scan to the boundary argument.** Only credit a record ctor that appears in — or
   is traced into — the boundary call's argument form, the same locals tracing the dynamic path
   already performs. Removes the false positive at its source; the largest change.
4. **Give callers a veto.** A `:record-ctor-exclude-fn` / ns-prefix predicate consulted by
   `build-inserter-type-map`. Cheap and unblocks consumers immediately, but it is a workaround: it
   asks every caller to enumerate third-party record namespaces they have never heard of.

(1) and (2) compose and are independent of (3).

## Reproduction sketch

```clojure
(defrecord Unrelated [x])                       ; stands in for a 3rd-party lib record

(defn helper [m]
  (when (:validate? m) (->Unrelated 1))         ; unrelated branch, never inserted
  (->fact :my/type m))                          ; registered constructor of interest

(defrule r [:some/trigger [{:keys [m]}] (= m ?m)] => (insert! (helper ?m)))
```

Expected `:insert-types` `[:my/type]` with a `:resolution :full` callsite; actual
`[user.Unrelated]` with no callsite record.
