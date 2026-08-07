# Fix Working-Memory Annotation Enrichment Flow

## Problem

The `hot-swap-session-data` branch refactored the analysis/cache pipeline and
dropped session working-memory enrichment from the server startup path.
Consequence: fact types inserted at runtime (dynamic inserts with
`:resolution :none` — e.g. `AuditTrail`, `ComplianceReview`,
`compliance-review-result`) and their type-hierarchy linkages disappeared from
`/v1/fact-types`.

The `swap-session!` path has enrichment via auto-detect modes, but `start!`
only accepts `:layers` (file paths) with no way to specify an enrichment mode.

## Architecture Principle

**The annotations-atom is the source of truth.** Whatever the caller puts there
(static or pre-enriched with WM-derived types) is what analysis consumes.
The cache layer is dumb — it uses `@annotations-atom` as-is. Enrichment is a
caller-level concern, performed via `build-annotations` *before* the
annotations hit the atom.

Both `start!` and `swap-session!` use the same `AnnotationsSpec` interface.
There is no separate `:layers` mechanism.

WM enrichment is modeled as a **delta layer** via `analyze/->memory-layer`
(eb78335). This layer carries only what session enrichment added over the
accumulated static annotations (props + source + generated), with proper
provenance so `merge-layers` tracks its contribution correctly. When the
session contributes nothing new, `->memory-layer` returns nil — no empty
layer, no provenance noise.

## Fixed Flows

Both paths converge on the same `build-annotations` → `build-auto-detect-annotations`
call chain. There is no separate `:layers` / `load-merged-annotations` path.

### Path A: `start!` with `:annotations {:enrichment :auto-detect}`

```
start!({:session s, :annotations {:enrichment :auto-detect}})
  │
  ├─ (reset! config-atom config)
  ├─ (reset! session-atom s)
  │
  └─ (swap! annotations-atom
         #(build-annotations s {:enrichment :auto-detect} %))
       │
       └─ build-annotations(session, annotations-spec, current-annotations)
            │  ;; Normalizes {:enrichment :auto-detect}
            │  ;; → {:source nil, :enrichment :auto-detect}
            │
            └─ build-auto-detect-annotations(session, nil, :auto-detect)
                 │
                 ├─ build-static-layers(session, nil, :auto-detect)
                 │    └─ [props-layer]   ← no source, auto-detect doesn't
                 │                        include generated w/o
                 │                        :auto-detect-from-rulebase
                 │
                 ├─ merged-static = merge-layers([props-layer])
                 ├─ base = annotations(merged-static)
                 │
                 ├─ memory-layer = ->memory-layer({:session s, :annotations base})
                 │    │
                 │    ├─ base' = normalize-annotations(base)
                 │    ├─ enriched = enrich-annotations-from-session(s, base')
                 │    ├─ delta = annotations-delta(base', enriched)
                 │    │    ;; Only types enrichment added over base
                 │    │    ;; e.g. AuditTrail, ComplianceReview
                 │    │
                 │    └─ delta->layer(:clara.tools.graph.analyze/memory,
                 │                    {:derived-from "session working memory"},
                 │                    delta)
                 │
                 └─ if memory-layer:
                      merge-layers([props-layer, memory-layer]) → annotations
                    else:
                      annotations(merged-static)
```

### Path B: `start!` with `:annotations {:source "annos.edn" :enrichment :auto-detect}`

Same as Path A but `build-static-layers` includes the source layer:

```
build-static-layers(session, "annos.edn", :auto-detect)
  └─ [props-layer, source-layer]   ← file re-read from disk
```

The memory delta is computed against `props + source`, so the memory layer
only claims types the sidecar annotations didn't already declare. More
provenance precision for free.

### Path C: `swap-session!` with `{:annotations {:enrichment :auto-detect}}`

