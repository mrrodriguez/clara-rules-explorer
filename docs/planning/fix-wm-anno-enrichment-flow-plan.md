# Fix Working-Memory Annotation Enrichment Flow

## Problem

The `hot-swap-session-data` branch refactored the analysis/cache pipeline and
dropped session working-memory enrichment from the server startup path.
Consequence: fact types inserted at runtime (dynamic inserts with
`:resolution :none` — e.g. `AuditTrail`, `ComplianceReview`,
`compliance-review-result`) and their type-hierarchy linkages disappeared from
`/v1/fact-types`.

The `swap-session!` path handles this correctly via auto-detect enrichment
modes (`build-auto-detect-layers` → memory layer → merge).  The gap is that
`start!` has no way to pass an enrichment mode — it only accepts `:layers`
(file paths), which map to `:source` with no `:enrichment`.

## Architecture Principle

**The annotations-atom is the source of truth.**  Whatever the caller puts there
(static or pre-enriched with WM-derived types) is what analysis consumes.
The cache layer is dumb — it uses `@annotations-atom` as-is.  Enrichment is a
caller-level concern, performed via `build-annotations` *before* the
annotations hit the atom.

Both `start!` and `swap-session!` should use the same `AnnotationsSpec`
interface for building annotations.  There should not be two parallel
mechanisms (`:layers` + `reload-annotations!` vs `:annotations` +
`build-annotations`).

## Current flows

### swap-session! (works correctly)

```
swap-session!({:annotations {:enrichment :auto-detect}})
  → build-annotations(session, annotations-spec, current)
      → build-auto-detect-annotations
          → build-auto-detect-layers
              → props + source + generated + memory  ← WM enrichment here
          → merge-layers
      → swap! annotations-atom  (atomic)
  → cache/warm! → cache/analysis → rulebase-analysis(enriched-annotations)
```

### start! (missing enrichment, uses separate `:layers` mechanism)

```
start!({:session s, :layers ["path/to/annos.edn"]})
  → reload-annotations!
      → load-merged-annotations  (props + file layers — NO memory layer)
      → reset! annotations-atom  (non-atomic)
  → cache/warm! → cache/analysis → rulebase-analysis(static-annotations)
```

### Non-atomic atom writes

Both paths have non-atomic `(reset! annotations-atom (build-annotations ... @annotations-atom))`.
The deref + reset is a read-then-write race.  Should be `(swap! annotations-atom #(build-annotations ... %))`.

## Solution

1. **Consolidate `start!` to use `:annotations` only** (same `AnnotationsSpec`
   as `swap-session!`).  Remove the separate `:layers` / `reload-annotations!`
   path.

2. **Fix non-atomic atom writes** everywhere (`swap!` instead of `reset!` + deref).

3. **Add `--annotations` CLI flag** to `main.clj`.  Keep `-l`/`--layer` as a
   backward-compatible shorthand.

4. **Default `demo-run` to `--enrichment auto-detect`**.

5. **No changes to `cache.clj` or `build-auto-detect-layers`** — they already
   work correctly.

## Detailed Changes

### 1. `server/src/clara/server/graph/server.clj`

#### a) Update `start!` signature — replace `:layers` with `:annotations`

```clojure
(defn start!
  "Starts the explorer server.
   Options:
   :session       - The Clara session to analyze.
   :annotations   - An AnnotationsSpec (same shape as swap-session!'s
                    :annotations arg).  May be nil (empty annotations),
                    a bare form (string path, vector of layers, map), or
                    a spec map with :source and optional :enrichment.
   :port          - Server port (default 9999).
   :working-memory-enabled - When false, working-memory routes return 409
                             (default true)."
  [{:keys [session annotations port working-memory-enabled]
    :or {port 9999 working-memory-enabled true}
    :as config}]
  (let [wm-available? (core/working-memory-available? session)]
    (reset! config-atom config)
    (reset! session-atom session)

    ;; Build annotations atomically via swap!
    (swap! annotations-atom #(build-annotations session annotations %))

    (when-not wm-available?
      (println "[server] Working-memory routes disabled: started with a rulebase, not a session"))
    (when (and wm-available? (not working-memory-enabled))
      (println "[server] Working-memory routes disabled by configuration (:working-memory-enabled false)"))

    (let [{:keys [handler cache]} (api/app session-atom annotations-atom working-memory-enabled)
          _ (reset! cache-atom cache)
          _ (cache/warm! cache session-atom annotations-atom)
          final-app (wrap-reload handler)]
      (when-let [server @server-instance]
        (Server/.stop server))
      (reset! server-instance
              (jetty/run-jetty final-app {:port port :join? false})))))
```

