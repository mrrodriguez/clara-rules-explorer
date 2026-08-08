# Nil safety in the analysis pipeline — remaining TODOs

## Background

A Clara session can hold a fact that its `fact-type-fn` cannot name. The
ordinary way in is a rule whose RHS inserts a collection containing `nil`:

```clojure
(when (seq facts)      ; non-empty, but has nil elements
  (insert-all! facts))
```

Clara accepts this. `create-get-alphas-fn` routes each fact by
`(fact-type-fn fact)`; for `nil` that yields `nil`, which matches no alpha
roots, so the fact matches nothing and is never seen again. Nothing warns. The
value sits inertly in insertion memory and costs nothing — until something
tries to _name_ it, which is exactly what this project does.

Before the recent nil-safety work, that surfaced as an NPE thrown a long way
from the cause:

```
Execution error (NullPointerException)
  at clara.server.tools.graph.serialize/slug (serialize.clj:44).
Cannot invoke "Object.toString()" because "s" is null
```

### What has landed already

`070c010` — bottom-layer tolerance in `serialize`:

- `slug` and `sha1-base36` take `(or s "")`, so `nil` slugs to `"x"`.
- `route-id*` documents `nil` as equivalent to `""`.
- Tests: `(= (route-id "") (route-id nil))`, and
  `(serialize-type-ref #{} nil nil)` returns `{:name nil, :id (route-id nil),
:known false}` without throwing.

`513387f` — drop-or-substitute at several consumers:

- `annotations/resolve-types` — `keep`, dropping tokens that resolve to nil.
- `core/extract-lhs-fact-types` — `(remove nil?)`.
- `core` produced/retract type sets — `(remove nil?)`.
- `serialize/serialize-dynamic-callsite` — `(remove nil?)` over
  `:resolved-types`.
- `memory/build-fact-table` — substitutes
  `:clara.tools.graph.analyze/unknown-fact-type` for a nil raw type.

That is a real improvement: the pipeline no longer dies. But every one of those
changes is **downstream tolerance** — each is placed where the nil has already
propagated. Nothing yet defends the point of entry, and the entry point is the
vendored inspect, which is intended to become part of clara-rules proper. The
items below are ordered with that first.

---

## 1. `get-wrapped-fact-groups` admits nil facts; `inspect-facts` in the same namespace does not

**Where:** `clara.server.vendor.tools.inspect/get-wrapped-fact-groups`

**Severity:** high — this is the source, and it is the code headed upstream.

### What happens

`get-wrapped-fact-groups` builds three sets and unions them into `:all-facts`:

- `facts-from-alphas` — `(map :fact)` over alpha memory elements
- `facts-from-inserts` — every fact in every insertion group
- `facts-from-matches` — token matches, falling back to `[fact]` when the node
  has no accumulator condition or the accumulator result is not a collection

None of the three filters `nil`. `:all-facts` is a key this fork _adds_ to the
inspection result — upstream `clara.tools.inspect/inspect` does not return it —
so every consumer of that key is exposed to a value upstream consumers never
see.

Meanwhile, `inspect-facts` in this same namespace already guards:

```clojure
:when (and (some? fact)
           (not (instance? ISystemFact fact)))
```

So the two fact views disagree on both counts, and the newer one is the less
defended.

### Why it matters

Two separate contract questions are currently unanswered, and both get decided
by accident:

1. **Can `:all-facts` contain nil?** Today: yes, and every downstream consumer
   has had to be patched one at a time (see the `513387f` list above). That is
   the pattern this item exists to stop.
2. **Does `:all-facts` include system facts?** Today: yes — `ISystemFact`
   instances such as `NegationResult` appear in it, where `inspect-facts`
   deliberately excludes them. A caller treating `:all-facts` as "the user's
   facts" gets engine internals mixed in.

`RulesInspectionSchema` types the key as `[s/Any]`, which documents neither.

### Suggested direction

Decide and document what `:all-facts` means, then enforce it once in
`get-wrapped-fact-groups` rather than at each consumer. If the answer is "every
fact the engine holds, warts included", say so in the schema docstring and
leave the guards downstream — but then the downstream guards need to be
complete, which today they are not (items 4–6). If the answer is "the same
population `inspect-facts` reports", apply the same `some?` / `ISystemFact`
filter and the downstream tolerance becomes belt-and-braces rather than
load-bearing.

Whichever is chosen, this is the decision worth making before the code moves
into clara-rules, because it becomes a public contract at that point.

DECISION: We should exclude nil and exclude ISystemFact the same way it is done elsewhere.

---

## 2. The unknown-fact-type sentinel

**Where:** `clara.server.tools.graph.memory/build-fact-table`

**Severity:** medium

```clojure
type-name (->> (or raw-type
                   :clara.tools.graph.analyze/unknown-fact-type)
               (serialize/serialize-fact-type nil))
```

Three distinct problems in three lines:

**a. It is a magic literal, used once, that is part of the API surface.** This
value reaches clients as a fact type `:name`, and its `route-id` becomes a URL
segment. It should be a named, documented var that both `memory` and any future
consumer can reference — a client wanting to special-case untyped facts
currently has to hardcode the string.

**b. It is silent.** The substitution is invisible: no log, no count, no
attribution. See item 7. DECISION: Print a warning for this.

---

## 3. Three different nil policies, none of them written down

**Where:** across `annotations`, `core`, `memory`, `serialize`

**Severity:** medium — the inconsistency is what makes items 4–6 easy to
re-introduce.

The codebase currently does all three of:

| Policy                     | Where                                                                                                                                              |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Drop**                   | `annotations/resolve-types`, `core/extract-lhs-fact-types`, `core` produced/retract sets, `serialize/serialize-dynamic-callsite` `:resolved-types` |
| **Substitute**             | `memory/build-fact-table`                                                                                                                          |
| **Tolerate and propagate** | `serialize/resolve-type` → `serialize-type-ref` (item 4)                                                                                           |

These are defensible individually and incoherent together. A reasonable rule,
stated once and referenced from each site:

- **Static analysis drops.** A nil type token carries no information — there is
  no fact behind it, only a failed resolution. Dropping loses nothing.
- **Working memory substitutes and attributes.** There _is_ a real fact object;
  dropping it would silently under-report the session's contents. It needs a
  name, and it needs to be traceable back to whatever produced it.
- **Serialization sees neither.** By the time `serialize-type-ref` runs, nil
  should already be impossible; a guard there is a backstop, not a policy.

---

## 4. `serialize-type-ref` still emits `:name nil`

**Where:** `clara.server.tools.graph.serialize/serialize-type-ref`

**Severity:** medium

`resolve-type`'s first clause is `(nil? x) nil`, so a nil type produces:

```clojure
{:name nil, :id "x-<hash>", :known false}
```

`070c010` added a test that _asserts_ this shape, which makes it deliberate
rather than accidental — but it means a nullable `:name` is now a supported
TypeReference, and it flows into `known-set` membership checks, the id indexes
(item 5), and the JSON API.

Worth confirming against the UI's TypeReference model whether `name` is
declared nullable there. If it is not, this is a wrong answer that reaches the
client rather than an exception that gets caught.

DECISION: we should not serialize this broken resolve-type structure for the consumer. The type
should be dropped entirely with a warning printed.

---

## 5. `route-id` aliases nil and `""`, and three index builders treat ids as identities

**Where:** `memory/build-id-name-index`, `core/build-production-id-index`,
`fact-types/build-fact-type-id-index`

**Severity:** medium — converts a tolerated nil into a hard failure elsewhere.

`070c010` made `(route-id nil)` equal `(route-id "")` by design. All three index
builders throw on a duplicate id:

```clojure
(throw (ex-info (format "... route-id collision: %s and %s both map to %s" ...)))
```

So an analysis containing both a nil-named entity and an empty-string-named one
now fails loudly at index-build time. That is the same failure mode the
nil-safety work set out to remove — an exception thrown far from the cause —
reintroduced one layer up. It is narrow, but it is reachable precisely because
item 4 lets nil names through.

Two ways out, and the first is better: make nil names impossible before the
index (items 3 and 4), leaving the collision assertion to catch what it was
written for. Failing that, the collision message should distinguish `nil` from
`""` — `format`'s `%s` renders both indistinguishably, so the error text
currently reads as though the same name collided with itself.

DECISION: we are addressing nils upstream, but we should never try to make a route on nil. we should
print a warning if it happens and otherwise skip this entry from every needing to get a route-id made.

---

## 6. `serialize-lhs` deliberately admits a nil `:type`

**Where:** `clara.server.tools.graph.serialize/serialize-lhs`

**Severity:** low

```clojure
(contains? node :type) (update :type #(serialize-type-ref known-set prod-ns %))
```

`contains?` rather than a truthiness check, so a condition node with an explicit
`:type nil` is passed through to `serialize-type-ref`. Before `070c010` this
threw; now it yields `{:name nil ...}` — the crash became a quiet wrong answer,
which is the less useful of the two.

Decide whether a condition with a nil `:type` is legal. If it cannot occur,
assert it. If it can, normalize it the way item 3 prescribes.

DECISION: only include the :type if there is `some?` value for `:type` (non-nil).

---

## 7. Nothing reports what was dropped or substituted

**Where:** all of the `513387f` sites, and `memory/build-fact-table` in
particular

**Severity:** medium — this is what makes the next occurrence hard to diagnose.

Every guard added so far is silent. An analysis over a session with untypable
facts now succeeds and looks clean; the only trace is an `unknown-fact-type`
entry a reader would have to notice and interpret.

The information needed to make this actionable is available at the point of
substitution. `build-fact-table` already computes `origin-map`, and each fact
entry gets `:inserted-from` — so the snapshot can say _which rules_ produced
facts it could not type. Options, roughly in order of cost:

- a `log/warn` once per snapshot build with the count and the distinct
  inserting rule names;
- a summary key on the snapshot itself (count plus rule names), which the API
  can expose and the UI can surface as a data-quality signal;
- the same for the static-analysis drops in `annotations/resolve-types` and
  `core`, which currently discard tokens with no record that they existed.

The underlying condition is a defect in the rules being analyzed, not in this
project. Tolerating it is right; hiding it is not. The value this project adds
is precisely telling someone what their rulebase actually does.

---

## 8. No end-to-end regression test at the entry point

**Where:** `server/test/clara/server/tools/graph/`

**Severity:** medium

`070c010` added unit tests for `route-id` and `serialize-type-ref` with nil
arguments. Those pin the individual guards. There is no test that:

1. builds a session from a rule whose RHS inserts a collection containing nil,
2. runs `memory/session-snapshot` and `core/rulebase-analysis` over it, and
3. asserts the resulting shape — no throw, a named type for the untyped fact,
   `:known false`, and (per item 7) attribution to the inserting rule.

That is the test that would have caught the original defect, and unlike the unit
tests it protects the _policy_ from item 3 rather than one function's argument
handling. Demo rules for this kind of fixture already live under
`server/test/clara/server/tools/graph/rules/`.

Worth adding alongside it: a case where the untypable fact reaches the alpha and
match collections rather than only the insertion collection, since item 1 shows
all three paths are unguarded but only the insertion path has been exercised in
practice.
