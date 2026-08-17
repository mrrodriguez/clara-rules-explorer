# Annotation Generation Pipeline — Concepts & Architecture

This document describes the internal vocabulary, data flow, and conceptual
model of the annotation-**generation** subsystem rooted in
`clara.server.tools.graph.analyze`.  It is meant to be read alongside:

* [`analyze-clj-kondo-notes.md`](analyze-clj-kondo-notes.md) — the clj-kondo
  interaction mechanics (`with-in-str`, stdin pattern, filename semantics).
* [`internal-analysis-models.md`](internal-analysis-models.md) — the Rulebase
  internals (Production records, LHS condition structures, the Rete DAG, and
  static type-compatibility graph construction) that the generated annotations
  feed into.

This document focuses on the **passes** that produce annotations — what they
produce, why, and how the pieces connect.

---

## 1. The Two Entry Points

There are two ways to produce a clj-kondo analysis that feeds into annotation
generation:

| Entry point | When used | Source of Clojure text |
|---|---|---|
| `build-analysis-from-namespaces` | Static / batch analysis of classpath sources | Real `.clj`/`.cljc` files resolved from the classpath |
| `analyze-session-rules` | Session-based analysis where the rulebase is the source of truth | **Synthesized** source: real classpath source (or reconstructed `ns` form) + one synthetic `(def __clara_explorer_rule_N__ (fn [] …))` snippet per rule RHS |

Both converge on a single merged **clj-kondo analysis map** (the same map
shape that `clj-kondo.core/run!` returns), which is then passed to
`generate-annotations-from-analysis`.

### Why synthesize sources for session rules?

The Clara session rulebase is the source of truth for which rules exist — this
includes macro-emitted rules that have no backing source file.  The combined
source (real `ns` + synthetic snippet defs) lets clj-kondo analyze every RHS
form uniformly, and **prune-and-replace** ensures only the snippet region
contributes to graph edges.

### Prune-and-replace

```
┌─────────────────────────────────────────────────────────────────┐
│ Combined source for a rule-owning namespace                     │
│                                                                 │
│  (ns my.rules …)          ← SOURCE REGION (row ≤ offset)        │
│  …                                                              │
│  (defrule my-rule …)      ← defrule emits hook output — DROPPED │
│  …                                                              │
│  ——— offset line ———                                          │
│  (def __clara_explorer_rule_0__ (fn [] (insert! …))) ← SNIPPET  │
│  (def __clara_explorer_rule_1__ (fn [] (retract! …))) ← REGION  │
└─────────────────────────────────────────────────────────────────┘
```

1. **Drop** any `:var-definitions` and `:var-usages` in the source region
   whose `:name` / `:from-var` matches a known session production (rule or
   query).  The rulebase already tells us the rules exist.
2. **Rename** snippet tags (`__clara_explorer_rule_0__` → `my-rule`) so
   downstream graph keys are the true production FQ symbols.

---

## 2. Core Vocabulary

### 2.1 Production

A **production** is a Clara rule or query from the rulebase.  It carries:

| Key | Description |
|---|---|
| `:name` | Fully-qualified rule name as a string (e.g. `"my.ns/my-rule"`) |
| `:ns-name` | Namespace as a symbol (e.g. `my.ns`) |
| `:lhs` | Left-hand side conditions (constrained DSL data) |
| `:rhs` | Right-hand side form — the code body (rules only; queries have none) |
| `:props` | Metadata map — may carry `:clara-rules/insert-types`, `:clara-rules/retract-types`, etc. |

