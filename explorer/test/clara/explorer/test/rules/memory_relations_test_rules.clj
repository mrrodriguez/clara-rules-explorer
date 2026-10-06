(ns clara.explorer.test.rules.memory-relations-test-rules
  "Working-memory relation fixtures. Each rule is one way a fact takes part in a
   production without appearing in a recorded activation, beside a rule where it
   does. Every logical rule inserts a Marker so its activation is recorded."
  (:require [clara.rules :as r]))

(defrecord Application [app-id max-apr])
(defrecord ManualHold [reason])
(defrecord MissingDocument [app-id doc-type])
(defrecord LoanOffer [app-id apr])
(defrecord DocumentCheck [app-id status])
(defrecord AuditEntry [app-id action])
(defrecord ReviewTask [app-id])
(defrecord ReviewClosed [app-id])
(defrecord Marker [rule app-id])

;; Negation, no join bindings: any ManualHold blocks every application.
(r/defrule ready-for-review
  [Application (= ?app-id app-id)]
  [:not [ManualHold]]
  =>
  (r/insert! (->Marker :ready-for-review ?app-id)))

;; Negation joined on a binding: a MissingDocument blocks only its own application.
(r/defrule documents-complete
  [Application (= ?app-id app-id)]
  [:not [MissingDocument (= ?app-id app-id)]]
  =>
  (r/insert! (->Marker :documents-complete ?app-id)))

;; Negation with a join filter: blocking depends on comparing two facts.
(r/defrule offers-within-limit
  [Application (= ?app-id app-id) (= ?max-apr max-apr)]
  [:not [LoanOffer (= ?app-id app-id) (> apr ?max-apr)]]
  =>
  (r/insert! (->Marker :offers-within-limit ?app-id)))

;; Positive join, then a failed test.
(r/defrule document-check-passed
  [Application (= ?app-id app-id)]
  [DocumentCheck (= ?app-id app-id) (= ?status status)]
  [:test (= :passed ?status)]
  =>
  (r/insert! (->Marker :document-check-passed ?app-id)))

;; Fires, but makes no logical insertion.
(r/defrule audit-application
  [Application (= ?app-id app-id)]
  =>
  (r/insert-unconditional! (->AuditEntry ?app-id :seen)))

;; A logical insertion that a later rule retracts with retract!.
(r/defrule open-review-task
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (->ReviewTask ?app-id)))

(r/defrule close-review-task
  [ReviewClosed (= ?app-id app-id)]
  [?task <- ReviewTask (= ?app-id app-id)]
  =>
  (r/retract! ?task))

;; A query with a negation.
(r/defquery applications-without-hold
  []
  [?app <- Application]
  [:not [ManualHold]])
