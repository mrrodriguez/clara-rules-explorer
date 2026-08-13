# Performance — Rulebase Analysis & Lifecycle

Status: **Plan — for review. Not yet implemented.**

Scope: the three items **deferred** from
[`perf-improvements-memory-plan.md`](./perf-improvements-memory-plan.md)
(§7 there), which sit on the *static-analysis* and *lifecycle* side rather than
the working-memory snapshot path. The snapshot path itself (Fixes 1–4) has
landed, so the remaining hot spots are the ones this plan addresses:

| # | Deferred item | Profiler self-time | Priority |
|---|---------------|--------------------|----------|
| A | `enrich-annotations-from-session` computes a `session-snapshot` that is discarded, then `cache/build-state` computes it again for the same session | 2× the (now ~300 ms) snapshot cost per session swap | P0 |
| B | `analyze-session-rules` re-runs clj-kondo per analysis (cold start / session swap / reload) | ~7.7 s (≈4.6 s in `clj-kondo.core/run!`) | P0 |
| C | `build-dep-graph` reduce/`next` hotspot | ~6.6 s | P1 |

Baseline numbers are from
[`perf-memory-profiler-ss-findings.md`](./perf-memory-profiler-ss-findings.md)
(the VisualVM sampler snapshot) plus post-Fix-1–4 measurements recorded in
[`perf-improvements-memory-progress.md`](./perf-improvements-memory-progress.md).

---

## 0. Baseline (current code, post Fix 1–4)

Relevant facts that anchor the three fixes:

- `memory/session-snapshot` is now ~300 ms for ~4000 heavy bulk facts (was ~24 s
  before Fix 1–3). It has two arities: 1-arity (`known-set = #{}`) and 2-arity
  (analysis-derived `known-set`).
- `session-snapshot-from-analysis` = `(session-snapshot session (analysis fact-types))`.
  The only thing `known-set` changes is the per-fact `[:type :known]` flag
  (`memory.clj` `build-fact-table`); the expensive parts — `inspect/inspect`,
  `sort-facts`, `prune-fns`, match indices — do not depend on it.
- `analyze-session-rules` → `build-analysis-from-namespaces` →
  `get-or-analyze-ns-analysis` caches **by bare `ns-sym`** in an atom. The
  server threads a persistent `:analyze-cache` atom through state, but
  `transition-swap` resets it to `{}` whenever the session **identity** changes
  (`server.clj`), so a fresh session object (even one with identical rules)
  pays the full kondo cost again.
- `build-dep-graph` (`core.clj`) already uses a `consumers-by-type` index and a
  memoized `ancestors-set-fn`; `downstream?` is no longer in the graph-build
  loop (only used later for `:match` serialization). The 6.6 s trace predates
  that refactor and must be re-measured before optimizing.

---

## 1. Fix A — reuse the enrichment-phase snapshot (P0)

**Files:** `server/src/clara/server/tools/graph/analyze.clj`,
`server/src/clara/server/tools/graph/memory.clj` (small additive change),
`server/src/clara/server/graph/server.clj`,
`server/src/clara/server/graph/cache.clj`.

### Problem

For a memory-enriched session swap the snapshot is built **twice** for the same
session:

1. `server/build-auto-detect-annotations` → `analyze/->memory-layer` →
   `enrich-annotations-from-session` → `(memory/session-snapshot session)` —
   used for `rule->session-raw-types`, then **discarded**.
2. `server/swap-session!` → `cache/warm!` → `cache/build-state` →
   `(memory/session-snapshot-from-analysis session analysis)` — the request-time
   snapshot, which only *additionally* needs the analysis `known-set`.

The second build re-runs `inspect/inspect`, `sort-facts`, `prune-fns`, and every
index rebuild. With Fix 1–3 landed this is "only" ~300 ms, but it is pure waste:
the two snapshots differ **only** in the per-fact `:known` flag.

### Fix

Thread the enrichment-phase snapshot through to the cache build, and re-stamp
`:known` (cheap, O(facts)) instead of rebuilding.

