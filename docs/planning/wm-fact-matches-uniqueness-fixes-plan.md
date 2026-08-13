# Working-memory production matches — fact identity in `:matches`

Status: **Plan — for review. Not yet implemented.**

Scope: `tools/graph/memory` (`explanations->fact-match-data`), the `ProductionActivity` API schema,
and the UI components that render it. Update-in-place — no compatibility shim for the current
response shape.

---

## 1. Problem

`/v1/session/rules/:id` and `/v1/session/queries/:id` return

```clj
{:matches [SessionFact …] :inserted-facts [SessionFact …]}
```

`:matches` is built by `explanations->fact-match-data`, which emits **one row per
(explanation × condition × fact)**:

```clj
(for [{:keys [bindings matches]} explanations
      match matches
      fact (extract-match-facts match)
      …]
  (assoc fact-entry :data (prune-fn bindings)))
```

Two things follow, and both are wrong at the API boundary.

**The same fact id appears more than once in one list.** Consumers treating `:id` as the row
identity break. The UI keys its `{#each}` on `item.id`, so a repeated id raises Svelte's
`each_key_duplicate` — a *fatal* hydration abort that blanks the entire rule page, not just the
list. The rule's summary view renders fine (it does not include session activity), so the symptom
reads as "full view is broken for this one rule".

**`:data` means two different things depending on where the `SessionFact` came from.** In
`:inserted-facts`, in `/v1/session/facts/:id`, and in fact-type role groups, `:data` is the fact's
own value. In `:matches` it is overwritten with the activation's bindings, and the fact value is
gone. Every consumer renders `:data` with the same widget, so an "Active Matches" row silently shows
bindings where the identical widget elsewhere shows a fact.

## 2. Minimal motivating example

Two rules, four facts. Both cases occur in ordinary rulebases and neither is a defect in the rules.

```clj
(def facts
  [{:type :t/config :name "c1"}
   {:type :t/item :tag "a"}
   {:type :t/item :tag "b"}
   {:type :t/item :tag nil}])
```

### Case A — one fact, one activation, two conditions

```clj
(defrule overlapping-conditions
  [?tagged <- (acc/all) :from [:t/item (some? tag)]]
  [?all    <- (acc/all) :from [:t/item]]
  => …)
```

One activation, bindings `{?tagged [item-a item-b] ?all [item-a item-b item-nil]}`. Facts `item-a`
and `item-b` satisfy *both* conditions, so `extract-match-facts` yields each of them twice inside
the same explanation. The two rows are byte-identical — same fact, same bindings. Pure duplication,
no information carried.

### Case B — one fact, many activations

```clj
(defrule pairwise
  [?config <- :t/config]
  [?item   <- :t/item]
  => …)
```

Three activations, one per `:t/item`. `config-c1` appears in all three, each time with a *different*
bindings map (`{?config c1 ?item item-a}`, `{… item-b}`, `{… item-nil}`). Three rows, same fact id,
genuinely distinct content.

**Deduplicating rows by fact id is only correct for Case A.** Doing it for Case B discards
activations. The current shape cannot express Case B with a unique `:id`, because the row is a
(fact, activation) pair and the fact id is not the pair's identity.

## 3. Server API change

`:matches` becomes a list of facts, each carrying every binding set it matched under. One row per
fact; `:id` is a key again; `:data` recovers its single meaning.

```clj
(s/defschema FactMatch
  "A working-memory fact matched by a production, with every distinct set of
   variable bindings it matched under.  One entry per fact — the fact appears
   once no matter how many conditions or activations it satisfies."
  {:fact SessionFact          ; :data is the fact's own value, as everywhere else
   :bindings [s/Any]})        ; s/Any: bindings are caller-shaped pruned data

(s/defschema ProductionActivity
  {:matches [FactMatch]
   (s/optional-key :inserted-facts) [SessionFact]})
```

`ProductionActivity` is used by both `handle-get-session-rule` and `handle-get-session-query`;
`build-rule-match-index` and `build-query-match-index` share
`explanations->fact-match-data`, so one change covers both.

### Before / after, Case A

```jsonc
// before — two identical rows, id 2 repeated
"matches": [
  {"id": 2, "type": {"name": ":t/item"}, "data": {"?tagged": [...], "?all": [...]}},
  {"id": 2, "type": {"name": ":t/item"}, "data": {"?tagged": [...], "?all": [...]}}
]

// after — one row, one binding set, :data is the fact
"matches": [
  {"fact": {"id": 2, "type": {"name": ":t/item"}, "data": {"tag": "a"}, "is-root": true, ...},
   "bindings": [{"?tagged": [...], "?all": [...]}]}
]
```

### Before / after, Case B

```jsonc
// before — three rows, id 1 repeated, fact value unavailable
"matches": [
  {"id": 1, "type": {"name": ":t/config"}, "data": {"?config": ..., "?item": {"tag": "a"}}},
  {"id": 1, "type": {"name": ":t/config"}, "data": {"?config": ..., "?item": {"tag": "b"}}},
  {"id": 1, "type": {"name": ":t/config"}, "data": {"?config": ..., "?item": {"tag": null}}}
]

// after — one row, three binding sets, nothing lost
"matches": [
  {"fact": {"id": 1, "type": {"name": ":t/config"}, "data": {"name": "c1"}, ...},
   "bindings": [{"?config": ..., "?item": {"tag": "a"}},
                {"?config": ..., "?item": {"tag": "b"}},
                {"?config": ..., "?item": {"tag": null}}]}
]
```

