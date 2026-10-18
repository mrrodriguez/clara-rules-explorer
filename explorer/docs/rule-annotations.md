# Clara Rules Explorer - Rule Annotations

Rule annotations provide metadata about Clara rules to statically construct the Rete dependency graph. They declare what fact types a rule's RHS (Right-Hand Side) inserts or retracts, enabling the Explorer to link rules to the LHS (Left-Hand Side) conditions of downstream rules.

This document covers the annotation **contract** — the keys, the layer/merge model, and the dynamic callsite vocabulary — with pointers to the implementation for the specifics. The generation *passes* behind it live in
[`analyze-pipeline-concepts.md`](analyze-pipeline-concepts.md); the clj-kondo interaction mechanics are in
[`analyze-clj-kondo-notes.md`](analyze-clj-kondo-notes.md).

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

Fact types in `*-types` keys (`:clara-rules/insert-types`,
`:clara-rules/retract-types`, `:resolved-types` in callsites) are **raw
fact-type-fn objects** end-to-end in memory: Classes, keywords, strings,
vectors, symbols — whatever the session's pluggable `fact-type-fn` returns.
Display formatting happens only at the serialization boundary (JSON out, EDN
write).

### In EDN (sidecar files, persisted annotations)

A fact type keeps its EDN literal form. The one workaround is Classes:
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

### Canonicalization

There is **one** canonicalizer for comparison, novelty detection, and merge
dedupe alike: `clara.explorer.serialize/resolve-type`, applied with the rule's
namespace (derived from the rule-name key at the merge site). Its docstring
spells out the per-kind rules (Class → `.getName`, keyword keeps its colon,
string → `pr-str`-quoted, unresolved symbol → `symbol[...]` wrapper, vector →
`pr-str`), including the two Class ↔ class-name-symbol convergence paths.
Refer there rather than re-deriving them here.

---

## Sources of Annotations

Annotations come in **layers** — one per source — folded together by
`clara.explorer.annotations.merge/merge-layers`, lowest precedence first.

```text
        lowest precedence ────────────────────────────► highest
 ┌──────────────────┐   ┌───────────────────────────┐   ┌──────────────────┐
 │ :props           │   │ generated                 │   │ curated          │
 │ (rule source,    │──▶│ (:clara.explorer.analyze/ │──▶│ (sidecar layer   │
 │  ->props-layer)  │   │  generated id)            │   │  files)          │
 └──────────────────┘   └───────────────────────────┘   └──────────────────┘
                                  │ merge-layers (fold, rightmost wins)
                                  ▼
                       merged annotations + :provenance
                                  │ derive-conclusions
                                  ▼
                  resolved callsite types promoted into
                  :clara-rules/insert-types / :retract-types
```

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

`clara.explorer.annotations.merge/->props-layer` reads every production's whole
`:props` map off the compiled rulebase and folds it as the base layer that
generated and curated layers add to. A higher layer can overrule a props value
with `:replace` or a tombstone (below).

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
value survives. Layers are read via `clara.explorer.annotations.merge/->layer`
and written with `clara.explorer.annotations.merge/write-layer!`.

A layer file is one input to a whole persisted artifact set — the fold beside
it, the analysis over that fold, a provenance manifest. That side of things is
[Persisted artifacts](persisted-artifacts.md); nothing below this line is about
files.

---

## Annotation Merging

`clara.explorer.annotations.merge/merge-layers` folds an ordered sequence of
layers — **lowest precedence first**, so the rightmost layer wins a conflict.
Layer `:id`s must be distinct. The merged result carries per-rule, per-key
`:provenance` (which layer(s) claimed each value, or `:derived`), and each
callsite entry records `:from-layer`.

### Default strategies

| Key | Strategy |
|---|---|
| `:clara-rules/insert-types` / `:retract-types` | **union**, lower layer first, deduplicated |
| `:clara-rules/notes` | last declared wins |
| `:clara-rules/no-output-types` | **last declared wins** (a curator can declare `false` to say "this rule *does* produce facts") |
| `:clara-rules/dynamic-*-types-detected` | **deep merge by `:callsite-id`**; `:resolution` is always recomputed, never taken from a layer |
| unknown keys | last declared wins, preserved through every merge |