```
swap-session!({:session s, :annotations {:enrichment :auto-detect}})
  │
  ├─ (swap! config-atom assoc :session s)
  ├─ (reset! session-atom s)
  ├─ (reset! analyze-cache-atom {})
  │
  └─ (swap! annotations-atom
         #(build-annotations s {:enrichment :auto-detect} %))
       │
       └─ build-annotations(s, {:enrichment :auto-detect}, current)
            │
            └─ build-auto-detect-annotations(s, nil, :auto-detect)
                 │  ... identical to Path A from here ...
                 │
                 └─ merge-layers(...) → annotations
```

### Path D: `swap-session!` with `{:annotations {:enrichment :auto-detect-from-memory}}`

```
swap-session!({:session s, :annotations {:enrichment :auto-detect-from-memory}})
  │
  └─ build-auto-detect-annotations(s, nil, :auto-detect-from-memory)
       │
       ├─ build-static-layers(s, nil, :auto-detect-from-memory)
       │    └─ [props-layer]   ← no source, no generated
       │                        (:auto-detect-from-memory doesn't include
       │                         kondo static analysis)
       │
       ├─ merged-static = merge-layers([props-layer])
       ├─ base = annotations(merged-static)
       │
       ├─ memory-layer = ->memory-layer({:session s, :annotations base})
       │    │  ;; When WM not available: memory-layer = nil
       │    │  ;; (warning printed in build-auto-detect-annotations)
       │
       └─ if memory-layer:
            merge-layers([props-layer, memory-layer]) → annotations
          else:
            annotations(merged-static)   ← just props
```

### Path E: `start!` without enrichment (backward compat)

```
start!({:session s, :annotations "path/to/annos.edn"})
  │  ;; Bare form → normalized to {:source "path/to/annos.edn"},
  │  ;; enrichment = nil
  │
  └─ (swap! annotations-atom
         #(build-annotations s "path/to/annos.edn" %))
       │
       └─ build-annotations(s, "path/to/annos.edn", current)
            │  ;; Normalizes bare form: {:source "path/to/annos.edn"}
            │  ;; enrichment = nil → no auto-detect
            │
            └─ coerce-to-bare-annotations("path/to/annos.edn", s)
                 │  ;; Reads file from disk, merges with props-layer
                 │  ;; No WM enrichment
                 │
                 └─ bare annotations (static only)
```

### Path F: POST `/v1/annotations/reload`

```
POST /v1/annotations/reload
  │
  └─ reload-annotations!
       │
       └─ (swap! annotations-atom
              #(build-annotations session annotations %))
            │  ;; Uses the annotations spec stored in config-atom
            │  ;; at server start.  File-backed sources re-read from
            │  ;; disk; auto-detect modes re-run (kondo + ->memory-layer).
```

### Summary: which enrichment modes produce which layers

| Mode | props | source | generated (kondo) | memory (`->memory-layer`) |
|------|-------|--------|-------------------|---------------------------|
| `nil` / `:none` | ✓ | if given | — | — |
| `:auto-detect-from-rulebase` | ✓ | if given | ✓ | — |
| `:auto-detect-from-memory` | ✓ | if given | — | ✓ (when WM available) |
| `:auto-detect` | ✓ | if given | ✓ | ✓ (when WM available) |

Memory enrichment always goes through `->memory-layer` → delta → layer merge.
When WM is unavailable, `->memory-layer` returns nil and the mode degrades
gracefully (e.g. `:auto-detect-from-memory` → just props + source).

## Non-atomic atom writes (fixed)

Every path uses `swap!` to update `annotations-atom`:

```clojure
;; BEFORE (non-atomic — read-then-write race):
(let [s @session-atom
      bare (build-annotations s annotations @annotations-atom)]
  (reset! annotations-atom bare))    ;; ← @annotations-atom captured above is stale

;; AFTER (atomic):
(let [s @session-atom]
  (swap! annotations-atom #(build-annotations s annotations %)))
```

This matters because `build-annotations` reads `current-annotations` for
`:reuse` mode. The old pattern could lose a concurrent update.

