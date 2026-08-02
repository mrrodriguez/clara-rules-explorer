# Plan Review: Extend API with Fact Type Hierarchy Details

This document outlines the review of the initial design plan (`docs/extend-api-hierarchy-details-plan.md`) for surfacing type-hierarchy information in the API, incorporating design decisions and necessary adjustments to the roadmap.

## 1. Data Model Asymmetry in Phase 2 - RESOLVED

**Observation:**
In Phase 2b, the original plan proposed changing the JSON keys for the `match` array depending on whether the API is returning an upstream or downstream link:

- Upstream entry: `{"produces": "A", "satisfies": "B"}`
- Downstream entry: `{"requires": "B", "satisfied-by": "A"}`

**Decision:**
This introduces unnecessary complexity for API consumers (e.g., forcing Union types in TypeScript) for the exact same conceptual relationship. The plan will be adjusted to use a **symmetric data structure** for both perspectives.

- We will use `producer-type` and `consumer-type` terminology.
- Example: `{"producer-type": "A", "consumer-type": "B"}`
  The UI context inherently knows if it is rendering an upstream or downstream list and can interpret these keys accordingly without ambiguity.

## 2. Breaking Internal Tooling via `:dep-graph` Changes - RESOLVED

**Observation:**
In Phase 2a, the plan changes the internal output of `build-dep-graph` from a set of rule names `#{ "consumer-rule" }` to a nested map `{"consumer-rule" {:via [...]}}`.
Because `merged-analysis.edn` exposes `:dep-graph`, changing this fundamental shape will break existing tooling that reads the analysis file (such as `scripts/annotations_report.bb` and other agent scripts).

**Decision:**
We do not need to preserve backward compatibility for this internal data structure. However, the plan must be updated to include a **new phase/step** explicitly scoped to analyze and update agent scripts and any other affected tooling after the core refactor work is done.

## 3. Hierarchy Noise / Clutter - RESOLVED

**Observation:**
An initial concern was raised that dumping the raw output of `ancestors-fn` might include language-level plumbing (e.g., `java.lang.Object`, `clojure.lang.IRecord`), and a suggestion was made to filter them out for clarity.

**Decision:**
We will **not** filter the class hierarchy. Many use cases rely on custom `ancestors-fn` implementations where hierarchies behave unexpectedly, and it is fully valid for Clara rules to refer directly to types like `java.lang.Object` or `IRecord`. We cannot arbitrarily hide this information. The plan's approach to include the raw, unfiltered output of `ancestors-fn` stands as-is.

## 4. `ancestors-fn` Fallback Logic - RESOLVED

**Observation:**
An initial concern questioned whether `(:ancestors-fn (meta get-alphas-fn))` would always be present, suggesting a fallback to `clojure.core/ancestors` might be needed.

**Decision:**
This concern has no merit. The rulebase will always have an `ancestors-fn` as it is integral to Clara-rules. The plan's approach to extracting it directly from the rulebase metadata is sound and requires no fallback logic.

## 5. Non-Deterministic Output of `ancestors` Arrays

**Observation:**
If Clara uses `clojure.core/ancestors` or `parents` in the absence of a custom function, the return value is an unordered Clojure `set`. Serializing a set directly to a JSON array results in a non-deterministic order, causing flaky API responses and UI jitter.

**Decision:**
We must sort the `ancestors` array deterministically. Since we cannot reliably know the true hierarchy order for ad-hoc derive chains, we will use a custom lexicographical sort on the stringified types:

- Domain types will appear at the front.
- Language/plumbing types (e.g., prefixed with `clojure.lang`, `java.lang`, `clojure.core`, or `java.util`) will be forced to the _end_ of the list.

## 6. UI Handling of "Ghost Types"

**Observation:**
In Phase 1c, we correctly decided not to add "ghost types" (ancestor types not explicitly used in any rule) as primary entities in the `fact-types` map. However, the UI will still receive these types in the `:ancestors` array and needs to know they aren't fully-fledged fact-types in the system (e.g., so it doesn't render broken links).

**Decision:**
The `ancestors` API shape should not be just an array of strings. It should be an array of objects/maps providing more detail.

- Example: `[{"type": "java.lang.Object", "no-consumer": true}]`
  This gives the API consumer an explicit flag (`no-consumer: true`) indicating that this is a ghost type and allows the UI to render it as plain text rather than a hyperlink.

## 7. Deduplication of Type-Pairs

**Observation:**
If a producer rule has multiple `insert!` calls for the same type (e.g., inserting two different `Foo` records), and a downstream rule matches that type in multiple conditions, the `matching-type-pairs` helper could yield identical, duplicate edges (e.g., `[{producer-type: Foo, consumer-type: Foo}, {producer-type: Foo, consumer-type: Foo}]`).

**Decision:**
Ensure the `matching-type-pairs` helper applies `distinct` (or evaluates against sets) so we don't bloat the API payload with redundant, identical bridges for a single production link.

## 8. Performance of `ancestors-fn` Enumeration

**Observation:**
Because we are preserving the full Java class hierarchy without filtering, running `ancestors-fn` on hundreds of fact types could be slightly expensive if a custom `ancestors-fn` does heavy computation, and the returned inheritance trees can be large.

**Decision:**
Ensure `build-fact-type-summary-map` evaluates `(ancestors-fn type)` exactly once per unique fact type (e.g. by processing unique types first or caching the results) rather than repeatedly evaluating it inside a loop over every rule's conditions.
