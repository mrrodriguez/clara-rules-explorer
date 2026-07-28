# Plan: extensible "constructors of interest" — transitive fact-type resolution

_Status: implemented._

> **Update (gen-annos refactor):** the paired options described below
> (`:fact-constructor-match-fn` + `:fact-constructor-type-resolver-fn`) were
> superseded by a single **`:fact-constructors` vector** of
> `{:match-fn :type-resolver-fn}` specs (first matching spec in vector order
> wins), validated via `s/validate` against `GenerateAnnotationsOptions`.
> See `rule-annotations.md` for the current API. The design discussion in this
> document still applies.

## Context

The analyzer already traces fact types **through helper functions** — but only when the fact is
built by a **record/Java constructor**. `rule-nested-helper-call` +
`test-static-insert-types` prove the chain `rule → make-document-check-nested →
map->DocumentCheck` resolves to `DocumentCheck`, via `build-inserter-type-map` (analyze.clj:199),
which scans a direct-inserter's whole _reachable subtree_ for record constructors.

That machinery is **class-constructor-only**. A builder is recognized solely by
`constructor-fn-name?` (`->X` / `map->X`) **and** `resolve-record-type` requiring the derived
class to actually load. A codebase whose facts are built by a plain function that returns a
value (e.g. a map tagged with metadata, or `(assoc m :some/type t)`) — not a record — is
therefore invisible to the subtree scan and the ctor chain. Such an insert only reaches the
`:callsite-resolver-fn` as the **boundary-call argument** (`(helper …)`), never the helper's
inner constructor call, so its type is lost when the constructor lives one call away.

Concrete real example from the test suite — `loan-doc-rules/collect-app-doc-check-input`:

```clojure
;; helpers.clj — a "constructor of interest": builds a fact value, not a record.
(defn ->fact [fact-type fact-data]
  (with-meta fact-data {:type fact-type}))

;; loan_doc_rules.clj — a helper that builds facts via that constructor and returns them:
(defn ->document-check-input [data]
  (h/->fact :loan-doc-rules/document-check-input data))

;; The rule inserts the helper's output — the constructor is reached transitively:
(r/defrule collect-app-doc-check-input
  [Application (= ?app-id app-id)]
  [AllGivenDocuments (= ?app-id app-id) (= ?given-docs docs)]
  [AllRequiredDocuments (= ?app-id app-id) (= ?required-docs docs)]
  =>
  (r/insert! (->document-check-input {...})))
```

The analyzer sees `insert!` with arg `(->document-check-input …)`, finds no record ctor
in the subtree, and hands the bare `(->document-check-input …)` arg to the resolver — which
cannot know the type, because the `(h/->fact :loan-doc-rules/document-check-input …)` call
is inside `->document-check-input`. Today, this rule's annotation is **fabricated by hand**
in `loan-doc-rules-annotations.edn`, with the type hard-coded and a note acknowledging the
gap. This plan removes that fabrication.

## Goal

Generalize the existing transitive tracing so the analyzer can be told about **additional
constructors of interest** — functions that act as fact constructors in the insert/retract
chain and may carry fact-type information. Requirements:

- **Type-agnostic.** The analyzer must not assume the fact type is a keyword, a vector, a class,
  or anything else. It only _locates_ the constructor callsite; extracting the type from that
  callsite form is entirely the caller's decision.
- **Fully-qualified matching only.** Constructors are matched against caller-supplied
  **fully-qualified symbols**, never ambiguous shorthands (a bare `->fact` means different vars
  in different namespaces). kondo already resolves this: `(var-usage-callee usage)` =
  `(symbol (:to u) (:name u))`.
- **No re-parsing.** Reuse the clj-kondo analysis and call graph already computed; do not
  re-walk source to rediscover reachability.
- **Dedicated resolver.** A new `:fact-constructor-type-resolver-fn` handles constructor
  callsites. The existing `:callsite-resolver-fn` is **not** overloaded for this purpose;
  it remains focused on ad hoc boundary-call resolution (metadata maps, var-as-fact, literals,
  unresolvable helper calls).
- **Optional `:via` chain.** The resolver receives a path showing how the constructor was
  reached from the originating `insert!`/`retract!`. See §5 for tradeoffs.

## Design

### 1. New option: `:fact-constructor-match-fn`

A function `(fn [var-sym] -> truthy/nil)` that decides whether a fully-qualified var symbol
names a constructor of interest. For example:

