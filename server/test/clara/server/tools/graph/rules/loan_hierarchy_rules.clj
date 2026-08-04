(ns clara.server.tools.graph.rules.loan-hierarchy-rules
  "Loan-domain rules exercising a keyword `derive` hierarchy, vector-tuple fact
   types, and a record fact type in one session: plain keywords (majority
   case), vector tuples of keyword-led forms (minor secondary), and
   class/record facts (minor).  Sessions must be built with `fact-type-fn` so
   tuple types resolve; the keyword hierarchy flows through the default
   ancestors-fn (clojure.core/ancestors on the global hierarchy)."
  (:require [clara.rules :as r]))

;; Keyword hierarchy: income-document <: supporting-document <: loan-document
;; <: base-document.  ::base-document is deliberately never on an LHS so it
;; appears only as a ghost (known: false) ancestor.
(derive ::income-document ::supporting-document)
(derive ::supporting-document ::loan-document)
(derive ::loan-document ::base-document)

(defrecord LoanApplication [app-id status])

(defn fact-type-fn
  "Session fact-type fn: honors `with-meta` :type, treats keyword-led vectors
   as tuple types (the vector itself is the type tag), falls back to the
   fact's class."
  [fact]
  (or (:type (meta fact))
      (and (vector? fact) (seq fact) (keyword? (first fact)) fact)
      (class fact)))

;; -- primary: keyword hierarchy --
;; Reads the LoanApplication record, inserts a derived keyword type.
(r/defrule insert-income-document
  {:clara-rules/insert-types [::income-document]}
  [LoanApplication (= ?app-id app-id)]
  =>
  (r/insert! (with-meta {:app-id ?app-id :doc :w2}
               {:type ::income-document})))

;; Consumes the derived type via its ancestor keyword; inserts an underived
;; keyword type (whose ancestors are empty).
(r/defrule review-supporting-document
  {:clara-rules/insert-types [::document-reviewed]}
  [?d <- ::supporting-document]
  =>
  (r/insert! (with-meta {:reviewed (:doc ?d)}
               {:type ::document-reviewed})))

;; -- secondary: vector-tuple fact types --
(r/defrule verify-income-status
  {:clara-rules/insert-types [[:loan/status "verified"]]}
  [::supporting-document]
  =>
  (r/insert! (with-meta [:loan/status "verified"]
               {:type [:loan/status "verified"]})))

(r/defrule flag-income-mismatch
  {:clara-rules/insert-types [[:document/flag "income-mismatch"]]}
  [?s <- [:loan/status "verified"]]
  =>
  (r/insert! (with-meta [:document/flag "income-mismatch"]
               {:type [:document/flag "income-mismatch"]})))

;; -- queries --
;; Reads the top of the keyword hierarchy (ancestor of ::income-document).
(r/defquery find-loan-documents []
  [?d <- ::loan-document])

;; Reads an interface ancestor of LoanApplication — makes it known: true in
;; LoanApplication's :ancestors (Java interface hierarchy case).
(r/defquery find-map-facts []
  [?m <- clojure.lang.IPersistentMap])
