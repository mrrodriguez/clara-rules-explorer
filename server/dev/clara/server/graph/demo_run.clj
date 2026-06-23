(ns clara.server.graph.demo-run
  "Pre-loads demo rule namespaces and delegates to clara.server.graph.main/-main
   with the demo annotations file included."
  (:require [clara.server.tools.graph.rules.loan-app-facts]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clara.server.graph.main :as main]
            [clojure.java.io :as io]))

(defn -main
  [& args]
  (let [args (if (some #{"-p" "--port"} args)
               args
               (concat args ["-p" "9001"]))
        ann-path (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
                         .getPath)]
    (if (some #{"-a" "--annotations"} args)
      (apply main/-main args)
      (apply main/-main (concat args ["-a" ann-path])))))
