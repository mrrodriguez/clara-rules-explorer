;; Regenerates the checked-in persistence example under `example/example-out-dir`
;; (see server/docs/persisted-artifacts.md).
;;
;; Run from server/:
;;   make regen-artifacts
;;
;; The generation itself lives in
;; clara.server.tools.graph.artifacts.regen-example — the same function the
;; golden test (clara.server.tools.graph.artifacts.regen-example-test) uses, so
;; the checked-in example and what the test asserts cannot drift apart. This
;; namespace only deletes the target dir first (so the manifest's :history is a
;; single fresh entry and the whole set is a pure function of the session +
;; rules on disk) and reports what was written.

(ns regen-artifacts
  (:require [clara.server.tools.graph.artifacts.regen-example :as example]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn -main
  [& _]
  (delete-tree! example/example-out-dir)
  (let [{:keys [dir generated-rule-count memory-rule-count manifest]}
        (example/generate-example-artifacts! example/example-out-dir)]
    (println "wrote artifact set to" dir)
    (println "generated layer:" generated-rule-count "rules")
    (println "memory layer:" memory-rule-count "rules")
    (println "manifest:" manifest)
    (System/exit 0)))
