(ns clara.server.graph.server
  "Lifecycle management for the Clara Rules Explorer server."
  (:require [ring.adapter.jetty :as jetty]
            [clara.rules.engine :as eng]
            [clara.server.graph.api :as api]
            [clara.server.graph.cache :as cache]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core]
            [schema.core :as s])
  (:import
   [org.eclipse.jetty.server
    Server]
   java.io.File))

(defonce ^:private server-instance (atom nil))
(defonce ^:private session-atom (atom nil))
(defonce ^:private annotations-atom (atom {}))
(defonce ^:private config-atom (atom {}))

;; The cache atom created by `clara.server.graph.api/app`, stored here so
;; `swap-session!` can eagerly warm it after a runtime swap.
(defonce ^:private cache-atom (atom nil))

(defonce ^{:private true
           :doc "Per-namespace analysis cache shared across `swap-session!` calls.
                `analyze-session-rules` caches per-ns results here so repeated annotation
                builds against the same session avoid re-analyzing rule namespaces.
                Cleared when the session reference changes identity."}
  analyze-cache-atom (atom {}))

(defn- load-merged-annotations
  "Folds the rule-:props layer (base) plus the configured `:layers` through
   merge-layers.  File-backed layers are re-read on every call, so
   POST /v1/annotations/reload picks up edits; in-memory layers are kept
   as-is."
  [session layers]
  (ann.merge/merge-layers (into [(ann.merge/props-layer session)]
                                (map ann.merge/->layer)
                                layers)))

(defn- reload-annotations! []
  (let [{:keys [session layers]} @config-atom]
    (reset! annotations-atom
            (load-merged-annotations session layers))))

;; ---------------------------------------------------------------------------
;; Annotation building
;; ---------------------------------------------------------------------------

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

(defn- build-auto-detect-layers
  "Build the layer vector for auto-detect enrichment modes."
  [session source enrichment wm?]
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
  [session source enrichment]
  (let [wm? (core/working-memory-available? session)]
    (when (and (#{:auto-detect-from-memory} enrichment)
               (not wm?))
      (println "[server] :auto-detect-from-memory requested but no working memory available — skipping"))
    (-> (build-auto-detect-layers session source enrichment wm?)
        ann.merge/merge-layers
        ann.merge/annotations)))

(defn build-annotations
  "Resolve annotations for `session` from `annotations-spec`.

   `annotations-spec` is an `AnnotationsSpec` map, or a legacy bare form
   (bare map, MergedAnnotations, vector of Layers, string path, or File)
   which is treated as `{:source <form>}`.

   `current-annotations` is the current @annotations-atom value (used by
   :reuse when no source is given)."
  [session annotations-spec current-annotations]
  (let [;; Normalize legacy forms to {:source ...}
        {:keys [source enrichment]}
        (if (or (nil? annotations-spec)
                (and (map? annotations-spec)
                     (or (contains? annotations-spec :source)
                         (contains? annotations-spec :enrichment))))
          annotations-spec
          {:source annotations-spec})]
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

      ;; Auto-detect modes
      (build-auto-detect-annotations session source enrichment))))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(s/defschema SessionOrRulebase
  "A live Clara session or a raw rulebase map."
  (s/pred #(or (satisfies? eng/ISession %) (map? %))
          'session-or-rulebase?))

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

(s/defschema SwapSessionOpts
  "Options for `swap-session!`.  At least one of :session or :annotations
   must be provided."
  {(s/optional-key :session) SessionOrRulebase
   (s/optional-key :annotations) (s/maybe AnnotationsArg)
   (s/optional-key :warm-cache?) s/Bool})

(defn swap-session!
  "Hot-swap the running server's session and/or annotations at runtime.

   `opts` is a `SwapSessionOpts` map:
     :session     — New Clara session (or rulebase).
     :annotations — An `AnnotationsSpec` map, or a legacy bare form (map,
                    MergedAnnotations, vector of Layers, string path, or
                    File).  When absent and :session is given, annotations
                    are cleared.

   Returns the new value of `annotations-atom` (a bare annotations map)."
  [opts]
  (let [{:keys [session annotations warm-cache?]
         :or {warm-cache? true}}
        (s/validate SwapSessionOpts opts)]
    (when (and (nil? session) (nil? annotations))
      (throw (IllegalArgumentException.
              "swap-session! requires at least one of :session or :annotations")))

    ;; 1. Swap the session
    (when (some? session)
      (swap! config-atom assoc :session session)
      (reset! session-atom session)
      ;; New session may have different rules in the same namespaces —
      ;; clear the analysis cache so namespaces are re-analyzed.
      (reset! analyze-cache-atom {}))

    ;; 2. Build annotations
    (when (or (some? session) (some? annotations))
      (let [s @session-atom
            bare (build-annotations s annotations @annotations-atom)]
        (reset! annotations-atom bare)
        (swap! config-atom assoc :annotations bare)))

    ;; 3. Warm the cache
    (when warm-cache?
      (when-let [c @cache-atom]
        (cache/warm! c session-atom annotations-atom)))

    @annotations-atom))

(defn- wrap-reload [handler]
  (fn [req]
    (if (and (= :post (:request-method req))
             (= "/v1/annotations/reload" (:uri req)))
      (do
        (reload-annotations!)
        {:status 200 :body @annotations-atom})
      (handler req))))

(defn start!
  "Starts the explorer server.
   Options:
   :session       - The Clara session to analyze.  A raw Rulebase is also
                    accepted; when given a rulebase, working-memory routes
                    return 409 (see :working-memory-enabled).
   :layers        - Ordered vector of annotation layers (lowest precedence
                    first): path strings (read from disk, re-read on reload)
                    or in-memory layer maps.  The rule-:props layer is
                    folded in first, as the base everything else overlays.
   :port          - Server port (default 9999).
   :working-memory-enabled - When false, working-memory routes return 409
                             even when a live session is provided
                             (default true)."
  [{:keys [session port working-memory-enabled] :or {port 9999 working-memory-enabled true} :as config}]
  ;; Pass the RAW :working-memory-enabled flag to api/app, not a conjunction
  ;; with session capability: the 409 attribution (:rulebase-input vs
  ;; :disabled-by-config) depends on the two causes staying separate.
  (let [wm-available? (core/working-memory-available? session)]
    (reset! config-atom config)
    (reset! session-atom session)
    (reload-annotations!)
    (when-not wm-available?
      (println "[server] Working-memory routes disabled: started with a rulebase, not a session"))
    (when (and wm-available? (not working-memory-enabled))
      (println "[server] Working-memory routes disabled by configuration (:working-memory-enabled false)"))

    (let [{:keys [handler cache]} (api/app session-atom annotations-atom working-memory-enabled)
          _ (reset! cache-atom cache)
          _ (cache/warm! cache session-atom annotations-atom)
          final-app (wrap-reload handler)]
      (when-let [server  @server-instance]
        (Server/.stop server))
      (reset! server-instance
              (jetty/run-jetty final-app {:port port :join? false})))))

(defn stop!
  "Stops the explorer server."
  []
  (when-let [server @server-instance]
    (Server/.stop server)
    (reset! server-instance nil)))
