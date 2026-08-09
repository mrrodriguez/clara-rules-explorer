(ns clara.server.tools.graph.rules.nil-safety-test-rules
  "Minimal rules for nil-safety regression testing."
  (:require [clara.rules :as r]))

;; A simple fact type for the rule to match on.
(defrecord NilSafetyFact [id])

(r/defrule nil-insertion-rule
  "RHS inserts nil — the entry point for untypable facts in the analysis pipeline.
   nil has no usable fact-type-fn result, so it matches no alpha root and sits
   inertly in insertion memory."
  [NilSafetyFact (= ?id id)]
  =>
  (r/insert! nil))
