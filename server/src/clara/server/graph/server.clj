(ns clara.server.graph.server
  "Lifecycle management for the Clara Rules Explorer server."
  (:require [ring.adapter.jetty :as jetty]
            [clara.server.graph.api :as api]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core])
  (:import
   [org.eclipse.jetty.server
    Server]))

(defonce ^:private server-instance (atom nil))
(defonce ^:private session-atom (atom nil))
(defonce ^:private annotations-atom (atom {}))
(defonce ^:private config-atom (atom {}))

(defn- ->layer
  "Coerces a `:layers` entry to a Layer: a path string is read from disk, a
   map is taken as an in-memory layer."
  [x]
  (if (string? x)
    (ann.merge/read-layer x)
    (ann.merge/layer x)))

(defn- load-merged-annotations
  "Folds the rule-:props layer (base) plus the configured `:layers` through
   merge-layers.  File-backed layers are re-read on every call, so
   POST /v1/annotations/reload picks up edits; in-memory layers are kept
   as-is."
  [session layers]
  (ann.merge/merge-layers (into [(ann.merge/props-layer session)]
                                (map ->layer)
                                layers)))

(defn- reload-annotations! []
  (let [{:keys [session layers]} @config-atom]
    (reset! annotations-atom (load-merged-annotations session layers))))

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

    (let [{:keys [handler analysis-cache]} (api/app session-atom annotations-atom working-memory-enabled)
          _ (api/warm-analysis-cache! session-atom annotations-atom analysis-cache)
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
