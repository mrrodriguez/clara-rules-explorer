(ns clara.server.graph.integration-test
  "HTTP integration tests for the explorer server over both canonical
   mk-session combinations:

   - the loan-doc-rules + loan-app-rules session (the session the demo data
     mirrors), and
   - the loan-hierarchy-rules session (keyword derive hierarchy + vector-tuple
     fact types).

   `with-server` / `start-server!` start a server over either session — flip
   with `{:session-fn run-loan-hierarchy-rules :layers []}`.  Tests are named
   after the session they exercise.  This is not the demo itself: the demo
   data is built from the non-hierarchy loan session only."
  (:require [clara.rules :as r]
            [clara.rules.durability :as d]
            [clara.rules.durability.fressian :as df]
            [clara.rules.engine :as eng]
            [clj-http.client :as client]
            [clojure.data.fressian :as fres]
            [clojure.set :as set]
            [clojure.string :as str]
            [jsonista.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clara.server.graph.demo-setup :as demo]
            [clara.server.graph.main :as main]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clara.server.tools.graph.rules.loan-hierarchy-rules :as lhr]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Session builders — the two canonical mk-session combinations
;; ---------------------------------------------------------------------------

;; Default port — 9001 matches the UI dev server's vite proxy
;; (ui/vite.config.ts proxies /v1 → http://localhost:9001), so an
;; interactively started integration server (see the rich comment) lines up
;; with `pnpm dev`.  `with-server` rebinds to a test port for CI isolation.
(def ^:dynamic *port* 9001)

(defn ->url
  [path]
  (format "http://localhost:%s/v1%s" *port* path))

(defn run-app-outcome-approved
  "Approved-app working memory (Application + required/given docs + identity
   + fraud checks) — the loan-doc/app demo mirror."
  [session]
  (-> session
      (r/insert (laf/map->Application {:app-id "app-1"})
                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :paycheck})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :bank-statement})
                (laf/map->IdentityCheck {:app-id "app-1" :status :pass})
                (laf/map->FraudCheck {:app-id "app-1" :status :pass}))
      (r/fire-rules)))

(defn run-loan-app-rules
  "The loan-doc-rules + loan-app-rules session with approved-app working
   memory — the session the demo data mirrors.  Pass `{:with-facts? false}`
   for a bare session (rulebase analysis only, empty working memory)."
  ([{:keys [with-facts?] :or {with-facts? true}}]
   (cond-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                         'clara.server.tools.graph.rules.loan-app-rules)
     with-facts? run-app-outcome-approved))
  ([]
   (run-loan-app-rules {})))

(defn run-loan-hierarchy-rules
  "The loan-hierarchy-rules session (keyword derive hierarchy, vector-tuple
   and record fact types) with a LoanApplication inserted so the rules fire.
   Requires `:fact-type-fn` so tuple types resolve.  Pass
   `{:with-facts? false}` for a bare session."
  ([{:keys [with-facts?] :or {with-facts? true}}]
   (cond-> (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                         :fact-type-fn lhr/fact-type-fn)
     with-facts? (-> (r/insert (lhr/map->LoanApplication {:app-id "app-1" :status :new}))
                     (r/fire-rules))))
  ([]
   (run-loan-hierarchy-rules {})))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(def ^:private loan-doc-annotations-path
  (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
          .getPath))

(defn start-server!
  "Starts an integration server on `*port*` with the session built by
   `session-fn` and returns it (keeps running until `server/stop!`).

   Options:
   - :session-fn  — session builder: `run-loan-app-rules` (default, the
     loan-doc/app session) or `run-loan-hierarchy-rules` (the loan-hierarchy
     session).
   - :session-opts — opts passed to the session builder; `{:with-facts?
     false}` starts with an empty working memory (rulebase analysis only).
   - :layers      — annotation-layer vector; defaults to the loan-doc
     annotation fixture (inert for the hierarchy session, whose types are
     declared in rule :props)."
  [& [opts]]
  (let [{:keys [session-fn session-opts layers]
         :or {session-fn run-loan-app-rules
              session-opts {:with-facts? true}
              layers [loan-doc-annotations-path]}} opts]
    (server/start! {:port *port*
                    :session (session-fn session-opts)
                    :annotations {:source (vec layers)}})))

(defn with-server
  "Runs `f` with a server up (see `start-server!` for the session options),
   stopping it when `f` returns or throws.  Binds `*port*` to 19001 (or the
   `:port` opt) so tests never clash with a dev/demo server on the default
   port."
  [f & [opts]]
  (let [port (or (:port opts) 19001)]
    (binding [*port* port]
      (start-server! opts)
      (try
        (f)
        (finally
          (server/stop!))))))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn get-rules []
  (-> (client/get (->url "/rules") {:accept :json})
      :body
      json/read-value))