Productions live at `{:keys [productions]}` on the rulebase; queries also live
at `{:keys [query-nodes]}` (each node's `:query` entry is a production-like map).

### 2.2 Boundary Function

A **boundary function** is any Clara var that directly touches working memory
— the boundary where RHS execution meets the Rete network.

```clojure
;; The complete boundary set (see index.clj):
#{clara.rules/insert!
  clara.rules/insert-unconditional!
  clara.rules/insert-all-unconditional!
  clara.rules/insert-all!
  clara.rules.engine/insert-facts!
  clara.rules/insert
  clara.rules/insert-unconditional
  clara.rules/insert-all
  clara.rules/retract!
  clara.rules.engine/rhs-retract-facts!
  clara.rules/retract}
```

**Why do they terminate reachability expansion?**  The call graph traces
*through* helper functions to find boundary calls, but it does not trace
*into* the boundary functions themselves — they are leaf nodes whose argument
forms are the thing we want to inspect.

```clojure
;; Example: what reachability sees
(defn build-and-insert [data]        ;; var-reachability starts here
  (let [fact (->MyFact data)]        ;;   → follows into ->MyFact (record ctor)
    (insert! (assoc fact :extra 1))) ;;   → insert! is a boundary fn → STOP

;; Reachable set from build-and-insert:
;; #{build-and-insert ->MyFact insert!}
```

### 2.3 Direct Inserter / Direct Retractor

A var that **directly** calls a boundary function — i.e., the call graph
has an edge `v → boundary-fn`.

```clojure
;; Direct inserter: my-rule's RHS calls insert! directly
(defrule my-rule
  [?fact <- MyFact]
  =>
  (insert! (->OtherFact (:x ?fact))))

;; Indirect inserter: my-rule → helper → insert!
;; my-rule is NOT a direct inserter; helper IS.
(defrule my-rule
  [?fact <- MyFact]
  =>
  (do-insert (:x ?fact)))

(defn do-insert [x]
  (insert! (->OtherFact x)))
```

Direct inserters are important because they are the **gateway keys** for the
heuristic record-ctor scan (the "inserter type map").  If a rule's RHS calls a
helper, the rule isn't a direct inserter — but the helper's reachable subtree
is still explored via the call graph.

### 2.4 Call Graph

Built from clj-kondo's `:var-usages` vector — a flat list of every
`(caller-var, callee-var)` pair across the entire merged analysis.

```clojure
;; A kondo :var-usage entry (simplified):
{:from my.rules           ;; ns of the calling file
 :from-var my-rule        ;; the def that contains this usage
 :to clara.rules          ;; ns of the callee
 :name insert!            ;; callee's name
 :row 42 :col 5           ;; source position
 :filename "my/rules.clj"}

;; The call graph reduces these to:
;; {my.rules/my-rule #{clara.rules/insert! my.rules/->OtherFact my.rules/helper}
;;  my.rules/helper   #{clara.rules/insert! ...}}
```

**The graph is built once** in `index/build-analysis-index` and shared across
every per-rule pass.

### 2.5 Reachable Set

The **transitive closure** of callees from a starting var, stopping expansion
at boundary fns (boundary fns themselves are included).  Computed by BFS from
the call graph, memoized per index build so every per-rule inference pass
shares the same cache.

```clojure
;; Suppose graph edges: my-rule → helper → (->MyFact, insert!, unrelated-fn)
;; reachable-set(my-rule) = #{my-rule, helper, ->MyFact, insert!}
;;   (unrelated-fn is reached but not a boundary fn — it's included too;
;;    boundary fns just stop expansion, they're still in the set)
```

Used by `var-reachability` to determine: does this rule (or anything it calls)
eventually reach an insert/retract?

### 2.6 Callsite

A **callsite** is one argument form at a boundary call — a single `(insert!
<HERE>)` or `(retract! <HERE>)` — captured at a specific source position,
locals-traced if the argument is a local symbol, and run through the
resolution chain to determine what fact type(s) it produces.

A callsite ends up in one of three statuses:

| Status | Meaning |
|---|---|
| `:resolved` | Exactly one fact type was determined |
| `:resolved-multi` | Multiple fact types (e.g. a cond branch producing different types) |
| `:unresolved` | The chain could not determine the type → handed to `:callsite-resolver-fn` |

Each callsite carries provenance:

```clojure
;; Internal CallsiteEntry shape:
{:source-str "(insert! (->MyFact x))"
 :ns-name-sym my.rules
 :filename "my/rules.clj"
 :status :resolved
 :resolved-types [my_rules.MyFact]       ;; fq class-name symbols
 :constructor-sym my.rules/->MyFact
 :via {:boundary-var-name-sym clara.rules/insert!
       :boundary-in-var my.rules/my-rule}}  ;; optional provenance chain
```

`ViaChain` carries two rule-side keys beyond the constructor chain
(`:boundary-to-constructor-path`):

- **`:boundary-in-var`** — the var the boundary call is written in (the
  boundary's direct caller).  Exact, no graph walk.
- **`:rule-to-boundary-path`** — the shortest call-graph path from the rule var to
  `:boundary-in-var` (both inclusive), omitted when the two are the same var.

Both `:rule-to-boundary-path` and `:boundary-to-constructor-path` are shortest paths through the var-level
call graph, not observed runtime call paths.

### 2.7 Trace / Traced Args

Before any resolution pass, boundary-call argument forms are **traced**:

1. **Read** the argument form from source at kondo's position span
   (`analyze.kondo/read-boundary-args`).
2. If the argument is a **local symbol**, follow kondo's `:local-usages` →
   `:locals` linkage to find its binding's init form, then restart the chain
   on that form (depth-capped at 8).

```clojure
;; Example: local tracing
(defrule my-rule
  [?fact <- MyFact]
  =>
  (let [f (->MyFact (:x ?fact))]      ;; kondo :locals binding: f, init = (->MyFact …)
    (insert! f)))                       ;; kondo :local-usages: f at the arg position
;; trace-boundary-args follows f → its init form (->MyFact (:x ?fact))
;; → resolves as record ctor → MyFact
```

This is how macro-emitted gensyms and local bindings are resolved — kondo
sees through them via its `:local-usages` analysis.

### 2.8 Constructor Resolution Chain

The chain that converts a trace-resolved argument form into fact-type tokens:

```
Argument form
  ├─ Record ctor?  (->X …) or (map->X …)   → resolve in live ns → fq class name
  ├─ Java ctor?    (X. …) (new X …) (X/new) → resolve class → fq class name
  ├─ Local symbol?                          → trace to init form → restart chain
  └─ Otherwise                              → caller's :callsite-resolver-fn → :unresolved
```

This chain is implemented in `analyze.callsite` and `analyze.ctor`.  It only
resolves what it can prove the instance type of — everything else defers to the
caller.

### 2.9 Constructor of Interest

A **caller-declared** constructor that matters for fact-type detection.  The
caller supplies a `:fact-constructors` vector of `{:match-fn :type-resolver-fn}`
specs — these run **before** the generic boundary chain and **own** the
arguments they account for.

```clojure
;; Example: caller says "my-helper is a constructor of interest"
:fact-constructors
[{:match-fn       #(= % 'my.rules/my-helper)
  :type-resolver-fn (fn [ctx]
                      ;; ctx includes :arg-form, :rule, :direction, etc.
                      {:resolved-types [(keyword (:arg-form ctx))]})}]
```

### 2.10 Inserter Type Map (Record-Ctor Scan / Heuristic Fallback)

A **bottom-up, heuristic** index built during Phase 1: for every direct
inserter/retractor, scan its reachable subtree for `->X` / `map->X` record
constructors resolvable to loadable fact types.

```
For each direct-inserter var v:
  reachable = transitive closure from v
  for each callee in reachable:
    for each usage of callee (from by-caller index):
      if usage names a record constructor (->X / map->X):
        resolve to fq class name in live ns
        if type passes fallback filter → add to map
  → {v -> {MyFact {:usage kondo-usage}, OtherFact {:usage kondo-usage}}}
```

**Why is it heuristic?**  Clj-kondo's flat `:var-usages` cannot distinguish an
argument expression at a callsite from an independent call in the same function
body.  A helper that builds both a fact and an unrelated record value for a side
computation will have both constructors in its reachable subtree — the scan
can't tell which one was the insert argument.  This is why the scan is a
**fallback**, applied only when no caller-driven path accounted for an inserter
var's arguments.

### 2.11 Dynamic Type Fallback Resolution

Controls the heuristic scan's behavior:

| Mode | Behavior |
|---|---|
| `:none` | Disable the scan entirely |
| `:rulebase-fact-types-only` (default) | Admit only types that appear on some rule/query LHS (with hierarchy support via the session's `:ancestors-fn`) |
| `:all-resolvable-fact-types` | Admit any resolvable record-ctor type |

### 2.12 Var-Alias / Var-as-Fact

When a fact type **is** a function var — bound on the LHS and invoked in the
RHS:

```clojure
(defrule process-order
  [?order <- Order]            ;; Order is a fact type
  [?processor <- ProcessorFn]  ;; ProcessorFn IS a var — the var-as-fact pattern
  =>
  (insert! (processor ?order))) ;; invoke the aliased var
```

`alias-usage-map` discovers these: for each rule, scan LHS bindings, check if
`(fact-type-spec-fn fact-type)` returns `{:aliases-var v}`, verify the binding
is used in RHS, then inject a **synthetic** `:var-usage` linking the rule to
the aliased var.  The existing reachability then explores the aliased var's
call chain, and any boundary callsites found through that chain carry
`:fact-type` / `:fact-type-spec` context.

---

## 3. The Three Phases

### Phase 0: Preparation (`generate-annotations-from-analysis`)

```
Input: merged kondo analysis + options
  │
  ├─ Build source loader (layered: synthesized sources > classpath)
  ├─ Get rulebase from session
  ├─ Normalize rules-filter
  ├─ Build alias-by-rule (if fact-type-spec-fn)
  ├─ Inject synthetic alias var-usages into analysis
  └─→ Phase 1
```

### Phase 1: Index Build (`index/build-analysis-index`)

Builds **every precomputed view** over the merged analysis exactly once.
Nothing here is rule-specific.

```
Merged kondo analysis
  │
  ├─ :var-usages
  │   ├─ build-graph          → {caller #{callee …}}
  │   ├─ group-by caller       → usages-by-caller
  │   └─ group-by callee       → usages-by-callee
  │
  ├─ :local-usages
  │   └─ group-by [filename name] → local-usages-by-name
  │
  ├─ :locals
  │   └─ index by [filename id]   → locals-by-id
  │
  ├─ From the call graph:
  │   ├─ memoized-reachability     → (fn [v] → reachable-set)
  │   ├─ direct-callers(insert-fns) → direct-inserters
  │   ├─ direct-callers(retract-fns)→ direct-retractors
  │   │
  │   ├─ build-inserter-type-map   → {direct-inserter → {Type {:usage ...}}}
  │   ├─ build-inserter-type-map   → {direct-retractor → {Type {:usage ...}}}
  │   │   (retractor-type-map)
  │   │
  │   └─ build-constructor-callsite-map → {inserter-var → [CtorUsageMatch …]}
  │       (only when :fact-constructors supplied)
  │
  └─ Utility fns (memoized per run):
      ├─ get-source          → (fn [ns-sym filename] → source-str)
      ├─ read-ctor-form      → (fn [ctor-usage get-source] → form)
      └─ resolve-record-type → (fn [ns-sym class-sym] → fq-class-name-sym)
```

The result is the **`AnalysisIndex`** — a single map shared across every
per-rule pass.

### Phase 2: Per-Rule Inference (`infer-annotation-for-var`)

Runs for each production var.  Uses only the precomputed index — never scans
the raw `:var-usages` vector per rule.

```
For each production var v:
  │
  ├─ var-reachability(v)
  │   ├─ reachable = (reachable-set v)
  │   ├─ is-inserter?  = reachable ∩ (insert-fns ∪ direct-inserters) ≠ ∅
  │   └─ is-retractor? = reachable ∩ (retract-fns ∪ direct-retractors) ≠ ∅
  │
  ├─ If is-inserter?:
  │   └─ extract-insert-types(reachable, insert-fns, ctx)
  │       │
  │       ├─ 1. Find boundary usages
  │       │     For each insert-fn, get usages-by-callee, keep those whose
  │       │     caller ∈ reachable and caller ∉ insert-fns
  │       │
  │       ├─ 2. trace-boundary-args (read args from source + locals-trace)
  │       │     → [TracedArg …]
  │       │
  │       ├─ 3. Constructor-of-interest resolution (if :fact-constructors)
  │       │     resolve-constructor-callsites(traced-args, ctor-callsite-map, ctx)
  │       │     → {callsites, resolved-types, owned-arg-idxs}
  │       │     (owned-arg-idxs are removed before step 4)
  │       │
  │       ├─ 4. Boundary chain resolution (remaining args)
  │       │     resolve-boundary-callsites(remaining-args, ctx)
  │       │     → {callsites, resolved-types, resolved-arg-idxs}
  │       │
  │       └─ 5. Heuristic fallback (record-ctor scan)
  │             compute-heuristic-fallback-callsites(…)
  │             Applied only to direct-inserter vars with no handled arguments
  │             → [{callsites labeled :via {:source :record-ctor-scan}} …]
  │
  └─ Assemble annotation:
      {:clara-rules/insert-types [type …]
       :clara-rules/dynamic-insert-types-detected {:callsites […], :resolution …}
       …}
```

### Phase 3: Output

```
Per-rule annotations
  │
  ├─ normalize-annotations (keys → strings, sorted-map)
  └─→ Annotations map ready for sidecar merge / API consumption
```

---

## 4. Data Flow Diagram (End-to-End)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        ENTRY POINTS                                       │
│                                                                          │
│  build-analysis-from-namespaces          analyze-session-rules           │
│  ┌──────────────────────────┐            ┌───────────────────────────┐   │
│  │ Resolve classpath deps   │            │ session-rules-by-ns        │   │
│  │ Run kondo on each .clj   │            │ synthesize-ns-source per ns│   │
│  │ Merge analyses           │            │  → real source + snippets   │   │
│  └──────────┬───────────────┘            │ analyze via kondo stdin     │   │
│             │                            │ prune-and-rename-analysis   │   │
│             │                            └──────────┬────────────────┘   │
│             │                                       │                    │
│             └───────────────┬───────────────────────┘                    │
│                             │                                            │
│                    merged kondo analysis                                  │
└─────────────────────────────┬────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     generate-annotations-from-analysis                    │
│                                                                          │
│  Phase 0 ──── Preparation                                                │
│  Phase 1 ──── build-analysis-index → AnalysisIndex                       │
│  Phase 2 ──── for each rule: infer-annotation-for-var                     │
│  Phase 3 ──── normalize-annotations                                      │
│                                                                          │
│  Output: {rule-name-str → annotation-map}                                │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. The Kondo Tree: Indexes We Build

Clj-kondo returns four relevant vectors in its analysis map.  Each is flat —
a single vector across all analyzed namespaces.

| Kondo key | Shape | What we build from it |
|---|---|---|
| `:var-usages` | `[{:from ns, :from-var sym, :to ns, :name sym, :row n, …}]` | Call graph, usages-by-caller index, usages-by-callee index |
| `:var-definitions` | `[{:ns ns, :name sym, :row n, …}]` | (Used by prune-and-replace) |
| `:locals` | `[{:id n, :name sym, :row n, :end-col n, :filename s, …}]` | locals-by-id index `{[filename id] → binding}` |
| `:local-usages` | `[{:id n, :name sym, :row n, :filename s, …}]` | local-usages-by-name index `{[filename name] → [usage …]}` |

**Why we can't scan `:var-usages` per rule:**  At real-world scale (thousands
of rules × tens of thousands of usages), scanning the full vector per rule is
quadratic.  The by-caller and by-callee indexes reduce per-rule lookups to O(1)
map lookups.

**Why locals are indexed by `[filename id]` / `[filename name]`:**  Kondo's
local `:id` counters restart per analyzed namespace and are not deterministic
across runs.  The `filename` disambiguates collisions in the merged analysis.
The id values are only used for linkage *within* a single analysis map, never
persisted or compared across runs.

---

## 6. Resolution Order & Ownership

The three resolution paths run in strict priority order, and the earlier path
**owns** the arguments it accounts for:

```
Priority 1: Constructor-of-interest (caller-declared)
  │  "Did the caller explicitly register this constructor?"
  │  resolve-constructor-callsites → owned-arg-idxs
  │
  ▼
Priority 2: Boundary chain (automatic)
  │  "Can the generic ctor chain resolve this argument form?"
  │  resolve-boundary-callsites → resolved-arg-idxs
  │  (skips args owned by Priority 1)
  │
  ▼
Priority 3: Heuristic record-ctor scan (fallback)
  │  "Is there a direct-inserter var with no handled arguments?
  │   Did any record ctor in its subtree resolve to a fact type?"
  │  Applied per var (not per arg), labeled :record-ctor-scan
```

An insert is never reported twice.  The `:resolution` key on the dynamic
detection entry reflects whether all argument forms were accounted for:

When Priority 1 owns an argument but its type-resolver returns no type (an
unresolvable constructor), the constructor entry is dropped rather than
emitted twice — but its `:constructor-sym` and `:boundary-to-constructor-path` are carried over
(`:dropped-ctor-provenance`) and merged into the Priority 2 boundary entry for
that argument.  When two dropped constructors own the same argument the
constructor identity is ambiguous, and only the boundary-side keys are
emitted.  Every Priority 2 entry also gains a boundary-side `:via`
(`:boundary-var-name-sym`, `:boundary-in-var`, `:rule-to-boundary-path`) from the boundary
`usage` it already has.

| Resolution | Meaning |
|---|---|
| `:full` | Every boundary arg was resolved |
| `:partial` | Some, but not all, args were resolved |
| `nil` / absent | No boundary calls at all (unusual — implies reachable says inserter but no insert! calls found) |

---

## 7. Where to Find Each Concept

| Concept | File |
|---|---|
| Entry points, prune-and-replace, `extract-insert-types`, `infer-annotation-for-var`, `generate-annotations-from-analysis` | `analyze.clj` |
| Call graph, reachability, direct-inserters, inserter-type-map, constructor-callsite-map, `build-analysis-index` | `analyze/index.clj` |
| Boundary-call argument tracing, locals resolution, `resolve-boundary-callsites`, `resolve-constructor-callsites` | `analyze/callsite.clj` |
| Record/Java constructor recognition, `resolve-record-type` | `analyze/ctor.clj` |
| Source synthesis (`synthesize-ns-source`), namespace reconstruction | `analyze/synth.clj` |
| Reading source forms at kondo positions | `analyze/kondo.clj` |
| Var-alias discovery, `alias-usage-map`, `lhs-var-bindings` | `analyze/alias.clj` |
| Kondo usage helper (`fq-sym`, `var-usage-caller`, `var-usage-callee`) | `analyze/utils.clj` |
| Kondo interaction mechanics (`with-in-str`, stdin pattern) | `docs/analyze-clj-kondo-notes.md` |
| Annotation normalization, sidecar merge | `annotations.clj` |
