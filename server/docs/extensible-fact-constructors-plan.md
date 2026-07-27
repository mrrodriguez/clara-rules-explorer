# Plan: extensible "constructors of interest" — transitive fact-type resolution

_Status: proposed._

## Context

The analyzer already traces fact types **through helper functions** — but only when the fact is
built by a **record/Java constructor**. `rule-nested-helper-call` +
`test-static-insert-types` prove the chain `rule → make-document-check-nested →
map->DocumentCheck` resolves to `DocumentCheck`, via `build-inserter-type-map` (analyze.clj:199),
which scans a direct-inserter's whole *reachable subtree* for record constructors.

That machinery is **class-constructor-only**. A builder is recognized solely by
`constructor-fn-name?` (`->X` / `map->X`) **and** `resolve-record-type` requiring the derived
class to actually load. A codebase whose facts are built by a plain function that returns a
value (e.g. a map tagged with metadata, or `(assoc m :some/type t)`) — not a record — is
therefore invisible to the subtree scan and the ctor chain. Such an insert only reaches the
`:callsite-resolver-fn` as the **boundary-call argument** (`(helper …)`), never the helper's
inner constructor call, so its type is lost when the constructor lives one call away.

Concretely, given a fact-builder fn and a helper that uses it, this does **not** resolve today:

```clojure
;; A "constructor of interest": builds a fact value, not a record.
;; (The test suite already ships exactly this as a mock at
;;  clara.server.tools.graph.rules.analyze-test-rules/->fact, line 11.)
(defn ->fact [type data]
  (with-meta data {:type type}))

;; A helper that builds facts via that constructor and returns them (no insert! itself):
(defn make-tagged-facts [ids]
  (mapv (fn [id] (->fact :demo/tagged {:id id})) ids))

;; The rule inserts the helper's output — the constructor is reached transitively:
(defrule rule-ctor-of-interest-via-helper
  [Application (= ?app-id app-id)]
  =>
  (insert-all! (make-tagged-facts [?app-id])))
```

The analyzer sees `insert-all!` with arg `(make-tagged-facts [?app-id])`, finds no record ctor
in the subtree, and hands the bare `(make-tagged-facts …)` arg to the resolver — which cannot
know the type, because the `(->fact :demo/tagged …)` call is inside `make-tagged-facts`.

## Goal

Generalize the existing transitive tracing so the analyzer can be told about **additional
constructors of interest** — functions that act as fact constructors in the insert/retract
chain and may carry fact-type information. Requirements:

- **Type-agnostic.** The analyzer must not assume the fact type is a keyword, a vector, a class,
  or anything else. It only *locates* the constructor callsite; extracting the type from that
  callsite form is entirely the caller's `:callsite-resolver-fn` decision.
- **Fully-qualified matching only.** Constructors are matched against caller-supplied
  **fully-qualified symbols**, never ambiguous shorthands (a bare `->fact` means different vars
  in different namespaces). kondo already resolves this: `(var-usage-callee usage)` =
  `(symbol (:to u) (:name u))`.
- **No re-parsing.** Reuse the clj-kondo analysis and call graph already computed; do not
  re-walk source to rediscover reachability.
- **Feed the resolver the real callsite.** When a constructor of interest is found in the chain,
  call the resolver with the **constructor callsite form** (the nested `(->fact …)`, not the
  `insert!` arg), plus a **`:via` chain** describing how it was reached from the originating
  `insert!` / `retract!`, and the `:direction`.

## Design

### 1. New option: `:fact-constructor-fns`
A set of **fully-qualified constructor var symbols**, e.g.
`#{clara.server.tools.graph.rules.analyze-test-rules/->fact}`. Default `nil`/`#{}` ⇒ behavior
unchanged. Threaded: `generate-annotations-from-analysis` → `build-infer-ctx` →
`infer-annotation-for-var` → `extract-insert-types` → the rhs resolution.

A usage is a constructor-of-interest iff
`(contains? fact-constructor-fns (var-usage-callee usage))`.

### 2. Discover constructor callsites in the chain
Mirror `build-inserter-type-map`'s subtree approach (analyze.clj:199–228), but instead of
resolving a class, keep the *usage* so the callsite form + position + graph path are available:

- Add `build-constructor-callsite-map` → for each direction's `direct-callers` (callers of
  `insert-fns` / `retract-fns`), collect the constructor `:var-usages` whose caller is in that
  inserter's reachable subtree (`precompute-reachability`). Returns
  `{inserter-var -> [ctor-usage …]}`.
- In `extract-insert-types` (analyze.clj:238): when static record `static-types` are **absent**,
  gather constructor usages for `reachable`, resolve each (below), and union their tokens into
  `:resolved-types`. Gate the existing generic boundary-arg resolver deferral: when
  constructor-of-interest callsites are found for this direction they are authoritative for
  those inserts (prevents a duplicate `:unresolved` entry for the bare `(helper …)` arg); the
  boundary-arg path still runs for record/Java ctors and for other dynamic args (`with-meta`
  maps, the `:fact-type-spec-fn` var-alias flow), so `rule-metadata-map-fact` and the alias
  mechanism are preserved.

