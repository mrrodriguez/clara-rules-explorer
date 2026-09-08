# Enhanced LHS Analysis — Accumulator Details & Node Mapping — Plan

Status: **Planning / not yet implemented.**

Scope: extend the serialized rulebase LHS analysis so accumulator conditions
carry real information about the accumulator (form + `:initial-value`
presence), fix the currently-broken `:accumulator` representation, and lay the
groundwork for richer per-layer data (bindings) sourced from the compiled Rete
network.

---

## 1. Problem

A rulebase production LHS contains accumulator conditions like:

```clojure
{:accumulator (clara.rules.accumulators/all)
 :from {:type clara.server.tools.graph.rules.loan_app_facts.GivenDocument
        :constraints [(= ?app-id app-id)]}
 :result-binding :?docs}
```

The `:accumulator` value is a raw Clojure form — a list for an inline
constructor call (`(clara.rules.accumulators/all)`), or a fully-qualified
symbol for a var holding an accumulator (`(def my-all (acc/all))`).

Today the HTTP/serialized analysis emits this as a **vector of symbols**:

```json
"accumulator": ["clara.rules.accumulators/all"]
```

This is misleading — it looks like a collection of symbols, but it is really
the leftover of a single list form after `serialize/prune-fns` walks it. The
UI doesn't rely on it (it uses `lhs-form`), so the breakage went unnoticed.

We want the serialized accumulator to be a real object:

```json
"accumulator": {
  "form": "(clara.rules.accumulators/all)",
  "some-initial-value?": true
}
```

`some-initial-value?` means `(some? (:initial-value <accumulator-map>))` — the
first useful static property of an accumulator. More properties (and per-layer
`bindings`) are likely to follow, so the design should not paint us into a
corner.

---

## 2. Current serialization flow

- `core.clj` `production-summary` builds the rule/query summary. Its `:lhs`
  entry is:

  ```clojure
  :lhs (-> production :lhs
           (serialize/serialize-lhs p-ns-name known-set)
           serialize/prune-fns)
  ```

- `serialize.clj` `serialize-lhs` maps `serialize-condition` over each raw LHS
  condition.
- `serialize-condition` does a `clojure.walk/prewalk` over the condition. For
  each map node it:
  - serializes `:type` → `TypeReference`,
  - serializes `:constraints` / `:args` → pretty-printed string,
  - leaves everything else (including `:accumulator`) as-is.
- `prune-fns` then replaces any `IFn` values with strings. The list
  `(clara.rules.accumulators/all)` is not a function, so its single symbol
  survives as a sequence and JSON-encodes as `["clara.rules.accumulators/all"]`.

`serialize-lhs-form` (the `lhs-form` string) is unaffected by this plan — it
reconstructs a Clojure source form from the raw condition via
`condition->form`, which already handles accumulators correctly.

---

## 3. Findings from exploring the compiled session

