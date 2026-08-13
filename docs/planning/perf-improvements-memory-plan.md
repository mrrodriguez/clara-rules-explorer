# Performance Improvements — Working-Memory Snapshot & Session Enrichment

Status: **Plan — for review. Not yet implemented.**

Scope: the hot paths identified in
[`perf-memory-profiler-ss-findings.md`](./perf-memory-profiler-ss-findings.md),
narrowed to the observations that matter (the `memory.clj` snapshot path, plus
`analyze/enrich-annotations-from-session`).

---

## 1. Observations → root causes → fixes

| # | Observation (profiler self-time) | Root cause | Fix | Priority |
|---|----------------------------------|------------|-----|----------|
| 1–2 | `sort-facts` ~23s, almost all in the `sort`/comparator → `deterministic-fact-str` → nested `canonicalize` | The `sort-by` keyfn is recomputed on **every comparison** (O(n log n) full fact stringifications); `canonicalize`'s inner `sort-by pr-str` has the same O(k log k) recompute problem one level down | **Fix 1** Schwartzian transform in `sort-facts`; **Fix 2** decorate-sort-undecorate in `canonicalize` | P0 |
| 3 | `build-rule-match-index` ~500ms, most in `prune-fns` | `serialize/prune-fns` walks the same fact objects repeatedly (sort key, fact-table `:data`, and per-match `bindings`) with no reuse | **Fix 3** identity-memoized `prune-fns` scoped to one snapshot | P1 |
| 4 | (rest) | — | not addressed | — |
| 5 | `enrich-annotations-from-session` ~1.2s, almost all in `into` | `resolved-annotation-map` rebuild calls `ann/production-annotation` per production, and that fn runs a full O(A) `(every? (comp string? key) annotations)` scan on every call → O(P×A); `rule->session-raw-types` is also computed twice per call | **Fix 4** pre-normalized fast path + hoist the redundant work | P1 |

