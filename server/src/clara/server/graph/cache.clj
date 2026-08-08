(ns clara.server.graph.cache
  "Analysis and session-snapshot caching for the Clara Rules Explorer server.

   A single atom holds the cached analysis state plus the derived session
   snapshot.  Both `clara.server.graph.api` (HTTP handlers) and
   `clara.server.graph.server` (lifecycle) use this namespace.

   Invalidation is automatic: every access compares the current session and
   annotations **values** against the cached ones via `identical?`.  When
   either value changes identity, the cache misses and rebuilds."
  (:require [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.annotations.merge :as ann.merge]))

;; ---------------------------------------------------------------------------
;; Cache lifecycle
;; ---------------------------------------------------------------------------

(defn create
  "Returns a fresh, empty cache atom."
  []
  (atom nil))

;; ---------------------------------------------------------------------------
;; Internal builders
;; ---------------------------------------------------------------------------

(defn- build-state
  "Builds the analysis state and session snapshot from the current session
   and annotations.  Annotations are unwrapped to bare form; session
   working-memory enrichment is NOT applied here — it is the caller's
   responsibility to enrich `annotations-atom` (e.g. via `swap-session!`
   with `:enrich-from-session? true`) before the cache rebuilds."
  [session annotations]
  (let [bare (ann.merge/->bare-annotations annotations)
        analysis (core/rulebase-analysis session bare)]
    {:analysis analysis
     :snapshot (memory/session-snapshot-from-analysis session analysis)}))

;; ---------------------------------------------------------------------------
;; Cache access
;; ---------------------------------------------------------------------------

(defn- get-state
  "Returns the cached state map, rebuilding when the session or annotations
   reference has changed (identity check).  The state map includes
   `:analysis`, reverse indexes, and `:snapshot` (nil when working memory
   is unavailable)."
  [cache session annotations]
  (let [cached @cache]
    (if (and cached
             (identical? (:session cached) session)
             (identical? (:annotations cached) annotations))
      cached
      (let [state (build-state session annotations)]
        (reset! cache (assoc state
                             :session session
                             :annotations annotations))))))

(defn analysis
  "Returns the cached rulebase-analysis map for the current session and
   annotations.  Rebuilds transparently when inputs change.  The returned
   map includes `:production-id-index` and `:fact-type-id-index` reverse
   indexes (computed by `core/rulebase-analysis`)."
  [cache session annotations]
  (:analysis (get-state cache session annotations)))

(defn snapshot
  "Returns the cached working-memory snapshot for the current session, or
   nil when working memory is unavailable."
  [cache session annotations]
  (:snapshot (get-state cache session annotations)))

(defn warm!
  "Eagerly populates the cache so the next request does not pay the full
   `rulebase-analysis` + `session-snapshot` build cost."
  [cache session annotations]
  (get-state cache session annotations)
  nil)