All findings verified against a live session for
`clara.server.tools.graph.rules.loan-doc-rules` (the repo's demo rules).

### 3.1 Raw production LHS keeps the raw accumulator form

`(-> session eng/components :rulebase :productions)` preserves the raw LHS:

```clojure
{:accumulator (clara.rules.accumulators/all)  ; clojure.lang.PersistentList
 :from {:type clara.server.tools.graph.rules.loan_app_facts.GivenDocument
        :constraints [(= ?app-id app-id)]}
 :result-binding :?docs}
```

So the form is available for direct evaluation at serialization time. It is
already symbol-qualified by `clara.rules.dsl/resolve-vars` (e.g.
`acc/all` → `clara.rules.accumulators/all`).

### 3.2 The rulebase already contains the *evaluated* accumulator

`(:rulebase (eng/components session))` has these keys:

```
:activation-group-fn
:activation-group-sort-fn
:alpha-roots
:beta-roots
:get-alphas-fn
:id-to-node
:node-expr-fn-lookup
:production-nodes
:productions
:query-nodes
```

Relevant node records (`clara.rules.engine`):

| Record | Fields relevant here |
| --- | --- |
| `AlphaNode` | `:id :env :children :activation :fact-type` |
| `RootJoinNode` | `:id :condition :children :binding-keys` |
| `HashJoinNode` | `:id :condition :children :binding-keys` |
| `ExpressionJoinNode` | `:id :condition :join-filter-fn :children :binding-keys` |
| `NegationNode` / `NegationWithJoinFilterNode` | `:condition [:join-filter-fn] :children :binding-keys` |
| `TestNode` | `:id :env :constraints :test :children` |
| `AccumulateNode` | `:id :accum-condition :accumulator :result-binding :children :binding-keys :new-bindings` |
| `AccumulateWithJoinFilterNode` | `:id :accum-condition :accumulator :join-filter-fn :children :binding-keys :new-bindings` |
| `ProductionNode` | `:id :production :rhs` |
| `QueryNode` | `:id :query :param-keys` |

For an `AccumulateNode`, the `:accumulator` field is the **already-evaluated**
`clara.rules.engine.Accumulator` record:

```clojure
#clara.rules.engine.Accumulator{
  :initial-value []
  :retract-fn #function[...]
  :reduce-fn #function[...]
  :combine-fn nil
  :convert-return-fn #function[clojure.core/identity]}
```

while `:accum-condition` keeps the raw-ish form:

```clojure
{:accumulator (clara.rules.accumulators/all)
 :from {:type clara.server.tools.graph.rules.loan_app_facts.GivenDocument
        :constraints [(= ?app-id app-id)]}}
```

and `:result-binding`, `:binding-keys`, `:new-bindings` sit alongside.

So: **if we can map a raw LHS accumulator condition back to its
`AccumulateNode`, we don't need to `eval` the accumulator form at all** — we
read `:initial-value` straight off the node.

### 3.3 `:node-expr-fn-lookup` holds compiled expressions

`(:node-expr-fn-lookup rulebase)` is a `ham_fisted.PersistentHashMap` keyed by
`[node-id expr-key]` where `expr-key` is one of:

```
:alpha-expr :accum-expr :test-expr :join-filter-expr :action-expr
```

Each value is `[compiled-fn compilation-ctx]`. The ctx retains the generated
form and provenance, e.g. for accumulator node 4:

```clojure
[4 :accum-expr] =>
[#function[...AccN-AccE...]
 {:accum-expr (clojure.core/fn AccN-AccE [?__env__]
                (clara.rules.accumulators/all))
  :cache-key [...]}]
```

Alpha-expr ctxs show the bindings the alpha node computes (destructured fact
fields plus `:?binding` map). This is useful for future per-layer binding
analysis, but is **not required** for the current `:some-initial-value?` goal
because `AccumulateNode` already carries the evaluated accumulator.

### 3.4 Mapping raw LHS conditions → compiled nodes is the hard part

The compiler does not retain a direct "raw condition index → node id" map. The
raw `:lhs` order and the compiled node order differ. For
`collect-doc-meta`, raw order is:

1. `Application`
2. accumulator over `GivenDocument`
3. `:extracted-doc-meta`

but the compiled path from `:beta-roots` is:

```
RootJoinNode(2, Application)
  → HashJoinNode(3, :extracted-doc-meta)
    → AccumulateNode(4, (all) over GivenDocument)
      → ProductionNode(5, collect-doc-meta)
```

i.e. the compiler's `sort-conditions` defers accumulators until non-accumulator
conditions are satisfied, so `:extracted-doc-meta` moved ahead of the
accumulator.

Other complications for a general mapper:

- **`:exists` expansion** — `clara.rules.compiler/extract-exists` rewrites an
  `:exists` condition into an `AccumulateNode` (`(clara.rules.accumulators/exists)`)
  **plus** a `TestNode`. A raw `:exists` therefore maps to two nodes.
- **`:or` / DNF** — `to-dnf` can split one production into multiple beta paths
  (and possibly multiple terminal nodes). A raw `:or` condition can map to
  multiple nodes across several paths.
- **`:not`** — negation conditions become `NegationNode`s with their own
  condition shape; nested conditions are inside the node, not separate nodes.
- **Non-equality unifications** — when a condition has constraints that aren't
  pure equality joins, the compiler moves those constraints into a
  `:join-filter-expr` and the node's `:condition` keeps only the equality
  subset (with `:original-constraints` retained). A raw condition may thus not
  be textually equal to the node's `:condition`.

None of that makes mapping impossible, but a *fully general* mapper is a
substantial piece of work.

A practical, restricted mapper is feasible today:

1. Reconstruct reverse adjacency from each node's `:children`
   (`id -> set of parent ids`).
2. For a target production, find its terminal `ProductionNode`(s) by
   `(:production :name)` and reverse-BFS from them to collect the set of nodes
   that can reach that production.
3. Match raw conditions against those nodes structurally:
   - fact/test leaf → join/root/test node by `:type` + `:fact-binding` +
     `:constraints` (falling back to `:original-constraints` where present);
   - accumulator → `AccumulateNode` by `:accum-condition :accumulator` form +
     `:from` type + `:result-binding`.

This works for the common straight-line / accumulator cases and is unambiguous
within a production because `:result-binding` / `:fact-binding` disambiguate
repeated conditions. It does **not** yet cover `:or`, `:exists`, or complex
`:not` correctly — those need dedicated handling.

### 3.5 Bindings per layer are available once mapped

Once a raw condition is mapped to a node, per-layer binding data is
recoverable:

- join/negation nodes: `:binding-keys` (the join bindings),
- accumulator nodes: `:binding-keys` + `:new-bindings`,
- alpha nodes: `:env` and the `:alpha-expr` ctx form show what the alpha side
  computes,
- `:node-expr-fn-lookup` exposes the compiled expressions themselves.

This is the natural next increment after the mapping layer exists.

### 3.6 Accumulator node types & binding semantics

Both accumulator record types matter, not just `AccumulateNode`:

- `AccumulateNode` — equality-only `:from` constraints.
- `AccumulateWithJoinFilterNode` — created when the `:from` has non-equality
  unifications; adds a `join-filter-fn` field, otherwise the same shape.

Both carry the fields we care about: `:accum-condition`, `:accumulator`
(evaluated), `:result-binding`, `:binding-keys`, and `:new-bindings`. A
condition→node mapper must match both records.

`:binding-keys` and `:new-bindings` are **disjoint**. For DSL accumulators
they partition the `:from` constraint variables:

- `:binding-keys` = `(set/intersection cond-bindings parent-bindings)` —
  variables already bound upstream that this condition joins on.
- `:new-bindings` = `(set/difference constraint-vars parent-bindings)` —
  variables the `:from` constraints introduce for the first time.

Verified live with an accumulator whose `:from` constraints were
`(= ?p id) (= ?other val)` and `?p` was bound upstream:
`{:binding-keys #{:?p} :new-bindings #{:?other}}`, intersection empty.

Only the two accumulator node records retain `:new-bindings` in the compiled
network. `condition-to-node` computes `:new-bindings` for every condition
descriptor, but `compile-node` copies it only into `AccumulateNode` /
`AccumulateWithJoinFilterNode`; join and negation records keep
`:binding-keys` and drop `:new-bindings`, and `TestNode` keeps neither.

The reason `:new-bindings` is persisted only for accumulators is the
initial-value propagation rule: on left-activate (and after full retraction),
the engine emits the accumulator's converted `:initial-value` with no matching
facts **only when `(empty? new-bindings)`**. If the `:from` constraints
introduce new variables, an initial value cannot satisfy them, so nothing is
propagated. So `:new-bindings` is a first-class static property for
accumulator analysis — exactly the detail under focus.

### 3.7 A lightweight per-LHS binding analyzer is feasible

The per-condition picture the user wants — "which `:binding-keys` does it
use" and "which keys are new vs joined" — can be computed directly from a
production's raw `:lhs` by reusing the compiler's own transformation
functions. No beta graph, node ids, or compiled network required.

Public in `clara.rules.compiler`:

- `condition-type`
- `to-dnf`
- `analyze-condition` (per-condition `:bound` / `:unbound` / `:is-accumulator`)
- `sort-conditions`
- `condition-to-node` — returns `:used-bindings`, `:new-bindings`,
  `:join-bindings` (= the compiled `:binding-keys`), and
  `:join-filter-join-bindings`.

Private but small/stable (call via `#'clara.rules.compiler/...` or reimplement
their few lines locally):

- `extract-exists`
- `classify-variables`
- `variables-as-keywords`

The walk mirrors `build-rule-node` / `add-conjunctions` /
`get-condition-bindings`:

1. flatten top-level `:and` groups;
2. `sort-conditions`;
3. walk the sorted conditions carrying `ancestor-bindings` (init `#{}`);
4. per condition: `to-dnf`, split disjunctions, `extract-exists`, then for
   each conjunction call `condition-to-node` with `(:env production)` and the
   current ancestor bindings;
5. accumulate the next ancestor bindings exactly as the compiler does:
   `union(ancestor, (:used-bindings node), result-binding, fact-binding)`.

`condition-to-node` already yields the exact `new` vs `join` split for every
condition type — including join/test/negation nodes where the compiled session
drops `:new-bindings`. For binding metadata this lightweight analyzer is
strictly more informative than reading the compiled network, and it works on a
bare rulebase (no session required).

Caveats: `sc/defn` performs runtime schema validation (fine at analysis time,
optimizable later); depending on private vars is a deliberate coupling to
handle (copy or guard); `:or` / `:exists` / complex `:not` need the same
expansion the compiler does — which the functions above already encapsulate.

---

## 4. Options

### Option A — Eval the raw accumulator form in serialization (minimal)

Add `serialize/serialize-accumulator` (or extend `serialize-condition`) that,
for a condition with `:accumulator`:

1. pretty-prints the raw form via the existing dynamic `*form-printer*`
   (trimmed of the trailing newline), and
2. evaluates the form in the production's namespace
   (`(binding [*ns* (or (some-> prod-ns find-ns) *ns*)] (eval form))`),
   then reports `:some-initial-value?` as `(some? (:initial-value result))`.

Eval failures / non-map results degrade to `:some-initial-value? false`.

**Pros**

- Small, localized change in `serialize.clj` + tests + UI types/render.
- Directly matches the original proposal; no network traversal.
- Handles both inline constructor calls and var accumulator references.
- No dependency on the compiled network shape (works even on a bare rulebase
  as long as the namespaces are loaded).

**Cons**

- `eval` at serialization time is a (small) purity/side-effect concern for
  `->rulebase-analysis`, which is currently documented as pure.
- Accumulator forms that reference macro-local symbols (unresolvable after
  compilation) will fail to eval → false, with no way to know "unknown" vs
  "actually nil initial value".
- Does not unlock `:bindings`/node-level enrichment — that still needs another
  pass.

### Option B — Lightweight per-LHS binding analyzer (preferred for bindings)

Add a small namespace (e.g. `clara.server.tools.graph.lhs-bindings`) that
analyzes a single production's raw `:lhs` using the compiler's own
transformation functions (Section 3.7). It emits, per raw condition / expanded
conjunction:

- `:used-bindings` — every variable the condition references,
- `:join-bindings` — variables already bound upstream that this condition
  joins on (the compiled `:binding-keys`),
- `:new-bindings` — variables this condition introduces for the first time,
- `:result-binding` / `:fact-binding` where present.

This is **not** a compiler reimplementation: it reuses `to-dnf`,
`sort-conditions`, `condition-to-node` (and either calls or reimplements the
small private `extract-exists` / `classify-variables`) to reconstruct exactly
the binding sets the compiler uses — without building a beta graph or
assigning node ids.

**Pros**

- Works from a bare rulebase (no live session / compiled network required).
- Gives the complete new-vs-join picture for **every** condition type; the
  compiled session only persists `:new-bindings` for accumulator nodes.
- Small, focused, testable against `condition-to-node` output.
- Reuses the compiler's own semantics, so it stays correct as clara evolves.

**Cons**

- Depends on some `clara.rules.compiler` internals (public fns + a couple of
  private ones to copy or call via var).
- `sc/defn` schema validation is runtime overhead (fine for analysis-time;
  optimize later if needed).
- `:or` / `:exists` / complex `:not` require the same expansion the compiler
  does — already encapsulated by the reused fns, but needs fixtures.

### Option C — Compiled-node mapper (only for evaluated-accumulator access)

Keep the node-mapping idea from Section 3.4, but narrowly scoped: map
accumulator conditions to `AccumulateNode` / `AccumulateWithJoinFilterNode` to
read the engine's already-evaluated `:accumulator` (and persisted
`:binding-keys` / `:new-bindings`). This complements Option B — it is not a
replacement, because the compiled session drops `:new-bindings` for
non-accumulator nodes.

