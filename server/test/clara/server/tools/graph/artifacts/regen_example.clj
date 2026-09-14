(ns clara.server.tools.graph.artifacts.regen-example
  "The one definition of how the checked-in persistence examples under
   `example-out-dir` are generated.

   Both the regeneration entry point (`regen-artifacts/-main`, reachable as
   `make regen-artifacts`) and the golden test
   (`clara.server.tools.graph.artifacts.regen-example-test`) call
   `generate-example-artifacts!`, so the examples on disk and what the test
   asserts can never drift apart: the test regenerates into a temp dir and
   compares it against the checked-in copy byte-for-byte.

   `example-out-dir` is the registry root; each entry in `example-rulesets` is
   one ruleset bundle under it. The two checked-in rulesets are:

   - `loan-app-ruleset` — loan-doc-rules + loan-app-rules + loan-doc-queries
     with approved-app working memory, the session the demo mirrors.
   - `loan-disposition-ruleset` — the single-ns
     `clara.server.tools.graph.rules.loan-outcome-notices` session, unfired, so
     its persisted analysis records the downstream consume/produce contract
     without any upstream rules (or their facts) loaded and without a
     memory-derived layer.

   The loan-app session builder is inlined here rather than required from
   `clara.server.graph.integration-test`, because `make regen-artifacts` runs on
   the `:test` alias alone and the integration-test namespace drags in `dev/`
   dependencies. The generated layer resolves the
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
   [clara.server.tools.graph.rules.loan-doc-rules]
   [clara.server.tools.graph.rules.loan-outcome-notices]
   [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def example-generated-by
  "The `:generated-by` claim every layer and the provenance manifest carry."
  "clara-rules-explorer")

(def example-registry-base
  "The checked-in registry root, relative to `server/test-resources/`."
  "rules-annos")

(def example-out-dir
  (format "test-resources/%s" example-registry-base))

;; ---------------------------------------------------------------------------
;; Sessions
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

(defn- ->loan-app-session []
  (run-app-outcome-approved
   (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                 'clara.server.tools.graph.rules.loan-app-rules
                 'clara.server.tools.graph.rules.loan-doc-queries)))

(defn- ->loan-disposition-session
  "The downstream ruleset alone, unfired: its facts are produced by another
   ruleset (`loan-app-ruleset`), which this bundle deliberately knows nothing
   about yet."
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-outcome-notices))

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

;; ---------------------------------------------------------------------------
;; The checked-in rulesets
;; ---------------------------------------------------------------------------

(def loan-app-ruleset
  "The loan-doc/app ruleset bundle spec."
  {:repo "loan-app-ruleset"
   :session-fn ->loan-app-session
   :fact-constructors [->fact-spec]
   :analysis-run
   {:session-build
    (str "clara.server.graph.integration-test/run-loan-app-rules"
         " (loan-doc-rules + loan-app-rules + loan-doc-queries,"
         " approved-app working memory)")
    :scope "demo loan application"}})

(def loan-disposition-ruleset
  "The single-ns downstream disposition ruleset bundle spec. Its session is
   intentionally unfired, so the persisted set has no memory-derived layer —
   the facts it consumes come from another ruleset it does not know about yet."
  {:repo "loan-disposition-ruleset"
   :session-fn ->loan-disposition-session
   :analysis-run
   {:session-build
    (str "clara.server.tools.graph.rules.loan-outcome-notices"
         " (single-ns session, unfired)")
    :scope "downstream loan disposition notices"}})

(def example-rulesets
  "The checked-in example rulesets, in generation order."
  [loan-app-ruleset loan-disposition-ruleset])

;; ---------------------------------------------------------------------------
;; Generation
;; ---------------------------------------------------------------------------

(defn- persist-ruleset!
  "Generate and persist one ruleset bundle under the registry root `root`."
  [root {:keys [repo session-fn fact-constructors analysis-run]}]
  (let [session (session-fn)
        dir (str (io/file root repo))
        opts {:dir dir
              :repo repo
              :generated-by example-generated-by}
        generated (flow/generate
                   (cond-> {:session session
                            :generated-by example-generated-by}
                     (seq fact-constructors) (assoc :fact-constructors fact-constructors)))
        memory-layer (flow/->memory-layer
                      {:session session
                       :annotations (:annotations (:layer generated))
                       :generated-by example-generated-by})
        _ (flow/persist! (cond-> generated
                           memory-layer (assoc :memory-layer memory-layer))
                         (assoc opts :session session))
        manifest-file (manifest/write-manifest!
                       (assoc opts
                              :session session
                              :analysis-run analysis-run))]
    {:repo repo
     :dir dir
     :generated-rule-count (count (:annotations (:layer generated)))
     :memory-rule-count (if memory-layer (count (:annotations memory-layer)) 0)
     :manifest manifest-file}))

(defn generate-example-artifacts!
  "Generate the full checked-in artifact registry — every ruleset in
   `example-rulesets` — under `dir`, and return a summary map.

   `dir` is the registry root; each ruleset is written to its own
   `<dir>/<repo>` subdirectory. `dir` is created if absent and is NOT cleared
   first: a caller regenerating the checked-in copy deletes it beforehand (see
   `regen-artifacts/-main`), while a caller testing writes to a fresh temp dir.

   Every artifact except the manifests is a pure function of the sessions +
   rules, so two runs into equal directories are byte-identical. The manifests
   additionally record run dates and git state (see
   `clara.server.tools.graph.artifacts.manifest/get-git-info`), which are
   environment, not generation."
  [dir]
  {:dir dir
   :rulesets (mapv #(persist-ruleset! dir %) example-rulesets)})
