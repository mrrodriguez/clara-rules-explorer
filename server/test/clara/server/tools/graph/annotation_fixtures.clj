(ns clara.server.tools.graph.annotation-fixtures
  "Shared access to the loan-doc annotation layer fixture
   (test-resources/.../loan-doc-rules-annotations.edn), regenerated as a
   Layer per docs/anno-merging-update-plan.md phase 6.6."
  (:require [clara.server.tools.graph.annotations :as ann]
            [clojure.java.io :as io]))

(def loan-doc-layer-path
  (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
          .getPath))

(defn loan-doc-merged-annotations
  "Merged annotations for the loan-doc test session: the rule-:props base
   layer plus the generated layer fixture (the same fold the server
   performs)."
  [session]
  (ann/merge-layers [(ann/props-layer session)
                     (ann/read-layer loan-doc-layer-path)]))