1. `memory.clj` — add a re-stamp helper:

   ```clojure
   (defn update-snapshot-known-set
     "Re-derives the per-fact :type :known flag of an existing snapshot from
      `known-set` (serialized fact-type names), without re-inspecting the
      session. Re-stamps every fact entry wherever it appears — :facts, the
      rule/query match indices, and the :fact-types inserted-from/used-by role
      grouping — still O(total fact entries)."
     [snapshot known-set]
     (letfn [(stamp [fact]
               (assoc-in fact [:type :known]
                         (contains? known-set (get-in fact [:type :name]))))
             (stamp-facts [facts] (mapv stamp facts))
             (stamp-roles [roles] (mapv #(update % :facts stamp-facts) roles))]
       (-> snapshot
           (update :facts #(reduce-kv (fn [m id f] (assoc m id (stamp f))) {} %))
           (update :rule-matches
                   #(reduce-kv (fn [m k {:keys [matches inserted-facts]}]
                                 (assoc m k {:matches (stamp-facts matches)
                                             :inserted-facts (stamp-facts inserted-facts)}))
                               {} %))
           (update :query-matches
                   #(reduce-kv (fn [m k {:keys [matches]}]
                                 (assoc m k {:matches (stamp-facts matches)}))
                               {} %))
           (update :fact-types
                   #(reduce-kv (fn [m t e]
                                 (assoc m t (-> e
                                                 (update :inserted-from stamp-roles)
                                                 (update :used-by stamp-roles))))
                               {} %)))))
   ```

2. `analyze.clj` — split enrich into a tuple-returning core and a thin wrapper:

   ```clojure
   (defn enrich-annotations-from-session*
     "Like `enrich-annotations-from-session`, but returns
      {:annotations enriched :snapshot snapshot} so a caller that also needs
      the working-memory snapshot (the cache build) reuses it instead of
      re-inspecting the session."
     [session annotations]
     (let [original (ann/normalize-annotations annotations)
           snapshot (memory/session-snapshot session)
           enriched (add-auto-detected-annotations snapshot original)
           …] ;; existing enrich body, unchanged
       {:annotations <enriched map>
        :snapshot  snapshot}))

   (defn enrich-annotations-from-session
     [session annotations]
     (:annotations (enrich-annotations-from-session* session annotations)))
   ```

   `->memory-layer` keeps returning **just a Layer**. It is refactored to take
   the already-computed `base` and `enriched` maps and only compute the delta
   and wrap it:

   ```clojure
   (defn ->memory-layer
     "Builds a working-memory annotation Layer from `enriched` (the result of
      enriching `base` from session working memory): the delta of what
      enrichment added over `base`, wrapped as a validated Layer with id
      `:clara.tools.graph.analyze/memory`. Returns nil when nothing new was
      added."
     [base enriched]
     (let [delta (ann/annotations-delta base enriched)]
       (when (seq delta)
         (ann.merge/annotations-delta->layer
          :clara.tools.graph.analyze/memory
          {:generated-by "clara-rules-explorer"
           :derived-from "session working memory"
           :rule-count (count delta)}
          delta))))
   ```

   (`->memory-layer` has a single caller — `server/build-auto-detect-annotations`
   — so its signature change is internal.)

