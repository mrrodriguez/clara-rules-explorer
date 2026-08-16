(ns clara.server.tools.graph.rules.loan-doc-rules
  (:require [clara.rules :as r]
            [clara.server.tools.graph.rules.helpers :as h]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.rules.accumulators :as acc])
  (:import
   [clara.server.tools.graph.rules.loan_app_facts
    Application
    GivenDocument
    AllGivenDocuments
    RequiredDocument
    AllRequiredDocuments
    DocumentCheck]))

(def count-atom (atom 0))

(defn ->document-check-input [data]
  (h/->fact :loan-doc-rules/document-check-input data))

(defrecord AllIdCardGivenDocuments [app-id docs])

;; ---------------------------------------------------------------------------
;; Loan-processing fact types for dynamic-rule demo
;; These fit the loan theme but are NOT consumed by other rules, so they
;; don't interfere with existing behavioral tests.
;; ---------------------------------------------------------------------------

(defrecord ComplianceReview [app-id status reviewer])
(defrecord StaleDocumentNotice [app-id doc-type reason])
(defrecord AuditTrail [app-id action timestamp])

;; ---------------------------------------------------------------------------
;; Using custom macros that emit rules with var referenced functions as facts.
;; ---------------------------------------------------------------------------

(h/def-fact-fn extract-doc-meta :extract-doc-meta
  [doc-fact]
  (let [doc-meta (-> doc-fact
                     meta
                     not-empty
                     (or {::no-doc-meta true}))]
    (with-meta {:doc-meta doc-meta}
      {:type :extracted-doc-meta})))

;; ---------------------------------------------------------------------------
;; Using a "function as a fact" pattern in the RHS.
;; ---------------------------------------------------------------------------

(r/defrule collect-doc-meta
  [Application (= ?app-id app-id)]
  [?docs <- (acc/all) :from [GivenDocument (= ?app-id app-id)]]
  [?extract-doc-meta <- :extract-doc-meta]
  =>
  (let [doc-metas (mapv ?extract-doc-meta ?docs)]
    (r/insert! (laf/map->AllGivenDocumentsMeta {:app-id ?app-id :doc-metas doc-metas}))))

;; ---------------------------------------------------------------------------
;; Dynamic helper functions — demonstrate callsite capture
;; ---------------------------------------------------------------------------

(defn build-compliance-review
  "Builds a ComplianceReview via Java constructor.
   The analyzer cannot trace through a Java ctor, so this produces
   a dynamic callsite that requires sidecar annotation to resolve."
  [app-id]
  (ComplianceReview. app-id :pass "automated-review"))

(defn build-compliance-via-metadata
  "Builds a ComplianceReview via metadata-map style.
   The fact type is embedded in metadata — unresolvable statically."
  [app-id]
  (with-meta {:app-id app-id :status :pass :reviewer "metadata-review"}
    {:type :compliance-review-result}))

(defn build-audit-trail-entry
  "Builds an AuditTrail via an opaque builder function.
   The analyzer knows an insert happened but cannot determine the fact type
   because the constructor is a custom fn with no recognisable pattern."
  [app-id action]
  (AuditTrail. app-id action (System/currentTimeMillis)))

(r/defrule collect-app-id-card-given-docs
  [Application (= ?app-id app-id)]
  [?docs <- (acc/all) :from [GivenDocument (= ?app-id app-id)
                             (= doc-type :id-card)]]
  =>
  (r/insert! (map->AllIdCardGivenDocuments {:app-id ?app-id :docs ?docs})))

(r/defrule collect-app-given-docs
  [Application (= ?app-id app-id)]
  [?docs <- (acc/all) :from [GivenDocument (= ?app-id app-id)]]
  =>
  (r/insert! (laf/map->AllGivenDocuments {:app-id ?app-id :docs ?docs})))

(r/defrule collect-app-req-docs
  [?app <- Application (= ?app-id app-id)]
  [?docs <- (acc/all) :from [RequiredDocument (= ?app-id app-id)]]
  =>
  (r/insert! (laf/map->AllRequiredDocuments {:app-id ?app-id :docs ?docs})))