Two further items from the original report (`analyze-session-rules` clj-kondo re-run ~7.7s,
`build-dep-graph` ~6.6s) are **not** part of this plan — they are on the
*static analysis* side, not the memory/snapshot side. They are listed in
[§7 Deferred](#7-deferred--out-of-scope) so they are not lost.

---

## 2. Fix 1 — Schwartzian transform in `sort-facts` (P0)

**File:** `server/src/clara/server/tools/graph/memory.clj`, `sort-facts` + `deterministic-fact-str`.

**Problem.** `sort-by` invokes its keyfn on *both* operands of *every* comparison:

```clojure
(sort-by keyfn coll) ;; = (sort (fn [x y] (compare (keyfn x) (keyfn y))) coll)
```

so for `n` facts the full key — `[fact-type-order, (str ft), deterministic-fact-str]`,
where `deterministic-fact-str` itself does `prune-fns` + recursive `canonicalize` + `pr-str` —
is built **O(n log n)** times. That is the 23s.

**Fix.** Decorate-sort-undecorate: compute each fact's key exactly once, sort the
`[key fact]` pairs, strip the keys. Output order is byte-identical because the
key vector is the same one the old comparator compared.

```clojure
(defn- sort-facts
  [facts fact-type-fn fact-type-order]
  (->> facts
       (map (fn [wrapped]
              (let [fact (platform/fact-id-unwrap wrapped)
                    ft (fact-type-fn fact)]
                [[(get fact-type-order ft Integer/MAX_VALUE)
                  (str ft)
                  (deterministic-fact-str fact)]
                 wrapped])))
       (sort-by first)
       (map second)))
```

**Expected impact.** Reduces full-fact stringifications from O(n log n) to O(n).
For large `n` this is the dominant win (roughly a 10–20× reduction in comparator
key computations). Exact numbers to be re-measured (see §6).

**Correctness notes.**
- `sort-by first` compares the `[int str str]` key vectors with `compare`, exactly
  as the original keyfn did.
- Identical keys fall back to TimSort stability, which preserves input order —
  the same behaviour as today. No change to fact-ID ordering.
- `sort-facts` currently returns a seq; the transform returns a lazy seq too, so
  `build-id-map` / `build-fact-table` consumers are unaffected.

---

## 3. Fix 2 — decorate-sort-undecorate inside `deterministic-fact-str` (P0)

**File:** `server/src/clara/server/tools/graph/memory.clj`, `deterministic-fact-str`.

**Problem.** `canonicalize` sorts maps and sets with `(sort-by pr-str …)`, which
recomputes `pr-str` on every comparison inside each map/set — O(k log k) `pr-str`
calls per collection of size k. `pr-str`/`pr-on` was the top leaf node in the
profile.

**Fix.** A local helper computes `pr-str` once per element:

```clojure
(defn- deterministic-fact-str
  [fact]
  (letfn [(sort-by-pr-str [coll]
            (->> coll
                 (map (juxt pr-str identity))
                 (sort-by first)
                 (map second)))
          (canonicalize [x]
            (cond
              (map? x) (into [::map]
                             (sort-by-pr-str
                              (map (fn [[k v]] [(canonicalize k) (canonicalize v)]) x)))
              (set? x) (into [::set]
                             (sort-by-pr-str (map canonicalize x)))
              (sequential? x) (mapv canonicalize x)
              :else x))]
    (pr-str (canonicalize (serialize/prune-fns fact)))))
```

**Expected impact.** Inner `pr-str` calls drop from O(k log k) to O(k) per
map/set. Combined with Fix 1, the stringification cost collapses to roughly one
canonicalize + one `pr-str` per fact element.

**Correctness notes.**
- `(juxt pr-str identity)` + `(sort-by first)` + `(map second)` is exactly
  `(sort-by pr-str coll)` with memoized keys — identical sort order, identical
  output string.
- The determinism tests in `memory_test.clj`
  (`test-deterministic-fact-str--shapes`, `test-stable-deterministic-fact-ids`)
  must still pass unchanged — they assert the same `deterministic-fact-str`
  string for maps in different key orders and stable fact IDs.

---

## 4. Fix 3 — identity-memoized `prune-fns` (P1)

**File:** `server/src/clara/server/tools/graph/serialize.clj` (memo core +
factory) and `server/src/clara/server/tools/graph/memory.clj` (wiring).

**Problem.** Within one `session-snapshot`, the *same fact object* is walked by
`prune-fns` at least twice — once inside `deterministic-fact-str` (sort key) and
again as `:data` in `build-fact-table` — and match `bindings` frequently embed
those same fact objects as bound values, which are then walked a third time in
`explanations->fact-match-data`. Clara keeps fact identity stable across the
`inspect` views (`:all-facts`, `:insertions`, `:matches` all hold the same
instances; `build-fact-table` already relies on `identical?` for `:is-root`),
so identity-keyed memoization is sound here.

**Fix.**

1. In `serialize.clj`, split `prune-fns` into a memo-aware recursive core and a
   public one-shot wrapper:

```clojure
(defn- prune-fns* [^java.util.IdentityHashMap memo x]
  (or (.get memo x)
      (let [result
            (cond
              (record? x) (reduce-kv (fn [m k v] (assoc m k (prune-fns* memo v))) {} x)
              (map? x) (reduce-kv (fn [m k v] (assoc m k (prune-fns* memo v))) (empty x) x)
              ;; seq-like (list / non-vector sequential): keep the reverse-order guard
              (or (list? x)
                  (and (sequential? x) (not (vector? x))))
              (into (empty x) (map #(prune-fns* memo %)) (reverse x))
              (sequential? x) (into [] (map #(prune-fns* memo %)) x)
              (coll? x) (into (empty x) (map #(prune-fns* memo %)) x)
              (keyword? x) x
              (symbol? x) x
              (class? x) (.getName ^Class x)
              (ifn? x) (str x)
              :else x)]
        (.put memo x result)
        result)))

(defn prune-fns
  "… (existing docstring) …"
  [x]
  (prune-fns* (java.util.IdentityHashMap.) x))

(defn memoizing-prune-fns
  "Returns a (fn [x] -> pruned) that memoizes by object identity within the
   returned fn's scope. Use where the same fact/substructure is pruned more
   than once in a single operation (e.g. a session snapshot)."
  []
  (let [memo (java.util.IdentityHashMap.)]
    (fn [x] (prune-fns* memo x))))
```

2. In `memory.clj`, create one memoized pruner in `session-snapshot` and thread
   it through the private helpers:

```clojure
(let [prune-fn (serialize/memoizing-prune-fns)
      …
      sorted-facts (sort-facts all-facts-wrapped fact-type-fn fact-type-order prune-fn)
      …
      fact-table (build-fact-table {… :prune-fn prune-fn …})
      rule-match-index (build-rule-match-index … prune-fn)
      query-match-index (build-query-match-index … prune-fn)]
  …)
```

   Signatures change to accept the pruner:
   - `deterministic-fact-str [prune-fn fact]` → `(prune-fn fact)` instead of `(serialize/prune-fns fact)`.
   - `sort-facts [facts fact-type-fn fact-type-order prune-fn]`.
   - `build-fact-table` uses `(prune-fn fact)` for `:data` (and in the nil-raw-type WARN branch).
   - `explanations->fact-match-data [explanations fact-table get-fact-id prune-fn]` → `(prune-fn bindings)`.
   - `build-rule-match-index` / `build-query-match-index` thread it through.

**Expected impact.** Fact pruning drops from 2× (sort key + fact table) to 1×,
and bindings that embed already-pruned fact sub-objects hit the memo during the
recursive walk. Directly targets the ~500ms in `build-rule-match-index` and
reduces redundant work across the whole snapshot.

**Correctness / risk notes.**
- `prune-fns` is pure (returns fresh structures), so identity-keyed memoization
  cannot change results; equal-but-not-identical values simply recompute.
- `IdentityHashMap` avoids `.equals` (which can be expensive, or throw, on
  arbitrary fact types); identity is exactly the semantics Clara already uses
  for facts.
- Leaf values are memoized to themselves; the only `nil` result is for `nil`
  input, and `.get`→recompute→`.put` stays correct for that case.
- Memory is bounded by distinct sub-objects in one snapshot and released with
  the snapshot build. For very large fact graphs this is a real (but temporary)
  allocation — acceptable; flag in review if the scenario has huge facts.
- This is the only fix that touches `serialize.clj`; the public `prune-fns`
  one-shot behaviour is unchanged, so all existing callers (including `core.clj`)
  are unaffected.

---

## 5. Fix 4 — `enrich-annotations-from-session` fast path (P1)

**File:** `server/src/clara/server/tools/graph/annotations.clj` and
`server/src/clara/server/tools/graph/analyze.clj`.

**Problem.** `enrich-annotations-from-session` builds

```clojure
resolved-annotation-map
(into {}
      (for [p (:productions rulebase)]
        [(ann/normalize-rule-name (:name p))
         (ann/production-annotation merged-annotations p)]))
```

and `ann/production-annotation` begins with a defensive full-map scan on **every**
call:

```clojure
(let [annotations (if (every? (comp string? key) annotations)
                    annotations
                    (normalize-annotations annotations))
      …])
```

`merged-annotations` here is the `:annotations` payload of `merge-layers`, which
is **already** a string-keyed `sorted-map` (both `fold-layer` and
`derive-with-provenance` normalize keys). So every one of the P production calls
re-scans all A annotation keys — O(P×A) — to re-derive a fact that is already
guaranteed true.

**Fix.**

1. In `annotations.clj`, keep `production-annotation` as the normalizing entry
   point but delegate the actual work to a public string-keyed fast path:

```clojure
(defn production-annotation
  "… (existing docstring) …"
  [annotations production]
  (production-annotation-normalized
   (if (every? (comp string? key) annotations)
     annotations
     (normalize-annotations annotations))
   production))

(defn production-annotation-normalized
  "Like `production-annotation`, but `annotations` MUST already have
   string rule-name keys (e.g. the `:annotations` payload of
   `annotations.merge/merge-layers`). Skips the per-call key scan."
  [annotations production]
  (let [rule-ann (get annotations (normalize-rule-name (:name production)))
        production-ns (get-production-ns production)]
    (-> (select-keys rule-ann production-annotation-keys)
        (resolve-type-key :clara-rules/insert-types production-ns)
        (resolve-type-key :clara-rules/retract-types production-ns)
        (update-keys unqualify-keyword))))
```

2. In `analyze.clj`'s `enrich-annotations-from-session`, call the fast path
   directly (the input is the `merge-layers` payload):

```clojure
(into {}
      (for [p (:productions rulebase)]
        [(ann/normalize-rule-name (:name p))
         (ann/production-annotation-normalized merged-annotations p)]))
```

3. Remove the redundant second `rule->session-raw-types` computation:
   `enrich-annotations-from-session` calls `add-auto-detected-annotations`
   (which internally computes `rule->session-raw-types`) and then computes it
   again itself. Compute once and pass it to `add-auto-detected-annotations`
   (add an arity), or have `add-auto-detected-annotations` return it alongside
   the enriched map.

**Expected impact.** The O(P×A) key-type scan goes away (P×A string? checks →
   0), and the duplicate session-raw-type index is built once. `into`-attributed
   time in this function should drop from ~1.2s toward the remaining per-rule
   resolution work.

**Correctness / risk notes.**
- `production-annotation` behaviour is unchanged; the new
  `production-annotation-normalized` is additive. `core.clj` callers continue to
  use the normalizing entry point (they may receive symbol-keyed maps from
  `coerce-annotations-arg`).
- `get-production-ns` (`the-ns`) and `resolve-type-locally` (`ns-resolve`) still
  run per production/type; if re-measurement shows they remain hot, a follow-up
  can memoize `the-ns` per `:ns-name` (all chain rules share one ns) — deferred
  until we have numbers, to avoid speculative complexity.

---

## 6. Verification

All changes are in `server/`, so use the Makefile targets:

```bash
cd server
make test              # full suite — includes determinism/ID-stability tests below
make lint              # clj-kondo
make reflection-check  # *warn-on-reflection* true
make format-check      # cljfmt (run `make format` first if needed)
```

Tests that pin the exact behaviour being preserved:

- `test/clara/server/tools/graph/memory_test.clj`
  - `test-deterministic-fact-str--shapes` — `deterministic-fact-str` output must
    be byte-identical (map key order / set element order must not matter).
  - `test-stable-deterministic-fact-ids` and the first `testing` block
    ("Facts are assigned monotonic IDs in a deterministic order") — fact IDs must
    be stable; the Schwartzian transform must not reorder equal keys.

Performance re-measurement (REPL, using the loaded `clojure-eval` workflow):

- The scenario is `test/clara/server/tools/graph/perf_test.clj` +
  `test/clara/server/tools/graph/rules/perf_gen_helpers.clj`.
  - `(perf-test/run-session! n)` builds a fired n-chain session.
  - Time the snapshot via `(time (memory/session-snapshot-from-analysis session analysis))`
    or `(time (memory/session-snapshot session))`.
  - `(perf-test/run-enrich-annotations-from-session!)` times the enrichment path.
- Re-run the same VisualVM sampler snapshot used to produce
  `cre-sampler-profiler-snapshot.xml` and confirm:
  1. `sort-facts` self-time drops from ~23s to the remaining one-per-fact
     stringification cost (target: low single-digit seconds or better).
  2. `deterministic-fact-str` / `pr-on` no longer dominate.
  3. `build-rule-match-index` prune-fns time drops.
  4. `enrich-annotations-from-session` `into` time drops.

Suggested sequencing: land Fix 1 + Fix 2 first (low risk, biggest win), re-measure,
then Fix 3 and Fix 4.

---

## 7. Deferred / out of scope

Recorded from the original report so they are not lost, but **not** part of this
plan (static-analysis side, not memory/snapshot):

- **`analyze-session-rules` (~7.7s):** clj-kondo re-runs per session analysis.
  The per-call `cache-atom` already exists but defaults to a fresh atom; a
  process-level (or state-atom-resident) cache keyed by rulebase/source identity
  would avoid the repeated `clj-kondo.core/run!`.
- **`build-dep-graph` (~6.6s):** report suggests set-based `downstream?` lookups.
  Note the current `build-dep-graph` already builds a `consumers-by-type` index
  and `->memoized-ancestors` memoizes ancestor *sets*; this needs its own
  investigation before proposing changes.

Structural opportunity worth a separate look (not in this plan):

- **Snapshot computed twice per session swap.** `build-auto-detect-annotations`
  → `->memory-layer` → `enrich-annotations-from-session` computes a
  `session-snapshot` (currently ~24s) and discards it; the request-time cache
  then computes it again in `cache/build-state` →
  `session-snapshot-from-analysis`. Reusing the enrichment-phase snapshot (or
  reordering so enrichment and cache build share one snapshot) is a 2× win on
  the largest cost. This is a caching/lifecycle change spanning `server.clj` +
  `cache.clj` + `analyze.clj`, so it is proposed separately from the hot-path
  fixes above.
