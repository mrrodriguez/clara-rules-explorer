# Clara Rules Explorer - Rule Annotations

Rule annotations provide metadata about Clara rules to statically construct the Rete dependency graph. They declare what fact types a rule's RHS (Right-Hand Side) inserts or retracts, enabling the Explorer to link rules to the LHS (Left-Hand Side) conditions of downstream rules.

---

## Annotation Structure

Rule annotations support the following qualified keys:

| Key | Type | Description |
|-----|------|-------------|
| `:clara-rules/insert-types` | vector of symbols | Fact types that may be inserted during the rule's RHS execution. |
| `:clara-rules/retract-types` | vector of symbols | Fact types that may be retracted during the rule's RHS execution. |
| `:clara-rules/no-output-types` | boolean | Set to `true` to declare that the rule has been manually vetted as a pure side-effect (e.g. logging, API calls) with no downstream fact effects. Suppresses "unlinked rule" warnings. |
| `:clara-rules/notes` | string | Free-form documentation or operational notes about the rule. |
| `:clara-rules/dynamic-insert-types-detected` | map | Captured callsite info when dynamic insertions are detected (see below). |
| `:clara-rules/dynamic-retract-types-detected` | map | Captured callsite info when dynamic retractions are detected (see below). |

---

## Sources of Annotations

The Explorer resolves annotations by merging metadata from two paths:

### Path A — Inline Rule `:props`

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

### Path B — Sidecar EDN File

Annotations can also be declared externally in a sidecar EDN file, mapped by the rule's fully qualified symbol or string representation:

```edn
{my.ns/cold-rule
 {:clara-rules/insert-types [my.ns.ColdAlert]
  :clara-rules/merge-props {:clara-rules/insert-types :merge}}

 my.ns/logging-rule
 {:clara-rules/no-output-types true
  :clara-rules/notes "Pure side-effect rule"}}
```

---

## Annotation Merging

