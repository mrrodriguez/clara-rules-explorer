# Working-memory relations: say what a fact did, not just where it was "used"

Status: **proposal**. Scope: `explorer/src/clara/explorer/memory.clj`, the vendored
`clara.explorer.vendor.tools.inspect`, the session schemas in `server/api.clj`,
`docs/explorer-graph-api.md`, the UI consumers of `used-by` (`ui/src/lib/types/api.ts`, the
session fact and fact-type pages, `ui/bin/scrape-demo-data.js` and its demo data), and
`memory_test.clj` with one new fixture namespace.

## 1. Problem

### 1.1 `used-by` is not what its name and its docs say

The API documents a session fact's `used-by` as "Rules/queries currently matching this fact". It
documents the fact-type grouping as "Facts grouped by which rule/query reads them".

`->used-by-index` builds it from `inspect`'s `:rule-matches` and `:query-matches`.

- **Rules:** `:rule-matches` holds only the tokens that have a **logical insertion recorded** in
  the production node's memory (`mem/get-insertions-all`).
- **Queries:** `:query-matches` holds the query node's tokens, which are its current results.

So for a rule, `used-by` means "this fact is in an activation that recorded a logical insertion".
For a query, it means "this fact is in a current result". Neither is "reads this fact", and an
empty list cannot be told apart from "nothing reads this".

The rulebase fact type's `used-by-rules` and `used-by-queries` really do mean "reads this type on
its LHS", negated conditions included. So the two views use one name for two different relations.
They disagree exactly when someone needs them to agree: when finding out why a rule did not fire.

### 1.2 Where a fact takes part and nothing records it

All of these are observed with the fixture in §2, at `dec3c9e`.

| Fact | Takes part how | `used-by` today |
|---|---|---|
| `ManualHold` | blocks `ready-for-review` and the query `applications-without-hold` through `:not` | `[]` |
| `MissingDocument` app-1 | blocks `documents-complete` for app-1 through a `:not` joined on `?app-id` | `[]` |
| `LoanOffer` app-1, apr 9 | blocks `offers-within-limit` for app-1 through a `:not` with a join filter | `[]` |
| `DocumentCheck` app-1, `:failed` | joins `document-check-passed`, then its `:test` fails | `[]` |
| `ReviewClosed` app-1 | `close-review-task` fires on it, but only `retract!`s | `[]` |
| `Application` app-1 | `audit-application` fires on it, but only `insert-unconditional!`s | `audit-application` absent |

### 1.3 The fact list itself goes stale after `retract!`

`inspect`'s `:all-facts` is the union of alpha memory, the facts in production nodes' insertion
records, and the facts in tokens. An RHS `retract!` of a logically inserted fact removes it from
working memory, but **leaves the inserting production's record in place**.

- `ReviewTask` app-1 was inserted by `open-review-task` and retracted by `close-review-task`. A
  query for `ReviewTask` does not return it. The explorer still lists it, with
  `inserted-from [open-review-task]`.
- `Application` app-1 still lists `open-review-task` in `used-by`, though that activation's only
  insertion is gone.

Truth-maintenance retraction does not do this: losing support removes the token and its
insertions together.

## 2. Fixture: `clara.explorer.test.rules.memory-relations-test-rules`

One production for each way of taking part, in the loan-application theme the other fixtures use.
Every rule that should be recorded inserts a `Marker`.

```clojure
(ns clara.explorer.test.rules.memory-relations-test-rules
  "Working-memory relation fixtures. Each rule is one way a fact takes part in a
   production without appearing in a recorded activation, beside a rule where it
   does. Every logical rule inserts a Marker so its activation is recorded."
  (:require [clara.rules :as r]))

(defrecord Application [app-id max-apr])
(defrecord ManualHold [reason])
(defrecord MissingDocument [app-id doc-type])
(defrecord LoanOffer [app-id apr])
(defrecord DocumentCheck [app-id status])
(defrecord AuditEntry [app-id action])
(defrecord ReviewTask [app-id])
(defrecord ReviewClosed [app-id])
(defrecord Marker [rule app-id])

;; Negation, no join bindings: any ManualHold blocks every application.
(r/defrule ready-for-review
  [Application (= ?app-id app-id)]
  [:not [ManualHold]]
  =>
  (r/insert! (->Marker :ready-for-review ?app-id)))

;; Negation joined on a binding: a MissingDocument blocks only its own application.
(r/defrule documents-complete
  [Application (= ?app-id app-id)]
  [:not [MissingDocument (= ?app-id app-id)]]
  =>
  (r/insert! (->Marker :documents-complete ?app-id)))

;; Negation with a join filter: blocking depends on comparing two facts.
(r/defrule offers-within-limit
  [Application (= ?app-id app-id) (= ?max-apr max-apr)]
  [:not [LoanOffer (= ?app-id app-id) (> apr ?max-apr)]]
  =>
  (r/insert! (->Marker :offers-within-limit ?app-id)))

;; Positive join, then a failed test.
(r/defrule document-check-passed
  [Application (= ?app-id app-id)]
  [DocumentCheck (= ?app-id app-id) (= ?status status)]
  [:test (= :passed ?status)]
  =>
  (r/insert! (->Marker :document-check-passed ?app-id)))

;; Fires, but makes no logical insertion.
(r/defrule audit-application
  [Application (= ?app-id app-id)]
  =>
  (r/insert-unconditional! (->AuditEntry ?app-id :seen)))

;; A logical insertion that a later rule retracts with retract!.
(r/defrule open-review-task
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (->ReviewTask ?app-id)))

(r/defrule close-review-task
  [ReviewClosed (= ?app-id app-id)]
  [?task <- ReviewTask (= ?app-id app-id)]
  =>
  (r/retract! ?task))

;; A query with a negation.
(r/defquery applications-without-hold
  []
  [?app <- Application]
  [:not [ManualHold]])
```

