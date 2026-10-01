;; Regenerates the checked-in persistence examples under `example/example-out-dir`
;; (see explorer/docs/persisted-artifacts.md).
;;
;; Run from explorer/:
;;   make regen-artifacts
;;
;; The generation itself lives in
;; clara.explorer.artifacts.regen-example — the same function the
;; golden test (clara.explorer.artifacts.regen-example-test) uses, so
;; the checked-in examples and what the test asserts cannot drift apart. This
;; namespace only deletes the target dir first (so each manifest's :history is a
;; single fresh entry and the whole registry is a pure function of the sessions
;; + rules on disk) and reports what was written.

(ns clara.explorer.dev.regen-artifacts
  (:require [clara.explorer.artifacts.layout :as layout]
            [clara.explorer.artifacts.regen-example :as example]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn -main
  [& _]
  (delete-tree! example/example-out-dir)
  (let [{:keys [dir rulesets variant composed]} (example/generate-example-artifacts! example/example-out-dir)]
    (println "wrote artifact registry to" dir)
    (doseq [{:keys [repo generated-rule-count memory-rule-count manifest]} rulesets]
      (println repo "generated layer:" generated-rule-count "rules")
      (println repo "memory layer:" memory-rule-count "rules")
      (println repo "manifest:" manifest))
    (println (:repo variant) "@" (layout/variant-path (:variant variant)) "variant:" (:manifest variant))
    (println (:repo composed) "composed:" (:rule-count composed) "rules")
    (println (:repo composed) "manifest:" (:manifest composed))
    (System/exit 0)))
