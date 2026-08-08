# Fix Working-Memory Annotation Enrichment Flow

> **Revision 3** — restructured into two phases. Phase 1 consolidates the
> server's stateful constructs (decided after design review: fix the state
> architecture *first*, rather than work around it). Phase 2 is the WM
> enrichment flow itself, now expressed on the consolidated state.
> HTTP is now a **read-only** contract: `POST /v1/annotations/reload` is
> removed; `swap-session!` (in-memory) is the only mutation API.
>
> Incorporates design review round 1
> (`fix-wm-anno-enrichment-flow-ds-review-1.md`). See
> [Review Disposition](#review-disposition) at the end.

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
together or they lie. Every defect found in review round 1 was a
shard-synchronization bug, not an enrichment-logic bug:

| Defect | Shard failure |
|---|---|
| Review #1: reload degrades after swap | built *result* stored where the *spec* belonged (`config-atom` vs `annotations-atom` confusion) |
| Review #7: racy deref after swap | `reset!` on one atom, then `@` on it while others change |
| New issue B: stale kondo analysis after `start!` restart | `analyze-cache-atom` clearing rule lived only in `swap-session!` |
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
 :analyze-cache}    ;; per-ns kondo memoization; cleared when session identity changes
```

### System handle (returned by `start!`)

```clojure
{:config      ;; validated StartOpts
 :state-atom  ;; atom of the state map above
 :cache       ;; request cache cell (atom, identity-invalidated memoization)
 :handler     ;; ring handler
 :server}     ;; Jetty instance
```

### Pure transitions — the actual API, trivially unit-testable

```clojure
(defn- transition-start  [state config] ...)              ;; State -> State
(defn- transition-swap   [state {:keys [session annotations]}] ...)
(defn- transition-reload [state] ...)  ;; re-runs (:annotations-spec state)
```

`transition-swap` records the spec as-given (nil for a session-only swap —
matching the documented "annotations cleared" semantics), clears
`:analyze-cache` when the session identity changes, and derives
`:annotations` via `build-annotations` (Phase 2). `transition-reload`
re-derives from the stored spec against the current session — file-backed
sources re-read from disk, auto-detect modes re-run. Because the transition
receives the whole immutable state, `:reuse` enrichment stops being a
cross-atom read entirely.

### Public API — system-first, with a default-system facade

```clojure
(defn start! [config] ...)        ;; -> system; also held in defonce default-system
(defn stop! ([] ...) ([system] ...))
(defn swap-session! ([opts] ...) ([system opts] ...))   ;; -> bare annotations
(defn reload-annotations! ([] ...) ([system] ...))      ;; in-memory only
```

The 1-arity forms operate on the `default-system` (set by the most recent
`start!`), preserving every existing callsite: `main.clj`,
`dev/hierarchy_run.clj`, `demo_run.clj`, tests, and external REPL embedding
all keep working unchanged. The 2-arity forms enable isolated per-fixture
systems in tests (parallel suites, ephemeral ports, no cross-suite
interference).

Side effects (Jetty start/stop, cache warming, `println` diagnostics) stay
**outside** the `swap!` — swap functions may retry and must stay pure-ish.
Warming uses the state returned by `swap!`, never a follow-up deref.

### HTTP contract: read-only

- **Remove `wrap-reload` and `POST /v1/annotations/reload`.** The endpoint is
  too limiting (re-derive only, no new state) and redundant once
  `swap-session!` / `reload-annotations!` work correctly in-memory. It can be
  reintroduced later on top of a proven in-memory API if a workflow needs it.
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

### Atoms after consolidation: three

1. `default-system` (defonce) — REPL/back-compat handle.
2. One `state-atom` per system.
3. One request `cache` cell per system (a memoization optimization, not
   domain state).

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
- Multi-instance test: two systems from two `start!` calls hold independent
  state (drive via 2-arity `swap-session!`; assert no cross-talk).
- Existing `server_test` / `integration_test` pass unchanged via the
  default-system facade (minus the removed reload-endpoint assertions, which
  become in-memory `reload-annotations!` assertions).

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
"truly new" false positives and wasted enrichment work (review issue #6).

## Fixed Flows

Both paths converge on the same `build-annotations` →
`build-auto-detect-annotations` call chain, invoked from the pure
transitions. There is no separate `:layers` / `load-merged-annotations` path.

### Path A: `start!` with `:annotations {:enrichment :auto-detect}`

```
start!({:session s, :annotations {:enrichment :auto-detect}})
  │
  ├─ validate StartOpts
  ├─ state-atom = (atom (transition-start initial-state config))
  │    │
  │    └─ transition-start
  │         ├─ :session ← s
  │         ├─ :annotations-spec ← {:enrichment :auto-detect}
  │         ├─ :analyze-cache ← {}
  │         └─ :annotations ← (build-annotations s spec {})
  │              │
  │              └─ build-auto-detect-annotations(s, nil, :auto-detect)
  │                   │
  │                   ├─ build-static-layers(s, nil, :auto-detect)
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
build-static-layers(session, "annos.edn", :auto-detect)
  └─ [props-layer, source-layer, generated-layer]   ← file re-read from disk