**Pros**

- No `eval` at serialization time; exact evaluated accumulator.
- Reuses the same reverse-BFS + structural matcher for future node-id exposure.

**Cons**

- Only strictly needed if Option A's `eval` is unacceptable.
- Most mapping complexity (`:or`, `:exists`, shared nodes, non-equality
  unifications) remains, for a narrower payoff once Option B exists.

### Recommended sequencing

1. **Now:** Option A — ship `:form` + `:some-initial-value?` and fix the broken
   `:accumulator` representation.
2. **Next:** Option B — add the per-condition binding analyzer; expose
   `:used-bindings` / `:join-bindings` / `:new-bindings` on serialized LHS
   conditions (additive).
3. **Later / only if needed:** Option C — node mapping for evaluated
   accumulators, replacing Option A's eval if purity becomes a blocker, and
   exposing node ids.

**Recommendation: A now, B next.** B is the right home for the binding-layer
work, and it removes the pressure to make the fragile node mapper carry
binding semantics. C is optional and only for the evaluated-accumulator
question.

---

## 5. Proposed API shape (incremental)

### 5.1 Server (`graph/api.clj`)

Add:

```clojure
(s/defschema AccumulatorInfo
  "Details of an accumulator condition's accumulator form."
  {:form s/Str
   :some-initial-value? s/Bool})
```