## Detailed Code Changes

### 1. `server/src/clara/server/graph/server.clj`

#### a) `build-auto-detect-layers` → `build-static-layers`

```clojure
(defn- build-static-layers
  "Build the static annotation layers for auto-detect enrichment modes
   (props, source, generated).  Memory enrichment is handled separately
   via `analyze/->memory-layer` so it produces a proper delta layer against
   the accumulated static base."
  [session source enrichment]
  (cond-> [(ann.merge/props-layer session)]
    (some? source)
    (conj (ann.merge/->layer {:id :source
                              :annotations (ann.merge/coerce-to-bare-annotations source session)}))
    (#{:auto-detect-from-rulebase :auto-detect} enrichment)
    (conj {:id :clara.tools.graph.analyze/generated
           :annotations (let [analysis (analyze/analyze-session-rules
                                        {:session-or-rulebase session
                                         :cache-atom analyze-cache-atom})
                              generated (analyze/generate-annotations-from-analysis
                                         {:analysis analysis
                                          :session-or-rulebase session})]
                          generated)})))
```

#### b) `build-auto-detect-annotations` uses `->memory-layer`

```clojure
(defn- build-auto-detect-annotations
  "Build annotations for auto-detect enrichment modes.

   Static layers are merged first so the memory delta is computed against
   the accumulated base — not an empty map.  When the session contributes
   nothing new, ->memory-layer returns nil and the memory layer is skipped."
  [session source enrichment]
  (let [wm? (core/working-memory-available? session)]
    (when (and (#{:auto-detect-from-memory} enrichment)
               (not wm?))
      (println "[server] :auto-detect-from-memory requested but no working memory available — skipping"))
    (let [static-layers (build-static-layers session source enrichment)
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

#### c) `start!` — `:layers` replaced by `:annotations`

```clojure
(defn start!
  "Starts the explorer server.
   Options:
   :session       - The Clara session to analyze.
   :annotations   - An AnnotationsSpec (same shape as swap-session!'s
                    :annotations arg).  May be nil, a bare form (string,
                    vector, map), or a spec map with :source/:enrichment.
   :port          - Server port (default 9999).
   :working-memory-enabled - When false, working-memory routes return 409
                             (default true)."
  [{:keys [session annotations port working-memory-enabled]
    :or {port 9999 working-memory-enabled true}
    :as config}]
  (let [wm-available? (core/working-memory-available? session)]
    (reset! config-atom config)
    (reset! session-atom session)
    (swap! annotations-atom #(build-annotations session annotations %))
    ...))
```

#### d) Remove `load-merged-annotations`

No longer needed. `build-annotations` → `coerce-to-bare-annotations` handles
file paths, vectors, and enrichment uniformly.

#### e) `reload-annotations!` — atomic, uses `build-annotations`

```clojure
(defn- reload-annotations! []
  (let [{:keys [session annotations]} @config-atom]
    (swap! annotations-atom #(build-annotations session annotations %))))
```

#### f) `swap-session!` — fix non-atomic pattern

```clojure
;; BEFORE:
(let [s @session-atom
      bare (build-annotations s annotations @annotations-atom)]
  (reset! annotations-atom bare)
  (swap! config-atom assoc :annotations bare))

;; AFTER:
(let [s @session-atom]
  (swap! annotations-atom #(build-annotations s annotations %))
  (swap! config-atom assoc :annotations @annotations-atom))
```

### 2. `server/src/clara/server/graph/main.clj`

#### a) Add `--annotations` CLI option

```clojure
[nil "--annotations EDN"
 (str "Annotations spec as inline EDN or path to an EDN file."
      "  The value must be an AnnotationsSpec map with optional"
      "  :source and :enrichment."
      "  Inline:  --annotations '{:source \"a.edn\" :enrichment :auto-detect}'"
      "  File:    --annotations my-spec.edn")
 :default nil]
```

#### b) `parse-annotations-arg` helper

```clojure
(defn- parse-annotations-arg [s]
  (try
    (let [parsed (edn/read-string s)]
      (if (string? parsed) {:source parsed} parsed))
    (catch Exception _
      (edn/read-string (slurp s)))))
```

#### c) `run-explorer-server` — normalize into annotations spec

```clojure
(let [annotations-spec (cond
                         annotations (parse-annotations-arg annotations)
                         (seq layer) {:source layer}
                         :else       nil)]
  (server/start! {:session loaded-session
                  :port port
                  :annotations annotations-spec
                  :working-memory-enabled working-memory-enabled}))
```

### 3. `server/dev/clara/server/graph/demo_run.clj`

Default to `--annotations '{:enrichment :auto-detect}'`:

```clojure
(defn -main [& args]
  (let [args (if (some #{"-p" "--port"} args) args (concat args ["-p" "9001"]))
        args (if (some #{"--annotations" "--enrichment"} args)
               args
               (concat args ["--annotations" "{:enrichment :auto-detect}"]))
        ann-path (some-> (io/resource "...") .getPath)]
    (if (some #{"-l" "--layer"} args)
      (apply main/-main args)
      (apply main/-main (concat args ["-l" ann-path])))))
```

When both `-l` and `--annotations` are present, `run-explorer-server` gives
`--annotations` priority.

### 4. `server/Makefile`

```makefile
demo-run:
	clojure -M:demo-run -s demo-data/session.bin \
	  --annotations '{:enrichment :auto-detect}' \
	  -l test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn
```

### 5. `server/AGENTS.md` — add atomic swap rule

```markdown
## Atom Concurrency

- **Atomic swaps:** When updating an atom based on its current value, use
  `swap!` (never `reset!` with `@`).  `(reset! a (f @a))` is a read-then-write
  race; use `(swap! a f)` instead.
```

### 6. Test updates

`server/test/clara/server/graph/server_test.clj`:

```clojure
(defn- start-server! [session layers]
  (server/start! {:port *port*
                  :session session
                  :annotations (when (seq layers) {:source layers})
                  :working-memory-enabled true}))
```

Existing swap-session tests pass unchanged — they exercise the same
`build-annotations` → `build-auto-detect-annotations` path. The internal
switch to `->memory-layer` is transparent to callers.

## Files NOT Changed

- **`cache.clj`** — The cache remains dumb.
- **`api.clj`** — No changes.
- **`annotations/merge.clj`** — No changes (delta helpers already in eb78335).
- **`annotations.clj`** — No changes (delta helpers already in eb78335).
- **`analyze.clj`** — No changes (`->memory-layer` already in eb78335).

## Verification Steps

1. **Server tests:**
   ```bash
   cd server && make test
   ```

2. **Demo re-scrape:**
   ```bash
   # Terminal 1
   cd server && make demo-setup && make demo-run

   # Terminal 2
   cd ui && pnpm run scrape:demo
   ```

3. **Verify static fact types include WM-derived types:**
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

4. **Verify AuditTrail detail has ancestors + inserted-by:**
   ```bash
   curl -s http://localhost:9001/v1/fact-types/clara.server.tools.graph.rules.loan-doc-rules.AuditTrail-ihl0yjcd
   ```
   Should include `ancestors` (with `known: false`) and `inserted-by-rules`
   pointing to `dynamic-insert-audit-trail`.

5. **Verify backward compat** — `-l` without `--annotations`:
   ```bash
   clojure -M:demo-run -s demo-data/session.bin -l path/to/annos.edn
   ```
   Still works (bare source, no enrichment).

6. **Verify demo-data git diff shows additions, not deletions:**
   ```bash
   git diff --stat HEAD -- ui/static/demo-data/
   ```
   Should **add** `AuditTrail`, `ComplianceReview`, `compliance-review-result`
   and restore interface ancestor linkages — not the ~783-line removal.