#### b) Remove `load-merged-annotations`

No longer needed — `build-annotations` → `coerce-to-bare-annotations` handles
file paths, vectors of layers, and enrichment uniformly:

```clojure
;; REMOVE:
;; (defn- load-merged-annotations
;;   "Folds the rule-:props layer (base) plus the configured `:layers` through
;;    merge-layers..."
;;   [session layers]
;;   (ann.merge/merge-layers (into [(ann.merge/props-layer session)]
;;                                 (map ann.merge/->layer)
;;                                 layers)))
```

#### c) Update `reload-annotations!` — use `swap!` + `build-annotations`

```clojure
(defn- reload-annotations! []
  (let [{:keys [session annotations]} @config-atom]
    (swap! annotations-atom #(build-annotations session annotations %))))
```

This reads the stored `:annotations` spec from `config-atom`.  File-backed
sources are re-read from disk on each reload (handled by `coerce-to-bare-annotations`
inside `build-annotations`).  Auto-detect modes are re-run (kondo analysis,
WM enrichment) on each reload.

#### d) Fix non-atomic pattern in `swap-session!`

Replace:
```clojure
;; BEFORE (non-atomic):
(let [s @session-atom
      bare (build-annotations s annotations @annotations-atom)]
  (reset! annotations-atom bare)
  (swap! config-atom assoc :annotations bare))
```

With:
```clojure
;; AFTER (atomic):
(let [s @session-atom]
  (swap! annotations-atom #(build-annotations s annotations %))
  (swap! config-atom assoc :annotations @annotations-atom))
```

### 2. `server/src/clara/server/graph/main.clj`

#### a) Add `--annotations` CLI option

```clojure
(def cli-options
  [["-s" "--session PATH" "Path to serialized Clara session file."]
   ["-l" "--layer PATH"
    (str "Path to an EDN annotation layer file.  Repeatable.  Shorthand for"
         " --annotations '{:source [paths...]}'.")
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-f" "--facts PATH" ...]
   ["-p" "--port PORT" ...]
   [nil "--working-memory-enabled BOOL" ...]
   [nil "--annotations EDN"
    (str "Annotations spec as inline EDN or path to an EDN file containing"
         " the spec.  The EDN value must be an AnnotationsSpec map with"
         " optional :source (bare map, vector of layers, string, or File)"
         " and :enrichment (:none, :reuse, :auto-detect-from-rulebase,"
         " :auto-detect-from-memory, :auto-detect)."
         "  Inline examples:"
         "  --annotations '{:source \"annos.edn\"}'"
         "  --annotations '{:source [\"a.edn\" \"b.edn\"] :enrichment :auto-detect}'"
         "  File path example:"
         "  --annotations my-spec.edn")
    :default nil]
   [nil "--generate-analysis DIR" ...]
   [nil "--load-session-state-fn SYMBOL" ...]
   ["-h" "--help" "Print this help."]])
```

#### b) Add `parse-annotations-arg` helper

```clojure
(defn- parse-annotations-arg
  "Parse the --annotations CLI value.  Tries inline EDN first; falls back to
   reading an EDN file at the given path.  A bare string result from inline
   EDN parsing is treated as a source file path and wrapped in {:source ...}."
  [s]
  (try
    (let [parsed (edn/read-string s)]
      (if (string? parsed)
        {:source parsed}
        parsed))
    (catch Exception _
      ;; Not valid EDN — treat as a file path containing an EDN spec
      (edn/read-string (slurp s)))))
```

#### c) Update `run-explorer-server` to normalize into annotations spec

```clojure
(defn run-explorer-server [options facts-path]
  (let [{:keys [session layer port load-session-state-fn
                working-memory-enabled annotations]} options
        ;; Build annotations spec: explicit --annotations takes priority,
        ;; then --layer as shorthand, then nil (empty annotations).
        annotations-spec (cond
                           annotations
                           (parse-annotations-arg annotations)
                           (seq layer)
                           {:source layer}
                           :else
                           nil)]
    ...
    (server/start!
     {:session loaded-session
      :port port
      :annotations annotations-spec
      :working-memory-enabled working-memory-enabled})
    ...))
```