Change `LhsCondition`:

```clojure
(s/optional-key :accumulator) AccumulatorInfo
```

(was `s/Any`).

### 5.2 Server (`serialize.clj`)

```clojure
(defn- eval-accumulator-form [form prod-ns] ...)
(defn- serialize-accumulator [form prod-ns]
  {:form (str/trim-newline (*form-printer* form))
   :some-initial-value? (boolean (some? (:initial-value (eval-accumulator-form form prod-ns))))})
```

`serialize-condition`'s `serialize-node` adds:

```clojure
(contains? node :accumulator) (update :accumulator #(serialize-accumulator % prod-ns))
```

### 5.3 UI (`ui/src/lib/types/api.ts`, `LhsCondition.svelte`)

- Replace `accumulator?: string[]` with `accumulator?: AccumulatorInfo`.
- Add `AccumulatorInfo { form: string; 'some-initial-value?': boolean }`.
- Render `leaf.accumulator.form` as the accumulator text and surface the
  initial-value flag (e.g. a small `initial-value` badge).

---

## 6. Tests to update / add

Server:

- `serialize_test.clj` `test-serialize-condition` accumulator case — update to
  expect the new map; use a fully-qualified form so eval succeeds.
- New cases:
  - `(clara.rules.accumulators/all)` → `:some-initial-value? true`.
  - `(clara.rules.accumulators/min :field)` (or `max`) →
    `:some-initial-value? false` (nil initial value).
  - var accumulator (`def my-all (acc/all)`) → `true`.
  - unevaluable form → `false` + form still string.
