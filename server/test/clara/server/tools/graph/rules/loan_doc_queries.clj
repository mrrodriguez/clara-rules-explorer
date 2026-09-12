(ns clara.server.tools.graph.rules.loan-doc-queries
  "Query-only rule namespace for the loan-document theme.  Exists so tests
   can prove `:ns-deps` covers production-owning namespaces beyond the
   rules' own namespace — a query in a namespace with no rules."
  (:require [clara.rules :as r])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            DocumentCheck]))

(r/defquery find-document-checks
  "Document checks recorded for an application."
  [?app-id]
  [?check <- DocumentCheck (= ?app-id app-id)])
