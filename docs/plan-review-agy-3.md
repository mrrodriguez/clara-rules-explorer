# Review: Extend API with Fact Type Hierarchy Details Plan

**Overview:**
The design plan is highly detailed, robust, and correctly avoids the pitfalls of the previous revision by keeping the `dep-graph` internal shape intact and moving type-bridge extraction to the serialization layer. Phase 1’s approach to extracting a memoized `ancestors-fn` and injecting `known` flags is also very sound.

I've reviewed this against the Clara rules logic and the explorer's codebase. Below are the findings, including one critical flaw regarding namespace resolution during type serialization, and a few minor validations/clarifications.

---

### 1. Critical Flaw: `ns-name` Resolution in Type-Bridge Serialization (Phase 2c)

- **The Problem:** The plan states that for Phase 2c, "The internal keys :producer-type / :consumer-type are already kebab-case, so serialization is just `resolve-type` on the values". However, `resolve-type` requires a namespace context (`ns-name`) to correctly serialize unresolved symbols (so `symbol[foo]` becomes `symbol[my.ns/foo]`).
- If you process a `match` pair `{:producer-type pt, :consumer-type ct}` inside `get-production-deps-summary` using the *current* production's `ns-name` for both `pt` and `ct`, it will incorrectly resolve an unresolved type originating from the *other* end of the edge. 
- For instance, if Rule B (in `ns.b`) is the current consumer, and it requires `symbol[foo]`, it would serialize the producer type from Rule A (in `ns.a`) as `symbol[ns.b/foo]` instead of `symbol[ns.a/foo]`. This causes the `:match` `producer-type` string to diverge from the `insert-types` string seen on Rule A's own summary.
- **The Fix:** The `type-analysis-map` from Phase 1a correctly gains the `:ns-name` of each production. `get-production-deps-summary` must look up the correct `:ns-name` for the producer and the consumer individually from `type-analysis-map`, and pass them explicitly to `resolve-type`.
  * `(resolve-type (:ns-name (get type-analysis-map producer-name)) pt)`
  * `(resolve-type (:ns-name (get type-analysis-map consumer-name)) ct)`

### 2. Terminology Clarification: Queries as Consumers (Edge Cases Checklist)

- **The Issue:** In the "Edge Cases Checklist", the plan states: "Queries as consumers: queries appear only on the `:upstream` side of their entries". This wording is confusing and could lead to implementation mistakes.
- **The Reality:** In the `dep-graph`, rules that produce facts are `:upstream` of the queries that consume them. So when looking at a query's summary, it has producers in its `:upstream` list and its `:downstream` list is empty (because queries produce nothing). Conversely, when looking at a rule's summary, a query it feeds appears in its `:downstream` list. The plan's logic handles this correctly, but the text should be clarified to avoid confusion during implementation.

### 3. Validation: `clojure.core/ancestors` on Strings/Non-hierarchical types (Phase 1)

- **The Concern:** The fallback `ancestors-fn` is `clojure.core/ancestors`. Our rulesets heavily use keywords, strings, and vectors (tuples) as fact types. What happens when `clojure.core/ancestors` is called on a string or vector? Does it throw an exception?
- **The Validation:** I tested `(clojure.core/ancestors "foo")` in the running REPL, and it returns `nil` (it does not throw). The plan's proposed `->memoized-ancestors` helper wraps the call in `set`: `(set (ancestors-fn t))`. Since `(set nil)` safely yields `#{}` without error, this approach is perfectly robust for non-hierarchical types. This part of the plan requires no change, but it's important to confirm it's safe.

### 4. Validation: URL-safety and `fq-name-from-param` (System Context / URL-safety)

- **The Concern:** Kind-explicit serialization will create fact-type names like `:my.ns/child`, `"foo"`, and `[:a 1]`. 
- **The Validation:** While `fq-name-from-param` internally splits by `.` and reconstructs strings (which would mangle `:my.ns/child` into `:my/ns/child`), `handle-get-fact-type` safely checks `(get fact-types p)` *before* checking the mangled name. Since the router percent-decodes the path parameter, `p` will perfectly match the key in `fact-types`. No modifications are needed to the routing logic; it already accommodates the new spellings gracefully.

---

**Conclusion:**
The plan is excellent and thoughtfully preserves the shape of the existing `dep-graph`. Once the `ns-name` context resolution is explicitly incorporated into the `get-production-deps-summary` (Phase 2c) logic, the plan is ready for implementation.
