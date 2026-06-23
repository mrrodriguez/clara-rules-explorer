# Clara Rules Explorer — Internal Models

This document covers the **internal** Clojure structures and analysis logic that power the explorer. For the external API contract (endpoints, response shapes), see [`docs/explorer-graph-api.md`](./explorer-graph-api.md).

## Internal Rule & Query Structures

The `Rulebase` stores rules and queries as **Production** records (or maps).

### Rule (Production)
```clojure
{:name "ns/rule-name"
 :ns-name 'ns
 :lhs [...]         ; Sequence of internal condition maps
 :rhs (fn [...] ...) ; Compiled RHS function
 :props {...}        ; Metadata map, including :clara-rules/insert-types
 :handler ...}       ; Internal Clara handler
```

### Query
```clojure
{:name "ns/query-name"
 :lhs [...]
 :params #{:?param-name}
 :props {...}}
```

### LHS Condition Structures

Unlike the DSL's vector form, the internal LHS is a sequence of maps (for leaf conditions) or vectors (for boolean operators).

- **Fact Condition**: `{:type java.lang.Class, :constraints [...]}`. `:type` is typically the **resolved Java Class**, not a symbol.
- **Accumulator Condition**: `{:accumulator ..., :from FactCondition, :result-binding :?var}`.
- **Boolean Condition**: `[:or Condition1 Condition2]`.

### Metadata (:props)

The `:props` map carries annotations used for dependency graph construction. Symbols provided in the DSL (e.g., `{:clara-rules/insert-types [MyFact]}`) are often resolved to `java.lang.Class` instances by the Clara compiler if available in the namespace during macro expansion.

---

## Core Model: The Rete DAG

The explorer is built from the **Rete Network** (the DAG of nodes that evaluate conditions). The source of truth is the `clara.rules.compiler/Rulebase` record, extracted via `(-> session eng/components :rulebase)`.

### Why `id-to-node`?

The `Rulebase` contains `:id-to-node`, a map of `node-id -> node-record`. This is the primary index because:

1. **Stability**: Node IDs are compiler-assigned and unique within a rulebase.
2. **Exhaustiveness**: Contains every node in the network (Alpha, Beta, terminals).
3. **Structure**: Each node record has a `:children` field for forward traversal.

---

## Static Analysis Logic

### 1. LHS Type Extraction

To build a dependency graph, we walk the `:lhs` of each production, dispatching on `clara.rules.schema/condition-type`:
- `:fact`: Extract the `:type`.
- `:accumulator`: Extract the `:type` from the `:from` condition.
- `:and`, `:or`, `:not`, `:exists`: Recursively walk children.

### 2. Dependency Graph Construction

The dependency graph represents potential fact flow.

**Edge Logic**: An edge exists from Rule A to Rule B if:
1. Rule A inserts type `T` (declared in `:props` metadata).
2. Rule B reads type `R` (extracted from LHS).
3. `T` is "compatible" with `R` (either `T == R` or `T` is a descendant of `R` per the rulebase's `ancestors-fn`).

**Relationship to `fact_graph`**:
- `clara.tools.fact-graph`: Provides a **dynamic** trace of facts in a *specific session* after they have fired. Instance-based.
- `clara.server.tools.graph`: Provides a **static** model of what *could* happen based on rule definitions. Type-based.

### 3. Reachability & Path Analysis

For any rule or query, we identify the specific path through the Rete network:

- **Reachable Nodes**: Starting from a `ProductionNode` or `QueryNode`, walk **upwards** using a reverse-index of `:id-to-node`.
- **Reverse Index**: Since nodes only store `:children`, we build `Map<id, Set<parent-id>>` by iterating over all nodes once.

---

## Example: Loan App Rules

Using `clara.server.tools.graph.rules.loan-app-rules`:

**Rule**: `collect-app-given-docs`
- **LHS Types**: `[Application, GivenDocument]`
- **Insert Types**: `[AllGivenDocuments]`

**Rule**: `collect-app-doc-check-input`
- **LHS Types**: `[Application, AllGivenDocuments, AllRequiredDocuments]`

**Static Edge**: `collect-app-given-docs` → `collect-app-doc-check-input`
(Because the first inserts `AllGivenDocuments` and the second reads it).

### Rete Mapping
```
AlphaNode(GivenDocument) → AccumulateNode → ProductionNode(collect-app-given-docs)
AlphaNode(AllGivenDocuments) → HashJoinNode → ProductionNode(collect-app-doc-check-input)
```