The exact mechanics of each fold live in `clara.explorer.annotations.merge/merge-layers`
(and `clara.explorer.annotations.merge/fold-key`); the table above is the curator-facing contract.

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
wins over the layer level). Keys are the *unqualified* annotation names:

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
maps. `merge-props` is a directive consumed by the merge — it is never
emitted into the merged output.

### Derivation

After merging, conclusions are derived from the merged evidence (never by
re-consulting the layers): each dimension's `:resolution` is recomputed, and
resolved callsite types are promoted into `:clara-rules/insert-types` /
`:retract-types` — a curated callsite thereby produces a graph edge without
anyone hand-writing a type. See `clara.explorer.annotations.merge/derive-conclusions`.

`:type-derivation :additive` (default) unions authored and callsite-derived
types; `:from-callsites` makes the callsite record authoritative for any
dimension that has one.

---

## The Annotations Library

The library is split into a namespace group under
`clara.explorer.annotations`:

| Namespace | Contents |
|---|---|
| `clara.explorer.annotations` | Rule-name normalization (`normalize-rule-name`, `normalize-annotations`, `get-annotation`) and per-production lookup (`production-annotation`) |
| `clara.explorer.annotations.callsite` | Callsite format and identity: `callsite-id`, `assign-callsite-ids`, `aggregate-resolution` |
| `clara.explorer.annotations.merge` | Layers and merging: `->layer`, `write-layer!`, `->props-layer`, `merge-layers`, `derive-conclusions`, `annotations`, `provenance` |
| `clara.explorer.annotations.report` | `unresolved-report` (the curation work list) and `validate-layers` (pure lint) |
| `clara.explorer.annotations.rebase` | `rebase-layer` — remap a layer across a namespace rename |

Layers produced by `clara.explorer.server.main --generate-analysis` (and by the
fixture generator, `make regen-fixture`) carry the distinguished id
`:clara.explorer.analyze/generated`; nothing in the library privileges
that id — it is a marker for humans and tooling.

**Dangling references.** Only the analyzer *discovers* callsites; every other
layer *annotates* ones that already exist. A merged callsite entry with no
discovered form (no `:source-str`) is dangling — by default it is
quarantined (`:dangling? true`, excluded from type derivation and the
resolution aggregate, and reported by `unresolved-report` /
`validate-layers`); `:on-dangling :keep` treats it as ordinary and `:drop`
removes it. A curating layer should carry `:source-str` as a redundant
witness — one line, ignored by the merge whenever a discovered entry
supplies one, and the only context left when the entry dangles.

**Rebasing.** Renaming a namespace dangles every curated callsite in it.
`clara.explorer.annotations.rebase/rebase-layer` remaps rule names, discovery
fields, and type tokens across a known old→new namespace mapping and
recomputes callsite ids, so a curated layer survives the rename without
re-confirmation.

---

## Dynamic Call-Site Capture and Resolution

When the analyzer detects callsites to `insert!`, `retract!`, or their variants
(like `insert-all!`) whose fact type cannot be determined by static constructor
tracing, it captures each callsite and attempts **runtime-guided resolution**.
The session rulebase is the source of truth: RHS forms come from the compiled
productions, so macro-emitted rules are captured too.

> **Internal architecture:** the full pipeline — entry points, index building,
> resolution chains, heuristic fallbacks — is documented in
> [`analyze-pipeline-concepts.md`](analyze-pipeline-concepts.md).

### What "simple enough" looks like

If your rule RHS inserts plain Java classes or Clojure record constructors,
it just works — no extra configuration. A tiny custom builder function (like
`(->fact :my-type data)`) needs one `:fact-constructors` spec telling the
analyzer which function is the constructor and where the type lives in its
args (usually `(second arg-form)`); after that, the same simple shapes below
resolve. LHS is omitted for brevity (`...`).

1. **Boundary fn calls the constructor directly.** The easiest case:

   ```clojure
   (defrule my-rule
     ...
     =>
     (insert! (->fact :my-type data)))
   ;; also automatic, no resolver needed:
   ;; (insert! (->MyRecord data))
   ;; (insert! (MyFact. data))
   ```

2. **Boundary fn calls a helper that reaches the constructor.** The `insert!`
is still in the RHS, but the constructor lives one or more helpers down:

   ```clojure
   (defn ->my-fact [data]
     (->fact :my-type data))

   (defrule my-rule
     ...
     =>
     (insert! (->my-fact data)))
   ```

