(ns clara.server.graph.server
  "Lifecycle management for the Clara Rules Explorer server."
  (:require [ring.adapter.jetty :as jetty]
            [clara.server.graph.api :as api]
            [clara.server.tools.graph.annotations :as ann])
  (:import
   [org.eclipse.jetty.server
    Server]))

(defonce ^:private server-instance (atom nil))
(defonce ^:private session-atom (atom nil))
(defonce ^:private annotations-atom (atom {}))
(defonce ^:private config-atom (atom {}))

(defn- reload-annotations! []
  (when-let [path (:annotations-file @config-atom)]
    (reset! annotations-atom (ann/load-sidecar path))))

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
   :session - The Clara session to analyze.
   :annotations-file - Path to an optional EDN sidecar file.
   :port             - Server port (default 9999)."
  [{:keys [session port] :or {port 9999} :as config}]
  (reset! config-atom config)
  (reset! session-atom session)
  (reload-annotations!)

  (let [base-app (api/app session-atom annotations-atom)
        final-app (wrap-reload base-app)]
    (when-let [server  @server-instance]
      (Server/.stop server))
    (reset! server-instance
            (jetty/run-jetty final-app {:port port :join? false}))))

(defn stop!
  "Stops the explorer server."
  []
  (when-let [server @server-instance]
    (Server/.stop server)
    (reset! server-instance nil)))
