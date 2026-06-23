(ns clara.server.tools.graph.rules.loan-app-facts)

(defrecord Application [app-id])
(defrecord LoanOffer [app-id apr term term-unit loan-amount])

(defrecord GivenDocument [app-id doc-type])
(defrecord AllGivenDocuments [app-id docs])

(defrecord RequiredDocument [app-id doc-type])
(defrecord AllRequiredDocuments [app-id docs])

(defrecord DocumentCheckInput [app-id required-docs given-docs missing-required-docs])

(defrecord MissingRequiredDocument [app-id doc-type])

(defrecord DocumentCheck [app-id status message])

(defrecord IdentityCheck [app-id status message])

(defrecord FraudCheck [app-id status message])
