# API Hierarchy Details Plan - Design Review

This document captures the accepted review feedback and gaps identified in the design plan (`docs/extend-api-hierarchy-details/`).

### 1. Caching Scope for `ancestors-fn` is Too Narrow
- **The Gap:** Phase 1a notes that `ancestors-fn` should be cached per unique type when building the fact-types summary map. However, Phase 2a introduces `matching-type-pairs` inside `build-dep-graph`, which does a nested loop over produced and consumed types and calls `downstream?` (which in turn calls `ancestors-fn`).
- **Actionable Critique:** If the cache is scoped only locally to `build-fact-type-summary-map`, `build-dep-graph` will redundantly execute `ancestors-fn` (potentially thousands of times in its nested loop). The memoization of `ancestors-fn` must be hoisted to the top level of `rulebase-analysis` so that *both* Phase 1 and Phase 2 share the same cached evaluations for the duration of the analysis run.

### 2. Missing Determinism in Phase 2 `:match` Arrays
- **The Gap:** Phase 1b explicitly mandates that the `:ancestors` array must be "deterministically sorted lexicographically" to guarantee stable API responses. However, Phase 2a/2b does not specify any sorting for the `:match` array.
- **Actionable Critique:** The order of the `:match` array will depend on the iteration order of `produced-types` and `consumed-types` in `matching-type-pairs`. To ensure stable, deterministic API responses, the `:match` pairs should also be explicitly sorted (e.g., lexicographically by `producer-type`, then `consumer-type`) before being serialized.

### 3. Two-Pass Requirement for Ghost Types is Implicit
- **The Gap:** In Phase 1a, the doc says: "Compute `known` by checking whether the ancestor type key exists in the fact-types summary map".
- **Actionable Critique:** Since the map is being built in the same pipeline, you cannot accurately determine if a type is "known" until *all* rules and queries have been processed. The design doc should explicitly state that attaching `:ancestors` requires a **two-pass operation**: Pass 1 to build the baseline frequencies and identify all known types, and Pass 2 to evaluate `ancestors-fn` and attach the `:ancestors` arrays with the fully resolved `known` booleans.