3. **The boundary fn itself lives in a helper.** The RHS never calls
   `insert!` directly; it calls a helper that does:

   ```clojure
   (defn do-insert! [data]
     (insert! (->fact :my-type data)))

   (defrule my-rule
     ...
     =>
     (do-insert! data))
   ```

Locals are followed through their *transitive* closure, so the shallow
variations above — a constructor bound to a local
(`(let [f (->fact :my-type m)] (insert! f))`) and bulk inserts
(`(insert-all! (mapv #(->fact :my-type %) xs))`) — generalize:

4. **A chain of locals.** Each hop resolves within the previous binding's
   init span, all the way back to the constructor (the same chain also
   resolves a plain record/Java ctor with no configuration):

   ```clojure
   (defrule my-rule
     ...
     =>
     (let [f (->fact :my-type m)
           g f
           h g]
       (insert! h)))
   ```

5. **A helper call in a `let` init.** The insert arg is a local whose init
   is a helper call; the constructor lives inside that helper:

   ```clojure
   (defn look-up-facts [m]
     [(->fact :my-type m)])

   (defrule my-rule
     ...
     =>
     (let [facts (look-up-facts m)]
       (insert-all! facts)))
   ```

6. **Seq combinators close over the constructor.** `concat`/`for` bodies
   holding the constructor all count as reached from the one insert:

   ```clojure
   (defrule my-rule
     ...
     =>
     (let [as (for [x xs] (->fact :my-type {:id x}))
           bs (for [y ys] (->fact :my-type {:id y}))]
       (insert-all! (concat as bs))))
   ```

   All of these are decided by *position identity*, never by the text of a
   form — so a same-named local in a branch that never flows into the insert
   still does **not** attribute:

   ```clojure
   (defrule my-rule
     ...
     =>
     (let [f (look-up-facts m)]          ; reaches ->fact :my-type
       (insert-all! f)
       (let [f (->fact :other-type m)]   ; shadowed, never inserted
         (println f))))
   ;; :my-type resolves; :other-type stays dropped
   ```

Anything more indirect — a constructor that is built but never inserted, or
reached only through opaque indirection like `(apply f args)` — stays
unresolved (or needs an explicit `:clara-rules/insert-types`).

### The resolution chain

For each boundary argument form, the automatic chain runs top to bottom
(full contract in the `clara.explorer.analyze.callsite` namespace docstring):

```text
        boundary argument form
                 │
                 ▼
   ┌─ seq? ──────────────────────────────────────► record ctor (->X / map->X)
   │  (head is a constructor)                        Java ctor (X. / new X / X/new)
   │                                                     │ resolved → fq type token
   │
   ├─ symbol? ───────────────────────────────────► locals trace (:let-init only)
   │  (kondo :local-usages :id → :locals binding)      │ params/destructured/loop/seq
   │                                                   │ are NOT traced
   │                                                   ▼
   │                                         recurse on the init form (depth-capped)
   │
   └─ otherwise ───────────────────────────────► :callsite-resolver-fn
         (helper call, with-meta, literal,           │
          var-as-fact — deliberately NOT auto)       ▼
                                          {:resolved-types …} → resolved
                                          nil → :status :none (unresolved)
```

Resolved types are **promoted**: a fully-resolved dynamic insert also appears
in `:clara-rules/insert-types` (likewise retracts), so downstream graph linking
uses it directly.

### Detection map structure

The internal shape is `clara.explorer.analyze.callsite/CallsiteEntry` (and the
aggregate `clara.explorer.analyze.callsite/CallsiteResolution`); see those
schemas for the authoritative field list. A fully-populated example:

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
          :boundary-in-var       my.rules/collect-input
          :boundary-to-constructor-path [{:var-name-sym my.rules/collect-input}
                      {:var-name-sym my.rules/->document-check-input}
                      {:var-name-sym my.helpers/->fact}]}}]
  :resolution :full}}
