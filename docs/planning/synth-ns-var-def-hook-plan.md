# Synthesized-namespace var-definition hook

Status: **Plan — for review. Not yet implemented.**

Scope: `analyze.synth` / `analyze/analyze-session-rules` — a caller hook that supplies the
definition forms of non-production vars in rule-owning namespaces whose source is not on the
classpath.

---

## 1. Problem

`analyze-session-rules` synthesizes one source string per rule-owning namespace and hands it to
clj-kondo. When the namespace has real source on the classpath, that source is the base and the
synthesis is complete: every helper var the rules call has a body, so the call graph reaches
`clara.rules/insert!` through any depth of intermediate vars.

When the namespace has no classpath source, `synth/reconstruct-ns-source` rebuilds an `(ns …)` form
from the live `Namespace` object and `synthesize-ns-source` emits `(declare …)` for every
non-production intern. A `declare` has no body, so the analysis sees the helper var exist but sees
nothing it calls.

The consequence is not a partially-resolved callsite — it is no callsite at all. `var-reachability`
walks the call graph out of the rule's RHS, dead-ends one hop later at the declared helper, and
reports `:is-inserter? false`. `extract-insert-types` never runs, and the rule is annotated
`:clara-rules/no-output-types true` even though it inserts at runtime.

A rule whose RHS is:

```clj
(do (doseq [x xs] (parse-and-insert! x)))
```

where `parse-and-insert!` is a helper var in the same namespace that calls `insert!`, is
indistinguishable from a rule with no output. A sibling rule in the same namespace whose RHS writes
`(insert! (->fact :t v))` inline resolves normally, because that call is inside the synthesized
snippet region.

## 2. Why the analyzer cannot close this on its own

The bodies do not exist in any form the analyzer can reach. `ns-interns` yields `Var` objects whose
values are compiled functions; there is no source text, no `:file`/`:line` pointing at a readable
resource, and nothing on the classpath to slurp. Reconstructing a body from a compiled function is
not possible.

A host that loads rules by evaluating namespace source at runtime — from a durable rulebase, a rule
authoring system, a generated namespace — generally *does* still hold the definition forms, because
whatever mechanism interned those vars had them. Only that host knows where they are and what shape
they take. So the analyzer takes them as an input rather than discovering them.

This matches the existing extension points (`:callsite-resolver-fn`, `:fact-constructors`,
`:fact-type-spec-fn`): the analyzer owns what is derivable from the session and clj-kondo, and
defers host-specific knowledge to a caller fn.

## 3. The hook

A new `analyze-session-rules` option:

```
:ns-var-defs-fn  - optional (fn [ns-sym] -> nil | [VarDef …])
```

```clj
(s/defschema VarDef
  "One top-level definition to include in a synthesized namespace source.
   `:name` is the var's local symbol (unqualified, as interned in `ns-sym`).
   `:form` is the complete top-level form defining it — `s/Any` because it is
   unevaluated source data of arbitrary shape."
  {:name s/Symbol
   :form s/Any})
```

Called once per rule-owning namespace, only when that namespace has no classpath source. Returning
`nil` or an empty vector reproduces current behaviour exactly. Exceptions are contained the way
`:callsite-resolver-fn` exceptions are: logged, treated as "no var defs for this namespace".

`:form` is a whole top-level form, not a body the analyzer wraps — the caller decides between
`(def f (fn f [x] …))`, `(defn f [x] …)`, or anything else that defines `:name`. The analyzer never
evaluates it; it is printed as text for clj-kondo to read.

Usage:

```clj
(analyze/analyze-session-rules
 {:session-or-rulebase session
  :ns-var-defs-fn (fn [ns-sym]
                    (when-let [forms (my-loader/captured-defs ns-sym)]
                      (mapv (fn [[sym form]] {:name sym :form form}) forms)))})
```

## 4. Emission

`synthesize-ns-source` gains an opts map carrying `:var-defs-fn`, and the reconstructed-source path
becomes:

```
(ns …)                                   ; reconstruct-ns-source
(declare <every non-production intern>)  ; unchanged
(def helper-1 …)                         ; one accepted VarDef per line
(def helper-2 …)
                                         ; blank separator (unchanged)
(def __clara_explorer_rule_0__ (fn [] …))
```

Rules that hold:

- **`declare` stays, and covers everything.** It is emitted before the defs and lists every
  non-production intern, including ones the hook supplies. Forward references between helper bodies
  resolve regardless of the order the hook returns them in, and vars the hook does not supply keep
  today's behaviour. A `declare` followed by a `def` of the same symbol is valid Clojure; the
  analysis run consumes only `:analysis`, so any redefinition lint finding is irrelevant.

