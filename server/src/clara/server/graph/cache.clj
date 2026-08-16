(ns clara.server.graph.cache
  "Analysis and memory-analysis caching for the Clara Rules Explorer server.

   A single atom holds the cached analysis state plus the derived
   memory-analysis.  Both `clara.server.graph.api` (HTTP handlers) and
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

(defn ->cache
  "Returns a fresh, empty cache atom."
  []
  (atom nil))

;; ---------------------------------------------------------------------------
;; Internal builders
;; ---------------------------------------------------------------------------

(defn- ->state
  "Builds the rulebase-analysis state and memory-analysis from the current
   session and annotations.  Annotations are unwrapped to bare form; session
   working-memory enrichment is NOT applied here — it is the caller's
   responsibility to enrich `annotations-atom` (e.g. via `swap-session!`
   with `:enrich-from-session? true`) before the cache rebuilds.

   `memory-analysis` (when non-nil) is the memory-analysis already produced by
   memory enrichment for this session; it is reused and only its `:known`
   flags are re-stamped from the analysis, avoiding a second in-memory
   inspection."
  [session annotations memory-analysis]
  (let [bare              (ann.merge/->bare-annotations annotations)
        rulebase-analysis (core/->rulebase-analysis session bare)
        known-set         (-> rulebase-analysis :fact-types keys set)
        memory-analysis   (if memory-analysis
                            (memory/update-memory-analysis-known-set memory-analysis known-set)
                            (when (core/working-memory-available? session)
                              (memory/->memory-analysis session known-set)))]
    {:rulebase-analysis rulebase-analysis
     :memory-analysis   memory-analysis}))

;; ---------------------------------------------------------------------------
;; Cache access
;; ---------------------------------------------------------------------------

(defn- get-state
  "Returns the cached state map, rebuilding when the session or annotations
   reference has changed (identity check).  The state map includes
   `:rulebase-analysis`, reverse indexes, and `:memory-analysis` (nil when
   working memory is unavailable)."
  [cache session annotations memory-analysis]
  (let [cached @cache]
    (if (and cached
             (identical? (:session cached) session)
             (identical? (:annotations cached) annotations))
      cached
      (let [state (->state session annotations memory-analysis)]
        (reset! cache (assoc state
                             :session session
                             :annotations annotations))))))

(defn get-rulebase-analysis
  "Returns the cached rulebase-analysis map for the current session and
   annotations, rebuilding transparently when inputs change.

   `memory-analysis` is the enrichment-phase memory-analysis (nil when none);
   on a miss it is reused instead of re-inspecting the session."
  [cache session annotations memory-analysis]
  (:rulebase-analysis (get-state cache session annotations memory-analysis)))

(defn get-memory-analysis
  "Returns the cached memory-analysis for the current session (nil when
   working memory is unavailable), rebuilding transparently on change.

   `memory-analysis` is the enrichment-phase memory-analysis (nil when none);
   on a miss it is reused instead of re-inspecting the session."
  [cache session annotations memory-analysis]
  (:memory-analysis (get-state cache session annotations memory-analysis)))

(defn warm!
  "Eagerly populates the cache so the next request avoids the full
   `->rulebase-analysis` + `->memory-analysis` build.

   `memory-analysis` is the enrichment-phase memory-analysis (nil when none);
   when non-nil it is reused instead of re-inspecting the session."
  [cache session annotations memory-analysis]
  (get-state cache session annotations memory-analysis)
  nil)