```clojure
(fn [sym]
  (#{'clara.server.tools.graph.rules.helpers/->fact} sym))
```

When nil/false, the symbol is ignored. When truthy, the usage proceeds to resolution via
`:fact-constructor-type-resolver-fn` (§3). Default nil ⇒ behavior unchanged.

This is a function rather than a static set so callers can match on any property of the
symbol: name, namespace, metadata, naming convention, etc.

Mirrors the naming pattern of `:fact-constructor-type-resolver-fn` — both are
`:fact-constructor-*` functions.

Threaded: `generate-annotations-from-analysis` → `build-infer-ctx` →
`extract-insert-types` → the rhs resolution.

### 2. New option: `:fact-constructor-type-resolver-fn`

A single function `(fn [ctx] -> nil or {:resolved-types [token …]})` responsible for
extracting fact types from any matched constructor callsite. Called with a
`ConstructorTypeResolverContext`:

| Key | Description |
|---|---|
| `:constructor-sym` | FQ symbol of the resolved constructor var (e.g. `clara.server.tools.graph.rules.helpers/->fact`) |
| `:arg-form` | The **constructor callsite form** — the full `(->fact :type data)` form read from source — **not** the `(helper …)` boundary arg |
| `:ns-name-sym` | Namespace where the constructor callsite was found |
| `:filename` | Source file |
| `:direction` | `:insert` or `:retract` |
| `:rule` | Full rule production map |
| `:via` | Optional provenance — see §5 |

This function is called **once per discovered constructor callsite** in the reachable subtree.
It is separate from `:callsite-resolver-fn`, which continues to handle boundary-call arg forms
that did **not** match a constructor of interest (metadata maps, opaque builders, literals, etc.).

`:fact-constructor-match-fn` and `:fact-constructor-type-resolver-fn` must be provided together
or both absent. Providing one without the other is a configuration error.

### 3. Discover constructor callsites in the chain

Mirror `build-inserter-type-map`'s subtree approach (analyze.clj:199–228), but instead of
resolving a class, keep the _usage_ so the callsite form + position + graph path are available:

- Add `build-constructor-callsite-map` → for each direction's `direct-callers` (callers of
  `insert-fns` / `retract-fns`), collect the `:var-usages` where
  `(fact-constructor-match-fn (var-usage-callee usage))` is truthy AND the caller is in
  that inserter's reachable subtree (`precompute-reachability`). Returns
  `{inserter-var -> [ctor-usage …]}`.

- In `extract-insert-types` (analyze.clj:238): when static record `static-types` are **absent**,
  gather constructor usages for `reachable`, resolve each (below), and union their tokens into
  `:resolved-types`.

  **Interaction with existing boundary resolution**: when constructor-of-interest callsites are
  found for this direction, they are **authoritative** for those inserts — their resolved types
  are unioned into `:resolved-types`. The existing boundary-arg path (record/Java ctor +
  `:callsite-resolver-fn`) still runs for remaining boundary usages, so `with-meta` maps,
  the `:fact-type-spec-fn` var-alias flow, and unresolvable helper calls are preserved.

  The reachable-subtree granularity carries the same caveat as the record-ctor path: a reachable
  subtree can include constructors from unrelated branches; callers suppress false positives with
  `:clara-rules/no-output-types`.

### 4. Resolve constructor callsites (analyze/rhs.clj)

Add `resolve-constructor-callsites` (new function, sibling to `resolve-boundary-callsites`,
rhs.clj:297). For each constructor usage:

- **Read the ctor call form** at the usage's `[row col end-row end-col]` via the existing
  `source-text-at` + `read-string` — the same mechanism `read-boundary-args` uses, proven to
  work on plain-defn helpers by the `insert-document-check-helper` fixture. Keep the **whole**
  call form (not `rest`).
- **Build `:via`** — provenance from the boundary fn to the constructor (see §5).
- **Call `:fact-constructor-type-resolver-fn`** with the context described in §2.
- **Assemble** a callsite entry carrying `:constructor-sym` and `:via` alongside the existing
  `:source-str` / `:ns-name-sym` / `:filename` / `:status` / `:resolved-types`, folded into the
  same `{:callsites … :resolved-types … :resolution …}` shape.

### 5. The `:via` chain

The `:via` chain traces how a constructor callsite was reached from the originating
`insert!`/`retract!`. It is a map:

```clojure
{:boundary-var-name-sym clara.rules/insert!
 :callstack
 [{:var-name-sym clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input}
  {:var-name-sym clara.server.tools.graph.rules.loan-doc-rules/->document-check-input}
  {:var-name-sym clara.server.tools.graph.rules.helpers/->fact}]}
```

- **`:boundary-var-name-sym`** — the insert!/retract! function being called. This is special: it is
  not part of the call-graph path (the path traces from the boundary's _caller_ to the
  constructor), but it identifies which boundary operation triggered the discovery.
- **`:callstack`** — the call-graph path from the boundary's direct caller through any
  intermediate helpers to the constructor's containing var, then the constructor itself. Each
  entry is a map with `:var-name-sym` (extensible: future entries could carry arity, filename,
  row/col, etc.).

#### Construction

Built by BFS traversal of the call graph we already compute (`build-graph` →
`{caller -> #{callees}}`). The algorithm:

1. The boundary usage gives us the **direct caller** — `(var-usage-caller boundary-usage)`.
   This is the rule var (or helper) that calls `insert!`/`retract!`.
2. The constructor usage gives us the **containing var** — `(var-usage-caller ctor-usage)`.
3. If the direct caller IS the containing var (constructor called directly in the rule's RHS),
   the callstack is `[{:var-name-sym containing-var} {:var-name-sym constructor-fq}]`.
4. Otherwise, run BFS through the call graph from the direct caller to the containing var.
5. Append `{:var-name-sym constructor-fq}` to the callstack.

```clojure
(defn- shortest-call-path
  "BFS from start to end in the call graph.
   Returns [start … end] or nil when unreachable.
   Neighbors are sorted by str for deterministic traversal."
  [graph start end]
  (when (contains? (transitive-reachability graph [start]) end)
    (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [start])
           visited #{start}]
      (when-let [path (peek queue)]
        (let [node (peek path)]
          (if (= node end)
            path
            (let [neighbors (->> (get graph node)
                                 (remove visited)
                                 (sort-by str)
                                 vec)]
              (recur (into (pop queue) (map #(conj path %) neighbors))
                     (into visited neighbors)))))))))
```

#### Determinism & diamond ambiguity

The call graph is `{caller -> #{callees}}` — sets are unordered, so naive BFS would be
non-deterministic. Sorting neighbors by `str` guarantees determinism.

**Diamond case**: a rule calls two helpers that both transitively call the same constructor,
giving two equal-length paths. BFS will pick whichever neighbor sorts first by `str` —
arbitrary but deterministic. This is acceptable because `:via` is about provenance, not
correctness; any valid path provides useful debugging context. The diamond case is noted here
as a known characteristic, not a bug.

#### Complexity

| Factor | Assessment |
|---|---|
| **Implementation cost** | ~20 lines for `shortest-call-path`. The call graph is already built. |
| **Runtime cost** | Negligible. Call graphs are 10s–100s of vars; BFS is microseconds. |
| **kondo limitations** | kondo's `:callstack` on `:var-definitions` captures the **analyzer's lexical nesting** (e.g. a `defn` inside another `defn`'s body), not call relationships. It is irrelevant for a `:via` chain. kondo's `:var-usages` have no `:callstack`. Our own BFS on the call graph is necessary. |
| **Serialization impact** | The `:via` map is 3–8 entries per callsite. Negligible in EDN/JSON output. |

### 6. Schema + serialize

- `ViaEntry` (new schema): `{:var-name-sym s/Symbol}`.
- `ViaChain` (new schema): `{:boundary-var-name-sym s/Symbol :callstack [ViaEntry]}`.
- `ConstructorTypeResolverContext` (new schema in analyze.clj): `:constructor-sym s/Symbol`,
  `:arg-form s/Any`, `:ns-name-sym s/Symbol`, `:filename s/Str`, `:direction (s/enum :insert
  :retract)`, `:rule s/Any`, `(s/optional-key :via) ViaChain`.
- `GenerateAnnotationsOptions` (analyze.clj:584): add optional `:fact-constructor-match-fn
  (s/=> s/Any s/Symbol)` and `:fact-constructor-type-resolver-fn (s/=> s/Any
  ConstructorTypeResolverContext)`.
- `serialize.clj` (~line 148 select-keys): include `:constructor-sym` and `:via` in the serialized
  callsite so the HTTP/EDN output is self-describing.

### 7. Remove fabricated annotation

The `loan-doc-rules-annotations.edn` entry for `collect-app-doc-check-input` has been removed
(as of this plan). After implementation, the `collect-app-doc-check-input` rule's annotation
will be resolved by the analyzer with `:fact-constructor-match-fn` and
`:fact-constructor-type-resolver-fn` that reads `(second arg-form)` as the type.

## Files modified (complete)

- Core: `src/clara/server/tools/graph/analyze.clj`,
  `src/clara/server/tools/graph/analyze/rhs.clj`,
  `src/clara/server/tools/graph/serialize.clj`.
- Schemas: `src/clara/server/graph/api.clj` (ViaEntry, ViaChain, DynamicCallsiteEntry extensions).
- Test resources: `test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn`
  (entry for `collect-app-doc-check-input` now reflects `:resolved` via `helpers/->fact`).
- Tests: `test/clara/server/tools/graph/rules/analyze_test_rules.clj` (added
  `rule-ctor-of-interest-via-helper` and `make-tagged-facts` fixtures),
  `test/clara/server/tools/graph/analyze_test.clj` (added
  `test-fact-constructor-resolution`, `test-constructor-options-validation`,
  `test-loan-doc-ctor-resolution`).

## Reuse (do not reinvent)

- `build-inserter-type-map` / `precompute-reachability` / `transitive-reachability` /
  `direct-callers` / `var-usage-caller` / `var-usage-callee` (analyze.clj) — subtree discovery
  and FQ matching.
- `source-text-at` / `read-boundary-args` (rhs.clj) — reading forms at kondo positions.
  `resolver-context` and `apply-resolver` remain for `:callsite-resolver-fn`; the new
  constructor path has its own context builder.

## Verification (test case)

Add the fixture and a test asserting:

```clojure
(let [ann (generate-annotations-from-analysis
            {:analysis analysis
             :session-or-rulebase session
             :fact-constructor-match-fn (fn [sym] (= `atr/->fact sym))
             :fact-constructor-type-resolver-fn
             (fn [{:keys [arg-form constructor-sym via]}]
               (when (= `atr/->fact constructor-sym)
                 {:resolved-types [(second arg-form)]}))})]
  ;; type is promoted:
  (is (= [:demo/tagged]
         (:clara-rules/insert-types
          (ann/get-annotation ann `atr/rule-ctor-of-interest-via-helper))))
  ;; and the callsite is self-describing about how it was reached:
  (let [cs (-> (ann/get-annotation ann `atr/rule-ctor-of-interest-via-helper)
               :clara-rules/dynamic-insert-types-detected :callsites first)]
    (is (= :resolved (:status cs)))
    (is (= `atr/->fact (:constructor-sym cs)))
    (is (= [:demo/tagged] (:resolved-types cs)))
    (let [{:keys [boundary-var-name-sym callstack]} (:via cs)]
      (is (= 'clara.rules/insert! boundary-var-name-sym))
      (is (= [`atr/make-tagged-facts `atr/->fact]
             (mapv :var-name-sym callstack)))))))
```

For the **real** `loan-doc-rules` test, update the existing
`test-dynamic-insert-types-detected` test to pass `:fact-constructor-match-fn` and
`:fact-constructor-type-resolver-fn`, then assert that `collect-app-doc-check-input` now shows
`:resolved` status with type `:loan-doc-rules/document-check-input` (instead of the current
`:unresolved` assertion).

Run the existing test suite; confirm no regression in `test-static-insert-types` /
`test-dynamic-insert-types-detected` (record/Java ctor + `with-meta` + alias paths unchanged).

## Notes / caveats

- The analyzer stays type-agnostic: it locates the constructor callsite and hands the form to the
  resolver. Reading `(second form)` as the type is the _resolver's_ choice for this constructor
  shape; a different constructor might carry its type in a different position or form.
- `:fact-constructor-match-fn` and `:fact-constructor-type-resolver-fn` must be provided together
  (or both nil). Validation should reject providing one without the other.
- The existing `:callsite-resolver-fn` is **not** modified. It continues to handle boundary-call
  args that did not resolve through a constructor of interest, exactly as it does today.
- `:callstack` entries are maps (`{:var-name-sym …}`) rather than bare symbols so future
  extensions (arity, filename, row/col) don't require a breaking schema change.
