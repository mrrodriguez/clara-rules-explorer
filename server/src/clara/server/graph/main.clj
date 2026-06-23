(ns clara.server.graph.main
  "CLI entry point for the Clara Graph Server.
   Loads a serialized Clara session and starts the explorer HTTP API."
  (:require [clara.server.graph.server :as server]
            [clara.rules.durability :as d]
            [clara.rules.durability.fressian :as df]
            [clojure.data.fressian :as fres]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:import [java.io EOFException]))

;; ---------------------------------------------------------------------------
;; Fressian-based IWorkingMemorySerializer
;; ---------------------------------------------------------------------------

(defrecord FressianFactReader [^java.io.InputStream stream]
  d/IWorkingMemorySerializer
  (serialize-facts [_ _fact-seq]
    (throw (UnsupportedOperationException.
            "FressianFactReader is read-only. Use a separate serializer when saving sessions.")))
  (deserialize-facts [_]
    (let [rdr (fres/create-reader stream :handlers df/read-handler-lookup)
          facts (java.util.ArrayList.)]
      (binding [d/*clj-struct-holder* facts]
        (try
          (loop []
            (let [fact (fres/read-object rdr)]
              (.add facts fact)
              (recur)))
          (catch EOFException _))
        (vec facts)))))

;; ---------------------------------------------------------------------------
;; CLI options
;; ---------------------------------------------------------------------------

(def cli-options
  [["-s" "--session PATH" "Path to serialized Clara session file (required)."]
   ["-a" "--annotations PATH" "Path to an EDN sidecar annotations file."
    :default nil]
   ["-f" "--facts PATH" (str "Path to serialized facts file."
                             "  Defaults to <session-path>.facts when omitted.")
    :default nil]
   ["-p" "--port PORT" "Server port."
    :default 9999
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Port must be between 1 and 65535"]]
   ["-h" "--help" "Print this help."]])

(defn- usage [summary]
  (println "Clara Graph Server — HTTP API for rulebase exploration and session inspection.\n")
  (println "Usage: clojure -M -m clara.server.graph.main [options]\n")
  (println "Options:")
  (println summary)
  (println "\nExample:")
  (println "  clojure -M -m clara.server.graph.main -s session.bin -a annotations.edn")
  (println))

;; ---------------------------------------------------------------------------
;; Deserialization helpers
;; ---------------------------------------------------------------------------

(defn- resolve-facts-path
  "Returns the facts file path.  When --facts is explicitly given, use it.
   Otherwise defaults to <session-path>.facts."
  [session-path facts-opt]
  (or facts-opt
      (str session-path ".facts")))

(defn- file-exists?
  "Returns true if the path is a readable, existing file."
  [path]
  (.exists (io/file path)))

;; ---------------------------------------------------------------------------
;; -main
;; ---------------------------------------------------------------------------

(defn -main
  "Loads a serialized Clara session and starts the explorer server.
   The session is deserialized via clara.rules.durability using the Fressian
   serialization format.

   Required:
     -s, --session PATH   Serialized session file (Fressian).

   Optional:
     -a, --annotations PATH  EDN sidecar annotations file.
     -f, --facts PATH        Serialized facts file (default: <session>.facts).
     -p, --port PORT         Server port (default: 9999).
     -h, --help"
  [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (when (:help options)
      (usage summary)
      (System/exit 0))

    (when (seq errors)
      (doseq [e errors] (println e))
      (println)
      (System/exit 1))

    (let [{:keys [session annotations facts port]} options]
      (when-not session
        (println "Error: --session is required.")
        (usage summary)
        (System/exit 1))

      (when-not (file-exists? session)
        (println (str "Error: session file not found: " session))
        (System/exit 1))

      (let [facts-path (resolve-facts-path session facts)]
        (when-not (file-exists? facts-path)
          (println (str "Error: facts file not found: " facts-path
                        "  (use --facts to specify a different path)"))
          (System/exit 1))

        (println "Loading session from:" session)
        (println "Loading facts from:   " facts-path)

        (when annotations
          (if (file-exists? annotations)
            (println "Loading annotations:" annotations)
            (println (str "Warning: annotations file not found: " annotations))))

        (let [session-stream (io/input-stream session)
              facts-stream (io/input-stream facts-path)
              session-serializer (df/create-session-serializer session-stream)
              mem-serializer (->FressianFactReader facts-stream)
              loaded-session (d/deserialize-session-state session-serializer mem-serializer)]

          (println "Session deserialized. Starting server on port" port "...")

          (server/start!
           (cond-> {:session loaded-session
                    :port port}
             (and annotations (file-exists? annotations))
             (assoc :annotations-file annotations)))

          (println (str "Clara Graph Server running at http://localhost:" port))
          (println "API endpoints at http://localhost:" port "/v1/")
          (println "Press Ctrl+C to stop.")

          ;; Block the main thread to keep the server alive.
          @(promise))))))