3. `server.clj` — `build-auto-detect-annotations` is the composition root that
   owns the snapshot. It calls `enrich-annotations-from-session*` **directly**
   (not via `->memory-layer`), keeps the snapshot, and lets `->memory-layer`
   wrap the delta:

   ```clojure
   (defn- build-auto-detect-annotations
     [session source enrichment analyze-cache-atom]
     (let [wm? (core/working-memory-available? session)]
       (when (and (#{:auto-detect-from-memory :auto-detect} enrichment) (not wm?))
         (println …))
       (let [static-layers (build-static-layers session source enrichment analyze-cache-atom)
             merged-static (ann.merge/merge-layers static-layers)
             base          (ann.merge/annotations merged-static)
             memory?       (and wm? (#{:auto-detect-from-memory :auto-detect} enrichment))
             {:keys [annotations snapshot]}
             (when memory?
               (analyze/enrich-annotations-from-session* session base))
             memory-layer  (when memory? (analyze/->memory-layer base annotations))]
         {:annotations (if memory-layer
                         (-> (conj static-layers memory-layer)
                             ann.merge/merge-layers
                             ann.merge/annotations)
                         base)
          :memory-snapshot snapshot})))
   ```

   `build-annotations` threads the tuple: its auto-detect branch returns the map
   above; the non-memory branches return `{:annotations … :memory-snapshot nil}`.
   `transition-start` / `transition-swap` / `transition-reload` store
   `:memory-snapshot` in the state map (nil when not memory-enriched).

4. `cache.clj` — `build-state` takes the memory snapshot as `:memory-snapshot`
   and only re-stamps `:known` instead of re-inspecting:

   ```clojure
   (defn- build-state [session annotations memory-snapshot]
     (let [bare      (ann.merge/->bare-annotations annotations)
           analysis  (core/rulebase-analysis session bare)
           known-set (-> analysis :fact-types keys set)
           snapshot  (if memory-snapshot
                       (memory/update-snapshot-known-set memory-snapshot known-set)
                       (memory/session-snapshot-from-analysis session analysis))]
       {:analysis analysis
        :snapshot snapshot}))
   ```

   `get-state` / `warm!` gain a `memory-snapshot` parameter, and the three shell
   call sites (`start-system!`, `swap-session!`, `reload-annotations!`) pass
   `(:memory-snapshot state)`. `:memory-snapshot` is an **input** to `build-state`,
   not stored in the cache; the cache continues to store the re-stamped snapshot
   under `:snapshot`.

### Expected impact

Eliminates the duplicate `inspect` + `sort-facts` + `prune-fns` + index build per
memory-enriched swap: **~300 ms → ~0 ms** (plus the O(facts) `:known` re-stamp,
which is negligible). One full snapshot per swap instead of two.

### Correctness / risk notes

- The reused snapshot is built with `known-set = #{}` (all `:known` false); the
  re-stamp sets the exact same flags `session-snapshot-from-analysis` would.
  `:known` is baked into fact entries in **four** places (`:facts`, rule/query
  match indices, and the `:fact-types` role grouping), so the re-stamp walks all
  four — verified byte-equal to a freshly-built analysis-derived snapshot.
- `:fact-raw-types`, `:used-by`, `:origin`, `:rule-matches`, `:query-matches`,
  and the three `*-id-index` maps are all `known-set`-independent and carry over
  unchanged.
- The cache's `identical?` invalidation is unchanged — the snapshot is only
  reused for the same session object, or explicitly keyed to the session the
  transition produced.
- Edge: non-memory enrichment modes produce no memory snapshot; `build-state`
  falls back to the existing `session-snapshot-from-analysis` path.
- `enrich-annotations-from-session*` is public (server.clj calls it); the
  `enrich-annotations-from-session` wrapper keeps its exact current return value,
  so the four test call sites and the perf harness are untouched.
- `->memory-layer`'s signature changes from `{:keys [session annotations]}` to
  `[base enriched]`; it has no callers outside `build-auto-detect-annotations`
  (verified) and is not exercised directly in tests.

---

## 2. Fix B — content-addressed clj-kondo cache (P0)

**Files:** `server/src/clara/server/tools/graph/analyze.clj`,
`server/src/clara/server/graph/server.clj` (transition seeding).

### Problem

`analyze-session-rules` runs clj-kondo over each rule-owning namespace's
*synthesized* source (real source + snippet defs) and over transitive classpath
dependencies. The cache already exists but:

- It is keyed **by bare `ns-sym`** (`get-or-analyze-ns-analysis`), so it cannot
  distinguish two different sessions (or two edits of the same file) that define
  rules in the same namespace — hence the blunt `transition-swap` reset to `{}`
  on any session identity change.
