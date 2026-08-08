# Clara Rules Explorer - Rule Annotations

Rule annotations provide metadata about Clara rules to statically construct the Rete dependency graph. They declare what fact types a rule's RHS (Right-Hand Side) inserts or retracts, enabling the Explorer to link rules to the LHS (Left-Hand Side) conditions of downstream rules.

---

## Annotation Structure

Rule annotations support the following qualified keys:

| Key | Type | Description |
|-----|------|-------------|
| `:clara-rules/insert-types` | vector of fact types | Fact types that may be inserted during the rule's RHS execution. See [Type Representation](#type-representation) below. |
| `:clara-rules/retract-types` | vector of fact types | Fact types that may be retracted during the rule's RHS execution. See [Type Representation](#type-representation) below. |
| `:clara-rules/no-output-types` | boolean | Set to `true` to declare that the rule has been manually vetted as a pure side-effect (e.g. logging, API calls) with no downstream fact effects. Suppresses "unlinked rule" warnings. |
| `:clara-rules/notes` | string | Free-form documentation or operational notes about the rule. |
| `:clara-rules/dynamic-insert-types-detected` | map | Captured callsite info when dynamic insertions are detected (see below). |
| `:clara-rules/dynamic-retract-types-detected` | map | Captured callsite info when dynamic retractions are detected (see below). |

---

## Type Representation

Fact types in `*-types` annotation keys (`:clara-rules/insert-types`,
`:clara-rules/retract-types`, `:resolved-types` in callsites) are **raw
fact-type-fn objects** end-to-end in memory: Classes, keywords, strings,
vectors, symbols — whatever the session's pluggable `fact-type-fn` returns.
Display formatting happens only at the serialization boundary (JSON out, EDN
write).

### In EDN (sidecar files, persisted annotations)

A fact type keeps its EDN literal form.  The one workaround is for Classes:
Classes have no EDN literal, so they are written as **Class-name symbols**
(fully qualified or unqualified).

| In-memory fact type | EDN representation                                       |
| ------------------- | -------------------------------------------------------- |
| Class instance      | **symbol** (class-name symbol, qualified or unqualified) |
| String              | string                                                   |
| Keyword             | keyword                                                  |
| Vector              | vector                                                   |
| Symbol (non-class)  | symbol                                                   |

Rules:
- If a type has no EDN literal form, it does not go in a sidecar.
- String literals for class names are not a supported convention — use symbols.

### In memory

Compared keys (`:insert-types`, `:retract-types`) carry **raw objects**
end-to-end; display formatting at the serialization boundary only.
`:fact-instance-derived-types` in dynamic detection maps also carries raw
objects — `serialize-dynamic-detection` converts them to strings at the JSON
boundary.

### Comparison (delta, novelty detection, enrichment coverage)

`serialize/resolve-type` with the rule's namespace:

- **Kind-explicit:** Class → `.getName`; keyword → keeps colon; string →
  `pr-str`-quoted; unresolved symbol → `symbol[...]` wrapper; vector →
  `pr-str` (inner kinds preserved).  Distinct kinds never conflate.
- **Class ↔ class-name-symbol convergence** via two paths: ns-aware import
  resolution, or the fallback that prints a fully-qualified symbol as its
  name (which equals `.getName`).

### Merge dedupe

Same canonicalizer as comparison: `serialize/resolve-type` with the rule's
namespace, derived from the rule-name key at the merge site.  There is **one**
canonicalization for comparison and dedupe alike.

---

## Sources of Annotations

Annotations come in **layers** — one per source — folded together by
`clara.server.tools.graph.annotations.merge/merge-layers`, lowest precedence
first.

### Rule `:props` — the base layer

Annotations can be declared directly in the Clojure source code within the rule's property map:

```clojure
(defrule cold-rule
  "Fires when temperature drops below freezing"
  {:clara-rules/insert-types [my.ns.Cold]
   :clara-rules/notes "Fires alerts downstream"}
  [Temperature (< value 32)]
  =>
  (insert! (->Cold)))
```

