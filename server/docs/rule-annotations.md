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

## Dynamic Call-Site Capture

When the rule base analyzer detects call sites to `insert!`, `retract!`, or their variants (like `insert-all!`), but cannot statically determine the fact type (e.g. `(insert! (with-meta {:app-id 1} {:type :custom}))`), it populates the dynamic detection keys.

Rather than a simple boolean flag, these keys contain structured coordinates mapping back to the Clojure source code forms:

```edn
{:clara-rules/dynamic-insert-types-detected
 {:callsites
  [{:source-str "(with-meta {:app-id ?app-id, :status :pass} {:type :custom-map-type})"
    :ns-name-sym clara.server.tools.graph.rules.analyze-test-rules
    :filename "test/clara/server/tools/graph/rules/analyze_test_rules.clj"}]}}
```

### Callsite Map Structure

Each entry in the `:callsites` vector is a map containing:

* **`:source-str`**: The exact string of the extracted Clojure argument form passed to the insertion or retraction callsite.
* **`:ns-name-sym`**: The symbol of the namespace where the callsite was located.
* **`:filename`**: The path to the file containing the callsite.

---

## CLI and Standalone Usage

The static analysis tools can be run as a standalone utility to generate annotations from source files or run programmatically against in-memory rule sessions.

### 1. Generating Annotations via CLI (Primary)

The server CLI supports a `-g` (or `--generate-annotations`) flag which accepts one or more Clojure source files and prints the resolved annotations EDN map directly to `stdout`. This does not require starting a web server or loading a serialized session.

```bash
# Analyze a single rule file
clojure -M -m clara.server.graph.main -g path/to/my_rules.clj

# Analyze multiple files (comma-separated)
clojure -M -m clara.server.graph.main -g path/to/my_rules.clj,path/to/other_rules.clj
```

**Example Output:**
```edn
{my.ns/cold-rule
 #:clara-rules{:insert-types [my.ns.Cold]}

 my.ns/orphan-rule
 #:clara-rules{:insert-types [],
               :dynamic-insert-types-detected
               {:callsites
                [{:source-str "(with-meta {:app-id ?app-id} {:type :custom-type})"
                  :ns-name-sym my.ns
                  :filename "path/to/my_rules.clj"}]}}}
```

### 2. Generating Static Analysis Dump via CLI

The `--generate-analysis` flag produces a complete static analysis dump to disk — both annotations and full rulebase analysis — without starting an HTTP server. This is the recommended workflow when introspecting a new ruleset that hasn't been annotated yet: you get the auto-generated annotations (which you can save and iteratively refine) alongside the full analysis (rules, queries, fact-types, dependency graph, and unresolved detections).

This mode requires a serialized session (`-s PATH`) and accepts the same session-loading arguments as the server mode (`-f`, `--load-session-state-fn`). Annotations are either generated from explicit source paths or auto-discovered from the session's compiled namespaces via clj-kondo.

```bash
# With explicit source paths for annotation generation
clojure -M -m clara.server.graph.main --generate-analysis out \
  -s path/to/session.bin -g path/to/my_rules.clj

# Auto-discover annotations from session namespaces (sources must be on classpath)
clojure -M -m clara.server.graph.main --generate-analysis out \
  -s path/to/session.bin

# With a custom session loader
clojure -M -m clara.server.graph.main --generate-analysis out \
  -s path/to/session.bin --load-session-state-fn my.app/load-session
```

Output:
```
out/
├── annotations.edn   # Auto-generated sidecar annotations
└── analysis.edn      # Full rulebase-analysis (rules, queries, fact-types, dep-graph, unresolved)
```

When `-g` source paths are omitted, annotations are auto-discovered from the session's compiled namespaces via clj-kondo (sources must be on the classpath).

### 3. Programmatic In-Memory Analysis (REPL Injection)

If you are running a JVM REPL in an existing project that compiles Clara rules, you can inject the Explorer server library onto your classpath dynamically to analyze in-memory sessions.

#### A. Inject Library to Classpath

* **Clojure 1.12+ Dynamic Loading**:
  Load the explorer library directly in your active REPL session using `add-libs`:
  ```clojure
  (require '[clojure.repl.deps :as deps])
  
  ;; For local reference
  (deps/add-libs '{mrrodriguez/clara-rules-explorer-server 
                   {:local/root "/absolute/path/to/clara-rules-explorer/server"}})
  
  ;; Or via git dependency
  (deps/add-libs '{io.github.mrrodriguez/clara-rules-explorer 
                   {:git/url "https://github.com/mrrodriguez/clara-rules-explorer"
                    :sha "<git-commit-sha>"
                    :deps/root "server"}})
  ```

* **Via `deps.edn` `:local/root`**:
  Alternatively, add it under an alias in your project's `deps.edn`:
  ```clojure
  :aliases
  {:explorer
   {:extra-deps {mrrodriguez/clara-rules-explorer-server 
                 {:local/root "/absolute/path/to/clara-rules-explorer/server"}}}}
  ```

#### B. Execute Analysis in REPL

Once the server classes are loaded on the classpath, you can use [clara.server.tools.graph.analyze](file:///Users/mrrodriguez/Projects/clara-rules-explorer/server/src/clara/server/tools/graph/analyze.clj) to inspect any active in-memory session or rulebase:

```clojure
(require '[clara.server.tools.graph.analyze :as analyze])

;; Build the merged clj-kondo analysis map from the session's namespaces
(let [analysis (analyze/analyze-session-rules 
                {:session-or-rulebase my-session
                 :include-ns-prefixes ["my.project.rules"]})
      ;; Generate the rule annotations map
      annotations (analyze/generate-annotations-from-analysis 
                   {:analysis analysis})]
  (clojure.pprint/pprint annotations))
```

To start the interactive web-based UI server for your live in-memory session, invoke [clara.server.graph.server/start!](file:///Users/mrrodriguez/Projects/clara-rules-explorer/server/src/clara/server/graph/server.clj):

```clojure
(require '[clara.server.graph.server :as server])

(server/start! {:session my-session
                :port 9999})
```

#### C. Analyzing Dynamically Generated In-Memory Namespaces

If you have namespaces dynamically defined fully in memory (with no matching files on the classpath or on disk), you can supply their source code maps using the `:in-memory-sources` option (a map of `{ns-symbol source-string}`).

This instructs the static analyzer to run `clj-kondo` directly on the provided in-memory source strings and enables coordinate-based dynamic callsite form extraction from memory:

```clojure
(require '[clara.server.tools.graph.analyze :as analyze])

(let [in-mem-source "(ns my.dynamic.rules
                       (:require [clara.rules :as r]))
                     (r/defrule dynamic-rule
                       =>
                       (r/insert! (with-meta {:id 1} {:type :dynamic-fact-type})))"
      
      ;; 1. Analyze the namespaces including the in-memory ones
      analysis (analyze/build-analysis-from-namespaces
                {:starting-namespaces ['my.dynamic.rules]
                 :in-memory-sources {'my.dynamic.rules in-mem-source}})
      
      ;; 2. Generate annotations (passing in-memory-sources so callsites can be extracted)
      annotations (analyze/generate-annotations-from-analysis
                   {:analysis analysis
                    :in-memory-sources {'my.dynamic.rules in-mem-source}})]
  (clojure.pprint/pprint annotations))
```
