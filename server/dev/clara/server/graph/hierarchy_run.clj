(ns clara.server.graph.hierarchy-run
  "Starts the explorer server over the loan-hierarchy-rules session (keyword
   derive hierarchy, vector-tuple fact types, a record fact type) for e2e
   testing of hierarchy-specific features.

   Distinct from the static demo server (`demo-run`), which serves the
   loan-app-rules session.  The hierarchy session is built in-memory —
   `:fact-type-fn` is required so keyword-meta and vector-tuple fact types
   resolve in the rete network.

   Also loads the match-uniqueness fixture rules and inserts one Config and
   three Items so `pairwise` activates three times — the e2e case for a
   working-memory fact matched under several distinct binding sets."
  (:require [clara.rules :as r]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.rules.loan-hierarchy-rules :as lhr]
            [clara.server.tools.graph.rules.match-uniqueness-test-rules :as mu]
            [clojure.tools.logging :as log]))

(defn -main
  "Usage: clojure -M:hierarchy-run [port]   (default port: 9201)"
  [& args]
  (let [port (or (some-> args first parse-long) 9201)
        session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                                  'clara.server.tools.graph.rules.match-uniqueness-test-rules
                                  :fact-type-fn lhr/fact-type-fn)
                    (r/insert (lhr/map->LoanApplication {:app-id "app-1" :status :new}))
                    ;; One Config × three Items → `pairwise` fires three
                    ;; times, so the Config fact appears in :matches once
                    ;; with three binding sets.
                    (r/insert (mu/->Config "c1"))
                    (r/insert (mu/->Item "a"))
                    (r/insert (mu/->Item "b"))
                    (r/insert (mu/->Item "c"))
                    (r/fire-rules))]
    (server/start! {:session session :port port})
    (log/infof "Hierarchy server running at http://localhost:%s" port)
    (log/info "Press Ctrl+C to stop.")
    @(promise)))