- Because of that reset, re-swapping a freshly-built-but-identical session
  re-pays the full ~7.7 s kondo cost.
- The cache lives only in the state atom; a process restart is a cold ~7.7 s
  (acceptable, but noted).

### Fix

Key the cache by **content identity**, not namespace alone:

1. In `build-analysis-from-namespaces`, compute a per-namespace cache key from
   the actual source clj-kondo will parse:

   ```clojure
   ;; synthesized ns: hash of the combined source string + config-dir
   ;; classpath ns:   hash of the slurped source (or resource URL + slurp) + config-dir
   (defn- ns-analysis-cache-key [ns-sym source-or-nil config-dir]
     [ns-sym (hash source-or-nil) config-dir])
   ```

   and change `get-or-analyze-ns-analysis` to take that key instead of `ns-sym`.

2. `analyze-session-rules` / `build-analysis-from-namespaces` already know the
   source at the call site (`synth` map or `resource-url`); slurp/hash there and
   pass the key through. The `::combined-sources` map is already built in
   `analyze-session-rules` — its `:source` strings are the natural hash input.

3. `server.clj` `transition-swap`: stop resetting the cache to `{}` on session
   identity change; instead prune entries whose namespace is no longer present
   in the current rulebase (bounded memory), e.g.

   ```clojure
   (let [live-nss (set (analyze/extract-session-namespaces s))]
     (into {} (filter (fn [[k _]] (contains? live-nss (first k)))) seed))
   ```

   Content-hash keying makes a stale entry harmless (a changed source gets a new
   key), so pruning is purely a memory-boundedness concern, not correctness.

### Expected impact

- Re-swap of an identical rulebase (the common reload/swap pattern) hits the
  cache: **~7.7 s → near-0 s** for the kondo phase.
- Session swaps to genuinely different rules correctly re-run kondo only for
  namespaces whose source actually changed (and only once per distinct source).
- Cold process start remains ~7.7 s (first analysis). Optional disk persistence
  of the analysis map is **out of scope** for this plan (large EDN, separate
  invalidation concerns) — flag if cold-start cost becomes a requirement.

### Correctness / risk notes

- `hash` of a source string is a *fingerprint*, not a proof — a hash collision
  would serve a stale analysis. Use a stronger digest (`sha-256` hex of the
  source) if we want collision-proofing; the cost is trivial (one digest per
  namespace, not per fact). Recommend sha-256 for safety.
- The cache-key change is internal: `get-or-analyze-ns-analysis` and
  `build-analysis-from-namespaces` are private; `analyze-session-rules`'s public
  `:cache-atom` option keeps its shape (atom of `cache-key → analysis`).
- `reload-annotations!` (file edit on disk) benefits automatically: the classpath
  source hash changes → new key → re-analysis; unchanged files stay cached.
- Memory: one entry per (namespace × distinct source). Bounded by the pruning
  pass in (3); note it in review if the service swaps many distinct rulebases
  without restarting.

---

## 3. Fix C — `build-dep-graph` investigation + optimization (P1)

**Files:** `server/src/clara/server/tools/graph/core.clj`.

### Problem

The profiler snapshot attributed ~6.6 s to `build-dep-graph` → `reduce` →
`next`, with leaf `downstream?`/`some`/`equiv` checks. That trace is from the
**pre-refactor** code: the current `build-dep-graph` already replaced the
O(n²) `downstream?` candidate loop with a `consumers-by-type` index + a
memoized `ancestors-set-fn`. So the 6.6 s figure is likely stale — but it has
**not been re-measured**, and the current loop still has obvious allocation
churn worth checking.

### Fix

**Gate 1 — measure first.** Re-run the analysis on the same scenario used for
the original snapshot (or the perf harness chain rules) and time
`core/rulebase-analysis` with and without `build-dep-graph`. If it is no longer
a hotspot, close this item with a measurement note and no code change.

**Gate 2 — if still hot, the concrete candidates, in order:**