`clara.server.tools.graph.annotations.merge/props-layer` reads every production's whole `:props` map off the
compiled rulebase — nothing is filtered.  Folded first (the convention), it
is the base that generated and curated layers add to; a higher layer can
still overrule a props value with `:replace` or a tombstone (below).

### Layer EDN files

Generated and curated annotations live in *layer* files — a map with an
`:id`, free-form `:source` provenance, and the `:annotations` payload keyed
by the rule's fully qualified name:

```edn
{:id :curated
 :source "curated-annos.edn"
 :annotations
 {"my.ns/cold-rule"
  #:clara-rules{:insert-types [my.ns.ColdAlert]}

  "my.ns/logging-rule"
  #:clara-rules{:no-output-types true
                :notes "Pure side-effect rule"}}}
```

Layers are **sparse**: omitting a key means "no opinion" — the lower layer's
value survives.  Layers are read with `read-layer` and written
with `write-layer!`; in-memory layers are first-class (`layer`).

---

## Annotation Merging

`(annotations/merge-layers layers)` folds an ordered sequence of layers —
**lowest precedence first**, so the rightmost layer wins a conflict.  Layer
`:id`s must be distinct.  The merged result carries per-rule, per-key
`:provenance` (which layer(s) claimed each value, or `:derived`), and each
callsite entry records `:from-layer`.

### Default strategies
* **`:clara-rules/insert-types` / `:retract-types`** — **union**, lower layer first, deduplicated.
* **`:clara-rules/notes`** — last declared wins.
* **`:clara-rules/no-output-types`** — **last declared wins**: a higher layer that
  declares the key decides the value, so a curator can say "no, this rule
  *does* produce facts" (`false` wins) as well as the reverse.
* **`:clara-rules/dynamic-*-types-detected`** — **deep merge by `:callsite-id`**:
  a sparse callsite conclusion (id, `:status`, `:resolved-types`, optional
  `:resolution-evidence`) overlays the analyzer's discovery fields without
  restating them.  `:resolution` is always recomputed from the merged
  callsites, never taken from a layer.
* **Unknown keys** — last declared wins, and they are *preserved* through
  every merge.

### Deleting and overruling
An explicit `nil` is a **tombstone** on any key — it erases the value where
mere absence would not:

```edn
{"my.ns/cold-rule"
 #:clara-rules{:dynamic-insert-types-detected nil}}   ; erase the detection map
```

### Custom Merge Control (`:clara-rules/merge-props`)
A layer can control the strategy per key, either at the layer level (a
default for every rule it touches) or on an individual rule entry (which
wins over the layer level).  Keys are the *unqualified* annotation names:

```edn
{:id :curated
 :merge-props {:insert-types :replace}          ; layer-wide default
 :annotations
 {"my.ns/override-rule"
  #:clara-rules{:insert-types [my.ns.NewFactOnly]}

  "my.ns/append-note-rule"
  #:clara-rules{:notes "extra context"
                :merge-props {:notes :append}}}}  ; per-rule override
```

Available strategies: `:union` / `:replace` for type vectors, `:replace` /
`:append` (newline-joined) for notes, `:deep` / `:replace` for detection
maps.  `merge-props` is a directive consumed by the merge — it is never
emitted into the merged output.

### Derivation
After merging, conclusions are derived from the merged evidence (never by
re-consulting the layers): each dimension's `:resolution` is recomputed, and
resolved callsite types are promoted into `:clara-rules/insert-types` /
`:retract-types` — a curated callsite thereby produces a graph edge without
anyone hand-writing a type.  `:type-derivation :additive` (default) unions
authored and callsite-derived types; `:from-callsites` makes the callsite
record authoritative for any dimension that has one.

---

## The Annotations Library

The library is split into a namespace group under
`clara.server.tools.graph.annotations`:

