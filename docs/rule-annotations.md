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
