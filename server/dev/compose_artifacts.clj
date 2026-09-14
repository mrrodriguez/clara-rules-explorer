;; Compose a registry selection into one unit-shaped artifact directory the
;; caller names, so `server/bin/annotations_report.bb` can read it as a normal
;; single unit (see docs/planning/composed-artifact-persist-plan.md).
;;
;; Run from server/:
;;   clojure -M:test -i dev/compose_artifacts.clj \
;;     -e '(compose-artifacts/-main {:root "rules-annos" :repo "composed/demo" :units [{:repo "loan-app-ruleset"} {:repo "loan-disposition-ruleset"}] :generated-by "me"})'
;;
;; The single argument is an opts map for `flow/compose-persist!`, passed either
;; as a literal form (as above) or as a string holding an inline EDN map or the
;; path to an .edn file. Single-quoting the -e expression lets the shell hand
;; the map through untouched, so the map itself needs no escaping. The function
;; itself is clara.server.tools.graph.artifacts.flow/compose-persist!, so this
;; namespace only reads the argument and reports what was written.

(ns compose-artifacts
  (:require [clara.server.tools.graph.artifacts.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- read-opts
  "Coerce a `-main` argument into the opts map `flow/compose-persist!` expects.
  Accepts an already-read map, an inline EDN string, or a path to an .edn file
  holding one."
  [arg]
  (cond
    (map? arg) arg
    (string? arg) (let [raw (str/trim arg)]
                    (if (str/starts-with? raw "{")
                      (edn/read-string raw)
                      (edn/read-string (slurp raw))))
    :else (throw (ex-info "compose-artifacts: expected an EDN opts map, an EDN string, or a file path"
                          {:arg arg}))))

(defn -main
  "Compose the opts map's registry selection into one unit-shaped artifact
  directory. The single argument is an opts map for
  `clara.server.tools.graph.artifacts.flow/compose-persist!` — a literal map
  (what the `-e` recipe in this file passes), an inline EDN string, or a path to
  an .edn file holding one."
  [& args]
  (when (empty? args)
    (println "usage: compose-artifacts <edn-opts-map | edn-string | edn-file>")
    (println "  keys: :root :repo :units :generated-by [:dir] [:branch] [:analysis-run]")
    (System/exit 1))
  (let [{:keys [dir layers rule-count manifest]}
        (flow/compose-persist! (read-opts (first args)))]
    (println "wrote composed unit to" dir)
    (println "layers:" (pr-str layers))
    (println "rules:" rule-count)
    (println "manifest:" manifest)
    (System/exit 0)))
