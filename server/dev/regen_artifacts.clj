;; Regenerates the checked-in persistence example under
;; test-resources/rules-annos/ (see server/docs/persisted-artifacts.md).
;;
;; Run from server/:
;;   make regen-artifacts
;;
;; The example is the full artifact set `clara.server.tools.graph.artifacts.*`
;; produces from one session: the loan-doc-rules + loan-app-rules +
;; loan-doc-queries session with approved-app working memory — the same session
;; `clara.server.graph.integration-test/run-loan-app-rules` builds, and the
;; session the demo mirrors.
;;
;; The generated layer resolves the `helpers/->fact` boundary hop exactly as
;; `dev/regen_fixture.clj` does. The opaque builders (`build-compliance-review`,
;; `build-compliance-via-metadata`, `build-audit-trail-entry`) and the
;; var-as-fact pattern are deliberately left to the working-memory layer, which
;; is what a fired session proves that static analysis cannot.
;;
;; Byte-stable: the target directory is deleted first so the provenance
;; manifest's `:history` is a single fresh entry and the whole set is a pure
;; function of the session + rules on disk.

(ns regen-artifacts
  (:require [clara.rules :as r]
            [clara.server.tools.graph.artifacts.flow :as flow]
            [clara.server.tools.graph.artifacts.manifest :as manifest]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-queries]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def ^:private out-dir
  "Where the checked-in example lives."
  "test-resources/rules-annos")

(def ^:private artifact-opts
  "Shared persistence opts: an explicit `:dir` (there is no repo subdir to name),
  a `:repo` for the provenance manifest, and the provenance claim every layer
  and the manifest carry."
  {:dir out-dir
   :repo "loan-app-rules"
   :generated-by "clara-rules-explorer"})

;; ---------------------------------------------------------------------------
;; The session — mirrors clara.server.graph.integration-test/run-loan-app-rules
;; ---------------------------------------------------------------------------

(defn- run-app-outcome-approved
  "Approved-app working memory (Application + required/given docs + identity
   + fraud checks + the doc-check-count-min threshold) — the loan-doc/app demo
   mirror."
  [session]
  (-> session
      (r/insert (laf/map->Application {:app-id "app-1"})
                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :paycheck})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :bank-statement})
                (laf/map->IdentityCheck {:app-id "app-1" :status :pass})
                (laf/map->FraudCheck {:app-id "app-1" :status :pass})
                (with-meta {:value 1}
                  {:type :doc-check-count-min}))
      (r/fire-rules)))

(defn- ->session []
  (run-app-outcome-approved
   (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                 'clara.server.tools.graph.rules.loan-app-rules
                 'clara.server.tools.graph.rules.loan-doc-queries)))

;; ---------------------------------------------------------------------------
;; Fact-constructor of interest — the `helpers/->fact` boundary hop, the same
;; spec dev/regen_fixture.clj resolves for the demo sidecar.
;; ---------------------------------------------------------------------------

(def ^:private helpers->fact-sym
  'clara.server.tools.graph.rules.helpers/->fact)

(def ^:private ->fact-spec
  {:match-fn (fn [sym] (= helpers->fact-sym sym))
   :type-resolver-fn (fn [{:keys [arg-form]}]
                       (when (and (seq? arg-form)
                                  (= 3 (count arg-form)))
                         {:resolved-types [(second arg-form)]}))})

;; ---------------------------------------------------------------------------
;; Generation
;; ---------------------------------------------------------------------------

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn -main
  [& _]
  (delete-tree! out-dir)
  (let [session (->session)
        generated (flow/generate {:session session
                                  :generated-by (:generated-by artifact-opts)
                                  :fact-constructors [->fact-spec]})
        memory-layer (flow/->memory-layer {:session session
                                           :annotations (:annotations (:layer generated))
                                           :generated-by (:generated-by artifact-opts)})
        dir (flow/persist! (assoc generated :memory-layer memory-layer)
                           (assoc artifact-opts :session session))
        manifest-file (manifest/write-manifest!
                       (assoc artifact-opts
                              :session session
                              :analysis-run
                              {:session-build
                               (str "clara.server.graph.integration-test/run-loan-app-rules"
                                    " (loan-doc-rules + loan-app-rules + loan-doc-queries,"
                                    " approved-app working memory)")
                               :scope "demo loan application"}))]
    (println "wrote artifact set to" dir)
    (println "generated layer:" (count (:annotations (:layer generated))) "rules")
    (println "memory layer:"
             (if memory-layer (count (:annotations memory-layer)) 0) "rules")
    (println "manifest:" manifest-file)))