1. **Avoid the per-produced-type set union allocation.** Current code:

   ```clojure
   :let [consumers (reduce into #{}
                           (keep consumers-by-type
                                 (cons pt (ancestors-set-fn pt))))]
   ```

   builds a fresh set per produced type. Precompute `consumers-by-type` values
   as vectors once, then collect consumer names with a `transient` set or a
   plain `into []` + `distinct`, and iterate directly (no intermediate set).

2. **Accumulate edges in flat transient structures instead of `vswap!` +
   `update-in` per edge.** The current per-edge update does two `update-in`
   calls (two map allocations) inside a `vswap!`. Build two flat maps
   (`producer→#{consumers}`, `consumer→#{producers}`) via `volatile!`/`transient`
   and assemble `{:upstream … :downstream …}` once at the end.

3. **Profile the `ancestors-set-fn` calls.** `->memoized-ancestors` memoizes
   `(set (ancestors-fn t))` per type — fine, but confirm the `ancestors-fn`
   itself (recovered from `:get-alphas-fn` meta, falling back to
   `clojure.core/ancestors`) is not the hidden cost; if it is, cache the raw
   ancestor *seq* (not just the set) or precompute the closure of
   produced-types → consumers once per analysis.

### Expected impact

Unknown until Gate 1. If the 6.6 s persists, candidates 1–2 remove the dominant
allocation churn (the `reduce into #{}` per produced type + per-edge `update-in`
pairs) and are expected to cut a large fraction of it. This is deliberately
investigation-first: no speculative rewrite without a fresh profile.

### Correctness / risk notes

- Output shape of `build-dep-graph` (`{name {:upstream #{…} :downstream #{…}}}`)
  is pinned by tests and the analysis consumers; any optimization must preserve
  it exactly (set equality, no ordering guarantee already).
- `->memoized-ancestors` stays scoped to one `rulebase-analysis` call (no
  cross-call retention), consistent with the memoization constraint used for
  Fix 3 of the memory plan.

---

## 4. Verification

All changes are in `server/`; use the Makefile targets:

```bash
cd server
make test              # full suite
make lint              # clj-kondo
make reflection-check  # *warn-on-reflection* true
make format-check
```

Targeted behavior pins:

- **Fix A**: existing memory/snapshot tests (`memory_test.clj`) and the four
  `enrich-annotations-from-session` test call sites must pass unchanged; add a
  test asserting `update-snapshot-known-set` produces `:known` flags identical
  to a freshly-built `session-snapshot-from-analysis` for a small fired session.
- **Fix B**: add a test that two `analyze-session-rules` calls over *different*
  source strings for the same namespace (via `:ns-source-map` / a synthetic
  session) produce different analyses and do not cross-serve from the cache;
  and that two calls over the *same* source hit the cache (assert the kondo
  run count via a test-only hook or a counter atom).
- **Fix C**: existing `core_test.clj` dep-graph tests pin the graph shape.

Performance re-measurement (scratch scripts under `server/target/tmp/`, or the
VisualVM sampler):

- **Fix A**: time `server/swap-session!` (or `build-annotations` +
  `cache/warm!`) before/after for a memory-enriched swap; confirm one snapshot
  build, not two (instrument `memory/session-snapshot` with a counter or use a
  profiler).
- **Fix B**: time `analyze-session-rules` cold vs. re-swap of an identical
  rulebase; the latter should collapse to near-0.
- **Fix C**: time `rulebase-analysis` on the original scenario; record
  `build-dep-graph` self-time before/after Gate 2 optimizations.

---

## 5. Sequencing & stopping points

1. **Fix A** (snapshot reuse) — self-contained, biggest per-swap win, low risk.
   Verify, stop for review.
2. **Fix B** (kondo cache) — self-contained, largest single cold/re-run cost.
   Verify, stop for review.
3. **Fix C** (build-dep-graph) — measure first, then optimize only if the gate
   justifies it. Verify, stop for review.

Each step logs to a progress file (to be created alongside implementation),
mirroring `perf-improvements-memory-progress.md`.