- Any `graph/api.clj` schema validation fixtures that touch `:accumulator`.

UI:

- `make check` / type-check after the type change.
- Existing LHS condition rendering tests (if any) should be updated to the new
  shape.

---

## 7. Open questions

1. **"unknown" vs "false".** Do we need a third state for unevaluable
   accumulators, or is `false` acceptable for now? The proposed shape only has
   `:some-initial-value?` boolean. A future `:resolution`-style marker could be
   added if needed.
2. **Purity of `->rulebase-analysis`.** If `eval` in serialization is a
   concern, we could move Option B (node-sourced) earlier, or precompute an
   accumulator-eval cache at session load time. Where should the eval cache
   live?
3. **`*form-printer*` trailing newline.** `default-form-printer` emits a
   trailing newline for a single form; plan is to `str/trim-newline`. Confirm
   that's acceptable (it also matters for `lhs-form` joins — not touched here).
4. **Bare rulebase without loaded namespaces.** Should analysis attempt to
   `require` the production ns / accumulator nses before eval, or accept false?
5. **Scope of the node mapper.** Should the future mapper be restricted to
   straight-line + accumulator + simple `:not`, explicitly excluding `:or` /
   `:exists` in v1, and degrade gracefully for the rest?

---

## 8. Environment note

The dev nREPL loads the local `CLARA_HOME` checkout via an Emacs dir-locals
override. During exploration a **stale Clara compiler cache** produced a
spurious `(clara.rules.accumulators/all) is not a valid accumulator` error on
`mk-session`. Resetting `clara.rules.compiler/default-compiler-cache` to a
fresh `(clojure.core.cache/soft-cache-factory {})` cleared it. This is an
environment/tooling caveat, not a code defect, but worth remembering when
`mk-session` misbehaves in the REPL.
