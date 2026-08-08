(ns clara.server.graph.server
  "Lifecycle management for the Clara Rules Explorer server.

   State is consolidated into a single atom per server instance:
     {:session           ;; live Clara session or raw rulebase
      :annotations-spec  ;; the AnnotationsSpec that produced :annotations
      :annotations       ;; derived bare annotations (source of truth for analysis)
      :analyze-cache}    ;; per-ns kondo memoization (plain immutable map)

   Transitions are pure functions over the state — trivially unit-testable.
   Side effects (Jetty start/stop, cache warming) stay in the shell layer.

   HTTP is read-only.  All mutation goes through the in-memory
   `swap-session!` / `reload-annotations!` API."
  (:require [ring.adapter.jetty :as jetty]
            [clara.rules.engine :as eng]
            [clara.server.graph.api :as api]
            [clara.server.graph.cache :as cache]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core]
            [clojure.set :as set]
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
                 :auto-detect (props + both on top of source)."
  {(s/optional-key :source) (s/maybe (s/pred (some-fn ann.merge/merged-annotations?
                                                      vector?
                                                      map?
                                                      string?
                                                      #(instance? File %))
                                             'annotations-input?))
   (s/optional-key :enrichment) (s/maybe (s/enum :none :reuse
                                                 :auto-detect-from-rulebase
                                                 :auto-detect-from-memory
                                                 :auto-detect))})

(s/defschema AnnotationsArg
  "Either an AnnotationsSpec map or a legacy annotation-input form
   (bare map, MergedAnnotations, vector of Layers, string path, or File)."
  (s/pred (fn [x]
            (or (nil? x)
                (and (map? x)
                     (or (contains? x :source)
                         (contains? x :enrichment)))
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

;; ---------------------------------------------------------------------------
;; Annotation building — shared by transitions
;; ---------------------------------------------------------------------------

(def ^:private auto-detect-modes
  #{:auto-detect-from-rulebase :auto-detect-from-memory :auto-detect})

(defn- build-auto-detect-layers
  "Build the layer vector for auto-detect enrichment modes."
  [session source enrichment wm? analyze-cache-atom]
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
                          generated)})
    (and (#{:auto-detect-from-memory :auto-detect} enrichment)
         wm?)
    (conj {:id :clara.tools.graph.analyze/memory
           :annotations (analyze/enrich-annotations-from-session session {})})))

(defn- build-auto-detect-annotations
  "Build annotations for :auto-detect-from-rulebase, :auto-detect-from-memory,
   or :auto-detect enrichment modes."
  [session source enrichment analyze-cache-atom]
  (let [wm? (core/working-memory-available? session)]
    (when (and (#{:auto-detect-from-memory :auto-detect} enrichment)
               (not wm?))
      (println (format "[server] %s requested but no working memory available — skipping WM enrichment"
                       enrichment)))
    (-> (build-auto-detect-layers session source enrichment wm? analyze-cache-atom)
        ann.merge/merge-layers
        ann.merge/annotations)))

(defn build-annotations
  "Resolve annotations for `session` from `annotations-spec`.

   `annotations-spec` is an `AnnotationsSpec` map, or a legacy bare form
   (bare map, MergedAnnotations, vector of Layers, string path, or File)
   which is treated as `{:source <form>}`.

   `current-annotations` is the current `:annotations` value (used by
   :reuse when no source is given).  `analyze-cache-atom` is a temporary
   atom for per-ns kondo memoization across this build.

   Accepts `nil` annotations-spec → returns `{}`.

   Call without `analyze-cache-atom` for a one-shot build with a fresh
   per-build cache (convenience for tests and direct callers)."
  ([session annotations-spec current-annotations]
   (build-annotations session annotations-spec current-annotations (atom {})))
  ([session annotations-spec current-annotations analyze-cache-atom]
   (let [;; Normalize legacy forms to {:source ...}
         spec (if (or (nil? annotations-spec)
                      (and (map? annotations-spec)
                           (or (contains? annotations-spec :source)
                               (contains? annotations-spec :enrichment))))
                annotations-spec
                {:source annotations-spec})
         ;; Validate spec-shaped maps at the choke point.
         _ (when (map? spec) (s/validate AnnotationsSpec spec))
         {:keys [source enrichment]} spec]
     (case enrichment
       :reuse
       (if (some? source)
         (ann.merge/coerce-to-bare-annotations source session)
         current-annotations)

       :none
       (if (some? source)
         (ann.merge/coerce-to-bare-annotations source session)
         {})

       nil
       (if (some? source)
         (ann.merge/coerce-to-bare-annotations source session)
         {})

       ;; Auto-detect modes — explicit enumeration with fail-fast for unknown values.
       (:auto-detect-from-rulebase :auto-detect-from-memory :auto-detect)
       (build-auto-detect-annotations session source enrichment analyze-cache-atom)

       ;; nil enrichment handled above; catch-all is unknown enum values
       (throw (IllegalArgumentException.
               (format "Unknown :enrichment mode %s. Expected: %s"
                       enrichment (pr-str auto-detect-modes))))))))

;; ---------------------------------------------------------------------------
;; Pure state transitions
;; ---------------------------------------------------------------------------

(defn- transition-start
  "Config -> State.  Builds fresh state from a validated config map.
   `:annotations-spec` is the raw spec as-given (may be nil for no annotations)."
  [{:keys [session annotations-spec]}]
  (let [tmp   (atom {})
        built (build-annotations session annotations-spec nil tmp)]
    {:session          session
     :annotations-spec annotations-spec
     :annotations      built
     :analyze-cache    @tmp}))

(defn- transition-swap
  "State -> {:keys [session annotations-spec]} -> State.
   Only receives the state-affecting keys; :warm-cache? is consumed by the
   shell.  Absent `:session` carries the current session over; absent
   `:annotations-spec` clears annotations (nil spec → {})."
  [state {:keys [session annotations-spec]}]
  (let [s     (if (some? session) session (:session state))
        seed  (if (and (some? session)
                       (not (identical? session (:session state))))
                {}
                (:analyze-cache state))
        tmp   (atom seed)
        built (build-annotations s annotations-spec (:annotations state) tmp)]
    {:session          s
     :annotations-spec annotations-spec
     :annotations      built
     :analyze-cache    @tmp}))

(defn- transition-reload
  "State -> State.  Re-derives :annotations from (:annotations-spec state)
   against the current session.  File-backed sources are re-read from disk;
   the generated (kondo) layer rebuilds from cached per-ns analyses in the
   state (kondo does not re-run)."
  [state]
  (let [tmp   (atom (:analyze-cache state))
        built (build-annotations (:session state)
                                 (:annotations-spec state)
                                 (:annotations state)
                                 tmp)]
    (assoc state
           :annotations built
           :analyze-cache @tmp)))

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
      (println "[server] Working-memory routes disabled: started with a rulebase, not a session"))
    (when (and wm-available? (not working-memory-enabled))
      (println "[server] Working-memory routes disabled by configuration (:working-memory-enabled false)"))

    (let [{:keys [handler cache]} (api/app state-atom working-memory-enabled)]
      ;; Warm before binding Jetty — defensive: a request in the gap builds
      ;; on demand, but warming first means the first real request never
      ;; pays the cold-build penalty.
      (cache/warm! cache (:session state) (:annotations state))
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
         (cache/warm! (:cache system) (:session new-state) (:annotations new-state)))
       (:annotations new-state)))))

(defn reload-annotations!
  "Re-derives annotations from the last effective AnnotationsSpec against the
   current session.  File-backed sources are re-read from disk; the generated
   (kondo) layer rebuilds from cached per-ns analyses in the state (kondo
   does not re-run).
   0-arity operates on the default system; 1-arity on an explicit system."
  ([]
   (reload-annotations! (require-system @default-system)))
  ([system]
   (let [new-state (swap! (:state-atom system) transition-reload)]
     (cache/warm! (:cache system) (:session new-state) (:annotations new-state))
     (:annotations new-state))))