| Namespace | Contents |
|---|---|
| `…graph.annotations` | Rule-name normalization (`normalize-rule-name`, `normalize-annotations`, `get-annotation`) and per-production lookup (`production-annotation`) |
| `…graph.annotations.callsite` | Callsite format and identity: `callsite-id`, `assign-callsite-ids`, `aggregate-resolution` |
| `…graph.annotations.merge` | Layers and merging: `layer`, `read-layer`, `write-layer!`, `props-layer`, `merge-layers`, `derive-conclusions`, `annotations`, `provenance` |
| `…graph.annotations.report` | `unresolved-report` (the curation work list) and `validate-layers` (pure lint) |
| `…graph.annotations.rebase` | `rebase-layer` — remap a layer across a namespace rename |

Layers produced by `clara.server.graph.main --generate-analysis` (and by the
fixture generator, `make regen-fixture`) carry the distinguished id
`:clara.tools.graph.analyze/generated`; nothing in the library privileges
that id — it is a marker for humans and tooling.

**Dangling references.** Only the analyzer *discovers* callsites; every other
layer *annotates* ones that already exist.  A merged callsite entry with no
discovered form (no `:source-str`) is dangling — by default it is
quarantined (`:dangling? true`, excluded from type derivation and the
resolution aggregate, and reported by `unresolved-report` /
`validate-layers`); `:on-dangling :keep` treats it as ordinary and `:drop`
removes it.  A curating layer should carry `:source-str` as a redundant
witness — one line, ignored by the merge whenever a discovered entry
supplies one, and the only context left when the entry dangles.

**Rebasing.** Renaming a namespace dangles every curated callsite in it.
`rebase-layer` remaps rule names, discovery fields, and type tokens across a
known old→new namespace mapping and recomputes callsite ids, so a curated
layer survives the rename without re-confirmation.

---

## Dynamic Call-Site Capture and Resolution

When the rule base analyzer detects call sites to `insert!`, `retract!`, or their variants (like `insert-all!`) whose fact type cannot be determined by static constructor tracing, it captures each callsite and attempts **runtime-guided resolution**. The session rulebase is the source of truth: RHS forms come from the compiled productions, so macro-emitted rules are captured too.

> **Internal architecture:** The full pipeline — entry points, index building,
> resolution chains, heuristic fallbacks — is documented in
> [`server/docs/analyze-pipeline-concepts.md`](analyze-pipeline-concepts.md).

### The resolution chain

For each callsite argument form (see `clara.server.tools.graph.analyze.callsite`):

1. **Record constructors** — `->MyRecord` / `map->MyRecord` heads are resolved in the live namespace of the consuming rule.
2. **Java constructors** — `(MyFact. …)`, `(new MyFact …)`, and `(MyFact/new …)` resolve to the class.
3. **Locals tracing** — a local symbol argument (e.g. a macro gensym) is traced through clj-kondo's `:locals` analysis to its binding's init form, and the chain restarts there.
4. **Everything else defers** — helper calls, `with-meta`, literals/templates, and the var-as-fact pattern are *deliberately not* resolved automatically; they go to the caller-supplied `:callsite-resolver-fn`, then fall back to unresolved capture.

Resolved types are **promoted**: a fully-resolved dynamic insert also appears in `:clara-rules/insert-types` (likewise retracts), so downstream graph linking uses it directly.

### Detection map structure

```edn
{:clara-rules/dynamic-insert-types-detected
 {:callsites
  [{:callsite-id "my.rules:->fact:a3f19c2b:0"
    :source-str "(h/->fact :my-rules/document-check-input data)"
    :ns-name-sym my.rules
    :filename "my/rules.clj"
    :status :full
    :resolved-types [:my-rules/document-check-input]
    :constructor-sym my.helpers/->fact
    :via {:boundary-var-name-sym clara.rules/insert!
          :callstack [{:var-name-sym my.rules/collect-input}
                      {:var-name-sym my.rules/->document-check-input}
                      {:var-name-sym my.helpers/->fact}]}}]
  :resolution :full}}
```