The scenario: insert `Application` app-1 and app-2 (both `max-apr` 7), `ManualHold`,
`MissingDocument` app-1, `LoanOffer` app-1 apr 9 and app-2 apr 5, `DocumentCheck` app-1
`:failed`, and `ReviewClosed` app-1, then fire.

## 3. The relations

Every relation runs **from a fact to productions**, the same direction as `used-by` and
`inserted-from`. Read it as "this fact *relation* these productions". The fact-type level groups
its instances by production, exactly as `used-by` is grouped today.

| Key | A fact is in it when | Productions | Read from |
|---|---|---|---|
| `:supports-insertions-of` | it is in an activation whose recorded logical insertions include one still in working memory | rules | production node insertion records (§4.2 adds the "still in working memory" part) |
| `:supports-results-of` | it is in a current result (accumulator `:from` inputs included) | queries | query node tokens |
| `:matches-condition-of` | it passes a positive condition's own constraints, the ones that need no other condition's bindings | rules, queries | join node element memory and accumulate-node memory |
| `:blocks-condition-of` | it matches a negated condition **and** blocks a partial match waiting at that node | rules, queries | negation node elements and tokens |
| `:blocking-candidate-of` | it matches a negated condition, but blocks no partial match there now | rules, queries | negation node elements and tokens |

**Why two keys replace `used-by`.** For a rule the relation is "supports its insertions". For a
query, which has no RHS, it is "is in its results". Those are different claims, so they get
different names. Queries are not missing this information: their tokens are their results.

**How `retract!` fits in.** An RHS `retract!` is not truth-maintained, and Clara records no
insertion for it. So:

- An activation that only retracts leaves nothing behind, and its facts get no
  `:supports-insertions-of` entry (`ReviewClosed` above). That is a limit (§6).
- Retracting a fact that another activation inserted leaves that activation's record pointing at a
  fact that is gone. §4.2 stops counting such a record, which is what makes the name
  `:supports-insertions-of` true.

**`:blocks-condition-of` versus `:blocking-candidate-of`.** Both can be computed exactly from memory a restored
session already has. Clara keeps a negation node's waiting tokens even while they are blocked,
and keys tokens and elements by the same join bindings:

| Negation form | Fact blocks a token when | Fixture |
|---|---|---|
| No join bindings | always, if any token is waiting | `ManualHold` blocks both apps |
| Joined on bindings | its join bindings equal the token's | `MissingDocument` app-1 blocks app-1 only |
| Join filter | its join bindings equal the token's, **and** the node's `join-filter-fn`, called as `(join-filter-fn token fact fact-bindings env)`, returns bindings | `LoanOffer` apr 9 blocks app-1; apr 5 blocks nothing |

A candidate is a fact that **would** block a partial match that matches it, if one arrived:
`LoanOffer` app-2 apr 5 is one.

### 3.1 The fixture under the proposal

Observed with §4.3 on the scenario in §2. The `:supports-insertions-of` column for
app-1 applies §4.2: `ReviewTask` app-1 is absent from alpha memory.

| Fact | `used-by` today | Proposed |
|---|---|---|
| `Application` app-1 | `[open-review-task]` | `:supports-insertions-of []`; `:matches-condition-of` all seven productions |
| `Application` app-2 | `[documents-complete offers-within-limit open-review-task]` | `:supports-insertions-of` the same three; `:supports-results-of []` (the hold blocks the query); `:matches-condition-of` all seven |
| `ManualHold` | `[]` | `:blocks-condition-of [ready-for-review applications-without-hold]` |
| `MissingDocument` app-1 | `[]` | `:blocks-condition-of [documents-complete]` |
| `LoanOffer` app-1, apr 9 | `[]` | `:blocks-condition-of [offers-within-limit]` |
| `LoanOffer` app-2, apr 5 | `[]` | `:blocking-candidate-of [offers-within-limit]` |
| `DocumentCheck` app-1, `:failed` | `[]` | `:matches-condition-of [document-check-passed]` |
| `ReviewClosed` app-1 | `[]` | `:matches-condition-of [close-review-task]` |
| `ReviewTask` app-1 | listed, though retracted | not listed |
| `ReviewTask` app-2 | `[]` | `:matches-condition-of [close-review-task]` |

