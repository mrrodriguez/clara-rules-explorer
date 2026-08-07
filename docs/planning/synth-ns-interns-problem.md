# Synthesized namespace source omits the namespace's own interned vars

## Summary

`clara.server.tools.graph.analyze.synth/reconstruct-ns-source` rebuilds an
`(ns …)` form for namespaces with no source on the classpath. It reconstructs
everything that comes from *outside* the namespace — requires, aliases, refers,
imports, `:refer-clojure` deviations — and nothing that the namespace defines
itself.

So a rule namespace that interns its own helper vars produces synthetic source
in which those helpers are **undefined symbols**. clj-kondo cannot resolve them,
emits no callee for any call to them, and every downstream consumer that keys
on a resolved callee silently does nothing.

The `:fact-constructors` extension point is the visible casualty. Its `:match-fn`
is `(fn [fq-var-sym] -> truthy)` — it is only ever consulted when there *is* a
fully-qualified callee symbol. For a project whose rules build facts through its
own constructor, in a restored session, there never is one. The one case the
hook exists to serve is the case it cannot reach.

## Where this bites

Any namespace the analysis sees through `reconstruct-ns-source` rather than real
source:

- a rulebase restored from a serialized session — the namespaces are recreated
  by the loader, usually under generated names, and no file on the classpath
  declares them;
- rules defined by `eval` / `load-string`;
- rules shipped in a jar without sources.

Restored sessions are the common one, and they are also where this project adds
the most value: they carry the whole cross-project network, including rules
whose source nobody has checked out.

## What it looks like

A rule namespace that defines a constructor wrapper and uses it:

```clojure
(ns example.ruleset
  (:require [clara.rules :refer [defrule insert!]]
            [facts.model.core :as fm]))

(defn ->fact                      ; interned HERE, not referred
  [fact-type fact-data]
  (fm/->fact fact-type (scrub fact-data)))

(defrule alert-rule
  [:some/input [{:keys [id]}] (= ?id id)]
  =>
  (insert! (->fact :some/alert {:id ?id})))
```

After a serialize/restore cycle the namespace exists in the JVM, `->fact` is
interned in it, and `reconstruct-ns-source` emits only:

```clojure
(ns example.ruleset.<generated-suffix>
  (:require [clara.rules :refer [defrule insert!]]
            [facts.model.core :as fm]
            ...))
```

No `->fact`. The synthetic rule snippet that follows it —
`(def __clara_explorer_rule_0__ (fn [] (insert! (->fact :some/alert {:id ?id}))))`
— therefore calls an undefined symbol. The resulting annotation:

```clojure
{:clara-rules/dynamic-insert-types-detected
 {:callsites [{:source-str "(->fact :some/alert {:id ?id})"
               :ns-name-sym example.ruleset.<generated-suffix>
               :status :none
               :callsite-id "example.ruleset.<generated-suffix>:-:e1a4011e:0"}]
  :resolution :none}}
```

Note the empty constructor segment in `:callsite-id` (`:-:`) and the absent
`:constructor-sym`. The callsite is *detected* — the boundary-argument pass sees
an argument to `insert!` — but nothing is attributed to it, so no
`:fact-constructors` spec is ever offered the chance to claim it.

The same namespace's calls to `fm/->fact` resolve fine, because `fm` is an alias
the ns form does reconstruct. The failure is specific to vars the namespace
owns.

## Suggested direction

Emit declarations for the namespace's own interned vars, ahead of the rule
snippets, so clj-kondo resolves calls to them as `<ns>/<name>`:

```clojure
(declare ->fact scrub some-other-helper)
```

`declare` is enough — the analysis needs the callee *attributed*, not
type-checked or arity-checked. Points to settle:

1. **Which vars.** Everything in `ns-interns` is the simple answer. The rule and
   query vars are interned too; declaring them is harmless, but consider whether
   it interferes with how productions are located in the analysis.
2. **Ordering.** Declarations must precede the synthetic `(def
   __clara_explorer_rule_N__ …)` snippets, which means `synthesize-ns-source`
   composes them, not `reconstruct-ns-source` alone — the latter is documented
   as returning an `(ns …)` form and is used on its own elsewhere.
3. **Name collisions.** A var interned under a name the ns also excludes from
   `clojure.core` (`defn` is a real case) needs the declaration and the
   `:refer-clojure :exclude` clause to stay consistent.
4. **Arglists, optionally.** `(declare ^{:arglists '([fact-type fact-data])} ->fact)`
   would additionally let kondo arity-check the synthetic calls. Not required
   for callee attribution; worth it only if something downstream wants it.
5. **Only where source is missing.** When real source is on the classpath it
   already declares these vars, so the declarations belong to the reconstructed
   path only — emitting them in both would double-define.

## Verifying a fix

The condition cannot be reproduced by a session built from namespaces that can
be `require`d — those have source, so synth never runs. It needs a namespace
that exists in the JVM with no backing file, which is what a
serialize/deserialize cycle produces naturally.

A hermetic fixture: compile one or two small rulesets from **source strings**
under generated namespace names,
serialize the session, deserialize it straight back from the in-memory bytes,
and run the annotation pass over the result. No files, no artifacts to keep in
the repository, and fast enough for a unit test. Assert that a call to a
namespace-local constructor arrives with `:constructor-sym` set and its fact
type resolved — and, as a guard against the fixture silently degrading, that no
production namespace in it has a source file on the classpath.
