# Fix Working-Memory Annotation Enrichment Flow — Implementation Plan

This plan has two phases. **Phase 1** consolidates the server's stateful
constructs — the state architecture is fixed *first* so the enrichment work
is not built on workarounds. **Phase 2** is the WM enrichment flow itself,
expressed on the consolidated state.

HTTP is a **read-only** contract: `POST /v1/annotations/reload` is removed;
`swap-session!` / `reload-annotations!` (in-memory) are the only mutation
API. A reload endpoint can be reintroduced later on top of the proven
in-memory API if a workflow needs it.

## Problem

The `hot-swap-session-data` branch refactored the analysis/cache pipeline and
dropped session working-memory enrichment from the server startup path.
Consequence: fact types inserted at runtime (dynamic inserts with
`:resolution :none` — e.g. `AuditTrail`, `ComplianceReview`,
`compliance-review-result`) and their type-hierarchy linkages disappeared from
`/v1/fact-types`.

The `swap-session!` path has enrichment via auto-detect modes, but `start!`
only accepts `:layers` (file paths) with no way to specify an enrichment mode.

---

# Phase 1: Consolidate Server State

## Motivation — the defect evidence

`server.clj` currently holds **six** `defonce` atoms: `server-instance`,
`session-atom`, `annotations-atom`, `config-atom`, `cache-atom` (an atom
holding the cache atom created inside `api/app`), and `analyze-cache-atom`.

The count isn't the problem — the **sharding** is. Session, annotations-spec,
annotations, and analyze-cache form *one logical unit of state*: they change
together or they lie. Every defect observed in the enrichment work was a
shard-synchronization bug, not an enrichment-logic bug:

| Defect | Shard failure |
|---|---|
| Reload degrades after swap | built *result* stored where the *spec* belonged (`config-atom` vs `annotations-atom` confusion) |
| Racy deref after swap | `reset!` on one atom, then `@` on it while others change |
| Stale kondo analysis after `start!` restart | `analyze-cache-atom` clearing rule lived only in `swap-session!` |
| Latent: mismatched cache entries | `cache/get-state` derefs `session-atom` and `annotations-atom` as two separate reads; a mid-swap request can pair session_t+1 with annotations_t and serve/cache it |

Two structural smells reinforce this:

- **`cache-atom` is an atom holding an atom** — a side channel drilled because
  `api/app` owns the cache but `server` needs to warm it. Ownership boundary
  in the wrong place.
- **Global singleton state makes tests interfere.** `server_test.clj` and
  `integration_test.clj` both call the global `start!`; both routers close
  over the same shared atoms, so two servers in one JVM cannot serve
  different sessions. The suites pass only because they run sequentially.

**Conclusion:** consolidate to one state atom per server *instance*, with
pure transition functions as the update API. Do this before Phase 2 so the
enrichment flow is designed on the correct foundation instead of encoding
workarounds.

## Target design

### State (pure data, one atom per system)

```clojure
{:session           ;; live Clara session or raw rulebase
 :annotations-spec  ;; the AnnotationsSpec that produced :annotations — ALWAYS a spec,
                    ;; never a built result (this is the reload-correctness invariant)
 :annotations       ;; derived bare annotations (source of truth for analysis)
 :analyze-cache}    ;; per-ns kondo memoization — a plain IMMUTABLE MAP value
                    ;; ({ns-sym → analysis}), reseeded to {} when session
                    ;; identity changes.  Lifecycle owned by the transitions
                    ;; (see "Transition contracts" → analyze-cache design).
```

### System handle (returned by `start-system!` / `start!`)

```clojure
{:config      ;; validated StartOpts
 :state-atom  ;; atom of the state map above
 :cache       ;; request cache cell (atom, identity-invalidated memoization)
 :handler     ;; ring handler
 :server}     ;; Jetty instance
```

### Pure transitions — the actual API, trivially unit-testable

```clojure
(defn- transition-start  [config] ...)                    ;; Config -> State
(defn- transition-swap   [state {:keys [session annotations]}] ...)
(defn- transition-reload [state] ...)  ;; re-runs (:annotations-spec state)
```

`transition-swap` records the spec as-given (nil for a session-only swap —
matching the documented "annotations cleared" semantics), reseeds
`:analyze-cache` when the session identity changes, and derives
`:annotations` via `build-annotations` (Phase 2). `transition-reload`
re-derives from the stored spec against the current session — file-backed
sources re-read from disk, the memory layer re-derives. Because the
transition receives the whole immutable state, `:reuse` enrichment stops
being a cross-atom read entirely.

### Transition contracts

```clojure
(defn- transition-start
  "Config -> State.  Builds fresh state from a VALIDATED StartOpts
   (schema validation is the shell's job — start-system! s/validates first).
   `props-layer` accepts a session or a raw rulebase, so rulebase input
   flows through the same build path unchanged."
  [config]
  ...)

(defn- transition-swap
  "State -> {:session ... :annotations ...} -> State.
   Receives ONLY the state-affecting opts; :warm-cache? is a shell concern
   (a side effect) and never reaches the transition."
  [state {:keys [session annotations]}]
  ...)

(defn- transition-reload
  "State -> State.  Re-derives :annotations from (:annotations-spec state)."
  [state]
  ...)
```

Postconditions per key:

| Key | `transition-start` | `transition-swap` | `transition-reload` |
|---|---|---|---|
| `:session` | `(:session config)` | given session, else carry over | carry over |
| `:annotations-spec` | `(:annotations config)` as-given (may be nil) | `(:annotations opts)` as-given (may be nil) | carry over |
| `:annotations` | built via `build-annotations` | rebuilt | rebuilt from stored spec |
| `:analyze-cache` — **seed** | `{}` | `{}` iff session given AND not `identical?` to current; else the carried-over value | carried-over value |
| `:analyze-cache` — **committed** | `@tmp` (startup kondo results retained) | `@tmp` | `@tmp` (≡ seed when nothing new was analyzed) |

All three transitions thread a temporary atom through `build-annotations`
and commit `@tmp` — **including `transition-start`**: seeding `{}` and
committing `@tmp` retains the startup kondo analysis, so the first
`reload-annotations!` or annotations-only swap doesn't re-analyze every
namespace the session just paid for.

**`build-annotations` contract:** accepts `nil` (→ `{}`), an `AnnotationsSpec`
map, or a bare form. nil is a *designed* input — both boundary schemas use
`(s/maybe AnnotationsArg)` and session-only swaps rely on nil → `{}` — so it
is documented on the function itself.