Direction attribution uses the same reachable-subtree granularity the record-ctor path already
uses (a var reachable from a direct `insert!` caller ⇒ insert chain). Carry over the existing
caveat verbatim: a reachable subtree can include constructors from unrelated branches; callers
suppress false positives with `:clara-rules/no-output-types`.

### 3. Resolve a constructor callsite (analyze/rhs.clj)
Add `resolve-constructor-callsites` (sibling to `resolve-boundary-callsites`, rhs.clj:297). For
each constructor usage:
- **Read the ctor call form** at the usage's `[row col end-row end-col]` via the existing
  `source-text-at` + `read-string` — the same mechanism `read-boundary-args` uses, proven to
  work on plain-defn helpers by the `insert-document-check-helper` fixture. Keep the **whole**
  call form (not `rest`).
- **Build `:via`** — a call-graph path from the direction's boundary fn to the ctor's containing
  var: `[<boundary-fn> <caller-var> … <containing-var> <constructor-fq>]`. Add a small BFS
  `shortest-call-path` over `graph` (reuse the `graph` already in ctx).
- **Call the resolver** via an extended `resolver-context` (rhs.clj:244) with: `:arg-form` = the
  ctor call form, `:constructor` = FQ ctor sym, `:via` = the chain, `:direction`, `:ns-name-sym`
  = `(:from ctor-usage)` (the ns where the constructor call lives), `:filename`, `:rule`.
- **Assemble** a callsite entry carrying `:constructor` and `:via` alongside the existing
  `:source-str` / `:ns-name-sym` / `:filename` / `:status` / `:resolved-types`, folded into the
  same `{:callsites … :resolved-types … :resolution …}` shape.

### 4. Schema + serialize
- `CallsiteResolverContext` (analyze.clj:570): add optional `:constructor s/Symbol`,
  `:via [s/Any]`; document that `:arg-form` may be a constructor callsite form.
- `GenerateAnnotationsOptions` (analyze.clj:584): add optional `:fact-constructor-fns #{s/Symbol}`.
- `serialize.clj` (~line 148 select-keys): include `:constructor` and `:via` in the serialized
  callsite so the HTTP/EDN output is self-describing.

## Files to modify
- Core: `src/clara/server/tools/graph/analyze.clj`,
  `src/clara/server/tools/graph/analyze/rhs.clj`,
  `src/clara/server/tools/graph/serialize.clj`.
- Tests: `test/clara/server/tools/graph/rules/analyze_test_rules.clj` (add the
  `make-tagged-facts` helper + `rule-ctor-of-interest-via-helper` fixture from the Context
  example, reusing the existing mock `->fact` at line 11),
  `test/clara/server/tools/graph/analyze_test.clj` (new deftest driven by
  `:fact-constructor-fns #{…/->fact}` and a `:callsite-resolver-fn` that reads `(second form)`
  as the type).

## Reuse (do not reinvent)
- `build-inserter-type-map` / `precompute-reachability` / `transitive-reachability` /
  `direct-callers` / `var-usage-caller` / `var-usage-callee` (analyze.clj) — subtree discovery
  and FQ matching.
- `source-text-at` / `read-boundary-args` / `resolver-context` / `apply-resolver` (rhs.clj) —
  reading forms at kondo positions and invoking the resolver.

## Verification (test case)
Add the fixture from the Context section and a test asserting:

```clojure
(let [ann (generate-annotations-from-analysis
            {:analysis analysis
             :session-or-rulebase session
             :fact-constructor-fns #{`atr/->fact}
             :callsite-resolver-fn (fn [{:keys [arg-form]}]
                                     (when (and (seq? arg-form)
                                                (= '->fact (some-> arg-form first name symbol)))
                                       {:resolved-types [(second arg-form)]}))})]
  ;; type is promoted:
  (is (= [:demo/tagged]
         (:clara-rules/insert-types (ann/get-annotation ann `atr/rule-ctor-of-interest-via-helper))))
  ;; and the callsite is self-describing about how it was reached:
  (let [cs (-> (ann/get-annotation ann `atr/rule-ctor-of-interest-via-helper)
               :clara-rules/dynamic-insert-types-detected :callsites first)]
    (is (= :resolved (:status cs)))
    (is (= `atr/->fact (:constructor cs)))
    (is (= [:demo/tagged] (:resolved-types cs)))
    (is (= ['clara.rules/insert-all! `atr/make-tagged-facts `atr/->fact] (:via cs)))))
```

Run the existing test suite; confirm no regression in `test-static-insert-types` /
`test-dynamic-insert-types-detected` (record/Java ctor + `with-meta` + alias paths unchanged).

## Notes / caveats
- The analyzer stays type-agnostic: it locates the constructor callsite and hands the form to the
  resolver. Reading `(second form)` as the type is the *resolver's* choice for this constructor
  shape; a different constructor might carry its type in a different position or form.
- A constructor whose fact type is a runtime-conditional local (e.g. bound by `case`) is not
  resolvable from the callsite form alone and remains `:unresolved`.
- Constructors emitted by macros that clj-kondo does not expand are not discoverable without a
  clj-kondo hook (the analyzer only sees what kondo records); that is a separate concern.
