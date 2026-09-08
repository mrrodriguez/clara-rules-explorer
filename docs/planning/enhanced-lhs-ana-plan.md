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
- Does not unlock `:bindings`/node-level enrichment — that still needs the
  mapper.

### Option B — Map raw conditions to compiled nodes, read the evaluated accumulator (richer)

Add a dedicated analysis pass (new namespace, e.g.
`clara.server.tools.graph.lhs-nodes`) that:

1. builds reverse adjacency from `:children`,
2. resolves each production to its reachable node set via reverse-BFS from its
   terminal node(s),
3. structurally matches raw LHS conditions to nodes (Section 3.4),
4. serializes accumulator details from the node's evaluated `:accumulator`
   (no `eval`), and can later emit `:bindings` / node ids per condition.

**Pros**

- Uses the engine's own evaluated accumulator — no re-evaluation, no purity
  concern, exact.
- Unlocks the richer per-layer data (`:binding-keys`, `:new-bindings`,
  node ids, compiled expressions) that the user is already interested in.
- The mapping layer is reusable for other condition metadata.

**Cons**

- Substantially more code and test surface.
- Must correctly handle compiler transformations: topological reorder,
  `:exists` expansion, `:or`/DNF, `:not`, non-equality unifications, shared
  nodes, multiple terminal nodes.
- Structural matching is heuristic; edge cases need a lot of fixtures.
- Requires a live compiled session (not a bare hand-built rulebase).

### Option C — Hybrid (recommended)

1. **Now:** implement **Option A** to ship the concrete accumulator fields
   (`:form`, `:some-initial-value?`) and fix the broken representation.
2. **Next:** implement **Option B** as a separate pass, initially to source
   `:some-initial-value?` (and future accumulator fields) from the node, and to
   attach optional per-condition node/binding metadata.

This keeps the API additive: the accumulator object gains keys over time
(`:some-initial-value?` now, later `:node-id`, `:bindings`, …) without a
breaking change.

**Recommendation: Option C.** Option A is cheap and satisfies the immediate
goal; Option B is the right home for the binding-layer work but is too big to
bundle with it blindly.

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
