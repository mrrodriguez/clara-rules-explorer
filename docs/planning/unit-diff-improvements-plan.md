# Unit diff improvements

Status: **proposal**. Scope: `clara.explorer.artifacts.diff`, its tests, and the `diff` paragraph
in `explorer/docs/persisted-artifacts.md`. No artifact change.

Two places where `annotations_report.bb <before> diff <after>` reports something under the wrong
heading. Both are independent and can land in either order.

## 1. Part A — a production's identity is its name, not its unit

### 1.1 Problem

`compared-fields` includes `[:unit :unit false]`, so a production whose `:unit` differs between
the two sides is tagged `[unit]`. In a composed unit, `:unit` is the unit key of the source unit
that supplied the production: `<repo>`, or `<repo>@<variant path>` for a variant.

Compose the same rulesets twice, once with every source unit at one set of coordinates and once
at another, and diff the two composed units. Every source unit's key differs (its variant path
names the coordinates), so **every production present on both sides is tagged `[unit]`**, even
though nothing about any production changed. The real changes drown in it.

### 1.2 Why `:unit` is not a production field

A production is identified by its fully-qualified name, and that name is the whole of its
identity: rules, queries, and fact types are named by their namespace, never by the unit that
happened to carry them. Which unit supplied a production is attribution, a fact about how the
selection was assembled. It belongs with the units, not with the production.

Nothing is lost by dropping it. A composed unit holds each production name once (they are the keys
of `production-index.edn`), so the only way a production can keep its name and change units is for
its namespace to move from one source unit to another. If its code is the same, that is not a change
to the rulebase, and the diff should report nothing for it. If its code is different, the other
compared fields say so. Fact types carry no unit at all, so this was only ever a production-level
tag.

### 1.3 Change

- Remove `:unit` from `compared-fields`. `production-tags` and `rule-detail-text` both read that
  spec, so the tag and the `--rule` detail drop it together. `:unit` stays in the production values,
  so `--edn` and `rule-detail` still show where each side's production came from.
- Add a `:units` key to the `diff` value, compared from each manifest's `:analysis-run :units`
  (`{:repo :variant :sha …}` per source unit), matched by `:repo`:
  - `:added` / `:removed`: repos supplying units on one side only;
  - `:changed`: repos on both sides whose `:variant` or `:sha` differs, each with its before and
    after.

  A ruleset unit records no `:units`, so for two ruleset units the key is empty. This is provenance
  for the comparison (which source units the two compositions were built from), not a statement
  about any production: a namespace moving between two source units that are on both sides shows
  here only as both units' shas changing.
- `->text` prints a `units:` count line and a `changed units:` section (`~ <repo> <before> ->
  <after>`, variant path and short sha) only when either side is composed, so a ruleset-unit diff
  reads exactly as it does today.

### 1.4 Tests

- Two composed units holding the same productions from source units at different variant
  addresses: no production tags, and every repo listed under `:units :changed`.
- A repo present on one side only: `:units :added` / `:removed`.
- Two ruleset units: `:units` empty, `->text` unchanged.
- The checked-in example's composed-vs-ruleset assertions still hold, minus any `[unit]` tag.

## 2. Part B — fact types a scope-only namespace brings with it

### 2.1 Problem

Productions and edges in a namespace present on only one side are listed under `scope`. Fact types
are not: a type that exists only because a scope-only namespace inserts or matches it is listed
under `fact-types` as added or removed, which reads as a change to shared code.

Before runs `orders.rules`; after also runs `promo.overrides`:

```clojure
(ns orders.rules)
(defrule order-total [:cart ...] => (insert! (->fact :order/total {...})))

(ns promo.overrides)
(defrule flag-big-order [:order/total (> amount 1000)] => (insert! (->fact :promo/big-order {})))
```

Today:

```
scope namespaces (only after):  promo.overrides
scope productions:              promo.overrides/flag-big-order
scope edges:                    orders.rules/order-total -> promo.overrides/flag-big-order
added fact-types:
  + :promo/big-order
```

Nothing shared inserts or matches `:promo/big-order`; it is in the diff only because
`promo.overrides` is. It belongs under `scope`.

### 2.2 The rule, with the hierarchy

"Touched by a shared production" has to follow the hierarchy, in the same two directions the
report's `producers` and `consumers` close over:

> An added or removed fact type `T` is listed under `scope` when **no shared production** (one not
> listed under `scope`) on the side holding `T`:
>
> - **matches** `T` or any **ancestor** of `T` (`:lhs-types`): a production matching an ancestor
>   receives `T` facts too; or
> - **inserts or retracts** `T` or any **descendant** of `T` (`:insert-types`, `:retract-types`):
>   a fact of a descendant is a fact of each of its ancestors.
>
> The hierarchy is that side's `fact-types.edn` `:ancestors`, descendants being its inverse.

The hierarchy case is the reason for the rule's shape. Add one shared production and one line to
the example above:

```clojure
(ns audit.rules)   ; on both sides
(defrule log-flag [?f <- :flag/any] => ...)

(ns promo.overrides)
(derive :promo/big-order :flag/any)
```

Now `:promo/big-order` reaches `audit.rules/log-flag`, shared code, through its ancestor. That is
a real effect on what shared rules see, so the type stays under `fact-types`. The edge
`promo.overrides/flag-big-order -> audit.rules/log-flag` is still listed under `scope` edges,
because one end is a scope production.

### 2.3 Change

- In `diff`, after `diff-scope` has decided the scope productions, partition `diff-fact-types`'
  `:added` (against the after side) and `:removed` (against the before side) by the rule above.
- Scope-attributed types move to a new `:scope :fact-types` vector; `->text` prints them as a
  `scope fact-types:` section and counts them on the `scope:` line.
- Everything needed is already in `read-unit`: each production's type lists and each side's
  `:ancestors`. No new file is read.

### 2.4 What it still cannot attribute

A type present on **both** sides whose `:ancestors` changed (`fact-types :changed`) stays there,
even when the `derive` that changed it lives in a scope-only namespace. `fact-types.edn` records a
type's `:name`, `:ns`, and `:ancestors`, not where a derivation was declared, so the diff cannot
trace an ancestry change to a namespace. The same goes for an edge between two shared productions
that appears only through such a change. Both stay where they are, and the doc says so.

### 2.5 Tests

- The `promo.overrides` example: `:promo/big-order` under `:scope :fact-types`, not
  `:fact-types :added`.
- The hierarchy example: a shared production matching an ancestor keeps the type in `:fact-types`.
- A shared production inserting a descendant of the type keeps it in `:fact-types`.
- A removed type, judged against the before side's productions and hierarchy.
- A type touched by both a scope and a shared production stays in `:fact-types`.

## 3. Docs

`persisted-artifacts.md`, the `diff` paragraph:

- the changed-production tags no longer include `:unit`; composed units get a `units` section;
- `scope` also lists the fact types only scope-only namespaces touch, through the hierarchy;
- §2.4's limit, in one sentence.
