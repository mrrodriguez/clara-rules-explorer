# Callsite provenance: what `:via` does not say

Status: **Problem statement — for review. Not yet implemented.**

Scope: `analyze.callsite` / `analyze/extract-insert-types` — the `:via` chain on the callsites of
`:clara-rules/dynamic-insert-types-detected`.

---

## 1. What `:via` is for

A dynamic-insert callsite names a constructor call the analysis found somewhere in the subtree a
rule can reach, not something written in the rule's RHS. `:via` is the answer to "why is this
callsite attributed to this rule" — without it, a consumer reading an annotation has a
`:source-str` and no way to find the code it came from, and a curator has no way to judge whether
the attribution is right before recording a type against it.

Today it answers that question for some callsites and not others, and the line between them is not
where a reader would expect it.

## 2. The example

```clojure
(ns example.rules
  (:require [clara.rules :refer [defrule insert! insert-all!]]
            [example.facts :refer [->fact]]))     ; registered via :fact-constructors

(defn build-facts [id]
  [(->fact :example/one {:id id})
   (->fact :example/two {:id id})])

(defn insert-summary! [id]                        ; the boundary call lives here
  (insert! (->fact :example/summary {:id id})))

(defn record-summary! [id]                        ; one hop above it
  (insert-summary! id))

(defn insert-config-fact! [path fact-type]        ; the type is a PARAMETER
  (when-let [value (lookup path)]
    (insert! (->fact fact-type {:value value}))))

(defrule inline-rule
  [:example/input [{:keys [id]}] (= ?id id)]
  =>
  (insert! (->fact :example/direct {:id ?id})))

(defrule helper-arg-rule                          ; boundary in the RHS, ctor in a helper
  [:example/input [{:keys [id]}] (= ?id id)]
  =>
  (insert-all! (build-facts ?id)))

(defrule two-hop-rule                             ; boundary inside a helper, two hops out
  [:example/input [{:keys [id]}] (= ?id id)]
  =>
  (record-summary! ?id))

(defrule config-rule                              ; boundary inside a helper, type unresolvable
  [:example/input [{:keys [id]}] (= ?id id)]
  =>
  (insert-config-fact! "/some/path" :example/from-config))
```

`inline-rule` and `helper-arg-rule` are the cases `:via` handles well. The other two are the gaps.

## 3. Gap A — provenance is discarded when the constructor is unresolved

`config-rule` reports:

```edn
{:source-str "(->fact fact-type {:value value})"
 :ns-name-sym example.rules
 :filename    "example/rules.clj"
 :status      :none}
```

No `:constructor-sym`, no `:via`, nothing that says the call is written in `insert-config-fact!` or
that the rule reaches it at all. The reader is left with a source string that appears in no rule
RHS in the codebase.

The provenance exists and is thrown away. `resolve-ctor-callsite` builds the entry for this
callsite with

```edn
:constructor-sym example.facts/->fact
:via {:boundary-var-name-sym clara.rules/insert!
      :callstack [{:var-name-sym example.rules/insert-config-fact!}
                  {:var-name-sym example.facts/->fact}]}
```

and then `resolve-ctor-usage-for-inserter` drops the whole entry, because it ends with

```clojure
(when (not= :none (:status entry))
  [(:idx owner) entry])
```

The argument then falls through to `resolve-boundary-callsites`, which re-derives an entry from the
traced argument alone and emits neither `:constructor-sym` nor `:via`.

Dropping is deliberate and stays: the boundary path may still resolve the argument by another route
(locals tracing, record types, `:callsite-resolver-fn`), and emitting both entries would report one
insert twice. What is not deliberate is that the fallback path emits *less provenance than it
already has in hand*. Nothing about this is specific to unresolved types either — a boundary
argument that never matched a constructor at all (`(insert-all! facts)`, where `facts` is a
parameter) is emitted by the same path with the same silence.

## 4. Gap B — the callstack starts at the boundary call, never at the rule

`two-hop-rule` reports:

```edn
{:source-str "(->fact :example/summary {:id id})"
 :constructor-sym example.facts/->fact
 :status :full :resolved-types [:example/summary]
 :via {:boundary-var-name-sym clara.rules/insert!
       :callstack [{:var-name-sym example.rules/insert-summary!}
                   {:var-name-sym example.facts/->fact}]}}
```

`record-summary!` — the only var `two-hop-rule`'s RHS actually names — is absent, and so is the rule.
`ctor-call-path` BFSes from `inserter-var`, the var the boundary call is written in, so `:callstack`
covers boundary-call → constructor and nothing above it. When the boundary call is written in the
RHS (`inline-rule`, `helper-arg-rule`) the first entry happens to be the rule and the chain reads
whole; when it is written in a helper, every hop between the rule and that helper is missing.

The hops are not unknowable. `extract-insert-types` attributes the callsite to the rule *because*
the boundary-holding var is in the rule's `var-reachability` `:reachable` set, and the same call
graph that set is computed from holds the edges. What is missing is that nothing walks them.

The reconstruction is honest but approximate, and equally so at both ends: clj-kondo reports
var-level edges (`:from-var` → `:to`/`:name`), not per-callsite call paths, so any chain is a
shortest path through the call graph rather than the path taken at runtime. `:callstack` is already
a `shortest-call-path` result and carries that caveat today.

## 5. Expected outcome

Two additions to `ViaChain`:

- **`:boundary-in-var`** — the var the boundary call is written in (the docs' "boundary's direct
  caller"), `(u/fq-sym (:from usage) (:from-var usage))`. Exact, needs no graph walk, and is
  available to both passes for every callsite. It is what `(first :callstack)` already implies —
  stated as its own key because it is the anchor `:rule-path` joins onto, and because the
  callsites that need it most are exactly the ones with no `:callstack` at all.
- **`:rule-path`** — `[ViaEntry …]` from the rule var to `:boundary-in-var`, both ends inclusive,
  from a `shortest-call-path` BFS. Omitted when the two are the same var, so a boundary call in an
  RHS reads exactly as it does today.

`:callstack` keeps its meaning — boundary-holding var → … → constructor — rather than absorbing the
rule-side hops, so its invariant (first entry is the var holding the boundary call) survives and
`:rule-path`'s last entry joins onto it.

`config-rule`, gap A closed:

```edn
{:source-str "(->fact fact-type {:value value})"
 :ns-name-sym example.rules
 :filename    "example/rules.clj"
 :constructor-sym example.facts/->fact
 :status :none
 :via {:boundary-var-name-sym clara.rules/insert!
       :boundary-in-var       example.rules/insert-config-fact!
       :rule-path [{:var-name-sym example.rules/config-rule}
                   {:var-name-sym example.rules/insert-config-fact!}]
       :callstack [{:var-name-sym example.rules/insert-config-fact!}
                   {:var-name-sym example.facts/->fact}]}}
```

Still `:none` — the type is a parameter and no pass propagates a caller's argument into a callee's
parameter. That is the correct answer; what changes is that the callsite now says where it is and
how the rule reaches it, which is what makes it curatable.

`two-hop-rule`, gap B closed:

```edn
:via {:boundary-var-name-sym clara.rules/insert!
      :boundary-in-var       example.rules/insert-summary!
      :rule-path [{:var-name-sym example.rules/two-hop-rule}
                  {:var-name-sym example.rules/record-summary!}
                  {:var-name-sym example.rules/insert-summary!}]
      :callstack [{:var-name-sym example.rules/insert-summary!}
                  {:var-name-sym example.facts/->fact}]}
```

`inline-rule` and `helper-arg-rule` gain `:boundary-in-var` (the rule var) and no `:rule-path`,
their `:callstack` unchanged.

## 6. Suggested fix

**Gap A.** `resolve-boundary-callsites` builds `:via` for every entry it emits from the boundary
`usage` it already has — `:boundary-var-name-sym`, `:boundary-in-var`, `:rule-path`. This needs
nothing from the constructor pass, so it also covers arguments that never matched a constructor.

Additionally, `resolve-constructor-callsites` returns the provenance of the entries it drops, keyed
by owned argument `:idx` (alongside `:owned-arg-idxs`, which stays as it is), and the boundary entry
for that argument merges the dropped `:constructor-sym` and `:callstack`. When more than one dropped
constructor owns one argument the constructor identity is ambiguous: emit only the unambiguous
boundary-side keys for it and no `:constructor-sym`/`:callstack`.

**Gap B.** `:rule-path` is computed once per (rule, boundary-in-var) pair — memoized, since a rule's
callsites cluster in a few vars — from the call graph already in `ctx`. `extract-insert-types` is
where the rule var name enters, so it threads it into `ctx` for both passes; `shortest-call-path`
becomes shared rather than private to the constructor pass.

**Schema and serialization.** `ViaChain` (internal, `analyze.callsite`) and its serialized
counterpart (`clara.server.graph.api/ViaChain`) both gain the two optional keys, `serialize`
stringifies the new symbols the way it stringifies `:callstack`, and `annotations.rebase` remaps
them alongside the existing `:via` handling. The schema docstring states that `:rule-path` and
`:callstack` are shortest paths through a var-level call graph, not observed call paths.

## 7. Consequences

Attaching `:constructor-sym` to a previously bare boundary entry changes its `callsite-id`:
`callsite-basis` is `[ns-name-sym constructor-sym source-str]`, and the id renders a missing
constructor as `-`. Curated overlay entries recorded against those ids are quarantined as stale and
regenerated. `:via` itself is deliberately outside the basis, so gap B alone churns nothing.

Consumers that group callsites by `:constructor-sym` see unresolved constructor calls move out of
the "no constructor" bucket and into a named one — which is the point: "an unresolvable call to
`->fact`" and "an insert with no constructor call to read" are different problems and are worth
counting separately.

## 8. Work items

1. `:boundary-in-var` on every `:via`, both passes.
2. Shared `shortest-call-path` + memoized `:rule-path`, threaded from `extract-insert-types`.
3. Dropped-constructor provenance carried into the boundary entry, with the ambiguity rule.
4. `ViaChain` schemas (internal + API), `serialize`, `annotations.rebase`.
5. Docs: `server/docs/rule-annotations.md` where the callsite shape and `:via` are described, and
   `server/docs/analyze-pipeline-concepts.md` where the two passes are.

## 9. Tests

- A rule whose boundary call is in its RHS: `:boundary-in-var` is the rule var, no `:rule-path`,
  `:callstack` byte-identical to today.
- A rule two hops above the boundary call: `:rule-path` names both intermediate vars in order and
  ends at `:boundary-in-var`.
- A constructor the resolver cannot type, inside a helper: one callsite, `:status :none`, carrying
  `:constructor-sym` and the full `:via`, and not reported twice.
- A boundary argument that matches no constructor at all: `:via` with no `:callstack` and no
  `:constructor-sym`, and a `:rule-path` when the boundary call is not in the RHS.
- Two dropped constructors owning one argument: boundary-side keys only, no `:constructor-sym`.
- A rule reaching one boundary-holding var by two different paths: the emitted `:rule-path` is one
  of them, deterministically (the BFS is already sorted by `str`).
