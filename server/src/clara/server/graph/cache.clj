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
   with `:enrich-from-session? true`) before the cache rebuilds.

   `memory-snapshot` (when non-nil) is the working-memory snapshot already
   produced by memory enrichment for this session; it is reused and only its
   `:known` flags are re-stamped from the analysis, avoiding a second
   in-memory inspection."
  [session annotations memory-snapshot]
  (let [bare      (ann.merge/->bare-annotations annotations)
        analysis  (core/rulebase-analysis session bare)
        known-set (-> analysis :fact-types keys set)
        snapshot  (if memory-snapshot
                    (memory/update-snapshot-known-set memory-snapshot known-set)
                    (memory/session-snapshot-from-analysis session analysis))]
    {:analysis analysis
     :snapshot snapshot}))

;; ---------------------------------------------------------------------------
;; Cache access
;; ---------------------------------------------------------------------------

(defn- get-state
  "Returns the cached state map, rebuilding when the session or annotations
   reference has changed (identity check).  The state map includes
   `:analysis`, reverse indexes, and `:snapshot` (nil when working memory
   is unavailable)."
  [cache session annotations memory-snapshot]
  (let [cached @cache]
    (if (and cached
             (identical? (:session cached) session)
             (identical? (:annotations cached) annotations))
      cached
      (let [state (build-state session annotations memory-snapshot)]
        (reset! cache (assoc state
                             :session session
                             :annotations annotations))))))

(defn analysis
  "Returns the cached rulebase-analysis map for the current session and
   annotations, rebuilding transparently when inputs change.

   `memory-snapshot` is the enrichment-phase working-memory snapshot (nil when
   none); on a miss it is reused instead of re-inspecting the session."
  [cache session annotations memory-snapshot]
  (:analysis (get-state cache session annotations memory-snapshot)))

(defn snapshot
  "Returns the cached working-memory snapshot for the current session (nil
   when working memory is unavailable), rebuilding transparently on change.

   `memory-snapshot` is the enrichment-phase working-memory snapshot (nil when
   none); on a miss it is reused instead of re-inspecting the session."
  [cache session annotations memory-snapshot]
  (:snapshot (get-state cache session annotations memory-snapshot)))

(defn warm!
  "Eagerly populates the cache so the next request avoids the full
   `rulebase-analysis` + `session-snapshot` build.

   `memory-snapshot` is the enrichment-phase working-memory snapshot (nil when
   none); when non-nil it is reused instead of re-inspecting the session."
  [cache session annotations memory-snapshot]
  (get-state cache session annotations memory-snapshot)
  nil)
