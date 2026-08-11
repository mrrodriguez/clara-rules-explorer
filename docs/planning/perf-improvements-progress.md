# Performance Improvements — Progress Tracker

Based on: `docs/planning/perf-improvements.md`

## Step 1: `core/rulebase-analysis` — 14.7s, two hot spots own 90%

### 1a. `build-dep-graph` is O(n²) in productions

**Status:** ✅ DONE

**Plan:** Build a `consumers-by-type` index (`consumed-type -> #{consumer-name}`),
then for each producer, for each produced type `pt`, walk `(cons pt (ancestors-set-fn pt))`
and union the consumer sets found. Linear in `(produced types) × (hierarchy depth)`
plus edges emitted, instead of O(n²) in productions.

**Implementation:**
- [x] Add `build-consumers-by-type` helper to `core.clj`
- [x] Replace `build-dep-graph` body with the inverted algorithm
- [x] Verify: `make test` passes (201 tests, 0 failures)
- [x] Verify: `make lint` clean (0 errors, 0 warnings)
- [x] Verify: `make reflection-check` clean (no warnings)

**Implementation notes:**

Added `build-consumers-by-type` — a private helper that builds a map from
consumed-type to the set of consumer names, in a single `reduce-kv` pass over
`type-analysis-map`.

Replaced `build-dep-graph`'s O(n²) nested-`for` candidate loop with a
two-phase algorithm:
1. Build `consumers-by-type` index (one pass over all consumers)
2. For each producer, for each produced type `pt`, walk `(cons pt (ancestors-set-fn pt))`
   and union the consumer sets found, adding edges for each non-self consumer.

The per-edge logic (`update-in` with `fnil conj #{}` into `:upstream`/`:downstream`)
is identical to the original `add-dep-graph-entry` — only the candidate enumeration
changed.

### 1b. `build-rule-summary-map` is mostly `clojure.pprint`

**Status:** ✅ DONE

**Plan:** Make the form printer injectable via a dynamic var so callers who
don't need pretty-printed sub-forms can bind it to a cheap printer (e.g.,
`pr-str` or `fipp.edn/pprint`).

**Implementation:**
- [x] Add `default-form-printer` and `^:dynamic *form-printer*` to `serialize.clj`
- [x] `serialize-condition`, `serialize-lhs-form`, `serialize-rhs-form` all read `*form-printer*` directly
- [x] `rulebase-analysis` docstring documents the dynamic var pattern
- [x] Add `fipp/fipp` to `:test` extra-deps (0.6.29)
- [x] Add `test-form-printer-dynamic-var` test demonstrating `binding` overrides with fipp and pr-str
- [x] Verify: `make test` passes (201 tests, 0 failures)
- [x] Verify: `make lint` clean (0 errors, 0 warnings)
- [x] Verify: `make reflection-check` clean (no warnings)

**Implementation notes:**

`serialize/*form-printer*` is a `^:dynamic` var defaulting to
`default-form-printer` (`with-out-str` + `pp/pprint`). The public API —
`rulebase-analysis` — accepts an explicit `:form-printer` opt and manages
the `binding` internally:
```clojure
;; Default (clojure.pprint)
(rulebase-analysis session annotations)

;; Cheap single-line printer
(rulebase-analysis session annotations {:form-printer pr-str})
```

The dynamic var is an implementation detail of `serialize.clj`; callers do
not bind it directly.

`fipp/fipp` is in `:test` extra-deps. A dedicated test in
`serialize_test.clj` demonstrates `binding` overrides with fipp and pr-str.

## Step 2: Artifact writing — make printer the caller's choice

**Status:** NOT STARTED

## Step 3: `rulebase-analysis` is pure — document and return cache key

**Status:** NOT STARTED
