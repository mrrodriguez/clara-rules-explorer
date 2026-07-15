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

## Usage Workflows

There are two paths to generate annotations and analysis, depending on whether you already have a REPL running with a live Clara session.

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
      annotations (analyze/generate-annotations-from-analysis {:analysis analysis})]
  (clojure.pprint/pprint annotations))
```

#### 3. Generate full static analysis from a live session

To get the same output as `--generate-analysis` (annotations + full rulebase analysis), use `clara.server.tools.graph.core/rulebase-analysis`:

```clojure
(require '[clara.server.tools.graph.analyze :as analyze]
         '[clara.server.tools.graph.core :as core]
         '[clojure.pprint :as pprint])

(let [analysis    (analyze/analyze-session-rules
                   {:session-or-rulebase my-session})
      annotations (analyze/generate-annotations-from-analysis {:analysis analysis})
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

#### 5. Analyze by namespace (no session needed)

You can also run the analysis against specific namespaces without a session, using classpath discovery:

```clojure
(require '[clara.server.tools.graph.analyze :as analyze])

(let [cache-atom (atom {})
      analysis   (analyze/build-analysis-from-namespaces
                  {:starting-namespaces '[my.project.rules.income]
                   :include-ns-prefixes []   ; nil or [] = follow all transitive deps
                   :cache-atom cache-atom})
      annotations (analyze/generate-annotations-from-analysis {:analysis analysis})]
  annotations)
```

#### 6. In-memory namespaces (no source files)

If you have namespaces defined entirely in memory with no matching source files on disk or classpath, supply them via `:in-memory-sources`:

```clojure
(let [in-mem-source "(ns my.dynamic.rules
                       (:require [clara.rules :as r]))
                     (r/defrule dynamic-rule
                       =>
                       (r/insert! (with-meta {:id 1} {:type :dynamic-fact-type})))"

      analysis    (analyze/build-analysis-from-namespaces
                   {:starting-namespaces '[my.dynamic.rules]
                    :in-memory-sources {'my.dynamic.rules in-mem-source}})

      annotations (analyze/generate-annotations-from-analysis
                   {:analysis analysis
                    :in-memory-sources {'my.dynamic.rules in-mem-source}})]
  (clojure.pprint/pprint annotations))
```

---

### Path B — CLI via `-main` (standalone, no REPL needed)

When a REPL isn't available, use the `-main` entry point. **Note:** if your session uses custom rule constructs, custom fact types, or non-Fressian serialization, those dependencies must be on the classpath when invoking `clojure -M`. The REPL path (Path A) avoids this because everything is already loaded in your running process. For a full flags reference, see the [server README](../README.md#cli-entry-point).

#### 1. Generate annotations to stdout

No session required — runs clj-kondo directly on source files:

```bash
clojure -M -m clara.server.graph.main -g path/to/my_rules.clj
clojure -M -m clara.server.graph.main -g path/to/rules.clj,path/to/other.clj
```

Example output:
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

#### 2. Generate static analysis dump to disk

Requires a serialized session. Produces `annotations.edn` + `analysis.edn` in the given output directory. Annotations are either generated from explicit `-g` source paths or auto-discovered from the session's compiled namespaces via clj-kondo:

```bash
# With explicit source paths
clojure -M -m clara.server.graph.main --generate-analysis out \
  -s session.bin -g path/to/my_rules.clj

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