```

The memory delta is computed against `props + source + generated`, so the
memory layer only claims types the static layers didn't already declare.

### Path C: `swap-session!` with `{:session s, :annotations {:enrichment :auto-detect}}`

```
swap-session!(system, {:session s, :annotations {:enrichment :auto-detect}})
  │
  ├─ (swap! state-atom transition-swap opts)
  │    │
  │    └─ transition-swap
  │         ├─ :session ← s
  │         ├─ :annotations-spec ← {:enrichment :auto-detect}   ;; the SPEC
  │         ├─ :analyze-cache ← {}   ;; session identity changed
  │         └─ :annotations ← (build-annotations s spec (:annotations state))
  │              └─ ... identical to Path A from here ...
  │
  └─ (cache/warm! cache new-session new-annotations)  ;; from swap! return value
```

A session-only swap (`{:session s}`) stores `nil` as the spec — annotations
are cleared (existing documented behavior) and a later reload reproduces that.

### Path D: `swap-session!` with `{:annotations {:enrichment :auto-detect-from-memory}}`

```
build-auto-detect-annotations(s, nil, :auto-detect-from-memory)
  │
  ├─ build-static-layers(s, nil, :auto-detect-from-memory)
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
                                            (:annotations state))
            ;; File-backed sources re-read from disk; auto-detect modes
            ;; re-run (kondo + ->memory-layer) against the CURRENT session.
            ;; Cache invalidates on the new annotations reference; warmed
            ;; eagerly after the swap for parity with swap-session!.
```

**Reload semantics:** reload re-derives the *last effective spec* against the
*current* session. It never resurrects state a swap explicitly replaced, and
it never no-ops on a stale built map. This replaces the removed
`POST /v1/annotations/reload` endpoint for REPL/demo workflows.

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
`transition-reload`; restructure `start!` / `stop!` / `swap-session!` around
them; add `reload-annotations!`; delete `wrap-reload`,
`load-merged-annotations`, and the old `reload-annotations!` HTTP path.

Phase 2, within the transitions:

#### a) `build-auto-detect-layers` → `build-static-layers`

Renamed (memory no longer built here) and both non-props layers are validated
through `ann.merge/layer` for consistency (review issue #9 — `->layer` is the
coercion entry point; `layer` is the direct constructor/validator):

```clojure
(defn- build-static-layers
  "Build the static annotation layers for auto-detect enrichment modes
   (props, source, generated).  Memory enrichment is handled separately
   via `analyze/->memory-layer` so it produces a proper delta layer against
   the accumulated static base."
  [session analyze-cache source enrichment]
  (cond-> [(ann.merge/props-layer session)]
    (some? source)
    (conj (ann.merge/layer {:id :source
                            :annotations (ann.merge/coerce-to-bare-annotations
                                          source session)}))
    (#{:auto-detect-from-rulebase :auto-detect} enrichment)
    (conj (ann.merge/layer
           {:id :clara.tools.graph.analyze/generated
            :annotations (let [analysis (analyze/analyze-session-rules
                                         {:session-or-rulebase session
                                          :cache-atom analyze-cache})]
                           (analyze/generate-annotations-from-analysis
                            {:analysis analysis
                             :session-or-rulebase session}))}))))
```

(`analyze-cache` is now threaded through from the state map rather than read
from a defonce atom. `analyze-session-rules` takes an atom; the transition
holds the new cache value and the swap commits it — pass an atom created
per-build or keep `:analyze-cache` as an atom value inside the state map;
either way its lifecycle is owned by the transitions, honoring "cleared when
session identity changes" for both `start!` and `swap-session!`.)

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

#### c) `build-annotations` — explicit modes, fail fast on typos

Combine the identical `:none`/`nil` branches, enumerate the auto-detect modes
explicitly, and **throw on unknown enrichment** instead of silently degrading
to props+source (a typo'd mode like `:auto-dectect` currently falls through
`case` into the auto-detect default):

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

#### d) `start!` — validates, builds a system, no `:layers`

```clojure
(s/defschema StartOpts
  "Options for `start!`."
  {:session SessionOrRulebase
   (s/optional-key :annotations) (s/maybe AnnotationsArg)
   (s/optional-key :port) s/Int
   (s/optional-key :working-memory-enabled) s/Bool})

(defn start!
  "Starts the explorer server and returns the system map.
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
  (let [{:keys [session annotations port working-memory-enabled]
         :or {port 9999 working-memory-enabled true}}
        (s/validate StartOpts config)   ;; fail fast at the boundary
        state-atom (atom (transition-start {} config))
        {:keys [handler cache]} (api/app state-atom working-memory-enabled)
        _ (cache/warm! cache session (:annotations @state-atom))
        server (jetty/run-jetty handler {:port port :join? false})]
    ;; stop previous default-system's Jetty, if any
    ...
    (reset! default-system {:config config :state-atom state-atom
                            :cache cache :handler handler :server server})))
```

#### e) `swap-session!` — one atomic transition

```clojure
(defn swap-session!
  "Hot-swap the running server's session and/or annotations at runtime.
   1-arity operates on the default system; 2-arity on an explicit system.
   Returns the new bare annotations map."
  ([opts] (swap-session! @default-system opts))
  ([system opts]
   (let [{:keys [session annotations warm-cache?]
          :or {warm-cache? true}} (s/validate SwapSessionOpts opts)]
     (when (and (nil? session) (nil? annotations))
       (throw (IllegalArgumentException.
               "swap-session! requires at least one of :session or :annotations")))
     (let [new-state (swap! (:state-atom system) transition-swap opts)]
       (when warm-cache?
         (cache/warm! (:cache system) (:session new-state) (:annotations new-state)))
       (:annotations new-state)))))
```

#### f) `reload-annotations!` — in-memory, spec-driven

```clojure
(defn reload-annotations!
  "Re-derives annotations from the last effective AnnotationsSpec against the
   current session.  File-backed sources are re-read from disk; auto-detect
   modes re-run.  In-memory counterpart to the removed HTTP reload endpoint."
  ([] (reload-annotations! @default-system))
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

Add one canonicalization function used by *all* comparison sites (review
issue #6). `type-str` stays as the ns-free core; `canonical-type-str` adds
symbol resolution under a production namespace:

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
                            (some-> (find-ns prod-ns) (ns-resolve t)))]
       (cond
         (class? resolved) (.getName ^Class resolved)
         (var? resolved)   (str (symbol (-> resolved meta :ns ns-name str)
                                        (-> resolved meta :name str)))
         :else             (str resolved))
       (str t))
     (type-str t))))