## 4. Server implementation

`explanations->fact-match-data` becomes a group-and-collect over the same comprehension:

1. Walk `(explanation × condition × fact)` as today, producing `[fact-id bindings]` pairs. Facts with
   no `get-fact-id` are dropped, unchanged.
2. Group by fact id. Within a group, `distinct` the pruned bindings — this is exactly what collapses
   Case A, because both rows of a Case-A pair carry the same explanation's bindings.
3. Emit `{:fact (get fact-table id) :bindings [...]}`, dropping ids the fact table cannot describe
   (`keep`, matching how `:inserted-facts` already handles it).

Ordering is part of the contract, since the snapshot is a cache key and responses must be stable
across identical sessions:

- rows sorted by `(:id fact)` — consistent with `sorted-facts`, which already imposes a
  deterministic type-then-value order on ids.
- `:bindings` sorted by `deterministic-fact-str` of the binding map. Explanation order comes out of
  working-memory iteration and is not stable on its own.

`build-used-by-index` walks the same product and already applies `distinct` to the productions it
collects, so it needs no change.

`/v1/session-snapshot` serves the snapshot's `:rule-matches` / `:query-matches` maps verbatim, so it
picks up the new shape with no handler change — but it is a documented response and moves with this.

## 5. UI change

`SessionProductionActivityResponse.matches` becomes `FactMatch[]`. The affected path is
`SessionProductionActivity → SessionActivityBlock → SessionActivityList → SessionActivityRow`, which
today is typed `SessionFact[]` end to end and is shared with `:inserted-facts`.

- `SessionActivityBlock`'s `ActivityCategory` carries a discriminator so a category can hold either
  `SessionFact[]` ("Inserted Facts") or `FactMatch[]` ("Active Matches").
- `SessionActivityList` keys on `item.fact.id` for match categories and `item.id` for fact
  categories — unique in both by construction.
- `SessionActivityRow` renders a match row as the fact (id, type, origins badge, `:data` = the fact
  value, same as anywhere else) plus one expandable block per entry in `:bindings`, labelled by
  ordinal when there is more than one. A single-binding row reads the way it does today.

The row count drops for any rule with repeated matches — the practical effect is a rule whose
`:matches` was hundreds of rows over a much smaller set of facts now lists each fact once with its
activations nested.

## 6. Recommendation — the fatal hydration abort

Do this as well, independently of the shape change.

`each_key_duplicate` is not a warning. Svelte throws it during hydration and the whole page dies —
in the observed case the RHS section had already rendered server-side and was then wiped, so the
page looked broken with no visible cause. SSR does not check keys, so a hard page load renders and
then blanks on hydrate, which is a genuinely confusing failure mode to debug.

Section 3 makes `:id` a real key, and that is the correctness fix. It does not remove the coupling:
about a dozen `{#each … (item.id)}` / `(item.name)` blocks across the UI key on values the server
supplies, and any future server-side regression in any of them takes down a whole page rather than
degrading one list.

Add a small helper — `keyOf(items, fn)` or equivalent — that returns render keys, appending an
occurrence ordinal on collision, and use it in the keyed lists that iterate server-supplied data.
Keys stay stable and keyed reconciliation keeps working in the normal case; a duplicate degrades to
a redundant re-render instead of a page abort. Do not switch these lists to index keys: that trades
a rare crash for permanently worse list-update behaviour.

Pair it with a server-side test asserting `:matches` ids are distinct (§7), so the invariant is
enforced where it is owned and the UI guard stays what it is — a backstop, not the contract.

## 7. Tests

Server:

- Case A fixture: one fact satisfying two conditions of one activation yields one row with one
  binding set.
- Case B fixture: one fact across N activations yields one row with N binding sets, and no
  activation is lost.
- Case A and B combined in one rule: the fact appears once, with the distinct binding sets only.
- `:matches` fact ids are distinct, over every rule and query in a fixture session.
- `:data` on a match row equals `:data` for the same fact id from `/v1/session/facts/:id`.
- Response is byte-identical across two snapshot builds of the same session (ordering stability).
- Queries get the same treatment as rules.

UI:

- A `FactMatch` with several binding sets renders one row with several expandable blocks.
- A category of `SessionFact[]` and a category of `FactMatch[]` render side by side in one block.
- The key helper returns distinct keys for a list containing duplicate ids, and leaves a
  duplicate-free list's keys unchanged.
- Playwright: navigate to a full rule view for a rule with a multi-activation match, both by
  client-side navigation and by direct load, and assert no page errors.

## 8. Work items

1. `explanations->fact-match-data` → group by fact id, distinct + sort bindings, return
   `{:fact :bindings}`.
2. `FactMatch` schema; `ProductionActivity` `:matches` retyped.
3. UI types: `FactMatch`, `SessionProductionActivityResponse.matches`.
4. `ActivityCategory` discriminator; `SessionActivityList` keying; `SessionActivityRow` multi-binding
   rendering.
5. Key helper, applied to the keyed lists over server-supplied data.
6. Tests per §7.
7. Docs: `docs/explorer-graph-api.md` — the `/v1/session/rules/:id` and `/v1/session/queries/:id`
   response shapes, the `/v1/session-snapshot` `rule-matches` / `query-matches` shapes, and the
   `matches` field note that currently records `data` as holding variable bindings.