* **`:source-str`** — the exact source text of the argument form at the boundary callsite (locals are *not* inlined here; the resolver receives the traced form separately). **Note:** on constructor-path callsites (those carrying `:constructor-sym`), `:source-str`/`:ns-name-sym`/`:filename` instead describe the *constructor* call form — which may live in a helper namespace and reference helper-locals (e.g. `(->fact :demo/tagged {:id id})`).
* **`:ns-name-sym`** / **`:filename`** — where the callsite was found (may be a helper namespace).
* **`:callsite-id`** — stable identity within the rule+dimension:
  `ns:ctor:hash:ordinal` over the namespace, constructor, and source text
  (see [anno-merging-update-plan.md](anno-merging-update-plan.md) §4.4).
* **`:status`** — `:full` (every type this callsite can produce is known),
  `:partial` (some known, possibly more), or `:none` (nothing known).  The
  analyzer emits only `:full` and `:none`; `:partial` is reachable through
  curation.
* **`:resolved-types`** — present when resolved; the fact-type tokens.
* **`:constructor-sym`** — present when resolved via a `:fact-constructors` spec; the fully-qualified constructor symbol. Its presence also discriminates constructor-path callsites from boundary-path ones (see note on `:source-str` below).
* **`:via`** — present when resolved via a `:fact-constructors` spec; a `ViaChain` tracing how the constructor was reached from the originating `insert!`/`retract!` (see below). On *heuristic* callsites (the record-ctor scan fallback, below), `:via` instead carries `{:source :record-ctor-scan}` with no `:callstack`.
* **`:resolution`** (aggregate) — `:full` when every callsite is `:full`, `:none` when every callsite is `:none`, `:partial` otherwise. Heuristic scan callsites count as resolved; check `:via :source` to distinguish their confidence.

### Heuristic record-ctor scan fallback

For boundary arguments the resolution chain above cannot explain (e.g. a
record built inside a helper and returned through an opaque call), the
analyzer runs a **fallback scan**: record constructors (`->X` / `map->X`)
found anywhere in a direct inserter var's reachable subtree, resolved against
the live runtime. This is weaker evidence than the chain — clj-kondo's flat
`:var-usages` cannot tell an argument expression apart from an unrelated call
in the same body — so it is deliberately subordinate:

