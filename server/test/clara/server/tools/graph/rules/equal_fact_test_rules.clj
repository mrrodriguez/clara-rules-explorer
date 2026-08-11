(ns clara.server.tools.graph.rules.equal-fact-test-rules
  "Two rules that insert EQUAL but distinct facts, for attribution testing.

   Equal-by-value facts are ordinary in real rulesets — two rules deriving the
   same conclusion from different premises — and they are the general case of
   the aliasing that `nil` shows in the extreme: any index keyed by the fact
   rather than by the insertion merges them."
  (:require [clara.rules :as r]))

(defrecord Seed [id])
(defrecord Derived [tag])

(r/defrule inserts-equal-a
  [Seed (= ?id id)]
  =>
  (r/insert! (->Derived "same")))

(r/defrule inserts-equal-b
  [Seed (= ?id id)]
  =>
  (r/insert! (->Derived "same")))
