# Performance improvements

Findings from profiling the analysis + artifact-writing pipeline against a large
production rulebase, and the changes they suggest.

## Measurement subject

A single restored Clara session:

| dimension | count |
| --- | --- |
| productions (rules) | 4,528 |
| productions (queries) | 372 |
| distinct fact types | 5,026 |
| Rete nodes | 24,650 |
| merged annotations, printed | ~8.6 MB |
| `slim`med analysis, printed | ~19 MB |

Every number below is wall clock on that session, measured at a REPL with the
rulebase already loaded. They are meant as ratios, not absolutes — the shapes
(`O(n²)`, per-form printer setup) are what generalizes.

## 1. `core/rulebase-analysis` — 14.7s, and two hot spots own 90% of it

Timing each `let` binding of `rulebase-analysis` in isolation:

| stage | time |
| --- | --- |
| `build-dep-graph` | 7.0s |
| `build-rule-summary-map` | 6.4s |
| `build-production-annotation-map` | 1.1s |
| `ft/build-fact-type-summary-map` | 0.17s |
| `build-query-summary-map` | 0.12s |
| `nodes/build-nodes` | 0.10s |
| `build-production-map` | 0.04s |
| `ft/build-ancestors-index` | 0.03s |
| `ft/known-type-names` | 0.02s |
| `build-type-analysis-map` | 0.02s |
| `ft/build-fact-type-id-index` | 0.002s |
| everything else | < 0.01s |

### 1a. `build-dep-graph` is `O(n²)` in productions

`core.clj`'s `producer-consumer-pairs` is a nested `for` over `type-analysis-map`
against itself, so it evaluates ~4,900² ≈ **24M** candidate pairs, and each one
surviving the `:when` runs `some-type-consumed?` — a nested `some` over
produced × consumed types with an `ancestors-set-fn` lookup per combination. The
result has 4,710 entries, so the overwhelming majority of that work establishes
that a pair is *not* an edge.

The relation is invertible. An edge exists when some produced type of the
producer is downstream of some consumed type of the consumer, and "downstream"
is exactly `consumer-type ∈ (conj (ancestors producer-type) producer-type)`. So
build one index:

```
consumers-by-type : consumed-type -> #{consumer-name}
```

then for each producer, for each of its produced types `pt`, walk
`(cons pt (ancestors-set-fn pt))` and union the consumer sets found. That is
linear in `(produced types) × (hierarchy depth)` plus the edges actually
emitted, instead of quadratic in productions. `ancestors-set-fn` is already
memoized, so the hierarchy walk is cheap after first touch.

`matching-type-pairs` already follows this discipline — its docstring notes it is
computed only for real edges, "never in the O(n²) candidate loop". The candidate
loop itself is the remaining offender.

### 1b. `build-rule-summary-map` is mostly `clojure.pprint`

Substituting the printer used inside `serialize.clj` and re-timing that stage:

| printer | `build-rule-summary-map` |
| --- | --- |
| `clojure.pprint/pprint` (current) | 6.0s |
| `fipp.edn/pprint` | 3.9s |
| `pr-str` | 1.9s |

So **~4.1s of the 6.0s is pretty-printing**, from `serialize-condition` (one
`pp/pprint` per constraint and per arg, via `serialize-forms`),
`serialize-lhs-form`, and `serialize-rhs-form`. At this scale that is tens of
thousands of individual `pp/pprint` calls on small forms, where the per-call
setup of the pretty writer dominates the formatting itself.

Note that fipp only recovers half the gap here, because it also pays a
per-invocation setup cost; on many small forms that cost is the problem, not the
layout algorithm. Options, in increasing order of behavior change:

1. Make the printer injectable (see §2) so a caller who does not need
   pretty-printed sub-forms can pass a cheap one.
2. Print lazily. `:lhs` / `:lhs-form` / `:rhs-form` are display strings; the
   summary could carry the form and render at the API boundary, so a caller who
   only wants the dep graph never pays for them.
3. Cache by form. Constraint forms repeat heavily across rules in a generated or
   templated rulebase, so a memo keyed on the form would collapse much of the
   work.

## 2. Artifact writing — the printer should be the caller's choice

