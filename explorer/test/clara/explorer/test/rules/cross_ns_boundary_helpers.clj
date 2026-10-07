(ns clara.explorer.test.rules.cross-ns-boundary-helpers
  "Dependency namespace for the cross-namespace boundary-traversal test: the
   boundary call (`insert!`) lives here, in a namespace other than the rule's."
  (:require [clara.rules :as r]
            [clara.explorer.test.rules.helpers :as helpers]))

(defn insert-cross-ns-fact!
  "Inserts a fact directly, so a rule reaching the boundary must traverse into
   this dependency namespace's call graph."
  [id]
  (r/insert! (helpers/->fact :cross-ns-boundary/out {:id id})))
