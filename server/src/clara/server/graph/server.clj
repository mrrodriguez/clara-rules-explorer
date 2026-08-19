(ns clara.server.graph.server
  "Lifecycle management for the Clara Rules Explorer server.

   State is consolidated into a single atom per server instance; its contract
   is modeled by the `ServerState` schema.

   Transitions are pure functions over the state — trivially unit-testable.
   Side effects (Jetty start/stop, cache warming) stay in the shell layer.

   HTTP is read-only.  All mutation goes through the in-memory
   `swap-session!` / `reload-annotations!` API."
  (:require [ring.adapter.jetty :as jetty]
            [clara.rules.engine :as eng]
            [clara.server.graph.api :as api]
            [clara.server.graph.cache :as cache]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.analyze.callsite :as callsite]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.utils :as utils]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [schema.core :as s])
  (:import
   [org.eclipse.jetty.server
    Server]
   java.io.File))

;; ---------------------------------------------------------------------------
;; Default system — the global handle for REPL/back-compat
;; ---------------------------------------------------------------------------

(defonce ^:private default-system (atom nil))

(defn- require-system
  "Returns system or throws with a clear message."
  [system]
  (or system
      (throw (IllegalStateException.
              "no explorer system started — call start! first"))))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(s/defschema SessionOrRulebase
  "A live Clara session or a raw rulebase map."
  (s/pred #(or (satisfies? eng/ISession %) (map? %))
          'session-or-rulebase?))

(s/defschema AnnotationsSpec
  "Specification for building annotations.
   :source     — optional explicit annotations (nil, bare map, MergedAnnotations,
                 vector of Layers, string path, or File).
   :enrichment — optional mode: nil (source as-is), :none (explicitly no enrichment),
                 :reuse (keep current annotations, source takes priority),
                 :auto-detect-from-rulebase (props + static analysis on top of source),
                 :auto-detect-from-memory (props + WM enrichment on top of source),
                 :auto-detect (props + both on top of source).
   :fact-constructors   — optional vector of `analyze/FactConstructorSpec` maps
                          passed through to the generated analysis layer, declaring
                          caller-defined constructors of interest.
   :callsite-resolver-fn — optional fn passed through to the generated analysis
                           layer as the boundary-callsite escape hatch."
  {(s/optional-key :source) (s/maybe (s/pred (some-fn ann.merge/merged-annotations?
                                                      vector?
                                                      map?
                                                      string?
                                                      #(instance? File %))
                                             'annotations-input?))
   (s/optional-key :enrichment) (s/maybe (s/enum :none :reuse
                                                 :auto-detect-from-rulebase
                                                 :auto-detect-from-memory
                                                 :auto-detect))
   (s/optional-key :fact-constructors) [analyze/FactConstructorSpec]
   (s/optional-key :callsite-resolver-fn) (s/=> s/Any callsite/CallsiteResolverContext)})

(s/defschema AnnotationsArg
  "Either an `AnnotationsSpec` map or a legacy annotation-input form
   (bare map, `ann.merge/MergedAnnotations`, vector of `ann.merge/Layer`
   entries, string path, or File)."
  (s/pred (fn [x]
            (or (nil? x)
                (and (map? x)
                     (or (contains? x :source)
                         (contains? x :enrichment)
                         (contains? x :fact-constructors)
                         (contains? x :callsite-resolver-fn)))
                (ann.merge/merged-annotations? x)
                (vector? x)
                (string? x)
                (instance? File x)
                (map? x)))
          'annotations-arg?))

(s/defschema StartOpts
  "Validated config for `start!` / `start-system!`."
  {:session SessionOrRulebase
   (s/optional-key :annotations) (s/maybe AnnotationsArg)
   (s/optional-key :port) s/Int
   (s/optional-key :working-memory-enabled) s/Bool})

(s/defschema SwapSessionOpts
  "Options for `swap-session!`.  At least one of :session or :annotations
   must be provided."
  {(s/optional-key :session) SessionOrRulebase
   (s/optional-key :annotations) (s/maybe AnnotationsArg)
   (s/optional-key :warm-cache?) s/Bool})

(s/defschema BareAnnotations
  "A bare rule→annotation map: string rule-name keys, map values (the
   `:annotations` payload of a merged annotations result)."
  (s/pred (fn [m]
            (and (map? m)
                 (every? string? (keys m))
                 (every? map? (vals m))))
          'bare-annotations?))

(s/defschema MemoryAnalysis
  "A memory-analysis as produced by `clara.server.tools.graph.memory/->memory-analysis`.
   An open map here — the memory-analysis's full shape is owned by
   `clara.server.tools.graph.memory`."
  (s/pred map? 'memory-analysis?))

(s/defschema ServerState
  "The consolidated server state held in the system's state atom.  The source
   of truth for the state contract (the ns docstring refers here).  Optional
   keys are omitted from the map when their value would be nil."
  {:session                           SessionOrRulebase
   (s/optional-key :annotations-spec) AnnotationsArg
   :annotations                       BareAnnotations
   (s/optional-key :memory-analysis)  MemoryAnalysis
   :analyze-cache                     (s/pred map? 'analyze-cache?)})

;; ---------------------------------------------------------------------------
;; Annotation building — shared by transitions
;; ---------------------------------------------------------------------------

(def ^:private auto-detect-modes
  #{:auto-detect-from-rulebase :auto-detect-from-memory :auto-detect})

(defn- ->source-layer
  "Coerce one source entry to a `ann.merge/Layer`.  Path strings / Files are
   read from disk via `ann.merge/read-layer`; bare rule→annotation maps are
   wrapped as a source layer; `ann.merge/MergedAnnotations` are unwrapped
   first."
  [x]
  (cond
    (or (string? x) (instance? File x))
    (ann.merge/read-layer x)

    (ann.merge/merged-annotations? x)
    (ann.merge/layer {:id :source :annotations (ann.merge/annotations x)})

    (map? x)
    ;; A bare rule→annotation map — wrap as a source layer.
    (ann.merge/layer {:id :source :annotations x})

    :else
    (throw (IllegalArgumentException.
            (format "Unsupported source entry type: %s" (pr-str (type x)))))))

(defn- ->static-layers
  "Build the static annotation layers for auto-detect enrichment modes
   (props, source, generated).  Memory enrichment is handled separately
   via `analyze/->memory-layer` so it produces a proper delta layer against
   the accumulated static base.

   Each source entry becomes its own layer — path strings are read from
   disk, bare maps are wrapped as source layers, MergedAnnotations are
   unwrapped.  This preserves per-file `:provenance` and per-callsite
   `:from-layer` attribution instead of flattening all files into one
   `:id :source` layer.

   When a source layer already carries :id :clara.tools.graph.analyze/generated
   (e.g. a pre-computed kondo analysis saved as a static sidecar), the
   live-generated layer is skipped — the explicit source takes precedence.

   `analyze-cache-atom` is the temporary atom seeded from and committed
   back to the state map by the calling transition."
  [session source enrichment analyze-cache-atom analysis-opts]
  (let [source-layers (when (some? source)
                        (map ->source-layer
                             (if (vector? source) source [source])))
        source-ids (into #{} (map :id) source-layers)
        generated-layer (when (and (#{:auto-detect-from-rulebase :auto-detect} enrichment)
                                   (not (contains? source-ids :clara.tools.graph.analyze/generated)))
                          (let [analysis (analyze/->rule-source-analysis
                                          {:session-or-rulebase session
                                           :cache-atom analyze-cache-atom})]
                            (ann.merge/layer
                             {:id :clara.tools.graph.analyze/generated
                              :annotations (analyze/->annotations-from-rule-source-analysis
                                            (merge {:rule-source-analysis analysis
                                                    :session-or-rulebase session}
                                                   analysis-opts))})))
        layers (cond-> [(ann.merge/props-layer session)]
                 (seq source-layers) (into source-layers)
                 generated-layer (conj generated-layer))]
    layers))

(defn- ->auto-detect-annotations
  "Build annotations for the auto-detect enrichment modes, returning
   {:annotations … :memory-analysis …}.  `:memory-analysis` is the
   memory-analysis from memory enrichment (nil when it did not run).

   Static layers are merged first so the memory delta is computed against the
   accumulated base — not an empty map."
  [session source enrichment analyze-cache-atom analysis-opts]
  (let [wm? (core/working-memory-available? session)]
    (when (and (#{:auto-detect-from-memory :auto-detect} enrichment)
               (not wm?))
      (log/warnf "[server] %s requested but no working memory available — skipping memory enrichment"
                 enrichment))
    (let [static-layers (->static-layers session source enrichment analyze-cache-atom analysis-opts)
          merged-static (ann.merge/merge-layers static-layers)
          base          (ann.merge/annotations merged-static)
          memory?       (and wm?
                             (#{:auto-detect-from-memory :auto-detect} enrichment))
          {:keys [annotations memory-analysis]}
          (when memory?
            (analyze/merge-memory-derived-insert-types* base session))
          memory-layer  (when memory? (analyze/->memory-layer base annotations))]
      {:annotations (if memory-layer
                      (-> (conj static-layers memory-layer)
                          ann.merge/merge-layers
                          ann.merge/annotations)
                      base)
       :memory-analysis memory-analysis})))

(defn ->resolved-annotations*
  "Like `->resolved-annotations`, but returns {:annotations …} with an optional
   :memory-analysis (omitted when nil) so a caller that also needs the
   memory-analysis (the cache build) can reuse it.  See
   `->resolved-annotations` for the resolution semantics."
  ([session annotations-spec current-annotations]
   (->resolved-annotations* session annotations-spec current-annotations (atom {})))
  ([session annotations-spec current-annotations analyze-cache-atom]
   (let [;; Normalize legacy forms to {:source ...}
         spec (if (or (nil? annotations-spec)
                      (and (map? annotations-spec)
                           (or (contains? annotations-spec :source)
                               (contains? annotations-spec :enrichment)
                               (contains? annotations-spec :fact-constructors)
                               (contains? annotations-spec :callsite-resolver-fn))))
                annotations-spec
                {:source annotations-spec})
         ;; Validate spec-shaped maps at the choke point.
         _ (when (map? spec) (s/validate AnnotationsSpec spec))
         {:keys [source enrichment fact-constructors callsite-resolver-fn]} spec
         ;; `:memory-analysis` exists only on the auto-detect path, where it is
         ;; a dynamic value that may be nil — `remove-nil-vals` below strips it
         ;; when nil.  The other branches omit the key literally (memory
         ;; enrichment never runs there).
         analysis-opts (cond-> {}
                         fact-constructors (assoc :fact-constructors fact-constructors)
                         callsite-resolver-fn (assoc :callsite-resolver-fn callsite-resolver-fn))
         built
         (case enrichment
           :reuse
           (if (some? source)
             {:annotations (-> session
                               (->static-layers source nil analyze-cache-atom analysis-opts)
                               ann.merge/merge-layers
                               ann.merge/annotations)}
             (if (some? current-annotations)
               {:annotations current-annotations}
               {:annotations (-> session
                                 ann.merge/props-layer
                                 ann.merge/annotations)}))

           (:none nil)
           {:annotations (-> session
                             (->static-layers source nil analyze-cache-atom analysis-opts)
                             ann.merge/merge-layers
                             ann.merge/annotations)}

           ;; Auto-detect modes — explicit enumeration with fail-fast for unknown values.
           (:auto-detect-from-rulebase :auto-detect-from-memory :auto-detect)
           (->auto-detect-annotations session source enrichment analyze-cache-atom analysis-opts)

           ;; nil enrichment handled above; catch-all is unknown enum values
           (throw (IllegalArgumentException.
                   (format "Unknown :enrichment mode %s. Expected: %s"
                           enrichment (pr-str auto-detect-modes)))))]
     (utils/remove-nil-vals built))))

(defn ->resolved-annotations
  "Resolve annotations for `session` from `annotations-spec`.

   `annotations-spec` is an `AnnotationsSpec` map, or a legacy bare form
   (bare map, MergedAnnotations, vector of Layers, string path, or File)
   which is treated as `{:source <form>}`.

   `current-annotations` is the current `:annotations` value (used by
   :reuse when no source is given).  `analyze-cache-atom` is a temporary
   atom for per-ns kondo memoization across this build.

   Accepts `nil` annotations-spec → returns `{}`.

   Call without `analyze-cache-atom` for a one-shot build with a fresh
   per-build cache (convenience for tests and direct callers).

   Returns the resolved bare annotations map.  See `->resolved-annotations*` for
   the variant that also returns the memory-analysis."
  ([session annotations-spec current-annotations]
   (->resolved-annotations session annotations-spec current-annotations (atom {})))
  ([session annotations-spec current-annotations analyze-cache-atom]
   (:annotations (->resolved-annotations* session annotations-spec current-annotations analyze-cache-atom))))

;; ---------------------------------------------------------------------------
;; Pure state transitions
;; ---------------------------------------------------------------------------

(s/defn ^:private transition-start :- ServerState
  "Config -> State.  Builds fresh state from a validated config map.
   `:annotations-spec` is the raw spec as-given (may be nil for no annotations)."
  [{:keys [session annotations-spec]}]
  (let [tmp   (atom {})
        built (->resolved-annotations* session annotations-spec nil tmp)]
    (utils/remove-nil-vals
     {:session          session
      :annotations-spec annotations-spec
      :annotations      (:annotations built)
      :memory-analysis  (:memory-analysis built)
      :analyze-cache    @tmp})))

(s/defn ^:private transition-swap :- ServerState
  "State -> {:keys [session annotations-spec]} -> State.
   Only receives the state-affecting keys; :warm-cache? is consumed by the
   shell.  Absent `:session` carries the current session over; absent
   `:annotations-spec` clears annotations (nil spec → {})."
  [state :- ServerState
   {:keys [session annotations-spec]}]
  (let [s     (if (some? session) session (:session state))
        seed  (if (and (some? session)
                       (not (identical? session (:session state))))
                {}
                (:analyze-cache state))
        tmp   (atom seed)
        built (->resolved-annotations* s annotations-spec (:annotations state) tmp)]
    (utils/remove-nil-vals
     {:session          s
      :annotations-spec annotations-spec
      :annotations      (:annotations built)
      :memory-analysis  (:memory-analysis built)
      :analyze-cache    @tmp})))

(s/defn ^:private transition-reload :- ServerState
  "State -> State.  Re-derives :annotations from (:annotations-spec state)
   against the current session.  File-backed sources are re-read from disk;
   the generated (kondo) layer rebuilds from cached per-ns analyses in the
   state (kondo does not re-run)."
  [state :- ServerState]
  (let [tmp   (atom (:analyze-cache state))
        built (->resolved-annotations* (:session state)
                                       (:annotations-spec state)
                                       (:annotations state)
                                       tmp)]
    (utils/remove-nil-vals
     (assoc state
            :annotations (:annotations built)
            :memory-analysis (:memory-analysis built)
            :analyze-cache @tmp))))

;; ---------------------------------------------------------------------------
;; System lifecycle
;; ---------------------------------------------------------------------------

(defn start-system!
  "Pure constructor: validate, build state, warm cache, start Jetty, return
   the system map.  Never touches `default-system` — tests use this for true
   isolation.

   Returns {:config :state-atom :cache :handler :server}."
  [config]
  (let [_ (s/validate StartOpts config)
        {:keys [port working-memory-enabled] :or {port 9999 working-memory-enabled true}} config
        state (transition-start (clojure.set/rename-keys config {:annotations :annotations-spec}))
        state-atom (atom state)
        wm-available? (core/working-memory-available? (:session state))]
    (when-not wm-available?
      (log/warn "[server] Working-memory routes disabled: started with a rulebase, not a session"))
    (when (and wm-available? (not working-memory-enabled))
      (log/warn "[server] Working-memory routes disabled by configuration (:working-memory-enabled false)"))

    (let [{:keys [handler cache]} (api/app state-atom working-memory-enabled)]
      ;; Warm before binding Jetty — defensive: a request in the gap builds
      ;; on demand, but warming first means the first real request never
      ;; pays the cold-build penalty.
      (cache/warm! cache (:session state) (:annotations state) (:memory-analysis state))
      (let [jetty (jetty/run-jetty handler {:port port :join? false})]
        {:config      config
         :state-atom  state-atom
         :cache       cache
         :handler     handler
         :server      jetty}))))

(defn start!
  "Operator entry point: like `start-system!`, plus default-system
   management.  Same-port restart stops the previous default's Jetty BEFORE
   binding (prevents BindException); a previous default on a different port
   keeps running."
  [config]
  (let [port (:port config 9999)
        prev @default-system]
    (when (and prev (= port (get-in prev [:config :port] 9999)))
      (Server/.stop ^Server (:server prev)))
    (let [system (start-system! config)]
      (reset! default-system system)
      system)))

(defn stop!
  "Stops the explorer server.  0-arity stops the default system; 1-arity
   stops an explicit system (and resets the default when it's the same one)."
  ([]
   (when-let [system @default-system]
     (stop! system)))
  ([system]
   (when-let [^Server jetty (:server system)]
     (Server/.stop jetty))
   (when (identical? system @default-system)
     (reset! default-system nil))))

;; ---------------------------------------------------------------------------
;; Runtime mutation (in-memory only — HTTP never mutates state)
;; ---------------------------------------------------------------------------

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
                            {:session session
                             :annotations-spec annotations})]
       (when warm-cache?
         (cache/warm! (:cache system) (:session new-state) (:annotations new-state)
                      (:memory-analysis new-state)))
       (:annotations new-state)))))

(defn reload-annotations!
  "Re-derives annotations from the last effective `AnnotationsSpec` against
   the current session.  File-backed sources are re-read from disk; the generated
   (kondo) layer rebuilds from cached per-ns analyses in the state (kondo
   does not re-run).
   0-arity operates on the default system; 1-arity on an explicit system."
  ([]
   (reload-annotations! (require-system @default-system)))
  ([system]
   (let [new-state (swap! (:state-atom system) transition-reload)]
     (cache/warm! (:cache system) (:session new-state) (:annotations new-state)
                  (:memory-analysis new-state))
     (:annotations new-state))))
