(ns clara.server.graph.hierarchy-run
  "Starts the explorer server over the loan-hierarchy-rules session (keyword
   derive hierarchy, vector-tuple fact types, a record fact type) for e2e
   testing of hierarchy-specific features.

   Distinct from the static demo server (`demo-run`), which serves the
   loan-app-rules session.  The hierarchy session is built in-memory —
   `:fact-type-fn` is required so keyword-meta and vector-tuple fact types
   resolve in the rete network."
  (:require [clara.rules :as r]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.rules.loan-hierarchy-rules :as lhr]))

(defn -main
  "Usage: clojure -M:hierarchy-run [port]   (default port: 9201)"
  [& args]
  (let [port (or (some-> args first parse-long) 9201)
        session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                                  :fact-type-fn lhr/fact-type-fn)
                    (r/insert (lhr/map->LoanApplication {:app-id "app-1" :status :new}))
                    (r/fire-rules))]
    (server/start! {:session session :port port})
    (println (format "Hierarchy server running at http://localhost:%s" port))
    (println "Press Ctrl+C to stop.")
    @(promise)))