* **Caller-driven resolution always wins.** The scan never displaces the
  constructor-of-interest or boundary-chain paths. It is applied per
  direct-inserter var, and only for vars whose boundary arguments no
  caller-driven path accounted for. (Note the granularity: multiple insert
  sites in the *same* var share that var's verdict.)
* **Labeled, never silent.** Fallback types are promoted to
  `:clara-rules/insert-types`/`:retract-types` *and* emitted as callsites
  carrying `:via {:source :record-ctor-scan}` (with the ctor name as
  `:source-str` and the inserter's boundary fn as `:boundary-var-name-sym`
  when known), so consumers can filter or down-weight them.
* **Scoped by `:dynamic-type-fallback-resolution`** (option to
  `generate-annotations-from-analysis`):
  * `:rulebase-fact-types-only` (**default**) — a scanned type is credited
    only when it, **or any of its ancestors via the session's
    `:ancestors-fn`**, appears on the LHS of some rule/query production in
    the session. LHS matching is hierarchical, so a subtype inserted behind a
    helper still links to a supertype condition. Third-party records that
    merely pass through the subtree (validation helpers, schema-library
    internals) are dropped.
  * `:none` — the scan never runs; annotations come only from caller-driven
    resolution.
  * `:all-resolvable-fact-types` — any resolvable record-ctor type is
    credited (the pre-fix recall, minus the precedence bug).
* **Traceable skips.** Types rejected by the default filter are reported via
  `tap>` (inert until a consumer calls `add-tap`):

  ```clojure
  {:event :clara-rules/type-fallback-skipped
   :boundary :insert                    ; or :retract
   :mode :rulebase-fact-types-only
   :inserter-var my.ns/helper          ; direct inserter var owning the subtree
   :skipped-type malli.core.Tag        ; the rejected fq class-name symbol
   :ctor-ns malli.core :ctor-name ->Tag
   :filename "malli/core.cljc" :row 123 :col 45}
  ```

Java constructors are out of scan scope by design: they are not vars, and the
boundary chain already resolves them precisely at the callsite.

### `:callsite-resolver-fn`

`generate-annotations-from-analysis` accepts `:callsite-resolver-fn` — an escape hatch invoked once per argument form the automatic chain cannot resolve. It receives:

| Key | Description |
|-----|-------------|
| `:rule` | the full production map of the consuming rule (`:name :ns-name :lhs :rhs :props` …) |
| `:ns-name-sym` | namespace where the callsite was found (may be a helper ns) |
| `:direction` | `:insert` or `:retract` |
| `:boundary-fn` | e.g. `clara.rules/insert!` |
| `:arg-form` | the argument form, with locals already traced to their init forms |
| `:source-str` | `pr-str` of `:arg-form` |
| `:filename` | file containing the callsite |

Return `nil` (still unresolved) or `{:resolved-types [tokens…]}`. Tokens may be Classes, symbols, keywords, or any fact-type shape your session uses; they pass through to the annotation. Exceptions are contained — logged and treated as unresolved.

Example — resolving the var-as-fact pattern (`(insert! (var my-fact-fn))`):

```clojure
(defn var-fact-resolver
  [{:keys [arg-form ns-name-sym]}]
  (when (and (seq? arg-form)
             (= 'var (first arg-form))
             (symbol? (second arg-form)))
    (when-let [v (ns-resolve (the-ns ns-name-sym) (second arg-form))]
      (when-let [t (:type (meta v))]
        {:resolved-types [t]}))))

(analyze/generate-annotations-from-analysis
 {:analysis analysis
  :session-or-rulebase my-session
  :callsite-resolver-fn var-fact-resolver})
```

### `:fact-type-spec-fn` — var-alias chains

The **function-as-fact** (var-as-fact) pattern: a fact *is* a function var
(e.g. a macro emitting `(insert! (var the-fn))` with the fact type on the
var's `:type` meta), matched downstream by `[?f <- :the-type]` and invoked as
a fn in the RHS. Nothing about this is hardcoded; the caller declares the
mapping via `:fact-type-spec-fn`:

```clojure
:fact-type-spec-fn
(fn [fact-type]
  ;; => spec map, or nil when the fact type follows no special pattern.
  ;; Currently one key (the spec is open for extension):
  {:aliases-var fully.qualified/var-name})
```

**Mechanism** (per rule, when a spec fn is supplied):

1. The rule's `:lhs` is scanned for bound fact variables — `:fact-binding` on fact conditions and
   `:result-binding` on accumulator conditions.
2. When `(fact-type-spec-fn t)` returns `{:aliases-var v}` for a bound type *and* the binding is
   used in the RHS (detected via the rule's snippet var-usages), a synthetic var-usage links the
   rule to `v`, so the existing reachability explores `v`'s whole call chain for boundary fns. (If
   `v` is invisible to clj-kondo — macro-emitted, unhooked — its chain is empty; that is the caller
   `:config-dir` situation.)
3. Callsites discovered *through* an alias chain **bypass the constructor chain**: they are recorded
   `:status :none` (unresolved) with the alias context attached — `:fact-type` (the LHS-bound type) and
   `:fact-type-spec` (the spec map) on both the callsite entry and the `:callsite-resolver-fn`
   context — and are never automatically resolved. The resolver decides.

```clojure
(analyze/generate-annotations-from-analysis
 {:analysis analysis
  :session-or-rulebase my-session
  :fact-type-spec-fn (fn [t]
                       (when (= t :extract-doc-meta)
                         {:aliases-var 'my.rules/extract-doc-meta}))
  :callsite-resolver-fn my-resolver})
```

The *producing* side (the rule inserting the var itself) is unaffected by the
spec fn — its `(var the-fn)` callsite is a plain resolver-fn concern and
carries no alias context.

### `:fact-constructors` — extensible constructor tracing

When a codebase builds facts through plain functions rather than record/Java
constructors (e.g. `with-meta`-tagged maps via a shared `->fact` builder),
those functions are invisible to the automatic constructor-resolution chain
because the chain only recognizes `->X` / `map->X` / `Class.` patterns.
`:fact-constructors` lets callers declare their own constructors of interest
as a **vector of specs**:

```clojure
:fact-constructors [{:match-fn         (fn [fq-var-sym] -> truthy/nil)
                     :type-resolver-fn (fn [ctx] -> nil or {:resolved-types [token …]})}
                    …]
```

- **`:match-fn`** — the predicate that identifies a constructor of interest
  by its fully-qualified symbol (e.g. `my.helpers/->fact`). kondo already
  resolves these to FQ symbols, so matching is unambiguous.
- **`:type-resolver-fn`** — called once per discovered constructor callsite
  in the reachable subtree. Receives a `ConstructorTypeResolverContext`
  (table below).

When several specs could match the same callee, the **first matching spec in
vector order wins** — vector order is precedence. Each spec must have both
keys; `generate-annotations-from-analysis` validates its options map against
`GenerateAnnotationsOptions` (`s/validate`) at entry, so a malformed spec
fails fast.

| Key | Description |
|---|---|
| `:constructor-sym` | FQ symbol of the matched constructor var |
| `:arg-form` | The full constructor callsite form read from source (e.g. `(->fact :my-type data)`) |
| `:ns-name-sym` | Namespace where the constructor callsite was found |
| `:filename` | Source file |
| `:direction` | `:insert` or `:retract` |
| `:rule` | Full rule production map |
| `:via` | Optional `ViaChain` — provenance from the boundary fn to this constructor callsite |

#### `ViaChain`

The `:via` chain traces how a constructor was reached from the originating
boundary call:

```clojure
{:boundary-var-name-sym clara.rules/insert!
 :callstack
 [{:var-name-sym my.rules/collect-app-doc-check-input}
  {:var-name-sym my.rules/->document-check-input}
  {:var-name-sym my.helpers/->fact}]}
```

- **`:boundary-var-name-sym`** — the `insert!`/`retract!` variant.
- **`:callstack`** — BFS shortest-path through the call graph from the boundary's
direct caller to the constructor's containing var, then the constructor itself.
Entries are maps (`{:var-name-sym …}`) so future extensions (arity, filename,
row/col) don't require a breaking change.

#### Example

Given a `->fact` constructor in `helpers.clj`:

```clojure
;; helpers.clj
(defn ->fact [fact-type fact-data]
  (with-meta fact-data {:type fact-type}))
```

Used transitively through a helper:

```clojure
;; my_rules.clj
(defn ->document-check-input [data]
  (h/->fact :loan-doc-rules/document-check-input data))

(r/defrule collect-app-doc-check-input
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (->document-check-input {...})))
```

The analyzer is told about `->fact`:

```clojure
(analyze/generate-annotations-from-analysis
 {:analysis analysis
  :session-or-rulebase my-session
  :fact-constructors
  [{:match-fn (fn [sym] (= 'my.helpers/->fact sym))
    :type-resolver-fn (fn [{:keys [arg-form]}]
                        {:resolved-types [(second arg-form)]})}]})
```

The analyzer traces through `->document-check-input` to find the `(h/->fact …)`
callsite, reads `:loan-doc-rules/document-check-input` as the type, and promotes
it into `:clara-rules/insert-types`. The callsite entry carries `:constructor-sym`
and a `:via` chain showing the full provenance.

**Validation**: options are checked against `GenerateAnnotationsOptions`
with `s/validate` at function entry — a spec missing `:match-fn` or
`:type-resolver-fn` throws.

#### Nested constructors

When constructor calls are nested — `(insert! (->fact :a (->fact :b)))` —
*each* matched constructor claims the insert and all of their types are
promoted. This is deliberate: the analyzer favors promoting possibly-too-many
insert-types over missing one entirely (a false positive links a rule to a
downstream fact type it may not actually produce; a false negative silently
breaks the dependency graph).

#### Precedence over `:callsite-resolver-fn`

Constructor resolution is the **more specific** mechanism, so it runs first and
wins. Both hooks can safely be supplied together — a boundary call is never
reported twice.

A boundary argument is **owned** by the constructor path when it is shown to reach
a constructor that resolved. Ownership is decided per boundary argument (not per
rule), because one rule may mix a constructor insert with an unrelated one. Three
ways an argument reaches a constructor, each matching how the argument is written:

| The argument is… | Example | How it is matched |
|---|---|---|
| the constructor call itself | `(insert! (->fact :t m))`, `(insert-all! (mapv #(->fact :t %) xs))` | the constructor's source span is inside the boundary call's |
| a call that leads to it | `(insert! (my-middle-fn args))` where `my-middle-fn` calls `->fact` | a call written inside the boundary call names a link on the constructor's `:via` callstack — at any depth |
| a local bound to it | `(let [f (->fact :t m)] (insert! f))` | the locals-traced form equals the resolved constructor call form |

All three are needed, and none subsumes the others. The call graph is
`{caller-var -> #{callee-vars}}`, so it cannot say which of a rule's several
`insert!` calls reached a constructor — that needs the source spans clj-kondo puts
on every var-usage (the same data `read-boundary-args` uses to read the arguments).
And a bare local names nothing at all, so only the traced form joins it back.

Rule 1 matches the constructor by *usage identity*, never by name — so a rule
containing two separate `->fact` calls does not attribute both to whichever insert
happens to contain one of them.

An owned argument is skipped: `:callsite-resolver-fn` is never invoked for it, and
the constructor entry (with `:constructor-sym` and `:via`) is the only entry
emitted for that insert. Every other boundary argument reaches
`:callsite-resolver-fn` normally — `with-meta` maps, literals, var-as-fact —
*including in the same rule* as a constructor insert, and including when it stays
unresolved. An insert nobody can explain is reported with `:status :none` rather than
dropped, so `:resolution` stays honest.

### A constructor is only an insert if an insert reaches it

A constructor call that **no** boundary argument reaches is not emitted and its
type is not promoted:

```clojure
(let [f (->fact :x m)]          ; called, but never inserted
  (insert! (something-else)))
;; => insert-types nil, resolution :none
;;    one :unresolved callsite for (something-else)
```

This is what keeps the reachable-subtree scan from claiming types a rule never
emits — the scan finds *candidates*, and ownership decides which are real. A
constructor the resolver cannot type is likewise not emitted; the argument falls
through to the boundary path instead of being reported twice.

The trade-off is recall: if an insert reaches a constructor by indirection none of
the three routes can see — say `(insert-all! (apply f args))`, where the
constructor-reaching fn is never named in the argument — the type is dropped rather
than guessed. The boundary callsite then remains `:unresolved`, which is the honest
signal, and the caller can annotate `:clara-rules/insert-types` explicitly.

Record/Java constructors and the `:fact-type-spec-fn` var-alias flow are unaffected.
Alias-discovered callsites are deliberately never auto-resolved, so ownership never
claims them either.

---

## Usage Workflows

There are two paths to generate annotations and analysis, depending on whether you already have a REPL running with a live Clara session. **A session (or rulebase) is always required** — it is the source of truth for which rules exist, including macro-emitted ones.

### Path A — REPL with a live session (preferred when a REPL is already up)

If you're already in a JVM REPL with a live Clara session, inject the explorer library at runtime with `add-libs` (requires Clojure 1.12+). This avoids classpath issues — your REPL already has all rule constructs, custom fact types, and deserialization logic loaded that the standalone CLI would need you to manage separately.

#### 1. Inject the explorer library

```clojure
(require '[clojure.repl.deps :as deps])

;; Local checkout:
(deps/add-libs '{mrrodriguez/clara-rules-explorer-server
                 {:local/root "/path/to/clara-rules-explorer/server"}})

;; Or from git:
(deps/add-libs '{io.github.mrrodriguez/clara-rules-explorer
                 {:git/url "https://github.com/mrrodriguez/clara-rules-explorer"
                  :sha "<git-commit-sha>"
                  :deps/root "server"}})
```

#### 2. Generate annotations from a live session

Auto-discover namespaces from the session and generate annotations:

```clojure
(require '[clara.server.tools.graph.analyze :as analyze])

(let [analysis    (analyze/analyze-session-rules
                   {:session-or-rulebase my-session
                    :include-ns-prefixes ["my.project.rules"]})
      annotations (analyze/generate-annotations-from-analysis
                   {:analysis analysis
                    :session-or-rulebase my-session})]
  (clojure.pprint/pprint annotations))
```

Rules defined by `eval` in namespaces with no classpath source are handled automatically: `analyze-session-rules` reconstructs an `ns` form from the live namespace and synthesizes source from the session's productions.

#### 3. Generate full static analysis from a live session

To get the same output as `--generate-analysis` (annotations + full rulebase analysis), use `clara.server.tools.graph.core/rulebase-analysis`:

```clojure
(require '[clara.server.tools.graph.analyze :as analyze]
         '[clara.server.tools.graph.core :as core]
         '[clojure.pprint :as pprint])

(let [analysis    (analyze/analyze-session-rules
                   {:session-or-rulebase my-session})
      annotations (analyze/generate-annotations-from-analysis
                   {:analysis analysis
                    :session-or-rulebase my-session})
      full        (core/rulebase-analysis my-session annotations)]
  ;; Inspect interactively:
  (keys full)
  ;; => (:rules :queries :fact-types :nodes :dep-graph :unresolved)

  ;; Write to disk:
  (spit "annotations.edn" (with-out-str (pprint/pprint annotations)))
  (spit "analysis.edn"    (with-out-str (pprint/pprint full))))
```

#### 4. Start the explorer UI from a live session

```clojure
(require '[clara.server.graph.server :as server])

(server/start! {:session my-session :port 9999})
```

---

### Path B — CLI via `-main` (standalone, no REPL needed)

When a REPL isn't available, use the `-main` entry point. **Note:** if your session uses custom rule constructs, custom fact types, or non-Fressian serialization, those dependencies must be on the classpath when invoking `clojure -M`. The REPL path (Path A) avoids this because everything is already loaded in your running process. For a full flags reference, see the [server README](../README.md#cli-entry-point).

#### Generate static analysis dump to disk

Requires a serialized session. Produces `annotations.edn` + `analysis.edn` in the given output directory. Annotations are auto-discovered from the session's rule namespaces via clj-kondo, with the session rulebase as the source of truth for rules:

```bash
# Auto-discover from session (sources must be on classpath)
clojure -M -m clara.server.graph.main --generate-analysis out \
  -s session.bin

# With a custom session loader
clojure -M -m clara.server.graph.main --generate-analysis out \
  -s session.bin --load-session-state-fn my.app/load-session
```

Output:
```
out/
├── annotations.edn   # Auto-generated sidecar annotations
└── analysis.edn      # Full rulebase-analysis (rules, queries, fact-types, dep-graph, unresolved)
```
