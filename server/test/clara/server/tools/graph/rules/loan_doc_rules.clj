(ns clara.server.tools.graph.rules.loan-doc-rules
  (:require [clara.rules :as r]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.rules.accumulators :as acc])
  (:import
   [clara.server.tools.graph.rules.loan_app_facts
    Application
    GivenDocument
    AllGivenDocuments
    RequiredDocument
    AllRequiredDocuments
    DocumentCheckInput
    DocumentCheck]))

(def count-atom (atom 0))

(defrecord AllIdCardGivenDocuments [app-id docs])

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

(r/defrule collect-app-doc-check-input
  [Application (= ?app-id app-id)]
  [AllGivenDocuments (= ?app-id app-id) (= ?given-docs docs)]
  [AllRequiredDocuments (= ?app-id app-id) (= ?required-docs docs)]
  =>
  (let [given-doc-types (into #{} (map :doc-type) ?given-docs)]
    (r/insert! (laf/map->DocumentCheckInput {:app-id ?app-id
                                             :required-docs ?required-docs
                                             :given-docs ?given-docs
                                             :missing-required-docs (into []
                                                                          (remove (comp given-doc-types :doc-type))
                                                                          ?required-docs)}))))

(r/defrule app-has-all-required-docs
  [Application (= ?app-id app-id)]
  [DocumentCheckInput (= ?app-id app-id)
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
  [DocumentCheckInput (= ?app-id app-id)
   (= ?missing-required-docs missing-required-docs)
   (seq ?missing-required-docs)]
  =>
  (swap! count-atom inc))

(r/defquery find-document-check
  [:?app-id]
  [?document-check <- DocumentCheck (= ?app-id app-id)])