;; The boundary hop the demo uses to exercise the full provenance chain:
;; the rule's RHS calls this helper, which holds the `insert!` — so the
;; callsite carries both `:rule-to-boundary-path` (rule → helper) and
;; `:boundary-to-constructor-path` (helper → ->document-check-input → ->fact).
(defn insert-document-check-input! [data]
  (r/insert! (->document-check-input data)))

(r/defrule collect-app-doc-check-input
  [Application (= ?app-id app-id)]
  [AllGivenDocuments (= ?app-id app-id) (= ?given-docs docs)]
  [AllRequiredDocuments (= ?app-id app-id) (= ?required-docs docs)]
  =>
  (let [given-doc-types (into #{} (map :doc-type) ?given-docs)]
    (insert-document-check-input! {:app-id ?app-id
                                   :required-docs ?required-docs
                                   :given-docs ?given-docs
                                   :missing-required-docs (into []
                                                                (remove (comp given-doc-types :doc-type))
                                                                ?required-docs)})))

(r/defrule app-has-all-required-docs
  [Application (= ?app-id app-id)]
  [:loan-doc-rules/document-check-input
   [{:keys [app-id required-docs given-docs missing-required-docs]}]
   (= ?app-id app-id)
   (= ?required-docs required-docs)
   (= ?given-docs given-docs)
   (= ?missing-required-docs missing-required-docs)]
  =>
  (let [status (if (seq ?missing-required-docs)
                 :fail
                 :pass)
        message (case status
                  :fail "Missing required documents"
                  "All required documents found")]
    (r/insert! (laf/map->DocumentCheck {:app-id ?app-id
                                        :status status
                                        :message message}))))

(r/defrule collect-all-missing-required-docs
  ;; NOTE: This rule has :clara-rules/no-output-types true declared in the
  ;; annotation sidecar, indicating it has been vetted as having no downstream
  ;; effects (pure side-effect). It exists to test that the no-output-types
  ;; annotation prevents :unlinked-rule detection.
  [:loan-doc-rules/document-check-input
   [{:keys [app-id missing-required-docs]}]
   (= ?app-id app-id)
   (= ?missing-required-docs missing-required-docs)
   (seq ?missing-required-docs)]
  =>
  (swap! count-atom inc))

(r/defquery find-document-check
  [:?app-id]
  [?document-check <- DocumentCheck (= ?app-id app-id)])

;; ---------------------------------------------------------------------------
;; Dynamic rule examples — demonstrate callsite capture for the demo
;; These fire after a document check passes and insert/retract loan-processing
;; fact types via dynamic constructors (Java ctor, metadata-map, opaque builder).
;; ---------------------------------------------------------------------------

(r/defrule dynamic-insert-compliance-review
  "After document checks pass, trigger a compliance review via Java constructor.
   The analyzer sees the ComplianceReview. call but cannot statically determine
   its return type — captured as a dynamic insert callsite."
  [DocumentCheck (= ?app-id app-id) (= status :pass)]
  =>
  (r/insert! (build-compliance-review ?app-id)))

(r/defrule dynamic-insert-compliance-metadata
  "Same compliance review, but built via metadata-map style.
   The analyzer captures the with-meta form as a dynamic callsite."
  [DocumentCheck (= ?app-id app-id) (= status :pass)]
  =>
  (r/insert! (build-compliance-via-metadata ?app-id)))

(r/defrule dynamic-retract-stale-notice
  "When doc check passes, retract any stale-document notice via Java constructor.
   The analyzer captures the retract callsite with source coordinates."
  [DocumentCheck (= ?app-id app-id) (= status :pass)]
  =>
  (r/retract! (StaleDocumentNotice. ?app-id :paystub "no-longer-needed")))

(r/defrule dynamic-insert-audit-trail
  "After document checks pass, write an audit trail entry via opaque builder.
   The analyzer detects an insert but cannot resolve the fact type — the
   builder is a custom non-record function. This stays unresolved."
  [DocumentCheck (= ?app-id app-id) (= status :pass)]
  =>
  (r/insert! (build-audit-trail-entry ?app-id :doc-check-passed)))
