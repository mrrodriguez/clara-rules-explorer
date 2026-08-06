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
    Server]))

(defonce ^:private server-instance (atom nil))
(defonce ^:private session-atom (atom nil))
(defonce ^:private annotations-atom (atom {}))
(defonce ^:private config-atom (atom {}))

;; The cache atom created by `clara.server.graph.api/app`, stored here so
;; `swap-session!` can eagerly warm it after a runtime swap.
(defonce ^:private cache-atom (atom nil))

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
    (reset! annotations-atom (load-merged-annotations session layers))))

;; ---------------------------------------------------------------------------
;; Runtime session / annotation swap
;; ---------------------------------------------------------------------------

(s/defschema SessionOrRulebase
  "A live Clara session or a raw rulebase map."
  (s/pred #(or (satisfies? eng/ISession %) (map? %))
          'session-or-rulebase?))

(s/defschema AnnotationsInput
  "Any of the three valid annotation-input forms:
     - a MergedAnnotations value (from merge-layers)
     - a vector of Layer maps (merged on the fly)
     - a bare rule->annotation map."
  (s/pred (some-fn ann.merge/merged-annotations?
                   vector?
                   map?)
          'annotations-input?))

(s/defschema SwapSessionOpts
  "Options for `swap-session!`.  At least one of :session or :annotations
   must be provided."
  {(s/optional-key :session) SessionOrRulebase
   (s/optional-key :annotations) AnnotationsInput
   (s/optional-key :enrich-from-session?) s/Bool
   (s/optional-key :warm-cache?) s/Bool})

(defn swap-session!
  "Hot-swap the running server's session and/or annotations at runtime.

   `opts` is a `SwapSessionOpts` map.  When :session is provided without
   :annotations, annotations are recomputed from the new session's rule
   :props merged with the currently configured layers (same effect as
   POST /v1/annotations/reload but against the new session).

   Returns the new value of `annotations-atom` (the bare annotations map)."
  [opts]
  (let [{:keys [session annotations enrich-from-session? warm-cache?]
         :or {enrich-from-session? false warm-cache? true}}
        (s/validate SwapSessionOpts opts)]
    (when (and (nil? session) (nil? annotations))
      (throw (IllegalArgumentException.
              "swap-session! requires at least one of :session or :annotations")))

    (let [session-updated? (some? session)]
      ;; 1. Swap the session
      (when session-updated?
        ;; config-atom keeps the canonical session ref for reload-annotations!
        (swap! config-atom assoc :session session)
        (reset! session-atom session))

      ;; 2. Update annotations
      (cond
        ;; Explicit annotations supplied — coerce and store
        (some? annotations)
        (reset! annotations-atom
                (ann.merge/coerce-to-bare-annotations annotations @session-atom))

        ;; Session changed but no annotations given — recompute from new
        ;; session's rule :props + existing layers
        session-updated?
        (reload-annotations!))

      ;; 3. Optionally enrich with session working-memory types
      (when enrich-from-session?
        (let [s @session-atom]
          (when (core/working-memory-available? s)
            (swap! annotations-atom
                   #(analyze/enrich-annotations-from-session s %)))))

      ;; 4. Eagerly warm the cache so the next request avoids the full
      ;;    rulebase-analysis build cost
      (when warm-cache?
        (when-let [c @cache-atom]
          (cache/warm! c session-atom annotations-atom)))

      @annotations-atom)))

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
