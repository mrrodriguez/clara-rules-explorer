;; Compose a registry selection into one unit-shaped artifact directory the
;; caller names, so `server/bin/annotations_report.bb` can read it as a normal
;; single unit (see docs/planning/composed-artifact-persist-plan.md).
;;
;; Run from server/:
;;   clojure -M:test -i dev/compose_artifacts.clj \
;;     -e "(compose-artifacts/-main \"{:root \\\"rules-annos\\\" :repo \\\"composed/demo\\\" :units [{:repo \\\"loan-app-ruleset\\\"} {:repo \\\"loan-disposition-ruleset\\\"}] :generated-by \\\"me\\\"}\")"
;;
;; The single argument is either an inline EDN opts map or a path to an .edn
;; file holding one. The function itself is
;; clara.server.tools.graph.artifacts.flow/compose-persist!, so this namespace
;; only reads the argument and reports what was written.

(ns compose-artifacts
  (:require [clara.server.tools.graph.artifacts.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn -main
  [& args]
  (when (empty? args)
    (println "usage: compose-artifacts <edn-opts-map-or-file>")
    (println "  example keys: :root :repo :units :generated-by [:dir] [:branch] [:analysis-run]")
    (System/exit 1))
  (let [raw (str/trim (first args))
        opts (if (str/starts-with? raw "{")
               (edn/read-string raw)
               (edn/read-string (slurp raw)))
        {:keys [dir layers rule-count manifest]} (flow/compose-persist! opts)]
    (println "wrote composed unit to" dir)
    (println "layers:" (pr-str layers))
    (println "rules:" rule-count)
    (println "manifest:" manifest)
    (System/exit 0)))