(defn get-fact-types []
  (-> (client/get (->url "/fact-types") {:accept :json})
      :body
      json/read-value))

(defn get-session-snapshot []
  (-> (client/get (->url "/session-snapshot") {:accept :json})
      :body
      json/read-value))

(defn get-session-fact-types []
  (-> (client/get (->url "/session/fact-types") {:accept :json})
      :body
      json/read-value))

(defn get-rulebase-summary []
  (-> (client/get (->url "/rulebase-summary") {:accept :json})
      :body
      json/read-value))

(defn get-analysis []
  (-> (client/get (->url "/analysis") {:accept :json})
      :body
      json/read-value))

(defn get-rule [name]
  (let [rules (get (get-rules) "rules")
        entry (first (filter #(= name (get % "name")) rules))
        id (get entry "id")]
    (-> (client/get (->url (str "/rules/" id)) {:accept :json})
        :body
        json/read-value)))

(defn get-queries []
  (-> (client/get (->url "/queries") {:accept :json})
      :body
      json/read-value))

(defn get-query [name]
  (let [queries (get (get-queries) "queries")
        entry (first (filter #(= name (get % "name")) queries))
        id (get entry "id")]
    (-> (client/get (->url (str "/queries/" id)) {:accept :json})
        :body
        json/read-value)))

(defn get-fact-type [name]
  (let [fact-types (get (get-fact-types) "fact-types")
        entry (first (filter #(= name (get % "name")) fact-types))
        id (get entry "id")]
    (-> (client/get (->url (str "/fact-types/" id)) {:accept :json})
        :body
        json/read-value)))

(defn get-session-fact-type [name]
  (let [snapshot (get-session-snapshot)
        id (some (fn [[id n]] (when (= n name) id)) (get snapshot "fact-type-id-index"))]
    (-> (client/get (->url (str "/session/fact-types/" id)) {:accept :json})
        :body
        json/read-value)))

(defn get-session-fact [id]
  (-> (client/get (->url (str "/session/facts/" id)) {:accept :json})
      :body
      json/read-value))

(defn get-session-rule [name]
  (let [snapshot (get-session-snapshot)
        id (some (fn [[id n]] (when (= n name) id)) (get snapshot "rule-id-index"))]
    (-> (client/get (->url (str "/session/rules/" id)) {:accept :json})
        :body
        json/read-value)))

(defn get-session-query [name]
  (let [snapshot (get-session-snapshot)
        id (some (fn [[id n]] (when (= n name) id)) (get snapshot "query-id-index"))]
    (-> (client/get (->url (str "/session/queries/" id)) {:accept :json})
        :body
        json/read-value)))

(defn get-annotations []
  (-> (client/get (->url "/annotations") {:accept :json})
      :body
      json/read-value))

;; ---------------------------------------------------------------------------
;; Tests — loan-doc-rules + loan-app-rules session
;; ---------------------------------------------------------------------------

(deftest test-loan-doc-rulebase-analysis-endpoints
  (with-server
    (fn []
      (testing "Summary and analysis"
        (let [summary (get-rulebase-summary)
              analysis (get-analysis)]
          (is (some? summary))
          (is (some? analysis))))

      (testing "Rules endpoints"
        (let [rules (get-rules)
              rule (get-rule "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")]
          (is (seq rules))
          (is (= "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?" (get rule "name")))))

      (testing "Queries endpoints"
        (let [queries (get-queries)
              query (get-query "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome")]
          (is (seq queries))
          (is (= "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome" (get query "name")))))

      (testing "Fact types endpoints"
        (let [fact-types (get-fact-types)
              fact-type (get-fact-type "clara.server.tools.graph.rules.loan_app_facts.Application")]
          (is (seq fact-types))
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (get fact-type "name"))))))))

(deftest test-loan-doc-session-state-endpoints
  (with-server
    (fn []
      (testing "Session snapshot and facts"
        (let [ss (get-session-snapshot)
              session-fact-types (get-session-fact-types)
              session-fact-type (get-session-fact-type "clara.server.tools.graph.rules.loan_app_facts.Application")]
          (is (some? ss))
          (is (seq session-fact-types))
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (get session-fact-type "name")))

          (testing "Individual fact retrieval"
            (let [fact-id (ffirst (get ss "facts"))
                  fact (get-session-fact fact-id)]
              (is (some? fact))
              (is (= (str fact-id) (str (get fact "id"))))))))

      (testing "Session rules and queries"
        (let [session-rule (get-session-rule "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")
              session-query (get-session-query "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome")]
          (is (some? session-rule))
          (is (some? session-query)))))))

(deftest test-loan-doc-annotations-endpoints
  (with-server
    (fn []
      (testing "Annotations retrieval and in-memory reload"
        (let [annotations (get-annotations)]
          (is (some? annotations)))
        ;; In-memory reload should succeed and return annotations
        (let [result (server/reload-annotations!)]
          (is (map? result)))
        ;; HTTP endpoint reflects the reload
        (let [annotations-after (get-annotations)]
          (is (some? annotations-after)))))))

;; ---------------------------------------------------------------------------
;; Tests — loan-hierarchy-rules session
;; ---------------------------------------------------------------------------

(def ^:private income-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document")

(def ^:private supporting-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document")

(def ^:private loan-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/loan-document")

(def ^:private base-document
  ":clara.server.tools.graph.rules.loan-hierarchy-rules/base-document")

(defn- with-hierarchy-server
  [f]
  (with-server f {:session-fn run-loan-hierarchy-rules :layers []}))

(deftest test-hierarchy-fact-type-ancestors
  (with-hierarchy-server
    (fn []
      (testing "Fact-type detail serves the ancestor chain with honest known flags"
        (let [ft (get-fact-type income-document)
              ancestors (get ft "ancestors")]
          (is (some? ft))
          (is (= [supporting-document loan-document base-document]
                 (mapv #(get % "name") ancestors))
              "Descendant-first hierarchy order (supporting <: loan <: base)")
          (is (= [true true false]
                 (mapv #(get % "known") ancestors))
              "supporting/loan are on an LHS (known); base-document is a ghost (known: false)"))))))

(deftest test-hierarchy-tuple-fact-types
  (with-hierarchy-server
    (fn []
      (testing "Vector-tuple fact types are kind-explicit across the API"
        (let [names (set (map #(get % "name") (get (get-fact-types) "fact-types")))]
          (is (contains? names "[:loan/status \"verified\"]"))
          (is (contains? names "[:document/flag \"income-mismatch\"]")))))))

(deftest test-hierarchy-type-bridge-match
  (with-hierarchy-server
    (fn []
      (testing "The type-bridge :match links insert-income-document → review-supporting-document"
        (let [rule (get-rule (str "clara.server.tools.graph.rules.loan-hierarchy-rules/"
                                  "insert-income-document"))
              downstream (get rule "downstream")
              bridge (first (filter #(= "clara.server.tools.graph.rules.loan-hierarchy-rules/review-supporting-document"
                                        (get % "name"))
                                    downstream))]
          (is (some? bridge) "Downstream edge to review-supporting-document exists")
          (is (= 1 (count (get bridge "match"))))
          (let [match (first (get bridge "match"))]
            (is (= income-document (get-in match ["producer-type" "name"])))
            (is (= supporting-document (get-in match ["consumer-type" "name"]))
                "Hierarchy bridge: producer keyword ≠ consumer keyword, linked via the derive chain")
            (is (true? (get-in match ["producer-type" "known"])))))))))

(deftest test-hierarchy-session-known-flags
  (with-hierarchy-server
    (fn []
      (testing "Session fact-type known flags agree with the analysis end-to-end"
        (let [snapshot (get-session-snapshot)
              income-entry (first (filter (fn [[_id fact]]
                                            (= income-document (get-in fact ["type" "name"])))
                                          (get snapshot "facts")))]
          (is (some? income-entry) "income-document fact is in working memory")
          (is (true? (get-in income-entry [1 "type" "known"]))
              "income-document is an analysis insert-type → session known: true (linkable)")
          (is (nil? (get snapshot "fact-raw-types"))
              "The internal fact-id → raw-type index is stripped from the served snapshot (raw objects do not serialize to JSON)"))))))

;; ---------------------------------------------------------------------------
;; Fressian fact round-trip
;; ---------------------------------------------------------------------------

(deftest ^{:doc "The fix for main/FressianFactReader: d/*clj-struct-holder* must be a
  throwaway list, separate from the facts list.  If they are the same list,
  the Fressian record read handler (create-identity-based-handler)
  double-adds every record via d/clj-struct-holder-add-obj!, shifting all
  MemIdx positions and corrupting working-memory snapshots."}
  test-fressian-fact-reader-no-double-add
  (testing "Fressian fact round-trip preserves correct fact count"
    (let [session (run-loan-app-rules)
          {:keys [memory]} (eng/components session)
          {:keys [indexed-facts]} (d/indexed-session-memory-state memory)
          baos (java.io.ByteArrayOutputStream.)]
      ;; Serialize facts to bytes (same pattern as FressianFactWriter)
      (with-open [w (fres/create-writer baos :handlers df/write-handler-lookup)]
        (binding [d/*clj-struct-holder* (java.util.IdentityHashMap.)]
          (doseq [fact indexed-facts]
            (fres/write-object w fact))))
      ;; Deserialize via FressianFactReader
      (let [bais (java.io.ByteArrayInputStream. (.toByteArray baos))
            reader (main/->FressianFactReader bais)
            deserialized (d/deserialize-facts reader)]
        (is (= (count indexed-facts) (count deserialized))
            (str "Fact count mismatch after Fressian round-trip. "
                 "Expected " (count indexed-facts)
                 " got " (count deserialized)
                 ". Double-add bug in FressianFactReader?"))))))

(deftest ^{:doc "End-to-end: session → serialize → deserialize → snapshot must contain
  only legitimate Clara fact types — no internal Clojure/Java types like
  clojure.lang.PersistentVector or clojure.lang.Symbol."}
  test-fressian-roundtrip-no-garbage-fact-types
  (testing "Session snapshot after Fressian round-trip has no internal Clojure/Java fact types"
    (let [session (run-loan-app-rules)
          ;; Full round-trip: serialize session + facts, then deserialize
          session-baos (java.io.ByteArrayOutputStream.)
          facts-baos (java.io.ByteArrayOutputStream.)
          session-serializer (df/create-session-serializer session-baos)
          facts-serializer (demo/->FressianFactWriter facts-baos)]
      (d/serialize-session-state session session-serializer facts-serializer
                                 {:with-rulebase? true})
      (let [session-in (java.io.ByteArrayInputStream. (.toByteArray session-baos))
            facts-in (java.io.ByteArrayInputStream. (.toByteArray facts-baos))
            session-deser (df/create-session-serializer session-in)
            facts-deser (main/->FressianFactReader facts-in)
            restored (d/deserialize-session-state session-deser facts-deser)
            snapshot (memory/session-snapshot restored)
            type-names (into #{} (map :name) (vals (:fact-types snapshot)))]
        (doseq [tname type-names]
          (is (not (clojure.string/starts-with? tname "clojure.lang."))
              (str "Internal Clojure type leaked into fact types: " tname))
          (is (not (clojure.string/starts-with? tname "java.lang."))
              (str "Internal Java type leaked into fact types: " tname))
          (is (not (clojure.string/starts-with? tname "java.util."))
              (str "Internal Java util type leaked into fact types: " tname)))
        (let [expected #{"clara.server.tools.graph.rules.loan_app_facts.GivenDocument"
                         "clara.server.tools.graph.rules.loan_app_facts.IdentityCheck"
                         "clara.server.tools.graph.rules.loan_app_facts.FraudCheck"
                         "clara.server.tools.graph.rules.loan_app_facts.Application"
                         "clara.server.tools.graph.rules.loan_app_facts.RequiredDocument"
                         "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments"
                         "clara.server.tools.graph.rules.loan_app_facts.AllGivenDocumentsMeta"
                         "clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments"
                         "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"
                         "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome"
                         "clara.server.tools.graph.rules.loan_doc_rules.AllIdCardGivenDocuments"
                         "clara.server.tools.graph.rules.loan_doc_rules.ComplianceReview"
                         "clara.server.tools.graph.rules.loan_doc_rules.AuditTrail"
                         "loan-doc-rules/document-check-input"
                         "extract-doc-meta"}
              present (set/intersection expected type-names)]
          (is (seq present)
              (str "No expected fact types found in snapshot. "
                   "Expected at least some of: " (pr-str expected)
                   " Got: " (pr-str type-names))))))))

(comment
  ;; --- Interactive exploration over either canonical session --------------

  ;; Default: the loan-doc-rules + loan-app-rules session (the demo mirror).
  (start-server!)
  (get-rulebase-summary)
  (get-rule "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")
  (get-analysis)

  ;; Or with an EMPTY working memory — rulebase analysis only, no facts:
  (start-server! {:session-opts {:with-facts? false}})
  (get-fact-types)
  (get-session-snapshot)

  ;; Flip to the loan-hierarchy-rules session (keyword hierarchy + tuples):
  (start-server! {:session-fn run-loan-hierarchy-rules :layers []})
  (get-fact-type income-document)
  (get-session-snapshot)

  ;; Same, with an empty working memory:
  (start-server! {:session-fn run-loan-hierarchy-rules :layers []
                  :session-opts {:with-facts? false}})
  (get-fact-types)

  ;; Stop when done:
  (server/stop!)

  ::done)
