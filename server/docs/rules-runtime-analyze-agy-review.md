# Code Review: Runtime Session-Based Rule Analysis

This document reviews the implementation of the runtime session-based rule analysis plan, focusing specifically on `server/src/clara/server/tools/graph/analyze.clj` and `server/src/clara/server/tools/graph/core.clj`.

## Overall Assessment

The implementation aligns exceptionally well with the design principles outlined in `analyze-runtime-session-rules-plan.md`. The separation of concerns is clear: Clara's session is treated as the undeniable source of truth for rule structure, while `clj-kondo` handles all syntax interpretation. 

The architecture of synthesizing combined sources, utilizing a verbatim `clara-rules` hook configuration, and pruning the resulting AST based on known production definitions is robust. It successfully circumvents the fragility of previous AST-walking approaches.

However, there are a few gaps and areas for improvement, primarily concerning performance at scale and minor robustness issues in edge-case evaluations.

---

## Identified Gaps & Improvements

### 1. Performance of `build-dep-graph` (O(N^2) Complexity)
**File:** `core.clj` (`build-dep-graph`)

Currently, the producer-consumer edge resolution utilizes a cartesian product, checking every rule against every other rule:
```clojure
(for [[p-name1 {produced-types1 :produced-types}] type-analysis-map
      [p-name2 {consumed-types2 :consumed-types}] type-analysis-map
      :when (and (not= p-name1 p-name2)
                 (seq produced-types1)
                 (seq consumed-types2)
                 (some-type-consumed? produced-types1 consumed-types2))]
  [p-name1 p-name2])
```
For large rulebases (e.g., 5,000+ rules), this O(N²) loop will result in tens of millions of iterations and become a significant bottleneck.

**Improvement:** 
Invert the index. Build a map of `consumed-type -> #{consumer-rules}`. Then, iterate over the producers. For each produced type `P`, calculate the set of satisfied types (i.e., `P` and all its `ancestors`) and look up the matching consumers in O(1) time. This changes the time complexity to `O(N * types * consumers-per-type)`.

### 2. Set Allocation Overhead in `downstream?`
**File:** `core.clj` (`downstream?`)

The `downstream?` helper currently allocates a new set on every invocation:
```clojure
(defn- downstream? [ancestors-fn inserter-type reader-type]
  (or (= inserter-type reader-type)
      (and ancestors-fn
           (contains? (set (ancestors-fn inserter-type)) reader-type))))
```
Given that this is called within the inner loop of `build-dep-graph`, re-evaluating `ancestors-fn` and allocating a new `set` repeatedly creates unnecessary GC pressure.

**Improvement:**
Memoize the set conversion of `ancestors-fn`, or precompute the ancestral type expansions for all produced types *before* resolving the producer-consumer edges.

### 3. Fragile Heuristics in `detect-unresolved`
**File:** `core.clj` (`detect-unresolved`)

The current unresolved logic stringifies the RHS data form to execute substring matching:
```clojure
(let [rhs (str (:rhs production))]
  (when (and (or (str/includes? rhs "insert!")
                 ...) ...)))
```
Using `str` and `str/includes?` is fragile. It will trigger false positives on string literals (`(println "do not insert!")`) or comments (if retained in AST), and it could easily miss valid insertion boundaries that don't match the hardcoded strings (e.g., aliased functions or custom wrapper macros that do not contain the substring `"insert!"`).

**Improvement:**
Since `(:rhs production)` is a Clojure data structure (not a raw string), use `clojure.walk/tree-seq` or a recursive function to search the data structure for known boundary function symbols (e.g., `'clara.rules/insert!`).

### 4. Missing Jar Resource Support in `build-source-loader`
**File:** `analyze.clj` (`build-source-loader`)

When resolving a missing namespace, the source loader falls back to reading the filename directly:
```clojure
(if-let [res (and ns-sym (find-ns-resource ns-sym))]
  (slurp res)
  (when filename
    (let [^java.io.File file (io/as-file filename)]
      (when (.exists file)
        (slurp file)))))
```
If a dynamic callsite originates from a third-party library inside a `.jar`, `clj-kondo` reports a filename URI (e.g., `jar:file:/path/to/lib.jar!...`). `io/as-file` and `.exists` will fail to resolve this, resulting in a `nil` source. Consequently, dynamic callsite arguments inside external helpers won't be accurately extracted.

**Improvement:**
Check if the `filename` string represents a URI or starts with `jar:` / `zip:` and resolve it using `clojure.java.io/resource` or `java.net.URL` before falling back to `io/as-file`.

### 5. Filter Bypassing in `build-analysis-from-namespaces`
**File:** `analyze.clj` (`build-analysis-from-namespaces`)

```clojure
(loop [queue (into (set starting-namespaces)
                   (filter ns-matches-prefix?)
                   (extract-required-namespaces initial-analysis))
```
The transducer `(filter ns-matches-prefix?)` is applied to the dependencies from `initial-analysis`, but the initial `(set starting-namespaces)` bypasses this filter. 

**Improvement:**
If the intent is that explicitly requested namespaces are *always* analyzed regardless of exclusion filters, this is acceptable but should be documented in the docstring. If the filters are meant to be global and absolute, the `starting-namespaces` should be filtered prior to queue initialization.

### 6. Redundant `normalize-key` calls
**File:** `analyze.clj` (`extract-session-namespaces`)

```clojure
(defn extract-session-namespaces [session-or-rulebase]
  (->> session-or-rulebase
       extract-session-rule-names
       (into [] (comp (map normalize-key) ...))))
```
The `extract-session-rule-names` helper already normalizes the rule names (applying `normalize-key`). Applying the `(map normalize-key)` transducer again in `extract-session-namespaces` is redundant.

**Improvement:**
Remove the redundant transducer step.
