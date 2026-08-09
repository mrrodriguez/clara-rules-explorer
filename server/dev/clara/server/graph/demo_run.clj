(ns clara.server.graph.demo-run
  "Pre-loads demo rule namespaces and delegates to clara.server.graph.main/-main
   with the demo annotations file included and WM enrichment enabled by default."
  (:require [clara.server.tools.graph.rules.loan-app-facts]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clara.server.graph.main :as main]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- flag-present?
  "True when args contain FLAG as a separate token or in --flag=value form."
  [args & flags]
  (some (fn [a]
          (some #(or (= a %) (str/starts-with? a (str % "="))) flags))
        args))

(defn -main
  [& args]
  (let [args (if (flag-present? args "-p" "--port")
               args
               (concat args ["-p" "9001"]))
        args (if (flag-present? args "--annotations")
               args
               (concat args ["--annotations" "{:enrichment :auto-detect}"]))
        ann-path (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
                         .getPath)]
    (if (flag-present? args "-l" "--layer")
      (apply main/-main args)
      (apply main/-main (concat args ["-l" ann-path])))))