```

Terminology worth having precise:

- **`:source-str`** — exact source text of the argument form at the boundary
  callsite (locals are *not* inlined here; the resolver receives the traced
  form separately). On constructor-path callsites (those carrying
  `:constructor-sym`), `:source-str`/`:ns-name-sym`/`:filename` instead
  describe the *constructor* call form, which may live in a helper namespace.
- **`:callsite-id`** — stable identity within the rule+dimension; format in
  `clara.explorer.annotations.callsite/callsite-id`.
- **`:status`** / **`:resolution`** — `:full` / `:partial` / `:none`. The
  analyzer emits only `:full` and `:none`; `:partial` is reachable through
  curation. `:resolution` aggregates per-callsite `:status`
  (`clara.explorer.annotations.callsite/aggregate-resolution`).
- **`:constructor-sym`** — present when resolved via a `:fact-constructors`
  spec; its presence discriminates constructor-path callsites from
  boundary-path ones.
- **`:via`** — the `clara.explorer.analyze.callsite/ViaChain` provenance (see
  below).

### Heuristic record-ctor scan fallback

For boundary arguments the chain cannot explain (e.g. a record built inside a
helper and returned through an opaque call), the analyzer runs a **fallback
scan** over a direct inserter var's reachable subtree. This is weaker evidence
than the chain — clj-kondo's flat `:var-usages` cannot tell an argument
expression apart from an unrelated call in the same body — so it is
deliberately subordinate. The mechanics are `clara.explorer.analyze.index/->inserter-type-map`
(build) and `clara.explorer.analyze/extract-insert-types` (attribution); the
contract:

- **Caller-driven resolution always wins.** Applied per direct-inserter var,
  and only for vars whose boundary arguments no caller-driven path accounted
  for.
- **Labeled, never silent.** Fallback types are promoted *and* emitted as
  callsites carrying `:via {:source :record-ctor-scan}`.
- **Scoped by `:dynamic-type-fallback-resolution`** (an option to
  `clara.explorer.analyze/->annotations-from-rule-source-analysis`, schema
  `clara.explorer.analyze/DynamicTypeFallbackResolution`):
  - `:rulebase-fact-types-only` (**default**) — a scanned type is credited
    only when it, **or any of its ancestors via the session's
    `:ancestors-fn`**, appears on the LHS of some rule/query production.
  - `:none` — the scan never runs.
  - `:all-resolvable-fact-types` — any resolvable record-ctor type is credited.
- **Traceable skips.** Rejected types are reported via `tap>`:

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

`clara.explorer.analyze/->annotations-from-rule-source-analysis` accepts
`:callsite-resolver-fn` — an escape hatch invoked once per argument form the
automatic chain cannot resolve. Its context is
`clara.explorer.analyze.callsite/CallsiteResolverContext`; the local binding
shape is `clara.explorer.analyze.callsite/LocalBinding`. Two things are easy to
miss and worth stating here:

- **A `:let-init` binding's `:resolve-local` is scoped to that init span**, so
  a resolver recurses into a returned `:init-form` by calling *that*
  `:resolve-local` — not the callsite-scoped one (which cannot see those
  locals). The whole chain shares one depth budget; once exhausted, lookups
  return nil.
- **Exact-vs-possible types.** A lookup in a literal map, a `case`, or an `if`
  gives the *set* of values a type could be, not the one it is. A resolver that
  enumerates values must report every possible value and leave the callsite
  unresolved if any branch or default is not literal. Over-reporting a member
  the flow can never produce is acceptable; omitting one a resolved callsite
  could produce is not.

Return `nil` (still unresolved) or `{:resolved-types [tokens…]}`. Tokens may
be Classes, symbols, keywords, or any fact-type shape your session uses.
Exceptions are contained — logged and treated as unresolved.

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

(analyze/->annotations-from-rule-source-analysis
 {:rule-source-analysis analysis
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

The mechanism is `clara.explorer.analyze.alias/alias-usage-map`: it scans each
rule's `:lhs` for bound fact variables, and when `(fact-type-spec-fn t)`
returns `{:aliases-var v}` for a bound type *and* the binding is used in the
RHS, it emits a synthetic `:var-usage` linking the rule to `v` — so the
existing reachability explores `v`'s whole call chain for boundary fns.

Callsites discovered *through* an alias chain **bypass the constructor
chain**: they are recorded `:status :none` (unresolved) with the alias context
attached — `:fact-type` and `:fact-type-spec` on both the callsite entry and
the `:callsite-resolver-fn` context — and are never automatically resolved.
The resolver decides.

```clojure
(analyze/->annotations-from-rule-source-analysis
 {:rule-source-analysis analysis
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
constructors, those functions are invisible to the automatic chain (which only
recognizes `->X` / `map->X` / `Class.` patterns). `:fact-constructors` declares
extra constructors as a **vector of specs** — schema
`clara.explorer.analyze/FactConstructorSpec`, whose `:match-fn` is a
`clara.explorer.analyze/ConstructorMatchFn` (usually a set of fully-qualified
symbols) and whose `:type-resolver-fn` receives a
`clara.explorer.analyze.callsite/ConstructorTypeResolverContext`:

```clojure
:fact-constructors [{:match-fn         (fn [fq-var-sym] -> truthy/nil)   ; or #{'my.helpers/->fact}
                     :type-resolver-fn (fn [ctx] -> nil or {:resolved-types [token …]})}
                    …]
```

When several specs could match the same callee, the **first matching spec in
vector order wins** — vector order is precedence. Options are validated against
`clara.explorer.analyze/RuleSourceAnnotationsOptions` at entry, so a malformed
spec fails fast.

#### `ViaChain`

The `:via` chain traces how a constructor was reached from the originating
boundary call, and — when the boundary call is not in the rule's own RHS — how
the rule reaches the var holding that boundary call. Schema:
`clara.explorer.analyze.callsite/ViaChain`.

```clojure
{:boundary-var-name-sym clara.rules/insert!
 :boundary-in-var       my.rules/collect-app-doc-check-input
 :boundary-to-constructor-path
 [{:var-name-sym my.rules/collect-app-doc-check-input}
  {:var-name-sym my.rules/->document-check-input}
  {:var-name-sym my.helpers/->fact}]}
```

```text
   rule (my.rules/…)
      │  :rule-to-boundary-path  (omitted when boundary is in the rule's own RHS)
      ▼
 collect-app-doc-check-input          ← :boundary-in-var (direct caller of insert!)
      │  :boundary-to-constructor-path
      ▼
 ->document-check-input
      ▼
 my.helpers/->fact                    ← :constructor-sym
```

- **`:boundary-var-name-sym`** — the `insert!`/`retract!` variant.
- **`:boundary-in-var`** — the var the boundary call is written in (the
  boundary's direct caller). Always present when `:via` is.
- **`:boundary-to-constructor-path`** — BFS shortest path through the call
  graph from the boundary's direct caller to the constructor's containing var,
  then the constructor itself.
- **`:rule-to-boundary-path`** — BFS shortest path from the rule var to
  `:boundary-in-var` (both ends inclusive); omitted when the two are the same.

Entries are maps (`{:var-name-sym …}`) so future extensions don't break the
shape. Both paths are **shortest paths through a var-level call graph**, not
observed runtime call paths — clj-kondo reports var-level edges.

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
(analyze/->annotations-from-rule-source-analysis
 {:rule-source-analysis analysis
  :session-or-rulebase my-session
  :fact-constructors
  [{:match-fn (fn [sym] (= 'my.helpers/->fact sym))
    :type-resolver-fn (fn [{:keys [arg-form]}]
                        {:resolved-types [(second arg-form)]})}]})
```

The analyzer traces through `->document-check-input` to find the `(h/->fact …)`
callsite, reads `:loan-doc-rules/document-check-input` as the type, and promotes
it into `:clara-rules/insert-types`. The callsite entry carries
`:constructor-sym` and a `:via` chain showing the full provenance.

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

A boundary argument is **owned** by the constructor path when it is shown to
reach a constructor that resolved. Ownership is decided per boundary argument
(not per rule), because one rule may mix a constructor insert with an
unrelated one. Three ways an argument reaches a constructor — the logic is
`clara.explorer.analyze.callsite/find-owning-boundary-arg`:

| The argument is… | Example | How it is matched |
|---|---|---|
| the constructor call itself | `(insert! (->fact :t m))`, `(insert-all! (mapv #(->fact :t %) xs))` | the constructor's source span is inside the boundary call's |
| a call that leads to it | `(insert! (my-middle-fn args))` where `my-middle-fn` calls `->fact` | a call written inside the boundary call names a link on the constructor's `:via` `:boundary-to-constructor-path` — at any depth |
| a local bound to it | `(let [f (->fact :t m)] (insert! f))` | the locals-traced form equals the resolved constructor call form |

All three are needed, and none subsumes the others. The call graph is
`{caller-var -> #{callee-vars}}`, so it cannot say which of a rule's several
`insert!` calls reached a constructor — that needs the source spans clj-kondo
puts on every var-usage. And a bare local names nothing at all, so only the
traced form joins it back.

An owned argument is skipped: `:callsite-resolver-fn` is never invoked for it,
and the constructor entry (with `:constructor-sym` and `:via`) is the only
entry emitted for that insert. Every other boundary argument reaches
`:callsite-resolver-fn` normally — `with-meta` maps, literals, var-as-fact —
*including in the same rule* as a constructor insert, and including when it
stays unresolved. An insert nobody can explain is reported with `:status :none`
rather than dropped, so `:resolution` stays honest.

### A constructor is only an insert if an insert reaches it

A constructor call that **no** boundary argument reaches is not emitted and its
type is not promoted (the contract is
`clara.explorer.analyze.callsite/resolve-constructor-callsites`):

```clojure
(let [f (->fact :x m)]          ; called, but never inserted
  (insert! (something-else)))
;; => insert-types nil, resolution :none
;;    one :none callsite for (something-else)
```

This is what keeps the reachable-subtree scan from claiming types a rule never
emits — the scan finds *candidates*, and ownership decides which are real. A
constructor the resolver cannot type is likewise not emitted; the argument
falls through to the boundary path instead of being reported twice.

The trade-off is recall: if an insert reaches a constructor by indirection none
of the three routes can see — say `(insert-all! (apply f args))` — the type is
dropped rather than guessed. The boundary callsite then remains `:none`, which
is the honest signal, and the caller can annotate `:clara-rules/insert-types`
explicitly.

Record/Java constructors and the `:fact-type-spec-fn` var-alias flow are
unaffected. Alias-discovered callsites are deliberately never auto-resolved, so
ownership never claims them either.

### `:ns-var-defs-fn` — helper bodies for source-less rule namespaces

When a rule-owning namespace has no classpath source, `clara.explorer.analyze/->rule-source-analysis`
reconstructs an `(ns …)` form from the live namespace and emits a
`(declare …)` for every non-production intern. A `declare` has no body, so the
analysis sees a helper var the rules call but nothing it calls in turn — a rule
whose RHS reaches `insert!`/`retract!` only through such a helper is
annotated `:clara-rules/no-output-types`.

The analyzer cannot reconstruct those bodies itself (`ns-interns` yields
compiled `Var`s, no source text), so `->rule-source-analysis` takes them as an
input. The hook's contract is `clara.explorer.analyze.synth/VarDef`, and the
emission/validation rules are `clara.explorer.analyze.synth/synthesize-ns-source`
(plus `clara.explorer.analyze.synth/var-def-line`):

```clojure
:ns-var-defs-fn
(fn [ns-sym]
  ;; => nil, or a vector of VarDefs:
  [{:name 'parse-and-insert!                 ; unqualified var symbol
    :form '(defn parse-and-insert! [xs]      ; the complete top-level form
             (doseq [x xs]
               (r/insert! (->fact :demo/parsed {:id x}))))}])
```

- **Called once per source-less rule-owning namespace.** A return of `nil` or
  `[]` reproduces the current behaviour exactly; exceptions are logged and
  treated as no var defs for that namespace.
- **`:form` is a whole top-level form** defining `:name`; the analyzer prints
  it as text for clj-kondo to read — never evaluates it.
- **Forms are printed one per line and round-trip validated.** A form that
  prints across lines, or whose printed text is unreadable, is skipped and
  reported via `tap>` with `:event :clara-rules/var-def-skipped`.
- **Production names are rejected** — a `clara.explorer.analyze.synth/VarDef`
  whose `:name` collides with a production is dropped.

Once the helper bodies are in the analyzed text, nothing else changes: the
helper is recorded as a direct inserter, and `:fact-constructors` /
`:callsite-resolver-fn` are reached normally, with constructor callsites
carrying a `:via` `:boundary-to-constructor-path` through the helper chain.

```clojure
(let [analysis (analyze/->rule-source-analysis
                {:session-or-rulebase session
                 :ns-var-defs-fn (fn [ns-sym]
                                   (when-let [defs (captured-defs ns-sym)]
                                     (mapv (fn [[sym form]] {:name sym :form form})
                                           defs)))})
      annotations (analyze/->annotations-from-rule-source-analysis
                    {:rule-source-analysis analysis
                     :session-or-rulebase session})]
  …)
```

---

## Usage Workflows

There are two paths to generate annotations and analysis, depending on whether
you already have a REPL running with a live Clara session. **A session (or
rulebase) is always required** — it is the source of truth for which rules
exist, including macro-emitted ones.

### Path A — REPL with a live session (preferred when a REPL is already up)

If you're already in a JVM REPL with a live Clara session, inject the explorer
library at runtime with `add-libs` (requires Clojure 1.12+). This avoids
classpath issues — your REPL already has all rule constructs, custom fact
types, and deserialization logic loaded that the standalone CLI would need you
to manage separately.

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
(require '[clara.explorer.analyze :as analyze])

(let [analysis    (analyze/->rule-source-analysis
                   {:session-or-rulebase my-session
                    :include-ns-prefixes ["my.project.rules"]})
      annotations (analyze/->annotations-from-rule-source-analysis
                   {:rule-source-analysis analysis
                    :session-or-rulebase my-session})]
  (clojure.pprint/pprint annotations))
```

Rules defined by `eval` in namespaces with no classpath source are handled
automatically: `clara.explorer.analyze/->rule-source-analysis` reconstructs an
`ns` form from the live namespace and synthesizes source from the session's
productions.

#### 3. Generate full static analysis from a live session

To get the same output as `--generate-analysis` (annotations + full rulebase
analysis), use `clara.explorer.core/->rulebase-analysis`:

```clojure
(require '[clara.explorer.analyze :as analyze]
         '[clara.explorer.core :as core]
         '[clojure.pprint :as pprint])

(let [analysis    (analyze/->rule-source-analysis
                   {:session-or-rulebase my-session})
      annotations (analyze/->annotations-from-rule-source-analysis
                   {:rule-source-analysis analysis
                    :session-or-rulebase my-session})
      full        (core/->rulebase-analysis my-session annotations)]
  ;; Inspect interactively:
  (keys full)
  ;; => (:rules :queries :fact-types :nodes :dep-graph :unresolved :ns-deps)

  ;; Write to disk:
  (spit "annotations.edn" (with-out-str (pprint/pprint annotations)))
  (spit "analysis.edn"    (with-out-str (pprint/pprint full))))
```

For large rulebases the LHS/RHS form pretty-printing inside
`->rulebase-analysis` can be slow. Pass `:form-printer pr-str` to skip it:

```clojure
(core/->rulebase-analysis my-session annotations {:form-printer pr-str})
```

#### 4. Start the explorer UI from a live session

```clojure
(require '[clara.explorer.server.serve :as server])

(server/start! {:session my-session :port 9999})
```

---

### Path B — CLI via `-main` (standalone, no REPL needed)

When a REPL isn't available, use the `-main` entry point. **Note:** if your
session uses custom rule constructs, custom fact types, or non-Fressian
serialization, those dependencies must be on the classpath when invoking
`clojure -M`. The REPL path (Path A) avoids this because everything is already
loaded in your running process. For a full flags reference, see the
[server README](../README.md#cli-entry-point).

#### Generate static analysis dump to disk

Requires a serialized session. Produces `annotations.edn` + `analysis.edn` in
the given output directory. Annotations are auto-discovered from the session's
rule namespaces via clj-kondo, with the session rulebase as the source of truth
for rules:

```bash
# Auto-discover from session (sources must be on classpath)
clojure -M:dev -m clara.explorer.server.main --generate-analysis out \
  -s session.bin

# With a custom session loader
clojure -M:dev -m clara.explorer.server.main --generate-analysis out \
  -s session.bin --load-session-state-fn my.app/load-session
```

Output:
```
out/
├── annotations.edn   # Generated annotation layer (id :clara.explorer.analyze/generated)
└── analysis.edn      # Full rulebase-analysis (rules, queries, fact-types, dep-graph, unresolved)
```