**`:analyze-cache` design — plain value in the state map, threaded through a
per-build temporary atom.** `analyze-session-rules` requires a mutable
`:cache-atom` holding `{ns-sym → analysis}` (writes are idempotent
`swap! assoc` per namespace). The transition creates a temporary atom seeded
from the incoming `:analyze-cache`, passes it down through
`build-annotations`, and commits `@tmp` into the new state:

```clojure
(defn- transition-swap [state {:keys [session annotations]}]
  (let [s     (if (some? session) session (:session state))
        seed  (if (and (some? session)
                       (not (identical? session (:session state))))
                {}
                (:analyze-cache state))
        tmp   (atom seed)
        built (build-annotations s annotations (:annotations state) tmp)]
    {:session          s
     :annotations-spec annotations   ;; the spec as-given, possibly nil
     :annotations      built
     :analyze-cache    @tmp}))
```

Alternatives considered and rejected:

- **Atom stored inside the state map** — a mutable cell inside "pure" state
  breaks value semantics: transition tests can no longer compare states as
  plain data, and the state map stops being something you can print, diff,
  and reason about. The tmp-atom design keeps the committed state a pure
  value.
- **Atom owned by the system handle** — splits the cache lifecycle out of
  the transitions, recreating the "forgot to clear on restart" hazard. The
  reseed rule must live where session identity changes: inside the
  transitions. The memoization-cell carve-out for the *request* cache still
  stands — that cell self-invalidates by identity and has no lifecycle rule
  to own.

The reseed rule is session-identity-based **only**. Changing the sidecar
source without changing the session does NOT reseed: kondo analyzes rule
*sources*, not annotation files, so cached per-ns analyses remain valid.

**Concurrency & purity:** all mutation entry points are operator-driven —
REPL, CLI, tests — never HTTP, so swaps are effectively single-threaded and
`swap!` CAS retries are not a live concern. Transitions are kept pure
regardless: it makes them unit-testable as plain functions over values and
keeps reasoning local. If a concurrent mutation path is ever introduced,
serialize mutations with a lock rather than designing around retries.
Transitions perform value-deterministic I/O by design — sidecar re-reads
(reload's whole purpose), kondo analysis (which may materialize its bundled
config), and diagnostic prints such as the WM-unavailable warning. These are
read-only/idempotent from the state's perspective. Non-idempotent effects
(Jetty start/stop, cache `warm!`) stay in the shells as a
separation-of-concerns rule, not a retry-safety one.

### Public API — system-first, with a default-system facade

```clojure
(defn start-system! [config] ...) ;; -> system; pure constructor — never
                                  ;;    touches default-system, never stops
                                  ;;    anything
(defn start! [config] ...)        ;; -> system; start-system! + default-system
                                  ;;    management (operator/REPL entry)
(defn stop! ([] ...) ([system] ...))
(defn swap-session! ([opts] ...) ([system opts] ...))   ;; -> bare annotations
(defn reload-annotations! ([] ...) ([system] ...))      ;; in-memory only
```

The 1-arity forms operate on the `default-system` (set by the most recent
`start!`), preserving every existing callsite: `main.clj`,
`dev/hierarchy_run.clj`, `demo_run.clj`, tests, and external REPL embedding
all keep working unchanged. The 2-arity forms plus `start-system!` enable
isolated per-fixture systems in tests.

Side effects (Jetty start/stop, cache warming, `println` diagnostics) stay
**outside** the `swap!` as a separation-of-concerns rule (see
"Concurrency & purity" above). Warming uses the state returned by `swap!`,
never a follow-up deref.

Default-system semantics:

- **Construction and registration are separate operations.** `start-system!`
  is the pure constructor: validate, build state, warm, bind Jetty, return
  the system map. It never touches `default-system` and never stops another
  system — tests use it for true isolation (a test that went through
  `start!` would both stop the fixture's Jetty and hijack the default
  mid-suite).
