# Group Bindings Are Analyzed But Never Attached — Problem & Plan

Companion to [`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md) and
[`roadmap-enhanced-lhs-ana.md`](./roadmap-enhanced-lhs-ana.md).

Status: **resolved.** `:or` groups, `:exists` groups, compound negations, and every
leaf nested inside one now carry `:bindings` on the augmented LHS (implemented
per §5.1–§5.4, §5.6 — the no-node-without-`:bindings` invariant holds). The
transitional `:bindings-deferred` net proposed in §5.5 was removed after
landing: it was never emitted, so it was pure baggage.

The cause is mostly **representational, not semantic.** Group bindings are well
defined, clara computes the compound-negation one itself and materializes it in the
compiled network, and for `:or` / `:exists` the records are already computed
correctly by `analyze-lhs-bindings` before being discarded. The blocker is that the
pass returns a flat record list keyed by a 1:1 `:attach-path`, and a group is
inherently one node to *many* records with a scope of its own.

One case is genuinely semantic and needs new analysis rather than re-plumbing:
**compound negations**, where the existing records model a De Morgan decomposition
the compiler never builds (§3.2). Those records must be replaced, not rerouted.

---

## 1. The defect

`augment-lhs` attaches a `:bindings` map per LHS leaf: plain fact leaves, test
leaves, accumulator leaves, and the child of a simple `[:not [T …]]`. It attaches
nothing under a `:or`, an `:exists`, or a compound negation — and the omission
reaches the **nested leaves**, not just the group node.

| condition | group node | nested leaf |
| --- | --- | --- |
| `[:not [T …]]` (simple) | no `:bindings` | **has** `:bindings` |
| `[:or [A …] [B …]]` | no `:bindings` | **no** `:bindings` |
| `[:exists [T …]]` | no `:bindings` | **no** `:bindings` |
| `[:not [:and [A …] [B …]]]` | no `:bindings` | **no** `:bindings` |

### Why this is worse than a missing feature

The key is **absent**, not empty. A consumer writing the obvious thing —

```clojure
(get-in leaf [:bindings :binding-keys])   ;; => nil
```

— reads `nil` as "this condition joins on nothing", which is a *wrong answer*
rather than a missing one. Nothing on the value distinguishes "analyzed, joins on
nothing" from "not analyzed", so a consumer aggregating bindings across a rulebase
silently under-reports every disjunctive and existential condition with no signal
that it did. That makes this a correctness issue for consumers, and it is why the
deferral marker in §5.5 is worth shipping ahead of everything else.

---

## 2. Root cause

`analyze-lhs-bindings` computes a full `LhsBindingRecord` for every expanded
conjunction, groups included. `resolve-attach-path` then decides where it lands:

```clojure
(defn- resolve-attach-path
  [origin condition]
  (cond
    (map? condition)                 origin
    (compound-negation? condition)   nil
    (#{:not 'not} (first condition)) (ffirst (group-child-paths origin condition))
    :else                            nil))     ;; <- :or and :exists land here
```

`build-binding-index` keeps only records with a non-nil `:attach-path`:

```clojure
(if attach-path
  (assoc acc attach-path {:bindings (binding-summary record)})
  acc)                                          ;; <- silently dropped
```

Observed on `[Acct (= ?aid id)] [:or [Doc (= ?aid acct-id) (= ?k kind)] [Flag (= ?aid acct-id)]]`:

```
origin=[0]  attach=[0]   binding-keys=()       new-bindings=(:?aid)
   {:type Acct, :constraints [(= ?aid id)]}
origin=[1]  attach=nil   binding-keys=(:?aid)  new-bindings=(:?k)     ; Doc
origin=[1]  attach=nil   binding-keys=(:?aid)  new-bindings=()        ; Flag
```

Both branches have correct, useful binding sets. They are simply not placed.

### The representation is the blocker

`{attach-path -> bindings}` requires each record to own exactly one tree node.
Three things a group needs, that shape cannot express:

- **1:N.** One group node summarizing N branch records needs *aggregation*, where
  `build-binding-index` does `assoc` — and in fact throws on a duplicate path, so
  attaching two `:or` branches at the group's own origin fails outright.
- **Scope.** A group's children are analyzed relative to the bindings available at
  the group, and variables bound inside a negation do not escape it. A flat list
  carrying one global `ancestor-bindings` thread has nowhere to put a sub-scope.
- **Synthesized conditions.** `:exists` expands to an accumulator that exists in no
  tree. There is no original node to attach it to, so the group node has to be a
  legal attachment target — which the current shape allows only by accident.

Everything below follows from fixing that, not from discovering new semantics.

---

## 3. What the compiler actually does

### 3.1 Compound negations: clara computes the group binding set itself

`clara.rules.compiler/get-complex-negation`, handling exactly
`[:not [:and/:or/:not …]]`:

```clojure
;; Insert the bindings from ancestors that are used in the negation
;; in the NegationResult fact so that the [:not [NegationResult...]]
;; condition can assert that the facts matching the negation
;; have the necessary bindings.
ancestor-bindings-in-negation-expr (set/intersection
                                     (variables-as-keywords negation-expr)
                                     ancestor-bindings)
```

That set **is** the compound negation's group-level `:binding-keys`: the upstream
variables the negation joins on. It needs no DNF, no record matching, and no attach
path — just `variables-as-keywords` over the group and an intersection with the
bindings in scope. The generated helper production's LHS is
`(concat previous-expressions [negation-expr])`, so the nested conditions are
analyzed in the same ancestor context the group sits in — which is precisely the
recursive sub-scope walk proposed in §5.2.

The extraction being a separate production is an *evaluation* strategy. It does not
make the question "which upstream variables does this condition depend on"
ill-posed; clara answers it on the line above.

### 3.2 The compiler never De Morgans a compound negation — and the current records assume it does

`get-complex-negation` runs **before** `to-dnf`, and replaces the condition:

```clojure
{:keys [new-expression generated-rule]} (get-complex-negation previous-conditions
                                                              current-condition
                                                              ancestor-bindings
                                                              production)
condition       (or new-expression current-condition)
dnf-expression  (to-dnf condition)          ;; <- sees [:not {NegationResult …}]
```

So a compound negation is rewritten to a *simple* negation over `NegationResult`
before DNF ever sees it, and `to-dnf` is then a no-op on it. Confirmed on the
compiled network for `[Acct …] [:not [:and [Doc …] [Flag …]]]`:

```
4 ProductionNode  production: r-not-and__G__29958
                  lhs: (Acct [:and Doc Flag])       ; the generated helper rule
5 NegationNode    condition: clara.rules.engine.NegationResult
                  constraints: [(= "…__G__29958" gen-rule-name)
                                (= ?aid (:?aid ancestor-bindings))]
6 ProductionNode  production: r-not-and             ; the original
```

Two things follow, and they point in opposite directions.

**The group-level binding set is not merely well defined — it is materialized in the
network.** `(= ?aid (:?aid ancestor-bindings))` on the NegationNode *is*
`ancestor-bindings-in-negation-expr` from §3.1. A group-level `:binding-keys` for a
compound negation can therefore be computed by the §3.1 one-liner **and checked
against the compiled NegationNode**. That is a stronger footing than any other group
kind has.

**But the records `analyze-lhs-bindings` produces today for compound negations are
modeling something the compiler does not build.** `disjunction-branches` calls
`com/to-dnf` on the raw condition, with no `get-complex-negation` step, so it gets
the De Morgan expansion:

```clojure
[:not [:and {:type Doc …} {:type Flag …}]]
;; com/to-dnf =>
(:or [:not {:type Doc …}] [:not {:type Flag …}])
```

That expansion is logically equivalent and not *unsound* — clara rejects rules whose
conjuncts are correlated through an internally-bound variable, so the analyzer can
never meet a case where De Morgan changes the meaning:

> Using variable that is not previously bound. … Unbound variables: `#{?d}`

— but it is a different decomposition from the helper-production-plus-`NegationResult`
the network actually contains. §5.2 must therefore **not** reuse these records for
compound negations; it must do the sub-scope walk over `negation-expr`, which is
exactly what the generated rule's LHS `(concat previous-expressions [negation-expr])`
already describes.

This retroactively makes the shipped deferral a *conservative* choice for compound
negations rather than a pure oversight. The silent absence is still the bug.

*(Two earlier drafts of this section were wrong: the first said the extraction made
the binding question ill-posed — it does not, see §3.1. The second said no
extraction happens for DNF-able shapes — it does, for every compound negation. The
generated rule is reachable only through the node graph; it is not in
`(:productions rulebase)`, which is what made it easy to miss.)*

### 3.3 Reusing the compiler does not mean only reusing it

The pass deliberately reuses `to-dnf` / `sort-conditions` / `condition-to-node` so
it stays correct as clara evolves. That is the right default and should not change.
It does not follow that every question must be answered by a compiler function
applied to a whole top-level condition. Where a group needs its own analysis —
recursing into children with a scoped ancestor set, or intersecting a group's
variables with the bindings in scope — doing that directly is still reuse: it calls
the same `variables-as-keywords` / `condition-to-node` primitives, just at a
granularity the current entry point does not offer.

---

## 4. What a group's bindings mean

**One rule, every node: a group's `:bindings` is the componentwise union of its
children's.** One vocabulary — `:binding-keys`, `:new-bindings`,
`:join-filter-join-bindings` — on leaves and groups alike, with no group-only keys
and no second interpretation to choose between.

That holds for each group kind, and not by convention — it falls out of the
per-child records, which come from `com/condition-to-node` and therefore already
encode clara's scope rules:

- **`:or`** — union across branches. Strictly, each branch is its own beta path, so
  a variable bound on one branch is not bound on the others and the union
  over-approximates. In practice clara rejects a downstream condition that depends
  on a one-branch binding, so the difference cannot be observed by a rule that
  compiles. Treat it as the union (§7.1 records the escape hatch if it ever bites).
- **Compound negation** — clara's own `variables-as-keywords ∩ ancestor-bindings`
  (§3.1) and the union of children agree, because a negation's children can only
  join on ancestor bindings: clara rejects intra-negation correlation, and
  `condition-to-node` reports no new bindings for a negated condition. So each
  child's `:new-bindings` is empty, and the union is empty — which is also the
  correct downstream answer, since nothing bound inside a negation escapes it.
- **`:exists`** — one child, so the union *is* the child. The synthesized
  accumulator's binding set and the child's are the same set (§5.3).

The escape rules therefore need no special-casing at the group level. They are
already true of every child record, so unioning preserves them.

---

## 5. Plan

Children first, then the group, since the group is just their union (§4).

### 5.1 Analyze nested conditions in their own scope

For the children, recurse rather than trying to back a path out of the flat list:
run the walk over the group's children with `ancestor-bindings` set to what is in
scope at the group, and attach the resulting records at the children's real paths.

For a compound negation this is not merely convenient, it is **required for
correctness** (§3.2): the existing DNF records decompose the negation a way the
network does not, so they must be discarded for this case. The sub-scope walk over
`negation-expr` reproduces the generated helper rule's own LHS,
`(concat previous-expressions [negation-expr])`, which is what the compiler
actually analyzes.

Two scope rules the recursion must honor, both already clara's:

- **Nothing bound inside a negation escapes it.** The sub-walk's new bindings feed
  the sub-scope only; the outer `ancestor-bindings` is unchanged by a negation.
- **A disjunction's branches are independent of each other.** Each branch starts
  from the group's ancestor set, not from the previous branch's result.

For `:or`, the existing records already carry real tree nodes as `:condition` and
could be matched by value within the group's origin; the sub-scope walk gives the
same answer and is simpler to reason about, so prefer it uniformly.

### 5.2 Roll the children up to the group

`:bindings` on a group is the componentwise union of its children's (§4). No new
keys, no aggregation policy to configure, and no case analysis by group kind.

Mechanically: `resolve-attach-path` returns the group's own `origin` instead of
`nil`, and `build-binding-index` unions when several records land on one path.
Keep the duplicate-path throw for *leaf* paths, where a collision really is an
invariant violation.

### 5.3 `:exists`

`expand-exists` synthesizes `{:accumulator (…/exists) :from <child> :result-binding
:?__exists__…}`. That condition exists in no tree, so there is nothing to attach it
to as-is — but its `:from` **is** the child, so its binding set is the child's
binding set. Attach it to both: the child gets it because it is genuinely the
child's, and the group gets it as the union of its one child. Uniform with every
other group, and no marker.

Two details:

- **Never surface `:?__exists__…`.** The synthetic `:result-binding` is an artifact
  of the analysis, absent from the authored rule. It must not appear in
  `:new-bindings` at either level.
- **Nothing inside an `:exists` escapes it**, same as a negation, so it contributes
  no new bindings downstream — which the child's own record already reflects.

### 5.4 Wire shape

One vocabulary, every node:

```clojure
{:condition-type :or
 :children [{:type Doc  … :bindings {:binding-keys [:?aid] :new-bindings [:?k]}}
            {:type Flag … :bindings {:binding-keys [:?aid] :new-bindings []}}]
 :bindings {:binding-keys [:?aid]      ; union of the children
            :new-bindings [:?k]}}      ; union of the children
```

`:join-filter-join-bindings` unions the same way and stays optional-when-empty.

**A group's `:bindings` is derived, not additional.** A consumer aggregates over
leaves *or* reads group summaries — never both, or it double counts. Worth saying
in the schema docstring, since it is the one thing the uniform shape does not make
self-evident.

### 5.5 Make any remaining gap explicit — ship this first

Independent of everything above and the highest value per line: never let an
unanalyzed node be indistinguishable from an analyzed one.

```clojure
{:condition-type :not
 :bindings-deferred :compound-negation
 :children [ … ]}
```

A consumer then branches on a key that is *present* and says why, instead of
inferring meaning from `nil`. Ship it even if §5.1–5.3 slip: it turns a silently
wrong answer into a visibly incomplete one.

This is a **transitional safety net, not part of the target shape.** Once §5.1–5.3
land it should appear nowhere, and the invariant in §6 is what enforces that. It is
deliberately *not* used for `:exists` children (§5.3) — those get real bindings, and
marking them would be inventing a special case where the uniform rule already
applies.

### 5.6 Schema and UI

- `LhsBindingInfo` is unchanged — groups reuse the leaf shape (§5.4).
- `LhsCondition` gains `(s/optional-key :bindings-deferred) s/Keyword`; it already
  permits `:bindings` on group entries.
- `LhsCondition.svelte` renders `bindings` on group entries as well as leaves, and
  surfaces `bindings-deferred` rather than an empty binding block.

---

## 6. Tests

Each case asserts the group node **and** every nested leaf, since missing the
nested level is the current defect.

- flat `:or` — both branches attached; group equals their union
- nested `:or` over `:and` — every leaf attached at its real path
- `:or` with identical children — both attached, no duplicate-path throw
- `:or` with asymmetric branches — a binding present on one branch only still
  appears on the group, per §4
- two `:or` groups in one LHS — no cross-talk between their sub-scopes
- `:exists` — group and child carry the same bindings; `:?__exists__…` appears
  nowhere; neither node is marked deferred
- compound negation — nested leaves attached from the sub-scope walk, **not** from
  the De Morgan records
- negation-internal and `:exists`-internal bindings do not leak into the outer
  ancestor set
- simple `:not` — unchanged, child still attached

The structural property, asserted over every group in the fixture rather than case
by case, since it is the whole model:

> **A group's `:bindings` equals the componentwise union of its children's.**

The cross-check worth having, because it ties the analysis to the network rather
than to itself:

> For a compound negation, the group's `:binding-keys` equals both
> `(set/intersection (variables-as-keywords negation-expr) ancestor-bindings)` and
> the `ancestor-bindings` restriction on the compiled `NegationNode`'s
> `NegationResult` condition.

And the invariant that actually closes the hole, over a fixture covering every
condition kind:

> **No node in an augmented LHS has neither `:bindings` nor `:bindings-deferred`.**

It fails today on every group.

---

## 7. Open questions

1. **If the `:or` union ever proves too coarse**, the escape hatch is a key on the
   group's `:bindings` describing how the branches were combined — a
   `:merge-resolution :union` or similar — rather than a second set of binding keys.
   That keeps one vocabulary and lets a caller detect the approximation instead of
   having to know about it. Not worth adding until a real rule is misread by it:
   `:or` branches that disagree about their bindings are unusual, and a rule that
   depends on the difference is already hard to reason about for other reasons.
2. **Whether the generated helper rule should be surfaced at all.** It exists in the
   node graph and not in `:productions`, so nothing today can see it. Naming it on
   the compound negation's `:bindings` would let a consumer correlate the analysis
   with the network, and would have made §3.2 obvious rather than a two-draft
   mistake. Probably out of scope here, but worth recording.
