# Testing misses: `annos-report-changes-plan.md`

## A `derive` declared in one unit, on a type only another unit uses

### What the change is for

A unit used to record a fact type only when one of its own productions consumed or produced it,
or when the type was an ancestor of one that did. So when unit A declares `(derive :t/b :t/c)` and
none of A's productions mention `:t/b`, A dropped that edge. If unit B inserts `:t/a` and declares
`(derive :t/a :t/b)`, then:

- A records nothing for `:t/b`.
- B records `:t/a → [:t/b]`, because B never loaded A's `derive`.
- The composition unions and re-closes the ancestor sets, but no unit wrote down `:t/b → :t/c`.
  So `:t/a` never reaches `:t/c`, A's consumer of `:t/c` gets no edge from B's producer, and
  `:t/c` reads as an entry point.

Neither unit needs the other's classpath. Each unit only needs to record its own declared edges,
including edges whose child it never uses. That is what `:declared-tags-fn` and
`fact-types/register-declared-tags` do.

### Why no current test covers it

- `core-test/test-declared-tags-registered-and-not-known` and `test-declared-tags-fn-override`
  check one analysis: a declared tag gets an entry, and the override works. Neither composes
  anything.
- `compose-test/union-fact-types-recloses-and-orders-ancestors-test` checks the union on
  hand-written fact-type maps. Those maps already contain the edge the change exists to record.
- The checked-in `rules-annos/` units are generated in one JVM with every fixture namespace loaded,
  over the global hierarchy. Every unit sees every `derive`, so an edge is recorded whether or not
  the declaring unit uses its child. A test over them passes with the change reverted. The
  `ApplicationOutcome` entry that now appears in the disposition unit is this leakage, not the case
  above: `loan-app-rules` both declares that `derive` and uses `ApplicationOutcome`, so loan-app's
  unit always recorded it.

### The test

**Fixture: two units with separate hierarchies.** Add two small rule namespaces under
`test/clara/explorer/test/rules/`. Neither calls `clojure.core/derive`, so nothing touches the
global hierarchy.

- Unit A: `consume-c` matches `:t/c`.
- Unit B: `produce-a` matches an input type and inserts `:t/a`. Declare
  `:clara-rules/insert-types [:t/a]` in props, the way `loan-hierarchy-rules` does.

In the test, give each unit its own hierarchy, standing in for its own classpath:

```clojure
(def ^:private a-hierarchy (-> (make-hierarchy) (derive :t/b :t/c)))
(def ^:private b-hierarchy (-> (make-hierarchy) (derive :t/a :t/b)))
```

**Steps:**

1. Build each unit's session over its own hierarchy: `:ancestors-fn #(ancestors a-hierarchy %)`
   (and b's), plus a `:fact-type-fn` for keyword facts.
2. Analyze each with `core/->rulebase-analysis`, passing
   `{:declared-tags-fn #(keys (:parents a-hierarchy))}` (and b's). Annotations come from
   `ann.merge/->props-layer`.
3. Slim each with `slim/slim-rulebase-analysis`.
4. Compose in memory with `shared.compose/->composed-analysis`. Its caps are
   `{:read-analysis <lookup of the slim analysis by unit> :assert-compatible! (constantly nil)}`,
   and the selection is `[{:repo "unit-a"} {:repo "unit-b"}]`. Nothing touches disk.

**Assertions:**

- **The fixture models separate classpaths.** B's own analysis has no entry for `":t/c"`.
- **The declaring unit records its unused child.** A's analysis has `":t/b"` with ancestors
  `[":t/c"]`, and `":t/b"` is not in A's known set.
- **The composition closes the chain.** The composed `":t/a"` has ancestors `[":t/b" ":t/c"]`.
- **The cross-unit edge exists.** In the composed `:dep-graph`, `consume-c`'s `:upstream` contains
  `produce-a`.
- **No false entry point.** `cross-unit/all-entry-points` over the composition does not contain
  `":t/c"`.

**Control: the test must fail without the change.** Repeat the steps with A analyzed under
`{:declared-tags-fn (constantly [])}`, which is the old behavior. The composed `":t/a"` stops at
`[":t/b"]`, `consume-c` has no upstream from `produce-a`, and `":t/c"` is an entry point. Keep this
as a `testing` block in the same `deftest`, so a regression shows as both blocks flipping.

**Where:** `compose_test.clj`, beside `union-fact-types-recloses-and-orders-ancestors-test`. It
tests the same union, starting from analysis rather than from hand-written maps.
