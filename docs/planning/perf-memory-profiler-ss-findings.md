# Profiler Snapshot Analysis

After a full top-down analysis of the VisualVM sampler snapshot (`cre-sampler-profiler-snapshot.xml`), looking at the *Total Time* spent in application-level functions, the root causes of the performance bottlenecks become much clearer. 

The previous analysis only identified the "leaf" functions (like `pr-on`) where the CPU was ultimately spinning, but looking at the full traces reveals *why* those functions were being hammered.

Here are the primary top-level hot spots and the paths that consume the most time:

## 1. `session-snapshot` and `sort-facts` (~23.9 Seconds)
The absolute largest bottleneck in the application is `clara.server.tools.graph.memory/session-snapshot` taking ~24 seconds. 
Nearly all of this time (23.2s) is spent immediately inside `clara.server.tools.graph.memory/sort-facts`. 

When we trace down where `sort-facts` spends its time, we see it is doing an extremely expensive sort operation that involves stringifying facts to compare them:

```clojure
  Heavy path down:
    -> 23202.0 ms  clara.server.tools.graph.memory/sort-facts
    -> 23202.0 ms  clojure.core/sort-by
    -> 23202.0 ms  java.util.TimSort.sort
    -> 8810.0 ms   clara.server.tools.graph.memory/sort-facts/fn (the comparator)
    -> 8810.0 ms   clara.server.tools.graph.memory/deterministic-fact-str
    -> 8208.0 ms   clara.server.tools.graph.memory/deterministic-fact-str/canonicalize
    -> 7993.0 ms   clojure.core/sort-by (sorting again inside canonicalize!)
    -> 2894.0 ms   clojure.core/pr-str  (printing the canonicalized fact)
    -> 2793.0 ms   clojure.core/pr-on   (the heavy leaf identified earlier)
```
**Takeaway:** `sort-facts` is brutally slow because it sorts by `deterministic-fact-str`. That function canonicalizes the fact (which involves *more* sorting) and then calls `pr-str` on it. This explains why `pr-on` was the top leaf node in the profile—it's being called thousands of times inside a sorting comparator!

## 2. Rule Analysis (`analyze-session-rules`) (~7.7 Seconds)
Another massive chunk of time is spent analyzing rules. `clara.server.tools.graph.analyze/analyze-session-rules` takes 7.7 seconds. 

Tracing this down, it spends almost all of its time invoking `clj-kondo` to parse and analyze the source code:

```clojure
  Heavy path down:
    -> 7700.0 ms  clara.server.tools.graph.analyze/analyze-session-rules
    -> 7499.0 ms  clara.server.tools.graph.analyze/build-analysis-from-namespaces
    -> 7499.0 ms  clara.server.tools.graph.analyze/get-or-analyze-ns-analysis
    -> 4600.0 ms  clara.server.tools.graph.analyze/analyze-source-code
    -> 4600.0 ms  clj-kondo.core/run!
    -> 2489.0 ms  clj-kondo.impl.core/process-files
```
**Takeaway:** The dynamic invocation of `clj-kondo` on the namespaces during the session analysis is taking a very long time. 

## 3. Dependency Graph Building (`build-dep-graph`) (~6.6 Seconds)
Building the dependency graph is the next largest bottleneck. 

```clojure
  Heavy path down:
    -> 6696.0 ms  clara.server.tools.graph.core/build-dep-graph
    -> 6696.0 ms  clojure.core/reduce
    -> 6596.0 ms  clojure.core/next
```
**Takeaway:** While the trace gets murky inside core reduction functions, we know from earlier leaf analysis that `downstream?` checks were burning time inside `clojure.core/some` (using `equiv`). `build-dep-graph` is likely iterating over large collections redundantly.

## 4. `prune-fns` Serialization Prep (~494ms)
As pointed out, `clara.server.tools.graph.serialize/prune-fns` takes nearly half a second.

```clojure
  Heavy path down:
    -> 494.0 ms  clara.server.tools.graph.serialize/prune-fns
    -> 494.0 ms  clojure.core/reduce-kv
    -> 494.0 ms  clojure.lang.PersistentArrayMap.kvreduce
    -> 494.0 ms  clara.server.tools.graph.serialize/prune-fns (recursive call)
    -> 494.0 ms  clojure.lang.PersistentHashMap.kvreduce
```
**Takeaway:** `prune-fns` recursively walks the entire data structure using `reduce-kv` (on maps) and `reduce` (on vectors/lists). Because the session snapshot data is enormous, just walking the tree to strip out functions takes 500ms.

### Updated Action Items:
1. **Critical:** `sort-facts` is completely CPU-bound stringifying facts in a comparator. Can facts be sorted by a stable hash, an ID, or a simpler heuristic rather than a full `pr-str` canonicalization?
2. **Analysis Caching:** Is it possible to cache the `clj-kondo` analysis results so `analyze-session-rules` doesn't have to re-run `clj-kondo.core/run!` for 4.6+ seconds every time?
3. **Graph Traversal:** Refactor `build-dep-graph` to use sets for `downstream?` lookups rather than `some` on lists.
4. **Serialization:** `prune-fns` might be faster if it uses a pre-walk from `clojure.walk` or if we can avoid storing functions in the state to begin with.
