(ns clara.server.graph.main
  "CLI entry point for the Clara Graph Server.
   Loads a serialized Clara session and starts the explorer HTTP API."
  (:require [clara.server.graph.server :as server]
            [clara.rules.durability :as d]
            [clara.rules.durability.fressian :as df]
            [clojure.data.fressian :as fres]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.annotations :as annotations]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.serialize :as serialize]
            [clojure.pprint :as pprint])
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
  [["-s" "--session PATH" "Path to serialized Clara session file."]
   ["-a" "--annotations PATH" "Path to an EDN sidecar annotations file."
    :default nil]
   ["-f" "--facts PATH" (str "Path to serialized facts file."
                             "  Defaults to <session-path>.facts when omitted.")
    :default nil]
   ["-p" "--port PORT" "Server port."
    :default 9999
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Port must be between 1 and 65535"]]
   ["-g" "--generate-annotations PATHS" "Generate annotations EDN for Clojure source paths (comma-separated)."
    :parse-fn #(str/split % #",")]
   [nil "--generate-analysis DIR" "Generate annotations and analysis EDN files to the specified output directory."
    :id :generate-analysis-dir]
   [nil "--load-session-state-fn SYMBOL" "Symbol naming a function to load the session state."
    :parse-fn symbol]
   ["-h" "--help" "Print this help."]])

(defn- usage [summary]
  (println "Clara Graph Server — HTTP API for rulebase exploration and session inspection.\n")
  (println "Usage: clojure -M -m clara.server.graph.main [options]\n")
  (println "Options:")
  (println summary)
  (println "\nExamples:")
  (println "  clojure -M -m clara.server.graph.main -s session.bin -a annotations.edn")
  (println "  clojure -M -m clara.server.graph.main -g src/my_rules.clj,src/other_rules.clj")
  (println "  clojure -M -m clara.server.graph.main --generate-analysis out -s session.bin -g src/my_rules.clj")
  (println))
(defn- exit [code]
  (System/exit code))

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

(defn- run-generate-annotations [generate-annotations]
  (let [res (analyze/generate-annotations-from-paths {:paths generate-annotations})]
    (pprint/pprint res)))

(defn- validate-server-options [options]
  (let [{:keys [session facts load-session-state-fn]} options]
    (cond
      (not session)
      {:error "Error: Either --session or --generate-annotations is required."
       :show-usage? true}

      (not (file-exists? session))
      {:error (format "Error: session file not found: %s" session)}

      :else
      (let [facts-path (resolve-facts-path session facts)]
        (if (and (not load-session-state-fn)
                 (not (file-exists? facts-path)))
          {:error (format "Error: facts file not found: %s  (use --facts to specify a different path)" facts-path)}
          {:facts-path facts-path})))))

(defn- resolve-fn [x]
  (cond
    (symbol? x) (let [resolved (try
                                 (requiring-resolve x)
                                 (catch Exception _
                                   nil))]
                  (if resolved
                    resolved
                    (throw (IllegalArgumentException. (format "Could not resolve function: %s" x)))))
    (ifn? x) x
    :else (throw (IllegalArgumentException. (format "Invalid :load-session-state-fn: %s" x)))))

(defn load-session-state
  "Loads the session state using the provided load-fn, or the default fressian deserializer if load-fn is not provided.
   load-fn can be a symbol or an IFn."
  ([session-path facts-path]
   (load-session-state session-path facts-path nil))
  ([session-path facts-path load-fn]
   (if load-fn
     (let [f (resolve-fn load-fn)]
       (f session-path facts-path))
     (with-open [session-stream (io/input-stream session-path)
                 facts-stream (io/input-stream facts-path)]
       (let [session-serializer (df/create-session-serializer session-stream)
             mem-serializer (->FressianFactReader facts-stream)]
         (d/deserialize-session-state session-serializer mem-serializer))))))

(defn- run-generate-analysis
  "Generates annotations and static analysis artifacts, writing them to the
   specified output directory.

   Annotations are either generated from explicit -g source paths or
   auto-discovered from session namespaces via clj-kondo."
  [{:keys [session facts load-session-state-fn generate-annotations generate-analysis-dir]}]
  (when-not session
    (println "Error: --session is required with --generate-analysis")
    (exit 1))
  (when-not (file-exists? session)
    (println (format "Error: session file not found: %s" session))
    (exit 1))

  (let [facts-path (resolve-facts-path session facts)]
    (when (and (not load-session-state-fn)
               (not (file-exists? facts-path)))
      (println (format "Error: facts file not found: %s  (use --facts to specify a different path)"
                       facts-path))
      (exit 1))

    (println (format "Loading session from: %s" session))
    (let [loaded-session (load-session-state session facts-path load-session-state-fn)]
      (println "Session loaded.")

      (let [annotations
            (if generate-annotations
              (do
                (println (format "Generating annotations from source paths: %s"
                                 (str/join ", " generate-annotations)))
                (analyze/generate-annotations-from-paths {:paths generate-annotations}))
              (do
                (println "Auto-discovering annotations from session namespaces...")
                (let [analysis (analyze/analyze-session-rules
                                {:session-or-rulebase loaded-session})]
                  (analyze/generate-annotations-from-analysis {:analysis analysis}))))

            _ (println "Running rulebase analysis...")
            analysis (core/rulebase-analysis loaded-session annotations)

            _ (.mkdirs (io/file generate-analysis-dir))

            annotations-path (str generate-analysis-dir "/annotations.edn")
            analysis-path (str generate-analysis-dir "/analysis.edn")]

        (annotations/write-annotations! annotations-path annotations)
        (println (format "Annotations written to: %s" annotations-path))

        (spit analysis-path
              (with-out-str
                (pprint/pprint analysis)))
        (println (format "Analysis written to: %s" analysis-path))))))

(defn run-explorer-server
  "Starts the explorer server with the given options."
  [options facts-path]
  (let [{:keys [session annotations port load-session-state-fn]} options]
    (println (format "Loading session from: %s" session))
    (when (or (not load-session-state-fn) (file-exists? facts-path))
      (println (format "Loading facts from:   %s" facts-path)))
    (when annotations
      (if (file-exists? annotations)
        (println (format "Loading annotations: %s" annotations))
        (println (format "Warning: annotations file not found: %s" annotations))))
    (let [loaded-session (load-session-state session facts-path load-session-state-fn)]
      (println (format "Session deserialized. Starting server on port %s ..." port))
      (server/start!
       (cond-> {:session loaded-session
                :port port}
         (and annotations (file-exists? annotations))
         (assoc :annotations-file annotations)))
      (println (format "Clara Graph Server running at http://localhost:%s" port))
      (println (format "API endpoints at http://localhost:%s/v1/" port))
      (println "Press Ctrl+C to stop."))))

;; ---------------------------------------------------------------------------
;; -main
;; ---------------------------------------------------------------------------

(defn -main
  "Loads a serialized Clara session and starts the explorer server, generates
   rule annotations directly from source files, or generates a static analysis
   dump with annotations.

   Required (at least one):
     -s, --session PATH              Serialized session file (Fressian) to run server.
     -g, --generate-annotations PATHS Clojure source paths (comma-separated) to print annotations.
     --generate-analysis DIR         Output directory for annotations and analysis EDN files.

   Optional:
     -a, --annotations PATH  EDN sidecar annotations file.
     -f, --facts PATH        Serialized facts file (default: <session>.facts).
     -p, --port PORT         Server port (default: 9999).
     --load-session-state-fn SYMBOL  Symbol naming a function to load the session state.
     -h, --help"
  [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (when (:help options)
      (usage summary)
      (exit 0))

    (when (seq errors)
      (doseq [e errors] (println e))
      (println)
      (exit 1))

    (let [{:keys [generate-annotations generate-analysis-dir]} options]
      (cond
        generate-analysis-dir
        (run-generate-analysis options)

        generate-annotations
        (do
          (run-generate-annotations generate-annotations)
          (exit 0))

        :else
        (let [validation (validate-server-options options)]
          (if-let [error (:error validation)]
            (do
              (println error)
              (when (:show-usage? validation)
                (usage summary))
              (exit 1))
            (try
              (run-explorer-server options (:facts-path validation))
              ;; Block the main thread to keep the server alive.
              @(promise)
              (catch Throwable t
                (println (str "Error: " (.getMessage t)))
                (exit 1)))))))))