When both Path A (props) and Path B (sidecar) declare annotations for the same rule, the Explorer merges them in [annotations.clj](file:///Users/mrrodriguez/Projects/clara-rules-explorer/server/src/clara/server/tools/graph/annotations.clj) using the following rules:

### Default Strategy (`:merge`)
For collection keys (`:clara-rules/insert-types` and `:clara-rules/retract-types`), values from both sources are **unioned** together.

### Override Semantics
* **Notes**: The sidecar note always overrides the inline property note if present.
* **Pure Side-Effects**: `:clara-rules/no-output-types` evaluates to `true` if declared as `true` in either source.

### Custom Merge Control (`:clara-rules/merge-props`)
The sidecar file can control the merging strategy per category by specifying a `:clara-rules/merge-props` map containing `:clara-rules/insert-types` and/or `:clara-rules/retract-types` keys mapped to:
* `:merge` (default) — Union the types together.
* `:replace` — Discard inline types and use only the sidecar declaration.

```edn
{my.ns/override-rule
 {:clara-rules/insert-types [my.ns.NewFactOnly]
  :clara-rules/merge-props {:clara-rules/insert-types :replace}}}
```

---

## Dynamic Call-Site Capture and Resolution

When the rule base analyzer detects call sites to `insert!`, `retract!`, or their variants (like `insert-all!`) whose fact type cannot be determined by static constructor tracing, it captures each callsite and attempts **runtime-guided resolution**. The session rulebase is the source of truth: RHS forms come from the compiled productions, so macro-emitted rules are captured too.

### The resolution chain

For each callsite argument form (see `clara.server.tools.graph.analyze.rhs`):

1. **Record constructors** — `->MyRecord` / `map->MyRecord` heads are resolved in the live namespace of the consuming rule.
2. **Java constructors** — `(MyFact. …)`, `(new MyFact …)`, and `(MyFact/new …)` resolve to the class.
3. **Locals tracing** — a local symbol argument (e.g. a macro gensym) is traced through clj-kondo's `:locals` analysis to its binding's init form, and the chain restarts there.
4. **Everything else defers** — helper calls, `with-meta`, literals/templates, and the var-as-fact pattern are *deliberately not* resolved automatically; they go to the caller-supplied `:callsite-resolver-fn`, then fall back to unresolved capture.

Resolved types are **promoted**: a fully-resolved dynamic insert also appears in `:clara-rules/insert-types` (likewise retracts), so downstream graph linking uses it directly.

### Detection map structure

```edn
{:clara-rules/dynamic-insert-types-detected
 {:callsites
  [{:source-str "(h/->fact :my-rules/document-check-input data)"
    :ns-name-sym my.rules
    :filename "my/rules.clj"
    :status :resolved
    :resolved-types [:my-rules/document-check-input]
    :constructor-sym my.helpers/->fact
    :via {:boundary-var-name-sym clara.rules/insert!
          :callstack [{:var-name-sym my.rules/collect-input}
                      {:var-name-sym my.rules/->document-check-input}
                      {:var-name-sym my.helpers/->fact}]}}]
  :resolution :full}}
```

* **`:source-str`** — the exact source text of the argument form at the boundary callsite (locals are *not* inlined here; the resolver receives the traced form separately).
* **`:ns-name-sym`** / **`:filename`** — where the callsite was found (may be a helper namespace).
* **`:status`** — `:resolved`, `:resolved-multi` (several types), or `:unresolved`.
* **`:resolved-types`** — present when resolved; the fact-type tokens.
* **`:constructor-sym`** — present when resolved via `:fact-constructor-type-resolver-fn`; the fully-qualified constructor symbol.
* **`:via`** — present when resolved via `:fact-constructor-type-resolver-fn`; a `ViaChain` tracing how the constructor was reached from the originating `insert!`/`retract!` (see below).
* **`:resolution`** (aggregate) — `:full` when every callsite resolved, `:partial` when some did, `:none` otherwise.

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
   `:status :unresolved` with the alias context attached — `:fact-type` (the LHS-bound type) and
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

### `:fact-constructor-match-fn` and `:fact-constructor-type-resolver-fn` — extensible constructor tracing

When a codebase builds facts through plain functions rather than record/Java
constructors (e.g. `with-meta`-tagged maps via a shared `->fact` builder),
those functions are invisible to the automatic constructor-resolution chain
because the chain only recognizes `->X` / `map->X` / `Class.` patterns. The
two extension fns let callers declare their own constructors of interest.

- **`:fact-constructor-match-fn`** — `(fn [var-sym] -> truthy/nil)` — the
  predicate that identifies a constructor of interest by its fully-qualified
  symbol (e.g. `my.helpers/->fact`). kondo already resolves these to FQ
  symbols, so matching is unambiguous.
- **`:fact-constructor-type-resolver-fn`** — `(fn [ctx] -> nil or
  {:resolved-types [token …]})` — called once per discovered constructor
  callsite in the reachable subtree. Receives a `ConstructorTypeResolverContext`:

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
  :fact-constructor-match-fn
  (fn [sym] (= 'my.helpers/->fact sym))
  :fact-constructor-type-resolver-fn
  (fn [{:keys [arg-form]}]
    {:resolved-types [(second arg-form)]})})
```

The analyzer traces through `->document-check-input` to find the `(h/->fact …)`
callsite, reads `:loan-doc-rules/document-check-input` as the type, and promotes
it into `:clara-rules/insert-types`. The callsite entry carries `:constructor-sym`
and a `:via` chain showing the full provenance.

**Validation**: both fns must be provided together (or both absent). Providing one
without the other throws.

**Interaction with existing resolution**: constructor-of-interest callsites are
discovered and resolved *before* the existing boundary-call resolution path runs.
When constructor callsites are found, unresolved boundary callsites (those that
would have been handed to `:callsite-resolver-fn`) are suppressed in favor of the
richer constructor provenance. Record/Java constructors, `with-meta` maps, and
the `:fact-type-spec-fn` var-alias flow are unaffected.

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