## 4. Changes

### 4.1 Split and rename `used-by`

- Replace the fact's `:used-by` with `:supports-insertions-of` (rule refs) and
  `:supports-results-of` (query refs). Do the same for the fact-type grouping.
- Remove `:used-by` entirely (no deprecated alias); the two new keys supersede it.
- Update the UI types and pages to the new keys.
- Leave the rulebase fact type's `used-by-rules` and `used-by-queries` alone. They mean what they
  say.

### 4.2 Drop stale insertion records

A fact in an insertion record **is present** if it is held by beta-node memory — join/negation
element memory or accumulate-node memory (accumulator `:from` inputs). **It was retracted** if
some alpha node would accept it but none holds it; re-run that node's `activation` on the fact to
tell. If no alpha node accepts it, presence cannot be decided. Keep it, and say so in the docs.

- `:all-facts` and the fact table drop retracted facts.
- `:supports-insertions-of` counts a token only if one of its insertions is still present.

### 4.3 Node-memory relations

Walk `:id-to-node`. A node's productions are the production and query nodes reachable through
`:children`.

- **Join nodes** (`RootJoinNode`, `HashJoinNode`, `ExpressionJoinNode`): each element →
  `:matches-condition-of`.
- **Negation nodes** (`NegationNode`, `NegationWithJoinFilterNode`): group the tokens and the
  elements by join bindings, the node's `:binding-keys` taken from each `:bindings`. Then apply
  the table in §3 → `:blocks-condition-of`, or else `:blocking-candidate-of`.
- **Accumulate nodes** (`AccumulateNode`, `AccumulateWithJoinFilterNode`, which include
  `:exists`): read the accumulated facts from accumulate memory into
  `:matches-condition-of`; the join-filter variant still contributes every candidate fact
  (condition-local, not token-filtered).

Don't build this on `inspect`'s `:condition-matches`. It keys by condition, so equal conditions in
different productions merge, and the node, and with it the production, is lost. It also never
looks at tokens, so it cannot tell `:blocks-condition-of` from a candidate.

### 4.4 API and docs

New `ProductionDep` lists on the session fact and the fact-type role groups. In
`docs/explorer-graph-api.md`, one definition per key, worded as in §3, plus §6's limits.

## 5. Tests (`inspect_test.clj`, `memory_test.clj`, fixtures in §2 and the accumulator fixture)

- Each row of §3.1 is one assertion on the relation it names.
- `:blocks-condition-of` and `:blocking-candidate-of` are disjoint for each fact and production.
- `ReviewTask` app-1 is absent from `:facts`; `ReviewTask` app-2 is present.
- A session with no negated, failed or retracted facts has `:supports-insertions-of` equal to the
  rules that fired (rule-only) and `:supports-results-of` equal to the query that matched
  (query-only).
- An inserted accumulator-input fact read only by an accumulator `:from` condition is retained in
  `:facts`, appears in `:matches-condition-of` for both accumulate node types, and supports the
  rules that accumulated it.

## 6. Limits, documented rather than fixed

- **An activation that recorded no logical insertion leaves no trace.** That covers
  `insert-unconditional!`, `retract!`-only RHSs, side effects, and an `insert!` on a branch not
  taken (`audit-application`, `close-review-task`). Only `:unfiltered-rule-matches`, from a
  session fired under `with-full-logging`, has these. If it is present, an optional
  `:in-activations-of` could expose it.
- **An unconditional insertion of a type no condition reads is invisible.** `AuditEntry` is in no
  view: no alpha node holds it and no insertion record names it.
- **`:test` conditions hold no facts.** `:matches-condition-of` shows `DocumentCheck` reached
  `document-check-passed`, but not that the test was what stopped it.
- **`:matches-condition-of` is condition-local.** It says the fact passes that condition's own
  constraints, not that it joined the facts before it.  For an accumulator `:from`, a fact is
  condition-local by passing the `:from` type and alpha constraints; a join-filter accumulator
  still lists candidate facts that the token filter would reject.

## 7. Order

§4.3 is additive and can land first. §4.1 is the breaking rename (no deprecated alias). §4.2 is
independent of both. The fixtures and the §5 tests land with whichever change they cover.
