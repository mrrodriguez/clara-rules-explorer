(ns clara.server.tools.graph.artifacts.regen-example
  "The one definition of how the checked-in persistence example under
   `example-out-dir` is generated.

   Both the regeneration entry point (`regen-artifacts/-main`, reachable as
   `make regen-artifacts`) and the golden test
   (`clara.server.tools.graph.artifacts.regen-example-test`) call
   `generate-example-artifacts!`, so the example on disk and what the test
   asserts can never drift apart: the test regenerates into a temp dir and
   compares it against the checked-in copy byte-for-byte.

   The session mirrors `clara.server.graph.integration-test/run-loan-app-rules`
   — loan-doc-rules + loan-app-rules + loan-doc-queries with approved-app
   working memory, the session the demo mirrors. The builder is inlined here
   rather than required from that namespace, because `make regen-artifacts`
   runs on the `:test` alias alone and the integration-test namespace drags in
   `dev/` dependencies. The generated layer resolves the
   `clara.server.tools.graph.rules.helpers/->fact` boundary hop via the
   `->fact-spec` fact-constructor; the opaque builders and the var-as-fact
   pattern are deliberately left to the working-memory layer, which is what a
   fired session proves that static analysis cannot."
  (:require
   [clara.rules :as r]
   [clara.server.tools.graph.artifacts.flow :as flow]
   [clara.server.tools.graph.artifacts.manifest :as manifest]
   [clara.server.tools.graph.rules.loan-app-facts :as laf]
   [clara.server.tools.graph.rules.loan-app-rules]
   [clara.server.tools.graph.rules.loan-doc-queries]
   [clara.server.tools.graph.rules.loan-doc-rules]))

(set! *warn-on-reflection* true)

(def example-repo
  "The `:repo` the checked-in example's provenance manifest claims."
  "loan-app-ruleset")

(def example-generated-by
  "The `:generated-by` claim every layer and the provenance manifest carry."
  "clara-rules-explorer")

(def example-resource-base
  "rules-annos/loan-app-ruleset")

(def example-out-dir
  (format "test-resources/%s" example-resource-base))

;; ---------------------------------------------------------------------------
;; The session — mirrors `clara.server.graph.integration-test/run-loan-app-rules`
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
;; Fact-constructor of interest
;; ---------------------------------------------------------------------------

(def ^:private helpers->fact-sym
  'clara.server.tools.graph.rules.helpers/->fact)

(def ->fact-spec
  "The fact-constructor spec resolving the
   `clara.server.tools.graph.rules.helpers/->fact` boundary hop —
   `(->fact <type> <data>)` — to its first argument. The same resolution the
   demo annotation sidecar generator (`make regen-fixture`) uses."
  {:match-fn (fn [sym] (= helpers->fact-sym sym))
   :type-resolver-fn (fn [{:keys [arg-form]}]
                       (when (and (seq? arg-form)
                                  (= 3 (count arg-form)))
                         {:resolved-types [(second arg-form)]}))})

(defn generate-example-artifacts!
  "Generate the full checked-in artifact set — the two layers, merged
   annotations, the mutli-part analysis files, the digest, and the provenance
   manifest — into `dir`, and return a summary map.

   `dir` is created if absent and is NOT cleared first: a caller regenerating the
   checked-in copy deletes it beforehand (see `regen-artifacts/-main`), while a
   caller testing writes to a fresh temp dir.

   Every artifact except the manifest is a pure function of the session + rules,
   so two runs into equal directories are byte-identical. The manifest
   additionally records run dates and git state (see
   `clara.server.tools.graph.artifacts.manifest/get-git-info`), which are
   environment, not generation."
  [dir]
  (let [session (->session)
        opts {:dir dir
              :repo example-repo
              :generated-by example-generated-by}
        generated (flow/generate {:session session
                                  :generated-by example-generated-by
                                  :fact-constructors [->fact-spec]})
        memory-layer (flow/->memory-layer {:session session
                                           :annotations (:annotations (:layer generated))
                                           :generated-by example-generated-by})
        _ (flow/persist! (assoc generated :memory-layer memory-layer)
                         (assoc opts :session session))
        manifest-file (manifest/write-manifest!
                       (assoc opts
                              :session session
                              :analysis-run
                              {:session-build
                               (str "clara.server.graph.integration-test/run-loan-app-rules"
                                    " (loan-doc-rules + loan-app-rules + loan-doc-queries,"
                                    " approved-app working memory)")
                               :scope "demo loan application"}))]
    {:dir dir
     :generated-rule-count (count (:annotations (:layer generated)))
     :memory-rule-count (if memory-layer (count (:annotations memory-layer)) 0)
     :manifest manifest-file}))