`annotations.merge/write-layer!` hardcodes `clojure.pprint/pprint`, as do
`graph.main`'s analysis output and `serialize.clj`'s form rendering. For
artifacts in the tens of megabytes this dominates everything else in a persist
step.

Printing the `slim`med analysis of the subject session:

| writer | time | output size |
| --- | --- | --- |
| `clojure.pprint/pprint` | 27.6s | 19.2M chars |
| `fipp.edn/pprint` | 2.8s | 39.4M chars |
| `pr-str` | 0.23s | 18.2M chars (one line) |

All three round-trip to `=` values through `clojure.edn/read-string`, and none
leak metadata when written under `*print-meta* false`.

The trade is real in both directions, which is the argument for not picking one:

- `fipp` is ~10x faster but ~2x larger, because it breaks groups all-or-nothing
  where `clojure.pprint` fills lines. Output size is nearly insensitive to
  `:width` — widening from 72 to 240 changed it by under 3%.
- `pr-str` is ~120x faster and the smallest, but a single line is useless if the
  artifact is reviewed as a diff.

Which of those matters depends entirely on what the consumer does with the file.
A caller committing artifacts to git and reviewing them as diffs wants the
compact fill layout; a caller regenerating on every run and feeding the result
to a machine wants speed.

### Suggested API shape

Do not hardcode `fipp` or anything else — take the printer as an option and
default to today's behavior, so nothing changes for existing callers and no new
dependency is introduced:

- `annotations.merge/write-layer!` — accept an opts map carrying a printer fn
  `(fn [value writer] ...)`, defaulting to `clojure.pprint/pprint`. This is the
  important one: it is the public write path callers use for every layer file.
- `serialize.clj`'s `serialize-condition` / `serialize-lhs-form` /
  `serialize-rhs-form` — take a form printer `(fn [form] String)` threaded from
  `rulebase-analysis` opts, defaulting to the current `with-out-str` +
  `pp/pprint`. This is what makes §1b addressable without forking the ns.
- `graph.main`'s analysis output — the same option, wired to whatever CLI flag
  fits.

A dynamic var would also work and is less invasive to signatures, but an
explicit option is easier to reason about across the thread boundaries the
server introduces.

## 3. `rulebase-analysis` is pure — say so, so callers can cache

`rulebase-analysis` reads only `:productions`, `:id-to-node`, and the
`:get-alphas-fn` metadata off the rulebase, plus the merged annotations value.
It touches no working memory and no mutable state, so the same
`(rulebase, merged-annotations)` pair always yields an equal result.

This matters because the natural pipeline shape —

```
generate annotations  -> analysis over [props, generated]
enrich from memory    -> analysis over [props, generated, memory]
persist               -> analysis over the layer stack read back from disk
```

— calls it three times, and in the common case the second and third calls are
computed over *equal* merged-annotation values, paying full cost for an
identical result. Downstream that was 45s of a 124s pipeline, 30s of it pure
recomputation.

Callers can fix this themselves once they know it is safe, and the merged value
is cheap enough to use directly as the cache key: `=` over the 8.6 MB merged
annotations of the subject session takes **10ms**, against 14.7s to recompute.

Two things would help callers, neither of which changes behavior:

1. State the purity contract in the `rulebase-analysis` docstring — the result is
   a function of the rulebase and the merged annotations alone, and callers may
   cache on that pair.
2. Where the library hands back an analysis alongside the layer it generated,
   also hand back the merged value it was computed over. Without that, a caller
   holding an analysis cannot tell whether it is still valid for the stack it is
   about to persist, and has to fall back on a proxy such as "are the layer ids
   the same" — which is weaker and wrong in both directions: equal ids can carry
   different content, and different ids can fold to the same annotations.

An alternative to (2) is for the library to memoize the last
`(rulebase, merged) -> analysis` pair itself. That is less work for callers, but
it pins a large result in memory for the life of the process, so it should be
opt-in if it is done at all.

## Summary of expected wins

| change | scope | saving on the subject session |
| --- | --- | --- |
| Invert `build-dep-graph` | library | ~7s per analysis |
| Injectable form printer in `serialize.clj` | library + caller | up to ~4s per analysis |
| Injectable printer in `write-layer!` | library + caller | ~25s per large artifact |
| Document purity, return the merged value analyzed | library + caller | ~15s per redundant call avoided |
