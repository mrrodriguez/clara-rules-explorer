(ns clara.server.graph.cache
  "Analysis and memory-analysis caching for the Clara Rules Explorer server.

   A single atom holds the cached analysis state plus the derived
   memory-analysis.  Both `clara.server.graph.api` (HTTP handlers) and
   `clara.server.graph.server` (lifecycle) use this namespace.

   Invalidation is automatic: every access compares the current inputs
   **values** against the cached ones via `identical?`.  In session mode the
   inputs are the session and annotations; in registry mode the input is the
   supplied `:rulebase-analysis` value, which is returned as-is (nothing is
   recomputed — the registry mode already composed and rehydrated it)."
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

(defn- ->session-state
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
  "Returns the cached state map, rebuilding when the inputs' identity changes.
   `state` is a server state map: registry mode (`:rulebase-analysis` present)
   returns the supplied analysis as-is, invalidated by `identical?` on that
   value; session mode rebuilds from session + annotations as before."
  [cache {:keys [session rulebase-analysis annotations memory-analysis]}]
  (let [cached @cache]
    (if rulebase-analysis
      (if (and cached (identical? (:rulebase-analysis cached) rulebase-analysis))
        cached
        (let [built {:rulebase-analysis rulebase-analysis
                     :memory-analysis   nil
                     :registry-mode?    true}]
          (reset! cache built)
          built))
      (if (and cached
               (identical? (:session cached) session)
               (identical? (:annotations cached) annotations))
        cached
        (let [built (assoc (->session-state session annotations memory-analysis)
                           :session session
                           :annotations annotations)]
          (reset! cache built)
          built)))))

(defn get-rulebase-analysis
  "Returns the cached rulebase-analysis map for the current server state,
   rebuilding transparently when inputs change.  In registry mode the supplied
   analysis is returned as-is."
  [cache state]
  (:rulebase-analysis (get-state cache state)))

(defn get-memory-analysis
  "Returns the cached memory-analysis for the current server state (nil when
   working memory is unavailable — always nil in registry mode), rebuilding
   transparently on change."
  [cache state]
  (:memory-analysis (get-state cache state)))

(defn warm!
  "Eagerly populates the cache so the next request avoids the full build.

   `state` is the server state map; in session mode `:memory-analysis` (when
   non-nil) is reused instead of re-inspecting the session."
  [cache state]
  (get-state cache state)
  nil)
