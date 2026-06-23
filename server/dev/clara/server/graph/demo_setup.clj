(ns clara.server.graph.demo-setup
  "Serializes a demo Clara session (loan application rules + working memory)
   to disk for use with clara.server.graph.main."
  (:require [clara.rules :as r]
            [clara.rules.durability :as d]
            [clara.rules.durability.fressian :as df]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.data.fressian :as fres]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Fressian fact writer (mirrors FressianFactReader in main.clj)
;; ---------------------------------------------------------------------------

(defrecord FressianFactWriter [^java.io.OutputStream stream]
  d/IWorkingMemorySerializer
  (serialize-facts [_ fact-seq]
    (let [w (fres/create-writer stream :handlers df/write-handler-lookup)]
      (binding [d/*clj-struct-holder* (java.util.IdentityHashMap.)]
        (doseq [fact fact-seq]
          (fres/write-object w fact)))))
  (deserialize-facts [_]
    (throw (UnsupportedOperationException.
            "FressianFactWriter is write-only. Use FressianFactReader when loading sessions."))))

;; ---------------------------------------------------------------------------
;; Session factory
;; ---------------------------------------------------------------------------

(defn approved-app-working-memory []
  "Working memory for a single approved loan application (app-1)."
  [(laf/map->Application {:app-id "app-1"})
   (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
   (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
   (laf/map->GivenDocument {:app-id "app-1" :doc-type :paycheck})
   (laf/map->GivenDocument {:app-id "app-1" :doc-type :bank-statement})
   (laf/map->IdentityCheck {:app-id "app-1" :status :pass})
   (laf/map->FraudCheck {:app-id "app-1" :status :pass})])

(defn ->demo-session
  "Creates a demo session with loan application rules and approved-app working memory."
  []
  (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                    'clara.server.tools.graph.rules.loan-app-rules)
      (r/insert-all (approved-app-working-memory))
      (r/fire-rules)))

;; ---------------------------------------------------------------------------
;; Serialization
;; ---------------------------------------------------------------------------

(defn serialize-demo!
  "Serializes the demo session to the given directory.  Creates session.bin and
   session.bin.facts files."
  [output-dir]
  (let [session (->demo-session)
        session-path (io/file output-dir "session.bin")
        facts-path (io/file output-dir "session.bin.facts")]
    (io/make-parents session-path)
    (println "Serializing demo session...")
    (with-open [session-out (io/output-stream session-path)
                facts-out (io/output-stream facts-path)]
      (let [session-serializer (df/create-session-serializer session-out)
            facts-serializer (->FressianFactWriter facts-out)]
        (d/serialize-session-state session
                                   session-serializer
                                   facts-serializer
                                   {:with-rulebase? true})))
    (println "Session written to:" (str session-path))
    (println "Facts written to:   " (str facts-path))
    (println "\nRun the server with:")
    (println (str "  clojure -M:demo-run -s " output-dir "/session.bin"))))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Serializes a demo Clara session to disk.
   Usage: clojure -M -m clara.server.graph.demo-setup [output-dir]
   Default output-dir: demo-data"
  [& args]
  (let [output-dir (first args)]
    (serialize-demo! (or output-dir "demo-data"))))