- `start!` is the operator entry: if the previous default is bound to the
  **same port**, stop its Jetty **before** binding (same-port restart — the
  dominant REPL/`demo-run` workflow — must stop first or `run-jetty` throws
  `BindException`; this matches the current code's ordering). Then it
  delegates to `start-system!` and registers the result as the default.
- A previous default on a **different port** keeps running (multi-instance
  via `start!`); the default pointer moves and the orphaned system stays up
  — capture the earlier `start!` return value or `stop!` it explicitly.
- `stop!` 0-arity stops the default and resets it to nil; `stop!` 1-arity
  resets the default too when stopping the system that *is* the default
  (`identical?` check), so no dangling default points at a stopped system.
- 1-arity mutations with no system started fail fast via an explicit
  `require-system` guard ("no explorer system started") instead of NPE-ing
  on a nil deref.
- **Test pattern:** tests that need isolation call `start-system!` and use
  the 2-arity forms exclusively — the fixture server is never stopped and
  the default is never hijacked mid-suite. cognitect test-runner (the
  `:run-tests` alias) runs namespaces sequentially in one JVM, so
  fixture/default interplay is deterministic.

### HTTP contract: read-only

- **Remove `wrap-reload` and `POST /v1/annotations/reload`.** The endpoint is
  too limiting (re-derive only, no new state) and redundant once
  `swap-session!` / `reload-annotations!` work correctly in-memory.
- No swap endpoint exists today and none is added. HTTP never mutates server
  state; all mutation goes through the in-memory API.
- Removal surface: `wrap-reload` + the reload branch in `server.clj`, the
  `post-annotations-reload` helper and its assertion block in
  `integration_test.clj`, and the `POST /v1/annotations/reload` section in
  `docs/explorer-graph-api.md` (line ~494). The UI never calls it.

### `api.clj` — handlers read one coherent state

`app` receives the single `state-atom` (plus the cache cell) instead of
separate session/annotations atoms:

```clojure
(defn app [state-atom working-memory-enabled?]
  (let [cache (cache/create)]
    {:handler (ring/ring-handler
               (router state-atom cache working-memory-enabled?)
               (ring/create-default-handler))
     :cache cache}))
```

Each handler derefs `state-atom` **once per request** and passes plain values
down — a coherent `(session, annotations)` pair by construction:

```clojure
(let [{:keys [session annotations]} @state-atom]
  (cache/analysis cache session annotations))
```

`wm-route`'s per-request `working-memory-available?` check reads
`(:session @state-atom)` from the same deref.

### `cache.clj` — values in, not atoms

`get-state` / `analysis` / `snapshot` / `warm!` change signature from
`(cache session-atom annotations-atom)` to `(cache session annotations)`.
Identity-based invalidation is unchanged — but it now compares a coherent
input pair, eliminating the mismatched-pair caching hazard. The cache stays
dumb: no enrichment, no ownership of state.

### Atoms after consolidation: three kinds

1. `default-system` (defonce) — REPL/back-compat handle.
2. One `state-atom` per system.
3. One request `cache` cell per system (a memoization optimization, not
   domain state).

That is 2N+1 atom *instances* for N running systems.

`server-instance`, `config-atom`, `session-atom`, `annotations-atom`,
`cache-atom` (atom-of-atom), and `analyze-cache-atom` all disappear as
separate constructs.

### Integrant: considered, deferred

Evaluated as the system-component option. What it would bring:
multi-instance systems (real win — test isolation), declarative wiring as
data, lifecycle ordering (low value — only Jetty has teardown),
`integrant.repl` workflows (modest — `defonce` style already works here).

What it does **not** bring: it manages component *identity and lifecycle*,
not *content coordination* — every defect above lives inside what would be a
single Integrant component, so the state-atom + pure-transition design is
required either way. DI value is modest on a straight-line graph
(state → cache → handler → jetty). And it adds embedding friction for a
server consumed programmatically from other REPLs.

**Decision:** build Phase 1 Integrant-*shaped* (system map, explicit wiring)
but library-free. Migration to Integrant later is mechanical — each
system-map key becomes an `init-key`. Revisit when a fifth component appears
(a sidecar file-watcher is the likeliest candidate) or if per-environment
component variants emerge.

### Phase 1 tests

- Pure transition tests: `transition-start` / `transition-swap` /
  `transition-reload` over plain state maps — no HTTP, no Jetty. Spec
  invariant: after a swap, `(:annotations-spec state)` is the given spec;
  after a session-only swap it is nil; `transition-reload` from that state
  yields `{}`.
- `transition-start` retains the startup kondo analysis: after start with
  an auto-detect mode, `(:analyze-cache state)` is non-empty.
- Multi-instance test: two systems from two `start-system!` calls hold
  independent state (drive via 2-arity `swap-session!`; assert no
  cross-talk), and neither call touches `default-system` or stops the
  other's Jetty.
- Existing `server_test` / `integration_test` pass with only the
  `start-server!` helper update shown in §11, via the default-system facade
  (minus the removed reload-endpoint assertions, which become in-memory
  `reload-annotations!` assertions).

---

# Phase 2: WM Enrichment Flow

## Architecture Principles

**1. The state map's `:annotations` is the source of truth.** Whatever the
caller puts there (static or pre-enriched with WM-derived types) is what
analysis consumes. The cache layer is dumb — it uses the annotations value
as-is and invalidates on reference identity. Enrichment is a caller-level
concern, performed via `build-annotations` inside transitions, *before* the
state is committed.

**2. One `AnnotationsSpec` interface for both entry points.** `start!` and
`swap-session!` accept the same `:annotations` spec. There is no separate
`:layers` mechanism.

**3. `:annotations-spec` always holds the spec, never the result.** The value
is always the `AnnotationsSpec` that produced the current `:annotations` —
such that `transition-reload` reproduces (and refreshes) the current state.
`transition-start` stores the startup spec; `transition-swap` stores the
`:annotations` argument as-given (nil for session-only swaps).

**4. WM enrichment is a delta layer** via `analyze/->memory-layer` (eb78335).
The layer carries only what session enrichment added over the accumulated
static base (props + source + generated), with proper provenance so
`merge-layers` tracks its contribution. When the session contributes nothing
new, `->memory-layer` returns nil — no empty layer, no provenance noise.

**5. Type canonicalization for comparison is distinct from type serialization
for display.** `serialize/resolve-type` is the *boundary serializer* (strings
`pr-str`-quoted, keywords colon-prefixed, symbols ns-resolved) — its output is
what the API serves. Comparison/dedup must instead use one shared canonical
form (`annotations/canonical-type-str`) everywhere: merge dedupe, enrichment
coverage checks, and delta computation. Mixing the two is what produces
"truly new" false positives and wasted enrichment work.

## Fixed Flows

Both paths converge on the same `build-annotations` →
`build-auto-detect-annotations` call chain, invoked from the pure
transitions. There is no separate `:layers` / `load-merged-annotations` path.

### Path A: `start!` with `:annotations {:enrichment :auto-detect}`

```
start!({:session s, :annotations {:enrichment :auto-detect}})
  │
  ├─ validate StartOpts
  ├─ state-atom = (atom (transition-start config))   ;; Config -> State, no seed
  │    │
  │    └─ transition-start
  │         ├─ :session ← s
  │         ├─ :annotations-spec ← {:enrichment :auto-detect}
  │         ├─ tmp = (atom {})                 ;; analyze-cache seed
  │         ├─ :analyze-cache ← @tmp           ;; startup kondo retained
  │         └─ :annotations ← (build-annotations s spec {} tmp)
  │              │
  │              └─ build-auto-detect-annotations(s, tmp, nil, :auto-detect)
  │                   │
  │                   ├─ build-static-layers(s, tmp, nil, :auto-detect)
  │                   │    └─ [props-layer, generated-layer]
  │                   ├─ base = annotations(merge-layers(static-layers))
  │                   ├─ memory-layer = ->memory-layer({:session s, :annotations base})
  │                   │    ├─ enriched = enrich-annotations-from-session(s, normalize(base))
  │                   │    ├─ delta = annotations-delta(base, enriched)
  │                   │    │    ;; Only types enrichment added over base
  │                   │    │    ;; e.g. AuditTrail, ComplianceReview
  │                   │    └─ annotations-delta->layer(:clara.tools.graph.analyze/memory,
  │                   │             {:derived-from "session working memory"}, delta)
  │                   └─ memory-layer ? merge-layers([...static, memory-layer]) : base
  │
  ├─ handler/cache = (api/app state-atom wm-enabled?)
  ├─ (cache/warm! cache s annotations)
  └─ start Jetty; system → default-system
```

### Path B: `start!` with `:annotations {:source "annos.edn" :enrichment :auto-detect}`

Same as Path A but `build-static-layers` includes the source layer:

```
build-static-layers(session, tmp, "annos.edn", :auto-detect)
  └─ [props-layer, source-layer, generated-layer]   ← file re-read from disk
```

The memory delta is computed against `props + source + generated`, so the
memory layer only claims types the static layers didn't already declare.

### Path C: `swap-session!` with `{:session s, :annotations {:enrichment :auto-detect}}`

```
swap-session!(system, {:session s, :annotations {:enrichment :auto-detect}})
  │
  ├─ (swap! state-atom transition-swap (select-keys opts [:session :annotations]))
  │    │
  │    └─ transition-swap
  │         ├─ :session ← s
  │         ├─ :annotations-spec ← {:enrichment :auto-detect}   ;; the SPEC
  │         ├─ tmp = (atom {})       ;; session identity changed → fresh seed
  │         ├─ :analyze-cache ← @tmp
  │         └─ :annotations ← (build-annotations s spec (:annotations state) tmp)
  │              └─ ... identical to Path A from here ...
  │
  └─ (cache/warm! cache new-session new-annotations)  ;; from swap! return value
```

A session-only swap (`{:session s}`) stores `nil` as the spec — annotations
are cleared (existing documented behavior) and a later reload reproduces that.

### Path D: `swap-session!` with `{:annotations {:enrichment :auto-detect-from-memory}}`

```
build-auto-detect-annotations(s, tmp, nil, :auto-detect-from-memory)
  │
  ├─ build-static-layers(s, tmp, nil, :auto-detect-from-memory)
  │    └─ [props-layer]   ← no source, no generated
  │                        (:auto-detect-from-memory skips kondo static analysis)
  ├─ base = annotations(merge-layers([props-layer]))
  ├─ memory-layer = ->memory-layer({:session s, :annotations base})
  │    │  ;; When WM not available: skipped (nil), warning printed
  └─ memory-layer ? merge-layers([props-layer, memory-layer]) : base
```

### Path E: `start!` without enrichment (backward compat)

```
start!({:session s, :annotations "path/to/annos.edn"})
  │  ;; Bare form → normalized to {:source "path/to/annos.edn"}, enrichment nil
  └─ build-annotations(s, "path/to/annos.edn", current)
       └─ coerce-to-bare-annotations("path/to/annos.edn", s)
            ;; Reads file from disk, merges with props-layer. No WM enrichment.
```

### Path F: in-memory `reload-annotations!`

```
reload-annotations!(system)
  │
  └─ (swap! state-atom transition-reload)
       │
       ├─ spec = (:annotations-spec state)   ;; always a SPEC (principle 3):
       │    ;; the start! spec, or the spec from the last swap-session!
       │    ;; (nil after a session-only swap)
       │
       └─ :annotations ← (build-annotations (:session state) spec
                                            (:annotations state)
                                            (atom (:analyze-cache state)))
            ;; File-backed sources are re-read from disk and the memory
            ;; layer re-derives via ->memory-layer against the CURRENT
            ;; session.  The generated (kondo) layer rebuilds from the
            ;; CARRIED-OVER per-ns analyses — kondo does NOT re-run.
            ;; Request-cache invalidation depends on the spec kind (below).
```

**Reload semantics:** reload re-derives the *last effective spec* against the
*current* session. It never resurrects state a swap explicitly replaced, and
it never no-ops on a stale built map. This replaces the removed
`POST /v1/annotations/reload` endpoint for REPL/demo workflows.

**Cache behavior on reload:** `:analyze-cache` is carried over (session
unchanged, kondo results still valid) — the generated layer is rebuilt from
the *cached* per-ns analyses; only file-backed sources and the memory layer
actually re-read/re-derive. **Designed limitation:** no operator gesture
invalidates kondo analysis short of a session swap — if a rule namespace is
edited and re-loaded under the same session object, reload reuses the cached
analysis. That is coherent (the session's compiled rulebase is equally
stale) and refreshing means constructing a new session.

For file-backed and auto-detect specs, reload commits a *fresh*
`:annotations` reference even when the rebuilt value is `=` to the previous
one, so the identity-based request cache rebuilds on next access and the
post-swap `warm!` does real work. Two designed paths return the *identical*
reference instead: a `:reuse` spec with no source returns
`current-annotations` — reload of a stored `:reuse` spec is a permanent
no-op, the coherent reading of "keep what you have" — and bare in-memory
maps / `MergedAnnotations` sources pass through unchanged (nothing on disk
could have changed).

### Summary: which enrichment modes produce which layers

| Mode | props | source | generated (kondo) | memory (`->memory-layer`) |
|------|-------|--------|-------------------|---------------------------|
| `nil` / `:none` | ✓ | if given | — | — |
| `:auto-detect-from-rulebase` | ✓ | if given | ✓ | — |
| `:auto-detect-from-memory` | ✓ | if given | — | ✓ (when WM available) |
| `:auto-detect` | ✓ | if given | ✓ | ✓ (when WM available) |

Memory enrichment always goes through `->memory-layer` → delta → layer merge.
When WM is unavailable, the memory layer is skipped and the mode degrades
gracefully (e.g. `:auto-detect-from-memory` → just props + source), with a
printed warning for both `:auto-detect-from-memory` **and** `:auto-detect`.

## Atom discipline (Phase 1 makes this structural)

All state updates go through `swap!` on the single `state-atom` with pure
transition functions; the old `(reset! a (f @a))` read-then-write races and
post-swap derefs disappear by construction. The `AGENTS.md` rule still gets
recorded (it applies project-wide):

```markdown
## Atom Concurrency

- **Atomic swaps:** When updating an atom based on its current value, use
  `swap!` (never `reset!` with `@`).  `(reset! a (f @a))` is a read-then-write
  race; use `(swap! a f)` instead.  Likewise, use the return value of `swap!`
  rather than dereferencing the atom afterwards.
```

(Note: `current-annotations` is only consulted by `:reuse`; all other modes
build fresh. This is documented on `build-annotations`.)

## Detailed Code Changes

### 1. `server/src/clara/server/graph/server.clj` — Phase 1 + Phase 2

Phase 1: replace the six defonce atoms with `default-system` + per-system
`state-atom`; add `transition-start` / `transition-swap` /
`transition-reload`; restructure into `start-system!` / `start!` / `stop!` /
`swap-session!` around them; add `reload-annotations!`; delete `wrap-reload`,
`load-merged-annotations`, and the old `reload-annotations!` HTTP path.

Phase 2, within the transitions:

#### a) `build-auto-detect-layers` → `build-static-layers`

Renamed (memory no longer built here) and both non-props layers are validated
through `ann.merge/layer` for consistency (`->layer` is the coercion entry
point; `layer` is the direct constructor/validator):

```clojure
(defn- build-static-layers
  "Build the static annotation layers for auto-detect enrichment modes
   (props, source, generated).  Memory enrichment is handled separately
   via `analyze/->memory-layer` so it produces a proper delta layer against
   the accumulated static base."
  [session analyze-cache source enrichment]
  (let [source-layers (when (some? source)
                        (map ann.merge/->layer
                             (if (vector? source) source [source])))]
    (cond-> [(ann.merge/props-layer session)]
      (seq source-layers) (into source-layers)
      (#{:auto-detect-from-rulebase :auto-detect} enrichment)
      (conj (ann.merge/layer
             {:id :clara.tools.graph.analyze/generated
              :annotations (let [analysis (analyze/analyze-session-rules
                                           {:session-or-rulebase session
                                            :cache-atom analyze-cache})]
                             (analyze/generate-annotations-from-analysis
                              {:analysis analysis
                               :session-or-rulebase session}))})))))
```

(`analyze-cache` here is the temporary atom seeded from and committed back
to the state map by the calling transition — see "Transition contracts" →
analyze-cache design. `props-layer` accepts a session or a raw rulebase, so
rulebase input flows through unchanged.)

**Per-file source layers:** each source entry becomes its own layer via
`ann.merge/->layer` (path strings are read from disk; bare maps and
`MergedAnnotations` pass through the same coercion). This preserves the old
`:layers` startup semantics — per-file `:provenance` and per-callsite
`:from-layer` attribution — instead of flattening all files into one
`:id :source` layer, and it eliminates the double `props-layer` fold that
the coerce path performed. Consequence, chosen deliberately: `merge-layers`
duplicate-`:id` strictness now applies across files (two sidecars both
carrying `:id :.../generated` fail fast — a real config error), matching
pre-refactor behavior.

#### b) `build-auto-detect-annotations` uses `->memory-layer`

```clojure
(defn- build-auto-detect-annotations
  "Build annotations for auto-detect enrichment modes.

   Static layers are merged first so the memory delta is computed against
   the accumulated base — not an empty map.  When the session contributes
   nothing new, ->memory-layer returns nil and the memory layer is skipped."
  [session analyze-cache source enrichment]
  (let [wm? (core/working-memory-available? session)]
    (when (and (#{:auto-detect-from-memory :auto-detect} enrichment)
               (not wm?))
      (println (format "[server] %s requested but no working memory available — skipping memory enrichment"
                       enrichment)))
    (let [static-layers (build-static-layers session analyze-cache source enrichment)
          merged-static (ann.merge/merge-layers static-layers)
          base          (ann.merge/annotations merged-static)
          memory-layer  (when (and wm?
                                   (#{:auto-detect-from-memory :auto-detect} enrichment))
                          (analyze/->memory-layer {:session session
                                                   :annotations base}))]
      (if memory-layer
        (-> (conj static-layers memory-layer)
            ann.merge/merge-layers
            ann.merge/annotations)
        (ann.merge/annotations merged-static)))))
```

#### c) `build-annotations` — contract, arities, fail fast on typos

Signature (the 3-arity preserves the existing calling convention for direct
callers/tests; transitions use the 4-arity to thread the cache):

```clojure
(defn build-annotations
  "Resolve annotations for `session` from `annotations-spec`.

   `annotations-spec` may be nil (→ `{}`), an `AnnotationsSpec` map, or a
   legacy bare form (bare map, MergedAnnotations, vector of Layers, string
   path, or File) which is treated as `{:source <form>}`.

   `current-annotations` is the current state's :annotations value (only
   consulted by :reuse).  `analyze-cache-atom` is a temporary atom of
   {ns-sym → kondo-analysis}, seeded and committed by the calling
   transition; the 3-arity uses a fresh throwaway cache.

   Boundary validation: a map containing :source or :enrichment is a spec
   and is validated against AnnotationsSpec; a map with neither key is a
   bare annotations map (the ambiguity is inherent — see below)."
  ([session annotations-spec current-annotations]
   (build-annotations session annotations-spec current-annotations (atom {})))
  ([session annotations-spec current-annotations analyze-cache-atom]
   ...))
```

**Boundary validation of spec-shaped maps:** `AnnotationsArg`'s pred is
tautological for maps (its final `(map? x)` clause swallows every map), so a
typo like `{:enrichment :auto-dectect}` would pass the boundary and only
fail at the `case`-throw. Tighten: any map containing `:source` or
`:enrichment` is `s/validate`d against `AnnotationsSpec` (whose
`:enrichment` is already the enum — the typo now fails at the boundary with
a schema error). The validation lives in `build-annotations`' normalization
— the single choke point both `start!` and `swap-session!` flow through.
Documented inherent ambiguity: a map with *neither* key (e.g. the typo
`{:soruce "x.edn"}`) is indistinguishable from a bare annotations map and
passes through — accepted; disallowing bare maps would break legacy callers.

Combine the identical `:none`/`nil` branches, enumerate the auto-detect modes
explicitly, and **throw on unknown enrichment** instead of silently degrading
to props+source (a bare `case` would throw anyway; the explicit throw exists
for the actionable message):

```clojure
(case enrichment
  :reuse
  (if (some? source)
    (ann.merge/coerce-to-bare-annotations source session)
    current-annotations)

  (:none nil)   ;; :none is the explicit opt-out; nil is the default
  (if (some? source)
    (ann.merge/coerce-to-bare-annotations source session)
    {})

  (:auto-detect-from-rulebase :auto-detect-from-memory :auto-detect)
  (build-auto-detect-annotations session analyze-cache source enrichment)

  (throw (IllegalArgumentException.
          (format "Unknown :enrichment mode: %s" (pr-str enrichment)))))
```

#### d) `start-system!` / `start!` — validates, builds a system, no `:layers`

```clojure
(s/defschema StartOpts
  "Options for `start-system!` / `start!`."
  {:session SessionOrRulebase
   (s/optional-key :annotations) (s/maybe AnnotationsArg)
   (s/optional-key :port) s/Int
   (s/optional-key :working-memory-enabled) s/Bool})

(defn start-system!
  "Builds and starts an explorer system; returns the system map.
   Pure constructor: never touches default-system, never stops another
   system — tests use it for isolation.
   Options:
   :session       - The Clara session to analyze.  A raw Rulebase is also
                    accepted; when given a rulebase, working-memory routes
                    return 409 (see :working-memory-enabled).
   :annotations   - An AnnotationsSpec (same shape as swap-session!'s
                    :annotations arg).  May be nil, a bare form (string,
                    vector, map), or a spec map with :source/:enrichment.
   :port          - Server port (default 9999).
   :working-memory-enabled - When false, working-memory routes return 409
                             even when a live session is provided
                             (default true)."
  [config]
  (let [config (s/validate StartOpts config)   ;; fail fast at the boundary
        {:keys [session port working-memory-enabled]
         :or {port 9999 working-memory-enabled true}} config
        state-atom (atom (transition-start config))   ;; the VALIDATED config
        {:keys [handler cache]} (api/app state-atom working-memory-enabled)
        _ (cache/warm! cache session (:annotations @state-atom))
        server (jetty/run-jetty handler {:port port :join? false})]
    {:config config :state-atom state-atom :cache cache
     :handler handler :server server}))

(defn start!
  "Operator entry point: like start-system!, plus default-system management.
   Same-port restart stops the previous default's Jetty BEFORE binding
   (BindException otherwise); a previous default on a different port keeps
   running."
  [config]
  (let [port (:port config 9999)
        prev @default-system]
    (when (and prev (= port (get-in prev [:config :port] 9999)))
      (Server/.stop ^Server (:server prev)))   ;; stop before bind
    (let [system (start-system! config)]
      (reset! default-system system)
      system)))

(defn- require-system
  [system]
  (or system
      (throw (IllegalStateException.
              "no explorer system started — call start! first"))))
```

#### e) `swap-session!` — one atomic transition

`SwapSessionOpts` (unchanged from the current codebase; shown for
completeness):

```clojure
(s/defschema SwapSessionOpts
  "Options for `swap-session!`.  At least one of :session or :annotations
   must be provided."
  {(s/optional-key :session) SessionOrRulebase
   (s/optional-key :annotations) (s/maybe AnnotationsArg)
   (s/optional-key :warm-cache?) s/Bool})
```

Note: `:warm-cache?` is consumed by the shell only — `transition-swap`
receives just `{:session :annotations}` (see "Transition contracts").
Naming discipline through the implementation: locals are
`spec`/`annotations-spec` for the spec and `built`/`annotations` for the
derived map; the public opts key remains `:annotations`.

```clojure
(defn swap-session!
  "Hot-swap the running server's session and/or annotations at runtime.
   1-arity operates on the default system; 2-arity on an explicit system.
   Returns the new bare annotations map."
  ([opts] (swap-session! (require-system @default-system) opts))
  ([system opts]
   (let [{:keys [session annotations warm-cache?]
          :or {warm-cache? true}} (s/validate SwapSessionOpts opts)]
     (when (and (nil? session) (nil? annotations))
       (throw (IllegalArgumentException.
               "swap-session! requires at least one of :session or :annotations")))
     (let [new-state (swap! (:state-atom system) transition-swap
                            (select-keys opts [:session :annotations]))]
       (when warm-cache?
         (cache/warm! (:cache system) (:session new-state) (:annotations new-state)))
       (:annotations new-state)))))
```

#### f) `reload-annotations!` — in-memory, spec-driven

```clojure
(defn reload-annotations!
  "Re-derives annotations from the last effective AnnotationsSpec against the
   current session.  File-backed sources are re-read from disk; the memory
   layer re-derives via ->memory-layer; the generated (kondo) layer rebuilds
   from the cached per-ns analyses in the state (kondo does not re-run).
   In-memory counterpart to the removed HTTP reload endpoint."
  ([] (reload-annotations! (require-system @default-system)))
  ([system]
   (let [new-state (swap! (:state-atom system) transition-reload)]
     (cache/warm! (:cache system) (:session new-state) (:annotations new-state))
     (:annotations new-state))))
```

### 2. `server/src/clara/server/graph/api.clj` — Phase 1

Router and handlers take the single `state-atom` + `cache`; every handler
derefs once per request and passes values to `cache/analysis` /
`cache/snapshot`. All routes stay GET-only. `app` returns `{:handler :cache}`
as today (no more side-channel atom needed by `server`).

### 3. `server/src/clara/server/graph/cache.clj` — Phase 1

`analysis` / `snapshot` / `warm!` / `get-state` take
`(cache session annotations)` values. Invalidation logic unchanged.

### 4. `server/src/clara/server/tools/graph/annotations.clj` — shared type canonicalization

Add one canonicalization function used by *all* comparison sites. `type-str`
stays as the ns-free core; `canonical-type-str` adds symbol resolution under
a production namespace:

```clojure
(defn fq-name->namespace
  "Extract the namespace portion from a fully-qualified rule name string like
   \"some.ns/rule-name\".  Returns a symbol, or nil if the name has no
   namespace segment."
  [fq-str]
  (some-> fq-str symbol namespace symbol))

(defn canonical-type-str
  "Canonical string form of a type value for COMPARISON (merge dedupe,
   enrichment coverage checks, delta computation).

   Unlike `serialize/resolve-type` — the boundary serializer for display —
   strings pass through unquoted and keywords lose their colon, so a Class,
   its `.getName` string, and its source symbol all canonicalize
   identically.  When `prod-ns` is given, symbols are resolved in that
   namespace first, so a rule's `AuditTrail` symbol and the
   `my.ns.AuditTrail` Class compare equal."
  ([t] (type-str t))
  ([prod-ns t]
   (if (symbol? t)
     (if-let [resolved (and prod-ns
                            (some-> (find-ns prod-ns)
                                    ^clojure.lang.Namespace
                                    (.getMapping t)))]
       (cond
         (class? resolved) (.getName ^Class resolved)
         (var? resolved)   (str (symbol (-> resolved meta :ns ns-name str)
                                        (-> resolved meta :name str)))
         :else             (str resolved))
       (str t))
     (type-str t))))
```

**Resolution mechanism:** `Namespace/getMapping` consults both interned vars
and `:import`ed classes — imported class symbols live in the same ns-map
lookup path as var symbols (verified on the pinned Clojure 1.12.4;
`ns-resolve` also works here, but `getMapping` is the documented public API
rather than a Compiler internal).

**Resolver unification:** the codebase otherwise grows three type resolvers
with subtly different semantics — `annotations/resolve-type-locally`
(try/catch-guarded, derefs vars), `serialize/resolve-type` (unguarded,
display strings), and this one. Consolidate behind one shared import-aware
resolver in `annotations.clj` with one try/catch policy (guard resolution
failures, degrade to nil → `(str t)`); `canonical-type-str` is "resolve,
then canonical string" and `serialize/resolve-type` becomes display
formatting on top of the same resolver. `:props` insertion types, EDN
sidecar symbols, and delta symbol types then all canonicalize through one
path.

`annotations-delta`'s `new-types` gains the rule's namespace (derived from
the rule name via `fq-name->namespace`) so symbol types canonicalize the same
way they did during enrichment.

### 5. `server/src/clara/server/tools/graph/analyze.clj` — comparison vs. display split

- `annot-type->str` is removed from all *comparison* sites
  (`existing-strs`, `resolved-strs`, the `truly-new` removal, and the
  `dedupe-by` in `enrich-annotations-from-session`) — replaced by
  `(partial ann/canonical-type-str rule-ns)`.
- `serialize/resolve-type` is kept **only** for display values:
  `:fact-instance-derived-types` strings and sort keys.
- The private `fq-name->namespace` moves to `annotations.clj` (see above);
  analyze.clj uses it from there.

Net effect: enrichment coverage checks, merge dedupe, and delta computation
all compare under one canonical form, so enrichment no longer "discovers"
types that delta then discards (or vice versa).

### 6. `server/src/clara/server/graph/main.clj`

#### a) Add `--annotations` CLI option

```clojure
[nil "--annotations EDN"
 (str "Annotations spec as inline EDN or a path to an EDN file containing one."
      "  Inline:  --annotations '{:source \"a.edn\" :enrichment :auto-detect}'"
      "  File:    --annotations my-spec.edn"
      "  When the spec has no :source, any -l/--layer flags supply it.")
 :default nil]
```

#### b) `parse-annotations-arg` — handles bare paths, fails clearly

`edn/read-string "my-spec.edn"` returns the *symbol* `my-spec.edn` (valid
EDN), so a naive parse leaks a symbol into `build-annotations` and explodes
deep inside layer construction. And `edn/read-string` reads only the first
form, silently ignoring trailing garbage. Parse defensively — exactly one
form, EOF-checked:

```clojure
(defn- read-edn-single
  "Reads exactly one EDN form from s; trailing garbage is an error."
  [s]
  (let [r (java.io.PushbackReader. (java.io.StringReader. s))
        form (edn/read {:eof ::eof} r)]
    (when-not (identical? ::eof (edn/read {:eof ::eof} r))
      (throw (IllegalArgumentException.
              (format "Trailing data after EDN form: %s" (pr-str s)))))
    form))

(defn- parse-annotations-arg
  "Parses the --annotations value: inline EDN (a spec map, or a bare string
   treated as {:source s}), or a path to an EDN file containing a spec.
   Anything else is an error."
  [s]
  (let [parsed (try (read-edn-single s)
                    (catch Exception _ ::not-edn))]
    (cond
      (map? parsed)    parsed
      (string? parsed) {:source parsed}
      :else
      ;; A bare path (parsed as a symbol/number, or not EDN at all):
      ;; read the file as an EDN spec.
      (if (file-exists? s)
        (let [content (read-edn-single (slurp s))]
          (if (map? content)
            content
            (throw (IllegalArgumentException.
                    (format "Invalid --annotations file %s: expected an AnnotationsSpec map, got %s"
                            s (pr-str content))))))
        (throw (IllegalArgumentException.
                (format "Invalid --annotations: %s (not a valid EDN spec map or an existing file)"
                        s)))))))
```

#### c) `run-explorer-server` — `-l` and `--annotations` compose

Rather than one flag silently discarding the other, the two compose
predictably: `-l` supplies the spec's `:source` when the spec doesn't declare
one; when both declare a source, the spec wins with a warning. The existing
warn-and-skip tolerance for missing `-l` files is preserved (a missing layer
file warns and is skipped; a malformed spec is still fail-fast):

```clojure
(let [layer (into []   ;; preserve the existing warn-and-skip tolerance
                   (keep (fn [f]
                           (if (file-exists? f)
                             f
                             (println (format "Warning: layer file not found, skipping: %s" f)))))
                   layer)
      spec (some-> annotations parse-annotations-arg)
      annotations-spec
      (cond
        (and spec (nil? (:source spec)) (seq layer))
        (assoc spec :source (vec layer))        ;; -l becomes the spec's source

        (and spec (some? (:source spec)) (seq layer))
        (do (println (format "Warning: --annotations :source takes priority; --layer values ignored: %s"
                             (pr-str layer)))
            spec)

        spec          spec
        (seq layer)   {:source (vec layer)}
        :else         nil)]
  (server/start! {:session loaded-session
                  :port port
                  :annotations annotations-spec
                  :working-memory-enabled working-memory-enabled}))
```

`{:source ["a.edn" "b.edn"]}` is valid — each entry becomes its own layer
via `ann.merge/->layer` in `build-static-layers`, which reads path strings
from disk.

### 7. `server/dev/clara/server/graph/demo_run.clj`

Inject the default `--annotations` (enrichment only) and keep the existing
`-l` default for the curated sidecar — the composition logic in
`run-explorer-server` merges them into
`{:source [ann-path] :enrichment :auto-detect}`. Flag detection must
recognize both the separate-token and `--flag=value` forms (otherwise an
equals-form flag slips past and a duplicate default gets injected):

```clojure
(defn- flag-present?
  "True when args contain FLAG as a separate token or in --flag=value form."
  [args & flags]
  (some (fn [a]
          (some #(or (= a %) (str/starts-with? a (str % "="))) flags))
        args))

(defn -main [& args]
  (let [args (if (flag-present? args "-p" "--port")
               args
               (concat args ["-p" "9001"]))
        args (if (flag-present? args "--annotations")
               args
               (concat args ["--annotations" "{:enrichment :auto-detect}"]))
        ann-path (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
                         .getPath)]
    (if (flag-present? args "-l" "--layer")
      (apply main/-main args)
      (apply main/-main (concat args ["-l" ann-path])))))
```

Note the composition semantics make the old explicit-`-l` workflow behave
sensibly too: `demo-run -l other.edn` yields
`{:source ["other.edn"] :enrichment :auto-detect}` rather than having the
injected default silently override the user's flag.

### 8. `server/Makefile`

Unchanged flags, now actually correct (previously `--annotations` would have
discarded the curated sidecar entirely):

```makefile
demo-run:
	clojure -M:demo-run -s demo-data/session.bin \
	  --annotations '{:enrichment :auto-detect}' \
	  -l test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn
```

(demo_run's own defaults would supply both anyway; explicit is better at the
Makefile level as living documentation.)

### 9. `server/AGENTS.md`

Add the atom-concurrency rule shown in [Atom discipline](#atom-discipline-phase-1-makes-this-structural).

### 10. `docs/explorer-graph-api.md`

Remove the `POST /v1/annotations/reload` section; note the read-only HTTP
contract and point at the in-memory `swap-session!` / `reload-annotations!`
API for mutation.

### 11. Tests

Phase 1 tests as listed [above](#phase-1-tests).

Helper update (`server_test.clj`):

```clojure
(defn- start-server!
  ([session layers]
   (server/start! {:port *port*
                   :session session
                   :annotations (when (seq layers) {:source (vec layers)})
                   :working-memory-enabled true}))
  ([session]
   (start-server! session [])))
```

Existing swap-session tests pass unchanged — they exercise the same
`build-annotations` → `build-auto-detect-annotations` path (via the
default-system facade). The internal switch to `->memory-layer` is
transparent to callers.

`integration_test.clj`: drop `post-annotations-reload` and its assertion
block; replace with an in-memory `(server/reload-annotations!)` call followed
by `GET /v1/annotations` to prove the HTTP surface reflects in-memory
mutation.

**New Phase 2 test cases** (the fix must not rest on the e2e demo scrape
alone):

1. `test-build-annotations-auto-detect-with-memory` — call
   `build-annotations` (via `transition-start` or directly) with a fired
   session and `{:enrichment :auto-detect}`; assert the result includes the
   WM-derived insert types on the dynamic-insert rules (same assertions as
   `test-swap-session-auto-detect`, no HTTP needed).
2. `test-start-auto-detect-enrichment` — a fired session and
   `:annotations {:enrichment :auto-detect}`; assert `GET /v1/annotations`
   shows WM-derived types. Uses `start-system!` (never touches
   `default-system`, never stops another system's Jetty) plus 2-arity
   mutations, so it neither disturbs the shared fixture server nor hijacks
   the default mid-suite.
3. `test-reload-after-session-only-swap` — start with
   `{:source path :enrichment :auto-detect}`, swap session-only (spec → nil),
   then in-memory `reload-annotations!` → `{}` (reload reproduces the swap).
4. `test-reload-after-swap-with-spec` — swap with
   `:annotations {:source path :enrichment :auto-detect}`, then
   `reload-annotations!` → re-derives the same enriched annotations from the
   spec (not a no-op on a stored result). Optionally: edit the sidecar file
   between swap and reload to prove the file is re-read.
5. `test-build-annotations-unknown-enrichment` — `{:enrichment :auto-dectect}`
   fails fast: a spec-shaped map is validated against `AnnotationsSpec` at
   the `build-annotations` choke point (enum violation → schema error), with
   the `case`-throw as the second line of defense.
6. `test-canonical-type-str-resolution` — (i) an unqualified record-class
   symbol in `:props` (`AuditTrail`) canonicalizes identically to its Class
   under the rule's prod-ns; (ii) qualified symbols as stored in EDN
   sidecars match the Class's `.getName`; (iii) an unloaded namespace
   (`find-ns` → nil) degrades to `(str t)` — pinned behavior for
   `main -s session.bin` runs that deserialize without rule namespaces
   loaded.

## Files NOT Changed

- **`annotations/merge.clj`** — No changes (`layer`, `annotations-delta->layer`
  already in place).
- **`serialize.clj`** — *Small* change (resolver unification, §4):
  `resolve-type` stays the boundary serializer for display, but its
  resolution logic is reimplemented on top of the shared import-aware
  resolver in `annotations.clj` instead of its own unguarded fallback.

## Verification Steps

1. **Server tests:**
   ```bash
   cd server && make test
   ```

2. **Lint + reflection:**
   ```bash
   cd server && make lint reflection-check
   ```

3. **Demo re-scrape:**
   ```bash
   # Terminal 1
   cd server && make demo-setup && make demo-run

   # Terminal 2
   cd ui && pnpm run scrape:demo
   ```

4. **Verify static fact types include WM-derived types:**
   ```bash
   curl -s http://localhost:9001/v1/fact-types | python3 -c "
   import sys, json
   data = json.load(sys.stdin)
   names = {ft['name'] for ft in data['fact-types']}
   for e in ['clara.server.tools.graph.rules.loan_doc_rules.AuditTrail',
             'clara.server.tools.graph.rules.loan_doc_rules.ComplianceReview',
             ':compliance-review-result']:
       print(f'{e}: {\"PRESENT\" if e in names else \"MISSING\"}')"
   ```
   All three → **PRESENT**.

5. **Verify AuditTrail detail has ancestors + inserted-by:**
   ```bash
   curl -s http://localhost:9001/v1/fact-types/clara.server.tools.graph.rules.loan-doc-rules.AuditTrail-ihl0yjcd
   ```
   Should include `ancestors` (with `known: false)` and `inserted-by-rules`
   pointing to `dynamic-insert-audit-trail`.

6. **Verify backward compat** — `-l` without `--annotations`, via `main`
   directly (the `:demo-run` alias intentionally injects the enrichment
   default):
   ```bash
   clojure -M -m clara.server.graph.main -s demo-data/session.bin -l path/to/annos.edn
   ```
   Bare source, no enrichment — `{:source ["path/to/annos.edn"]}`, static only.

7. **Verify reload/swap semantics** — covered by the new unit tests
   (reload-after-swap cases). For manual REPL verification:
   ```clojure
   (server/swap-session! {:session new-session})        ;; annotations cleared
   (server/reload-annotations!)                          ;; => {} (spec was reset)
   (server/swap-session! {:annotations {:source "annos.edn"
                                        :enrichment :auto-detect}})
   (server/reload-annotations!)                          ;; re-derives enriched annos
   ```

8. **Verify demo-data git diff shows additions, not deletions:**
   ```bash
   git diff --stat HEAD -- ui/static/demo-data/
   ```
   Should **add** `AuditTrail`, `ComplianceReview`, `compliance-review-result`
   and restore interface ancestor linkages — not the ~783-line removal.