- **One line per form.** `synthesize-ns-source` counts lines to compute `:offset`, and
  `analyze.kondo` reads forms back out of the same text by clj-kondo row/col. Both break if a form
  spans lines. Forms print under `*print-length*`/`*print-level*` bound to `nil` (as rule snippets
  already do); `pr-str` escapes newlines inside string literals, so a readable form is always one
  line. A printed form that still contains a newline is skipped.

- **Round-trip validated.** A form containing an unreadable literal (an object with no reader
  representation) prints as text clj-kondo cannot parse, and one such form corrupts the analysis of
  the whole namespace. Each form is `read-string`-checked after printing; failures are skipped and
  reported via `tap>` with `:event :clara-rules/var-def-skipped` context, alongside the ns and var
  name.

- **Production names are rejected.** A `VarDef` whose `:name` matches a production in the namespace
  is dropped. `prune-and-rename-analysis` removes source-region (`row <= offset`) var-definitions and
  var-usages attributed to production vars, so such a form would be silently erased after analysis;
  dropping it up front keeps the source and the analysis consistent.

- **Defs are source region, not snippet region.** `:offset` is computed over ns form + declares +
  var defs, so the snippet region still starts at the first rule. Helper vars are not production
  vars, so pruning leaves their definitions and usages intact, and the call graph gains the
  `helper → insert!` edges the rules need.

## 5. What this unlocks downstream

Nothing else changes. Once the helper bodies are in the analyzed text:

- `index/build-analysis-index` records the helper as a direct inserter and adds its edges.
- `var-reachability` reports `:is-inserter? true` for rules that call it.
- `extract-insert-types` finds the boundary usages, and `callsite/trace-boundary-args` reads the
  argument forms out of the synthesized source through `::combined-sources` — which already takes
  precedence over classpath resources in `build-source-loader`, so positions resolve against the
  exact text clj-kondo saw.
- `:fact-constructors` and `:callsite-resolver-fn` are reached normally, and constructor callsites
  carry a `:via` callstack through the helper chain.

## 6. Plumbing

The option lives on `analyze-session-rules` and is threaded to `synth/synthesize-ns-source` at its
single call site. `generate-annotations-from-analysis` needs no change: it consumes
`::combined-sources` off the analysis map.

`server/start!`'s internal `:auto-detect` layer does not thread it, consistent with
`:fact-constructors` and `:callsite-resolver-fn`. Callers who need resolution hooks run
`analyze-session-rules` + `generate-annotations-from-analysis` themselves and pass the result to
`start!` as a `:source` layer.

## 7. Cost

clj-kondo work per namespace scales with the supplied text. A namespace contributing a few hundred
helper bodies costs roughly what analyzing its real source costs — which is the correct price, and
what the classpath-source path already pays. Namespaces the hook returns nothing for are unchanged.
The per-namespace analysis cache (`:cache-atom`) applies as before, keyed by ns symbol, so a session
swap that reuses the cache does not re-pay.

## 8. Work items

1. `VarDef` schema and `:var-defs-fn` support in `analyze.synth`: filter production-name collisions,
   print + round-trip validate, emit one line per form, extend `:offset`.
2. Convert `synthesize-ns-source` to take an opts map.
3. `:ns-var-defs-fn` option on `analyze-session-rules`, with exception containment, threaded to
   `synthesize-ns-source`.
4. `tap>` reporting for skipped var defs.
5. Docs: `analyze-session-rules` docstring, `analyze.synth` ns docstring,
   `docs/rule-annotations.md` where the extension points are described.

## 9. Tests

- `synthesize-ns-source` with a `:var-defs-fn`: defs land in the source region, `:offset` covers
  them, `:tag->production` is unaffected, and the snippet region is unchanged.
- Production-name collision is dropped from the emitted source.
- A form containing an unreadable literal is skipped and the rest of the namespace still analyzes.
- A form that prints across lines is skipped.
- No `:var-defs-fn`, and a `:var-defs-fn` returning `nil`, both produce byte-identical source to
  today.
- End-to-end on a session whose rule-owning namespace has no classpath source and whose RHS reaches
  `insert!` only through a helper var: without the hook the rule annotates
  `:clara-rules/no-output-types`; with it the rule reports `:clara-rules/insert-types` and dynamic
  callsites whose `:via` callstack names the helper.
- The same, with two hops of helper vars, confirming clj-kondo attributes `:from-var` correctly for
  a `(def f (fn f [] …))` form and the graph traverses both edges.