```

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
deep inside layer construction (review issue #4). Parse defensively:

```clojure
(defn- parse-annotations-arg
  "Parses the --annotations value: inline EDN (a spec map, or a bare string
   treated as {:source s}), or a path to an EDN file containing a spec.
   Anything else is an error."
  [s]
  (let [parsed (try (edn/read-string s)
                    (catch Exception _ ::not-edn))]
    (cond
      (map? parsed)    parsed
      (string? parsed) {:source parsed}
      :else
      ;; A bare path (parsed as a symbol/number, or not EDN at all):
      ;; read the file as an EDN spec.
      (if (file-exists? s)
        (let [content (edn/read-string (slurp s))]
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

Rather than one flag silently discarding the other (review issues #2 and #5),
the two compose predictably: `-l` supplies the spec's `:source` when the spec
doesn't declare one; when both declare a source, the spec wins with a
warning:

```clojure
(let [spec (some-> annotations parse-annotations-arg)
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

`{:source ["a.edn" "b.edn"]}` is valid — `coerce-to-bare-annotations` maps
`->layer` over vector entries, and `->layer` reads path strings from disk.

### 7. `server/dev/clara/server/graph/demo_run.clj`

Inject the default `--annotations` (enrichment only) and keep the existing
`-l` default for the curated sidecar — the composition logic in
`run-explorer-server` merges them into
`{:source [ann-path] :enrichment :auto-detect}`. No dead `--enrichment`
check (review issue #3); the resource path is real:

```clojure
(defn -main [& args]
  (let [args (if (some #{"-p" "--port"} args)
               args
               (concat args ["-p" "9001"]))
        args (if (some #{"--annotations"} args)
               args
               (concat args ["--annotations" "{:enrichment :auto-detect}"]))
        ann-path (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
                         .getPath)]
    (if (some #{"-l" "--layer"} args)
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

**New Phase 2 test cases** (review issue #8 — the fix must not rest on the
e2e demo scrape alone):

1. `test-build-annotations-auto-detect-with-memory` — call
   `build-annotations` (via `transition-start` or directly) with a fired
   session and `{:enrichment :auto-detect}`; assert the result includes the
   WM-derived insert types on the dynamic-insert rules (same assertions as
   `test-swap-session-auto-detect`, no HTTP needed).
2. `test-start-auto-detect-enrichment` — `start!` with a fired session and
   `:annotations {:enrichment :auto-detect}`; assert `GET /v1/annotations`
   shows WM-derived types. Uses the 2-arity/system-handle form so it doesn't
   disturb the shared fixture server.
3. `test-reload-after-session-only-swap` — start with
   `{:source path :enrichment :auto-detect}`, swap session-only (spec → nil),
   then in-memory `reload-annotations!` → `{}` (reload reproduces the swap).
4. `test-reload-after-swap-with-spec` — swap with
   `:annotations {:source path :enrichment :auto-detect}`, then
   `reload-annotations!` → re-derives the same enriched annotations from the
   spec (not a no-op on a stored result). Optionally: edit the sidecar file
   between swap and reload to prove the file is re-read.
5. `test-build-annotations-unknown-enrichment` — `{:enrichment :auto-dectect}`
   throws `IllegalArgumentException`.

## Files NOT Changed

- **`annotations/merge.clj`** — No changes (`layer`, `annotations-delta->layer`
  already in place).
- **`serialize.clj`** — No changes (`resolve-type` stays the boundary
  serializer; it is simply no longer (ab)used for comparison).

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

## Review Disposition

How each round-1 review issue was resolved (revisions 2–3):

| # | Issue | Resolution |
|---|-------|------------|
| 1 | Reload breaks after swap (result stored as spec) | **Adopted, simplified, then subsumed by Phase 1.** The state map's `:annotations-spec` always holds the *spec* (principle 3), written only by pure transitions. One coherent state value — no shadow keys, no cross-atom discipline to maintain. |
| 2 | demo-run drops curated annotations | **Adopted via composition.** `-l` and `--annotations` compose: `-l` fills the spec's `:source` when absent. Makefile/demo_run keep both flags; nothing is discarded. |
| 3 | `--enrichment` dead code + placeholder path | **Adopted.** Check removed; real resource path used; default injection is a single `--annotations` flag. |
| 4 | `parse-annotations-arg` symbol bug | **Adopted with sharper semantics.** Non-map/non-string EDN parses are treated as file paths; file content must be a spec map; clear `IllegalArgumentException` otherwise. |
| 5 | Silent `--layer` discard | **Adopted.** Warning printed when the spec's own `:source` wins over `-l`. |
| 6 | Type normalization inconsistency | **Adopted (option B, sharpened).** New `annotations/canonical-type-str` is the single comparison form (ns-aware for symbols); `resolve-type` remains display-only. `annotations.clj` and `analyze.clj` move out of "Files NOT Changed". |
| 7 | Racy deref after swap | **Subsumed by Phase 1.** Single `swap!` per mutation; warming uses the `swap!` return value. |
| 8 | No unit tests for start! + WM | **Adopted.** Five new Phase 2 cases plus pure-transition Phase 1 tests. |
| 9 | `->layer` vs `layer` | **Adopted.** `layer` constructor used directly; generated layer now validated too. |
| 10 | `current-annotations` only used by `:reuse` | **Documented** on `build-annotations`; arg kept (needed by `:reuse`). |
| 11 | `:layers` fully gone from config | Confirmed; completed by Phase 1 (no config-atom at all). |
| — | `:none`/`nil` duplicate branches | Combined into one `case` branch. |

Additional issues found during review incorporation (not in the review):

| # | Issue | Resolution |
|---|-------|------------|
| A | Unknown `:enrichment` (e.g. typo `:auto-dectect`) falls through `case` into the auto-detect default and silently yields props+source | `case` enumerates modes explicitly; default throws. |
| B | `start!` never cleared `analyze-cache-atom` despite its docstring ("cleared when the session reference changes identity") — stale kondo analysis across restarts | Subsumed by Phase 1: analyze-cache lives in the state map; transitions own its lifecycle for both `start!` and swaps. |
| C | `start!` had no schema validation of `:annotations`; bad input exploded deep in `merge-layers` | New `StartOpts` schema validated at the boundary. |
| D | WM-unavailable warning only fired for `:auto-detect-from-memory`, not `:auto-detect` | Warning covers both. |
| E | Reload didn't warm the cache | HTTP reload removed (read-only contract); in-memory `reload-annotations!` warms from the `swap!` return value. |
| F | Six-atom sharding: every round-1 defect was a shard-synchronization bug; `cache-atom` atom-of-atom side channel; test suites share global state | **Phase 1**: one state atom per system + pure transitions + system handle; Integrant-shaped but library-free (decision recorded in Phase 1). |
| G | `POST /v1/annotations/reload` too limiting as the only HTTP mutation | **Removed.** HTTP is read-only; `swap-session!` / `reload-annotations!` in-memory are the mutation API. Endpoint can return later on top of the proven in-memory API. |
