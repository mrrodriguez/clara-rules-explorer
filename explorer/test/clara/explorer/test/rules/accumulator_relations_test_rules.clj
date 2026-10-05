(ns clara.explorer.test.rules.accumulator-relations-test-rules
  "Accumulator inspection fixtures.  Accumulator `:from` inputs must be part of
   working-memory inspection: retained in `:all-facts`, reported in
   `:matches-condition-of`, and attributed in `:supports-insertions-of`.  One
   rule inserts an accumulator-input fact of a type read only by an accumulator
   `:from` condition, so retention cannot lean on a join/negation element."
  (:require [clara.rules :as r]
            [clara.rules.accumulators :as acc]))

(defrecord Start [group threshold])
(defrecord AccumSource [group size])
(defrecord AccumResult [group n])

;; Inserts an accumulator-input fact of a type read only by accumulator `:from`
;; conditions — no positive or negated condition reads AccumSource directly.
(r/defrule insert-accum-source
  [Start (= ?group group)]
  =>
  (r/insert! (->AccumSource ?group 1)))

;; Simple accumulator (equality join only): compiles to AccumulateNode.
(r/defrule accumulate-sources
  [Start (= ?group group)]
  [?sources <- (acc/all) :from [AccumSource (= ?group group)]]
  =>
  (r/insert! (->AccumResult ?group (count ?sources))))

;; Non-equality join filter on the accumulator `:from`: compiles to
;; AccumulateWithJoinFilterNode.
(r/defrule accumulate-over-threshold
  [Start (= ?group group) (= ?threshold threshold)]
  [?sources <- (acc/all) :from [AccumSource (= ?group group) (> size ?threshold)]]
  =>
  (r/insert! (->AccumResult ?group (count ?sources))))