### 3. `server/dev/clara/server/graph/demo_run.clj`

Default to `--enrichment auto-detect`:

```clojure
(defn -main [& args]
  (let [args (if (some #{"-p" "--port"} args)
               args
               (concat args ["-p" "9001"]))
        ;; Default to auto-detect enrichment so the dep graph includes
        ;; session-derived fact types (dynamic inserts).
        args (if (some #{"--annotations" "--enrichment"} args)
               args
               (concat args ["--annotations"
                             "{:enrichment :auto-detect}"]))
        ann-path (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
                         .getPath)]
    (if (some #{"-l" "--layer"} args)
      (apply main/-main args)
      (apply main/-main (concat args ["-l" ann-path])))))
```

Note: `-l` + `--annotations` are compatible — `run-explorer-server` gives
`--annotations` priority over `-l`.  When both are present, `--annotations`
wins (which is correct — explicit spec overrides shorthand).

### 4. `server/Makefile`

Update `demo-run` to include enrichment (redundant with demo-run.clj default,
but explicit for documentation):

```makefile
demo-run:
	clojure -M:demo-run -s demo-data/session.bin \
	  --annotations '{:enrichment :auto-detect}' \
	  -l test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn
```

### 5. `server/AGENTS.md`

Add atomic swap rule:

```markdown
## Atom Concurrency

- **Atomic swaps:** When updating an atom based on its current value, use
  `swap!` (never `reset!` with `@`).  `(reset! a @(f a))` is a read-then-write
  race; use `(swap! a f)` instead.
- **Identity-based cache invalidation:** When multiple atoms form a logical
  unit (e.g., session + annotations → cache), invalidate the cache by
  `identical?` reference checks rather than value equality — `reset!` on
  any input atom changes its identity, naturally invalidating the cache.
```

### 6. Test updates

#### `server/test/clara/server/graph/server_test.clj`

- Update `start-server!` helper to use `:annotations` instead of `:layers`:

  ```clojure
  (defn- start-server! [session layers]
    (server/start! {:port *port*
                    :session session
                    :annotations (when (seq layers) {:source layers})
                    :working-memory-enabled true}))
  ```

- Add integration test for `start!` with `:annotations {:enrichment :auto-detect}`:

  ```clojure
  (deftest test-start-with-wm-enrichment
    (testing "start! with :annotations {:enrichment :auto-detect} includes WM-derived fact types"
      ;; Start a separate server on a different port with enrichment
      ;; Verify /v1/fact-types includes dynamically-inserted types
      ))
  ```

- Existing swap-session enrichment tests (`test-swap-session-auto-detect-from-memory`,
  `test-swap-session-auto-detect`) should continue to pass — no changes to
  swap-session! logic.

## Files NOT Changed

- **`cache.clj`** — No changes.  The cache remains dumb; it uses
  `@annotations-atom` as-is.
- **`server.clj` `build-auto-detect-layers`** — No changes.  The memory
  enrichment layer stays here for both `start!` and `swap-session!`.
- **`api.clj`** — No changes.
- **`annotations/merge.clj`** — No changes.

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
   All three should show **PRESENT**.

4. **Verify AuditTrail detail has ancestors + inserted-by:**
   ```bash
   curl -s http://localhost:9001/v1/fact-types/clara.server.tools.graph.rules.loan-doc-rules.AuditTrail-ihl0yjcd
   ```
   Should include `ancestors` (with `known: false`) and `inserted-by-rules`
   pointing to `dynamic-insert-audit-trail`.

5. **Verify backward compat** — `-l` still works without `--annotations`:
   ```bash
   clojure -M:demo-run -s demo-data/session.bin -l path/to/annos.edn
   ```

6. **Verify demo-data git diff shows additions, not deletions:**
   ```bash
   git diff --stat HEAD -- ui/static/demo-data/
   ```
   Should add `AuditTrail`, `ComplianceReview`, `compliance-review-result`
   fact types and restore interface ancestor linkages — **not** the ~783-line
   removal seen on the current branch.
